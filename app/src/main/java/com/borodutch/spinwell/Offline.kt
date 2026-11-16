package com.borodutch.spinwell

import java.text.Normalizer

// Everything here is pure (lists in, lists out) so it is unit tested; the screens pass in Downloads.done and the cached lists.

private val marks = Regex("\\p{Mn}+")

/** lowercase with accents folded ("Beyoncé" = "beyonce"); Cyrillic keeps й and folds only ё, so "мой" never matches "мои" */
fun fold(s: String): String {
    val b = StringBuilder(s.length)
    for (ch in s) {
        when {
            ch == 'ё' || ch == 'Ё' -> b.append('е')
            ch in 'Ѐ'..'ӿ' -> b.append(ch.lowercaseChar())
            else -> b.append(Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).replace(marks, "").lowercase())
        }
    }
    return b.toString()
}

/** every word of [query] appears somewhere in [fields] (case, accents and ё ignored) */
fun matches(query: String, vararg fields: String): Boolean {
    val words = fold(query).split(' ', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val hay = fold(fields.joinToString(" "))
    return words.all { it in hay }
}

class Found(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>) {
    val empty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/** the first exact name match (artist, album, song), else the first name that starts with the query */
fun topResult(query: String, f: Found): Any? {
    val q = fold(query).trim()
    if (q.isEmpty()) return null
    val all: List<Pair<String, Any>> = f.artists.map { it.name to it } + f.albums.map { it.name to it } + f.songs.map { it.title to it }
    return all.firstOrNull { fold(it.first) == q }?.second ?: all.firstOrNull { fold(it.first).startsWith(q) }?.second
}

/** "A".."Z" for Latin (accents folded), the letter itself for Cyrillic and others, "#" for digits and symbols */
fun indexLetter(name: String): String {
    val c = fold(name).trim().firstOrNull() ?: return "#"
    return if (c.isLetter()) c.uppercaseChar().toString() else "#"
}

/** artists with at least one of [songs], A to Z; [known] (the cached artist list) supplies names, photos and album counts */
fun artistsOf(songs: List<Song>, known: Map<String, Artist> = emptyMap()): List<Artist> =
    songs.filter { it.artistId.isNotEmpty() }.groupBy { it.artistId }.map { (id, ss) ->
        val k = known[id]
        Artist(id, k?.name ?: ss.first().artist, k?.albumCount?.takeIf { it > 0 } ?: ss.map { it.albumId }.distinct().size,
            k?.coverArt?.takeIf { it.isNotEmpty() } ?: ss.first().coverArt, k?.starred ?: false, emptyList())
    }.sortedBy { fold(it.name) }

/** albums that have songs among [songs], with those songs in disc and track order; [known] fills in what a song doesn't carry */
fun albumsOf(songs: List<Song>, known: Map<String, Album> = emptyMap()): List<Album> =
    songs.filter { it.albumId.isNotEmpty() }.groupBy { it.albumId }.map { (id, ss0) ->
        val ss = ss0.sortedWith(compareBy({ it.disc }, { it.track }))
        val f = ss.first(); val k = known[id]
        Album(id, k?.name ?: f.album, k?.artist ?: f.albumArtist.ifBlank { f.artist }, k?.artistId?.takeIf { it.isNotEmpty() } ?: f.artistId,
            k?.year?.takeIf { it > 0 } ?: f.year, ss.size, ss.sumOf { it.duration }, k?.coverArt?.takeIf { it.isNotEmpty() } ?: f.coverArt,
            k?.genre?.ifBlank { null } ?: f.genre, k?.starred ?: false, ss)
    }.sortedWith(compareBy({ fold(it.name) }))

/** downloaded songs of one artist: credited to them, or on one of their albums ([albumIds]) */
fun songsOfArtist(songs: List<Song>, artistId: String, albumIds: Set<String> = emptySet()) =
    songs.filter { it.artistId == artistId || it.albumId in albumIds }

/** the local search over what's downloaded */
fun searchLocal(query: String, songs: List<Song>, known: Map<String, Artist> = emptyMap(), knownAlbums: Map<String, Album> = emptyMap()): Found {
    val hits = songs.filter { matches(query, it.title, it.artist, it.album, it.albumArtist) }
    val artists = artistsOf(songs, known).filter { matches(query, it.name) }
    val albums = albumsOf(songs, knownAlbums).filter { matches(query, it.name, it.artist) }
    return Found(artists, albums, hits)
}

object Offline {
    val songs get() = Downloads.done.values.map { it.song }
}
