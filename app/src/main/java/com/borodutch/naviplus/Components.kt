package com.borodutch.naviplus

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Press-scale card behaviour: a subtle shrink while held, tap and long-press (with a haptic). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.press(enabled: Boolean = true, onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val haptic = LocalHapticFeedback.current
    return this.graphicsLayer { scaleX = sc; scaleY = sc }.combinedClickable(
        interactionSource = src, indication = null, enabled = enabled,
        onLongClick = onLong?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } },
        onClick = onClick,
    )
}

@Composable
fun Glyph(v: ImageVector, tint: Color = LocalContentColor.current, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Icon(v, null, modifier.size(size), tint)

@Composable
fun IconBtn(v: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 48.dp, glyph: Dp = 24.dp, onClick: () -> Unit) =
    Box(modifier.size(size).clip(CircleShape).clickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(v, desc, Modifier.size(glyph), tint)
    }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) =
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }

@Composable
fun Pill(text: String, icon: ImageVector? = null, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (filled) Accent else Surface2
    val fg = if (filled) Color(0xFF16112E) else MaterialTheme.colorScheme.onBackground
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).press(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), fg); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** the play/pause glyph morphing between a triangle and two bars */
@Composable
fun PlayPause(playing: Boolean, tint: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(if (playing) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "pp")
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        fun l(a: Float, b: Float) = (a + (b - a) * p) * s
        // left quad: play (8,5)(12,7.5)(12,16.5)(8,19) -> pause (6,5)(10,5)(10,19)(6,19)
        val left = Path().apply {
            moveTo(l(8f, 6f), l(5f, 5f)); lineTo(l(12f, 10f), l(7.5f, 5f)); lineTo(l(12f, 10f), l(16.5f, 19f)); lineTo(l(8f, 6f), l(19f, 19f)); close()
        }
        // right quad: play (12,7.5)(19,12)(19,12)(12,16.5) -> pause (14,5)(18,5)(18,19)(14,19)
        val right = Path().apply {
            moveTo(l(12f, 14f), l(7.5f, 5f)); lineTo(l(19f, 18f), l(12f, 5f)); lineTo(l(19f, 18f), l(12f, 19f)); lineTo(l(12f, 14f), l(16.5f, 19f)); close()
        }
        drawPath(left, tint); drawPath(right, tint)
    }
}

/** three bouncing bars for the song that's playing */
@Composable
fun EqBars(playing: Boolean, tint: Color = Accent, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    androidx.compose.foundation.Canvas(modifier.size(18.dp)) {
        val w = size.width / 5f
        listOf(a, b, c).forEachIndexed { i, v ->
            val h = size.height * if (playing) v else 0.3f
            drawRoundRect(tint, Offset(i * 2 * w, size.height - h), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
        }
    }
}

@Composable
fun Heart(on: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    val sc by animateFloatAsState(if (on) 1f else 0.9f, spring(0.35f, Spring.StiffnessMedium), label = "heart")
    val haptic = LocalHapticFeedback.current
    IconBtn(if (on) Ic.HeartFilled else Ic.Heart, if (on) "Remove from favorites" else "Add to favorites",
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }, if (on) Accent else tint, size) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
}

/** a thin download state next to a song: nothing, a progress ring while waiting/downloading, a check when saved */
@Composable
fun DlState(id: String, modifier: Modifier = Modifier) {
    when {
        Downloads.has(id) -> Icon(Ic.DownloadDone, "Downloaded", modifier.size(16.dp), Accent)
        Downloads.queue.any { it.id == id } -> {
            val s = Downloads.queue.firstOrNull { it.id == id }
            if (id == Downloads.currentId && s != null && s.size > 0)
                CircularProgressIndicator({ (Downloads.currentBytes.toFloat() / s.size).coerceIn(0f, 1f) }, modifier.size(14.dp), Accent, strokeWidth = 2.dp)
            else CircularProgressIndicator({ 0f }, modifier.size(14.dp), Muted, strokeWidth = 2.dp, trackColor = Muted.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun AlbumCard(a: Album, share: String, modifier: Modifier = Modifier.width(148.dp), onClick: () -> Unit) {
    val dim = Net.offline && !Downloads.done.values.any { it.song.albumId == a.id }
    Column(modifier.alpha(if (dim) 0.4f else 1f).press(!dim, onClick = onClick)) {
        CoverArt(a.coverArt, 300, Modifier.fillMaxWidth(), 10.dp, "cover-$share-${a.id}")
        Spacer(Modifier.height(8.dp))
        Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(a.artist, a.year.takeIf { it > 0 }?.toString()).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ArtistChip(a: Artist, onClick: () -> Unit) = Column(Modifier.width(110.dp).press(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
    CoverArt(a.coverArt, 300, Modifier.fillMaxWidth(), 100.dp)
    Spacer(Modifier.height(8.dp))
    Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SongRow(
    song: Song, modifier: Modifier = Modifier, number: Int? = null, showCover: Boolean = false, subtitle: String? = null,
    queue: List<Song>? = null, index: Int = 0, ctx: Ctx? = null, onClick: (() -> Unit)? = null, showAlbum: Boolean = true,
) {
    val playing = Audio.q.current?.song?.id == song.id
    val locked = Net.offline && !Downloads.has(song.id)
    var menu by remember { mutableStateOf(false) }
    val click = onClick ?: { if (queue != null && ctx != null) Audio.play(queue, index, ctx) else Audio.play(listOf(song), 0, Ctx("song", song.id, song.title)) }
    Row(
        modifier.fillMaxWidth().alpha(if (locked) 0.4f else 1f).press(!locked, onLong = { menu = true }, onClick = click).padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) { CoverArt(song.coverArt, 150, Modifier.size(48.dp), 6.dp); Spacer(Modifier.width(12.dp)) }
        else if (number != null) Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (playing) EqBars(Audio.playing) else Text(if (number > 0) number.toString() else "–", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (playing) Accent else MaterialTheme.colorScheme.onBackground, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
            val sub = subtitle ?: if (showCover) listOf(song.artist, if (showAlbum) song.album else "").filter { it.isNotBlank() }.joinToString(" · ") else song.featured
            if (sub.isNotBlank()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (showCover && playing) { EqBars(Audio.playing); Spacer(Modifier.width(8.dp)) }
        DlState(song.id, Modifier.padding(horizontal = 4.dp))
        Text(fmtTime(song.duration), color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        Box {
            IconBtn(Ic.More, "More", size = 40.dp, tint = Muted) { menu = true }
            SongMenu(song, menu) { menu = false }
        }
    }
}

@Composable
fun SongMenu(song: Song, show: Boolean, onDismiss: () -> Unit) {
    var info by remember { mutableStateOf(false) }
    val starred = Fav.on(song.id, song.starred)
    val dl = Downloads.has(song.id) || Downloads.queue.any { it.id == song.id }
    DropdownMenu(show, onDismiss, containerColor = Surface2) {
        @Composable fun item(t: String, f: () -> Unit) = DropdownMenuItem({ Text(t) }, onClick = { onDismiss(); f() })
        item("Play next") { Audio.playNext(song) }
        item("Add to queue") { Audio.addToQueue(song) }
        if (song.albumId.isNotEmpty()) item("Go to album") { Nav.push(Route.Album(song.albumId, "song", song.coverArt)) }
        if (song.artistId.isNotEmpty()) item("Go to artist") { Nav.push(Route.Artist(song.artistId, "song")) }
        item(if (starred) "Unfavorite" else "Favorite") { Fav.toggle(song.id, song.starred) }
        item(if (dl) "Remove download" else "Download") { if (dl) Downloads.removeSong(song.id) else Downloads.add(listOf(song)) }
        item("Song info") { info = true }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false }, containerColor = Surface2,
        confirmButton = { TextButton({ info = false }) { Text("Close") } },
        title = { Text(song.title, maxLines = 2) },
        text = {
            Column {
                val rows = listOf(
                    "Artist" to song.artist, "Album" to song.album, "Format" to qualityOf(song), "Bitrate" to song.bitRate.takeIf { it > 0 }?.let { "$it kbps" },
                    "Sample rate" to song.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" }, "Bit depth" to song.bitDepth.takeIf { it > 0 }?.toString(),
                    "Size" to song.size.takeIf { it > 0 }?.let { fmtBytes(it) }, "Path" to song.path, "Plays" to song.playCount.toString(),
                )
                rows.forEach { (k, v) -> if (!v.isNullOrBlank()) Row(Modifier.padding(vertical = 3.dp)) {
                    Text(k, color = Muted, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                } }
            }
        },
    )
}

/** an ⓘ that opens a short explanation */
@Composable
fun InfoButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconBtn(Ic.Info, "About $title", size = 40.dp, glyph = 20.dp, tint = Muted) { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, containerColor = Surface2,
        confirmButton = { TextButton({ open = false }) { Text("Got it") } },
        title = { Text(title) }, text = { Text(text, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)) },
    )
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    if (text.isNotBlank()) Text(
        text, modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Muted.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f), maxLines = 1,
    )
}

@Composable
fun Empty(text: String, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium) }

/** page padding that clears the status bar (+offline banner) and the mini player + tab bar */
@Composable
fun pagePadding(extraTop: Dp = 8.dp) = PaddingValues(top = LocalTop.current + extraTop, bottom = LocalBottom.current + 16.dp)

/** A–Z style rail: tap or drag to jump */
@Composable
fun LetterRail(letters: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var h by remember { mutableStateOf(1) }
    var last by remember { mutableStateOf("") }
    fun pick(y: Float) {
        val l = letters.getOrNull((y / h * letters.size).toInt().coerceIn(0, letters.size - 1)) ?: return
        if (l != last) { last = l; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(l) }
    }
    Column(
        modifier.width(24.dp).fillMaxHeight().pointerInput(letters) { detectDragGestures(onDragStart = { pick(it.y) }) { c, _ -> pick(c.position.y) } }
            .pointerInput(letters) { detectTapGestures { pick(it.y) } }
            .onSizeChangedPx { h = it.height.coerceAtLeast(1) },
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally,
    ) { letters.forEach { Text(it, fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Bold) } }
}

fun Modifier.onSizeChangedPx(f: (androidx.compose.ui.unit.IntSize) -> Unit) = this.onSizeChanged(f)

/** A list that grows a page at a time (cached first page, then the network). */
class Paged<T>(private val method: String, private val base: Map<String, String>, private val page: Int, private val parse: (org.json.JSONObject) -> List<T>) {
    val items = mutableStateListOf<T>()
    var done by mutableStateOf(false)
    var busy = false

    suspend fun more() {
        if (busy || done) return
        busy = true
        val offset = items.size
        val p = base + mapOf(if (method == "search3") "songOffset" to offset.toString() else "offset" to offset.toString())
        try {
            if (offset == 0) withContext(Dispatchers.IO) { Api.cached(method, p)?.let { j -> runCatching { parse(j) }.getOrNull() } }?.let { items.addAll(it) }
            val fresh = if (Net.offline) null else withContext(Dispatchers.IO) { runCatching { parse(Api.call(method, p)) }.getOrNull() }
            if (fresh != null) {
                if (offset == 0) { if (fresh != items.toList()) { items.clear(); items.addAll(fresh) } } else items.addAll(fresh)
                done = fresh.size < page
            } else if (offset > 0) {
                val c = withContext(Dispatchers.IO) { Api.cached(method, p)?.let { j -> runCatching { parse(j) }.getOrNull() } } ?: emptyList()
                items.addAll(c); done = c.size < page
            } else done = items.size < page
        } finally { busy = false }
    }
}

@Composable
fun BackButton(modifier: Modifier = Modifier) =
    Box(modifier.padding(start = 8.dp, top = 4.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable { Nav.pop() }, contentAlignment = Alignment.Center) {
        Icon(Ic.Back, "Back", Modifier.size(22.dp), Color.White)
    }

/** scroll positions of the tab screens, kept while you wander into an album and back */
object Scroll {
    private val m = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun list(k: String) = m.getOrPut(k) { androidx.compose.foundation.lazy.LazyListState() }
}
