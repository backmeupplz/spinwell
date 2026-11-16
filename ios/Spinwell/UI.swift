import SwiftUI

enum Route: Hashable {
    case album(String, String)    // id, transition source
    case artist(String, String)
    case playlist(String, String)
    case favorites
    case settings
    case genre(String)
}

@Observable final class Nav {
    var path: [Route] = []
    func open(_ r: Route) { path.append(r) }
}

private struct ZoomKey: EnvironmentKey { static let defaultValue: Namespace.ID? = nil }
extension EnvironmentValues { var zoomNS: Namespace.ID? { get { self[ZoomKey.self] } set { self[ZoomKey.self] = newValue } } }

extension View {
    /// Cover -> page zoom: mark the link as a source ...
    @ViewBuilder func zoomSource(_ id: String, _ ns: Namespace.ID?) -> some View {
        if let ns { matchedTransitionSource(id: id, in: ns) } else { self }
    }
    /// ... and the page as the destination.
    @ViewBuilder func zoomDestination(_ id: String, _ ns: Namespace.ID?) -> some View {
        if let ns { navigationTransition(.zoom(sourceID: id, in: ns)) } else { self }
    }
}

/// One tab's NavigationStack: routes, offline banner.
struct Stack<Root: View>: View {
    @State private var nav = Nav()
    @Environment(\.zoomNS) private var ns
    @ViewBuilder var root: Root

    var body: some View {
        NavigationStack(path: $nav.path) {
            root.safeAreaInset(edge: .top, spacing: 0) { OfflineBanner() } // under the top bar
                .navigationDestination(for: Route.self) { r in
                    switch r {
                    case .album(let id, let src): AlbumPage(id: id).safeAreaInset(edge: .top, spacing: 0) { OfflineBanner() }.zoomDestination(src, ns)
                    case .playlist(let id, let src): PlaylistPage(id: id).safeAreaInset(edge: .top, spacing: 0) { OfflineBanner() }.zoomDestination(src, ns)
                    case .artist(let id, let src): ArtistPage(id: id).zoomDestination(src, ns)
                    case .favorites: FavoritesView().navigationTitle("Favorites").navigationBarTitleDisplayMode(.inline)
                    case .settings: SettingsView()
                    case .genre(let g): Text(g)
                    }
                }
        }
        .environment(nav)
        .onAppear { Nav.active = nav; Hooks.openIfRequested(nav) }
    }
}

struct OfflineBanner: View {
    var body: some View {
        if app.offline {
            Button {
                if app.settings.offlineMode { app.settings.offlineMode = false } else { Task { await app.ping() } }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "wifi.slash").font(.caption.weight(.semibold))
                    Text(app.settings.offlineMode ? "Offline mode · tap to go online" : "Offline · showing downloads").font(.footnote.weight(.medium))
                }
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Color.orange.opacity(0.92), in: .capsule).foregroundStyle(.black)
                .frame(maxWidth: .infinity).padding(.vertical, 4)
            }
            .buttonStyle(.plain)
            .transition(.move(edge: .top).combined(with: .opacity))
            .accessibilityIdentifier("offline-banner")
        }
    }
}

struct Press: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.96 : 1).animation(.spring(duration: 0.25), value: configuration.isPressed)
    }
}

struct EqBars: View {
    var active: Bool
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 24, paused: !active)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<3, id: \.self) { i in
                    Capsule().frame(width: 3, height: active ? 4 + 10 * abs(sin(t * (3 + Double(i) * 1.3) + Double(i))) : 4)
                }
            }
            .frame(height: 14, alignment: .bottom)
        }
    }
}

struct Section_: View {
    let title: String
    var action: (() -> Void)? = nil
    var body: some View {
        Text(title).font(.title3.bold()).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Album card for shelves and grids: the whole card is the NavigationLink (zoom source).
struct AlbumCard: View {
    let album: Album
    var width: CGFloat? = 150
    var source: String
    @Environment(\.zoomNS) private var ns

    var body: some View {
        let dim = app.offline && !app.downloads.albumIds.contains(album.id)
        NavigationLink(value: Route.album(album.id, source)) {
            VStack(alignment: .leading, spacing: 6) {
                Cover(id: album.coverArt, size: width ?? 175).frame(width: width, height: width)
                    .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
                    .zoomSource(source, ns)
                Text(album.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text([album.artist, album.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            
            .frame(width: width, alignment: .leading).frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
            .opacity(dim ? 0.4 : 1)
        }
        .buttonStyle(Press())
    }
}

struct ErrorLine: View {
    let text: String
    var body: some View { Text(text).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).padding() }
}

/// A play / shuffle / download style capsule
struct Pill: View {
    let title: String
    let symbol: String
    var prominent = false
    var body: some View {
        Label(title, systemImage: symbol).font(.headline).lineLimit(1)
            .frame(maxWidth: .infinity).padding(.vertical, 14)
            .foregroundStyle(prominent ? Color.black : Color.white)
            .glassEffect(prominent ? .regular.tint(.white).interactive() : .regular.interactive(), in: .capsule)
    }
}
