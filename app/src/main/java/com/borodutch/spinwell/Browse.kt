package com.borodutch.spinwell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun parseAlbums(j: JSONObject): List<Album> = j.optJSONObject("albumList2")?.list("album")?.map(Album::of).orEmpty()

private val shelves = listOf(
    "recent" to "Recently played", "newest" to "Recently added", "frequent" to "Most played", "starred" to "Favorite albums", "random" to "Rediscover",
)

fun shuffleAllSongs() = Store.scope.launch {
    val songs = (if (Net.offline) null else withContext(Dispatchers.IO) {
        runCatching { Api.call("getRandomSongs", mapOf("size" to "200")).optJSONObject("randomSongs")?.list("song")?.map(Song::of) }.getOrNull()
    }) ?: Downloads.done.values.map { it.song }
    if (songs.isNotEmpty()) Audio.shuffleAll(songs, Ctx("shuffle", "all", "Shuffle all"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val states = shelves.map { (type, _) -> remote("getAlbumList2", mapOf("type" to type, "size" to if (type == "random") "20" else "20"), ::parseAlbums) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(states.any { it.loading }) { if (states.none { it.loading }) refreshing = false }
    val any = states.any { !it.value.isNullOrEmpty() }
    PullToRefreshBox(refreshing, { refreshing = true; states.forEach { it.refresh() }; Net.ping() }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), Scroll.list("home"), contentPadding = pagePadding(4.dp)) {
            item {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Spinwell", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconBtn(Ic.Settings, "Settings") { Nav.push(Route.Settings) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("Shuffle all", Ic.Shuffle, true, Modifier.weight(1f)) { shuffleAllSongs() }
                    Pill("Favorites", Ic.Heart, false, Modifier.weight(1f)) { Nav.libraryTab = 4; Nav.go(Route.Library) }
                }
            }
            if (!any && Net.offline) item { Empty("Offline. Open Downloads to play what you saved.") }
            states.forEachIndexed { i, st ->
                val list = st.value.orEmpty()
                if (list.isNotEmpty()) {
                    val (type, title) = shelves[i]
                    item(key = "shelf-$type") {
                        Column {
                            SectionTitle(title)
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(list, key = { it.id }) { a -> AlbumCard(a, "home-$type") { Nav.push(Route.Album(a.id, "home-$type", a.coverArt)) } }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

/** What an album page and a playlist page both show. */
class Coll(
    val kind: String, val id: String, val name: String, val artist: String, val artistId: String, val coverArt: String,
    val songs: List<Song>, val year: Int, val genre: String, val starred: Boolean, val owner: String, val duration: Int,
)

private fun collOf(a: Album) = Coll("album", a.id, a.name, a.artist, a.artistId, a.coverArt, a.songs, a.year, a.genre, a.starred, "", a.duration.takeIf { it > 0 } ?: a.songs.sumOf { it.duration })
private fun collOf(p: Playlist) = Coll("playlist", p.id, p.name, "", "", p.coverArt, p.songs, 0, "", false, p.owner, p.duration.takeIf { it > 0 } ?: p.songs.sumOf { it.duration })

@Composable
fun CollectionScreen(id: String, playlist: Boolean, share: String, routeCover: String) {
    val st = remote(if (playlist) "getPlaylist" else "getAlbum", mapOf("id" to id)) { j ->
        if (playlist) collOf(Playlist.of(j.getJSONObject("playlist"))) else collOf(Album.of(j.getJSONObject("album")))
    }
    val c = st.value
    val notes = if (!playlist) remote("getAlbumInfo2", mapOf("id" to id)) { j -> j.optJSONObject("albumInfo")?.optString("notes").orEmpty() }.value.orEmpty() else ""
    val cover = c?.coverArt?.takeIf { it.isNotEmpty() } ?: routeCover
    val dom by rememberDominant(cover)
    val tint by animateColorAsState(dom ?: Surface2, tween(500), label = "tint")
    val songs = c?.songs.orEmpty()
    val ctx = Ctx(if (playlist) "playlist" else "album", id, c?.name.orEmpty())
    LaunchedEffect(c?.songs, Net.offline) { if (c != null && playlist && !Net.offline) Downloads.sync("playlist", id, c.name, c.coverArt, c.songs) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.75f), Bg)))) {
                    Spacer(Modifier.height(LocalTop.current.coerceAtLeast(0.dp) + 56.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CoverArt(cover, 600, Modifier.widthIn(max = 280.dp).fillMaxWidth(0.7f).shadow(26.dp, RoundedCornerShape(12.dp), spotColor = Color.Black), 12.dp, "cover-$share-$id")
                    }
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(c?.name ?: " ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (c != null && c.artist.isNotBlank()) Text(c.artist, color = Accent, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable(enabled = c.artistId.isNotEmpty()) { Nav.push(Route.Artist(c.artistId, "album")) }.padding(vertical = 2.dp))
                        if (c != null) {
                            val meta = if (playlist) listOf(c.owner, "${songs.size} songs", fmtLong(c.duration))
                            else listOf(c.year.takeIf { it > 0 }?.toString(), c.genre.ifBlank { null }, "${songs.size} songs", fmtLong(c.duration)).filterNotNull()
                            Text(meta.filter { it.isNotBlank() }.joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodyMedium)
                            Badge(bestQuality(songs), Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Play", Ic.Play, true) { if (songs.isNotEmpty()) Audio.play(songs, 0, ctx) }
                    Pill("Shuffle", Ic.Shuffle, false) { if (songs.isNotEmpty()) Audio.shuffleAll(songs, ctx) }
                    Spacer(Modifier.weight(1f))
                    if (c != null) {
                        DownloadButton(c)
                        if (!playlist) Heart(Fav.on(id, c.starred)) { Fav.toggle(id, c.starred, "albumId") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconBtn(Ic.More, "More") { menu = true }
                            DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                                DropdownMenuItem({ Text("Play next") }, { menu = false; songs.asReversed().forEach { Audio.playNext(it) } })
                                DropdownMenuItem({ Text("Add to queue") }, { menu = false; songs.forEach { Audio.addToQueue(it) } })
                                if (c.artistId.isNotEmpty()) DropdownMenuItem({ Text("Go to artist") }, { menu = false; Nav.push(Route.Artist(c.artistId, "album")) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (c == null && st.error != null) item { Empty(if (Net.offline) "This isn't available offline." else "Couldn't load: ${st.error}") }
            val multi = !playlist && songs.map { it.disc }.distinct().size > 1
            songs.forEachIndexed { i, s ->
                if (multi && (i == 0 || songs[i - 1].disc != s.disc)) item(key = "disc-${s.disc}") {
                    Text("Disc ${s.disc}", color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
                item(key = "s-$i-${s.id}") {
                    if (playlist) SongRow(s, showCover = true, queue = songs, index = i, ctx = ctx)
                    else SongRow(s, number = if (s.track > 0) s.track else i + 1, queue = songs, index = i, ctx = ctx)
                }
            }
            if (notes.isNotBlank()) item {
                Text(stripHtml(notes), color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp))
            }
        }
        BackButton(Modifier.padding(top = LocalTop.current))
    }
}

fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()

@Composable
fun DownloadButton(c: Coll) {
    val pin = Downloads.pinnedOf(c.kind, c.id)
    val p = Downloads.progress(c.songs)
    val busy = pin != null && p < 1f && c.songs.any { s -> Downloads.queue.any { it.id == s.id } }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator({ p.coerceAtLeast(0.03f) }, Modifier.size(30.dp), Accent, strokeWidth = 2.5.dp)
        IconBtn(if (pin != null && p >= 1f) Ic.DownloadDone else Ic.Download, if (pin != null) "Remove download" else "Download", tint = if (pin != null) Accent else LocalContentColor.current, size = 48.dp, glyph = if (busy) 18.dp else 24.dp) {
            if (pin != null) Downloads.removeContainer(pin) else if (c.songs.isNotEmpty()) Downloads.add(c.songs, Pinned(c.kind, c.id, c.name, c.coverArt, c.songs.map { it.id }))
        }
    }
}

// ---------- artist ----------

@Composable
fun ArtistScreen(id: String, share: String) {
    val st = remote("getArtist", mapOf("id" to id)) { j -> Artist.of(j.getJSONObject("artist")) }
    val a = st.value
    val info = remote("getArtistInfo2", mapOf("id" to id)) { j ->
        val o = j.optJSONObject("artistInfo2")
        Triple(stripHtml(o?.optString("biography").orEmpty()), o?.list("similarArtist")?.map(Artist::of).orEmpty(), o?.optString("largeImageUrl").orEmpty())
    }.value
    val top = if (a != null) remote("getTopSongs", mapOf("artist" to a.name, "count" to "10")) { j -> j.optJSONObject("topSongs")?.list("song")?.map(Song::of).orEmpty() }.value.orEmpty() else emptyList()
    var allTop by remember { mutableStateOf(false) }
    var bioOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val albums = a?.albums.orEmpty()
    fun isSingle(x: Album) = x.releaseTypes.any { t -> t.lowercase().let { "single" in it || "\"ep\"" in it || it == "ep" } }
    val main = albums.filter { !isSingle(it) }.sortedByDescending { it.year }
    val singles = albums.filter(::isSingle).sortedByDescending { it.year }
    val img = a?.coverArt?.takeIf { it.isNotEmpty() } ?: albums.firstOrNull()?.coverArt
    val ctx = Ctx("artist", id, a?.name.orEmpty())

    suspend fun allSongs(): List<Song> = withContext(Dispatchers.IO) {
        albums.flatMap { al ->
            (Api.cached("getAlbum", mapOf("id" to al.id)) ?: runCatching { Api.call("getAlbum", mapOf("id" to al.id)) }.getOrNull())
                ?.optJSONObject("album")?.let { Album.of(it).songs }.orEmpty()
        }.filter { Audio.playable(it) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            item {
                Box(Modifier.fillMaxWidth()) {
                    CoverArt(img, 600, Modifier.fillMaxWidth(), 0.dp, "cover-$share-$id", placeholderIcon = false)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Bg), startY = 0f)))
                    Text(a?.name ?: " ", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(20.dp), maxLines = 2)
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Play", Ic.Play, true) { scope.launch { val l = if (top.isNotEmpty()) top else allSongs(); if (l.isNotEmpty()) Audio.play(l, 0, ctx) } }
                    Pill("Shuffle", Ic.Shuffle, false) { scope.launch { val l = allSongs(); if (l.isNotEmpty()) Audio.shuffleAll(l, ctx) } }
                    Spacer(Modifier.weight(1f))
                    if (a != null) Heart(Fav.on(id, a.starred)) { Fav.toggle(id, a.starred, "artistId") }
                }
            }
            if (top.isNotEmpty()) {
                item { SectionTitle("Top songs") }
                val shown = if (allTop) top else top.take(5)
                items(shown.size, key = { "top-${shown[it].id}" }) { i -> SongRow(shown[i], showCover = true, queue = top, index = i, ctx = ctx, showAlbum = false) }
                if (top.size > 5) item { TextButton({ allTop = !allTop }, Modifier.padding(start = 8.dp)) { Text(if (allTop) "Show less" else "Show more", color = Accent) } }
            }
            if (main.isNotEmpty()) item { AlbumGrid("Albums", main, "artist-$id") }
            if (singles.isNotEmpty()) item { AlbumGrid("Singles & EPs", singles, "artist-$id") }
            val similar = info?.second.orEmpty()
            if (similar.isNotEmpty()) item {
                SectionTitle("Similar artists")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(similar, key = { it.id }) { s -> ArtistChip(s) { Nav.push(Route.Artist(s.id, "similar")) } }
                }
            }
            val bio = info?.first.orEmpty()
            if (bio.isNotBlank()) item {
                Column(Modifier.padding(20.dp).clickable { bioOpen = !bioOpen }) {
                    Text("About", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(bio, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = if (bioOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (a == null && st.error != null) item { Empty(if (Net.offline) "This isn't available offline." else "Couldn't load: ${st.error}") }
        }
        BackButton(Modifier.padding(top = LocalTop.current))
    }
}

@Composable
fun AlbumGrid(title: String, albums: List<Album>, share: String) {
    Column {
        SectionTitle(title)
        albums.chunked(2).forEach { row ->
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { a -> AlbumCard(a, share, Modifier.weight(1f)) { Nav.push(Route.Album(a.id, share, a.coverArt)) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
