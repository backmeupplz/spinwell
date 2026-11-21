package com.borodutch.spinwell

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** text that is laid out at its big size and drawn scaled down (so the mini player's title can grow into the full one) */
private fun Modifier.scaledBox(width: () -> Float, scale: () -> Float) = layout { m, _ ->
    val s = scale()
    val w = width()
    val p = m.measure(Constraints(maxWidth = (w / s).roundToInt().coerceAtLeast(0)))
    layout((p.width * s).roundToInt(), (p.height * s).roundToInt()) {
        p.placeWithLayer(0, 0) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
    }
}

@Composable
fun BoxScope.PlayerSheet(exp: Animatable<Float, AnimationVector1D>, barH: Dp, miniH: Dp, statusTop: Dp, navBottom: Dp) {
    // everything below reads these derived values, so queue edits (index, added items, persist) don't recompose the whole sheet
    val curState = remember { derivedStateOf { Audio.q.current } }
    val ctxName by remember { derivedStateOf { Audio.q.ctx.title } }
    val shuffleOn by remember { derivedStateOf { Audio.q.shuffle } }
    val repeatMode by remember { derivedStateOf { Audio.q.repeat } }
    val cur = curState.value ?: return
    val song = cur.song
    val scope = rememberCoroutineScope()
    val d = LocalDensity.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val dom by rememberDominant(song.coverArt)
    val domColor by animateColorAsState(dom ?: Surface2, tween(700), label = "dom")
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val fullShown by remember { derivedStateOf { exp.value > 0.01f } }
    val twoLines by remember { derivedStateOf { exp.value > 0.5f } }

    var pos by remember { mutableLongStateOf(Audio.position) }
    LaunchedEffect(Audio.playing, cur.key, Audio.silent) { // swapped for the silence: the bar goes back to 0
        while (true) { pos = Audio.position; if (!Audio.playing) break; delay(200) }
    }
    val durMs = (Audio.durationMs.takeIf { it > 0 } ?: (song.duration * 1000L)).coerceAtLeast(1)
    val coverDx = remember { Animatable(0f) }
    val pauseScale by animateFloatAsState(if (Audio.playing) 1f else 0.86f, spring(0.55f, 320f), label = "pause")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        fun px(x: Dp) = with(d) { x.toPx() }
        val miniHpx = px(miniH)
        val miniTop = H - px(barH) - miniHpx - px(8.dp)
        // laid out from the bottom up so a small phone (5.4") never clips; the cover takes what's left under the top bar
        // a tablet keeps the controls in a column of at most 680 dp (560 in landscape): centered in portrait, on the right half in landscape beside the cover
        val tablet = minOf(W, H) >= px(600.dp)
        val land = tablet && W > H
        val cw = if (!tablet) W else if (land) minOf(W / 2f - px(48.dp), px(560.dp)) else minOf(W, px(680.dp))
        val cx0 = if (!tablet) 0f else if (land) W / 2f + (W / 2f - cw) / 2f else (W - cw) / 2f
        val padW = cw - px(56.dp)
        val rowY1 = if (land) (H + px(statusTop)) / 2f + px(104.dp) else H - px(navBottom) - px(64.dp)
        val controlsY1 = rowY1 - px(60.dp)
        val seekY1 = controlsY1 - px(100.dp)
        val titleY1 = seekY1 - px(96.dp)
        val top0 = px(statusTop) + px(60.dp)
        val avail = titleY1 - px(18.dp) - top0
        val cs = (if (land) minOf(W / 2f - px(64.dp), H - top0 - px(32.dp)) else minOf(cw - px(56.dp), avail)).coerceAtLeast(px(120.dp))
        val coverTop1 = if (land) top0 + (H - top0 - px(24.dp) - cs) / 2f else top0 + (avail - cs) / 2f
        val coverX1 = if (land) (W / 2f - cs) / 2f else (W - cs) / 2f
        val sidePad = cx0 + px(28.dp)
        val miniMargin = px(8.dp)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

        fun expand() = scope.launch { exp.animateTo(1f, spring(0.85f, Spring.StiffnessMediumLow)) }
        fun collapse() = scope.launch { exp.animateTo(0f, spring(0.9f, Spring.StiffnessMedium)) }
        val drag = rememberDraggableState { delta -> scope.launch { exp.snapTo((exp.value - delta / miniTop).coerceIn(0f, 1f)) } }

        Box(
            Modifier
                .offset { IntOffset(lerp(miniMargin, 0f, exp.value).roundToInt(), lerp(miniTop, 0f, exp.value).roundToInt()) }
                .layout { m, _ ->
                    val e = exp.value
                    val w = lerp(W - 2 * miniMargin, W, e).roundToInt(); val h = lerp(miniHpx, H, e).roundToInt()
                    val p = m.measure(Constraints.fixed(w, h))
                    layout(w, h) { p.place(0, 0) }
                }
                .graphicsLayer {
                    val r = lerp(px(14.dp), 0f, exp.value).coerceAtLeast(0f) // the spring can overshoot past 1
                    shape = RoundedCornerShape(r); clip = true
                }
                .draggable(drag, Orientation.Vertical, onDragStopped = { v ->
                    val to = if (v < -700f) 1f else if (v > 700f) 0f else if (exp.value > 0.5f) 1f else 0f
                    exp.animateTo(to, spring(0.85f, Spring.StiffnessMediumLow))
                })
                .drawBehind {
                    val e = exp.value
                    val mini = domColor.copy(alpha = 0.30f).compositeOver(Color(0xFF16161C))
                    drawRect(mini, alpha = 1f - e)
                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f).compositeOver(domColor), Color(0xFF0B0B0E))), alpha = e)
                    // progress: a soft fill behind the whole mini, growing from the leading edge
                    val pw = size.width * (pos.toFloat() / durMs).coerceIn(0f, 1f)
                    drawRect(Color.White, Offset(if (rtl) size.width - pw else 0f, 0f), Size(pw, miniHpx), alpha = 0.10f * (1f - e * 4f).coerceIn(0f, 1f))
                },
        ) {
            // mini: tap to expand, swipe sideways to skip
            var miniDx by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxWidth().height(miniH)
                    .pointerInput(Unit) { detectTapGestures { if (exp.value < 0.5f) expand() } }
                    .draggable(rememberDraggableState { if (exp.value < 0.1f) miniDx += it }, Orientation.Horizontal, onDragStopped = {
                        if (exp.value < 0.1f) { if (miniDx < -px(70.dp)) Audio.next() else if (miniDx > px(70.dp)) Audio.prev(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        miniDx = 0f
                    }),
            )

            // cover
            Box(
                Modifier
                    .offset { IntOffset((lerp(px(10.dp), coverX1, exp.value) + coverDx.value * exp.value + miniDx * 0.4f * (1 - exp.value)).roundToInt(), lerp(px(8.dp), coverTop1, exp.value).roundToInt()) }
                    .layout { m, _ ->
                        val s = lerp(px(48.dp), cs, exp.value).roundToInt()
                        val p = m.measure(Constraints.fixed(s, s)); layout(s, s) { p.place(0, 0) }
                    }
                    .graphicsLayer {
                        val s = lerp(1f, pauseScale, exp.value); scaleX = s; scaleY = s
                        shadowElevation = exp.value * px(22.dp); shape = RoundedCornerShape(12.dp); clip = false
                        ambientShadowColor = Color.Black; spotShadowColor = Color.Black
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    val dx = coverDx.value
                                    if (exp.value > 0.9f && abs(dx) > W * 0.22f) {
                                        val dir = if (dx < 0) -1 else 1
                                        coverDx.animateTo(dir * W, tween(140))
                                        if (dir < 0) Audio.next() else Audio.prev()
                                        coverDx.snapTo(-dir * W * 0.6f)
                                    }
                                    coverDx.animateTo(0f, spring(0.8f, Spring.StiffnessMedium))
                                }
                            },
                            onDragCancel = { scope.launch { coverDx.animateTo(0f) } },
                        ) { change, dx -> if (exp.value > 0.9f) { change.consume(); scope.launch { coverDx.snapTo(coverDx.value + dx) } } }
                    },
            ) {
                AnimatedContent(song.coverArt, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) }, label = "cover") { id ->
                    CoverArt(id, 1200, Modifier.fillMaxSize(), 10.dp, placeholderIcon = true)
                }
            }

            // title + artist: one block that moves and grows from the mini row to under the cover
            Column(
                Modifier
                    .offset { IntOffset((lerp(px(70.dp), sidePad, exp.value) + coverDx.value * 0.3f * exp.value + miniDx * 0.4f * (1 - exp.value)).roundToInt(), lerp(px(10.dp), titleY1, exp.value).roundToInt()) },
            ) {
                Text(
                    song.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
                    maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.scaledBox({ lerp(W - px(70.dp) - px(112.dp), padW - px(52.dp), exp.value) }, { lerp(0.625f, 1f, exp.value) }),
                )
                val err = Audio.error
                Text(
                    // a song that failed: the mini row says so, the full player says why; stopped offline: both say the same
                    if (err == null) song.artist else if (twoLines || err == stringResource(R.string.err_no_downloaded_next)) err else stringResource(R.string.err_play_short),
                    style = if (err != null && twoLines) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    color = if (err == null) Muted else MaterialTheme.colorScheme.error,
                    maxLines = if (err != null && twoLines) 2 else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.scaledBox({ lerp(W - px(70.dp) - px(112.dp), padW - px(52.dp), exp.value) }, { lerp(0.8125f, 1f, exp.value) })
                        .clickable(enabled = fullShown && err == null && song.artistId.isNotEmpty()) { collapse(); Nav.push(Route.Artist(song.artistId, "player")) },
                )
            }

            // play / pause: a small button in the mini row that becomes the big one
            Box(
                Modifier
                    .offset { IntOffset(lerp(W - 2 * miniMargin - px(56.dp), cx0 + cw / 2f - px(36.dp), exp.value).roundToInt(), lerp(px(8.dp), controlsY1 - px(36.dp), exp.value).roundToInt()) }
                    .layout { m, _ ->
                        val s = lerp(px(48.dp), px(72.dp), exp.value).roundToInt()
                        val p = m.measure(Constraints.fixed(s, s)); layout(s, s) { p.place(0, 0) }
                    }
                    .graphicsLayer { shape = CircleShape; clip = true }
                    .drawBehind { drawCircle(Color.White, alpha = exp.value) }
                    .pointerInput(Unit) { detectTapGestures { Audio.toggle() } },
                contentAlignment = Alignment.Center,
            ) {
                val tint by remember { derivedStateOf { if (exp.value > 0.5f) Color.Black else Color.White } }
                PlayPause(Audio.playing, tint, 30.dp)
            }

            if (fullShown) Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((exp.value - 0.55f) * 2.4f).coerceIn(0f, 1f) }) {
                // top bar
                Row(Modifier.padding(top = statusTop + 8.dp, start = 8.dp, end = 8.dp).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Down, stringResource(R.string.a11y_minimize_player)) { collapse() }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.label_playing_from).uppercase(java.util.Locale.getDefault()), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Muted, letterSpacing = caps(1f))
                        Text(ctxName.ifBlank { song.album }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconBtn(Ic.More, stringResource(R.string.a11y_more)) { menu = true }
                        SongMenu(song, menu, onNavigate = { collapse() }) { menu = false }
                    }
                }
                // favorite beside the title
                Heart(Fav.on(song.id, song.starred), Modifier.offset { IntOffset((cx0 + cw - px(28.dp) - px(48.dp)).roundToInt(), (titleY1 - px(8.dp)).roundToInt()) }) { Fav.toggle(song.id, song.starred) }

                // seek bar + times
                Column(Modifier.offset { IntOffset(sidePad.roundToInt(), seekY1.roundToInt()) }.width(with(d) { padW.toDp() })) {
                    SeekBar({ pos }, { durMs }, { ms -> Audio.seekTo(ms); pos = Audio.position })
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtTime((pos / 1000).toInt()), style = MaterialTheme.typography.labelSmall, color = Muted)
                        Spacer(Modifier.weight(1f))
                        Text("-" + fmtTime(((durMs - pos) / 1000).toInt()), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
                // transport (the big play button is the element above, in the middle)
                Row(
                    Modifier.offset { IntOffset(cx0.roundToInt(), (controlsY1 - px(32.dp)).roundToInt()) }.width(with(d) { cw.toDp() }).height(64.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ToggleBtn(Ic.Shuffle, stringResource(R.string.action_shuffle), shuffleOn) { Audio.toggleShuffle() }
                    IconBtn(Ic.Prev, stringResource(R.string.a11y_previous), size = 56.dp, glyph = 36.dp) { Audio.prev() }
                    Spacer(Modifier.size(72.dp))
                    IconBtn(Ic.Next, stringResource(R.string.a11y_next), size = 56.dp, glyph = 36.dp) { Audio.next() }
                    ToggleBtn(if (repeatMode == 2) Ic.RepeatOne else Ic.Repeat, stringResource(when (repeatMode) { 1 -> R.string.a11y_repeat_all; 2 -> R.string.a11y_repeat_one; else -> R.string.a11y_repeat_off }), repeatMode > 0) { Audio.cycleRepeat() }
                }
                // quality, lyrics, up next, output
                Row(
                    Modifier.offset { IntOffset(cx0.roundToInt(), rowY1.roundToInt()) }.width(with(d) { cw.toDp() }).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.weight(1f)) { Badge(if (Downloads.has(song.id)) qualityOf(song).ifBlank { stringResource(R.string.status_downloaded) } else qualityOf(song)) }
                    IconBtn(Ic.Lyrics, stringResource(R.string.title_lyrics)) { showLyrics = true }
                    IconBtn(Ic.Queue, stringResource(R.string.title_up_next)) { showQueue = true }
                    IconBtn(Ic.Output, stringResource(R.string.a11y_output)) {
                        val i = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    }
                }
            }
        }

        BackHandler(showQueue) { showQueue = false }
        BackHandler(showLyrics && !showQueue) { showLyrics = false }
        AnimatedVisibility(showQueue, enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)), exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200))) {
            QueueSheet(domColor) { showQueue = false }
        }
        AnimatedVisibility(showLyrics, enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)), exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200))) {
            LyricsSheet(song, domColor, { pos }) { showLyrics = false }
        }
    }
}

@Composable
fun SeekBar(pos: () -> Long, dur: () -> Long, onSeek: (Long) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var drag by remember { mutableStateOf<Float?>(null) }
    var w by remember { mutableFloatStateOf(1f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun frac(x: Float) = (if (rtl) 1f - x / w else x / w).coerceIn(0f, 1f) // the bar fills from the right in RTL
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(rtl) { detectTapGestures { onSeek((frac(it.x) * dur()).toLong().coerceIn(0, dur())); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
            .pointerInput(rtl) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = frac(it.x); haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragEnd = { drag?.let { f -> onSeek((f * dur()).toLong()) }; drag = null; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    onDragCancel = { drag = null },
                ) { change, _ -> change.consume(); drag = frac(change.position.x) }
            },
    ) {
        w = size.width
        val cy = size.height / 2
        val f = drag ?: (pos().toFloat() / dur()).coerceIn(0f, 1f)
        val th = 4.dp.toPx()
        val x0 = if (rtl) size.width * (1f - f) else 0f
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(Color.White, Offset(x0, cy - th / 2), Size(size.width * f, th), CornerRadius(th / 2))
        drawCircle(Color.White, if (drag != null) 9.dp.toPx() else 6.dp.toPx(), Offset(if (rtl) size.width * (1f - f) else size.width * f, cy))
    }
}
