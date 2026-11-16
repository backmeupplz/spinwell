import SwiftUI

struct ArtistPage: View {
    let id: String
    @State private var artist: Artist?
    @State private var top: [Song] = []
    @State private var showAllTop = false
    @State private var similar: [Artist] = []
    @State private var bio: String?
    @State private var bioOpen = false
    @State private var error: String?
    @State private var tint: Color?
    @State private var busy = false

    private var albums: [Album] { (artist?.album ?? []).sorted { ($0.year ?? 0) > ($1.year ?? 0) } }
    private func isSingle(_ a: Album) -> Bool { (a.releaseTypes ?? []).contains { ["single", "ep"].contains($0.lowercased()) } }
    private var cover: String? { artist?.coverArt ?? albums.first?.coverArt }
    private var ref: Ref { Ref(kind: "artist", id: id, name: artist?.title ?? "") }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                hero
                HStack(spacing: 12) {
                    Button { Task { await playAll(shuffle: false) } } label: { Pill(title: "Play", symbol: "play.fill", prominent: true) }.buttonStyle(Press())
                    Button { Task { await playAll(shuffle: true) } } label: { Pill(title: "Shuffle", symbol: "shuffle") }.buttonStyle(Press())
                }.padding(.horizontal, 20).disabled(busy)
                if let error, artist == nil { ErrorLine(text: error).frame(maxWidth: .infinity) }
                if !top.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Top songs").font(.title3.bold()).padding(.bottom, 4)
                        ForEach(Array((showAllTop ? top : Array(top.prefix(5))).enumerated()), id: \.element.id) { i, s in
                            SongRow(song: s, number: i + 1, cover: true) { player.play(top, from: s, context: Ref(kind: "artist", id: id, name: "\(artist?.title ?? "") Top songs")) }
                        }
                        if top.count > 5 { Button(showAllTop ? "Show less" : "Show more") { withAnimation(.smooth) { showAllTop.toggle() } }.font(.subheadline.weight(.semibold)).padding(.top, 6) }
                    }.padding(.horizontal, 20)
                }
                let main = albums.filter { !isSingle($0) }, singles = albums.filter(isSingle)
                grid("Albums", main)
                grid("Singles & EPs", singles)
                if !similar.isEmpty {
                    Text("Similar artists").font(.title3.bold()).padding(.horizontal, 20)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 14) {
                            ForEach(similar) { a in
                                NavigationLink(value: Route.artist(a.id, "sim-\(a.id)")) {
                                    VStack(spacing: 6) {
                                        Cover(id: a.coverArt, size: 90, radius: 45, symbol: "person.fill").frame(width: 90, height: 90)
                                        Text(a.title).font(.caption).lineLimit(1).frame(width: 90)
                                    }
                                }.buttonStyle(Press())
                            }
                        }.padding(.horizontal, 20)
                    }
                }
                if let bio, !bio.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("About").font(.title3.bold())
                        Text(bio).font(.callout).foregroundStyle(.secondary).lineLimit(bioOpen ? nil : 3)
                    }
                    .padding(.horizontal, 20).onTapGesture { withAnimation(.smooth) { bioOpen.toggle() } }
                }
                Color.clear.frame(height: 90)
            }
        }
        .ignoresSafeArea(edges: .top)
        .scrollIndicators(.hidden)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .task(id: cover) {
            guard let c = cover else { return }
            _ = await Covers.get(c, size: 600)
            if let u = Covers.color(c) { tint = Color(uiColor: u) }
        }
    }

    private var hero: some View {
        GeometryReader { g in
            let minY = g.frame(in: .global).minY
            ZStack(alignment: .bottomLeading) {
                Cover(id: cover, size: 400, radius: 0, symbol: "person.fill")
                    .frame(width: g.size.width, height: g.size.height + max(0, minY)).clipped()
                    .offset(y: -max(0, minY))
                LinearGradient(colors: [.clear, .black.opacity(0.35), .black], startPoint: .center, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 2) {
                    Text(artist?.title ?? "").font(.system(size: 38, weight: .bold)).lineLimit(2).minimumScaleFactor(0.7)
                    Text("\(albums.count) album\(albums.count == 1 ? "" : "s")").font(.subheadline).foregroundStyle(.white.opacity(0.7))
                }.padding(.horizontal, 20).padding(.bottom, 12)
            }
        }
        .frame(height: 400)
    }

    @ViewBuilder private func grid(_ title: String, _ list: [Album]) -> some View {
        if !list.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text(title).font(.title3.bold()).padding(.horizontal, 20)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 18) {
                    ForEach(list) { AlbumCard(album: $0, width: nil, source: "artist-\(id)-\($0.id)") }
                }.padding(.horizontal, 20)
            }
        }
    }

    private func load() async {
        let e = await app.load("getArtist", [("id", id)], ArtistR.self) { artist = $0.artist }
        if let e { error = e.localizedDescription }
        guard let name = artist?.name else { return }
        let params = app.extensions.contains("topSongsByArtistId") ? [("id", id), ("count", "20")] : [("artist", name), ("count", "20")]
        await app.load("getTopSongs", params, TopSongsR.self) { top = $0.topSongs?.song ?? [] }
        await app.load("getArtistInfo2", [("id", id)], ArtistInfoR.self) { r in
            bio = r.artistInfo2?.biography.map(plain); similar = r.artistInfo2?.similarArtist ?? []
        }
    }

    /// The artist's songs, newest album first (cached album pages make this fast after the first time).
    private func playAll(shuffle: Bool) async {
        busy = true; defer { busy = false }
        var all: [Song] = []
        for a in albums {
            var got: [Song] = []
            await app.load("getAlbum", [("id", a.id)], AlbumR.self) { got = $0.album.song ?? [] }
            all += got.sorted { ($0.discNumber ?? 1, $0.track ?? 0) < ($1.discNumber ?? 1, $1.track ?? 0) }
        }
        guard !all.isEmpty else { return }
        if shuffle { player.start(all, context: ref, randomStart: true) } else { player.start(all, context: ref) }
    }
}
