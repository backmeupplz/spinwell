import SwiftUI

/// Spotify-style queue: Now playing, Next in queue (added by hand), Next from <context>.
struct UpNextView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let q = player.q
        NavigationStack {
            List {
                if let cur = q.current {
                    Section("Now playing") { row(cur, current: true) }
                }
                if !q.nextInQueue.isEmpty {
                    Section {
                        ForEach(Array(q.nextInQueue)) { qi in row(qi) }
                            .onMove { from, to in let b = q.index + 1; player.move(from: IndexSet(from.map { $0 + b }), to: to + b) }
                    } header: {
                        HStack {
                            Text("Next in queue")
                            Spacer()
                            Button("Clear") { withAnimation(.smooth) { player.clearAdded() } }.font(.subheadline.weight(.semibold)).textCase(nil)
                        }
                    }
                }
                if !q.nextFromContext.isEmpty {
                    Section("Next from \(q.context?.name ?? "queue")") {
                        ForEach(Array(q.nextFromContext)) { qi in row(qi) }
                            .onMove { from, to in let b = q.index + 1 + q.addedRun; player.move(from: IndexSet(from.map { $0 + b }), to: to + b) }
                    }
                }
            }
            .listStyle(.plain)
            .environment(\.editMode, .constant(.active))
            .animation(.smooth(duration: 0.45), value: player.revision)
            .navigationTitle("Up next").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { withAnimation(.smooth(duration: 0.5)) { player.setShuffle(!q.shuffle) } } label: {
                        Image(systemName: "shuffle").foregroundStyle(q.shuffle ? Color.accentColor : .secondary)
                    }.accessibilityIdentifier("queue-shuffle")
                }
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }

    @ViewBuilder private func row(_ qi: QItem, current: Bool = false) -> some View {
        let s = qi.song
        HStack(spacing: 12) {
            Cover(id: s.coverArt, size: 44, radius: 6).frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(s.name).font(.body).lineLimit(1).foregroundStyle(current ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                Text(s.artist ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if current { EqBars(active: player.playing).foregroundStyle(.tint) }
            else { Text(clock(s.seconds)).font(.callout).monospacedDigit().foregroundStyle(.secondary) }
        }
        .contentShape(Rectangle())
        .opacity(app.playable(s) ? 1 : 0.35)
        .onTapGesture { if !current { player.jump(to: qi.id) } }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if !current { Button("Remove", role: .destructive) { withAnimation(.smooth) { player.remove(qi.id) } } }
        }
        .moveDisabled(current)
    }
}

struct LyricsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var lyrics: Lyrics?
    @State private var loaded = false

    var body: some View {
        let s = player.current
        NavigationStack {
            Group {
                if let l = lyrics, let lines = l.line, !lines.isEmpty {
                    if l.synced == true { synced(lines) } else { plainLyrics(lines) }
                } else if loaded {
                    ContentUnavailableView("No lyrics", systemImage: "quote.bubble", description: Text("This song has no lyrics on your server."))
                } else { ProgressView() }
            }
            .navigationTitle(s?.name ?? "Lyrics").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .task(id: s?.id) {
            guard let id = s?.id else { return }
            lyrics = nil; loaded = false
            let e = await app.load("getLyricsBySongId", [("id", id)], LyricsR.self) { r in
                let all = r.lyricsList?.structuredLyrics ?? []
                lyrics = all.first { $0.synced == true } ?? all.first
            }
            _ = e
            loaded = true
        }
    }

    private func synced(_ lines: [Lyrics.Line]) -> some View {
        let ms = player.pos * 1000 + 250
        let active = lines.lastIndex { Double($0.start ?? 0) <= ms } ?? -1
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    Color.clear.frame(height: 120)
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, l in
                        Text((l.value ?? "").isEmpty ? "♪" : l.value!)
                            .font(.system(size: 30, weight: .bold)).lineSpacing(2)
                            .foregroundStyle(i == active ? .white : .white.opacity(i < active ? 0.3 : 0.4))
                            .scaleEffect(i == active ? 1 : 0.92, anchor: .leading)
                            .blur(radius: i == active ? 0 : 0.6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture { player.seek(Double(l.start ?? 0) / 1000); player.play() }
                            .id(i)
                    }
                    Color.clear.frame(height: 300)
                }
                .padding(.horizontal, 24)
                .animation(.smooth(duration: 0.4), value: active)
            }
            .onChange(of: active) { _, a in if a >= 0 { withAnimation(.smooth(duration: 0.6)) { proxy.scrollTo(a, anchor: UnitPoint(x: 0.5, y: 0.3)) } } }
        }
    }

    private func plainLyrics(_ lines: [Lyrics.Line]) -> some View {
        ScrollView {
            Text(lines.map { $0.value ?? "" }.joined(separator: "\n")).font(.title3).lineSpacing(6)
                .frame(maxWidth: .infinity, alignment: .leading).padding(24)
        }
    }
}
