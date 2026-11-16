import SwiftUI

struct SongInfo: View {
    let song: Song
    var body: some View {
        NavigationStack {
            List {
                row("Title", song.title); row("Artist", song.artist); row("Album", song.album)
                row("Format", song.suffix?.uppercased())
                row("Bitrate", song.bitRate.map { "\($0) kbps" })
                row("Sample rate", song.samplingRate.map { "\($0) Hz" })
                row("Bit depth", song.bitDepth.map { "\($0)-bit" })
                row("Size", song.size.map(bytes))
                row("Duration", song.duration.map { clock(Double($0)) })
                row("Play count", song.playCount.map(String.init))
                row("Path", song.path)
            }
            .navigationTitle("Song info").navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
    @ViewBuilder private func row(_ k: String, _ v: String?) -> some View {
        if let v, !v.isEmpty { LabeledContent(k) { Text(v).foregroundStyle(.secondary).multilineTextAlignment(.trailing) } }
    }
}

/// Everything the `…` / long-press menu offers for a song.
struct SongMenu: View {
    let song: Song
    @Environment(Nav.self) private var nav: Nav?
    var onInfo: () -> Void = {}

    var body: some View {
        Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext([song]) }
        Button("Add to queue", systemImage: "text.line.last.and.arrowtriangle.forward") { player.addToQueue([song]) }
        if let a = song.albumId { Button("Go to album", systemImage: "square.stack") { nav?.open(.album(a, "menu-\(a)")) } }
        if let a = song.artistId { Button("Go to artist", systemImage: "music.mic") { nav?.open(.artist(a, "menu-\(a)")) } }
        let fav = app.isStarred(song)
        Button(fav ? "Unfavorite" : "Favorite", systemImage: fav ? "heart.slash" : "heart") { app.toggleStar(song.id, now: !fav) }
        if app.downloads.has(song.id) || app.downloads.queued(song.id) {
            Button("Remove download", systemImage: "trash", role: .destructive) { app.downloads.remove(song.id) }
        } else {
            Button("Download", systemImage: "arrow.down.circle") { app.downloads.add([song]) }
        }
        Button("Song info", systemImage: "info.circle", action: onInfo)
    }
}

struct DownloadGlyph: View {
    let id: String
    var body: some View {
        let d = app.downloads
        if d.has(id) { Image(systemName: "arrow.down.circle.fill").foregroundStyle(.secondary).font(.caption) }
        else if d.current == id { ProgressView().controlSize(.mini) }
        else if d.queued(id) { Image(systemName: "arrow.down.circle.dotted").foregroundStyle(.tertiary).font(.caption) }
    }
}

/// Track row: number or equalizer, title, featured artist, duration, download state.
struct SongRow: View {
    let song: Song
    var number: Int? = nil
    var cover = false
    var subtitle = true
    var swipe = true // swipe left to download / remove the download (List rows have their own swipe actions)
    let play: () -> Void
    @State private var info = false
    @State private var dx: CGFloat = 0
    @State private var swiped = 0
    @State private var swipeEnd = Date.distantPast

    private var haveDownload: Bool { app.downloads.has(song.id) || app.downloads.queued(song.id) }

    private var swipeReveal: some View {
        ZStack {
            (haveDownload ? Color.red : Color.accentColor)
            Image(systemName: haveDownload ? "trash.fill" : "arrow.down.circle.fill").foregroundStyle(.white).font(.title3)
        }
        .frame(width: max(0, -dx)).clipped().opacity(dx < -8 ? 1 : 0)
    }

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 24)
            .onChanged { v in
                guard swipe, v.translation.width < 0, abs(v.translation.width) > abs(v.translation.height) * 1.5 else { return }
                dx = max(v.translation.width, -96)
                swipeEnd = Date().addingTimeInterval(1)
            }
            .onEnded { _ in
                if dx < -64 {
                    swiped += 1
                    if haveDownload { app.downloads.remove(song.id) } else { app.downloads.add([song]) }
                }
                swipeEnd = Date().addingTimeInterval(0.35)
                withAnimation(.spring(duration: 0.3)) { dx = 0 }
            }
    }

    var body: some View {
        let isCurrent = player.current?.id == song.id
        let playable = app.playable(song)
        Button { if Date() > swipeEnd { play() } } label: { // a swipe that ends on the row must not also play it
            HStack(spacing: 12) {
                if cover { Cover(id: song.coverArt, size: 40, radius: 6).frame(width: 40, height: 40) }
                else {
                    ZStack {
                        if isCurrent { EqBars(active: player.playing).foregroundStyle(.tint) }
                        else if let number { Text("\(number)").font(.callout).monospacedDigit().foregroundStyle(.secondary) }
                    }.frame(width: 26)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.name).font(.body).lineLimit(1).foregroundStyle(isCurrent ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    if subtitle {
                        let sub = cover ? [song.artist, song.album].compactMap { $0 }.joined(separator: " · ") : song.featured
                        if let sub, !sub.isEmpty { Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    }
                }
                Spacer(minLength: 4)
                if app.isStarred(song) { Image(systemName: "heart.fill").font(.caption2).foregroundStyle(.pink) }
                DownloadGlyph(id: song.id)
                Text(clock(song.seconds)).font(.callout).monospacedDigit().foregroundStyle(.secondary)
                Menu { SongMenu(song: song) { info = true } } label: {
                    Image(systemName: "ellipsis").foregroundStyle(.secondary).frame(width: 36, height: 44)
                }
            }
            .frame(minHeight: 40)
            .contentShape(Rectangle())
            .opacity(playable ? 1 : 0.35)
        }
        .buttonStyle(PlayStyle(cover: song.coverArt))
        .disabled(!playable)
        .contextMenu { SongMenu(song: song) { info = true } }
        .sheet(isPresented: $info) { SongInfo(song: song) }
        .offset(x: dx)
        .background(alignment: .trailing) { swipeReveal }
        .simultaneousGesture(swipeGesture)
        .sensoryFeedback(.impact(weight: .light), trigger: swiped)
        .padding(.vertical, cover ? 8 : 6)
        .accessibilityIdentifier("song-\(song.id)")
    }
}

struct SongList: View {
    let songs: [Song]
    var context: Ref? = nil
    var numbered = false
    var cover = false
    var body: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(songs.enumerated()), id: \.offset) { i, s in
                SongRow(song: s, number: numbered ? (s.track ?? i + 1) : nil, cover: cover) {
                    player.play(songs, from: s, context: context)
                }
            }
        }
    }
}

/// Shuffle the whole library: 200 random songs from the server, or every download when the server is gone.
@MainActor func shuffleAllSongs(name: String) async {
    if !app.offline, let r = try? await app.fetch("getRandomSongs", [("size", "200")], RandomSongs.self), !r.list.isEmpty {
        player.start(r.list, context: Ref(kind: "shuffle", id: "all", name: name), randomStart: true)
    } else if app.offline { // also when that very request found the server gone
        player.start(app.downloads.songs, context: Ref(kind: "shuffle", id: "all", name: "Downloads"), randomStart: true)
    }
}

/// Looks like `.plain`, but the moment a row is pressed down it starts decoding the big cover and its color,
/// so by the time the finger lifts the full player has nothing left to compute.
struct PlayStyle: ButtonStyle {
    let cover: String?
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.white.opacity(0.1) : .clear, in: .rect(cornerRadius: 8)) // the pressed state of every list row
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { _, down in
            if down, let cover { Covers.prepare(cover) }
        }
    }
}
