import SwiftUI

/// A settings row. The explanation is a required parameter, so a setting can't exist without its ⓘ.
struct SettingRow<Content: View>: View {
    let title: String
    let info: String
    let id: String
    @ViewBuilder var content: Content
    @State private var showInfo: Bool

    init(_ title: String, info: String, id: String, @ViewBuilder content: () -> Content) {
        self.title = title; self.info = info; self.id = id; self.content = content()
        _showInfo = State(initialValue: false)
    }

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
            Button { showInfo = true } label: { Image(systemName: "info.circle").foregroundStyle(.secondary).padding(6) }
                .buttonStyle(.plain).accessibilityLabel("About \(title)").accessibilityIdentifier("info-\(id)")
                .sheet(isPresented: $showInfo) { InfoSheet(title: title, text: info) }
            Spacer(minLength: 8)
            content.foregroundStyle(.secondary)
        }
        .task { if Hooks.string("info") == id { try? await Task.sleep(for: .seconds(1)); showInfo = true } } // screenshot hook
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

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var confirmSignOut = false
    @State private var confirmRemove = false
    @State private var cacheBytes: Int64 = 0

    static let qualities = [(0, "Original"), (320, "320 kbps"), (256, "256 kbps"), (192, "192 kbps"), (128, "128 kbps")]
    static let dlQualities = [(0, "Original"), (320, "320 kbps"), (192, "192 kbps")]

    var body: some View {
        @Bindable var s = app.settings
        Form {
            Section("Server") {
                LabeledContent("Account") { Text("\(app.account?.user ?? "") @ \(app.account?.host ?? "")").foregroundStyle(.secondary) }
                LabeledContent("Server") { Text(app.serverVersion.isEmpty ? (app.offline ? "Offline" : "Connected") : app.serverVersion).foregroundStyle(.secondary) }
                Button("Sign out", role: .destructive) { confirmSignOut = true }
            }
            Section("Streaming") {
                SettingRow("Quality on Wi-Fi", info: "How songs are sent when you're on Wi-Fi. Original plays the file as it is on your server, so FLAC stays lossless; a number asks the server to convert to MP3 at that bitrate, which uses less data but can't sound better than the original.", id: "wifi") {
                    quality($s.wifiQuality, Self.qualities)
                }
                SettingRow("Quality on mobile data", info: "The same choice for when you're on mobile data. A lower bitrate saves your data plan and starts faster on a weak signal.", id: "cell") {
                    quality($s.cellQuality, Self.qualities)
                }
                SettingRow("Report plays to server", info: "Tell your server what you listen to, so Recently played and Most played stay accurate and play counts go up. Plays made offline are sent once you're back online.", id: "scrobble") {
                    Toggle("", isOn: $s.scrobble).labelsHidden()
                }
            }
            Section("Playback") {
                SettingRow("Resume playing on open", info: "If music was playing when the app was closed, start it again right away when you open the app. When this is off, the player opens where you left off, paused, so one tap continues.", id: "resume") {
                    Toggle("", isOn: $s.resumeOnOpen).labelsHidden()
                }
            }
            Section("Offline") {
                SettingRow("Offline mode", info: "Use only what's downloaded and never contact the server. The app also switches to this by itself whenever your server can't be reached, for example when Tailscale is off.", id: "offline") {
                    Toggle("", isOn: $s.offlineMode).labelsHidden()
                }
                SettingRow("Download quality", info: "How songs are saved for offline listening. Original keeps the exact file from your server and takes the most space; 320 or 192 asks the server to convert to MP3, which can make a FLAC album several times smaller.", id: "dlq") {
                    quality($s.dlQuality, Self.dlQualities)
                }
                SettingRow("Download over mobile data", info: "When this is off, downloads wait for Wi-Fi and start again by themselves once you're connected.", id: "dlcell") {
                    Toggle("", isOn: $s.dlCellular).labelsHidden()
                }
            }
            Section("Storage") {
                SettingRow("Cache", info: "Album covers and library pages kept on your phone so screens open instantly and you can browse offline. Clearing it is safe; it fills up again as you browse.", id: "cache") {
                    HStack(spacing: 10) {
                        Text("Covers and library: \(bytes(cacheBytes))").font(.footnote)
                        Button("Clear") { app.clearCache(); cacheBytes = app.cacheSize() }.buttonStyle(.bordered).controlSize(.small)
                    }
                }
                SettingRow("Downloads", info: "Songs saved for offline listening. Removing them frees the space; you can download them again any time.", id: "downloads") {
                    HStack(spacing: 10) {
                        Text("\(bytes(app.downloads.storageUsed)) · \(app.downloads.state.songs.count) songs").font(.footnote)
                        Button("Remove all", role: .destructive) { confirmRemove = true }.buttonStyle(.bordered).controlSize(.small)
                    }
                }
            }
            Section("About") {
                LabeledContent("Version") { Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "").foregroundStyle(.secondary) }
                Link("Source code", destination: URL(string: "https://git.borodutch.com/backmeupplz/spinwell")!)
            }
        }
        .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
        .task { cacheBytes = await Task.detached { await app.cacheSize() }.value }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { app.signOut() }
        } message: { Text("Your downloads stay on this phone and come back when you sign in to the same account.") }
        .confirmationDialog("Remove all downloads?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove all", role: .destructive) { app.downloads.removeAll() }
        }
    }

    private func quality(_ b: Binding<Int>, _ opts: [(Int, String)]) -> some View {
        Picker("", selection: b) { ForEach(opts, id: \.0) { Text($0.1).tag($0.0) } }.labelsHidden().pickerStyle(.menu)
    }
}
