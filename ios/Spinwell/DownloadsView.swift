import SwiftUI

struct DownloadsView: View {
    @Environment(Nav.self) private var nav
    var body: some View {
        let d = app.downloads
        let pinnedIds = Set(d.state.pinned.flatMap(\.songIds))
        let loose = d.songs.filter { !pinnedIds.contains($0.id) }
        let ref = Ref(kind: "downloads", id: "songs", name: "Downloads")
        let edge = EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16)
        List {
            Section {
                HStack(spacing: 8) {
                    Image(systemName: "internaldrive").foregroundStyle(.secondary)
                    Text(verbatim: "\(bytes(d.storageUsed)) · \(String(localized: "\(d.state.songs.count) songs"))").font(.footnote.weight(.medium))
                    Spacer()
                }.listRowInsets(edge)
                if !d.songs.isEmpty {
                    PlayRow { player.start(d.songs, context: ref) } shuffle: { player.start(d.songs, context: ref, randomStart: true) }
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
            }
            if d.active {
                Section("Downloading") {
                    if let id = d.current, let s = d.state.queue[id] {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(s.name).lineLimit(1)
                            ProgressView(value: min(1, Double(d.got) / Double(max(s.size ?? 1, 1)))).tint(.accentColor)
                        }.listRowInsets(edge)
                    }
                    HStack(spacing: 4) {
                        Text("\(d.pending) in queue").foregroundStyle(.secondary)
                        if d.paused { Text(verbatim: "·").foregroundStyle(.secondary); Text("Paused").foregroundStyle(.secondary) }
                        else if let w = d.waitingFor { Text(verbatim: "·").foregroundStyle(.orange); Text(w).foregroundStyle(.orange) }
                        Spacer(minLength: 8)
                        Button { d.paused ? d.resumeAll() : d.pauseAll() } label: { d.paused ? Text("Resume all") : Text("Pause all") }.font(.subheadline.weight(.semibold))
                    }.font(.subheadline).listRowInsets(edge)
                }
            }
            if !d.state.pinned.isEmpty {
                Section("Albums and playlists") {
                    ForEach(d.state.pinned) { p in
                        let (done, total) = d.progress(p.songIds.compactMap { id in d.state.songs[id] ?? d.state.queue[id] })
                        Button { nav.open(p.kind == "playlist" ? .playlist(p.cid, "dl-\(p.id)") : p.kind == "artist" ? .artist(p.cid, "dl-\(p.id)") : .album(p.cid, "dl-\(p.id)")) } label: {
                            ListRow(cover: p.coverArt, symbol: "music.note.list", title: p.name, caption: done == total ? String(localized: "\(total) songs") : String(localized: "\(done)/\(total) songs"))
                        }
                        .buttonStyle(.plain).listRowInsets(EdgeInsets())
                        .swipeActions { Button("Remove", role: .destructive) { withAnimation(.smooth) { d.removeContainer(p) } } }
                    }
                }
            }
            if !loose.isEmpty {
                Section("Songs") {
                    ForEach(loose) { s in
                        SongRow(song: s, cover: true, swipe: false) { player.play(loose, from: s, context: ref) }
                            .listRowInsets(edge)
                            .swipeActions { Button("Remove", role: .destructive) { withAnimation(.smooth) { d.remove(s.id) } } }
                    }
                }
            }
            if d.state.songs.isEmpty && !d.active {
                Section {
                    ContentUnavailableView { Image(systemName: "arrow.down.circle") }
                        description: { Text("Nothing downloaded yet. Tap the download arrow on an album, playlist or artist, or swipe a song left. Downloads play without a connection.") }
                        .listRowBackground(Color.clear)
                }
            }
            Section { Color.clear.frame(height: 60).listRowBackground(Color.clear) }
        }
        .listStyle(.plain)
        .navigationTitle("Downloads").navigationBarTitleDisplayMode(.inline)
        .animation(.smooth, value: d.state.songs.count)
        .animation(.smooth, value: d.state.pinned.count)
    }
}
