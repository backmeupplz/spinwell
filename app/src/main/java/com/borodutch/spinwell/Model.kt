package com.borodutch.spinwell

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

// ---------- auth + URLs (pure, unit tested) ----------

fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun authToken(password: String, salt: String) = md5(password + salt)

/** "music.example.ts.net/" -> "https://music.example.ts.net" */
fun normalizeServer(s: String): String {
    val t = s.trim().trimEnd('/')
    if (t.isEmpty()) return t
    return if ("://" in t) t else "https://$t"
}

class Session(val server: String, val user: String, val token: String, val salt: String) {
    val owner = sha256("$server|$user") // once: it is read on hot paths (queue building, cover files)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun auth() = "u=${enc(user)}&t=$token&s=$salt&v=1.16.1&c=Spinwell&f=json"
    fun url(method: String, params: Map<String, String> = emptyMap()): String =
        "$server/rest/$method?" + auth() + params.entries.joinToString("") { "&${it.key}=${enc(it.value)}" }
    fun cover(id: String, px: Int) = url("getCoverArt", mapOf("id" to id, "size" to px.toString()))
    /** kbps = 0 -> the original file */
    fun stream(id: String, kbps: Int) =
        if (kbps <= 0) url("stream", mapOf("id" to id, "maxBitRate" to "0"))
        else url("stream", mapOf("id" to id, "maxBitRate" to kbps.toString(), "format" to "mp3"))
    fun download(id: String, kbps: Int) = if (kbps <= 0) url("download", mapOf("id" to id)) else stream(id, kbps)
}

private val AUTH_KEYS = setOf("u", "t", "s", "v", "c", "f", "p")
/** Disk cache key: method + sorted params, never the auth ones. */
fun cacheKey(method: String, params: Map<String, String>) =
    sha256(method + "?" + params.filterKeys { it !in AUTH_KEYS }.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" })

// ---------- model ----------

data class Song(
    val id: String, val title: String, val album: String, val albumId: String, val artist: String, val artistId: String,
    val albumArtist: String, val track: Int, val disc: Int, val duration: Int, val coverArt: String, val suffix: String,
    val bitRate: Int, val sampleRate: Int, val bitDepth: Int, val size: Long, val path: String, val playCount: Int,
    val starred: Boolean, val year: Int, val genre: String, val artists: List<String>,
) {
    fun toJson() = JSONObject().put("id", id).put("title", title).put("album", album).put("albumId", albumId).put("artist", artist)
        .put("artistId", artistId).put("albumArtist", albumArtist).put("track", track).put("discNumber", disc).put("duration", duration)
        .put("coverArt", coverArt).put("suffix", suffix).put("bitRate", bitRate).put("samplingRate", sampleRate)
        .put("bitDepth", bitDepth).put("size", size).put("path", path).put("playCount", playCount)
        .put("starred", if (starred) "x" else "").put("year", year).put("genre", genre)
        .put("artists", JSONArray().apply { artists.forEach { put(JSONObject().put("name", it)) } })

    /** featured artists to show next to the title when they differ from the album artist */
    val featured get() = if (artist.isNotBlank() && albumArtist.isNotBlank() && artist != albumArtist) artist else ""

    companion object {
        fun of(o: JSONObject) = Song(
            id = o.optString("id"), title = o.optString("title"), album = o.optString("album"), albumId = o.optString("albumId"),
            artist = o.optString("artist"), artistId = o.optString("artistId"),
            albumArtist = o.optString("albumArtist"), track = o.optInt("track"), disc = o.optInt("discNumber", 1).coerceAtLeast(1),
            duration = o.optInt("duration"), coverArt = o.optString("coverArt", o.optString("albumId")), suffix = o.optString("suffix"),
            bitRate = o.optInt("bitRate"), sampleRate = o.optInt("samplingRate"), bitDepth = o.optInt("bitDepth"),
            size = o.optLong("size"), path = o.optString("path"), playCount = o.optInt("playCount"),
            starred = o.optString("starred").isNotEmpty(), year = o.optInt("year"), genre = o.optString("genre"),
            artists = o.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
        )
    }
}

data class Album(
    val id: String, val name: String, val artist: String, val artistId: String, val year: Int, val songCount: Int,
    val duration: Int, val coverArt: String, val genre: String, val starred: Boolean, val songs: List<Song>,
    val playCount: Int = 0, val releaseTypes: List<String> = emptyList(),
) {
    companion object {
        fun of(o: JSONObject) = Album(
            id = o.optString("id"), name = o.optString("name", o.optString("title")), artist = o.optString("artist"),
            artistId = o.optString("artistId"), year = o.optInt("year"), songCount = o.optInt("songCount"),
            duration = o.optInt("duration"), coverArt = o.optString("coverArt", o.optString("id")), genre = o.optString("genre"),
            starred = o.optString("starred").isNotEmpty(),
            songs = o.optJSONArray("song")?.let { a -> (0 until a.length()).map { Song.of(a.getJSONObject(it)) } } ?: emptyList(),
            playCount = o.optInt("playCount"),
            releaseTypes = o.optJSONObject("releaseTypes")?.let { listOf(it.toString()) } ?: o.optJSONArray("releaseTypes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        )
    }
}

data class Artist(val id: String, val name: String, val albumCount: Int, val coverArt: String, val starred: Boolean, val albums: List<Album>) {
    companion object {
        fun of(o: JSONObject) = Artist(
            id = o.optString("id"), name = o.optString("name"), albumCount = o.optInt("albumCount"),
            coverArt = o.optString("coverArt"), starred = o.optString("starred").isNotEmpty(),
            albums = o.optJSONArray("album")?.let { a -> (0 until a.length()).map { Album.of(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

data class Playlist(val id: String, val name: String, val owner: String, val songCount: Int, val duration: Int, val coverArt: String, val songs: List<Song>) {
    companion object {
        fun of(o: JSONObject) = Playlist(
            id = o.optString("id"), name = o.optString("name"), owner = o.optString("owner"), songCount = o.optInt("songCount"),
            duration = o.optInt("duration"), coverArt = o.optString("coverArt", "pl-" + o.optString("id")),
            songs = o.optJSONArray("entry")?.let { a -> (0 until a.length()).map { Song.of(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

fun JSONObject.list(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

// ---------- display helpers (pure) ----------

fun fmtTime(sec: Int): String { val s = sec.coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
fun fmtLong(sec: Int): String { val m = (sec + 30) / 60; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
    else -> "%.0f KB".format(b / 1024.0)
}
private fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000.0)

fun qualityOf(s: Song): String = when (s.suffix.lowercase()) {
    "flac", "alac", "wav", "aiff", "ape", "wv" -> {
        val name = s.suffix.uppercase()
        if (s.bitDepth > 0 && s.sampleRate > 0) "$name ${s.bitDepth}/${khz(s.sampleRate)}" else name
    }
    "opus" -> "OPUS"
    "" -> ""
    else -> s.suffix.uppercase().let { if (s.bitRate > 0) "$it ${s.bitRate}" else it }
}

/** the best quality in [songs]: lossless by depth x rate, then by bitrate */
fun bestQuality(songs: List<Song>): String =
    songs.maxByOrNull { s ->
        when (s.suffix.lowercase()) {
            "flac", "alac", "wav", "aiff", "ape", "wv" -> 1_000_000_000L + s.bitDepth * 1000L + s.sampleRate / 100
            else -> s.bitRate.toLong()
        }
    }?.let(::qualityOf) ?: ""
