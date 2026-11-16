import SwiftUI

struct DownloadsView: View {
    @Environment(Nav.self) private var nav
    var body: some View {
        let d = app.downloads
        let pinnedIds = Set(d.state.pinned.flatMap(\.songIds))
        let loose = d.songs.filter { !pinnedIds.contains($0.id) }
        List {
            Section {
                HStack {
                    Image(systemName: "internaldrive").foregroundStyle(.secondary)
                    Text("\(bytes(d.storageUsed)) · \(d.state.songs.count) songs").font(.subheadline.weight(.medium))
                    Spacer()
                }
            }
            if d.active {
                Section("Downloading") {
                    if let id = d.current, let s = d.state.queue[id] {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(s.name).lineLimit(1)
                            ProgressView(value: min(1, Double(d.got) / Double(max(s.size ?? 1, 1)))).tint(.accentColor)
                        }
                    }
                    HStack {
                        Text("\(d.pending) in queue").foregroundStyle(.secondary)
                        if let w = d.waitingFor { Text("· waiting for \(w)").foregroundStyle(.orange) }
                        Spacer()
                        Button(d.paused ? "Resume all" : "Pause all") { d.paused ? d.resumeAll() : d.pauseAll() }.font(.subheadline.weight(.semibold))
                    }.font(.subheadline)
                }
            }
            if !d.state.pinned.isEmpty {
                Section("Albums & playlists") {
                    ForEach(d.state.pinned) { p in
                        Button { nav.open(p.kind == "playlist" ? .playlist(p.cid, "dl-\(p.id)") : p.kind == "artist" ? .artist(p.cid, "dl-\(p.id)") : .album(p.cid, "dl-\(p.id)")) } label: {
                            HStack(spacing: 14) {
                                Cover(id: p.coverArt, size: 56, radius: 8, symbol: "music.note.list").frame(width: 56, height: 56)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.name).lineLimit(1)
                                    let (done, total) = d.progress(p.songIds.compactMap { id in d.state.songs[id] ?? d.state.queue[id] })
                                    Text(done == total ? "\(total) songs" : "\(done) of \(total) songs").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                        .swipeActions { Button("Remove", role: .destructive) { withAnimation(.smooth) { d.removeContainer(p) } } }
                    }
                }
            }
            if !loose.isEmpty {
                Section("Songs") {
                    ForEach(loose) { s in
                        SongRow(song: s, cover: true) { player.play(loose, from: s, context: Ref(kind: "downloads", id: "songs", name: "Downloads")) }
                            .swipeActions { Button("Remove", role: .destructive) { withAnimation(.smooth) { d.remove(s.id) } } }
                    }
                }
            }
            if d.state.songs.isEmpty && !d.active {
                Section {
                    ContentUnavailableView("Nothing downloaded yet", systemImage: "arrow.down.circle",
                                           description: Text("Open an album or playlist and tap the download button to listen offline."))
                        .listRowBackground(Color.clear)
                }
            }
            Section { Color.clear.frame(height: 60).listRowBackground(Color.clear) }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Downloads")
        .animation(.smooth, value: d.state.songs.count)
        .animation(.smooth, value: d.state.pinned.count)
    }
}
