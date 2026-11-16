import Foundation

/// Launch arguments for driving screens without taps (screenshots, device checks): `-open album:ID`, `-playalbum ID`
/// (`-shuffle 1`, `-startat N`), `-player 1`, `-upnext 1`, `-lyrics 1`, `-info <settingId>`, `-tab library|downloads|search`,
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
        guard let id = d.string(forKey: "playalbum") else { return }
        if let r = try? await app.fetch("getAlbum", [("id", id)], AlbumR.self) {
            let songs = r.album.song ?? []
            player.start(songs, at: d.integer(forKey: "startat"), context: Ref(kind: "album", id: id, name: r.album.title), shuffle: d.bool(forKey: "shuffle"))
            if d.bool(forKey: "paused") { player.pause() }
        }
    }
}
