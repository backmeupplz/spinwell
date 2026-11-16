package com.borodutch.naviplus

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        player = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
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
            override fun onPlayerError(error: PlaybackException) { Net.ping() }
        })
        h.postDelayed(tick, 5_000)
    }

    fun ensureService(ctx: Context = Store.app) { runCatching { ctx.startService(Intent(ctx, PlayerService::class.java)) } }

    // ---- items ----
    private fun artUri(s: Song): Uri? {
        if (s.coverArt.isEmpty()) return null
        for (px in intArrayOf(600, 1200, 300, 150)) {
            val f = File(Store.app.cacheDir, "covers/${Store.owner}/${s.coverArt}_$px")
            if (f.length() > 0) return Uri.fromFile(f)
        }
        return Uri.parse(Store.session!!.cover(s.coverArt, 600))
    }

    private fun mediaItem(i: QItem): MediaItem {
        val s = i.song
        val local = Downloads.file(s.id)
        val uri = if (local != null) Uri.fromFile(local) else Uri.parse(Store.session!!.stream(s.id, Settings.streamKbps()))
        return MediaItem.Builder().setMediaId(i.key.toString()).setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setArtworkUri(artUri(s))
                .setTrackNumber(s.track.takeIf { it > 0 }).setIsPlayable(true).setIsBrowsable(false).build()
        ).build()
    }

    private fun repeatMode() = when (q.repeat) { 0 -> Player.REPEAT_MODE_OFF; 1 -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_ONE }
    fun playable(s: Song) = !Net.offline || Downloads.has(s.id)

    // ---- starting playback ----
    fun play(songs: List<Song>, at: Int, ctx: Ctx, shuffle: Boolean = false) {
        val chosen = songs.getOrNull(at)
        val list = songs.filter(::playable)
        if (list.isEmpty()) return
        val idx = chosen?.let { c -> list.indexOfFirst { it.id == c.id }.coerceAtLeast(0) } ?: 0
        val repeat = q.repeat
        q = QueueState.start(list, idx, ctx, shuffle, System.nanoTime()).copy(repeat = repeat)
        player.setMediaItems(q.items.map(::mediaItem), q.index, 0L)
        player.repeatMode = repeatMode()
        player.prepare(); player.play()
        ensureService()
        persist()
    }

    fun shuffleAll(songs: List<Song>, ctx: Ctx) = play(songs, 0, ctx, shuffle = true)

    // ---- queue edits ----
    fun playNext(s: Song) {
        if (q.items.isEmpty()) return play(listOf(s), 0, Ctx("song", s.id, s.title))
        val key = Keys.next(); val at = q.index + 1
        q = q.playNext(s, key)
        player.addMediaItem(at, mediaItem(q.items[at])); persist()
    }

    fun addToQueue(s: Song) {
        if (q.items.isEmpty()) return play(listOf(s), 0, Ctx("song", s.id, s.title))
        val at = q.index + 1 + q.nextRun.size
        q = q.addToQueue(s, Keys.next())
        player.addMediaItem(at, mediaItem(q.items[at])); persist()
    }

    fun jump(i: Int) {
        if (i !in q.items.indices) return
        q = q.copy(index = i)
        player.seekTo(i, 0); player.play(); persist()
    }

    fun removeAt(i: Int) { if (i in q.items.indices) { q = q.remove(i); player.removeMediaItem(i); persist() } }
    fun move(from: Int, to: Int) { if (from != to) { q = q.move(from, to); player.moveMediaItem(from, to); persist() } }
    fun clearNext() {
        val n = q.nextRun.size
        if (n == 0) return
        val at = q.index + 1
        q = q.clearNext(); player.removeMediaItems(at, at + n); persist()
    }

    fun toggleShuffle() {
        if (q.items.isEmpty()) { q = q.copy(shuffle = !q.shuffle); return }
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

    fun cycleRepeat() { q = q.copy(repeat = (q.repeat + 1) % 3); player.repeatMode = repeatMode(); persist() }

    fun clear() {
        if (!ready) return
        player.stop(); player.clearMediaItems()
        q = QueueState(); playing = false
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
    fun persist() { if (q.items.isNotEmpty()) Store.p.edit().putString("queue", q.toJson().toString()).apply() else Store.p.edit().remove("queue").apply() }
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
        player.setMediaItems(q.items.map(::mediaItem), q.index, Store.p.getLong("pos", 0))
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
            .build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.release() // the player outlives the service: it belongs to the app
        session = null
        super.onDestroy()
    }
}
