package com.borodutch.spinwell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import org.json.JSONObject

private val tabNames = listOf("Artists", "Albums", "Playlists", "Songs", "Favorites")
private val sorts = listOf("name" to "Name", "artist" to "Artist", "newest" to "Recently added", "year" to "Year", "frequent" to "Most played")
private fun sortType(s: String) = when (s) { "name" -> "alphabeticalByName"; "artist" -> "alphabeticalByArtist"; "year" -> "byYear"; else -> s }

private fun parseSongs3(j: JSONObject) = j.optJSONObject("searchResult3")?.list("song")?.map(Song::of).orEmpty()

@Composable
fun LibraryScreen() {
    Column(Modifier.fillMaxSize().padding(top = LocalTop.current)) {
        ScreenTitle("Library")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabNames.forEachIndexed { i, n ->
                FilterChip(Nav.libraryTab == i, { Nav.libraryTab = i }, { Text(n) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = androidx.compose.ui.graphics.Color(0xFF16112E), containerColor = Surface2),
                    border = null, shape = androidx.compose.foundation.shape.RoundedCornerShape(50))
            }
        }
        Spacer(Modifier.height(4.dp))
        Crossfade(Nav.libraryTab, Modifier.fillMaxSize(), tween(180), label = "libtab") { tab ->
            when (tab) { 0 -> ArtistsTab(); 1 -> AlbumsTab(); 2 -> PlaylistsTab(); 3 -> SongsTab(); else -> FavoritesTab() }
        }
    }
}

private const val NOT_DOWNLOADED = "Not downloaded"

@Composable
private fun ArtistsTab() {
    val st = remote("getArtists") { j -> j.getJSONObject("artists").list("index").map { it.optString("name") to it.list("artist").map(Artist::of) } }
    val cached = st.value.orEmpty()
    val offline = Net.offline
    val downloaded = Downloads.done.size
    // offline: every artist with a downloaded song first (A to Z), then the cached rest dimmed
    val groups = remember(cached, offline, downloaded) {
        if (!offline) cached else {
            val all = cached.flatMap { it.second }
            val have = artistsOf(Offline.songs, all.associateBy { it.id })
            val ids = have.map { it.id }.toSet()
            val rest = all.filter { it.id !in ids }
            have.groupBy { indexLetter(it.name) }.toList() + if (rest.isEmpty()) emptyList() else listOf(NOT_DOWNLOADED to rest)
        }
    }
    val state = Scroll.list("lib-artists")
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp, end = 20.dp)) {
            groups.forEach { (letter, artists) ->
                item(key = "h-$letter") { Text(letter, color = if (letter == NOT_DOWNLOADED) Muted else Accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 0.dp)) }
                items(artists, key = { it.id }) { ArtistRow(it, "lib", dim = offline && letter == NOT_DOWNLOADED) }
            }
            if (groups.isEmpty()) item { Empty(if (st.loading) "Loading…" else if (offline) "No downloaded artists yet." else "No artists.") }
        }
        val letters = groups.map { it.first }.filter { it.length == 1 }
        if (letters.size > 1) LetterRail(letters, Modifier.align(Alignment.CenterEnd).padding(vertical = 24.dp)) { l ->
            var idx = 0
            for ((n, a) in groups) { if (n == l) break; idx += 1 + a.size }
            scope.launch { state.scrollToItem(idx) }
        }
    }
}

@Composable
private fun AlbumsTab() {
    val sort = Settings.librarySort
    val offline = Net.offline
    val extra = if (sort == "year") mapOf("fromYear" to "0", "toYear" to "3000") else emptyMap()
    val paged = remember(sort, offline) { Paged("getAlbumList2", mapOf("type" to sortType(sort), "size" to "60") + extra, 60, ::parseAlbums) }
    val grid = rememberLazyGridState()
    LaunchedEffect(paged) { paged.more() }
    LaunchedEffect(paged, offline) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= paged.items.size - 8) paged.more() } }
    // offline: downloaded albums first, the cached rest after (AlbumCard dims those)
    val downloaded = Downloads.done.size
    val shown = if (!offline) paged.items.toList() else {
        val have = albumsOf(Offline.songs, paged.items.associateBy { it.id })
            .sortedWith(when (sort) { "artist" -> compareBy { fold(it.artist) }; "year" -> compareByDescending { it.year }; else -> compareBy { fold(it.name) } })
        val ids = have.map { it.id }.toSet()
        have + paged.items.filter { it.id !in ids }
    }
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton({ menu = true }) { Text("Sort: ${sorts.first { it.first == sort }.second}", color = Accent) }
                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                    sorts.forEach { (k, n) -> DropdownMenuItem({ Text(n) }, { Settings.librarySort = k; menu = false }) }
                }
            }
            if (offline && downloaded > 0) Text("Downloaded first", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottom.current + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shown, key = { _, a -> a.id }) { _, a -> AlbumCard(a, "lib", Modifier.fillMaxWidth()) { Nav.push(Route.Album(a.id, "lib", a.coverArt)) } }
            if (shown.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Empty(if (offline) "No downloaded albums yet." else "Loading…") }
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val st = remote("getPlaylists") { j -> j.getJSONObject("playlists").list("playlist").map(Playlist::of) }
    val cached = st.value.orEmpty()
    val offline = Net.offline
    val pins = Downloads.pinned.toList()
    // offline: pinned (downloaded) playlists first, the cached rest dimmed
    val list = remember(cached, offline, pins, Downloads.done.size) {
        if (!offline) cached else {
            val ids = cached.map { it.id }.toSet()
            val fromPins = pins.filter { it.kind == "playlist" && it.id !in ids }.map { p ->
                val ss = p.songIds.mapNotNull { Downloads.done[it]?.song }
                Playlist(p.id, p.name, "", p.songIds.size, ss.sumOf { it.duration }, p.coverArt, emptyList())
            }
            (cached + fromPins).sortedBy { if (Downloads.pinnedOf("playlist", it.id) != null) 0 else 1 }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), Scroll.list("lib-playlists"), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
        items(list, key = { it.id }) { p ->
            MediaRow(p.coverArt, p.name, "${p.songCount} songs · ${fmtLong(p.duration)}", dim = offline && Downloads.pinnedOf("playlist", p.id) == null, shareKey = "cover-lib-${p.id}") {
                Nav.push(Route.Playlist(p.id, "lib", p.coverArt))
            }
        }
        if (list.isEmpty()) item { Empty(if (st.loading) "Loading…" else if (offline) "No downloaded playlists yet." else "No playlists yet.") }
    }
}

@Composable
private fun SongsTab() {
    val offline = Net.offline
    val paged = remember(offline) { Paged("search3", mapOf("query" to "", "songCount" to "60", "artistCount" to "0", "albumCount" to "0"), 60, ::parseSongs3) }
    val state = Scroll.list("lib-songs")
    LaunchedEffect(paged) { if (!offline) paged.more() }
    LaunchedEffect(paged, offline) { snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= paged.items.size - 12) paged.more() } }
    val ctx = Ctx("songs", "all", "Songs")
    // offline the cached pages are mostly songs you can't play: list what's downloaded instead
    val songs = if (offline) Offline.songs.sortedBy { fold(it.title) } else paged.items.toList()
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
        if (songs.isNotEmpty()) item(key = "ps") { PlayShuffle(songs, ctx) }
        itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = songs, index = i, ctx = ctx) }
        if (songs.isEmpty()) item { Empty(if (offline) "No downloaded songs yet." else "Loading…") }
    }
}

@Composable
private fun FavoritesTab() {
    val st = remote("getStarred2") { j ->
        val o = j.getJSONObject("starred2")
        Triple(o.list("artist").map(Artist::of), o.list("album").map(Album::of), o.list("song").map(Song::of))
    }
    val (artists, albums, songs) = st.value ?: Triple(emptyList(), emptyList(), emptyList())
    val ctx = Ctx("favorites", "all", "Favorites")
    LazyColumn(Modifier.fillMaxSize(), Scroll.list("lib-fav"), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
        if (artists.isNotEmpty()) item {
            SectionTitle("Artists")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(artists, key = { it.id }) { a -> ArtistChip(a) { Nav.push(Route.Artist(a.id, "fav")) } } }
        }
        if (albums.isNotEmpty()) item {
            SectionTitle("Albums")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(albums, key = { it.id }) { a -> AlbumCard(a, "fav") { Nav.push(Route.Album(a.id, "fav", a.coverArt)) } } }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            item { PlayShuffle(songs, ctx) }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = songs, index = i, ctx = ctx) }
        }
        if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) item { Empty(if (st.loading) "Loading…" else "Tap the heart on anything to find it here.") }
    }
}
