package com.borodutch.spinwell

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** A queue entry; the key is unique so the same song can sit in the queue twice. */
data class QItem(val key: Long, val song: Song)
data class Ctx(val kind: String = "", val id: String = "", val name: String = "")

object Keys {
    private val n = AtomicLong(1)
    fun next() = n.getAndIncrement()
    fun atLeast(v: Long) { while (true) { val c = n.get(); if (c >= v || n.compareAndSet(c, v)) return } }
}

/**
 * Random order where no two neighbours share an artist (then, secondarily, an album) whenever the input allows it.
 * [first] stays at position 0. Same seed, same order.
 * ponytail: O(n^2) group counting per step, fine for the 200-song shuffles we make; index the counts if lists get huge.
 */
fun <T> smartShuffle(input: List<T>, first: T?, seed: Long, artist: (T) -> String, album: (T) -> String): List<T> {
    val rest = input.toMutableList()
    val out = ArrayList<T>(input.size)
    if (first != null && rest.remove(first)) out += first
    rest.shuffle(Random(seed))
    while (rest.isNotEmpty()) {
        val last = out.lastOrNull()
        val lastArtist = last?.let(artist)
        val lastAlbum = last?.let(album)
        var pick = -1
        // an artist holding more than half of what's left has to be placed now or the rest can't be spread
        val (topArtist, top) = rest.groupingBy(artist).eachCount().maxByOrNull { it.value }!!
        if (top * 2 > rest.size && topArtist != lastArtist) pick = rest.indexOfFirst { artist(it) == topArtist }
        if (pick < 0) pick = rest.indexOfFirst { artist(it) != lastArtist && album(it) != lastAlbum }
        if (pick < 0) pick = rest.indexOfFirst { artist(it) != lastArtist }
        if (pick < 0) pick = 0
        out += rest.removeAt(pick)
    }
    return out
}

fun smartShuffle(songs: List<Song>, first: Song?, seed: Long) =
    smartShuffle(songs, first, seed, { it.artist.lowercase() }, { it.albumId })

data class QueueState(
    val items: List<QItem> = emptyList(),
    val original: List<QItem> = emptyList(),
    val index: Int = 0,
    val added: Set<Long> = emptySet(),
    val ctx: Ctx = Ctx(),
    val shuffle: Boolean = false,
    val repeat: Int = 0, // 0 off, 1 all, 2 one
) {
    val current get() = items.getOrNull(index)
    /** the songs queued with Play next / Add to queue that sit right after the current one */
    val nextRun get() = items.drop(index + 1).takeWhile { it.key in added }

    fun playNext(song: Song, key: Long = Keys.next()): QueueState {
        val at = (index + 1).coerceAtMost(items.size)
        return copy(items = items.toMutableList().apply { add(at, QItem(key, song)) }, added = added + key)
    }

    fun addToQueue(song: Song, key: Long = Keys.next()): QueueState {
        val at = (index + 1 + nextRun.size).coerceAtMost(items.size)
        return copy(items = items.toMutableList().apply { add(at, QItem(key, song)) }, added = added + key)
    }

    fun remove(i: Int): QueueState {
        if (i !in items.indices) return this
        val gone = items[i].key
        val left = items.filterIndexed { j, _ -> j != i }
        return copy(items = left, original = original.filter { it.key != gone }, added = added - gone,
            index = when { i < index -> index - 1; else -> index.coerceAtMost((left.size - 1).coerceAtLeast(0)) })
    }

    fun clearNext(): QueueState {
        val run = nextRun.map { it.key }.toSet()
        return copy(items = items.filter { it.key !in run }, original = original.filter { it.key !in run }, added = added - run)
    }

    fun move(from: Int, to: Int): QueueState {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val l = items.toMutableList().apply { add(to, removeAt(from)) }
        val idx = when {
            from == index -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return copy(items = l, index = idx)
    }

    fun withShuffle(on: Boolean, seed: Long): QueueState {
        val cur = current ?: return copy(shuffle = on)
        if (on == shuffle) return this
        val queued = items.drop(index + 1).filter { it.key in added }
        if (on) {
            val history = items.take(index + 1)
            val used = (history + queued).map { it.key }.toSet()
            val rest = original.filter { it.key !in used }
            val mixed = smartShuffle(listOf(cur) + rest, cur, seed, { it.song.artist.lowercase() }, { it.song.albumId }).drop(1)
            return copy(items = history + queued + mixed, shuffle = true)
        }
        val ci = original.indexOfFirst { it.key == cur.key }
        val queuedKeys = queued.map { it.key }.toSet()
        val base = original.filter { it.key !in queuedKeys }
        return if (ci >= 0) {
            val bi = base.indexOfFirst { it.key == cur.key }
            copy(items = base.take(bi + 1) + queued + base.drop(bi + 1), index = bi, shuffle = false)
        } else copy(items = listOf(cur) + queued + base, index = 0, shuffle = false)
    }

    fun toJson(): JSONObject {
        val pool = LinkedHashMap<Long, Song>()
        (items + original).forEach { pool[it.key] = it.song }
        return JSONObject()
            .put("pool", JSONArray().apply { pool.forEach { (k, s) -> put(JSONObject().put("k", k).put("s", s.toJson())) } })
            .put("items", JSONArray(items.map { it.key })).put("original", JSONArray(original.map { it.key }))
            .put("index", index).put("added", JSONArray(added.toList()))
            .put("ctx", JSONObject().put("kind", ctx.kind).put("id", ctx.id).put("name", ctx.name))
            .put("shuffle", shuffle).put("repeat", repeat)
    }

    companion object {
        fun of(o: JSONObject): QueueState {
            val pool = HashMap<Long, QItem>()
            val p = o.getJSONArray("pool")
            for (i in 0 until p.length()) p.getJSONObject(i).let { pool[it.getLong("k")] = QItem(it.getLong("k"), Song.of(it.getJSONObject("s"))) }
            fun keys(n: String) = o.getJSONArray(n).let { a -> (0 until a.length()).mapNotNull { pool[a.getLong(it)] } }
            val ad = o.getJSONArray("added")
            val c = o.getJSONObject("ctx")
            Keys.atLeast((pool.keys.maxOrNull() ?: 0) + 1)
            return QueueState(keys("items"), keys("original"), o.getInt("index"), (0 until ad.length()).map { ad.getLong(it) }.toSet(),
                Ctx(c.getString("kind"), c.getString("id"), c.getString("name")), o.getBoolean("shuffle"), o.getInt("repeat"))
        }

        /** a fresh queue over [songs]; shuffle starts from a random song */
        fun start(songs: List<Song>, at: Int, ctx: Ctx, shuffle: Boolean, seed: Long): QueueState {
            val orig = songs.map { QItem(Keys.next(), it) }
            if (orig.isEmpty()) return QueueState(ctx = ctx)
            if (!shuffle) return QueueState(orig, orig, at.coerceIn(orig.indices), emptySet(), ctx, false)
            val first = orig[Random(seed).nextInt(orig.size)]
            val mixed = smartShuffle(orig, first, seed, { it.song.artist.lowercase() }, { it.song.albumId })
            return QueueState(mixed, orig, 0, emptySet(), ctx, true)
        }
    }
}
