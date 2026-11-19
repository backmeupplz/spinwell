import Foundation
import UIKit

/// Launch arguments for driving screens without taps (screenshots, device checks): `-open album:ID`, `-playalbum ID`
/// (`-shuffle 1`, `-startat N`, `-paused 1`), `-playsong ID`, `-download ALBUMID`, `-player 1`, `-upnext 1`, `-lyrics 1`, `-info <settingId>`, `-tab library|downloads|search`,
/// `-libtab artists|albums|playlists|songs|favorites`, `-query text`, `-offline 1`, `-server/-user/-pass`.
///
/// Compiled only in Debug: in Release every hook is a no-op and the launch arguments are ignored.
@MainActor enum Hooks {
#if !DEBUG
    static func string(_ k: String) -> String? { nil }
    static func flag(_ k: String) -> Bool { false }
    static func openIfRequested(_ nav: Nav) {}
    static func signInIfRequested() {}
    static func playIfRequested() async {}
    static func runScript() async {}
#else
    static func string(_ k: String) -> String? { UserDefaults.standard.string(forKey: k) }
    static func flag(_ k: String) -> Bool { UserDefaults.standard.bool(forKey: k) }
    private static var opened = false

    /// `-signout 1`, `-server URL -user U -pass P` (when signed out), `-offline 0|1`.
    static func signInIfRequested() {
        let d = UserDefaults.standard
        if d.bool(forKey: "signout") { app.signOut() }
        if let s = d.string(forKey: "server"), let u = d.string(forKey: "user"), let p = d.string(forKey: "pass"), !app.signedIn {
            Task { await app.signIn(server: s, user: u, password: p) }
        }
        if d.object(forKey: "offline") != nil { app.settings.offlineMode = d.bool(forKey: "offline") }
        if let p = d.string(forKey: "hitchlog") { HitchLog.start(p) }
    }

    static func route(_ s: String) -> Route? {
        let p = s.split(separator: ":", maxSplits: 1).map(String.init)
        switch (p[0], p.count > 1 ? p[1] : "") {
        case ("album", let id): return .album(id, "hook-\(id)")
        case ("artist", let id): return .artist(id, "hook-\(id)")
        case ("playlist", let id): return .playlist(id, "hook-\(id)")
        case ("settings", _): return .settings
        case ("favorites", _): return .favorites
        default: return nil
        }
    }

    /// The first stack that appears pushes the requested route.
    static func openIfRequested(_ nav: Nav) {
        guard !opened, let s = UserDefaults.standard.string(forKey: "open"), let r = route(s) else { return }
        opened = true
        nav.open(r)
    }

    static func playIfRequested() async {
        let d = UserDefaults.standard
        if let id = d.string(forKey: "playsong"), let r = try? await app.fetch("getSong", [("id", id)], SongR.self) {
            player.start([r.song], context: Ref(kind: "song", id: id, name: r.song.name))
        }
        if let id = d.string(forKey: "download"), let r = try? await app.fetch("getAlbum", [("id", id)], AlbumR.self) {
            app.downloads.add(r.album.song ?? [], pin: Pinned(kind: "album", cid: id, name: r.album.title, coverArt: r.album.coverArt, songIds: []))
        }
        if d.bool(forKey: "playdownloads") { player.start(app.downloads.songs, context: Ref(kind: "downloads", id: "songs", name: "Downloads")) }
        guard let id = d.string(forKey: "playalbum") else { return }
        if let r = try? await app.fetch("getAlbum", [("id", id)], AlbumR.self) {
            let songs = r.album.song ?? []
            player.start(songs, at: d.integer(forKey: "startat"), context: Ref(kind: "album", id: id, name: r.album.title), shuffle: d.bool(forKey: "shuffle"))
            if d.bool(forKey: "paused") { player.pause() }
        }
    }

    /// `-script "wait:3,next,seek:20,wait:6,shuffle,prev,remove:2"` runs player actions and appends the queue state to
    /// `$TMPDIR/spinwell-hook.log` after each (Simulator: the host's /tmp via `-log /tmp/x.log`).
    static func runScript() async {
        let d = UserDefaults.standard
        guard let script = d.string(forKey: "script") else { return }
        let path = d.string(forKey: "log") ?? NSTemporaryDirectory() + "spinwell-hook.log"
        func log(_ s: String) {
            let line = "\(s) | now=\(player.current?.name ?? "-") idx=\(player.q.index)/\(player.q.items.count) pos=\(Int(player.pos)) playing=\(player.playing) shuffle=\(player.q.shuffle) repeat=\(player.q.repeatMode.rawValue) offline=\(app.offline) err=\(player.error ?? "-") next=\(player.q.upcoming?.song.name ?? "-")\n"
            if let h = FileHandle(forWritingAtPath: path) { h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close() } else { try? line.write(toFile: path, atomically: true, encoding: .utf8) }
        }
        for step in script.split(separator: ",").map(String.init) {
            let p = step.split(separator: ":").map(String.init)
            switch p[0] {
            case "wait": try? await Task.sleep(for: .seconds(Double(p[1]) ?? 1))
            case "next": player.next()
            case "prev": player.previous()
            case "seek": player.seek(Double(p[1]) ?? 0)
            case "shuffle": player.setShuffle(!player.q.shuffle)
            case "repeat": player.cycleRepeat()
            case "pause": player.pause()
            case "play": player.play()
            case "block": try? "refused".write(toFile: "/tmp/spinwell-block", atomically: true, encoding: .utf8) // dead-server simulation, see Account.url
            case "blackhole": try? "blackhole".write(toFile: "/tmp/spinwell-block", atomically: true, encoding: .utf8) // packets vanish: timeouts, stalls
            case "unblock": try? FileManager.default.removeItem(atPath: "/tmp/spinwell-block")
            case "playnext": if let r = try? await app.fetch("getSong", [("id", p[1])], SongR.self) { player.playNext([r.song]) }
            case "jump": if player.q.items.indices.contains(Int(p[1]) ?? 0) { player.jump(to: player.q.items[Int(p[1])!].id) }
            case "remove": if player.q.items.indices.contains(Int(p[1]) ?? 0) { player.remove(player.q.items[Int(p[1])!].id) }
            default: break
            }
            try? await Task.sleep(for: .milliseconds(300))
            log(step)
        }
    }
#endif
}

#if DEBUG
/// `-hitchlog /tmp/x.log`: appends a line for every frame that took more than 24 ms (a missed 60 Hz frame is 16.7 ms), so a tap -> player
/// open can be judged without Instruments. Gaps over 1 s (idle app, no animation) are skipped.
final class HitchLog: NSObject {
    private static var link: CADisplayLink?
    private static var last = 0.0
    private static var path = ""
    static func start(_ p: String) {
        path = p
        let l = CADisplayLink(target: HitchLog(), selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 60, preferred: 60) // else an idle app is ticked at a few Hz
        l.add(to: .main, forMode: .common)
        link = l
    }
    @objc func tick(_ l: CADisplayLink) {
        let now = l.timestamp
        defer { Self.last = now }
        let gap = (now - Self.last) * 1000
        guard Self.last > 0, gap > 24, gap < 1000 else { return }
        let line = String(format: "%.2f gap=%.0fms\n", now, gap)
        if let h = FileHandle(forWritingAtPath: Self.path) { h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close() } else { try? line.write(toFile: Self.path, atomically: true, encoding: .utf8) }
    }
}
#endif
