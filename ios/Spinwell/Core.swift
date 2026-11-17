import CryptoKit
import Foundation

/// Credentials as stored: never the password, only salt + md5(password + salt).
struct Account: Codable, Equatable {
    var server: String
    var user: String
    var salt: String
    var token: String

    static func make(server: String, user: String, password: String, salt: String = randomSalt()) -> Account {
        Account(server: normalize(server), user: user, salt: salt, token: md5(password + salt))
    }

    static func randomSalt() -> String { String((0..<12).map { _ in "abcdefghijklmnopqrstuvwxyz0123456789".randomElement()! }) }
    static func md5(_ s: String) -> String { Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }

    /// Trim spaces and trailing slashes, `https://` when no scheme was typed.
    static func normalize(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        while t.hasSuffix("/") { t.removeLast() }
        if !t.isEmpty, !t.lowercased().hasPrefix("http://"), !t.lowercased().hasPrefix("https://") { t = "https://" + t }
        return t
    }

    var host: String { URL(string: server)?.host() ?? server }
    /// Per server + user, so signing back in finds the downloads again.
    var scope: String { SHA256.hash(data: Data((server + "|" + user).utf8)).prefix(8).map { String(format: "%02x", $0) }.joined() }

    private static let authKeys: Set<String> = ["u", "t", "s", "v", "c", "f", "p"]

    func auth() -> [URLQueryItem] {
        [("u", user), ("t", token), ("s", salt), ("v", "1.16.1"), ("c", "Spinwell"), ("f", "json")].map { URLQueryItem(name: $0.0, value: $0.1) }
    }

    func url(_ method: String, _ params: [(String, String)] = []) -> URL? {
        var base = server
#if DEBUG
        // Outage simulation: while /tmp/spinwell-block exists every request goes to a dead address instead
        // (file text "blackhole" = packets vanish, anything else = connection refused). The account itself is untouched.
        if let t = try? String(contentsOfFile: "/tmp/spinwell-block", encoding: .utf8) {
            base = t.contains("blackhole") ? "http://10.255.255.1" : "http://127.0.0.1:9"
        }
#endif
        guard var c = URLComponents(string: base + "/rest/" + method) else { return nil }
        c.queryItems = params.map { URLQueryItem(name: $0.0, value: $0.1) } + auth()
        return c.url
    }

    /// Method + sorted params minus auth: the JSON cache key.
    static func cacheKey(_ method: String, _ params: [(String, String)]) -> String {
        method + "?" + params.filter { !authKeys.contains($0.0) }.sorted { ($0.0, $0.1) < ($1.0, $1.1) }.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
    }
}

/// Quality choices: 0 = original.
enum Quality {
    static let nativeSuffixes: Set<String> = ["mp3", "m4a", "m4b", "aac", "alac", "flac", "wav", "aif", "aiff", "caf"]

    /// iOS rule: a song AVPlayer can't decode streams/downloads as AAC 256 (saved `.aac`); an explicit
    /// bitrate asks for MP3; otherwise the original file.
    static func params(suffix: String?, bitrate: Int) -> [(String, String)] {
        if bitrate > 0 { return [("maxBitRate", String(bitrate)), ("format", "mp3")] }
        if !nativeSuffixes.contains((suffix ?? "").lowercased()) { return [("maxBitRate", "256"), ("format", "aac")] }
        return [("maxBitRate", "0")]
    }

    static func ext(suffix: String?, bitrate: Int) -> String {
        if bitrate > 0 { return "mp3" }
        let s = (suffix ?? "").lowercased()
        return nativeSuffixes.contains(s) ? s : (s.isEmpty ? "mp3" : "aac")
    }
}

extension Account {
    func streamURL(_ id: String, suffix: String?, bitrate: Int) -> URL? {
        url("stream", [("id", id)] + Quality.params(suffix: suffix, bitrate: bitrate))
    }
    func coverURL(_ id: String, size: Int) -> URL? { url("getCoverArt", [("id", id), ("size", String(size))]) }
}

func clock(_ s: Double) -> String {
    guard s.isFinite, s >= 0 else { return "0:00" }
    let t = Int(s)
    return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
}

func minutes(_ s: Int) -> String {
    let m = max(1, (s + 30) / 60)
    if m < 60 { return String(localized: "\(m) min") }
    return m % 60 == 0 ? String(localized: "\(m / 60) h") : String(localized: "\(m / 60) h \(m % 60) min")
}

func bytes(_ b: Int64) -> String { ByteCountFormatter.string(fromByteCount: b, countStyle: .file) }

/// HTML out of biographies.
func plain(_ html: String) -> String {
    html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"")
        .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&nbsp;", with: " ")
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

extension Song {
    /// `FLAC 24/96`, `FLAC 16/44.1`, `MP3 320`, `OPUS`
    var badge: String {
        let s = (suffix ?? "").uppercased()
        let lossless = ["FLAC", "ALAC", "WAV", "AIFF", "AIF", "APE", "WV"].contains(s)
        if lossless {
            let rate = Double(samplingRate ?? 44100) / 1000
            let r = rate == rate.rounded() ? String(Int(rate)) : String(format: "%.1f", rate)
            return "\(s) \(bitDepth ?? 16)/\(r)"
        }
        if ["MP3", "M4A", "AAC"].contains(s), let b = bitRate, b > 0 { return "\(s == "M4A" ? "AAC" : s) \(b)" }
        return s
    }
    /// ranking for "mixed -> highest"
    var qualityRank: Int {
        let s = (suffix ?? "").uppercased()
        if ["FLAC", "ALAC", "WAV", "AIFF", "AIF"].contains(s) { return 10_000 + (bitDepth ?? 16) * 1000 + (samplingRate ?? 44100) / 1000 }
        return bitRate ?? 0
    }
}

func albumBadge(_ songs: [Song]) -> String? { songs.max { $0.qualityRank < $1.qualityRank }?.badge }
