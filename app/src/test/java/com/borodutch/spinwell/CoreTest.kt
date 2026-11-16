package com.borodutch.spinwell

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URL

private fun song(n: Int, artist: String, album: String = "al-$artist") = Song(
    "s$n", "Song $n", album, "al-$album", artist, "ar-$artist", artist, n, 1, 180, "al-$album", "mp3", 320, 44100, 0, 1000, "p", 0, false, 2020, "", emptyList())

class CoreTest {
    @Test fun authToken() = assertEquals("26719a1196d2a940705a59634eb18eab", authToken("sesame", "c19b2d")) // Subsonic API docs example

    @Test fun urls() {
        val s = Session("http://h:4533", "admin", "tok", "salt")
        assertEquals("http://h:4533/rest/stream?u=admin&t=tok&s=salt&v=1.16.1&c=Spinwell&f=json&id=7&maxBitRate=0", s.stream("7", 0))
        assertTrue(s.stream("7", 192).endsWith("&id=7&maxBitRate=192&format=mp3"))
        assertTrue(s.download("7", 0).contains("rest/download?"))
        assertTrue(s.cover("al-1_x", 300).endsWith("id=al-1_x&size=300"))
        assertEquals("https://music.x.ts.net", normalizeServer("  music.x.ts.net/ "))
        assertEquals("http://10.0.2.2:4533", normalizeServer("http://10.0.2.2:4533/"))
    }

    @Test fun cacheKeyIgnoresAuth() {
        val a = cacheKey("getAlbum", mapOf("id" to "1", "u" to "admin", "t" to "x", "s" to "y"))
        val b = cacheKey("getAlbum", mapOf("t" to "other", "id" to "1"))
        assertEquals(a, b)
        assertNotEquals(a, cacheKey("getAlbum", mapOf("id" to "2")))
        assertEquals(cacheKey("m", mapOf("a" to "1", "b" to "2")), cacheKey("m", mapOf("b" to "2", "a" to "1")))
    }

    @Test fun smartShuffle() {
        val songs = (1..30).map { song(it, "artist${it % 6}") } // 6 artists x 5 songs: spreadable
        for (seed in 1L..20L) {
            val out = smartShuffle(songs, songs[7], seed)
            assertEquals(songs.toSet(), out.toSet()); assertEquals(30, out.size)
            assertEquals(songs[7], out[0])
            out.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed", a.artist, b.artist) }
            assertEquals(out, smartShuffle(songs, songs[7], seed))
        }
        assertNotEquals(smartShuffle(songs, null, 1), smartShuffle(songs, null, 2))
        // one dominant artist cannot be spread, but nothing is lost
        val heavy = (1..10).map { song(it, "x") } + song(11, "y")
        assertEquals(11, smartShuffle(heavy, null, 3).size)
        // a dominant-but-feasible artist (5 of 9) alternates
        val tight = (1..5).map { song(it, "x") } + (6..9).map { song(it, "y$it") }
        smartShuffle(tight, null, 4).zipWithNext().forEach { (a, b) -> assertNotEquals(a.artist, b.artist) }
    }

    private fun state(n: Int = 5) = QueueState.start((1..n).map { song(it, "a$it") }, 1, Ctx("album", "1", "Album"), false, 1)

    @Test fun queueOps() {
        var q = state()
        assertEquals("s2", q.current!!.song.id)
        q = q.playNext(song(90, "n")).playNext(song(91, "m")).addToQueue(song(92, "q"))
        assertEquals(listOf("s2", "s91", "s90", "s92", "s3", "s4", "s5"), q.items.drop(1).map { it.song.id })
        assertEquals(3, q.nextRun.size)
        q = q.remove(2) // s91 gone, current index unchanged
        assertEquals(1, q.index); assertEquals(2, q.nextRun.size)
        q = q.move(1, 3) // move current down
        assertEquals("s2", q.current!!.song.id)
        q = state().move(4, 0)
        assertEquals("s2", q.current!!.song.id); assertEquals(2, q.index)
        q = state().remove(0)
        assertEquals("s2", q.current!!.song.id); assertEquals(0, q.index)
        assertEquals(0, state().playNext(song(9, "z")).clearNext().nextRun.size)
    }

    @Test fun shuffleOffRestoresOrder() {
        var q = state(12).playNext(song(90, "n"))
        q = q.withShuffle(true, 5)
        assertTrue(q.shuffle); assertEquals("s2", q.current!!.song.id); assertEquals("s90", q.items[q.index + 1].song.id)
        assertEquals(13, q.items.size)
        q = q.copy(index = q.index + 3) // pretend we moved on
        val cur = q.current!!
        q = q.withShuffle(false, 5)
        assertEquals(cur.key, q.current!!.key)
        val ctxIds = q.items.map { it.song.id }.filter { it != "s90" }
        assertEquals((1..12).map { "s$it" }, ctxIds)
    }

    @Test fun queueJsonRoundTrip() {
        val q = state().playNext(song(90, "n")).withShuffle(true, 3)
        val r = QueueState.of(q.toJson())
        assertEquals(q.items.map { it.key }, r.items.map { it.key }); assertEquals(q.added, r.added); assertEquals(q.index, r.index)
        assertEquals(q.items.first().song, r.items.first().song)
    }

    @Test fun qualityBadges() {
        fun s(suffix: String, depth: Int = 0, rate: Int = 0, br: Int = 0) = song(1, "a").copy(suffix = suffix, bitDepth = depth, sampleRate = rate, bitRate = br)
        assertEquals("FLAC 24/96", qualityOf(s("flac", 24, 96000)))
        assertEquals("FLAC 16/44.1", qualityOf(s("flac", 16, 44100)))
        assertEquals("MP3 320", qualityOf(s("mp3", br = 320)))
        assertEquals("OPUS", qualityOf(s("opus", br = 128)))
        assertEquals("FLAC 24/96", bestQuality(listOf(s("mp3", br = 320), s("flac", 16, 44100), s("flac", 24, 96000))))
    }

    @Test fun downloadResumesWithRange() {
        val data = ByteArray(300_000) { (it * 31).toByte() }
        val ranges = ArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { ex ->
            val r = ex.requestHeaders.getFirst("Range").also { ranges += it }
            val from = r?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (r != null) ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            ex.sendResponseHeaders(if (r != null) 206 else 200, (data.size - from).toLong())
            ex.responseBody.use { it.write(data, from, data.size - from) }
        }
        server.start()
        try {
            val part = File.createTempFile("dlpart", ".part").apply { deleteOnExit() }
            part.writeBytes(data.copyOf(100_000))
            resume(URL("http://127.0.0.1:${server.address.port}/f"), part) {}
            assertArrayEquals(data, part.readBytes())
            assertEquals("bytes=100000-", ranges.single())
            part.delete(); resume(URL("http://127.0.0.1:${server.address.port}/f"), part) {} // fresh: no Range
            assertEquals(2, ranges.size); assertNull(ranges[1]); assertEquals(data.size.toLong(), part.length())
        } finally { server.stop(0) }
    }
}
