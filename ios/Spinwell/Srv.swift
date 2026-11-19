import Foundation
import Network
import Security
import SwiftUI

let supportDir: URL = {
    let u = URL.applicationSupportDirectory.appending(path: "spinwell")
    try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
    return u
}()

enum ApiError: LocalizedError {
    case offline, notSignedIn, server(Int, String), http(Int), unreachable
    var errorDescription: String? {
        switch self {
        case .offline: String(localized: "You're offline.")
        case .notSignedIn: String(localized: "Not signed in.")
        case .server(_, let m): m
        case .http(let c): c == 404 ? String(localized: "That doesn't look like a Subsonic server.") : String(localized: "That doesn't look like a Subsonic server (HTTP \(c)).")
        case .unreachable: String(localized: "Can't reach the server. Check the address and your connection.")
        }
    }
}

/// Small settings object. Every key lives in UserDefaults; `@Observable` makes the UI follow.
@MainActor @Observable final class Settings {
    private let d = UserDefaults.standard
    var wifiQuality: Int { didSet { d.set(wifiQuality, forKey: "wifiQuality") } }
    var cellQuality: Int { didSet { d.set(cellQuality, forKey: "cellQuality") } }
    var dlQuality: Int { didSet { d.set(dlQuality, forKey: "dlQuality") } }
    var dlCellular: Bool { didSet { d.set(dlCellular, forKey: "dlCellular") } }
    var resumeOnOpen: Bool { didSet { d.set(resumeOnOpen, forKey: "resumeOnOpen") } }
    var offlineMode: Bool { didSet { d.set(offlineMode, forKey: "offlineMode"); app.settingsChanged() } }
    var scrobble: Bool { didSet { d.set(scrobble, forKey: "scrobble") } }
    init() {
        let d = UserDefaults.standard
        func int(_ k: String, _ def: Int) -> Int { d.object(forKey: k) as? Int ?? def }
        func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) as? Bool ?? def }
        wifiQuality = int("wifiQuality", 0); cellQuality = int("cellQuality", 192); dlQuality = int("dlQuality", 0)
        dlCellular = bool("dlCellular", false); resumeOnOpen = bool("resumeOnOpen", false)
        offlineMode = bool("offlineMode", false); scrobble = bool("scrobble", true)
    }
}

/// An action made while offline, replayed in order.
struct Pending: Codable { var kind: String; var id: String; var at: Double }

@MainActor let app = Srv()

/// Server access: account in the Keychain, JSON cache, connectivity, outbox.
@MainActor @Observable final class Srv {
    var account: Account?
    var signInError: String?
    /// The server doesn't answer. Flipping it reloads every screen (they key their `.task` on `offline`) and the player's preload.
    var unreachable = false { didSet { if unreachable != oldValue { player.refreshPreload() } } }
    var cellular = false
    var extensions: Set<String> = []
    var serverVersion = ""
    /// star overrides until the server confirms (true = starred)
    var stars: [String: Bool] = [:]
    let settings = Settings()
    var dl: Downloads { downloads }
    @ObservationIgnored lazy var downloads = Downloads()
    @ObservationIgnored private var outbox: [Pending] = []
    @ObservationIgnored private let monitor = NWPathMonitor()
    @ObservationIgnored private var lastPath: NWPath.Status?
    @ObservationIgnored let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    var offline: Bool { settings.offlineMode || unreachable }
    var scope: String { account?.scope ?? "none" }
    var signedIn: Bool { account != nil }

    init() {
        account = Self.kcRead()
        outbox = (try? JSONDecoder().decode([Pending].self, from: Data(contentsOf: supportDir.appending(path: "outbox.json")))) ?? []
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self else { return }
                self.cellular = path.isExpensive
                if self.lastPath != nil, self.lastPath != path.status || path.status == .satisfied { Task { await self.ping() } }
                self.lastPath = path.status
            }
        }
        monitor.start(queue: .main)
        // while offline, look again every 20 s; while online and in front every 10 s, to notice a server that went away when nothing is being
        // requested; coming to the foreground checks right away
        Task { @MainActor [weak self] in
            var n = 0
            while true {
                try? await Task.sleep(for: .seconds(5))
                guard let self else { return }
                n += 1
                if self.unreachable ? n % 4 == 0 : (n % 2 == 0 && UIApplication.shared.applicationState == .active) { await self.ping(confirm: !self.unreachable) }
            }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.ping() }
        }
    }

    /// Network errors that mean "the server isn't there" (also found under AVFoundation's wrapper errors).
    nonisolated static let netCodes: Set<URLError.Code> = [.cannotConnectToHost, .timedOut, .notConnectedToInternet, .networkConnectionLost, .cannotFindHost,
                                                           .dnsLookupFailed, .secureConnectionFailed, .dataNotAllowed, .internationalRoamingOff, .cannotLoadFromNetwork]
    nonisolated static func isNetwork(_ e: Error) -> Bool {
        let ns = e as NSError
        if ns.domain == NSURLErrorDomain, netCodes.contains(URLError.Code(rawValue: ns.code)) { return true }
        if let u = ns.userInfo[NSUnderlyingErrorKey] as? Error { return isNetwork(u) }
        return false
    }
    func markUnreachable() { unreachable = true }

    func settingsChanged() { if !settings.offlineMode { Task { await ping() } } }

    // MARK: sign in / out

    func signIn(server: String, user: String, password: String) async {
        signInError = nil
        let a = Account.make(server: server, user: user.trimmingCharacters(in: .whitespaces), password: password)
        guard !user.isEmpty, !password.isEmpty, URL(string: a.server)?.host() != nil else { signInError = String(localized: "Enter your server address, username and password."); return }
        do {
            guard let url = a.url("ping") else { throw ApiError.http(0) }
            var r = URLRequest(url: url); r.timeoutInterval = 10
            let (d, resp) = try await session.data(for: r)
            if let c = (resp as? HTTPURLResponse)?.statusCode, c != 200 { throw ApiError.http(c) }
            guard let env = try? JSONDecoder().decode(Envelope<Empty>.self, from: d) else { throw ApiError.http(404) }
            if env.status != "ok" {
                let code = env.error?.code ?? 0
                throw ApiError.server(code, code == 40 || code == 41 ? String(localized: "Wrong username or password.") : env.error?.message ?? String(localized: "Couldn't sign in."))
            }
            Self.kcWrite(a)
            account = a
            unreachable = false
            didSignIn()
        } catch let e as ApiError { signInError = e.errorDescription }
        catch let e as URLError {
            signInError = e.code == .appTransportSecurityRequiresSecureConnection ? String(localized: "This server needs a secure connection.") : String(localized: "Can't reach the server. Check the address and your connection.")
        } catch { signInError = error.localizedDescription }
    }

    func didSignIn() {
        downloads.reload()
        Task { await loadExtensions(); await flushOutbox() }
    }

    func signOut(message: String? = nil) {
        player.stopAndClear()
        Self.kcWrite(nil)
        account = nil
        signInError = message
        downloads.pauseAll()
        Covers.clearMemory()
    }

    // MARK: connectivity

    /// One `ping` (4 s timeout). `confirm` retries once after a second before declaring the server gone: the periodic probe of an
    /// online app must not drop a preloaded song because of one slow answer. Requests that really fail flip at once without it.
    func ping(confirm: Bool = false) async {
        guard account != nil, !settings.offlineMode else { return }
        var ok = await probe()
        if ok == false, confirm { try? await Task.sleep(for: .seconds(1)); ok = await probe() }
        if ok == true { reachable() } else if ok == false { unreachable = true }
    }

    /// true = answered, false = did not, nil = cancelled
    private func probe() async -> Bool? {
        guard let url = account?.url("ping") else { return false }
        var r = URLRequest(url: url); r.timeoutInterval = 4
        do {
            let (d, _) = try await session.data(for: r)
            guard let env = try? JSONDecoder().decode(Envelope<Empty>.self, from: d), env.status == "ok" else { return false }
            serverVersion = [env.type?.capitalized, env.serverVersion?.split(separator: " ").first.map(String.init)].compactMap { $0 }.joined(separator: " ")
            return true
        } catch { return (error as? URLError)?.code == .cancelled ? nil : false }
    }

    func reachable() {
        guard unreachable else { return }
        unreachable = false
        Task { await loadExtensions(); await flushOutbox(); downloads.kick() }
    }

    func loadExtensions() async {
        if let r = try? await fetch("getOpenSubsonicExtensions", [], ExtensionsR.self) {
            extensions = Set((r.openSubsonicExtensions ?? []).map(\.name))
        }
    }

    // MARK: requests

    nonisolated static func decode<T: Decodable>(_ d: Data, _ t: T.Type) async throws -> T {
        let env = try JSONDecoder().decode(Envelope<T>.self, from: d)
        guard env.status == "ok", let b = env.body else { throw ApiError.server(env.error?.code ?? 0, env.error?.message ?? String(localized: "The request failed.")) }
        return b
    }

    func raw(_ method: String, _ params: [(String, String)]) async throws -> Data {
        guard let a = account, let url = a.url(method, params) else { throw ApiError.notSignedIn }
        if offline { throw ApiError.offline }
        do {
            var r = URLRequest(url: url); r.timeoutInterval = 10
            let (d, resp) = try await session.data(for: r)
            if let c = (resp as? HTTPURLResponse)?.statusCode, c != 200 {
                if [502, 503, 504].contains(c) { unreachable = true } // the proxy in front of the server is up, the server isn't
                throw ApiError.http(c)
            }
            reachable()
            return d
        } catch let e as URLError where e.code != .cancelled {
            if Self.isNetwork(e) { unreachable = true }
            throw e
        }
    }

    func fetch<T: Decodable>(_ method: String, _ params: [(String, String)] = [], _ t: T.Type) async throws -> T {
        let d = try await raw(method, params)
        do { return try await Self.decode(d, t) }
        catch let ApiError.server(code, m) {
            if code == 40 || code == 41 { signOut(message: m) }
            throw ApiError.server(code, m)
        }
    }

    // MARK: JSON cache (stale-while-revalidate)

    private var cacheDir: URL { URL.cachesDirectory.appending(path: "json/" + scope) }
    private func cacheFile(_ method: String, _ p: [(String, String)]) -> URL {
        cacheDir.appending(path: Account.md5(Account.cacheKey(method, p)))
    }

    func cacheSize() -> Int64 {
        let urls = [URL.cachesDirectory.appending(path: "json"), URL.cachesDirectory.appending(path: "covers")]
        return urls.reduce(0) { $0 + dirSize($1) }
    }

    func clearCache() {
        try? FileManager.default.removeItem(at: URL.cachesDirectory.appending(path: "json"))
        Covers.clearAll()
    }

    /// The cached response read synchronously, so a screen's very first frame already has its content (small files only).
    func cached<T: Decodable>(_ method: String, _ params: [(String, String)] = [], _ t: T.Type) -> T? {
        guard let d = try? Data(contentsOf: cacheFile(method, params)) else { return nil }
        return try? JSONDecoder().decode(Envelope<T>.self, from: d).body
    }

    /// Render the cached copy right away, then the fresh one if it differs. Returns the error of the refresh, if any.
    @discardableResult
    func load<T: Decodable>(_ method: String, _ params: [(String, String)] = [], _ t: T.Type, apply: (T) -> Void) async -> Error? {
        let file = cacheFile(method, params)
        let old = await Task.detached { try? Data(contentsOf: file) }.value
        if let old, let v = try? await Self.decode(old, t) { apply(v) }
        if Task.isCancelled { return CancellationError() }
        do {
            let d = try await raw(method, params)
            if d != old {
                let v = try await Self.decode(d, t)
                apply(v)
                let dir = cacheDir
                Task.detached { try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true); try? d.write(to: file) }
            }
            return nil
        } catch ApiError.offline { return old == nil ? ApiError.offline : nil }
        catch { return error is CancellationError || (error as? URLError)?.code == .cancelled ? nil : error }
    }

    // MARK: media

    func cover(_ id: String, size: Int) -> URL? { account?.coverURL(id, size: size) }

    /// `transcode`: the original failed to play, so Original quality asks for MP3 320 (the one conversion every server has).
    func stream(_ s: Song, transcode: Bool = false) -> URL? {
        let q = cellular ? settings.cellQuality : settings.wifiQuality
        return account?.streamURL(s.id, suffix: s.suffix, bitrate: transcode && q == 0 ? 320 : q)
    }

    func playable(_ s: Song) -> Bool { downloads.has(s.id) || !offline }

    // MARK: stars + scrobbles with an outbox

    func isStarred(_ id: String, _ server: Bool) -> Bool { stars[id] ?? server }
    func isStarred(_ s: Song) -> Bool { isStarred(s.id, s.isStarred) }

    func toggleStar(_ id: String, now: Bool, kind: String = "id") {
        stars[id] = now
        send(Pending(kind: (now ? "star" : "unstar") + ":" + kind, id: id, at: Date().timeIntervalSince1970))
    }

    func scrobble(_ id: String, submission: Bool, at: Double? = nil) {
        guard settings.scrobble else { return }
        send(Pending(kind: submission ? "scrobble" : "now", id: id, at: at ?? Date().timeIntervalSince1970))
    }

    private func send(_ p: Pending) {
        if p.kind == "now" { Task { _ = try? await raw("scrobble", [("id", p.id), ("submission", "false")]) }; return } // now-playing pings aren't worth keeping
        outbox.append(p); saveOutbox()
        Task { await flushOutbox() }
    }

    private var flushing = false
    func flushOutbox() async {
        guard !flushing, !offline, account != nil else { return }
        flushing = true; defer { flushing = false }
        while let p = outbox.first {
            do {
                switch p.kind.split(separator: ":").map(String.init) {
                case ["scrobble"]: _ = try await raw("scrobble", [("id", p.id), ("time", String(Int(p.at * 1000))), ("submission", "true")])
                case let k where k.first == "star" || k.first == "unstar":
                    let key = k.count > 1 && k[1] != "id" ? k[1] : "id" // id | albumId | artistId
                    _ = try await raw(k[0], [(key, p.id)])
                    stars[p.id] = nil
                default: break
                }
                outbox.removeFirst(); saveOutbox()
            } catch let e as ApiError { if case .server = e { outbox.removeFirst(); saveOutbox() } else { return } }
            catch { return }
        }
    }

    var pendingCount: Int { outbox.count }
    private func saveOutbox() {
        let o = outbox
        Task.detached { try? JSONEncoder().encode(o).write(to: supportDir.appending(path: "outbox.json")) }
    }

    // MARK: Keychain

    private static let kq: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "spinwell", kSecAttrAccount as String: "account"]

    private static func kcRead() -> Account? {
        var q = kq; q[kSecReturnData as String] = true
        var r: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &r) == errSecSuccess, let d = r as? Data else { return nil }
        return try? JSONDecoder().decode(Account.self, from: d)
    }

    private static func kcWrite(_ a: Account?) {
        SecItemDelete(kq as CFDictionary)
        guard let a else { return }
        var q = kq
        q[kSecValueData as String] = try? JSONEncoder().encode(a)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}

func dirSize(_ u: URL) -> Int64 {
    guard let e = FileManager.default.enumerator(at: u, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
    return e.compactMap { ($0 as? URL)?.fileSizeValue }.reduce(0, +)
}
extension URL { var fileSizeValue: Int64 { Int64((try? resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) } }
