package com.borodutch.naviplus

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
        Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabNames.forEachIndexed { i, n ->
                FilterChip(Nav.libraryTab == i, { Nav.libraryTab = i }, { Text(n) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = androidx.compose.ui.graphics.Color(0xFF16112E), containerColor = Surface2),
                    border = null, shape = androidx.compose.foundation.shape.RoundedCornerShape(50))
            }
        }
        Spacer(Modifier.height(8.dp))
        Crossfade(Nav.libraryTab, Modifier.fillMaxSize(), tween(180), label = "libtab") { tab ->
            when (tab) { 0 -> ArtistsTab(); 1 -> AlbumsTab(); 2 -> PlaylistsTab(); 3 -> SongsTab(); else -> FavoritesTab() }
        }
    }
}

@Composable
private fun ArtistRow(a: Artist) {
    val dim = Net.offline && Downloads.done.values.none { it.song.artistId == a.id }
    Row(Modifier.fillMaxWidth().alpha(if (dim) 0.4f else 1f).press(!dim) { Nav.push(Route.Artist(a.id, "lib")) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(a.coverArt, 150, Modifier.size(48.dp), 50.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (a.albumCount > 0) Text(if (a.albumCount == 1) "1 album" else "${a.albumCount} albums", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ArtistsTab() {
    val st = remote("getArtists") { j -> j.getJSONObject("artists").list("index").map { it.optString("name") to it.list("artist").map(Artist::of) } }
    val groups = st.value.orEmpty()
    val state = Scroll.list("lib-artists")
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp, end = 20.dp)) {
            groups.forEach { (letter, artists) ->
                item(key = "h-$letter") { Text(letter, color = Accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)) }
                items(artists, key = { it.id }) { ArtistRow(it) }
            }
            if (groups.isEmpty()) item { Empty(if (st.loading) "Loading…" else if (Net.offline) "Nothing cached yet." else "No artists.") }
        }
        if (groups.size > 1) LetterRail(groups.map { it.first }, Modifier.align(Alignment.CenterEnd).padding(vertical = 24.dp)) { l ->
            var idx = 0
            for ((n, a) in groups) { if (n == l) break; idx += 1 + a.size }
            scope.launch { state.scrollToItem(idx) }
        }
    }
}

@Composable
private fun AlbumsTab() {
    val sort = Settings.librarySort
    val extra = if (sort == "year") mapOf("fromYear" to "0", "toYear" to "3000") else emptyMap()
    val paged = remember(sort) { Paged("getAlbumList2", mapOf("type" to sortType(sort), "size" to "60") + extra, 60, ::parseAlbums) }
    val grid = rememberLazyGridState()
    LaunchedEffect(paged) { paged.more() }
    LaunchedEffect(paged, Net.offline) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= paged.items.size - 8) paged.more() } }
    var menu by remember { mutableStateOf(false) }
    Column {
        Box(Modifier.padding(horizontal = 16.dp)) {
            TextButton({ menu = true }) { Text("Sort: ${sorts.first { it.first == sort }.second}", color = Accent) }
            DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                sorts.forEach { (k, n) -> DropdownMenuItem({ Text(n) }, { Settings.librarySort = k; menu = false }) }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottom.current + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(paged.items, key = { _, a -> a.id }) { _, a -> AlbumCard(a, "lib", Modifier.fillMaxWidth()) { Nav.push(Route.Album(a.id, "lib", a.coverArt)) } }
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val st = remote("getPlaylists") { j -> j.getJSONObject("playlists").list("playlist").map(Playlist::of) }
    val list = st.value.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), Scroll.list("lib-playlists"), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
        items(list, key = { it.id }) { p ->
            val dim = Net.offline && Downloads.pinnedOf("playlist", p.id) == null
            Row(Modifier.fillMaxWidth().alpha(if (dim) 0.4f else 1f).press(!dim) { Nav.push(Route.Playlist(p.id, "lib", p.coverArt)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(p.coverArt, 300, Modifier.size(56.dp), 8.dp, "cover-lib-${p.id}")
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text("${p.songCount} songs · ${fmtLong(p.duration)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (list.isEmpty()) item { Empty(if (st.loading) "Loading…" else "No playlists yet.") }
    }
}

@Composable
private fun SongsTab() {
    val paged = remember { Paged("search3", mapOf("query" to "", "songCount" to "60", "artistCount" to "0", "albumCount" to "0"), 60, ::parseSongs3) }
    val state = Scroll.list("lib-songs")
    LaunchedEffect(paged) { paged.more() }
    LaunchedEffect(paged, Net.offline) { snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= paged.items.size - 12) paged.more() } }
    val ctx = Ctx("songs", "all", "Songs")
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
        itemsIndexed(paged.items, key = { i, s -> "$i-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = paged.items.toList(), index = i, ctx = ctx) }
        if (paged.items.isEmpty()) item { Empty(if (Net.offline) "Nothing cached yet." else "Loading…") }
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
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(artists, key = { it.id }) { a -> ArtistChip(a) { Nav.push(Route.Artist(a.id, "fav")) } } }
        }
        if (albums.isNotEmpty()) item {
            SectionTitle("Albums")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(albums, key = { it.id }) { a -> AlbumCard(a, "fav") { Nav.push(Route.Album(a.id, "fav", a.coverArt)) } } }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle("Songs") { TextButton({ Audio.shuffleAll(songs, ctx) }) { Text("Shuffle", color = Accent) } } }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = songs, index = i, ctx = ctx) }
        }
        if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) item { Empty(if (st.loading) "Loading…" else "Tap the heart on anything to find it here.") }
    }
}
