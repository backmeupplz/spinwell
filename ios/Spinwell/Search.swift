import SwiftUI

struct SearchResults {
    var artists: [Artist] = []
    var albums: [Album] = []
    var songs: [Song] = []
    var isEmpty: Bool { artists.isEmpty && albums.isEmpty && songs.isEmpty }
}

enum TopResult {
    case artist(Artist), album(Album), song(Song)
}

/// Lower-cased and without accents, so "Кино", "кино" and "ёлка"/"елка" all meet.
func fold(_ s: String) -> String { s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil) }

/// Search what is on the phone: every field folded, prefix matches before the rest (stable otherwise).
func localSearch(_ query: String, songs: [Song], albums: [Album], artists: [Artist]) -> SearchResults {
    let q = fold(query.trimmingCharacters(in: .whitespaces))
    guard !q.isEmpty else { return SearchResults() }
    func hits<T>(_ l: [T], _ limit: Int, _ fields: (T) -> [String?]) -> [T] {
        var starts: [T] = [], inside: [T] = []
        for x in l {
            let f = fields(x).compactMap { $0 }.map(fold)
            if f.contains(where: { $0.hasPrefix(q) }) { starts.append(x) } else if f.contains(where: { $0.contains(q) }) { inside.append(x) }
        }
        return Array((starts + inside).prefix(limit))
    }
    return SearchResults(artists: hits(artists, 10) { [$0.name] }, albums: hits(albums, 15) { [$0.name, $0.artist] },
                         songs: hits(songs, 50) { [$0.title, $0.artist, $0.album] })
}

/// The "top result": the first artist, album or song whose name is exactly the query, else one that starts with it.
func topResult(_ query: String, _ r: SearchResults) -> TopResult? {
    let q = fold(query.trimmingCharacters(in: .whitespaces))
    guard !q.isEmpty else { return nil }
    for exact in [true, false] {
        func ok(_ s: String?) -> Bool { let f = fold(s ?? ""); return exact ? f == q : f.hasPrefix(q) }
        if let a = r.artists.first(where: { ok($0.name) }) { return .artist(a) }
        if let a = r.albums.first(where: { ok($0.name) }) { return .album(a) }
        if let s = r.songs.first(where: { ok($0.title) }) { return .song(s) }
    }
    return nil
}

/// Last 10 searches that led somewhere.
enum Recents {
    private static let key = "recentSearches"
    static var all: [String] { UserDefaults.standard.stringArray(forKey: key) ?? [] }
    static func add(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        UserDefaults.standard.set(Array(([t] + all.filter { fold($0) != fold(t) }).prefix(10)), forKey: key)
    }
    static func clear() { UserDefaults.standard.removeObject(forKey: key) }
}

struct SearchView: View {
    @State private var query = Hooks.string("query") ?? ""
    @State private var results = SearchResults()
    @State private var resultsFor = ""
    @State private var loading = false
    @State private var allSongs = false
    @State private var recents = Recents.all
    @FocusState private var focus: Bool

    private var q: String { query.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                if q.isEmpty { recentList }
                else {
                    if resultsFor == q, results.isEmpty, !loading { ContentUnavailableView.search(text: q).padding(.top, 40) }
                    content
                }
                Color.clear.frame(height: 90)
            }
            .padding(.top, 4)
        }
        .scrollDismissesKeyboard(.immediately)
        .navigationTitle("Search").navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Artists, albums, songs")
        .searchFocused($focus)
        .onSubmit(of: .search) { Recents.add(q); recents = Recents.all }
        .onAppear { focus = true; recents = Recents.all }
        .overlay(alignment: .top) { if loading, !results.isEmpty { ProgressView().controlSize(.small).padding(6) } }
        .task(id: "\(query)|\(app.offline)") { await run() }
    }

    private func remember() { Recents.add(q); recents = Recents.all }

    @ViewBuilder private var recentList: some View {
        if recents.isEmpty {
            ContentUnavailableView("Search your library", systemImage: "magnifyingglass", description: Text("Artists, albums and songs, even offline."))
                .padding(.top, 60)
        } else {
            HStack {
                Text("Recent searches").font(.title3.bold())
                Spacer()
                Button("Clear") { withAnimation(.smooth) { Recents.clear(); recents = [] } }.font(.subheadline.weight(.semibold)).frame(minHeight: 44)
            }.padding(.horizontal, 16)
            ForEach(recents, id: \.self) { r in
                Button { query = r } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "clock").foregroundStyle(.secondary).frame(width: 24)
                        Text(r).lineLimit(1)
                        Spacer()
                        Image(systemName: "arrow.up.left").font(.footnote).foregroundStyle(.tertiary)
                    }.padding(.horizontal, 16).frame(minHeight: 44).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder private var content: some View {
        if let top = topResult(q, results) { topCard(top) }
        if !results.artists.isEmpty {
            Text("Artists").font(.title3.bold()).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(results.artists) { a in
                        NavigationLink(value: Route.artist(a.id, "search-artist-\(a.id)")) {
                            VStack(spacing: 4) {
                                Cover(id: a.coverArt, size: 72, radius: 36, symbol: "person.fill").frame(width: 72, height: 72)
                                Text(a.title).font(.caption).lineLimit(1).frame(width: 72)
                            }
                        }.buttonStyle(Press()).simultaneousGesture(TapGesture().onEnded { remember() })
                    }
                }.padding(.horizontal, 16)
            }
        }
        if !results.albums.isEmpty {
            Text("Albums").font(.title3.bold()).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(results.albums) { AlbumCard(album: $0, width: 120, source: "search-\($0.id)").simultaneousGesture(TapGesture().onEnded { remember() }) }
                }.padding(.horizontal, 16)
            }
        }
        if !results.songs.isEmpty {
            let ref = Ref(kind: "search", id: q, name: "Search \"\(q)\"")
            let songs = results.songs
            Text("Songs").font(.title3.bold()).padding(.horizontal, 16)
            PlayRow { remember(); player.start(songs, context: ref) } shuffle: { remember(); player.start(songs, context: ref, randomStart: true) }
            ForEach(Array((allSongs ? songs : Array(songs.prefix(5))).enumerated()), id: \.offset) { _, s in
                SongRow(song: s, cover: true) { remember(); player.play(songs, from: s, context: ref) }.padding(.horizontal, 16)
            }
            if songs.count > 5 {
                Button(allSongs ? "Show less" : "See all \(songs.count) songs") { withAnimation(.smooth) { allSongs.toggle() } }
                    .font(.subheadline.weight(.semibold)).padding(.horizontal, 16).frame(minHeight: 44)
            }
        }
    }

    /// The exact or prefix match, big and first.
    @ViewBuilder private func topCard(_ t: TopResult) -> some View {
        Text("Top result").font(.title3.bold()).padding(.horizontal, 16)
        switch t {
        case .artist(let a):
            NavigationLink(value: Route.artist(a.id, "search-top-\(a.id)")) { topRow(a.coverArt, 28, "person.fill", a.title, "Artist") }
                .buttonStyle(Press()).simultaneousGesture(TapGesture().onEnded { remember() })
        case .album(let a):
            NavigationLink(value: Route.album(a.id, "search-top-\(a.id)")) { topRow(a.coverArt, 8, "music.note", a.title, "Album · \(a.artist ?? "")") }
                .buttonStyle(Press()).simultaneousGesture(TapGesture().onEnded { remember() })
        case .song(let s):
            Button { remember(); player.play(results.songs, from: s, context: Ref(kind: "search", id: q, name: "Search \"\(q)\"")) } label: {
                topRow(s.coverArt, 8, "music.note", s.name, "Song · \(s.artist ?? "")")
            }.buttonStyle(Press())
        }
    }

    private func topRow(_ cover: String?, _ radius: CGFloat, _ symbol: String, _ title: String, _ caption: String) -> some View {
        HStack(spacing: 12) {
            Cover(id: cover, size: 56, radius: radius, symbol: symbol).frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                Text(caption).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(10).background(.white.opacity(0.08), in: .rect(cornerRadius: 14)).padding(.horizontal, 16).contentShape(Rectangle())
    }

    /// Debounced; the task is cancelled by the next keystroke, and the old results stay up until the new ones arrive.
    private func run() async {
        guard !q.isEmpty else { results = SearchResults(); resultsFor = ""; loading = false; allSongs = false; return }
        loading = true
        defer { if !Task.isCancelled { loading = false } }
        try? await Task.sleep(for: .milliseconds(200))
        guard !Task.isCancelled else { return }
        let asked = q
        if app.offline {
            let d = app.downloads
            results = localSearch(asked, songs: d.songs, albums: d.albums, artists: d.artists); resultsFor = asked
            return
        }
        if let r = try? await app.fetch("search3", [("query", asked), ("artistCount", "10"), ("albumCount", "15"), ("songCount", "50")], SearchR.self), !Task.isCancelled {
            results = SearchResults(artists: r.searchResult3?.artist ?? [], albums: r.searchResult3?.album ?? [], songs: r.searchResult3?.song ?? [])
            resultsFor = asked
        }
    }
}
