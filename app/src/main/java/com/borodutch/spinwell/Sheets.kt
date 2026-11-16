package com.borodutch.spinwell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
private fun SheetFrame(tint: Color, title: String, onClose: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val d = LocalDensity.current
    val top = with(d) { WindowInsets.statusBars.getTop(d).toDp() }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f).compositeOver2(tint), Bg, Bg)))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.padding(top = top + 8.dp, start = 20.dp, end = 8.dp).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            trailing?.invoke(this)
            IconBtn(Ic.Down, "Close") { onClose() }
        }
        content()
    }
}

private fun Color.compositeOver2(bg: Color): Color { val a = alpha; return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a)) }

/** Up next, Spotify style: Now playing / Next in queue / Next from <context>. Drag handle reorders, swipe removes, tap jumps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(tint: Color, onClose: () -> Unit) {
    val q = Audio.q
    val d = LocalDensity.current
    val run = q.nextRun
    val restStart = q.index + 1 + run.size
    val bottom = with(d) { WindowInsets.navigationBars.getBottom(d).toDp() } + 24.dp

    SheetFrame(tint, "Up next", onClose) {
        LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
            item(key = "h-now") { Label("Now playing") }
            q.current?.let { c -> item(key = "now-${c.key}") { QueueRow(c, q.index, handle = false, current = true) } }
            if (run.isNotEmpty()) {
                item(key = "h-next") {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Label("Next in queue") }
                        TextButton({ Audio.clearNext() }) { Text("Clear", color = Accent) }
                    }
                }
                itemsIndexed(run, key = { _, it -> it.key }) { i, it -> QueueRow(it, q.index + 1 + i) }
            }
            item(key = "h-ctx") {
                Label(if (q.ctx.name.isNotBlank()) "Next from ${q.ctx.name}" + if (q.shuffle) " · shuffled" else "" else "Next up")
            }
            val rest = q.items.drop(restStart)
            if (rest.isEmpty()) item(key = "empty") { Empty("Nothing else is queued.") }
            itemsIndexed(rest, key = { _, it -> it.key }) { i, it -> QueueRow(it, restStart + i) }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.QueueRow(item: QItem, index: Int, handle: Boolean = true, current: Boolean = false) {
    var dragging by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    val index2 by rememberUpdatedState(index)
    val song = item.song
    val dim = Net.offline && !Downloads.has(song.id)
    val body: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(if (dragging) Surface2 else Bg)
                .clickable(enabled = !current && !dim) { Audio.jump(index2) }.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(song.coverArt, 150, Modifier.size(46.dp), 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) Accent else Color.Unspecified, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (current) { EqBars(Audio.playing); Spacer(Modifier.width(16.dp)) }
            if (handle) Box(
                Modifier.size(48.dp).pointerInput(item.key) {
                    detectDragGestures(
                        onDragStart = { dragging = true; offset = 0f },
                        onDragEnd = { dragging = false; offset = 0f },
                        onDragCancel = { dragging = false; offset = 0f },
                    ) { change, amount ->
                        change.consume()
                        offset += amount.y
                        val cur = Audio.q.items.indexOfFirst { it.key == item.key }
                        if (offset > rowPx * 0.55f && cur < Audio.q.items.lastIndex) { Audio.move(cur, cur + 1); offset -= rowPx }
                        else if (offset < -rowPx * 0.55f && cur - 1 > Audio.q.index) { Audio.move(cur, cur - 1); offset += rowPx }
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Glyph(Ic.Drag, Muted) }
        }
    }
    if (!handle) { Box(Modifier) { body() }; return }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) { Audio.removeAt(Audio.q.items.indexOfFirst { it.key == item.key }); true } else false
    })
    SwipeToDismissBox(
        state, modifier = if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = offset } else Modifier.animateItem(),
        enableDismissFromStartToEnd = false,
        backgroundContent = { Box(Modifier.fillMaxSize().background(Color(0xFF7A1F2B)).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) { Glyph(Ic.Delete, Color.White) } },
    ) { body() }
}

// ---------- lyrics ----------

class Lyrics(val synced: Boolean, val lines: List<Pair<Long, String>>)

private fun parseLyrics(j: JSONObject): Lyrics? {
    val all = j.optJSONObject("lyricsList")?.list("structuredLyrics").orEmpty()
    val l = all.firstOrNull { it.optBoolean("synced") } ?: all.firstOrNull() ?: return null
    val lines = l.list("line").map { it.optLong("start", 0) to it.optString("value") }
    return if (lines.isEmpty()) null else Lyrics(l.optBoolean("synced"), lines)
}

@Composable
fun LyricsSheet(song: Song, tint: Color, pos: () -> Long, onClose: () -> Unit) {
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var loaded by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id, Store.ext.value) {
        val p = mapOf("id" to song.id)
        lyrics = withContext(Dispatchers.IO) { Api.cached("getLyricsBySongId", p)?.let(::parseLyrics) }
        // the structured call only on servers that advertise songLyrics; others get the plain legacy getLyrics
        if (!Net.offline && Store.ext.value.isNotEmpty()) withContext(Dispatchers.IO) {
            runCatching {
                if ("songLyrics" in Store.ext.value) parseLyrics(Api.call("getLyricsBySongId", p))
                else Api.call("getLyrics", mapOf("artist" to song.artist, "title" to song.title)).optJSONObject("lyrics")?.optString("value").orEmpty()
                    .lines().takeIf { it.any(String::isNotBlank) }?.let { ls -> Lyrics(false, ls.map { 0L to it }) }
            }.getOrNull()
        }?.let { lyrics = it }
        loaded = true
    }
    val state = rememberLazyListState()
    val l = lyrics
    var now by remember { mutableLongStateOf(pos()) }
    LaunchedEffect(song.id) { while (true) { now = pos(); kotlinx.coroutines.delay(150) } }
    val active = if (l != null && l.synced) l.lines.indexOfLast { it.first <= now + 150 }.coerceAtLeast(0) else -1
    val d = LocalDensity.current
    LaunchedEffect(active) { if (active >= 0) state.animateScrollToItem(active, -with(d) { 220.dp.roundToPx() }) }
    SheetFrame(tint, "Lyrics", onClose) {
        when {
            l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (loaded) "No lyrics for this song." else "Looking for lyrics…", color = Muted) }
            else -> LazyColumn(state = state, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 320.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(l.lines) { i, line ->
                    val on = !l.synced || i == active
                    val a by animateFloatAsState(if (on) 1f else 0.38f, tween(350), label = "la")
                    val s by animateFloatAsState(if (on) 1f else 0.86f, tween(350), label = "ls")
                    Text(
                        line.second.ifBlank { "♪" }, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = a; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0.5f) }
                            .clickable(enabled = l.synced) { Audio.seekTo(line.first) }.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}
