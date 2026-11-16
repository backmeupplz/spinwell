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
                            Text(t.rawValue).font(.subheadline.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 8)
                                .foregroundStyle(tab == t ? Color.black : .white)
                                .glassEffect(tab == t ? .regular.tint(.white).interactive() : .regular.interactive(), in: .capsule)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal)
            }
            .padding(.bottom, 8)
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
        .navigationTitle("Library")
    }
}

struct ArtistsList: View {
    @State private var loaded: [Artist] = []
    @State private var error: String?
    private var artists: [Artist] { loaded.isEmpty && app.offline ? app.downloads.artists : loaded }
    private var groups: [(String, [Artist])] {
        let g = Dictionary(grouping: artists) { a -> String in
            let c = String((a.title.folding(options: .diacriticInsensitive, locale: .current).first ?? "#").uppercased())
            return c.rangeOfCharacter(from: .letters) != nil ? c : "#"
        }
        return g.sorted { $0.key == "#" ? false : $1.key == "#" ? true : $0.key < $1.key }.map { ($0.key, $0.value.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }) }
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: .sectionHeaders) {
                    if let error, artists.isEmpty { ErrorLine(text: error) }
                    ForEach(groups, id: \.0) { letter, list in
                        Section {
                            ForEach(list) { a in
                                NavigationLink(value: Route.artist(a.id, "lib-artist-\(a.id)")) {
                                    HStack(spacing: 14) {
                                        Cover(id: a.coverArt, size: 52, radius: 26, symbol: "person.fill").frame(width: 52, height: 52)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(a.title).font(.body).lineLimit(1)
                                            Text("\(a.albumCount ?? 0) album\(a.albumCount == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                    }.padding(.vertical, 6).padding(.horizontal, 20).contentShape(Rectangle())
                                }.buttonStyle(.plain)
                            }
                        } header: {
                            Text(letter).font(.footnote.weight(.bold)).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 4)
                                .frame(maxWidth: .infinity, alignment: .leading).background(.black.opacity(0.7))
                        }
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
        .task { error = (await app.load("getArtists", [], Artists.self) { loaded = $0.list })?.localizedDescription }
    }
}

struct AlbumsGrid: View {
    enum Sort: String, CaseIterable {
        case name = "Name", artist = "Artist", added = "Recently added", year = "Year", played = "Most played"
        var type: String { switch self { case .name: "alphabeticalByName"; case .artist: "alphabeticalByArtist"; case .added: "newest"; case .year: "byYear"; case .played: "frequent" } }
        var extra: [(String, String)] { self == .year ? [("fromYear", "3000"), ("toYear", "0")] : [] }
    }
    @State private var sort: Sort = .name
    @State private var pages: [Int: [Album]] = [:]
    @State private var more = true
    @State private var error: String?
    private let size = 60
    private var albums: [Album] {
        let l = pages.keys.sorted().flatMap { pages[$0]! }
        return l.isEmpty && app.offline ? app.downloads.albums : l
    }

    var body: some View {
        ScrollView {
            let cols = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]
            HStack {
                Menu {
                    Picker("Sort by", selection: $sort) { ForEach(Sort.allCases, id: \.self) { Text($0.rawValue) } }
                } label: { Label(sort.rawValue, systemImage: "arrow.up.arrow.down").font(.footnote.weight(.semibold)) }
                Spacer()
            }.padding(.horizontal, 20)
            if let error, albums.isEmpty, !app.offline { ErrorLine(text: error) }
            LazyVGrid(columns: cols, spacing: 18) {
                ForEach(albums) { a in
                    AlbumCard(album: a, width: nil, source: "grid-\(a.id)")
                        .onAppear { if a.id == albums.last?.id, more { Task { await load(pages.keys.max()! + 1) } } }
                }
            }.padding(.horizontal, 20)
            Color.clear.frame(height: 90)
        }
        .task(id: sort) { pages = [:]; more = true; await load(0) }
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
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if let error, lists.isEmpty { ErrorLine(text: error) }
                if lists.isEmpty, error == nil { ErrorLine(text: "No playlists yet. Create one in Navidrome and it shows up here.") }
                ForEach(lists) { p in
                    NavigationLink(value: Route.playlist(p.id, "lib-pl-\(p.id)")) {
                        HStack(spacing: 14) {
                            Cover(id: p.coverArt, size: 60, radius: 8, symbol: "music.note.list").frame(width: 60, height: 60)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.title).font(.body).lineLimit(1)
                                Text("\(p.songCount ?? 0) songs · \(minutes(p.duration ?? 0))").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }.padding(.vertical, 6).padding(.horizontal, 20).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                Color.clear.frame(height: 90)
            }
        }
        .task { error = (await app.load("getPlaylists", [], PlaylistsR.self) { lists = $0.list })?.localizedDescription }
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
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if let error, songs.isEmpty { ErrorLine(text: error) }
                ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in
                    SongRow(song: s, cover: true) { player.play(songs, from: s, context: Ref(kind: "songs", id: "all", name: "Songs")) }
                        .padding(.horizontal, 20)
                        .onAppear { if i == songs.count - 1, more { Task { await load(pages.keys.max()! + 1) } } }
                }
                Color.clear.frame(height: 90)
            }
        }
        .task { await load(0) }
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
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if let error, songs.isEmpty, albums.isEmpty, artists.isEmpty { ErrorLine(text: error) }
                if songs.isEmpty && albums.isEmpty && artists.isEmpty && error == nil {
                    ErrorLine(text: "Tap the heart on a song, album or artist and it appears here.")
                }
                if !artists.isEmpty {
                    Text("Artists").font(.title3.bold()).padding(.horizontal, 20)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 14) {
                            ForEach(artists) { a in
                                NavigationLink(value: Route.artist(a.id, "fav-artist-\(a.id)")) {
                                    VStack(spacing: 6) {
                                        Cover(id: a.coverArt, size: 100, radius: 50, symbol: "person.fill").frame(width: 100, height: 100)
                                        Text(a.title).font(.caption).lineLimit(1).frame(width: 100)
                                    }
                                }.buttonStyle(Press())
                            }
                        }.padding(.horizontal, 20)
                    }
                }
                if !albums.isEmpty {
                    Text("Albums").font(.title3.bold()).padding(.horizontal, 20)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 14) { ForEach(albums) { AlbumCard(album: $0, source: "fav-\($0.id)") } }.padding(.horizontal, 20)
                    }
                }
                if !songs.isEmpty {
                    HStack {
                        Text("Songs").font(.title3.bold())
                        Spacer()
                        Button { player.start(songs, context: Ref(kind: "favorites", id: "songs", name: "Favorites"), randomStart: true) } label: { Label("Shuffle", systemImage: "shuffle").font(.subheadline.weight(.semibold)) }
                    }.padding(.horizontal, 20)
                    SongList(songs: songs, context: Ref(kind: "favorites", id: "songs", name: "Favorites"), cover: true).padding(.horizontal, 20)
                }
                Color.clear.frame(height: 90)
            }
        }
        .task {
            error = (await app.load("getStarred2", [], Starred.self) { r in
                artists = r.starred2?.artist ?? []; albums = r.starred2?.album ?? []; songs = r.starred2?.song ?? []
            })?.localizedDescription
        }
    }
}
