package com.borodutch.spinwell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
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
    "recent" to R.string.shelf_recently_played, "newest" to R.string.sort_recently_added, "frequent" to R.string.sort_most_played,
    "starred" to R.string.shelf_favorite_albums, "random" to R.string.shelf_rediscover,
)

fun shuffleAllSongs() = Store.scope.launch {
    val songs = (if (Net.offline) null else withContext(Dispatchers.IO) {
        runCatching { Api.call("getRandomSongs", mapOf("size" to "200")).optJSONObject("randomSongs")?.list("song")?.map(Song::of) }.getOrNull()
    }) ?: Downloads.done.values.map { it.song }
    if (songs.isNotEmpty()) Audio.shuffleAll(songs, Ctx("shuffle", "all"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val states = shelves.map { (type, _) -> remote("getAlbumList2", mapOf("type" to type, "size" to "20"), ::parseAlbums) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(states.any { it.loading }) { if (states.none { it.loading }) refreshing = false }
    val any = states.any { !it.value.isNullOrEmpty() }
    val offline = Net.offline
    val have = if (offline) albumsOf(Offline.songs, states.flatMap { it.value.orEmpty() }.associateBy { it.id }).take(20) else emptyList()
    PullToRefreshBox(refreshing, { refreshing = true; states.forEach { it.refresh() }; Net.ping() }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), Scroll.list("home"), contentPadding = pagePadding(2.dp)) {
            item {
                Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScreenTitle(stringResource(R.string.app_name), Modifier.weight(1f))
                    IconBtn(Ic.Settings, stringResource(R.string.title_settings)) { Nav.push(Route.Settings) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.action_shuffle_all), Ic.Shuffle, true, Modifier.weight(1f)) { shuffleAllSongs() }
                    Pill(stringResource(R.string.title_favorites), Ic.Heart, false, Modifier.weight(1f)) { Nav.libraryTab = 4; Nav.go(Route.Library) }
                }
            }
            if (!any && have.isEmpty() && offline) item { Empty(stringResource(R.string.empty_home_offline), icon = Ic.CloudOff) }
            if (have.isNotEmpty()) item(key = "shelf-downloaded") { Shelf(stringResource(R.string.title_downloaded_albums), have, "home-dl") }
            states.forEachIndexed { i, st ->
                val list = st.value.orEmpty()
                if (list.isNotEmpty()) {
                    val (type, title) = shelves[i]
                    item(key = "shelf-$type") { Shelf(stringResource(title), list, "home-$type") }
                }
            }
        }
    }
}

@Composable
private fun Shelf(title: String, albums: List<Album>, share: String) = Column {
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(albums, key = { it.id }) { a -> AlbumCard(a, share) { Nav.push(Route.Album(a.id, share, a.coverArt)) } }
    }
    Spacer(Modifier.height(4.dp))
}

/** What an album page and a playlist page both show. */
class Coll(
    val kind: String, val id: String, val name: String, val artist: String, val artistId: String, val coverArt: String,
    val songs: List<Song>, val year: Int, val genre: String, val starred: Boolean, val owner: String, val duration: Int,
)

private fun collOf(a: Album) = Coll("album", a.id, a.name, a.artist, a.artistId, a.coverArt, a.songs, a.year, a.genre, a.starred, "", a.duration.takeIf { it > 0 } ?: a.songs.sumOf { it.duration })
private fun collOf(p: Playlist) = Coll("playlist", p.id, p.name, "", "", p.coverArt, p.songs, 0, "", false, p.owner, p.duration.takeIf { it > 0 } ?: p.songs.sumOf { it.duration })

/** an album or playlist rebuilt from downloads.json, for pages that were never cached */
private fun localColl(id: String, playlist: Boolean): Coll? =
    if (playlist) Downloads.pinnedOf("playlist", id)?.let { p ->
        val ss = p.songIds.mapNotNull { Downloads.done[it]?.song }
        Coll("playlist", id, p.name, "", "", p.coverArt, ss, 0, "", false, "", ss.sumOf { it.duration })
    } else albumsOf(Offline.songs).firstOrNull { it.id == id }?.let(::collOf)

@Composable
fun CollectionScreen(id: String, playlist: Boolean, share: String, routeCover: String) {
    val st = remote(if (playlist) "getPlaylist" else "getAlbum", mapOf("id" to id)) { j ->
        if (playlist) collOf(Playlist.of(j.getJSONObject("playlist"))) else collOf(Album.of(j.getJSONObject("album")))
    }
    val c = st.value ?: if (Net.offline) localColl(id, playlist) else null
    val notes = if (!playlist) remote("getAlbumInfo2", mapOf("id" to id)) { j -> j.optJSONObject("albumInfo")?.optString("notes").orEmpty() }.value.orEmpty() else ""
    val cover = c?.coverArt?.takeIf { it.isNotEmpty() } ?: routeCover
    val dom by rememberDominant(cover)
    val tint by animateColorAsState(dom ?: Surface2, tween(500), label = "tint")
    val songs = c?.songs.orEmpty()
    val kind = if (playlist) "playlist" else "album"
    val ctx = Ctx(kind, id, c?.name.orEmpty())
    LaunchedEffect(c?.songs, Net.offline) { if (c != null && playlist && !Net.offline) Downloads.sync("playlist", id, c.name, c.coverArt, c.songs) }

    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.75f), Bg)))) {
                    Spacer(Modifier.height(LocalTop.current.coerceAtLeast(0.dp) + 44.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CoverArt(cover, 600, Modifier.size(208.dp).shadow(20.dp, RoundedCornerShape(10.dp), spotColor = Color.Black), 10.dp, "cover-$share-$id")
                    }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)) {
                        Text(c?.name ?: " ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (c != null && c.artist.isNotBlank()) Text(c.artist, color = Accent, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable(enabled = c.artistId.isNotEmpty()) { Nav.push(Route.Artist(c.artistId, "album")) }.padding(vertical = 1.dp))
                        if (c != null) {
                            val meta = if (playlist) listOf(c.owner, songsLabel(songs.size), fmtLong(c.duration))
                            else listOf(c.year.takeIf { it > 0 }?.toString(), c.genre.ifBlank { null }, songsLabel(songs.size), fmtLong(c.duration)).filterNotNull()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(meta.filter { it.isNotBlank() }.joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Badge(bestQuality(songs))
                            }
                        }
                    }
                }
            }
            item {
                PlayShuffle(songs, ctx) {
                    if (c != null) {
                        DownloadButton(c.kind, c.id, c.name, c.coverArt, c.songs)
                        if (!playlist) Heart(Fav.on(id, c.starred), size = 44.dp) { Fav.toggle(id, c.starred, "albumId") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconBtn(Ic.More, stringResource(R.string.a11y_more), size = 44.dp) { menu = true }
                            DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                                DropdownMenuItem({ Text(stringResource(R.string.menu_play_next)) }, { menu = false; songs.asReversed().forEach { Audio.playNext(it) } })
                                DropdownMenuItem({ Text(stringResource(R.string.menu_add_to_queue)) }, { menu = false; songs.forEach { Audio.addToQueue(it) } })
                                RepeatMenuItem(kind, id, songs, ctx) { menu = false }
                                if (c.artistId.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.menu_go_to_artist)) }, { menu = false; Nav.push(Route.Artist(c.artistId, "album")) })
                            }
                        }
                    }
                }
            }
            if (c == null && st.error != null) item { Empty(if (Net.offline) stringResource(R.string.err_not_available_offline) else stringResource(R.string.err_load_failed, st.error.orEmpty())) }
            val multi = !playlist && songs.map { it.disc }.distinct().size > 1
            songs.forEachIndexed { i, s ->
                if (multi && (i == 0 || songs[i - 1].disc != s.disc)) item(key = "disc-${s.disc}") {
                    Text(stringResource(R.string.label_disc, s.disc), color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                }
                item(key = "s-$i-${s.id}") {
                    if (playlist) SongRow(s, showCover = true, queue = songs, index = i, ctx = ctx)
                    else SongRow(s, number = if (s.track > 0) s.track else i + 1, queue = songs, index = i, ctx = ctx)
                }
            }
            if (notes.isNotBlank()) item {
                Text(stripHtml(notes), color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
        StatusScrim(Modifier.align(Alignment.TopCenter), LocalTop.current, visible = { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 300 })
        BackButton(Modifier.padding(top = LocalTop.current))
    }
}

/** "Repeat album" in a container's menu: plays it on repeat-all, or turns that off when it's the one repeating */
@Composable
fun RepeatMenuItem(kind: String, id: String, songs: List<Song>, ctx: Ctx, dismiss: () -> Unit) {
    val q = Audio.q
    val on = q.ctx.kind == kind && q.ctx.id == id && q.items.isNotEmpty() && q.repeat == 1
    val label = when (kind) {
        "playlist" -> if (on) R.string.menu_stop_repeating_playlist else R.string.menu_repeat_playlist
        "artist" -> if (on) R.string.menu_stop_repeating_artist else R.string.menu_repeat_artist
        else -> if (on) R.string.menu_stop_repeating_album else R.string.menu_repeat_album
    }
    DropdownMenuItem({ Text(stringResource(label), color = if (on) Accent else Color.Unspecified) }, {
        dismiss()
        if (on) Audio.setRepeat(0)
        else if (q.ctx.kind == kind && q.ctx.id == id && q.items.isNotEmpty()) Audio.setRepeat(1)
        else if (songs.isNotEmpty()) Audio.playRepeating(songs, ctx)
    })
}

fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()

/**
 * Download for an album, playlist or artist: arrow, ring while its songs arrive, check when all are saved.
 * Tapping it once saved (or downloading) offers Remove download. [load] fetches the songs when they aren't in hand (an artist's).
 */
@Composable
fun DownloadButton(kind: String, id: String, name: String, cover: String, songs: List<Song>, load: (suspend () -> List<Song>)? = null) {
    val pin = Downloads.pinnedOf(kind, id)
    val have = if (pin != null) pin.songIds.mapNotNull { Downloads.done[it]?.song ?: Downloads.queue.firstOrNull { q -> q.id == it } } else songs
    val p = Downloads.progress(have)
    val busy = pin != null && p < 1f && have.any { s -> Downloads.queue.any { it.id == s.id } }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator({ p.coerceAtLeast(0.03f) }, Modifier.size(28.dp), Accent, strokeWidth = 2.5.dp)
        IconBtn(if (pin != null && p >= 1f) Ic.DownloadDone else Ic.Download, stringResource(if (pin != null) R.string.a11y_downloaded_tap_remove else R.string.action_download), tint = if (pin != null) Accent else LocalContentColor.current, size = 44.dp, glyph = if (busy) 16.dp else 22.dp) {
            if (pin != null) menu = true
            else if (!Net.offline) scope.launch {
                val l = if (songs.isNotEmpty() || load == null) songs else load()
                if (l.isNotEmpty()) Downloads.add(l, Pinned(kind, id, name, cover, l.map { it.id }))
            }
        }
        DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
            val bytes = have.sumOf { Downloads.done[it.id]?.bytes ?: 0L }
            DropdownMenuItem({ Text(if (busy) stringResource(R.string.action_cancel_download) else stringResource(R.string.action_remove_download) + if (bytes > 0) " · ${fmtBytes(bytes)}" else "") }, { menu = false; pin?.let { Downloads.removeContainer(it) } })
        }
    }
}

// ---------- artist ----------

@Composable
fun ArtistScreen(id: String, share: String) {
    val st = remote("getArtist", mapOf("id" to id)) { j -> Artist.of(j.getJSONObject("artist")) }
    val cachedArtist = st.value
    val info = remote("getArtistInfo2", mapOf("id" to id)) { j ->
        val o = j.optJSONObject("artistInfo2")
        Triple(stripHtml(o?.optString("biography").orEmpty()), o?.list("similarArtist")?.map(Artist::of).orEmpty(), o?.optString("largeImageUrl").orEmpty())
    }.value
    val offline = Net.offline
    val top = if (cachedArtist != null) remote("getTopSongs", if ("topSongsByArtistId" in Store.ext.value) mapOf("id" to cachedArtist.id, "count" to "10") else mapOf("artist" to cachedArtist.name, "count" to "10")) { j -> j.optJSONObject("topSongs")?.list("song")?.map(Song::of).orEmpty() }.value.orEmpty() else emptyList()
    var allTop by remember { mutableStateOf(false) }
    var bioOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cachedAlbums = cachedArtist?.albums.orEmpty()
    // offline: what's downloaded of this artist (credited to them, or on their albums), from downloads.json + the cache
    val mine = if (offline) songsOfArtist(Offline.songs, id, cachedAlbums.map { it.id }.toSet()).sortedWith(compareByDescending<Song> { it.playCount }.thenBy { it.albumId }.thenBy { it.track }) else emptyList()
    val albums = if (offline) albumsOf(mine, cachedAlbums.associateBy { it.id }).sortedByDescending { it.year } else cachedAlbums
    val name = cachedArtist?.name ?: mine.firstOrNull { it.artistId == id }?.artist.orEmpty()
    val a = cachedArtist ?: if (offline && mine.isNotEmpty()) Artist(id, name, albums.size, "", false, albums) else null
    fun isSingle(x: Album) = x.releaseTypes.any { t -> t.lowercase().let { "single" in it || "\"ep\"" in it || it == "ep" } }
    val main = albums.filter { !isSingle(it) }.sortedByDescending { it.year }
    val singles = albums.filter(::isSingle).sortedByDescending { it.year }
    val fallback = cachedArtist?.coverArt?.takeIf { it.isNotEmpty() } ?: albums.firstOrNull()?.coverArt ?: mine.firstOrNull()?.coverArt
    val img = info?.third?.takeIf { it.startsWith("http") } ?: fallback // the server's artist photo, else the cover id
    val ctx = Ctx("artist", id, name)
    // the songs the hero's Play / Shuffle use, and the "Top songs" list
    val songsShown = if (offline) mine else top
    val shown = if (allTop) songsShown else songsShown.take(5)

    suspend fun allSongs(): List<Song> = if (offline) mine else withContext(Dispatchers.IO) { allArtistSongs(albums).filter { Audio.playable(it) } }

    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(240.dp).clipToBounds()) {
                    CoverArt(img, 1200, Modifier.fillMaxWidth().wrapContentHeight(BiasAlignment.Vertical(-0.4f), unbounded = true), 0.dp, "cover-$share-$id", placeholderIcon = false, behind = fallback)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Bg), startY = 0f)))
                    Text(name.ifBlank { " " }, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp), modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                PlayShuffle({ scope.launch { val l = if (!offline && top.isNotEmpty()) top else allSongs(); if (l.isNotEmpty()) Audio.play(l, 0, ctx) } },
                    { scope.launch { val l = allSongs(); if (l.isNotEmpty()) Audio.shuffleAll(l, ctx) } }) {
                    var menu by remember { mutableStateOf(false) }
                    if (a != null && !(offline && mine.isEmpty())) DownloadButton("artist", id, name, fallback.orEmpty(), emptyList(), load = { withContext(Dispatchers.IO) { allArtistSongs(albums) } })
                    if (a != null) Heart(Fav.on(id, a.starred), size = 44.dp) { Fav.toggle(id, a.starred, "artistId") }
                    Box {
                        IconBtn(Ic.More, stringResource(R.string.a11y_more), size = 44.dp) { menu = true }
                        DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                            var repeatSongs by remember { mutableStateOf(emptyList<Song>()) }
                            LaunchedEffect(menu) { if (menu) repeatSongs = allSongs() }
                            RepeatMenuItem("artist", id, repeatSongs, ctx) { menu = false }
                        }
                    }
                }
            }
            if (songsShown.isNotEmpty()) {
                item { SectionTitle(stringResource(if (offline) R.string.title_downloaded_songs else R.string.title_top_songs)) }
                items(shown.size, key = { "top-${shown[it].id}" }) { i -> SongRow(shown[i], showCover = true, queue = songsShown, index = i, ctx = ctx, showAlbum = false) }
                if (songsShown.size > 5) item { TextButton({ allTop = !allTop }, Modifier.padding(start = 8.dp)) { Text(stringResource(if (allTop) R.string.action_show_less else R.string.action_show_more), color = Accent) } }
            }
            if (main.isNotEmpty()) item { AlbumList(stringResource(if (offline) R.string.title_downloaded_albums else R.string.title_albums), main, "artist-$id") }
            if (singles.isNotEmpty()) item { AlbumList(stringResource(R.string.title_singles_eps), singles, "artist-$id") }
            val similar = if (offline) emptyList() else info?.second.orEmpty()
            if (similar.isNotEmpty()) item {
                SectionTitle(stringResource(R.string.title_similar_artists))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(similar, key = { it.id }) { s -> ArtistChip(s) { Nav.push(Route.Artist(s.id, "similar")) } }
                }
            }
            val bio = info?.first.orEmpty()
            if (bio.isNotBlank()) item {
                Column(Modifier.padding(16.dp).clickable { bioOpen = !bioOpen }) {
                    Text(stringResource(R.string.title_about), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(bio, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = if (bioOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (a == null && st.error != null) item { Empty(if (offline) stringResource(R.string.empty_artist_not_downloaded) else stringResource(R.string.err_load_failed, st.error.orEmpty())) }
        }
        StatusScrim(Modifier.align(Alignment.TopCenter), LocalTop.current, visible = { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 300 })
        BackButton(Modifier.padding(top = LocalTop.current))
    }
}

/** every song on [albums]: the cached album pages first, the network for the ones not seen yet */
fun allArtistSongs(albums: List<Album>): List<Song> = albums.flatMap { al ->
    (Api.cached("getAlbum", mapOf("id" to al.id)) ?: runCatching { Api.call("getAlbum", mapOf("id" to al.id)) }.getOrNull())
        ?.optJSONObject("album")?.let { Album.of(it).songs }.orEmpty()
}

@Composable
fun AlbumList(title: String, albums: List<Album>, share: String) {
    Column {
        SectionTitle(title)
        albums.forEach { AlbumRow(it, share, thumb = 44.dp) }
    }
}
