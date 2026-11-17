package com.borodutch.spinwell

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the Search tab remembers while you wander into an album and back: the query, the results, the recent searches. */
object SearchState {
    var query by mutableStateOf("")
    var found by mutableStateOf<Found?>(null) // the previous results stay on screen until the next ones arrive
    var loading by mutableStateOf(false)
    val recents = mutableStateListOf<String>()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        runCatching { JSONArray(Store.p.getString("recents", "[]")).let { a -> (0 until a.length()).forEach { recents += a.getString(it) } } }
    }
    private fun save() = Store.p.edit().putString("recents", JSONArray(recents.toList()).toString()).apply()
    fun remember(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recents.removeAll { it.equals(t, ignoreCase = true) }
        recents.add(0, t)
        while (recents.size > 10) recents.removeAt(recents.lastIndex)
        save()
    }
    fun forget(q: String) { recents.remove(q); save() }
    fun clear() { recents.clear(); save() }
}

/** search3, abortable: cancelling the coroutine disconnects the HTTP request instead of letting it finish unseen */
private suspend fun searchRemote(q: String): Found? {
    val conn = AtomicReference<HttpURLConnection?>()
    val gone = AtomicBoolean(false)
    val job = Store.scope.async(Dispatchers.IO) {
        runCatching {
            val p = mapOf("query" to q, "artistCount" to "10", "albumCount" to "20", "songCount" to "50")
            val o = Api.call("search3", p, onOpen = { conn.set(it); if (gone.get()) it.disconnect() }, aborted = { gone.get() }).getJSONObject("searchResult3")
            Found(o.list("artist").map(Artist::of), o.list("album").map(Album::of), o.list("song").map(Song::of))
        }.getOrNull()
    }
    try { return job.await() } catch (e: CancellationException) { gone.set(true); conn.get()?.disconnect(); job.cancel(); throw e }
}

@Composable
fun SearchScreen() {
    val s = SearchState
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { s.load(); focus.requestFocus() }
    // typing: wait 200 ms, cancel whatever was in flight, keep the old results showing until the new ones land
    LaunchedEffect(s.query, Net.offline) {
        val q = s.query.trim()
        if (q.isEmpty()) { s.found = null; s.loading = false; return@LaunchedEffect }
        s.loading = true
        try {
            val r = if (Net.offline) searchLocal(q, Offline.songs) else { delay(200); searchRemote(q) }
            if (r != null) s.found = r
            if (r != null && !r.empty) { delay(1_500); s.remember(q) }
        } finally { s.loading = false }
    }
    DisposableEffect(Unit) { onDispose { if (s.found?.empty == false) s.remember(s.query) } } // leaving for a result counts as a search
    val hideKeys = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput && available.y != 0f) focusManager.clearFocus(); return Offset.Zero }
        }
    }
    val q = s.query.trim()
    Column(Modifier.fillMaxSize().padding(top = LocalTop.current)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(44.dp).background(Surface1, RoundedCornerShape(50)).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Ic.Search, Muted, 20.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (s.query.isEmpty()) Text(stringResource(if (Net.offline) R.string.label_search_downloads else R.string.label_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    s.query, { s.query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { s.remember(s.query); focusManager.clearFocus() }),
                )
            }
            if (s.query.isNotEmpty()) IconBtn(Ic.Close, stringResource(R.string.action_clear), size = 40.dp, glyph = 20.dp, tint = Muted) { s.query = "" }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (s.loading && s.found != null) LinearProgressIndicator(Modifier.fillMaxWidth(), Accent, Surface2) }
        val f = s.found
        LazyColumn(Modifier.fillMaxSize().nestedScroll(hideKeys), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            if (q.isEmpty()) {
                if (s.recents.isEmpty()) item { Empty(stringResource(R.string.empty_search_start), icon = Ic.Search) }
                else {
                    item { SectionTitle(stringResource(R.string.title_recent_searches)) { TextButton({ s.clear() }) { Text(stringResource(R.string.action_clear), color = Accent) } } }
                    items(s.recents.size, key = { "r-${s.recents[it]}" }) { i ->
                        val r = s.recents[i]
                        Row(Modifier.fillMaxWidth().clickable { s.query = r }.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(Ic.History, Muted, 20.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(r, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            IconBtn(Ic.Close, stringResource(R.string.a11y_remove), size = 44.dp, glyph = 18.dp, tint = Muted) { s.forget(r) }
                        }
                    }
                }
            } else if (f != null) {
                results(f, q)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(f: Found, q: String) {
    if (f.empty) { item { Empty(stringResource(R.string.empty_nothing_found, q), icon = Ic.Search) }; return }
    val ctx = Ctx("search", q)
    topResult(q, f)?.let { top ->
        item(key = "top") {
            Column {
                SectionTitle(stringResource(R.string.title_top_result))
                when (top) {
                    is Artist -> ArtistRow(top, "search")
                    is Album -> AlbumRow(top, "search", "${stringResource(R.string.label_album)} · ${top.artist}")
                    is Song -> SongRow(top, showCover = true, queue = listOf(top), index = 0, ctx = Ctx("song", top.id, top.title))
                }
            }
        }
    }
    section("artists", R.string.title_artists, f.artists, 3) { ArtistRow(it, "search") }
    section("albums", R.string.title_albums, f.albums, 4) { AlbumRow(it, "search", listOfNotNull(stringResource(R.string.label_album), it.artist.ifBlank { null }, it.year.takeIf { y -> y > 0 }?.toString()).joinToString(" · ")) }
    if (f.songs.isNotEmpty()) {
        item(key = "songs-h") { SectionHeader("songs", R.string.title_songs, f.songs.size, 5) }
        item(key = "songs-ps") { PlayShuffle(f.songs, ctx) }
        item(key = "songs-list") {
            val open = Open.on("songs")
            Column { f.songs.take(if (open) f.songs.size else 5).forEachIndexed { i, s -> SongRow(s, showCover = true, queue = f.songs, index = i, ctx = ctx) } }
        }
    }
}

/** which sections of the results are expanded ("See all"); a plain holder so the lazy DSL can read it */
private object Open {
    private val set = mutableStateListOf<String>()
    fun on(k: String) = k in set
    fun toggle(k: String) { if (!set.remove(k)) set.add(k) }
}

@Composable
private fun SectionHeader(key: String, @StringRes title: Int, total: Int, limit: Int) =
    SectionTitle(stringResource(title)) { if (total > limit) TextButton({ Open.toggle(key) }) { Text(stringResource(if (Open.on(key)) R.string.action_show_less else R.string.action_see_all), color = Accent) } }

private fun <T> androidx.compose.foundation.lazy.LazyListScope.section(key: String, @StringRes title: Int, list: List<T>, limit: Int, row: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    item(key = "$key-h") { SectionHeader(key, title, list.size, limit) }
    item(key = "$key-list") { Column { list.take(if (Open.on(key)) list.size else limit).forEach { row(it) } } }
}
