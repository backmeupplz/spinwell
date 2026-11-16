import SwiftUI

/// A settings row: tinted icon tile, title, ⓘ, value on the right. The explanation is a required parameter,
/// so a setting can't exist without its ⓘ.
struct SettingRow<Content: View>: View {
    let title: String
    let symbol: String
    let tint: Color
    let info: String
    let id: String
    @ViewBuilder var content: Content
    @State private var showInfo: Bool

    init(_ title: String, symbol: String, tint: Color, info: String, id: String, @ViewBuilder content: () -> Content) {
        self.title = title; self.symbol = symbol; self.tint = tint; self.info = info; self.id = id; self.content = content()
        _showInfo = State(initialValue: false)
    }

    var body: some View {
        HStack(spacing: 10) {
            IconTile(symbol: symbol, tint: tint)
            Text(title).lineLimit(2).fixedSize(horizontal: false, vertical: true)
            Button { showInfo = true } label: { Image(systemName: "info.circle").font(.subheadline).foregroundStyle(.tertiary).frame(width: 28, height: 36) }
                .buttonStyle(.plain).accessibilityLabel("About \(title)").accessibilityIdentifier("info-\(id)")
                .sheet(isPresented: $showInfo) { InfoSheet(title: title, text: info) }
            Spacer(minLength: 4)
            content.foregroundStyle(.secondary)
        }
        .task { if Hooks.string("info") == id { try? await Task.sleep(for: .seconds(1)); showInfo = true } } // screenshot hook
    }
}

/// The little colored rounded square of iOS Settings.
struct IconTile: View {
    let symbol: String
    let tint: Color
    var size: CGFloat = 28
    var body: some View {
        Image(systemName: symbol).font(.system(size: size * 0.54, weight: .semibold)).foregroundStyle(.white)
            .frame(width: size, height: size).background(tint.gradient, in: .rect(cornerRadius: size * 0.26, style: .continuous))
    }
}

/// The ⓘ explanation: a small bottom sheet sized to its text, on a dimmed backdrop.
struct InfoSheet: View {
    let title: String
    let text: String
    @State private var height: CGFloat = 220
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            Text(text).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 16)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.visible)
    }
}

/// Two-part usage bar with a legend.
struct UsageBar: View {
    let parts: [(label: String, bytes: Int64, color: Color)]
    var body: some View {
        let total = max(parts.reduce(0) { $0 + $1.bytes }, 1)
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { g in
                HStack(spacing: 2) {
                    ForEach(parts.indices, id: \.self) { i in
                        let f = CGFloat(parts[i].bytes) / CGFloat(total)
                        if parts[i].bytes > 0 { Capsule().fill(parts[i].color).frame(width: max(6, g.size.width * f - 2)) }
                    }
                    Spacer(minLength: 0)
                }
            }
            .frame(height: 8).background(Capsule().fill(.white.opacity(0.1)))
            HStack(spacing: 14) {
                ForEach(parts.indices, id: \.self) { i in
                    HStack(spacing: 5) {
                        Circle().fill(parts[i].color).frame(width: 7, height: 7)
                        Text("\(parts[i].label) \(bytes(parts[i].bytes))").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var confirmSignOut = false
    @State private var confirmRemove = false
    @State private var cacheBytes: Int64 = 0
    @State private var free: Int64 = 0

    static let qualities = [(0, "Original"), (320, "320 kbps"), (256, "256 kbps"), (192, "192 kbps"), (128, "128 kbps")]
    static let dlQualities = [(0, "Original"), (320, "320 kbps"), (192, "192 kbps")]

    var body: some View {
        @Bindable var s = app.settings
        let dl = app.downloads.storageUsed
        List {
            Section { header }
            Section("Streaming") {
                SettingRow("Quality on Wi-Fi", symbol: "wifi", tint: .blue, info: "How songs are sent when you're on Wi-Fi. Original plays the file as it is on your server, so FLAC stays lossless; a number asks the server to convert to MP3 at that bitrate, which uses less data but can't sound better than the original.", id: "wifi") {
                    quality($s.wifiQuality, Self.qualities)
                }
                SettingRow("Quality on mobile data", symbol: "antenna.radiowaves.left.and.right", tint: .green, info: "The same choice for when you're on mobile data. A lower bitrate saves your data plan and starts faster on a weak signal.", id: "cell") {
                    quality($s.cellQuality, Self.qualities)
                }
                SettingRow("Report plays to server", symbol: "chart.bar.fill", tint: .orange, info: "Tell your server what you listen to, so Recently played and Most played stay accurate and play counts go up. Plays made offline are sent once you're back online.", id: "scrobble") {
                    Toggle("", isOn: $s.scrobble).labelsHidden()
                }
            }
            Section("Playback") {
                SettingRow("Resume playing on open", symbol: "play.circle.fill", tint: .pink, info: "If music was playing when the app was closed, start it again right away when you open the app. When this is off, the player opens where you left off, paused, so one tap continues.", id: "resume") {
                    Toggle("", isOn: $s.resumeOnOpen).labelsHidden()
                }
            }
            Section("Offline") {
                SettingRow("Offline mode", symbol: "airplane", tint: .orange, info: "Use only what's downloaded and never contact the server. The app also switches to this by itself whenever your server can't be reached, for example when Tailscale is off.", id: "offline") {
                    Toggle("", isOn: $s.offlineMode).labelsHidden()
                }
                SettingRow("Download quality", symbol: "arrow.down.circle.fill", tint: .indigo, info: "How songs are saved for offline listening. Original keeps the exact file from your server and takes the most space; 320 or 192 asks the server to convert to MP3, which can make a FLAC album several times smaller.", id: "dlq") {
                    quality($s.dlQuality, Self.dlQualities)
                }
                SettingRow("Download over mobile data", symbol: "cellularbars", tint: .teal, info: "When this is off, downloads wait for Wi-Fi and start again by themselves once you're connected.", id: "dlcell") {
                    Toggle("", isOn: $s.dlCellular).labelsHidden()
                }
            }
            Section {
                UsageBar(parts: [("Downloads", dl, Color.accentColor), ("Cache", cacheBytes, Color.gray)]).padding(.vertical, 4)
                SettingRow("Downloads", symbol: "internaldrive.fill", tint: .indigo, info: "Songs saved for offline listening. Removing them frees the space; you can download them again any time.", id: "downloads") {
                    HStack(spacing: 8) {
                        Text(app.downloads.state.songs.isEmpty ? "None" : bytes(dl)).font(.footnote).lineLimit(1)
                        if !app.downloads.state.songs.isEmpty {
                            Button("Remove", role: .destructive) { confirmRemove = true }.buttonStyle(.bordered).controlSize(.small).fixedSize()
                        }
                    }
                }
                SettingRow("Cache", symbol: "photo.stack.fill", tint: .gray, info: "Album covers and library pages kept on your phone so screens open instantly and you can browse offline. Clearing it is safe; it fills up again as you browse.", id: "cache") {
                    HStack(spacing: 10) {
                        Text(bytes(cacheBytes)).font(.footnote)
                        Button("Clear") { app.clearCache(); cacheBytes = app.cacheSize() }.buttonStyle(.bordered).controlSize(.small).fixedSize()
                    }
                }
            } header: { Text("Storage") } footer: { if free > 0 { Text("\(bytes(free)) free on this iPhone") } }
            Section("About") {
                LabeledContent {
                    Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "").foregroundStyle(.secondary)
                } label: { HStack(spacing: 10) { IconTile(symbol: "info", tint: .gray); Text("Version") } }
                Link(destination: URL(string: "https://git.borodutch.com/backmeupplz/spinwell")!) {
                    HStack(spacing: 10) { IconTile(symbol: "chevron.left.forwardslash.chevron.right", tint: .purple); Text("Source code"); Spacer(); Image(systemName: "arrow.up.right").font(.footnote).foregroundStyle(.tertiary) }
                }
            }
            Section {
                Button(role: .destructive) { confirmSignOut = true } label: { Text("Sign out").frame(maxWidth: .infinity) }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
        .task {
            cacheBytes = await Task.detached { await app.cacheSize() }.value
            free = (try? URL.homeDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
        }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { app.signOut() }
        } message: { Text("Your downloads stay on this phone and come back when you sign in to the same account.") }
        .confirmationDialog("Remove all downloads?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove all", role: .destructive) { app.downloads.removeAll() }
        }
    }

    /// Account card: server, user, version, online dot.
    private var header: some View {
        HStack(spacing: 14) {
            IconTile(symbol: "server.rack", tint: Color.accentColor, size: 52)
            VStack(alignment: .leading, spacing: 3) {
                Text(app.account?.host ?? "").font(.headline).lineLimit(1)
                Text([app.account?.user, app.serverVersion.isEmpty ? nil : app.serverVersion].compactMap { $0 }.joined(separator: " · ")).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                HStack(spacing: 6) {
                    Circle().fill(app.offline ? Color.orange : Color.green).frame(width: 8, height: 8)
                    Text(app.settings.offlineMode ? "Offline mode" : app.unreachable ? "Can't reach the server" : "Online").font(.footnote).foregroundStyle(.secondary)
                }.accessibilityIdentifier("status")
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
    }

    private func quality(_ b: Binding<Int>, _ opts: [(Int, String)]) -> some View {
        Picker("", selection: b) { ForEach(opts, id: \.0) { Text($0.1).tag($0.0) } }.labelsHidden().pickerStyle(.menu)
    }
}
