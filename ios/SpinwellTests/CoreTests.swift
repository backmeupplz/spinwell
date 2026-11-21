import XCTest
@testable import Spinwell

private func song(_ id: String, artist: String, album: String = "A", suffix: String = "mp3") -> Song {
    Song(id: id, title: id, album: album, albumId: album, artist: artist, artistId: artist, suffix: suffix)
}

final class AuthTests: XCTestCase {
    func testToken() {
        // md5("sesame" + "c19b2d") from the Subsonic API docs
        XCTAssertEqual(Account.md5("sesamec19b2d"), "26719a1196d2a940705a59634eb18eab")
        let a = Account.make(server: "music.example.ts.net/", user: "u", password: "sesame", salt: "c19b2d")
        XCTAssertEqual(a.token, "26719a1196d2a940705a59634eb18eab")
        XCTAssertEqual(a.server, "https://music.example.ts.net")
        XCTAssertEqual(Account.normalize("  http://192.168.1.2:4533// "), "http://192.168.1.2:4533")
        XCTAssertEqual(Account.normalize("192.168.1.5:4533"), "http://192.168.1.5:4533")
        XCTAssertEqual(Account.normalize("10.0.0.2/navidrome/"), "http://10.0.0.2/navidrome")
        XCTAssertEqual(Account.normalize("172.16.0.1"), "http://172.16.0.1")
        XCTAssertEqual(Account.normalize("172.32.0.1"), "https://172.32.0.1")
        XCTAssertEqual(Account.normalize("100.101.2.3:4533"), "http://100.101.2.3:4533")
        XCTAssertEqual(Account.normalize("nas:4533"), "http://nas:4533")
        XCTAssertEqual(Account.normalize("localhost:4533"), "http://localhost:4533")
        XCTAssertEqual(Account.normalize("music.local"), "http://music.local")
        XCTAssertEqual(Account.normalize("8.8.8.8"), "https://8.8.8.8")
        XCTAssertEqual(Account.normalize("https://192.168.1.5"), "https://192.168.1.5")
    }

    func testURLBuilding() throws {
        let a = Account(server: "http://h:4533", user: "admin", salt: "s", token: "t")
        let u = try XCTUnwrap(a.url("getAlbum", [("id", "x y")]))
        XCTAssertTrue(u.absoluteString.hasPrefix("http://h:4533/rest/getAlbum?id=x%20y&u=admin&t=t&s=s&v=1.16.1&c=Spinwell&f=json"))
        XCTAssertFalse(u.absoluteString.contains("p="))
    }

    func testIOSTranscodeRule() throws {
        let a = Account(server: "http://h", user: "u", salt: "s", token: "t")
        func q(_ suffix: String, _ br: Int = 0) -> [String: String] {
            let u = a.streamURL("1", suffix: suffix, bitrate: br)!
            return Dictionary(uniqueKeysWithValues: URLComponents(url: u, resolvingAgainstBaseURL: false)!.queryItems!.map { ($0.name, $0.value ?? "") })
        }
        XCTAssertEqual(q("flac")["maxBitRate"], "0"); XCTAssertNil(q("flac")["format"])
        for s in ["opus", "ogg", "wma", "ape"] { XCTAssertEqual(q(s)["format"], "aac"); XCTAssertEqual(q(s)["maxBitRate"], "256") }
        XCTAssertEqual(q("flac", 192)["format"], "mp3"); XCTAssertEqual(q("flac", 192)["maxBitRate"], "192")
        XCTAssertEqual(Quality.ext(suffix: "opus", bitrate: 0), "aac")
        XCTAssertEqual(Quality.ext(suffix: "flac", bitrate: 0), "flac")
        XCTAssertEqual(Quality.ext(suffix: "flac", bitrate: 320), "mp3")
    }

    func testCacheKeyExcludesAuth() {
        let a = Account.cacheKey("getAlbumList2", [("type", "recent"), ("size", "20"), ("u", "x"), ("t", "y"), ("s", "z")])
        let b = Account.cacheKey("getAlbumList2", [("size", "20"), ("type", "recent"), ("t", "other")])
        XCTAssertEqual(a, b)
        XCTAssertEqual(a, "getAlbumList2?size=20&type=recent")
    }
}

final class ShuffleTests: XCTestCase {
    private func library() -> [Song] {
        var l: [Song] = []
        for (a, n) in [("A", 8), ("B", 6), ("C", 5), ("D", 3)] { for i in 0..<n { l.append(song("\(a)\(i)", artist: a, album: "\(a)\(i % 2)")) } }
        return l
    }

    func testKeepsAllAndFirst() {
        let l = library()
        let s = smartShuffle(l, first: 7, seed: 42)
        XCTAssertEqual(Set(s.map(\.id)), Set(l.map(\.id)))
        XCTAssertEqual(s.count, l.count)
        XCTAssertEqual(s[0].id, l[7].id)
    }

    func testNoAdjacentSameArtistWhenFeasible() {
        for seed in 0..<50 as Range<UInt64> {
            let s = smartShuffle(library(), first: Int(seed) % 22, seed: seed)
            for i in 1..<s.count { XCTAssertNotEqual(s[i].artist, s[i - 1].artist, "seed \(seed) at \(i)") }
        }
    }

    func testDeterministicPerSeed() {
        XCTAssertEqual(smartShuffle(library(), seed: 5).map(\.id), smartShuffle(library(), seed: 5).map(\.id))
        XCTAssertNotEqual(smartShuffle(library(), seed: 5).map(\.id), smartShuffle(library(), seed: 6).map(\.id))
    }

    func testInfeasibleStillKeepsAll() {
        let l = (0..<6).map { song("x\($0)", artist: "Same") } + [song("y", artist: "Other")]
        XCTAssertEqual(Set(smartShuffle(l, seed: 1).map(\.id)), Set(l.map(\.id)))
    }
}

final class QueueTests: XCTestCase {
    private let songs = (0..<6).map { song("s\($0)", artist: "Art\($0 % 3)") }
    private func ids(_ q: QueueState) -> [String] { q.items.map(\.song.id) }

    func testPlayNextAndAdd() {
        var q = QueueState()
        q.start(songs, at: 1, context: Ref(kind: "album", id: "1", name: "X"), shuffle: false)
        q.playNext([song("n1", artist: "N")])
        q.addToQueue([song("a1", artist: "N")])
        q.playNext([song("n2", artist: "N")])
        XCTAssertEqual(ids(q), ["s0", "s1", "n2", "n1", "a1", "s2", "s3", "s4", "s5"])
        XCTAssertEqual(q.nextInQueue.map(\.song.id), ["n2", "n1", "a1"])
        XCTAssertEqual(q.nextFromContext.first?.song.id, "s2")
        q.clearAdded()
        XCTAssertEqual(ids(q), ["s0", "s1", "s2", "s3", "s4", "s5"])
    }

    func testSameSongTwiceHasDistinctKeys() {
        var q = QueueState()
        q.start(songs, context: nil, shuffle: false)
        q.addToQueue([songs[0]])
        XCTAssertEqual(Set(q.items.map(\.id)).count, q.items.count)
    }

    func testRemoveMoveJump() {
        var q = QueueState()
        q.start(songs, at: 2, context: nil, shuffle: false)
        q.remove(q.items[0].id)
        XCTAssertEqual(q.current?.song.id, "s2")
        XCTAssertEqual(q.index, 1)
        q.move(from: IndexSet(integer: 4), to: 2)
        XCTAssertEqual(ids(q), ["s1", "s2", "s5", "s3", "s4"])
        XCTAssertEqual(q.current?.song.id, "s2")
        q.jump(to: q.items[4].id)
        XCTAssertEqual(q.current?.song.id, "s4")
    }

    func testShuffleOffRestoresContextOrder() {
        var q = QueueState()
        q.start(songs, at: 0, context: nil, shuffle: false)
        q.setShuffle(true, seed: 9)
        XCTAssertTrue(q.shuffle)
        XCTAssertEqual(q.current?.song.id, "s0")
        XCTAssertEqual(Set(ids(q)), Set(songs.map(\.id)))
        q.advance(auto: false)
        let now = q.current!.song.id
        q.setShuffle(false)
        XCTAssertEqual(ids(q), songs.map(\.id))
        XCTAssertEqual(q.current?.song.id, now)
    }

    func testShuffleFromButtonStartsShuffled() {
        var q = QueueState()
        q.startShuffled(songs, context: nil, seed: 3)
        XCTAssertTrue(q.shuffle); XCTAssertEqual(q.index, 0); XCTAssertEqual(q.items.count, 6)
    }

    func testAdvanceAndRepeat() {
        var q = QueueState()
        q.start(Array(songs.prefix(2)), context: nil, shuffle: false)
        XCTAssertTrue(q.advance(auto: true)); XCTAssertFalse(q.advance(auto: true))
        q.repeatMode = .all
        XCTAssertTrue(q.advance(auto: true)); XCTAssertEqual(q.index, 0)
        q.repeatMode = .one
        XCTAssertTrue(q.advance(auto: true)); XCTAssertEqual(q.index, 0)
        XCTAssertTrue(q.advance(auto: false)); XCTAssertEqual(q.index, 1)
    }

    func testCodableRoundTrip() throws {
        var q = QueueState()
        q.start(songs, at: 2, context: Ref(kind: "album", id: "1", name: "X"), shuffle: true, seed: 1)
        let r = try JSONDecoder().decode(QueueState.self, from: JSONEncoder().encode(q))
        XCTAssertEqual(r.items, q.items); XCTAssertEqual(r.index, q.index); XCTAssertEqual(r.shuffle, true)
    }
}

final class FormatTests: XCTestCase {
    func testBadge() {
        XCTAssertEqual(Song(id: "1", suffix: "flac", samplingRate: 96000, bitDepth: 24).badge, "FLAC 24/96")
        XCTAssertEqual(Song(id: "1", suffix: "flac", samplingRate: 44100, bitDepth: 16).badge, "FLAC 16/44.1")
        XCTAssertEqual(Song(id: "1", suffix: "mp3", bitRate: 320).badge, "MP3 320")
        XCTAssertEqual(Song(id: "1", suffix: "opus").badge, "OPUS")
        XCTAssertEqual(albumBadge([Song(id: "1", suffix: "mp3", bitRate: 320), Song(id: "2", suffix: "flac", samplingRate: 44100, bitDepth: 16)]), "FLAC 16/44.1")
    }
}

final class SearchTests: XCTestCase {
    private let songs = [
        Song(id: "1", title: "Сон - кино", album: "Песни", artist: "Various"),
        Song(id: "2", title: "Группа крови", album: "Группа крови", artist: "Кино"),
        Song(id: "3", title: "Ёлка", album: "X", artist: "Y"),
    ]
    private let artists = [Artist(id: "a", name: "Кино"), Artist(id: "b", name: "Киноклуб")]

    func testCyrillicAnyCase() {
        for q in ["кино", "Кино", "КИНО", " кино "] {
            let r = localSearch(q, songs: songs, albums: [], artists: artists)
            XCTAssertEqual(r.artists.map(\.id), ["a", "b"], q)       // exact/prefix first, in input order
            XCTAssertEqual(r.songs.map(\.id).sorted(), ["1", "2"], q) // title or artist
        }
        XCTAssertEqual(localSearch("елка", songs: songs, albums: [], artists: []).songs.map(\.id), ["3"]) // ё folds to е
    }

    func testPrefixBeforeInside() {
        let r = localSearch("ино", songs: [], albums: [], artists: [Artist(id: "1", name: "Кино"), Artist(id: "2", name: "Иностранцы")])
        XCTAssertEqual(r.artists.map(\.id), ["2", "1"])
    }

    func testTopResultExactThenPrefix() {
        let r = localSearch("кино", songs: songs, albums: [], artists: artists)
        if case .artist(let a)? = topResult("кино", r) { XCTAssertEqual(a.id, "a") } else { XCTFail("expected exact artist") }
        XCTAssertNil(topResult("киноm", localSearch("киноm", songs: songs, albums: [], artists: artists))) // nothing matches
        let pre = SearchResults(artists: [Artist(id: "b", name: "Киноклуб")])
        if case .artist(let a)? = topResult("киноклу", pre) { XCTAssertEqual(a.id, "b") } else { XCTFail("expected prefix artist") }
    }
}

final class ConnectivityTests: XCTestCase {
    func testNetworkErrorsAreFoundUnderWrappers() {
        let inner = NSError(domain: NSURLErrorDomain, code: URLError.cannotConnectToHost.rawValue)
        let wrapped = NSError(domain: "AVFoundationErrorDomain", code: -11800, userInfo: [NSUnderlyingErrorKey: inner])
        XCTAssertTrue(Srv.isNetwork(wrapped))
        XCTAssertTrue(Srv.isNetwork(URLError(.timedOut)))
        XCTAssertFalse(Srv.isNetwork(URLError(.badServerResponse)))
        XCTAssertFalse(Srv.isNetwork(NSError(domain: "AVFoundationErrorDomain", code: -11828)))
    }

    func testArtistRefsCoverTrackAndAlbumArtists() {
        let s = Song(id: "1", artist: "A feat. B", artistId: "a", albumArtists: [NamedRef(id: "a", name: "A"), NamedRef(id: "c", name: "C")])
        XCTAssertEqual(Downloads.artistRefs(s).map(\.0), ["a", "c"])
    }
}

final class RepeatTests: XCTestCase {
    func testRepeatAllLoopsAndOneStays() {
        var q = QueueState()
        q.start([song("1", artist: "a"), song("2", artist: "b")], at: 1, context: nil, shuffle: false)
        q.repeatMode = .all
        XCTAssertTrue(q.advance(auto: true)); XCTAssertEqual(q.current?.song.id, "1") // wraps
        q.repeatMode = .one
        XCTAssertTrue(q.advance(auto: true)); XCTAssertEqual(q.current?.song.id, "1") // stays
        XCTAssertTrue(q.advance(auto: false)); XCTAssertEqual(q.current?.song.id, "2") // a manual next still moves
    }
}
