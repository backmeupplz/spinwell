import Foundation
import UIKit

/// Streams one URL into `<dest>.part`, resuming with `Range` when a partial file exists, then renames it to `dest`.
/// A server that ignores the range (200) restarts the file from zero.
final class PartDownload: NSObject, URLSessionDataDelegate {
    private let dest: URL, part: URL
    private var handle: FileHandle?
    private var cont: CheckedContinuation<Void, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var written: Int64 = 0
    private var failure: Error?
    private var skipBody = false
    private let progress: @Sendable (Int64) -> Void

    init(dest: URL, progress: @escaping @Sendable (Int64) -> Void = { _ in }) {
        self.dest = dest; self.part = dest.appendingPathExtension("part"); self.progress = progress
    }

    static func partSize(_ dest: URL) -> Int64 { dest.appendingPathExtension("part").fileSizeValue }

    func run(_ url: URL) async throws {
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        written = Self.partSize(dest)
        var r = URLRequest(url: url)
        r.timeoutInterval = 30
        if written > 0 { r.setValue("bytes=\(written)-", forHTTPHeaderField: "Range") }
        let s = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
        session = s
        defer { s.finishTasksAndInvalidate(); try? handle?.close() }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                cont = c
                task = s.dataTask(with: r)
                task?.resume()
            }
        } onCancel: { task?.cancel() }
    }

    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        do {
            if code == 416 { // our .part is already the whole file (or garbage): keep it only if it is the whole file
                skipBody = true
                let total = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last.flatMap { Int64($0) }
                if total == written { try finish() } else { try? FileManager.default.removeItem(at: part); fail(URLError(.resourceUnavailable)) }
                completionHandler(.cancel); return
            }
            guard (200...299).contains(code) else { skipBody = true; fail(ApiError.http(code)); completionHandler(.cancel); return }
            if code == 200 { written = 0; try? FileManager.default.removeItem(at: part) } // no range support: start over
            if !FileManager.default.fileExists(atPath: part.path) { FileManager.default.createFile(atPath: part.path, contents: nil) }
            handle = try FileHandle(forWritingTo: part)
            try handle?.seekToEnd()
            completionHandler(.allow)
        } catch { fail(error); completionHandler(.cancel) }
    }

    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do { try handle?.write(contentsOf: data); written += Int64(data.count); progress(written) } catch { fail(error); dataTask.cancel() }
    }

    func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let c = cont else { return }
        if let f = failure { cont = nil; c.resume(throwing: f); return }
        if let error { cont = nil; c.resume(throwing: error); return }
        do { try finish() } catch { fail(error) }
        cont = nil
        if let f = failure { c.resume(throwing: f) } else { c.resume() }
    }

    private func fail(_ e: Error) { if failure == nil { failure = e } }

    private func finish() throws {
        try? handle?.close(); handle = nil
        if FileManager.default.fileExists(atPath: dest.path) { try FileManager.default.removeItem(at: dest) }
        try FileManager.default.moveItem(at: part, to: dest)
    }
}

struct Pinned: Codable, Hashable, Identifiable {
    var kind: String // album | playlist | artist
    var cid: String
    var name: String
    var coverArt: String?
    var songIds: [String]
    var id: String { kind + ":" + cid }
}

struct DlState: Codable {
    var songs: [String: Song] = [:]
    var pinned: [Pinned] = []
    var queue: [String: Song] = [:] // pending, by song id
    var order: [String] = []
}

/// Downloads: the unit is the song, stored once under `media/<scope>/<id>.<ext>`. `downloads.json` carries the song
/// metadata so offline mode can list and play everything.
@MainActor @Observable final class Downloads {
    var state = DlState() { didSet { if state.songs.count != oldValue.songs.count { albumIds = Set(state.songs.values.compactMap(\.albumId)) } } }
    /// albums with at least one downloaded song (offline dimming)
    var albumIds: Set<String> = []
    var current: String?
    var got: Int64 = 0
    var paused = false
    var waitingFor: String? // "Wi-Fi" | "server"
    var failures = 0
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var loadedScope = ""

    private var jsonFile: URL { supportDir.appending(path: "downloads-\(app.scope).json") }
    var mediaDir: URL {
        let u = URL.applicationSupportDirectory.appending(path: "media/" + app.scope)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        var parent = URL.applicationSupportDirectory.appending(path: "media")
        var v = URLResourceValues(); v.isExcludedFromBackup = true // re-downloadable: keep it out of iCloud
        try? parent.setResourceValues(v)
        return u
    }

    func ext(_ s: Song) -> String { Quality.ext(suffix: s.suffix, bitrate: app.settings.dlQuality) }
    func file(_ s: Song) -> URL { mediaDir.appending(path: "\(s.id).\(ext(s))") }
    /// A finished file for this id, whatever extension it was saved with.
    func localURL(_ id: String) -> URL? {
        guard let s = state.songs[id] else { return nil }
        let f = mediaDir.appending(path: "\(id).\(savedExt[id] ?? ext(s))")
        return FileManager.default.fileExists(atPath: f.path) ? f : nil
    }
    private var savedExt: [String: String] {
        get { Dictionary(uniqueKeysWithValues: (UserDefaults.standard.dictionary(forKey: "dlext-" + app.scope) as? [String: String] ?? [:]).map { ($0, $1) }) }
        set { UserDefaults.standard.set(newValue, forKey: "dlext-" + app.scope) }
    }

    func has(_ id: String) -> Bool { state.songs[id] != nil }
    func queued(_ id: String) -> Bool { state.queue[id] != nil }
    var songs: [Song] { state.songs.values.sorted { ($0.artist ?? "", $0.album ?? "", $0.discNumber ?? 1, $0.track ?? 0) < ($1.artist ?? "", $1.album ?? "", $1.discNumber ?? 1, $1.track ?? 0) } }
    var pending: Int { state.order.count }
    var active: Bool { !state.order.isEmpty }

    func reload() {
        guard loadedScope != app.scope else { return }
        loadedScope = app.scope
        state = (try? JSONDecoder().decode(DlState.self, from: Data(contentsOf: jsonFile))) ?? DlState()
        // drop entries whose files vanished
        for id in state.songs.keys where localURL(id) == nil { state.songs[id] = nil }
        kick()
    }

    private func save() {
        let s = state, f = jsonFile
        Task.detached { try? JSONEncoder().encode(s).write(to: f) }
    }

    /// Container state for the album/playlist/artist buttons.
    func progress(_ songs: [Song]) -> (done: Int, total: Int) { (songs.filter { has($0.id) }.count, songs.count) }
    func isPinned(_ kind: String, _ id: String) -> Bool { state.pinned.contains { $0.id == kind + ":" + id } }

    func add(_ songs: [Song], pin: Pinned? = nil) {
        if var p = pin {
            p.songIds = songs.map(\.id)
            state.pinned.removeAll { $0.id == p.id }
            state.pinned.append(p)
        }
        for s in songs where !has(s.id) && !queued(s.id) { state.queue[s.id] = s; state.order.append(s.id) }
        save(); paused = false; kick()
    }

    /// New songs of pinned containers get picked up when the container is refreshed.
    func sync(_ kind: String, _ id: String, songs: [Song]) {
        guard isPinned(kind, id) else { return }
        let p = state.pinned.first { $0.id == kind + ":" + id }!
        add(songs, pin: Pinned(kind: kind, cid: id, name: p.name, coverArt: p.coverArt, songIds: []))
    }

    func remove(_ id: String) {
        if let u = localURL(id) { try? FileManager.default.removeItem(at: u) }
        try? FileManager.default.removeItem(at: (mediaDir.appending(path: "\(id).\(state.songs[id].map(ext) ?? "mp3")")).appendingPathExtension("part"))
        state.songs[id] = nil; state.queue[id] = nil; state.order.removeAll { $0 == id }
        for i in state.pinned.indices { state.pinned[i].songIds.removeAll { $0 == id } }
        state.pinned.removeAll { $0.songIds.isEmpty && $0.kind != "none" }
        if current == id { worker?.cancel() }
        save()
    }

    func removeContainer(_ p: Pinned) {
        state.pinned.removeAll { $0.id == p.id }
        let still = Set(state.pinned.flatMap(\.songIds))
        for id in p.songIds where !still.contains(id) { remove(id) }
        save()
    }

    func removeAll() {
        worker?.cancel()
        try? FileManager.default.removeItem(at: mediaDir)
        state = DlState(); save()
    }

    func pauseAll() { paused = true; worker?.cancel() }
    func resumeAll() { paused = false; kick() }

    var storageUsed: Int64 { dirSize(mediaDir) }

    func kick() {
        guard worker == nil, !paused, !state.order.isEmpty, app.account != nil else { return }
        worker = Task { await loop(); worker = nil }
    }

    private func loop() async {
        var delay = 2.0
        while !Task.isCancelled, let id = state.order.first, !paused {
            guard let song = state.queue[id] else { state.order.removeFirst(); continue }
            if app.cellular && !app.settings.dlCellular { waitingFor = "Wi-Fi"; try? await Task.sleep(for: .seconds(3)); continue }
            if app.offline { waitingFor = "server"; try? await Task.sleep(for: .seconds(3)); continue }
            waitingFor = nil
            current = id; got = 0
            let bg = UIApplication.shared.beginBackgroundTask { }
            defer { UIApplication.shared.endBackgroundTask(bg) }
            do {
                let q = app.settings.dlQuality
                guard let a = app.account, let url = a.streamURL(song.id, suffix: song.suffix, bitrate: q) else { throw ApiError.notSignedIn }
                // The original file comes from `download` (honors ranges); transcodes come from `stream`.
                let source = q == 0 && Quality.nativeSuffixes.contains((song.suffix ?? "").lowercased()) ? (a.url("download", [("id", song.id)]) ?? url) : url
                let dest = file(song)
                try await PartDownload(dest: dest) { [weak self] n in Task { @MainActor in self?.got = n } }.run(source)
                var ex = savedExt; ex[song.id] = dest.pathExtension; savedExt = ex
                state.songs[id] = song; state.queue[id] = nil; state.order.removeAll { $0 == id }
                var v = URLResourceValues(); v.isExcludedFromBackup = true
                var d = dest; try? d.setResourceValues(v)
                save()
                if let c = song.coverArt { await Covers.prefetch(c) }
                delay = 2; failures = 0
            } catch {
                current = nil
                if Task.isCancelled { break }
                failures += 1
                if (error as? URLError)?.code != .cancelled { try? await Task.sleep(for: .seconds(delay)) }
                delay = min(delay * 2, 60)
            }
            current = nil
        }
        current = nil; got = 0; waitingFor = nil
    }
}
