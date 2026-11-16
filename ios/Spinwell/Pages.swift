import SwiftUI

struct AlbumPage: View { let id: String; var body: some View { CollectionPage(kind: "album", id: id) } }
struct PlaylistPage: View { let id: String; var body: some View { CollectionPage(kind: "playlist", id: id) } }

/// Album and playlist share one page: hero on the cover's color, action row, tracks grouped by disc.
struct CollectionPage: View {
    let kind: String
    let id: String
    @Environment(Nav.self) private var nav
    @State private var title = ""
    @State private var artist: String?
    @State private var artistId: String?
    @State private var coverArt: String?
    @State private var meta = ""
    @State private var songs: [Song] = []
    @State private var starred = false
    @State private var notes: String?
    @State private var tint: Color?
    @State private var error: String?
    @State private var loaded = false
    @State private var confirmRemove = false

    /// Disc, then track; songs without numbers keep the server's order. Play and the list then agree.
    static func inDiscOrder(_ l: [Song]) -> [Song] {
        l.enumerated().sorted { ($0.element.discNumber ?? 1, $0.element.track ?? 0, $0.offset) < ($1.element.discNumber ?? 1, $1.element.track ?? 0, $1.offset) }.map(\.element)
    }

    private var ref: Ref { Ref(kind: kind, id: id, name: title) }
    private var discs: [(Int, [Song])] {
        Dictionary(grouping: songs) { $0.discNumber ?? 1 }.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                hero
                actions
                if let error, songs.isEmpty { ErrorLine(text: error) }
                tracks
                if let n = notes, !n.isEmpty {
                    Text(plain(n)).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 16).frame(maxWidth: .infinity, alignment: .leading)
                }
                Color.clear.frame(height: 90)
            }
        }
        .background(alignment: .top) {
            LinearGradient(colors: [(tint ?? Color(white: 0.25)).opacity(0.7), (tint ?? .clear).opacity(0.15), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 520).ignoresSafeArea().animation(.easeInOut(duration: 0.5), value: tint)
        }
        .scrollIndicators(.hidden)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: app.offline) { await load() }
        .task(id: coverArt) {
            guard let c = coverArt else { return }
            _ = await Covers.get(c, size: 600)
            if let u = Covers.color(c) { tint = Color(uiColor: u) }
        }
        .onChange(of: songs) { _, s in if app.downloads.isPinned(kind, id) { app.downloads.sync(kind, id, songs: s) } }
        .confirmationDialog("Remove downloaded songs?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove download", role: .destructive) { if let p = app.downloads.state.pinned.first(where: { $0.id == kind + ":" + id }) { app.downloads.removeContainer(p) } else { songs.forEach { app.downloads.remove($0.id) } } }
        }
    }

    private var hero: some View {
        VStack(spacing: 3) {
            Cover(id: coverArt, size: 200, radius: 12, symbol: kind == "playlist" ? "music.note.list" : "music.note")
                .frame(width: 200, height: 200)
                .shadow(color: .black.opacity(0.4), radius: 16, y: 8)
                .padding(.top, 2).padding(.bottom, 8)
            Text(title).font(.title3.bold()).multilineTextAlignment(.center).lineLimit(2)
            if let artist {
                if let artistId, kind == "album" {
                    Button(artist) { nav.open(.artist(artistId, "album-\(id)-artist")) }.font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
                } else { Text(artist).font(.subheadline.weight(.semibold)).foregroundStyle(.tint) }
            }
            HStack(spacing: 6) {
                Text(meta).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                if kind == "album", let b = albumBadge(songs) {
                    Text(b).font(.system(size: 10, weight: .semibold)).padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.white.opacity(0.14), in: .capsule)
                }
            }
        }
        .padding(.horizontal, 16)
        .opacity(loaded || !title.isEmpty ? 1 : 0)
        .animation(.smooth, value: title)
    }

    private var actions: some View {
        HStack(spacing: 6) {
            Button { player.start(songs, context: ref) } label: { Pill(title: "Play", symbol: "play.fill", prominent: true) }
                .buttonStyle(Press()).disabled(songs.isEmpty).accessibilityIdentifier("play")
            Button { player.start(songs, context: ref, randomStart: true) } label: { Pill(title: "Shuffle", symbol: "shuffle") }
                .buttonStyle(Press()).disabled(songs.isEmpty).accessibilityIdentifier("shuffle")
            DownloadButton(songs: songs, kind: kind, cid: id, name: title, coverArt: coverArt) { confirmRemove = true }
            if kind == "album" {
                Button {
                    starred.toggle(); app.toggleStar(id, now: starred, kind: "albumId")
                } label: {
                    RoundGlyph(symbol: starred ? "heart.fill" : "heart", color: starred ? .pink : .white).symbolEffect(.bounce, value: starred)
                }.buttonStyle(.plain)
            }
            Menu {
                Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(songs) }
                Button("Add to queue", systemImage: "text.line.last.and.arrowtriangle.forward") { player.addToQueue(songs) }
                RepeatMenuItem(noun: kind, ref: ref) { songs }
            } label: {
                RoundGlyph(symbol: "ellipsis")
            }
        }
        .padding(.horizontal, 16)
    }

    private var tracks: some View {
        LazyVStack(spacing: 0) {
            if kind == "playlist" {
                ForEach(Array(songs.enumerated()), id: \.offset) { i, s in
                    SongRow(song: s, number: nil, cover: true) { player.play(songs, from: s, context: ref) }
                }
            } else {
                ForEach(discs, id: \.0) { disc, list in
                    if discs.count > 1 {
                        Text("Disc \(disc)").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 10).padding(.bottom, 2)
                    }
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                        SongRow(song: s, number: s.track ?? i + 1) { player.play(songs, from: s, context: ref) }
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .animation(.smooth, value: songs.count)
    }

    private func load() async {
        if kind == "album" {
            let e = await app.load("getAlbum", [("id", id)], AlbumR.self) { r in
                let a = r.album
                title = a.title; artist = a.artist; artistId = a.artistId; coverArt = a.coverArt
                songs = Self.inDiscOrder(a.song ?? []); starred = app.isStarred(id, a.starred != nil)
                let genre = a.genre.map { [$0] } ?? []
                meta = ([a.year.map(String.init)].compactMap { $0 } + genre + ["\(songs.count) songs", minutes(a.duration ?? Int(songs.reduce(0) { $0 + $1.seconds }))]).joined(separator: " · ")
                loaded = true
            }
            if songs.isEmpty, app.offline, let a = app.downloads.albums.first(where: { $0.id == id }) {
                title = a.title; artist = a.artist; artistId = a.artistId; coverArt = a.coverArt
                songs = Self.inDiscOrder(app.downloads.songs(album: id))
                meta = ([a.year.map(String.init)].compactMap { $0 } + ["\(songs.count) songs"]).joined(separator: " · ")
                loaded = true
            } else if let e { error = e.localizedDescription }
            if !app.offline, let n = try? await app.fetch("getAlbumInfo2", [("id", id)], AlbumInfoR.self) { notes = n.albumInfo?.notes }
        } else {
            let e = await app.load("getPlaylist", [("id", id)], PlaylistR.self) { r in
                let p = r.playlist
                title = p.title; coverArt = p.coverArt; artist = nil
                songs = p.entry ?? []
                meta = [p.owner, "\(p.songCount ?? songs.count) songs", minutes(p.duration ?? Int(songs.reduce(0) { $0 + $1.seconds }))].compactMap { $0 }.joined(separator: " · ")
                loaded = true
            }
            if songs.isEmpty, app.offline, let p = app.downloads.state.pinned.first(where: { $0.id == "playlist:" + id }) {
                title = p.name; coverArt = p.coverArt
                songs = p.songIds.compactMap { app.downloads.state.songs[$0] }
                meta = "\(songs.count) songs"; loaded = true
            } else if let e { error = e.localizedDescription }
        }
    }
}

struct DownloadButton: View {
    let songs: [Song]
    let kind: String, cid: String, name: String
    let coverArt: String?
    let remove: () -> Void
    /// Containers whose songs aren't known up front (an artist) gather them here, then add them; `loading` shows a spinner meanwhile.
    var start: (() async -> Void)? = nil
    var loading = false

    var body: some View {
        let (done, total) = app.downloads.progress(songs)
        let complete = total > 0 && done == total
        let busy = !complete && songs.contains { app.downloads.queued($0.id) }
        Button {
            if complete || busy { remove() }
            else if let start { Task { await start() } }
            else { app.downloads.add(songs, pin: Pinned(kind: kind, cid: cid, name: name, coverArt: coverArt, songIds: [])) }
        } label: {
            ZStack {
                if loading { ProgressView().controlSize(.small) }
                else if complete { Image(systemName: "checkmark").font(.body.weight(.bold)).foregroundStyle(.green).transition(.scale.combined(with: .opacity)) }
                else if busy {
                    Circle().stroke(.white.opacity(0.2), lineWidth: 3).frame(width: 22, height: 22)
                    Circle().trim(from: 0, to: max(0.05, Double(done) / Double(max(total, 1)))).stroke(.tint, style: StrokeStyle(lineWidth: 3, lineCap: .round)).frame(width: 22, height: 22).rotationEffect(.degrees(-90))
                } else { Image(systemName: "arrow.down.circle").font(.body.weight(.medium)) }
            }
            .frame(width: 40, height: 40).glassEffect(.regular.interactive(), in: .circle).padding(2).contentShape(Circle())
            .animation(.smooth, value: done)
        }
        .buttonStyle(.plain).disabled((songs.isEmpty && start == nil) || loading).accessibilityIdentifier("download")
    }
}

/// "Repeat album" / "Stop repeating album" in a container's `…` menu: loops that container (repeat all). Starts it when something else plays.
struct RepeatMenuItem: View {
    let noun: String
    let ref: Ref
    let songs: () async -> [Song]
    var body: some View {
        let on = player.isRepeating(ref)
        Button(on ? "Stop repeating \(noun)" : "Repeat \(noun)", systemImage: on ? "repeat.circle.fill" : "repeat") {
            Task { await player.repeatContext(await songs(), ref) }
        }
    }
}
