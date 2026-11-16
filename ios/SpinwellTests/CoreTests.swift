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
