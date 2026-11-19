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
    /// songs whose original stream failed to play: they stream transcoded from then on (this session)
    @ObservationIgnored private var transcode: Set<String> = []
    /// the user wants sound: survives AVPlayer dropping a failed item (its rate is 0 by then), so a retry keeps playing
    @ObservationIgnored private var wantPlay = false
    @ObservationIgnored private var resumeAfterCall = false
    /// the queue entry `error` is about, and a count of rebuilds (a server check that returns after one is stale)
    @ObservationIgnored private var errorId: UUID?
    @ObservationIgnored private var loads = 0

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
            MainActor.assumeIsolated { // a call paused it: a retry stays paused; afterwards resume only what was playing
                guard let self else { return }
                if !ended { self.resumeAfterCall = self.wantPlay; self.wantPlay = false } else if resume, self.resumeAfterCall { self.play() }
            }
        }
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            let gone = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
            MainActor.assumeIsolated { if gone { self?.wantPlay = false; self?.resumeAfterCall = false } } // headphones out: the system paused, a retry must not play on the speaker
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
        q = QueueState(); q.repeatMode = keep; playing = false; pos = 0; duration = 0; wantPlay = false; error = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? FileManager.default.removeItem(at: stateFile)
    }

    // MARK: building AVPlayerItems

    private func item(for qi: QItem) -> AVPlayerItem? {
        let s = qi.song
        let url: URL?
        if let local = app.downloads.localURL(s.id) { url = local } else if app.offline { url = nil } // offline: no network calls at all
        else { url = app.stream(s, transcode: transcode.contains(s.id)) }
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
        revision += 1; loads += 1; wantPlay = play
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
        // offline, only a downloaded file stays: a stream queued while online would still go to the server
        if let a = after.first, keys[a] == wanted?.id, after.count == 1, playable(wanted),
           !app.offline || (a.asset as? AVURLAsset)?.url.isFileURL == true { return }
        after.forEach { ap.remove($0); keys[$0] = nil }
        if let w = wanted, playable(w), let n = item(for: w) { ap.insert(n, after: cur) }
    }

    /// The server came or went: a preloaded stream the phone can no longer fetch is dropped (the next downloaded song takes over).
    func refreshPreload() { if ap.currentItem != nil { preloadNext() } }

    // MARK: transport

    func play() {
        guard hasSong else { return }
        if ap.currentItem == nil || app.offline && !playing && !isFile(ap.currentItem) { // offline, a stream paused before the flip isn't resumed
            rebuild(at: pos, play: false)
            if skipIfEmpty() { return }
        }
        wantPlay = true
        // setActive can block for 100+ ms: never on the main thread, in the tap path (AVPlayer starts its own session use anyway)
        DispatchQueue.global(qos: .userInitiated).async { try? AVAudioSession.sharedInstance().setActive(true) }
        ap.play()
        error = nil
    }
    func pause() { wantPlay = false; ap.pause(); save() }
    func toggle() { playing || ap.timeControlStatus == .waitingToPlayAtSpecifiedRate ? pause() : play() }

    func seek(_ t: Double) {
        var t = max(0, min(t, duration > 0 ? duration : t))
        // offline, a song still streaming from before the flip seeks only within what is loaded: anywhere else fetches it again
        if app.offline, let it = ap.currentItem, !isFile(it) {
            let now = it.currentTime()
            let r = it.loadedTimeRanges.map(\.timeRangeValue).first { $0.containsTime(now) }
            t = r.map { min(max(t, $0.start.seconds), $0.end.seconds) } ?? now.seconds
        }
        pos = t
        ap.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            DispatchQueue.main.async { self?.updateInfo() }
        }
    }

    func next() {
        guard hasSong else { return }
        let from = q.index
        for _ in 0..<max(q.items.count, 1) {
            guard q.advance(auto: false) else { stopAtEnd(from: from); return }
            if playable(q.current) { break }
        }
        guard playable(q.current) else { stopAtEnd(from: from); return } // offline, repeat all: nothing downloaded all the way round
        // the preloaded next item makes this instant when it is the right one
        if let nxt = ap.items().dropFirst().first, keys[nxt] == q.current?.id {
            ap.advanceToNextItem()
            revision += 1
            preloadNext(); changed()
        } else { rebuild(play: true); changed() }
    }

    func previous() {
        guard hasSong else { return }
        // after a stop: retry the song. Offline only a downloaded one restarts, from its file (seeking a stream fetches it again)
        if pos > 3, playable(q.current) {
            if ap.currentItem == nil || app.offline && !isFile(ap.currentItem) { rebuild(play: ap.currentItem == nil || playing) } else { seek(0) }
            return
        }
        let before = q.index
        for _ in 0..<max(q.items.count, 1) { q.back(); if playable(q.current) || q.index == before { break } }
        if !playable(q.current) { q.index = before } // offline, nothing downloaded behind: restart this song, or stay put when it isn't downloaded either
        guard playable(q.current) else { return }
        rebuild(play: true); changed()
    }

    /// Offline, a song that isn't downloaded leaves the player empty: go on to the next downloaded one, or say there is none.
    /// `back`: the song to stop on when there is none (a jump goes back to where it came from).
    private func skipIfEmpty(back: Int? = nil) -> Bool {
        guard app.offline, ap.currentItem == nil else { return false }
        if !skipToPlayable(play: true) { if let back { q.index = back }; stop(noDownloadNext); changed() }
        return true
    }

    private func isFile(_ it: AVPlayerItem?) -> Bool { (it?.asset as? AVURLAsset)?.url.isFileURL == true }
    private var noDownloadNext: String { String(localized: "Can't reach the server, and no downloaded song is next.") }

    /// End of queue: stop on the last song, back at 0. Offline with nothing downloaded ahead: stop on the song we left, with a message.
    private func stopAtEnd(from: Int) {
        q.index = max(0, q.items.count - 1)
        if app.offline, q.index != from || !playable(q.current) { q.index = from; stop(noDownloadNext); changed(); return }
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
        let from = q.index
        q.jump(to: id)
        rebuild(play: true)
        if !skipIfEmpty(back: from) { changed() }
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

    private func changed() {
        if q.current?.id != errorId { error = nil } // the message belongs to the song that failed
        updateInfo(); save()
    }

    // MARK: observation

    private func itemChanged() {
        guard let it = ap.currentItem, let id = keys[it] else {
            return
        }
        if id != q.current?.id {
            if let f = failedCurrent { itemFailed(f); return } // the player skipped past a song that failed: handle that, don't advance
            // gapless auto-advance: the player moved on by itself
            if q.repeatMode == .one, q.current?.id == id { } else {
                _ = q.advance(auto: true)
                if q.current?.id != id, let i = q.items.firstIndex(where: { $0.id == id }) { q.index = i }
            }
            pos = 0; scrobbled = nil; revision += 1
            preloadNext(); changed()
        } else if q.repeatMode == .one {
            if let f = failedCurrent { itemFailed(f); return } // the repeat copy took over from a copy that failed
            pos = 0; scrobbled = nil; preloadNext()
        }
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
        // the player emptied itself (paused, or still at rate 1 waiting with no item to play): a failed song is handled as such; after a stop on one, stay on it
        let emptied = ap.currentItem == nil && (st == .paused || ap.reasonForWaitingToPlay == .noItemToPlay)
        if emptied && hasSong && error == nil { if let f = failedCurrent { itemFailed(f) } else { endOfQueue() } }
        save()
    }

    private func endOfQueue() {
        // The player ran out of items: carry on with the next playable song (offline skips what isn't downloaded), or stop.
        let from = q.index
        for _ in 0..<max(q.items.count, 1) {
            // repeat one stays on a song only while it can play: offline, a stream from before the flip moves on
            guard q.advance(auto: playable(q.current)) else { break }
            if playable(q.current) { rebuild(play: true); changed(); return }
        }
        pos = 0 // a message stop here replays the song from its start
        stopAtEnd(from: from)
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

    /// the current song's item when it has failed (the player may have dropped it before its failure was reported)
    private var failedCurrent: AVPlayerItem? { keys.first { $0.value == q.current?.id && $0.key.status == .failed }?.key }

    private func itemFailed(_ it: AVPlayerItem) {
        // repeat-one preloads a second item for the same song: only the one actually playing (or dropped) counts
        guard keys[it] == q.current?.id, ap.currentItem === it || !ap.items().contains(it),
              failedItems.insert(ObjectIdentifier(it)).inserted else { return } // status and end-notification both report it
        failed(it.error, at: it.currentTime().seconds, local: isFile(it))
    }

    /// A song that can't be fetched or decoded. Server gone: flip to offline and go on with the next downloaded song.
    /// Otherwise retry a streamed original once transcoded (a format the phone can't open), then stop on it with a message.
    private func failed(_ e: Error?, at: Double, local: Bool) {
        guard let s = current else { return }
        if let e, Srv.isNetwork(e) { app.markUnreachable() }
        if app.offline && !local {
            skipOffline(play: wantPlay)
        } else if !local, transcode.insert(s.id).inserted {
            rebuild(at: at.isFinite ? at : 0, play: wantPlay) // a restored, paused song stays paused
        } else if local {
            stop(String(localized: "The downloaded file can't be opened."))
        } else {
            // a dead server behind a proxy answers 502, which AVPlayer reports like a bad file: ask the server, and if it is gone, go on offline
            let n = loads, id = q.current?.id, resume = wantPlay
            stop(String(localized: "The server didn't send audio this phone can play."))
            Task {
                await app.ping()
                guard app.offline, loads == n, q.current?.id == id else { return }
                if resume { skipOffline(play: true) } else { stop(String(localized: "Can't reach the server")) }
            }
        }
    }

    private func skipOffline(play: Bool) {
        failedRun += 1
        if failedRun < 8, skipToPlayable(play: play) { error = nil; return }
        stop(noDownloadNext)
    }

    /// Stay on the failed song with the message; Play retries it (the empty player rebuilds; offline, one not downloaded is skipped), Next moves on.
    private func stop(_ message: String) {
        ap.pause(); ap.removeAllItems(); keys = [:]; itemObs = []; wantPlay = false
        error = message; errorId = q.current?.id
        save()
    }

    /// Move to the next song the phone can play right now (offline: downloaded ones), playing only if the failed one was.
    private func skipToPlayable(play: Bool) -> Bool {
        let from = q.index
        for _ in 0..<max(q.items.count, 1) {
            guard q.advance(auto: false) else { break }
            if playable(q.current) { rebuild(play: play); changed(); return true }
        }
        q.index = from // nothing to go on with: stay on the song that failed
        return false
    }

    /// A stream that sits in "buffering" for 6 s: check the server; if it is gone, carry on with downloads.
    private func watchStall(_ st: AVPlayer.TimeControlStatus) {
        stall?.cancel()
        guard st == .waitingToPlayAtSpecifiedRate, let id = q.current?.id, let s = current, !app.downloads.has(s.id) else { return }
        stall = Task {
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled, ap.timeControlStatus == .waitingToPlayAtSpecifiedRate, q.current?.id == id else { return }
            if !app.offline { await app.ping() } // the periodic probe may already have flipped us offline: then go straight on
            guard !Task.isCancelled, app.unreachable, q.current?.id == id else { return } // paused during the check: stay put
            skipOffline(play: true)
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
        c.likeCommand.localizedTitle = String(localized: "Favorite")
        c.likeCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let s = self?.current { app.toggleStar(s.id, now: !app.isStarred(s)); self?.updateInfo() } }
            return .success
        }
    }
}
