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
    private val h = Handler(Looper.getMainLooper())
    private var failures = 0
    private val netErrors = setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
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
                if (isPlaying) failures = 0
                savePos()
                if (isPlaying) scrobbleCheck()
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val k = item?.mediaId?.toLongOrNull()
                val i = q.items.indexOfFirst { it.key == k }
                if (i >= 0 && i != q.index) q = q.copy(index = i)
                durationMs = player.duration.coerceAtLeast(0)
                persist(); savePos()
                if (playing) scrobbleCheck()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
            }
            override fun onPlayerError(error: PlaybackException) {
                // the server went away under a streamed song: say so, then carry on with what's downloaded
                if (error.errorCode in netErrors) Net.failed(java.io.IOException("playback"))
                Net.ping()
                if (failures++ < q.items.size) skipToPlayable()
            }
            // a lock screen / headset / Auto controller changing repeat goes straight to ExoPlayer: mirror it into the queue
            override fun onRepeatModeChanged(mode: Int) {
                val r = when (mode) { Player.REPEAT_MODE_OFF -> 0; Player.REPEAT_MODE_ALL -> 1; else -> 2 }
                if (r != q.repeat) { q = q.copy(repeat = r); persist() }
            }
        })
        h.postDelayed(tick, 5_000)
    }

    fun ensureService(ctx: Context = Store.app) { runCatching { ctx.startService(Intent(ctx, PlayerService::class.java)) } }

    // ---- items ----
    /** one lookup per cover id per batch: a 60-song album has one cover, not 60 x 4 file checks */
    private fun artUri(s: Song, memo: MutableMap<String, Uri?>): Uri? {
        if (s.coverArt.isEmpty()) return null
        return memo.getOrPut(s.coverArt) {
            val dir = File(Store.app.cacheDir, "covers/${Store.owner}")
            listOf(600, 1200, 300, 150).firstNotNullOfOrNull { px -> File(dir, "${s.coverArt}_$px").takeIf { it.length() > 0 } }?.let { Uri.fromFile(it) }
                ?: Uri.parse(Store.session!!.cover(s.coverArt, 600))
        }
    }

    private fun mediaItem(i: QItem, memo: MutableMap<String, Uri?> = HashMap()): MediaItem {
        val s = i.song
        val local = Downloads.path(s.id)
        val uri = if (local != null) Uri.fromFile(local) else Uri.parse(Store.session!!.stream(s.id, Settings.streamKbps()))
        return MediaItem.Builder().setMediaId(i.key.toString()).setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setArtworkUri(artUri(s, memo))
                .setTrackNumber(s.track.takeIf { it > 0 }).setIsPlayable(true).setIsBrowsable(false).build()
        ).build()
    }

    private fun repeatMode() = when (q.repeat) { 0 -> Player.REPEAT_MODE_OFF; 1 -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_ONE }
    fun playable(s: Song) = !Net.offline || Downloads.has(s.id)

    /** after a failed song: the next one we can play (a downloaded one while the server is gone), wrapping when repeating all */
    private fun skipToPlayable() {
        val n = q.items.size
        if (n < 2) return
        val order = (1 until n).map { (q.index + it) % n }.filter { q.repeat == 1 || it > q.index }
        val to = order.firstOrNull { playable(q.items[it].song) } ?: return
        q = q.copy(index = to)
        player.seekTo(to, 0); player.prepare(); player.play(); persist()
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
     * Loads [q] into the player: the playing song and its neighbours at once (playback starts, the first frame isn't held up),
     * the rest in chunks of 12 on the following frames. Building a few hundred MediaItems in one go took ~250 ms on the main thread.
     */
    private fun load(startPos: Long) {
        finishFill()
        val items = q.items; val i = q.index
        val from = (i - 1).coerceAtLeast(0); val to = minOf(i + 3, items.lastIndex)
        player.setMediaItems(mediaItems(items.subList(from, to + 1)), i - from, startPos)
        val after = ArrayDeque(items.subList(to + 1, items.size).chunked(12))
        val before = ArrayDeque(items.subList(0, from).chunked(12).reversed()) // nearest the window first
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
    fun setRepeat(r: Int) { q = q.copy(repeat = r); player.repeatMode = repeatMode(); persist() }
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
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
        player.play(); ensureService()
    }
    fun next() { if (player.hasNextMediaItem()) player.seekToNext() }
    fun prev() = player.seekToPrevious()
    fun seekTo(ms: Long) { player.seekTo(ms); savePos() }
    val position get() = if (ready) player.currentPosition else 0L

    // ---- persistence ----
    fun persist() {
        val snap = q // immutable: serialised off the main thread (hundreds of songs of JSON), written in order
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
                .setDisplayName("Shuffle").setSessionCommand(cmdShuffle).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(when (q.repeat) { 1 -> CommandButton.ICON_REPEAT_ALL; 2 -> CommandButton.ICON_REPEAT_ONE; else -> CommandButton.ICON_REPEAT_OFF })
                .setDisplayName("Repeat").setSessionCommand(cmdRepeat).setSlots(CommandButton.SLOT_FORWARD).build(),
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
        session = MediaSession.Builder(this, Audio.player)
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
