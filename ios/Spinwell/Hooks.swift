import Foundation

/// Launch arguments for driving screens without taps (screenshots, device checks): `-open album:ID`, `-playalbum ID`
/// (`-shuffle 1`, `-startat N`, `-paused 1`), `-playsong ID`, `-download ALBUMID`, `-player 1`, `-upnext 1`, `-lyrics 1`, `-info <settingId>`, `-tab library|downloads|search`,
/// `-libtab artists|albums|playlists|songs|favorites`, `-query text`, `-offline 1`, `-server/-user/-pass`.
@MainActor enum Hooks {
    private static var opened = false

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

    /// `-script "wait:3,next,seek:20,wait:6,shuffle,prev"` runs player actions and appends the queue state to
    /// `$TMPDIR/spinwell-hook.log` after each (Simulator: the host's /tmp via `-log /tmp/x.log`).
    static func runScript() async {
        let d = UserDefaults.standard
        guard let script = d.string(forKey: "script") else { return }
        let path = d.string(forKey: "log") ?? NSTemporaryDirectory() + "spinwell-hook.log"
        func log(_ s: String) {
            let line = "\(s) | now=\(player.current?.name ?? "-") idx=\(player.q.index)/\(player.q.items.count) pos=\(Int(player.pos)) playing=\(player.playing) shuffle=\(player.q.shuffle) next=\(player.q.upcoming?.song.name ?? "-")\n"
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
            case "playnext": if let r = try? await app.fetch("getSong", [("id", p[1])], SongR.self) { player.playNext([r.song]) }
            case "jump": if player.q.items.indices.contains(Int(p[1]) ?? 0) { player.jump(to: player.q.items[Int(p[1])!].id) }
            default: break
            }
            try? await Task.sleep(for: .milliseconds(300))
            log(step)
        }
    }
}
