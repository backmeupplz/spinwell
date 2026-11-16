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
    @State private var photo: String?
    @State private var busy = false
    @State private var loadingDl = false
    @State private var confirmRemove = false

    /// The songs this artist's Download pinned (downloaded or still queued).
    private var pinnedSongs: [Song] {
        guard let p = app.downloads.state.pinned.first(where: { $0.id == "artist:" + id }) else { return [] }
        return p.songIds.compactMap { app.downloads.state.songs[$0] ?? app.downloads.state.queue[$0] }
    }

    private func downloadAll() async {
        loadingDl = true; defer { loadingDl = false }
        let all = await allSongs()
        guard !all.isEmpty else { return }
        app.downloads.add(all, pin: Pinned(kind: "artist", cid: id, name: name, coverArt: cover, songIds: []))
    }

    private var offline: Bool { app.offline }
    /// Offline this is what is on the phone: cached albums that have a downloaded song, plus downloaded albums the cache doesn't know.
    private var albums: [Album] {
        let cached = artist?.album ?? []
        let l: [Album]
        if offline {
            let have = Set(app.downloads.songs(artist: id).compactMap(\.albumId))
            let ids = Set(cached.map(\.id))
            l = cached.filter { have.contains($0.id) } + app.downloads.albums.filter { have.contains($0.id) && !ids.contains($0.id) }
        } else { l = cached }
        return l.sorted { ($0.year ?? 0) > ($1.year ?? 0) }
    }
    private func isSingle(_ a: Album) -> Bool { (a.releaseTypes ?? []).contains { ["single", "ep"].contains($0.lowercased()) } }
    /// The artist's own picture (Navidrome serves it as the artist cover id), else `largeImageUrl`, else their newest album's cover.
    private var cover: String? { artist?.coverArt ?? photo ?? albums.first?.coverArt }
    private var name: String { artist?.title ?? "" }
    private var ref: Ref { Ref(kind: "artist", id: id, name: name) }
    /// Top songs online, the downloaded songs of this artist offline.
    private var songs: [Song] { offline ? app.downloads.songs(artist: id) : top }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                hero
                HStack(spacing: 6) {
                    Button { Task { await playAll(shuffle: false) } } label: { Pill(title: "Play", symbol: "play.fill", prominent: true) }
                        .buttonStyle(Press()).accessibilityIdentifier("play")
                    Button { Task { await playAll(shuffle: true) } } label: { Pill(title: "Shuffle", symbol: "shuffle") }
                        .buttonStyle(Press()).accessibilityIdentifier("shuffle")
                    DownloadButton(songs: pinnedSongs, kind: "artist", cid: id, name: name, coverArt: cover, remove: { confirmRemove = true },
                                   start: { await downloadAll() }, loading: loadingDl)
                        .disabled(offline && pinnedSongs.isEmpty)
                    Menu {
                        RepeatMenuItem(noun: "artist", ref: ref) { await allSongs() }
                    } label: { RoundGlyph(symbol: "ellipsis") }
                }.padding(.horizontal, 16).disabled(busy)
                if let error, artist == nil { ErrorLine(text: error).frame(maxWidth: .infinity) }
                if !songs.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(offline ? "Downloaded songs" : "Top songs").font(.title3.bold()).padding(.bottom, 2)
                        let shown = showAllTop ? songs : Array(songs.prefix(5))
                        ForEach(shown) { s in
                            SongRow(song: s, cover: true) { player.play(songs, from: s, context: Ref(kind: "artist", id: id, name: offline ? name : "\(name) Top songs")) }
                        }
                        if songs.count > 5 { Button(showAllTop ? "Show less" : "Show more") { withAnimation(.smooth) { showAllTop.toggle() } }.font(.subheadline.weight(.semibold)).padding(.top, 4).frame(minHeight: 36) }
                    }.padding(.horizontal, 16)
                }
                let main = albums.filter { !isSingle($0) }, singles = albums.filter(isSingle)
                shelf("Albums", main)
                shelf("Singles & EPs", singles)
                let sim = offline ? similar.filter { app.downloads.artistIds.contains($0.id) } : similar
                if !sim.isEmpty {
                    Text("Similar artists").font(.title3.bold()).padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(sim) { a in
                                NavigationLink(value: Route.artist(a.id, "sim-\(a.id)")) {
                                    VStack(spacing: 4) {
                                        Cover(id: a.coverArt, size: 72, radius: 36, symbol: "person.fill").frame(width: 72, height: 72)
                                        Text(a.title).font(.caption).lineLimit(1).frame(width: 72)
                                    }
                                }.buttonStyle(Press())
                            }
                        }.padding(.horizontal, 16)
                    }
                }
                if let bio, !bio.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("About").font(.title3.bold())
                        Text(bio).font(.callout).foregroundStyle(.secondary).lineLimit(bioOpen ? nil : 3)
                    }
                    .padding(.horizontal, 16).onTapGesture { withAnimation(.smooth) { bioOpen.toggle() } }
                }
                Color.clear.frame(height: 90)
            }
        }
        .ignoresSafeArea(edges: .top)
        .scrollIndicators(.hidden)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: app.offline) { await load() }
        .confirmationDialog("Remove downloaded songs?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove download", role: .destructive) {
                if let p = app.downloads.state.pinned.first(where: { $0.id == "artist:" + id }) { app.downloads.removeContainer(p) }
            }
        }
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
                VStack(alignment: .leading, spacing: 0) {
                    Text(name).font(.system(size: 30, weight: .bold)).lineLimit(2).minimumScaleFactor(0.7)
                    Text("\(albums.count) album\(albums.count == 1 ? "" : "s")\(offline ? " downloaded" : "")").font(.footnote).foregroundStyle(.white.opacity(0.7))
                }.padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
        .frame(height: 240)
    }

    @ViewBuilder private func shelf(_ title: String, _ list: [Album]) -> some View {
        if !list.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.title3.bold()).padding(.horizontal, 16)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(list) { AlbumCard(album: $0, source: "artist-\(id)-\($0.id)") }
                    }.padding(.horizontal, 16)
                }
            }
        }
    }

    private func load() async {
        let e = await app.load("getArtist", [("id", id)], ArtistR.self) { artist = $0.artist }
        if artist == nil, app.offline, let a = app.downloads.artists.first(where: { $0.id == id }) { artist = a }
        else if let e, artist == nil { error = e.localizedDescription }
        guard let name = artist?.name else { return }
        let params = app.extensions.contains("topSongsByArtistId") ? [("id", id), ("count", "20")] : [("artist", name), ("count", "20")]
        await app.load("getTopSongs", params, TopSongsR.self) { top = $0.topSongs?.song ?? [] }
        await app.load("getArtistInfo2", [("id", id)], ArtistInfoR.self) { r in
            bio = r.artistInfo2?.biography.map(plain); photo = r.artistInfo2?.largeImageUrl.flatMap { $0.isEmpty ? nil : $0 }; similar = r.artistInfo2?.similarArtist ?? []
        }
    }

    /// The artist's songs: offline the downloads, online each album newest first (cached album pages make this fast after the first time).
    private func allSongs() async -> [Song] {
        if app.offline { return app.downloads.songs(artist: id) }
        var all: [Song] = []
        for a in albums {
            var got: [Song] = []
            await app.load("getAlbum", [("id", a.id)], AlbumR.self) { got = $0.album.song ?? [] }
            all += got.sorted { ($0.discNumber ?? 1, $0.track ?? 0) < ($1.discNumber ?? 1, $1.track ?? 0) }
        }
        // the server went away on the way: what is downloaded still plays
        return all.isEmpty && app.offline ? app.downloads.songs(artist: id) : all
    }

    private func playAll(shuffle: Bool) async {
        busy = true; defer { busy = false }
        let all = await allSongs()
        guard !all.isEmpty else { return }
        if shuffle { player.start(all, context: ref, randomStart: true) } else { player.start(all, context: ref) }
    }
}
