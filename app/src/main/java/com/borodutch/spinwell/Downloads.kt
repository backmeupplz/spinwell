package com.borodutch.spinwell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.min

class DlFile(val song: Song, val ext: String, val bytes: Long)
class Pinned(val kind: String, val id: String, val name: String, val coverArt: String, val songIds: List<String>)

/**
 * Songs download one file at a time into `<files>/media/<owner>/<songId>.<ext>` (`.part` + Range resume, so a drop or a
 * restart never starts a file over). The queue and everything downloaded (full song JSON, so offline can list and play
 * with metadata) live in downloads.json beside the files. A foreground dataSync service keeps the queue running.
 */
object Downloads {
    val done = mutableStateMapOf<String, DlFile>()
    val queue = mutableStateListOf<Song>()
    val pinned = mutableStateListOf<Pinned>()
    var currentId by mutableStateOf<String?>(null)
    var currentBytes by mutableStateOf(0L)
    var paused by mutableStateOf(false)
    var status by mutableStateOf("")
    @Volatile private var worker: Thread? = null
    private val lock = Any()

    private fun dir() = Store.dir("media")
    private fun index() = File(dir(), "downloads.json")

    fun has(id: String) = done.containsKey(id)
    fun file(id: String): File? = done[id]?.let { File(dir(), "$id.${it.ext}") }?.takeIf { it.isFile }
    fun totalBytes() = done.values.sumOf { it.bytes }
    fun pinnedOf(kind: String, id: String) = pinned.firstOrNull { it.kind == kind && it.id == id }
    val loose get() = done.values.filter { d -> pinned.none { d.song.id in it.songIds } }

    fun load() {
        done.clear(); queue.clear(); pinned.clear(); currentId = null; paused = false
        runCatching {
            val f = index().takeIf { it.isFile } ?: return
            val j = JSONObject(f.readText())
            j.list("songs").forEach { done[it.getJSONObject("song").optString("id")] = DlFile(Song.of(it.getJSONObject("song")), it.optString("ext"), it.optLong("bytes")) }
            j.list("queue").forEach { if (!done.containsKey(it.optString("id"))) queue += Song.of(it) }
            j.list("pinned").forEach { p ->
                pinned += Pinned(p.optString("kind"), p.optString("id"), p.optString("name"), p.optString("coverArt"),
                    p.optJSONArray("songIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
            }
            paused = j.optBoolean("paused")
        }
        // drop entries whose file is gone
        done.entries.filter { File(dir(), "${it.key}.${it.value.ext}").let { f -> !f.isFile } }.forEach { done.remove(it.key) }
        kick()
    }

    private fun save() = synchronized(lock) {
        runCatching {
            val j = JSONObject()
                .put("songs", JSONArray().apply { done.values.forEach { put(JSONObject().put("song", it.song.toJson()).put("ext", it.ext).put("bytes", it.bytes)) } })
                .put("queue", JSONArray().apply { queue.toList().forEach { put(it.toJson()) } })
                .put("pinned", JSONArray().apply { pinned.toList().forEach { put(JSONObject().put("kind", it.kind).put("id", it.id).put("name", it.name).put("coverArt", it.coverArt).put("songIds", JSONArray(it.songIds))) } })
                .put("paused", paused)
            val tmp = File(index().path + ".tmp")
            tmp.writeText(j.toString()); tmp.renameTo(index())
        }
    }

    /** enqueue [songs]; [pin] remembers the album/playlist they came from */
    fun add(songs: List<Song>, pin: Pinned? = null) {
        if (pin != null) {
            pinned.removeAll { it.kind == pin.kind && it.id == pin.id }
            pinned += pin
            pin.coverArt.takeIf { it.isNotEmpty() }?.let { c -> thread { Covers.prefetch(c, 600, 1200) } }
        }
        songs.filter { !done.containsKey(it.id) && queue.none { q -> q.id == it.id } }.forEach { queue += it }
        paused = false
        save(); kick()
    }

    /** a pinned playlist/album that gained songs: queue the new ones */
    fun sync(kind: String, id: String, name: String, coverArt: String, songs: List<Song>) {
        val p = pinnedOf(kind, id) ?: return
        if (songs.map { it.id } != p.songIds || songs.any { !done.containsKey(it.id) && queue.none { q -> q.id == it.id } }) add(songs, Pinned(kind, id, name, coverArt, songs.map { it.id }))
    }

    fun removeSong(id: String) {
        queue.removeAll { it.id == id }
        done.remove(id)?.let { File(dir(), "$id.${it.ext}").delete() }
        File(dir(), "$id.part").delete()
        save()
    }

    fun removeContainer(p: Pinned) {
        pinned.remove(p)
        p.songIds.filter { id -> pinned.none { id in it.songIds } }.forEach { removeSong(it) }
        save()
    }

    fun removeAll() {
        queue.clear(); pinned.clear()
        done.clear(); currentId = null
        dir().listFiles()?.forEach { if (it.name != "downloads.json") it.delete() }
        save()
    }

    fun pauseAll(unload: Boolean = false) {
        paused = true
        if (unload) { worker = null; done.clear(); queue.clear(); pinned.clear(); currentId = null } else save()
    }
    fun resumeAll() { paused = false; save(); kick() }

    /** (re)starts the service when something waits */
    fun kick() {
        if (queue.isEmpty() || paused || Store.session == null) return
        runCatching { ContextCompat.startForegroundService(Store.app, Intent(Store.app, DlService::class.java)) }
    }

    /** the progress of a container's songs: 0..1, or 1 when all are saved */
    fun progress(songs: List<Song>): Float {
        if (songs.isEmpty()) return 0f
        var got = 0f
        songs.forEach { s -> got += if (done.containsKey(s.id)) 1f else if (s.id == currentId && s.size > 0) (currentBytes.toFloat() / s.size).coerceIn(0f, 1f) else 0f }
        return got / songs.size
    }

    private fun gate(): String? = when {
        Store.session == null -> "Signed out"
        !Settings.dlMobile && !Net.unmetered() -> "Waiting for Wi-Fi"
        else -> null
    }

    /** the queue runner; DlService starts it, one at a time */
    fun run(s: DlService) = synchronized(lock) {
        if (worker?.isAlive == true) return
        worker = thread(name = "downloads") {
            val wake = s.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spinwell:downloads")
            wake.setReferenceCounted(false)
            var wait = 2_000L
            while (!paused) {
                val song = queue.firstOrNull() ?: break
                val g = gate()
                if (g != null) { status = g; Thread.sleep(2_000); if (g == "Signed out") break; continue }
                try {
                    wake.acquire(30 * 60_000L)
                    fetch(song, s)
                    status = ""; wait = 2_000
                } catch (e: HttpErr) {
                    if (e.code in 400..499 && e.code !in setOf(401, 408, 429)) { queue.remove(song); status = "Skipped ${song.title} (HTTP ${e.code})"; save() }
                    else { status = "Waiting for the server"; wait = backoff(wait) }
                } catch (e: IOException) {
                    status = "Waiting for the server"; Net.failed(e); wait = backoff(wait)
                }
            }
            if (wake.isHeld) wake.release()
            currentId = null
            if (queue.isEmpty()) status = ""
            s.stop()
        }
    }

    private fun backoff(wait: Long): Long { // wakes early when paused
        val until = System.currentTimeMillis() + wait
        while (System.currentTimeMillis() < until && !paused) Thread.sleep(250)
        return min(wait * 2, 60_000)
    }

    private fun fetch(song: Song, s: DlService) {
        val kbps = Settings.dlKbps
        val ext = if (kbps <= 0) song.suffix.ifEmpty { "mp3" } else "mp3"
        val f = File(dir(), "${song.id}.$ext")
        val part = File(dir(), "${song.id}.part")
        currentId = song.id
        if (kbps <= 0 && song.size > 0 && part.length() > song.size) part.delete()
        // a Range starting at the end is a 416; resume() treats that as complete
        val url = Store.session?.download(song.id, kbps) ?: throw IOException("signed out")
        resume(URL(url), part) { have -> currentBytes = have; if (paused) throw IOException("paused"); s.progress() }
        if (kbps <= 0 && song.size > 0 && part.length() != song.size) throw IOException("${song.id}: ${part.length()} of ${song.size} bytes")
        if (!part.renameTo(f)) throw IOException("can't save ${f.name}")
        done[song.id] = DlFile(song, ext, f.length())
        queue.removeAll { it.id == song.id }
        song.coverArt.takeIf { it.isNotEmpty() }?.let { Covers.prefetch(it, 600, 1200) }
        save()
    }
}

/** Keeps downloads going in the background, with a progress notification. */
class DlService : Service() {
    private var last = 0L

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        runCatching { ServiceCompat.startForeground(this, 1, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        Downloads.run(this)
        return START_NOT_STICKY
    }

    private fun note(): Notification {
        val q = Downloads.queue
        val cur = q.firstOrNull { it.id == Downloads.currentId } ?: q.firstOrNull()
        val pct = if (cur != null && cur.size > 0 && cur.id == Downloads.currentId) (Downloads.currentBytes * 100 / cur.size).toInt() else 0
        return NotificationCompat.Builder(this, "dl")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(cur?.title ?: "Downloads")
            .setContentText(Downloads.status.ifEmpty { "${q.size} left" + if (cur != null) " · $pct%" else "" })
            .setProgress(100, pct, cur == null || cur.size == 0L)
            .setOngoing(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, Main::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    fun progress() {
        val now = System.currentTimeMillis()
        if (now - last < 1000) return
        last = now
        getSystemService(NotificationManager::class.java).notify(1, note())
    }

    fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stop()
}
