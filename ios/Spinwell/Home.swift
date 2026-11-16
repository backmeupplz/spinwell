import SwiftUI

struct Shelf: Identifiable {
    var id: String
    var title: String
    var type: String
    var albums: [Album] = []
}

struct HomeView: View {
    @Environment(Nav.self) private var nav
    @State private var shelves: [Shelf] = [
        .init(id: "recent", title: "Recently played", type: "recent"),
        .init(id: "newest", title: "Recently added", type: "newest"),
        .init(id: "frequent", title: "Most played", type: "frequent"),
        .init(id: "starred", title: "Favorite albums", type: "starred"),
        .init(id: "random", title: "Rediscover", type: "random"),
    ]
    @State private var error: String?
    @State private var shuffling = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                HStack(spacing: 12) {
                    Button { Task { await shuffleAll() } } label: { Pill(title: "Shuffle all", symbol: "shuffle", prominent: true) }
                        .buttonStyle(Press()).accessibilityIdentifier("shuffle-all")
                    Button { nav.open(.favorites) } label: { Pill(title: "Favorites", symbol: "heart.fill") }.buttonStyle(Press())
                }
                .padding(.horizontal)
                if let error, shelves.allSatisfy({ $0.albums.isEmpty }) { ErrorLine(text: error).frame(maxWidth: .infinity) }
                ForEach(shelves.filter { !$0.albums.isEmpty }) { shelf in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(shelf.title).font(.title3.bold()).padding(.horizontal)
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 14) {
                                ForEach(shelf.albums) { a in AlbumCard(album: a, source: "home-\(shelf.id)-\(a.id)") }
                            }.padding(.horizontal).scrollTargetLayout()
                        }
                        .scrollTargetBehavior(.viewAligned)
                    }
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
                Color.clear.frame(height: 90)
            }
            .padding(.top, 8)
            .animation(.smooth, value: shelves.map { $0.albums.count })
        }
        .navigationTitle("Home")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { nav.open(.settings) } label: { Image(systemName: "gearshape") }.accessibilityIdentifier("settings")
            }
        }
        .refreshable { await refresh() }
        .task { await refresh() }
    }

    private func refresh() async {
        let types = shelves.map { ($0.id, $0.type) }
        await withTaskGroup(of: Void.self) { g in
            for (id, type) in types {
                g.addTask { @MainActor in
                    let e = await app.load("getAlbumList2", [("type", type), ("size", type == "random" ? "20" : "20")], AlbumList.self) { r in
                        if let i = shelves.firstIndex(where: { $0.id == id }), shelves[i].albums != r.list { shelves[i].albums = r.list }
                    }
                    if let e, !(e is ApiError && (e as? ApiError).map({ if case .offline = $0 { true } else { false } }) == true) { error = e.localizedDescription }
                }
            }
        }
    }

    private func shuffleAll() async {
        guard !shuffling else { return }
        shuffling = true; defer { shuffling = false }
        if app.offline {
            player.start(app.downloads.songs, context: Ref(kind: "shuffle", id: "all", name: "Downloads"), randomStart: true)
            return
        }
        if let r = try? await app.fetch("getRandomSongs", [("size", "200")], RandomSongs.self), !r.list.isEmpty {
            player.start(r.list, context: Ref(kind: "shuffle", id: "all", name: "Shuffle all"), randomStart: true)
        }
    }
}
