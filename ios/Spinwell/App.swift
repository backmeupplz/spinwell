import SwiftUI

@main struct SpinwellApp: App {
    init() {
        // test hooks: `-server URL -user U -pass P` signs in at launch, `-offline 1` forces offline mode
        app.downloads.reload() // files + metadata before anything draws or the player restores
        Hooks.signInIfRequested()
        HomeView.warmCovers()
    }
    var body: some Scene {
        WindowGroup { Root().preferredColorScheme(.dark).tint(Color(red: 0.55, green: 0.62, blue: 1)) }
    }
}

enum AppTab: Hashable { case home, library, downloads, search }

struct Root: View {
    @State private var tab: AppTab = {
        switch Hooks.string("tab") { case "library": .library; case "downloads": .downloads; case "search": .search; default: .home }
    }()
    @State private var showPlayer = false
    @Namespace private var ns

    var body: some View {
        Group {
            if app.signedIn { tabs } else { SignIn() }
        }
        .animation(.smooth, value: app.signedIn)
        .task(id: app.signedIn) {
            guard app.signedIn else { return }
            app.didSignIn()
            await app.ping()
            player.launched()
            await Hooks.playIfRequested()
            await Hooks.runScript()
        }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            Tab("Home", systemImage: "house.fill", value: AppTab.home) { Stack { HomeView() } }
            Tab("Library", systemImage: "square.stack.fill", value: AppTab.library) { Stack { LibraryView() } }
            Tab("Downloads", systemImage: "arrow.down.circle.fill", value: AppTab.downloads) { Stack { DownloadsView() } }
            Tab("Search", systemImage: "magnifyingglass", value: AppTab.search, role: .search) { Stack { SearchView() } }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .tabViewBottomAccessory(isEnabled: player.hasSong) { MiniPlayer(ns: ns) { showPlayer = true } }
        .environment(\.zoomNS, ns)
        .fullScreenCover(isPresented: $showPlayer) {
            FullPlayer().navigationTransition(.zoom(sourceID: "player", in: ns))
        }
        .onAppear { if player.restoredWasPlaying { showPlayer = true } else if ["player", "upnext", "lyrics"].contains(where: Hooks.flag) { showPlayer = true } }
    }
}

struct SignIn: View {
    @State private var server = UserDefaults.standard.string(forKey: "lastServer") ?? ""
    @State private var user = ""
    @State private var pass = ""
    @State private var busy = false
    @FocusState private var focus: Int?
    @State private var finding = false
    @State private var found: [LanServer]?

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.16, green: 0.14, blue: 0.4), .black], startPoint: .top, endPoint: .center).ignoresSafeArea()
            VStack(spacing: 28) {
                Spacer()
                VStack(spacing: 14) {
                    Image("Logo").resizable().scaledToFit().frame(width: 96, height: 96).shadow(color: .black.opacity(0.4), radius: 12, y: 6)
                    Text(verbatim: "Spinwell").font(.system(size: 40, weight: .bold, design: .rounded))
                    Text("Your own music, anywhere.").font(.subheadline).foregroundStyle(.secondary)
                }
                VStack(spacing: 12) {
                    field("Server", prompt: Text(verbatim: "https://music.example.ts.net"), text: $server, tag: 0)
                        .keyboardType(.URL).textContentType(.URL)
                    finder
                    field("Username", prompt: Text("Username"), text: $user, tag: 1).textContentType(.username)
                    SecureField("Password", text: $pass).focused($focus, equals: 2).submitLabel(.go)
                        .padding(14).glassEffect(.regular, in: .rect(cornerRadius: 14)).textContentType(.password)
                        .onSubmit(go)
                }
                if let e = app.signInError { Text(e).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center).transition(.opacity) }
                Button(action: go) {
                    ZStack {
                        Text("Sign in").font(.headline).opacity(busy ? 0 : 1)
                        if busy { ProgressView() }
                    }.frame(maxWidth: .infinity).padding(.vertical, 16)
                }
                .buttonStyle(.glassProminent).disabled(busy)
                Spacer()
            }
            .padding(.horizontal, 24).frame(maxWidth: 480)
        }
        .animation(.smooth, value: app.signInError)
    }

    /// "Find servers on this network" under the Server field; tapping a found one fills the field.
    private var finder: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                finding = true; found = nil
                Task { found = await Lan.find(); finding = false }
            } label: {
                HStack(spacing: 8) {
                    if finding { ProgressView().controlSize(.small) }
                    finding ? Text("Looking for servers…") : Text("Find servers on this network")
                }.font(.subheadline)
            }.disabled(finding).accessibilityIdentifier("find-servers")
            if let found {
                if found.isEmpty { Text("No servers found on this network.").font(.footnote).foregroundStyle(.secondary) }
                ForEach(found) { f in
                    Button { server = f.url; self.found = nil } label: {
                        HStack {
                            Text(verbatim: String(f.url.dropFirst("http://".count))).lineLimit(1)
                            Spacer()
                            Text(verbatim: f.name).font(.footnote).foregroundStyle(.secondary)
                        }.padding(12).contentShape(Rectangle()).glassEffect(.regular, in: .rect(cornerRadius: 12))
                    }.buttonStyle(.plain)
                }
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 4)
    }

    private func field(_ title: LocalizedStringKey, prompt: Text, text: Binding<String>, tag: Int) -> some View {
        TextField(title, text: text, prompt: prompt.foregroundStyle(.tertiary))
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .focused($focus, equals: tag).submitLabel(.next).onSubmit { focus = tag + 1 }
            .padding(14).glassEffect(.regular, in: .rect(cornerRadius: 14))
    }

    private func go() {
        busy = true
        Task {
            await app.signIn(server: server, user: user, password: pass)
            if app.signedIn { UserDefaults.standard.set(server, forKey: "lastServer") }
            busy = false
        }
    }
}
