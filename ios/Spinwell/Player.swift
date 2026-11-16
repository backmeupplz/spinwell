import AVFoundation
import MediaPlayer
import UIKit

struct Saved: Codable { var queue: QueueState; var position: Double; var wasPlaying: Bool }

@MainActor let player = Player()

/// AVQueuePlayer holding [current, next]; the app-owned `QueueState` decides what those are.
@MainActor @Observable final class Player {
    var q = QueueState()
    var playing = false
    var buffering = false
    var pos: Double = 0
    var duration: Double = 0
    var error: String?
    /// set when the app launched with music that was playing: the root view opens the full player once
    var restoredWasPlaying = false
    /// bumped on every queue change so views can animate reorders
    var revision = 0

    @ObservationIgnored private let ap = AVQueuePlayer()
    @ObservationIgnored private var keys: [AVPlayerItem: UUID] = [:]
    @ObservationIgnored private var obs: [NSKeyValueObservation] = []
    @ObservationIgnored private var scrobbled: UUID?
    @ObservationIgnored private var lastSave = Date.distantPast
    @ObservationIgnored private var failedRun = 0
    @ObservationIgnored private var sawDuration: Double = 0
    @ObservationIgnored private var artwork: (String, MPMediaItemArtwork)?
    @ObservationIgnored private var itemObs: [NSKeyValueObservation] = []
    @ObservationIgnored private var stall: Task<Void, Never>?
    @ObservationIgnored private var failedItems: Set<ObjectIdentifier> = []

    var current: Song? { q.current?.song }
    var hasSong: Bool { q.current != nil }
    var repeatMode: Repeat { q.repeatMode }

    private var stateFile: URL { supportDir.appending(path: "queue.json") }

    init() {
        ap.actionAtItemEnd = .advance
        ap.automaticallyWaitsToMinimizeStalling = true
        obs.append(ap.observe(\.currentItem) { [weak self] _, _ in DispatchQueue.main.async { self?.itemChanged() } })
        obs.append(ap.observe(\.timeControlStatus) { [weak self] _, _ in DispatchQueue.main.async { self?.statusChanged() } })
        ap.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        let nc = NotificationCenter.default
        nc.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] n in
            MainActor.assumeIsolated { if let it = n.object as? AVPlayerItem { self?.itemFailed(it) } }
        }
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            let ended = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.ended.rawValue
            let resume = ((n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init))?.contains(.shouldResume) == true
            if ended && resume { MainActor.assumeIsolated { self?.play() } }
        }
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.save() }
        }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        q.repeatMode = Repeat(rawValue: UserDefaults.standard.string(forKey: "repeat") ?? "") ?? .off
        remote()
        restore()
        syncModes()
    }

    // MARK: restore / persist

    private func restore() {
        guard let d = try? Data(contentsOf: stateFile), let s = try? JSONDecoder().decode(Saved.self, from: d), s.queue.current != nil else { return }
        q = s.queue
        pos = s.position
        duration = q.current?.song.seconds ?? 0
        restoredWasPlaying = s.wasPlaying
        rebuild(at: s.position, play: false)
    }

    /// Called on launch once the account is known: resume when the setting says so.
    func launched() {
        if restoredWasPlaying && app.settings.resumeOnOpen { play() }
    }

    func save(force: Bool = true) {
        lastSave = Date()
        let s = Saved(queue: q, position: pos, wasPlaying: playing)
        let f = stateFile
        Task.detached { try? JSONEncoder().encode(s).write(to: f, options: .atomic) }
    }

    func stopAndClear() {
        ap.pause(); ap.removeAllItems(); keys = [:]
        let keep = q.repeatMode
        q = QueueState(); q.repeatMode = keep; playing = false; pos = 0; duration = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? FileManager.default.removeItem(at: stateFile)
    }

    // MARK: building AVPlayerItems

    private func item(for qi: QItem) -> AVPlayerItem? {
        let s = qi.song
        let url: URL?
        if let local = app.downloads.localURL(s.id) { url = local } else { url = app.stream(s) }
        guard let url else { return nil }
        let it = AVPlayerItem(url: url)
        it.preferredForwardBufferDuration = 0
        keys[it] = qi.id
        itemObs.append(it.observe(\.status) { [weak self] it, _ in
            if it.status == .failed { DispatchQueue.main.async { MainActor.assumeIsolated { self?.itemFailed(it) } } }
        })
        return it
    }

    /// Next entry we can play: offline, skip what isn't downloaded.
    private func playable(_ qi: QItem?) -> Bool { qi.map { app.playable($0.song) } ?? false }

    /// Put [current, next] in the player; `at` seeks the current one.
    private func rebuild(at: Double = 0, play: Bool) {
        revision += 1
        ap.removeAllItems(); keys = [:]; itemObs = []; failedItems = []
        guard let cur = q.current, let first = item(for: cur) else { return }
        if let c = cur.song.coverArt { Covers.prepare(c) }
        ap.insert(first, after: nil)
        if at > 0 { ap.seek(to: CMTime(seconds: at, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) }
        preloadNext()
        pos = at
        duration = cur.song.seconds
        scrobbled = nil
        if play { self.play() }
        updateInfo()
    }

    private func preloadNext() {
        guard let cur = ap.currentItem else { return }
        // drop anything queued after the current item that is no longer the right next one
        let wanted = q.upcoming
        let after = ap.items().drop { $0 !== cur }.dropFirst()
        if let a = after.first, keys[a] == wanted?.id, after.count == 1, playable(wanted) { return }
        after.forEach { ap.remove($0); keys[$0] = nil }
        if let w = wanted, playable(w), let n = item(for: w) { ap.insert(n, after: cur) }
    }

    /// The server came or went: a preloaded stream the phone can no longer fetch is dropped (the next downloaded song takes over).
    func refreshPreload() { if ap.currentItem != nil { preloadNext() } }

    // MARK: transport

    func play() {
        guard hasSong else { return }
        if ap.currentItem == nil { rebuild(at: pos, play: false) }
        // setActive can block for 100+ ms: never on the main thread, in the tap path (AVPlayer starts its own session use anyway)
        DispatchQueue.global(qos: .userInitiated).async { try? AVAudioSession.sharedInstance().setActive(true) }
        ap.play()
        error = nil
    }
    func pause() { ap.pause(); save() }
    func toggle() { playing || ap.timeControlStatus == .waitingToPlayAtSpecifiedRate ? pause() : play() }

    func seek(_ t: Double) {
        let t = max(0, min(t, duration > 0 ? duration : t))
        pos = t
        ap.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            DispatchQueue.main.async { self?.updateInfo() }
        }
    }

    func next() {
        guard hasSong else { return }
        for _ in 0..<max(q.items.count, 1) {
            guard q.advance(auto: false) else { rebuildKeepingState(atEnd: true); return }
            if playable(q.current) { break }
        }
        // the preloaded next item makes this instant when it is the right one
        if let nxt = ap.items().dropFirst().first, keys[nxt] == q.current?.id {
            ap.advanceToNextItem()
            revision += 1
            preloadNext(); changed()
        } else { rebuild(play: true); changed() }
    }

    func previous() {
        guard hasSong else { return }
        if pos > 3 { seek(0); return }
        let before = q.index
        for _ in 0..<max(q.items.count, 1) { q.back(); if playable(q.current) || q.index == before { break } }
        rebuild(play: true); changed()
    }

    private func rebuildKeepingState(atEnd: Bool) {
        // end of queue: stop on the last song, back at 0
        q.index = max(0, q.items.count - 1)
        rebuild(play: false); pause(); changed()
    }

    /// Start a context. `shuffle` + `at == nil` = the Shuffle button.
    func start(_ songs: [Song], at: Int = 0, context: Ref?, shuffle: Bool = false, randomStart: Bool = false) {
        let songs = app.offline ? songs.filter { app.downloads.has($0.id) } : songs
        guard !songs.isEmpty else { return }
        let keepRepeat = q.repeatMode
        if randomStart { q.startShuffled(songs, context: context) } else { q.start(songs, at: min(at, songs.count - 1), context: context, shuffle: shuffle) }
        q.repeatMode = keepRepeat
        rebuild(play: true); changed()
    }

    func play(_ songs: [Song], from song: Song, context: Ref?) {
        let avail = app.offline ? songs.filter { app.downloads.has($0.id) } : songs
        start(avail, at: avail.firstIndex { $0.id == song.id } ?? 0, context: context, shuffle: q.shuffle && false)
    }

    func playNext(_ songs: [Song]) { enqueue(songs) { $0.playNext($1) } }
    func addToQueue(_ songs: [Song]) { enqueue(songs) { $0.addToQueue($1) } }
    private func enqueue(_ songs: [Song], _ f: (inout QueueState, [Song]) -> Void) {
        let empty = q.isEmpty
        f(&q, songs)
        if empty { rebuild(play: false); } else { preloadNext(); revision += 1 }
        changed()
    }

    func jump(to id: UUID) {
        q.jump(to: id)
        rebuild(play: true); changed()
    }
    func remove(_ id: UUID) { q.remove(id); if ap.currentItem == nil || keys[ap.currentItem!] != q.current?.id { rebuild(play: playing) } else { preloadNext(); revision += 1 }; changed() }
    func move(from: IndexSet, to: Int) { q.move(from: from, to: to); preloadNext(); revision += 1; changed() }
    func clearAdded() { q.clearAdded(); preloadNext(); revision += 1; changed() }
    func setShuffle(_ on: Bool) { q.setShuffle(on); preloadNext(); revision += 1; changed() }
    func cycleRepeat() { setRepeat(q.repeatMode.next) }
    private func isContext(_ r: Ref) -> Bool { hasSong && q.context?.kind == r.kind && q.context?.id == r.id }
    func isRepeating(_ r: Ref) -> Bool { isContext(r) && q.repeatMode == .all }
    /// `Repeat album` from a container's menu: toggles repeat-all when it is the playing context, else plays it in order on repeat.
    func repeatContext(_ songs: [Song], _ r: Ref) {
        if isContext(r) { setRepeat(q.repeatMode == .all ? .off : .all); return }
        start(songs, context: r)
        setRepeat(.all)
    }
    func setRepeat(_ r: Repeat) {
        q.repeatMode = r
        UserDefaults.standard.set(r.rawValue, forKey: "repeat")
        preloadNext(); changed()
    }

    private func changed() { updateInfo(); save() }

    // MARK: observation

    private func itemChanged() {
        guard let it = ap.currentItem, let id = keys[it] else {
            return
        }
        if id != q.current?.id {
            // gapless auto-advance: the player moved on by itself
            if q.repeatMode == .one, q.current?.id == id { } else {
                _ = q.advance(auto: true)
                if q.current?.id != id, let i = q.items.firstIndex(where: { $0.id == id }) { q.index = i }
            }
            pos = 0; scrobbled = nil; revision += 1
            preloadNext(); changed()
        } else if q.repeatMode == .one { pos = 0; scrobbled = nil; preloadNext() }
        duration = q.current?.song.seconds ?? 0
        if let s = current { app.scrobble(s.id, submission: false) }
        if it.status == .failed { itemFailed(it) }
    }

    private func statusChanged() {
        let st = ap.timeControlStatus
        playing = st == .playing || st == .waitingToPlayAtSpecifiedRate
        buffering = st == .waitingToPlayAtSpecifiedRate
        if st == .playing { failedRun = 0 }
        watchStall(st)
        updateInfo()
        if st == .paused && ap.currentItem == nil && hasSong { endOfQueue() }
        save()
    }

    private func endOfQueue() {
        // The player ran out of items: carry on with the next playable song (offline skips what isn't downloaded), or stop.
        for _ in 0..<max(q.items.count, 1) {
            guard q.advance(auto: true) else { break }
            if playable(q.current) { rebuild(play: true); changed(); return }
        }
        q.index = max(0, q.items.count - 1)
        pos = 0
        rebuild(play: false)
        changed()
    }

    private func tick() {
        let t = ap.currentTime().seconds
        if t.isFinite, ap.currentItem != nil { pos = t }
        if let d = ap.currentItem?.duration.seconds, d.isFinite, d > 0, abs(d - duration) > 1 { duration = d }
        if playing, let qi = q.current, scrobbled != qi.id {
            let d = max(duration, qi.song.seconds)
            if pos >= min(d * 0.5, 240), d > 0 { scrobbled = qi.id; app.scrobble(qi.song.id, submission: true) }
        }
        if playing, Date().timeIntervalSince(lastSave) > 5 { save() }
    }

    private func itemFailed(_ it: AVPlayerItem) {
        guard keys[it] == q.current?.id, failedItems.insert(ObjectIdentifier(it)).inserted else { return } // status and end-notification both report it
        failed(it.error)
    }

    /// A song that can't be fetched or decoded: when the server is gone, flip to offline; either way go on with the next playable song.
    private func failed(_ e: Error?) {
        if let e, Srv.isNetwork(e) { app.markUnreachable() }
        failedRun += 1
        if failedRun < 8, skipToPlayable() { error = nil; return }
        error = app.offline ? "Can't reach the server, and no downloaded song is next." : e?.localizedDescription
        ap.pause()
    }

    /// Move to the next song the phone can play right now (offline: downloaded ones) and start it.
    @discardableResult private func skipToPlayable() -> Bool {
        for _ in 0..<max(q.items.count, 1) {
            guard q.advance(auto: false) else { return false }
            if playable(q.current) { rebuild(play: true); changed(); return true }
        }
        return false
    }

    /// A stream that sits in "buffering" for 6 s: check the server; if it is gone, carry on with downloads.
    private func watchStall(_ st: AVPlayer.TimeControlStatus) {
        stall?.cancel()
        guard st == .waitingToPlayAtSpecifiedRate, let id = q.current?.id, let s = current, !app.downloads.has(s.id), !app.offline else { return }
        stall = Task {
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled, ap.timeControlStatus == .waitingToPlayAtSpecifiedRate, q.current?.id == id else { return }
            await app.ping()
            if app.unreachable, q.current?.id == id { skipToPlayable() }
        }
    }

    // MARK: lock screen / Control Center

    private func updateInfo() {
        syncModes()
        guard let s = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var i: [String: Any] = [
            MPMediaItemPropertyTitle: s.name,
            MPMediaItemPropertyArtist: s.artist ?? "",
            MPMediaItemPropertyAlbumTitle: s.album ?? "",
            MPMediaItemPropertyPlaybackDuration: max(duration, s.seconds),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: pos,
            MPNowPlayingInfoPropertyPlaybackRate: playing && !buffering ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let c = s.coverArt, let art = artwork, art.0 == c { i[MPMediaItemPropertyArtwork] = art.1 }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = i
        MPRemoteCommandCenter.shared().likeCommand.isActive = app.isStarred(s)
        if let c = s.coverArt, artwork?.0 != c {
            Task {
                guard let img = await Covers.get(c, size: 600) ?? Covers.any(c), current?.coverArt == c else { return }
                artwork = (c, MPMediaItemArtwork(boundsSize: img.size) { _ in img })
                updateInfo()
            }
        }
    }

    /// Lock screen / Control Center show the same shuffle and repeat state as the app.
    private func syncModes() {
        let c = MPRemoteCommandCenter.shared()
        c.changeShuffleModeCommand.currentShuffleType = q.shuffle ? .items : .off
        c.changeRepeatModeCommand.currentRepeatType = switch q.repeatMode { case .off: .off; case .all: .all; case .one: .one }
    }

    private func remote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.play() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.next() }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.previous() }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            let t = (e as? MPChangePlaybackPositionCommandEvent)?.positionTime ?? 0
            MainActor.assumeIsolated { self?.seek(t) }
            return .success
        }
        c.changeShuffleModeCommand.isEnabled = true
        c.changeShuffleModeCommand.addTarget { [weak self] e in
            let on = (e as? MPChangeShuffleModeCommandEvent)?.shuffleType != MPShuffleType.off
            MainActor.assumeIsolated { self?.setShuffle(on) }
            return .success
        }
        c.changeRepeatModeCommand.isEnabled = true
        c.changeRepeatModeCommand.addTarget { [weak self] e in
            let r: Repeat
            switch (e as? MPChangeRepeatModeCommandEvent)?.repeatType { case .one: r = .one; case .all: r = .all; default: r = .off }
            MainActor.assumeIsolated { self?.setRepeat(r) }
            return .success
        }
        c.likeCommand.localizedTitle = "Favorite"
        c.likeCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let s = self?.current { app.toggleStar(s.id, now: !app.isStarred(s)); self?.updateInfo() } }
            return .success
        }
    }
}
