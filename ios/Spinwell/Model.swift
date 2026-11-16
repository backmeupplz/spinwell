import Foundation

/// Everything except `id` is optional: one missing field must never fail a whole response.
struct Artist: Codable, Hashable, Identifiable {
    var id: String
    var name: String?
    var coverArt: String?
    var albumCount: Int?
    var artistImageUrl: String?
    var starred: String?
    var album: [Album]?
    var title: String { name ?? "" }
}

struct Album: Codable, Hashable, Identifiable {
    var id: String
    var name: String?
    var artist: String?
    var artistId: String?
    var coverArt: String?
    var songCount: Int?
    var duration: Int?
    var year: Int?
    var genre: String?
    var starred: String?
    var playCount: Int?
    var created: String?
    var releaseTypes: [String]?
    var song: [Song]?
    var title: String { name ?? "" }
}

struct Playlist: Codable, Hashable, Identifiable {
    var id: String
    var name: String?
    var owner: String?
    var coverArt: String?
    var songCount: Int?
    var duration: Int?
    var entry: [Song]?
    var title: String { name ?? "" }
}

struct NamedRef: Codable, Hashable { var id: String?; var name: String? }

struct Song: Codable, Hashable, Identifiable {
    var id: String
    var title: String?
    var album: String?
    var albumId: String?
    var artist: String?
    var artistId: String?
    var track: Int?
    var discNumber: Int?
    var year: Int?
    var genre: String?
    var coverArt: String?
    var size: Int64?
    var suffix: String?
    var bitRate: Int?
    var samplingRate: Int?
    var bitDepth: Int?
    var duration: Int?
    var path: String?
    var playCount: Int?
    var starred: String?
    var contentType: String?
    var displayAlbumArtist: String?
    var albumArtists: [NamedRef]?

    var name: String { title ?? "" }
    var isStarred: Bool { starred != nil }
    var seconds: Double { Double(duration ?? 0) }
    /// Featured artists show only when they differ from the album artist.
    var featured: String? {
        guard let a = artist, !a.isEmpty, a != (displayAlbumArtist ?? "") else { return nil }
        return a
    }
}

struct Ref: Codable, Hashable { var kind: String; var id: String; var name: String }

// MARK: responses (each is the body of `subsonic-response`)

struct Err: Decodable { var code: Int?; var message: String? }

struct Envelope<T: Decodable>: Decodable {
    var status: String
    var error: Err?
    var serverVersion: String?
    var type: String?
    var body: T?
    private enum K: String, CodingKey { case resp = "subsonic-response" }
    private enum I: String, CodingKey { case status, error, serverVersion, type }
    init(from d: Decoder) throws {
        let inner = try d.container(keyedBy: K.self).nestedContainer(keyedBy: I.self, forKey: .resp)
        status = try inner.decode(String.self, forKey: .status)
        error = try inner.decodeIfPresent(Err.self, forKey: .error)
        serverVersion = try inner.decodeIfPresent(String.self, forKey: .serverVersion)
        type = try inner.decodeIfPresent(String.self, forKey: .type)
        body = status == "ok" ? try T(from: try d.container(keyedBy: K.self).superDecoder(forKey: .resp)) : nil
    }
}

struct Empty: Decodable {}
struct Artists: Decodable {
    struct Index: Decodable { var name: String?; var artist: [Artist]? }
    var artists: Wrap?
    struct Wrap: Decodable { var index: [Index]? }
    var list: [Artist] { (artists?.index ?? []).flatMap { $0.artist ?? [] } }
}
struct ArtistR: Decodable { var artist: Artist }
struct AlbumR: Decodable { var album: Album }
struct AlbumList: Decodable {
    var albumList2: W?
    struct W: Decodable { var album: [Album]? }
    var list: [Album] { albumList2?.album ?? [] }
}
struct PlaylistsR: Decodable {
    var playlists: W?
    struct W: Decodable { var playlist: [Playlist]? }
    var list: [Playlist] { playlists?.playlist ?? [] }
}
struct PlaylistR: Decodable { var playlist: Playlist }
struct Starred: Decodable {
    var starred2: W?
    struct W: Decodable { var artist: [Artist]?; var album: [Album]?; var song: [Song]? }
}
struct RandomSongs: Decodable {
    var randomSongs: W?
    struct W: Decodable { var song: [Song]? }
    var list: [Song] { randomSongs?.song ?? [] }
}
struct SearchR: Decodable {
    var searchResult3: W?
    struct W: Decodable { var artist: [Artist]?; var album: [Album]?; var song: [Song]? }
}
struct SongR: Decodable { var song: Song }
struct TopSongsR: Decodable {
    var topSongs: W?
    struct W: Decodable { var song: [Song]? }
}
struct SimilarR: Decodable {
    var similarSongs2: W?
    struct W: Decodable { var song: [Song]? }
}
struct ArtistInfoR: Decodable {
    var artistInfo2: Info?
    struct Info: Decodable {
        var biography: String?
        var largeImageUrl: String?
        var similarArtist: [Artist]?
    }
}
struct AlbumInfoR: Decodable {
    var albumInfo: Info?
    struct Info: Decodable { var notes: String? }
}
struct ExtensionsR: Decodable {
    var openSubsonicExtensions: [E]?
    struct E: Decodable { var name: String }
}
struct LyricsR: Decodable {
    var lyricsList: W?
    struct W: Decodable { var structuredLyrics: [Lyrics]? }
}
struct Lyrics: Codable, Hashable {
    struct Line: Codable, Hashable { var start: Int?; var value: String? }
    var synced: Bool?
    var line: [Line]?
}
