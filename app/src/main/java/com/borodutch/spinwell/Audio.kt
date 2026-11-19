package com.borodutch.spinwell

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The player and the app-owned queue. The queue ([q]) is the truth and is mirrored into ExoPlayer's playlist, so
 * gapless playback, the notification and headset buttons all work on the same list. Persisted on every change.
 */
object Audio {
    lateinit var player: ExoPlayer
    private var ready = false
    var q by mutableStateOf(QueueState())
    var playing by mutableStateOf(false)
    var durationMs by mutableStateOf(0L)
    var restoredWasPlaying = false // the UI opens the full player once for this
    var sleepOutputError by mutableStateOf<String?>(null)
    /** why the current song stopped (it failed to play); cleared when something plays or the song changes */
    var error by mutableStateOf<String?>(null)
    private val transcode = HashSet<String>() // songs whose original failed to play: streamed as MP3 from then on (this session)
    private val badFiles = HashSet<String>() // downloads that failed to play while online: streamed instead while online (this session)
    private val h = Handler(Looper.getMainLooper())
    private var failures = 0
    private val netErrors = setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
    /** the server is gone (as opposed to answering with an error): no connection, a timeout, or a 502/503/504 from the proxy in front of it */
    private fun serverGone(e: PlaybackException) = e.errorCode in netErrors ||
        (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode in 502..504
    private var startedKey = -1L
    private var scrobbledKey = -1L
    private val tick = object : Runnable {
        override fun run() {
            if (playing) { savePos(); scrobbleCheck() }
            h.postDelayed(this, 5_000)
        }
    }

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        q = QueueState(repeat = Store.p.getInt("repeat", 0), shuffle = Store.p.getBoolean("shuffle", false)) // survives an empty queue and a relaunch
        player = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) { failures = 0; error = null }
                savePos()
                if (isPlaying) scrobbleCheck()
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // a transition reported after a seek we made from inside the listener (stopOn, skipToPlayable): the newer one follows
                if (item?.mediaId != player.currentMediaItem?.mediaId) return
                val k = item?.mediaId?.toLongOrNull()
                val i = q.items.indexOfFirst { it.key == k }
                val from = q.index
                if (i >= 0 && i != q.index) q = q.copy(index = i)
                error = null
                repoint() // the song we just left may still be a stream (it was playing when offline flipped), this one may be stale silence
                // offline, a song that isn't downloaded is a short silence in the player: go on to the next one we can play, or stop.
                // Became current through a queue edit (restore, removal): checked once the edit is done and the player holds the queue
                if (i >= 0 && !playable(q.items[i].song)) {
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) { if (!skipToPlayable()) stopOn(from) }
                    else if (!quieting) h.post { q.current?.let { if (!playable(it.song) && !skipToPlayable()) stopOn(q.index) } }
                }
                durationMs = player.duration.coerceAtLeast(0)
                persist(); savePos()
                if (playing) scrobbleCheck()
            }
            // Next/Previous after a failed song (here, on the lock screen, a headset or Auto) wakes the idle player: another
            // song plays, or Previous retries this one from the start. In-app scrubbing doesn't (seekTo clamps to 1 ms); a
            // session seek to 0 (lock-screen/Auto scrubber, headset rewind) reads as Previous and retries it
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && player.playerError != null && player.playbackState == Player.STATE_IDLE &&
                    (new.mediaItemIndex != old.mediaItemIndex || new.positionMs == 0L)) player.prepare()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
                // a song still streaming from before the flip ended with nothing downloaded after it: Play mustn't fetch it again
                // (and only while it still is ended: stopOn's seek back may already have moved on from a stale event)
                if (state == Player.STATE_ENDED && player.playbackState == Player.STATE_ENDED && Net.offline) q.current?.let { if (!playable(it.song)) stopOn(q.index) }
            }
            override fun onPlayerError(e: PlaybackException) {
                Net.ping()
                // the server went away under a streamed song: say so, then carry on with what's downloaded
                val local = player.currentMediaItem?.localConfiguration?.uri?.scheme == "file"
                val cur = q.current ?: return
                val id = cur.song.id
                val file = Downloads.path(id) // null: the download was removed while queued
                if (!local && serverGone(e)) {
                    Net.failed(java.io.IOException("playback"))
                    // downloaded after it was queued: play the file instead of skipping it
                    if (file != null && id !in badFiles) { swapCurrent(mediaItem(cur)); return }
                    goOn()
                    return
                }
                // offline, a removed download or a song still streaming from before the flip: go on with what's downloaded
                if (file == null && Net.offline) { goOn(); return }
                // the server sent something that won't decode, or the downloaded file won't open: ask the server for MP3 once,
                // from where it stopped (a removed download streams anyway; compared by URI: a second copy of the song in the
                // queue was built before the first one failed)
                val first = if (local) file != null && !Net.offline && badFiles.add(id) else transcode.add(id)
                if (local && first) transcode.add(id)
                val mp3 = mediaItem(cur)
                if (first || mp3.localConfiguration?.uri != player.currentMediaItem?.localConfiguration?.uri) { swapCurrent(mp3); return }
                // stay on the song with a message (Play retries, Next moves on) instead of running through the queue
                error = Store.app.getString(if (local) R.string.err_play_local else R.string.err_play_stream)
            }
        })
        h.postDelayed(tick, 5_000)
    }

    fun ensureService(ctx: Context = Store.app) { runCatching { ctx.startService(Intent(ctx, PlayerService::class.java)) } }

    // ---- items ----
    /** one lookup per cover id per batch: a 60-song album has one cover, not 60 x 4 file checks */
    private fun artUri(s: Song, memo: MutableMap<String, Uri?>): Uri? {
        if (s.coverArt.isEmpty()) return null
        if (s.coverArt in memo) return memo[s.coverArt] // a miss too: getOrPut would look again
        val dir = File(Store.app.cacheDir, "covers/${Store.owner}")
        val u = listOf(600, 1200, 300, 150).firstNotNullOfOrNull { px -> File(dir, "${s.coverArt}_$px").takeIf { it.length() > 0 } }?.let { Uri.fromFile(it) }
            ?: if (Net.offline) null else Uri.parse(Store.session!!.cover(s.coverArt, 600))
        memo[s.coverArt] = u
        return u
    }

    /** 10 ms of silence: what a song that isn't downloaded is in the player while offline, so preloading it never touches the network */
    private val silence: Uri = run {
        val n = 80 // 8 kHz, 8-bit mono
        val b = java.nio.ByteBuffer.allocate(44 + n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(8000)
            .putShort(1).putShort(8).put("data".toByteArray()).putInt(n)
        repeat(n) { b.put(0x80.toByte()) }
        Uri.parse("data:audio/wav;base64," + android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP))
    }

    /** the downloaded file to play: a download that failed to play streams instead while online */
    private fun localFile(id: String) = Downloads.path(id)?.takeIf { id !in badFiles || Net.offline }

    private fun mediaItem(i: QItem, memo: MutableMap<String, Uri?> = HashMap()): MediaItem {
        val s = i.song
        val local = localFile(s.id)
        val kbps = Settings.streamKbps().let { if (it <= 0 && s.id in transcode) 320 else it }
        val uri = if (local != null) Uri.fromFile(local) else if (Net.offline) silence else Uri.parse(Store.session!!.stream(s.id, kbps))
        return MediaItem.Builder().setMediaId(i.key.toString()).setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setArtworkUri(artUri(s, memo))
                .setTrackNumber(s.track.takeIf { it > 0 }).setIsPlayable(true).setIsBrowsable(false).build()
        ).build()
    }

    /** replaces the playing item and picks up from where it stopped (from the start once it has ended) */
    private fun swapCurrent(m: MediaItem) {
        val i = player.currentMediaItemIndex; val at = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition
        player.replaceMediaItem(i, m); player.seekTo(i, at); player.prepare(); player.repeatMode = repeatMode()
    }

    /** a song that can't play now: the next playable one, or a message (failures are capped at the queue length) */
    private fun goOn() {
        if (failures++ >= q.items.size || !skipToPlayable()) { if (Net.offline) stopOn(q.index) else error = Store.app.getString(R.string.err_no_downloaded_next) }
    }

    /**
     * Offline, repeat all on a song still streaming from before the flip with nothing else downloaded would wrap the look-ahead
     * round to that song and fetch it again: the player doesn't repeat then (the queue would only stop on it anyway)
     */
    // ponytail: other downloaded songs shorter than the look-ahead buffer (~50 s in all) can still let it wrap; mask always and wrap by hand if that matters
    private fun repeatMode() = when (q.repeat) {
        0 -> Player.REPEAT_MODE_OFF
        1 -> if (offlineStream() && q.items.indices.none { it != q.index && playable(q.items[it].song) }) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_ONE
    }
    fun playable(s: Song) = !Net.offline || Downloads.has(s.id)

    /**
     * offline with nothing downloaded ahead: paused back on the song we left, when it can play, with a message. Staying on a song
     * that isn't downloaded turns it into the silence, so Play never fetches a song still streaming from before the flip again
     */
    private fun stopOn(from: Int) {
        player.pause()
        val cur = q.current
        if (from != q.index && from in q.items.indices && playable(q.items[from].song)) player.seekTo(from, 0)
        else if (cur != null && !playable(cur.song)) silenceCurrent(cur)
        h.post { error = Store.app.getString(R.string.err_no_downloaded_next) } // after the seek's own transition, which clears it
    }

    /** the player's current item becomes [cur]'s silence, unless it already is or the player is on another song */
    private fun silenceCurrent(cur: QItem) {
        val m = player.currentMediaItem ?: return
        if (m.mediaId == cur.key.toString() && m.localConfiguration?.uri != silence) player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(cur))
    }

    /** after a failed song: the next one we can play (a downloaded one while the server is gone), wrapping when repeating all */
    private fun skipToPlayable(): Boolean {
        val n = q.items.size
        if (n < 2) return false
        finishFill() // the player's indexes must match the queue's
        val order = (1 until n).map { (q.index + it) % n }.filter { q.repeat == 1 || it > q.index }
        val to = order.firstOrNull { playable(q.items[it].song) } ?: return false
        q = q.copy(index = to)
        player.replaceMediaItem(to, mediaItem(q.items[to])) // it may have been downloaded since it was queued
        player.seekTo(to, 0); player.prepare(); persist() // keeps playWhenReady: a paused song moves on paused
        return true
    }

    /**
     * Offline flipped or downloads changed (any thread): point the queued songs at their file, their stream, or (offline, not
     * downloaded) silence. The playing song is left alone, since changing it would restart it; it plays to its end.
     * Only a silence that can now play for real is replaced in place.
     */
    fun refresh() { if (!refreshPosted) { refreshPosted = true; h.post { refreshPosted = false; repoint() } } }
    @Volatile private var refreshPosted = false
    private fun repoint() {
        if (!ready || Store.session == null) return
        player.repeatMode = repeatMode() // the playing song or what is downloaded changed
        val byKey = q.items.associateBy { it.key }
        val memo = HashMap<String, Uri?>()
        val offline = Net.offline
        val n = player.mediaItemCount; val c = player.currentMediaItemIndex
        if (n == 0) return
        val fresh = arrayOfNulls<MediaItem>(n) // the rebuilt stale items
        for (i in 0 until n) {
            val m = player.getMediaItemAt(i)
            val qi = byKey[m.mediaId.toLongOrNull()] ?: continue
            val now = m.localConfiguration?.uri
            val local = localFile(qi.song.id)
            if (i == c && (now != silence || local == null && offline)) continue
            val art = m.mediaMetadata.artworkUri
            val stale = when {
                local != null -> now != Uri.fromFile(local)
                offline -> now != silence
                else -> now == silence || now?.scheme == "file"
            } || if (offline) art?.scheme?.startsWith("http") == true else art == null && qi.song.coverArt.isNotEmpty()
            if (stale) fresh[i] = mediaItem(qi, memo)
        }
        // one replaceMediaItems per side of the playing song (one per item is a timeline rebuild each, O(n²) on a long queue),
        // spanning only the first to last changed song: a range is rebuilt whole, which would drop the buffered next song.
        // The playing song goes on its own: inside a range, ExoPlayer would move on to the range's first song
        fun side(a: Int, b: Int) {
            val from = (a until b).firstOrNull { fresh[it] != null } ?: return
            val to = (a until b).last { fresh[it] != null } + 1
            player.replaceMediaItems(from, to, (from until to).map { fresh[it] ?: player.getMediaItemAt(it) })
        }
        side(c + 1, n); fresh[c]?.let { player.replaceMediaItem(c, it) }; side(0, c)
    }

    // ---- starting playback ----
    fun play(songs: List<Song>, at: Int, ctx: Ctx, shuffle: Boolean = false) {
        val chosen = songs.getOrNull(at)
        val list = songs.filter(::playable)
        if (list.isEmpty()) return
        val idx = chosen?.let { c -> list.indexOfFirst { it.id == c.id }.coerceAtLeast(0) } ?: 0
        val repeat = q.repeat
        q = QueueState.start(list, idx, ctx, shuffle, System.nanoTime()).copy(repeat = repeat)
        load(0L)
        player.repeatMode = repeatMode()
        player.prepare(); player.play()
        ensureService()
        persist()
        // the player sheet will want the big cover and its color: start that now, not when it first draws
        q.current?.song?.coverArt?.takeIf { it.isNotEmpty() }?.let { c -> Store.scope.launch(Dispatchers.IO) { Covers.load(c, 600); Covers.load(c, 1200) } }
    }

    // ---- putting the queue into ExoPlayer without a hitch ----
    private fun mediaItems(l: List<QItem>): List<MediaItem> { val memo = HashMap<String, Uri?>(); return l.map { mediaItem(it, memo) } }
    private var fill: (() -> Boolean)? = null
    private val fillFrame = Choreographer.FrameCallback { stepFill() }
    private fun stepFill() { val f = fill ?: return; if (f()) Choreographer.getInstance().postFrameCallback(fillFrame) else fill = null }
    /** edits to the queue need the player to hold all of it first */
    private fun finishFill() { while (true) { val f = fill ?: return; if (!f()) fill = null } }

    /**
     * Loads [q] into the player: the playing song at once (playback starts, the first frame isn't held up),
     * the rest in chunks of 6 on the following frames. Building a few hundred MediaItems in one go took ~250 ms on the main thread.
     */
    private fun load(startPos: Long) {
        finishFill()
        val items = q.items; val i = q.index
        val from = i; val to = i // just the song to play: every extra item cost ~1.5 ms inside the tap
        player.setMediaItems(mediaItems(items.subList(from, to + 1)), i - from, startPos)
        val after = ArrayDeque(items.subList(to + 1, items.size).chunked(6))
        val before = ArrayDeque(items.subList(0, from).chunked(6).reversed()) // nearest the window first
        if (after.isEmpty() && before.isEmpty()) return
        fill = {
            if (after.isNotEmpty()) player.addMediaItems(mediaItems(after.removeFirst()))
            else if (before.isNotEmpty()) player.addMediaItems(0, mediaItems(before.removeFirst()))
            after.isNotEmpty() || before.isNotEmpty()
        }
        Choreographer.getInstance().postFrameCallback(fillFrame)
    }

    fun shuffleAll(songs: List<Song>, ctx: Ctx) = play(songs, 0, ctx, shuffle = true)

    // ---- queue edits ----
    fun playNext(s: Song) {
        finishFill()
        if (q.items.isEmpty()) return play(listOf(s), 0, Ctx("song", s.id, s.title))
        val key = Keys.next(); val at = q.index + 1
        q = q.playNext(s, key)
        player.addMediaItem(at, mediaItem(q.items[at])); persist()
    }

    fun addToQueue(s: Song) {
        finishFill()
        if (q.items.isEmpty()) return play(listOf(s), 0, Ctx("song", s.id, s.title))
        val at = q.index + 1 + q.nextRun.size
        q = q.addToQueue(s, Keys.next())
        player.addMediaItem(at, mediaItem(q.items[at])); persist()
    }

    fun jump(i: Int) {
        finishFill()
        if (i !in q.items.indices) return
        q = q.copy(index = i)
        player.seekTo(i, 0); player.play(); persist()
    }

    fun removeAt(i: Int) { finishFill(); if (i in q.items.indices) { q = q.remove(i); player.removeMediaItem(i); persist() } }
    fun move(from: Int, to: Int) { finishFill(); if (from != to) { q = q.move(from, to); player.moveMediaItem(from, to); persist() } }
    fun clearNext() {
        finishFill()
        val n = q.nextRun.size
        if (n == 0) return
        val at = q.index + 1
        q = q.clearNext(); player.removeMediaItems(at, at + n); persist()
    }

    fun toggleShuffle() {
        finishFill()
        if (q.items.isEmpty()) { q = q.copy(shuffle = !q.shuffle); persist(); return }
        val old = q
        val n = old.withShuffle(!old.shuffle, System.nanoTime())
        q = n
        // keep the playing item untouched (no gap), swap everything around it
        player.removeMediaItems(old.index + 1, player.mediaItemCount)
        if (old.index > 0) player.removeMediaItems(0, old.index)
        if (n.index > 0) player.addMediaItems(0, n.items.take(n.index).map(::mediaItem))
        player.addMediaItems(n.items.drop(n.index + 1).map(::mediaItem))
        persist()
    }

    fun cycleRepeat() = setRepeat((q.repeat + 1) % 3)
    fun setRepeat(r: Int) { q = q.copy(repeat = r); persist() }
    /** start [songs] in order with repeat-all on ("Repeat album") */
    fun playRepeating(songs: List<Song>, ctx: Ctx) { play(songs, 0, ctx); setRepeat(1) }

    fun clear() {
        if (!ready) return
        player.stop(); player.clearMediaItems()
        q = QueueState(repeat = q.repeat, shuffle = q.shuffle); playing = false
    }

    // ---- transport ----
    fun toggle() {
        if (q.items.isEmpty()) return
        if (player.isPlaying) { player.pause(); return }
        if (!canPlay()) return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
        player.play(); ensureService()
    }
    /**
     * Before Play here or from any controller (the session player asks too). Offline on a song that isn't downloaded (restored,
     * or left from before the flip): the next one we can play, or the message and false. One downloaded while it streamed goes on from the file
     */
    fun canPlay(): Boolean {
        if (going()) return true // a song still streaming from before the flip plays to its end
        val c = q.current ?: return true
        if (!playable(c.song)) return skipToPlayable() || run { stopOn(q.index); false }
        prepareOffline()
        return true
    }
    /**
     * A controller's bare prepare (Auto, a car on connect) and Play: offline, a paused song that isn't downloaded becomes the
     * silence (moving on is left to Play), one downloaded while it streamed goes on from the file
     */
    fun prepareOffline() {
        if (!Net.offline || going()) return
        val c = q.current ?: return
        if (!playable(c.song)) { quieting = true; silenceCurrent(c); quieting = false } // its transition mustn't skip
        else if (offlineStream()) swapCurrent(mediaItem(c))
    }
    private var quieting = false
    /** playing, or rebuffering on its way to it */
    private fun going() = player.playWhenReady && player.playbackState.let { it == Player.STATE_READY || it == Player.STATE_BUFFERING }
    fun offlineStream() = Net.offline && player.currentMediaItem?.localConfiguration?.uri?.scheme?.startsWith("http") == true
    /**
     * Offline, a song still streaming from before the flip seeks only forward within what is buffered: anywhere else (behind the
     * play position too, nothing is kept there) fetches it again. Null: stay put
     */
    fun seekable(ms: Long): Long? = when {
        !offlineStream() -> ms
        !player.isCurrentMediaItemSeekable -> null // ExoPlayer seeks a stream it can't seek (no Content-Length) by loading it again from the start
        else -> ms.coerceAtMost(player.bufferedPosition).takeIf { it > player.currentPosition }
    }
    fun next() { if (player.hasNextMediaItem()) player.seekToNext() }
    /** Previous here and from every controller (the session player forwards it) */
    fun prev() {
        val cur = q.current ?: return
        if (!Net.offline) { player.seekToPrevious(); return }
        // offline: back to the previous song we can play, not onto one that isn't downloaded (that would skip forward again),
        // and never restarting a song that is still streaming from before the flip
        finishFill()
        val n = q.items.size
        val restart = playable(cur.song) && player.currentPosition > player.maxSeekToPreviousPosition
        val back = if (restart) null else (1 until n).map { (q.index - it + n) % n }.filter { q.repeat == 1 || it < q.index }.firstOrNull { playable(q.items[it].song) }
        val to = back ?: if (playable(cur.song)) q.index else return
        if (to == q.index && offlineStream()) player.replaceMediaItem(to, mediaItem(cur)) // downloaded while it streamed: restart from the file
        player.seekTo(to, 0)
    }
    // a failed song stays stopped when scrubbed: 0 would read as Previous and retry it
    fun seekTo(ms: Long) { seekable(if (player.playerError != null) ms.coerceAtLeast(1) else ms)?.let { player.seekTo(it) }; savePos() }
    val position get() = if (ready) player.currentPosition else 0L

    // ---- persistence ----
    fun persist() {
        val snap = q // immutable: serialised off the main thread (hundreds of songs of JSON), written in order
        if (ready) player.repeatMode = repeatMode() // a queue edit can change what else is downloaded
        Store.p.edit().putInt("repeat", snap.repeat).putBoolean("shuffle", snap.shuffle).apply()
        updateButtons()
        writer.execute {
            if (Store.session == null) return@execute
            val e = Store.p.edit()
            if (snap.items.isNotEmpty()) e.putString("queue", snap.toJson().toString()) else e.remove("queue")
            e.apply()
        }
    }
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ---- lock screen / notification buttons: shuffle and repeat, in the state the app shows ----
    val cmdShuffle = SessionCommand("shuffle", Bundle.EMPTY)
    val cmdRepeat = SessionCommand("repeat", Bundle.EMPTY)
    var session: MediaSession? = null
    private var buttonsFor = -1
    fun updateButtons(force: Boolean = false) {
        val now = q.repeat * 2 + if (q.shuffle) 1 else 0
        if (now == buttonsFor && !force) return
        buttonsFor = now
        session?.setMediaButtonPreferences(listOf(
            CommandButton.Builder(if (q.shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(str(R.string.action_shuffle)).setSessionCommand(cmdShuffle).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(when (q.repeat) { 1 -> CommandButton.ICON_REPEAT_ALL; 2 -> CommandButton.ICON_REPEAT_ONE; else -> CommandButton.ICON_REPEAT_OFF })
                .setDisplayName(str(R.string.action_repeat)).setSessionCommand(cmdRepeat).setSlots(CommandButton.SLOT_FORWARD).build(),
        ))
    }
    fun savePos() {
        if (!ready || q.items.isEmpty()) return
        Store.p.edit().putLong("pos", player.currentPosition).putBoolean("wasPlaying", player.playWhenReady).apply()
    }

    /** Puts the saved queue into the player, paused at the saved position, before any network call. */
    fun restore() {
        val j = Store.p.getString("queue", null) ?: return
        val saved = runCatching { QueueState.of(JSONObject(j)) }.getOrNull() ?: return
        if (saved.items.isEmpty()) return
        q = saved.copy(index = saved.index.coerceIn(saved.items.indices))
        load(Store.p.getLong("pos", 0))
        player.repeatMode = repeatMode()
        player.prepare()
        restoredWasPlaying = Store.p.getBoolean("wasPlaying", false)
        if (restoredWasPlaying && Settings.resume) { player.play(); ensureService() }
    }

    // ---- scrobble: now-playing when a song starts, a play once half of it (or 4 min) has been heard ----
    private fun scrobbleCheck() {
        if (!Settings.scrobble) return
        if (player.currentMediaItem?.localConfiguration?.uri == silence) return // the stand-in for a song offline was never heard
        val cur = q.current ?: return
        if (startedKey != cur.key && !Net.offline) {
            startedKey = cur.key
            Store.scope.launch(Dispatchers.IO) { runCatching { Api.call("scrobble", mapOf("id" to cur.song.id, "submission" to "false")) } }
        }
        if (scrobbledKey != cur.key) {
            val pos = player.currentPosition
            val dur = maxOf(player.duration, 0L).takeIf { it > 0 } ?: (cur.song.duration * 1000L)
            if (pos >= dur / 2 || pos >= 240_000) {
                scrobbledKey = cur.key
                Outbox.send("scrobble", mapOf("id" to cur.song.id, "submission" to "true", "time" to System.currentTimeMillis().toString()))
            }
        }
    }
}

class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Audio.init(this)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notif) })
        // Previous from the lock screen, a headset or Auto goes through Audio.prev(): offline it steps back over songs that aren't downloaded.
        // Play (rewind when ended, play) asks Audio.canPlay() first and a bare prepare only quiets a song that isn't downloaded, so offline it never streams a song that isn't downloaded
        val p = object : ForwardingPlayer(Audio.player) {
            override fun seekToPrevious() = Audio.prev()
            override fun seekToPreviousMediaItem() { if (Net.offline) Audio.prev() else super.seekToPreviousMediaItem() }
            // a lock screen / headset / Auto controller changing repeat: into the queue (the player's own mode can be masked, see repeatMode)
            override fun setRepeatMode(mode: Int) = Audio.setRepeat(when (mode) { Player.REPEAT_MODE_OFF -> 0; Player.REPEAT_MODE_ALL -> 1; else -> 2 })
            override fun prepare() { Audio.prepareOffline(); super.prepare() }
            override fun seekTo(positionMs: Long) { Audio.seekable(positionMs)?.let { super.seekTo(it) } }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (mediaItemIndex != currentMediaItemIndex) super.seekTo(mediaItemIndex, positionMs) else Audio.seekable(positionMs)?.let { super.seekTo(mediaItemIndex, it) }
            }
            override fun seekBack() { if (!Audio.offlineStream()) super.seekBack() } // back from the buffer would fetch it again
            override fun seekForward() { if (Audio.offlineStream()) Audio.seekable(currentPosition + seekForwardIncrement)?.let { super.seekTo(it) } else super.seekForward() }
            override fun seekToDefaultPosition(mediaItemIndex: Int) { // a queue-item tap in Auto or another controller
                if (mediaItemIndex != currentMediaItemIndex) super.seekToDefaultPosition(mediaItemIndex) else Audio.seekable(0)?.let { super.seekToDefaultPosition(mediaItemIndex) }
            }
            override fun seekToDefaultPosition() { if (Audio.canPlay() && Audio.seekable(0) != null) super.seekToDefaultPosition() }
            override fun play() { if (Audio.canPlay()) super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (!playWhenReady || Audio.canPlay()) super.setPlayWhenReady(playWhenReady) }
        }
        session = MediaSession.Builder(this, p)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, Main::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(Audio.cmdShuffle).add(Audio.cmdRepeat).build())
                        .build()
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) { "shuffle" -> Audio.toggleShuffle(); "repeat" -> Audio.cycleRepeat() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        // The UI drives Audio.player directly, so no MediaController ever asks for this session through onGetSession.
        // Without addSession the service never tracks it: no media notification, no foreground service, no lock screen controls.
        addSession(session!!)
        Audio.session = session
        Audio.updateButtons(force = true)
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        Audio.session = null
        session?.release() // the player outlives the service: it belongs to the app
        session = null
        super.onDestroy()
    }
}
