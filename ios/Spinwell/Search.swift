import SwiftUI

struct SearchView: View {
    @State private var query = Hooks.string("query") ?? ""
    @State private var artists: [Artist] = []
    @State private var albums: [Album] = []
    @State private var songs: [Song] = []
    @State private var searched = false
    @FocusState private var focus: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if query.isEmpty {
                    ContentUnavailableView("Search your library", systemImage: "magnifyingglass", description: Text("Artists, albums and songs."))
                        .padding(.top, 60)
                } else if searched && artists.isEmpty && albums.isEmpty && songs.isEmpty {
                    ContentUnavailableView.search(text: query).padding(.top, 60)
                }
                if !artists.isEmpty {
                    Text("Artists").font(.title3.bold()).padding(.horizontal, 20)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 14) {
                            ForEach(artists) { a in
                                NavigationLink(value: Route.artist(a.id, "search-artist-\(a.id)")) {
                                    VStack(spacing: 6) {
                                        Cover(id: a.coverArt, size: 96, radius: 48, symbol: "person.fill").frame(width: 96, height: 96)
                                        Text(a.title).font(.caption).lineLimit(1).frame(width: 96)
                                    }
                                }.buttonStyle(Press())
                            }
                        }.padding(.horizontal, 20)
                    }
                }
                if !albums.isEmpty {
                    Text("Albums").font(.title3.bold()).padding(.horizontal, 20)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 14) { ForEach(albums) { AlbumCard(album: $0, width: 130, source: "search-\($0.id)") } }.padding(.horizontal, 20)
                    }
                }
                if !songs.isEmpty {
                    Text("Songs").font(.title3.bold()).padding(.horizontal, 20)
                    SongList(songs: songs, context: Ref(kind: "search", id: query, name: "Search \"\(query)\""), cover: true).padding(.horizontal, 20)
                }
                Color.clear.frame(height: 90)
            }
            .animation(.smooth, value: songs.count + albums.count + artists.count)
        }
        .scrollDismissesKeyboard(.immediately)
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Artists, albums, songs")
        .searchFocused($focus)
        .onAppear { focus = true }
        .task(id: query) { await run() }
    }

    private func run() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { artists = []; albums = []; songs = []; searched = false; return }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        if app.offline {
            let all = app.downloads.songs.filter { [$0.title, $0.artist, $0.album].compactMap { $0 }.contains { $0.localizedCaseInsensitiveContains(q) } }
            songs = all; albums = []; artists = []; searched = true
            return
        }
        if let r = try? await app.fetch("search3", [("query", q), ("artistCount", "10"), ("albumCount", "15"), ("songCount", "30")], SearchR.self), !Task.isCancelled {
            artists = r.searchResult3?.artist ?? []; albums = r.searchResult3?.album ?? []; songs = r.searchResult3?.song ?? []; searched = true
        }
    }
}
