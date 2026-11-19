import AVKit
import SwiftUI

extension Nav { @MainActor static weak var active: Nav? }

struct MiniPlayer: View {
    let ns: Namespace.ID
    let open: () -> Void
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @State private var drag: CGFloat = 0

    var body: some View {
        let s = player.current
        HStack(spacing: 12) {
            Cover(id: s?.coverArt, size: 36, radius: 7).frame(width: 36, height: 36)
            if placement != .inline || true {
                VStack(alignment: .leading, spacing: 0) {
                    Text(s?.name ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                    // a song that failed says so (the full player says why); stopped offline, the reason itself
                    if let e = player.error { Text(e == player.noDownloadNext ? e : String(localized: "Couldn't play this song")).font(.caption).foregroundStyle(.orange).lineLimit(1) }
                    else { Text(s?.artist ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                }
                .id(player.q.current?.id)
                .transition(.push(from: drag < 0 ? .trailing : .leading).combined(with: .opacity))
            }
            Spacer(minLength: 4)
            Button { player.toggle() } label: {
                Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 36, height: 36)
                    .contentTransition(.symbolEffect(.replace))
            }.buttonStyle(.plain).accessibilityLabel(player.playing ? Text("Pause") : Text("Play")).accessibilityIdentifier("mini-play")
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.body).frame(width: 34, height: 36) }.buttonStyle(.plain).accessibilityLabel("Next")
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .overlay(alignment: .bottom) {
            GeometryReader { g in
                Capsule().fill(.white.opacity(0.55)).frame(width: g.size.width * min(1, player.pos / max(player.duration, 1)), height: 2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }.frame(height: 2).padding(.horizontal, 16)
        }
        .offset(x: drag * 0.4)
        .gesture(DragGesture(minimumDistance: 14).onChanged { drag = $0.translation.width }.onEnded { v in
            let dx = v.translation.width, dy = v.translation.height
            if dy < -40, abs(dy) > abs(dx) { open() }
            else if abs(dx) > 50 { withAnimation(.smooth) { if dx < 0 { player.next() } else { player.previous() } } }
            withAnimation(.spring) { drag = 0 }
        })
        .onTapGesture(perform: open)
        .matchedTransitionSource(id: "player", in: ns)
        .accessibilityIdentifier("mini-player")
    }
}

struct SeekBar: View {
    @State private var dragging: Double?
    @Environment(\.layoutDirection) private var direction
    var body: some View {
        let dur = max(player.duration, 1)
        let value = dragging ?? player.pos
        VStack(spacing: 6) {
            GeometryReader { g in
                let w = g.size.width
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.22))
                    Capsule().fill(.white).frame(width: max(0, w * min(1, value / dur)))
                }
                .frame(height: dragging == nil ? 6 : 12)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if dragging == nil { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
                        let x = direction == .rightToLeft ? w - v.location.x : v.location.x
                        let t = max(0, min(1, x / w)) * dur
                        withAnimation(.snappy(duration: 0.15)) { dragging = t }
                    }
                    .onEnded { _ in
                        if let t = dragging { player.seek(t) }
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        withAnimation(.snappy) { dragging = nil }
                    })
            }
            .frame(height: 22)
            HStack {
                Text(clock(value)); Spacer(); Text(verbatim: "-" + clock(max(0, dur - value)))
            }.font(.caption).monospacedDigit().foregroundStyle(.white.opacity(0.6))
        }
    }
}

struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView(); v.tintColor = .white; v.activeTintColor = .white; return v
    }
    func updateUIView(_ v: AVRoutePickerView, context: Context) {}
}

struct FullPlayer: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showQueue = Hooks.flag("upnext")
    @State private var showLyrics = Hooks.flag("lyrics")
    @State private var dx: CGFloat = 0
    @State private var forward = true
    @State private var tint = FullPlayer.startTint()

    var body: some View {
        let s = player.current
        ZStack {
            LinearGradient(colors: [tint, tint.opacity(0.45), .black], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Button { dismiss() } label: { Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }.accessibilityLabel("Minimize player").accessibilityIdentifier("player-close")
                    Spacer()
                    VStack(spacing: 1) {
                        Text("Playing from").textCase(.uppercase).font(.system(size: 10, weight: .semibold)).tracking(caps(1)).foregroundStyle(.white.opacity(0.6)).lineLimit(1)
                        Text(verbatim: player.q.context?.title ?? String(localized: "Up next")).font(.footnote.weight(.semibold)).lineLimit(1)
                    }
                    Spacer()
                    Menu { if let s { SongMenu(song: s) } } label: { Image(systemName: "ellipsis").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("More")
                }
                .foregroundStyle(.white).padding(.horizontal, 12)
                Spacer(minLength: 8)
                cover(s)
                Spacer(minLength: 12)
                titleRow(s).padding(.horizontal, 28)
                if let e = player.error { Text(e).font(.caption).foregroundStyle(.orange).lineLimit(2).padding(.horizontal, 28).padding(.top, 6) }
                SeekBar().padding(.horizontal, 28).padding(.top, 14)
                transport.padding(.top, 8)
                bottomRow(s).padding(.horizontal, 28).padding(.top, 8).padding(.bottom, 8)
            }
        }
        .foregroundStyle(.white)
        .sheet(isPresented: $showQueue) { UpNextView().presentationDetents([.large]).presentationDragIndicator(.visible) }
        .sheet(isPresented: $showLyrics) { LyricsView().presentationDetents([.large]).presentationDragIndicator(.visible) }
        .task(id: s?.coverArt) {
            guard let c = s?.coverArt else { return }
            _ = await Covers.get(c, size: 1200)
            if let u = Covers.color(c) { withAnimation(.easeInOut(duration: 0.7)) { tint = Color(uiColor: u).mix(with: .black, by: 0.25) } }
        }
    }

    private func cover(_ s: Song?) -> some View {
        ZStack {
            Cover(id: s?.coverArt, size: 380, radius: 14)
                .id(player.q.current?.id)
                .transition(.push(from: forward ? .trailing : .leading).combined(with: .opacity))
        }
        .padding(.horizontal, player.playing ? 28 : 54)
        .frame(maxWidth: .infinity, maxHeight: .infinity) // takes what is left, so the square shrinks on a small phone instead of pushing the controls off
        .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
        .offset(x: dx * 0.5)
        .animation(.spring(response: 0.5, dampingFraction: 0.72), value: player.playing)
        .animation(.smooth(duration: 0.4), value: player.q.current?.id)
        .gesture(DragGesture(minimumDistance: 20).onChanged { dx = $0.translation.width }.onEnded { v in
            let t = v.translation.width
            if abs(t) > 70 { forward = t < 0; if t < 0 { player.next() } else { player.previous() } }
            withAnimation(.spring) { dx = 0 }
        })
        .accessibilityIdentifier("player-cover")
    }

    private func titleRow(_ s: Song?) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(s?.name ?? "").font(.title2.bold()).lineLimit(2)
                Button {
                    if let a = s?.artistId { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { Nav.active?.open(.artist(a, "player-\(a)")) } }
                } label: { Text(s?.artist ?? "").font(.title3).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
            }
            .id(player.q.current?.id).transition(.opacity)
            Spacer(minLength: 0)
            if let s {
                let fav = app.isStarred(s)
                Button { app.toggleStar(s.id, now: !fav) } label: {
                    Image(systemName: fav ? "heart.fill" : "heart").font(.title2).foregroundStyle(fav ? .pink : .white)
                        .frame(width: 44, height: 44).symbolEffect(.bounce, value: fav)
                }.accessibilityLabel(fav ? Text("Remove from favorites") : Text("Add to favorites")).accessibilityIdentifier("player-heart")
            }
        }
        .animation(.smooth, value: player.q.current?.id)
    }

    private var transport: some View {
        HStack {
            Button { player.setShuffle(!player.q.shuffle) } label: {
                ModeIcon(symbol: "shuffle", on: player.q.shuffle, inactive: .white.opacity(0.7)).font(.title3).frame(width: 48, height: 56)
                    .symbolEffect(.bounce, value: player.q.shuffle)
            }.accessibilityLabel("Shuffle").accessibilityIdentifier("shuffle-toggle")
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 30)).frame(width: 60, height: 60) }.accessibilityLabel("Previous")
            Spacer()
            Button { player.toggle() } label: {
                Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.system(size: 40))
                    .frame(width: 76, height: 76).contentTransition(.symbolEffect(.replace))
                    .glassEffect(.regular.interactive(), in: .circle)
            }.accessibilityLabel(player.playing ? Text("Pause") : Text("Play")).accessibilityIdentifier("player-play")
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 30)).frame(width: 60, height: 60) }.accessibilityLabel("Next")
            Spacer()
            Button { player.cycleRepeat() } label: {
                ModeIcon(symbol: player.repeatMode == .one ? "repeat.1" : "repeat", on: player.repeatMode != .off, inactive: .white.opacity(0.7))
                    .font(.title3).frame(width: 48, height: 56)
            }.accessibilityLabel(player.repeatMode == .one ? Text("Repeat one") : player.repeatMode == .off ? Text("Repeat off") : Text("Repeat all")).accessibilityIdentifier("repeat-toggle")
        }
        .buttonStyle(.plain).padding(.horizontal, 20)
        .sensoryFeedback(.selection, trigger: player.q.shuffle).sensoryFeedback(.selection, trigger: player.repeatMode)
    }

    private func bottomRow(_ s: Song?) -> some View {
        HStack {
            Text(s?.badge ?? "").font(.caption2.weight(.semibold)).padding(.horizontal, 9).padding(.vertical, 5)
                .background(.white.opacity(0.14), in: .capsule)
            Spacer()
            Button { showLyrics = true } label: { Image(systemName: "quote.bubble").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Lyrics").accessibilityIdentifier("lyrics-button")
            Button { showQueue = true } label: { Image(systemName: "list.bullet").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Up next").accessibilityIdentifier("upnext-button")
            RoutePicker().frame(width: 44, height: 44).accessibilityLabel("Output")
        }
    }
}


extension FullPlayer {
    /// The current cover's color when it is already known, so the gradient doesn't start grey and fade.
    static func startTint() -> Color {
        guard let c = player.current?.coverArt, let u = Covers.color(c) else { return Color(white: 0.2) }
        return Color(uiColor: u).mix(with: .black, by: 0.25)
    }
}
