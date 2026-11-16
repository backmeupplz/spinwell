import SwiftUI

enum LibTab: String, CaseIterable { case artists = "Artists", albums = "Albums", playlists = "Playlists", songs = "Songs", favorites = "Favorites" }

struct LibraryView: View {
    @State private var tab: LibTab = LibTab(rawValue: (Hooks.string("libtab") ?? "").capitalized) ?? .albums
    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(LibTab.allCases, id: \.self) { t in
                        Button { withAnimation(.smooth(duration: 0.25)) { tab = t } } label: {
                            Text(t.rawValue).font(.footnote.weight(.semibold)).padding(.horizontal, 14).frame(height: 32)
                                .foregroundStyle(tab == t ? Color.black : .white)
                                .glassEffect(tab == t ? .regular.tint(.white).interactive() : .regular.interactive(), in: .capsule)
                                .padding(.vertical, 6).contentShape(Rectangle()) // 44 pt to hit
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, 16)
            }
            Group {
                switch tab {
                case .artists: ArtistsList()
                case .albums: AlbumsGrid()
                case .playlists: PlaylistsList()
                case .songs: AllSongs()
                case .favorites: FavoritesView()
                }
            }
            .id(tab).transition(.opacity)
        }
        .navigationTitle("Library").navigationBarTitleDisplayMode(.inline)
    }
}

/// A 56 pt list row: 40 pt thumbnail, title, caption.
struct ListRow: View {
    let cover: String?
    var radius: CGFloat = 6
    var symbol = "music.note"
    let title: String
    let caption: String
    var dim = false
    var body: some View {
        HStack(spacing: 12) {
            Cover(id: cover, size: 40, radius: radius, symbol: symbol).frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body).lineLimit(1)
                Text(caption).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8).padding(.horizontal, 16).contentShape(Rectangle())
        .opacity(dim ? 0.4 : 1)
    }
}

struct ArtistsList: View {
    @State private var loaded: [Artist] = []
    @State private var error: String?
    private var offlineMode: Bool { app.offline }
    /// Offline: everyone with a downloaded song first (the server's picture when the list is cached), then the rest of the cached list, dimmed.
    private var downloaded: [Artist] {
        guard offlineMode else { return [] }
        let cached = Dictionary(loaded.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return app.downloads.artists.map { cached[$0.id] ?? $0 }
    }
    private var rest: [Artist] { offlineMode ? loaded.filter { !app.downloads.artistIds.contains($0.id) } : loaded }
    private var groups: [(String, [Artist])] {
        let g = Dictionary(grouping: rest) { a -> String in
            let c = String((a.title.folding(options: .diacriticInsensitive, locale: .current).first ?? "#").uppercased())
            return c.rangeOfCharacter(from: .letters) != nil ? c : "#"
        }
        return g.sorted { $0.key == "#" ? false : $1.key == "#" ? true : $0.key < $1.key }.map { ($0.key, $0.value.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }) }
    }

    private func row(_ a: Artist, _ src: String, dim: Bool) -> some View {
        let n = a.albumCount ?? 0
        return NavigationLink(value: Route.artist(a.id, src)) {
            ListRow(cover: a.coverArt, radius: 20, symbol: "person.fill", title: a.title, caption: "\(n) album\(n == 1 ? "" : "s")", dim: dim)
        }.buttonStyle(.plain)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: .sectionHeaders) {
                    if let error, loaded.isEmpty, downloaded.isEmpty { ErrorLine(text: error) }
                    if !downloaded.isEmpty {
                        Section {
                            ForEach(downloaded) { row($0, "lib-dl-artist-\($0.id)", dim: false) }
                        } header: { header("Downloaded") }
                    }
                    ForEach(groups, id: \.0) { letter, list in
                        Section {
                            ForEach(list) { row($0, "lib-artist-\($0.id)", dim: offlineMode) }
                        } header: { header(letter) }
                        .id(letter)
                    }
                    Color.clear.frame(height: 90)
                }
            }
            .overlay(alignment: .trailing) {
                VStack(spacing: 0) {
                    ForEach(groups.map(\.0), id: \.self) { l in
                        Button { proxy.scrollTo(l, anchor: .top) } label: { Text(l).font(.system(size: 10, weight: .bold)).frame(width: 22, height: 15) }
                    }
                }.padding(.trailing, 2)
            }
        }
        .task(id: app.offline) { error = (await app.load("getArtists", [], Artists.self) { loaded = $0.list })?.localizedDescription }
    }

    private func header(_ t: String) -> some View {
        Text(t).font(.footnote.weight(.bold)).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading).background(.black.opacity(0.7))
    }
}

struct AlbumsGrid: View {
    enum Sort: String, CaseIterable {
        case name = "Name", artist = "Artist", added = "Recently added", year = "Year", played = "Most played"
        var type: String { switch self { case .name: "alphabeticalByName"; case .artist: "alphabeticalByArtist"; case .added: "newest"; case .year: "byYear"; case .played: "frequent" } }
        var extra: [(String, String)] { self == .year ? [("fromYear", "3000"), ("toYear", "0")] : [] }
    }
    @State private var sort: Sort = .name
    @State private var loadedSort: Sort?
    @State private var pages: [Int: [Album]] = [:]
    @State private var more = true
    @State private var error: String?
    private let size = 60
    /// Offline: downloaded albums first, the rest of the cached pages after them (dimmed by the card).
    private var albums: [Album] {
        let l = pages.keys.sorted().flatMap { pages[$0]! }
        guard app.offline else { return l }
        let dl = app.downloads.albums, ids = Set(dl.map(\.id))
        return dl + l.filter { !ids.contains($0.id) }
    }

    var body: some View {
        ScrollView {
            let cols = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
            HStack {
                Menu {
                    Picker("Sort by", selection: $sort) { ForEach(Sort.allCases, id: \.self) { Text($0.rawValue) } }
                } label: { Label(sort.rawValue, systemImage: "arrow.up.arrow.down").font(.footnote.weight(.semibold)).frame(minHeight: 32) }
                Spacer()
            }.padding(.horizontal, 16)
            if let error, albums.isEmpty, !app.offline { ErrorLine(text: error) }
            LazyVGrid(columns: cols, spacing: 14) {
                ForEach(albums) { a in
                    AlbumCard(album: a, width: nil, source: "grid-\(a.id)")
                        .onAppear { if a.id == albums.last?.id, more, !pages.isEmpty, !app.offline { Task { await load((pages.keys.max() ?? -1) + 1) } } }
                }
            }.padding(.horizontal, 16)
            Color.clear.frame(height: 90)
        }
        .task(id: "\(sort.rawValue)|\(app.offline)") {
            if loadedSort != sort { pages = [:]; more = true; loadedSort = sort }
            await load(0)
        }
    }

    private func load(_ page: Int) async {
        let e = await app.load("getAlbumList2", [("type", sort.type), ("size", String(size)), ("offset", String(page * size))] + sort.extra, AlbumList.self) { r in
            pages[page] = r.list
            if page == (pages.keys.max() ?? 0) { more = r.list.count == size }
        }
        if let e { error = e.localizedDescription }
    }
}

struct PlaylistsList: View {
    @State private var lists: [Playlist] = []
    @State private var error: String?
    private var pinned: [Pinned] { app.downloads.state.pinned.filter { $0.kind == "playlist" } }
    /// Offline: pinned (downloaded) playlists first, the others dimmed.
    private var ordered: [Playlist] {
        guard app.offline else { return lists }
        let ids = Set(pinned.map(\.cid))
        let known = Set(lists.map(\.id))
        let extra = pinned.filter { !known.contains($0.cid) }.map { Playlist(id: $0.cid, name: $0.name, coverArt: $0.coverArt, songCount: $0.songIds.count) }
        return lists.filter { ids.contains($0.id) } + extra + lists.filter { !ids.contains($0.id) }
    }
    var body: some View {
        let pinnedIds = Set(pinned.map(\.cid))
        ScrollView {
            LazyVStack(spacing: 0) {
                if let error, ordered.isEmpty { ErrorLine(text: error) }
                if ordered.isEmpty, error == nil { EmptyState(symbol: "music.note.list", text: "No playlists yet. Create one in Navidrome and it shows up here.") }
                ForEach(ordered) { p in
                    NavigationLink(value: Route.playlist(p.id, "lib-pl-\(p.id)")) {
                        ListRow(cover: p.coverArt, symbol: "music.note.list", title: p.title,
                                caption: "\(p.songCount ?? 0) songs · \(minutes(p.duration ?? 0))", dim: app.offline && !pinnedIds.contains(p.id))
                    }.buttonStyle(.plain)
                }
                Color.clear.frame(height: 90)
            }
        }
        .task(id: app.offline) { error = (await app.load("getPlaylists", [], PlaylistsR.self) { lists = $0.list })?.localizedDescription }
    }
}

struct AllSongs: View {
    @State private var pages: [Int: [Song]] = [:]
    @State private var more = true
    @State private var error: String?
    private let size = 60
    private var songs: [Song] {
        let l = pages.keys.sorted().flatMap { pages[$0]! }
        return l.isEmpty && app.offline ? app.downloads.songs : l
    }
    private let ref = Ref(kind: "songs", id: "all", name: "Songs")
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if let error, songs.isEmpty { ErrorLine(text: error) }
                if !songs.isEmpty {
                    PlayRow { player.start(songs, context: ref) } shuffle: { Task { await shuffleAllSongs(name: "Songs") } }.padding(.bottom, 4)
                }
                ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in
                    SongRow(song: s, cover: true) { player.play(songs, from: s, context: ref) }
                        .padding(.horizontal, 16)
                        .onAppear { if i == songs.count - 1, more, !pages.isEmpty, !app.offline { Task { await load((pages.keys.max() ?? -1) + 1) } } }
                }
                Color.clear.frame(height: 90)
            }
        }
        .task(id: app.offline) { await load(0) }
    }
    private func load(_ page: Int) async {
        let e = await app.load("search3", [("query", ""), ("artistCount", "0"), ("albumCount", "0"), ("songCount", String(size)), ("songOffset", String(page * size))], SearchR.self) { r in
            let l = r.searchResult3?.song ?? []
            pages[page] = l
            if page == (pages.keys.max() ?? 0) { more = l.count == size }
        }
        if let e { error = e.localizedDescription }
    }
}

struct FavoritesView: View {
    @State private var artists: [Artist] = []
    @State private var albums: [Album] = []
    @State private var songs: [Song] = []
    @State private var error: String?
    private let ref = Ref(kind: "favorites", id: "songs", name: "Favorites")
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let error, songs.isEmpty, albums.isEmpty, artists.isEmpty { ErrorLine(text: error) }
                if songs.isEmpty && albums.isEmpty && artists.isEmpty && error == nil {
                    EmptyState(symbol: "heart", text: "Tap the heart on a song, album or artist and it appears here.")
                }
                if !artists.isEmpty {
                    Text("Artists").font(.title3.bold()).padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(artists) { a in
                                NavigationLink(value: Route.artist(a.id, "fav-artist-\(a.id)")) {
                                    VStack(spacing: 4) {
                                        Cover(id: a.coverArt, size: 80, radius: 40, symbol: "person.fill").frame(width: 80, height: 80)
                                        Text(a.title).font(.caption).lineLimit(1).frame(width: 80)
                                    }
                                }.buttonStyle(Press())
                            }
                        }.padding(.horizontal, 16)
                    }
                }
                if !albums.isEmpty {
                    Text("Albums").font(.title3.bold()).padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) { ForEach(albums) { AlbumCard(album: $0, source: "fav-\($0.id)") } }.padding(.horizontal, 16)
                    }
                }
                if !songs.isEmpty {
                    Text("Songs").font(.title3.bold()).padding(.horizontal, 16)
                    PlayRow { player.start(songs, context: ref) } shuffle: { player.start(songs, context: ref, randomStart: true) }
                    SongList(songs: songs, context: ref, cover: true).padding(.horizontal, 16)
                }
                Color.clear.frame(height: 90)
            }
        }
        .task(id: app.offline) {
            error = (await app.load("getStarred2", [], Starred.self) { r in
                artists = r.starred2?.artist ?? []; albums = r.starred2?.album ?? []; songs = r.starred2?.song ?? []
            })?.localizedDescription
        }
    }
}
