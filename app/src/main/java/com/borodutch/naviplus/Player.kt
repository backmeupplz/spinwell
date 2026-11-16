package com.borodutch.naviplus

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
    val cur = Audio.q.current ?: return
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
    LaunchedEffect(Audio.playing, cur.key) {
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
        val cs = minOf(W - px(56.dp), H * 0.40f)
        val coverTop1 = px(statusTop) + px(64.dp)
        val titleY1 = coverTop1 + cs + px(22.dp)
        val seekY1 = titleY1 + px(96.dp)
        val controlsY1 = seekY1 + px(64.dp)
        val rowY1 = controlsY1 + px(96.dp)
        val sidePad = px(28.dp)
        val miniMargin = px(8.dp)

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
                    val r = lerp(px(14.dp), 0f, exp.value)
                    shape = RoundedCornerShape(topStart = r, topEnd = r, bottomStart = lerp(px(14.dp), 0f, exp.value), bottomEnd = lerp(px(14.dp), 0f, exp.value)); clip = true
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
                    .offset { IntOffset((lerp(px(10.dp), (W - cs) / 2f, exp.value) + coverDx.value * exp.value + miniDx * 0.4f * (1 - exp.value)).roundToInt(), lerp(px(8.dp), coverTop1, exp.value).roundToInt()) }
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
                    modifier = Modifier.scaledBox({ lerp(W - px(70.dp) - px(112.dp), W - 2 * sidePad - px(52.dp), exp.value) }, { lerp(0.625f, 1f, exp.value) }),
                )
                Text(
                    song.artist, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.scaledBox({ lerp(W - px(70.dp) - px(112.dp), W - 2 * sidePad - px(52.dp), exp.value) }, { lerp(0.8125f, 1f, exp.value) })
                        .clickable(enabled = fullShown && song.artistId.isNotEmpty()) { collapse(); Nav.push(Route.Artist(song.artistId, "player")) },
                )
            }

            // play / pause: a small button in the mini row that becomes the big one
            Box(
                Modifier
                    .offset { IntOffset(lerp(W - 2 * miniMargin - px(56.dp), W / 2f - px(36.dp), exp.value).roundToInt(), lerp(px(8.dp), controlsY1 - px(36.dp), exp.value).roundToInt()) }
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

            // thin progress along the mini's bottom edge
            Box(Modifier.align(Alignment.TopStart).offset { IntOffset(0, (miniHpx - px(3.dp)).roundToInt()) }.fillMaxWidth().height(3.dp).drawBehind {
                drawRect(Accent, size = Size(size.width * (pos.toFloat() / durMs).coerceIn(0f, 1f), size.height), alpha = (1f - exp.value * 4f).coerceIn(0f, 1f))
            })

            if (fullShown) Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((exp.value - 0.55f) * 2.4f).coerceIn(0f, 1f) }) {
                // top bar
                Row(Modifier.padding(top = statusTop + 8.dp, start = 8.dp, end = 8.dp).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Down, "Minimize player") { collapse() }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", fontSize = 10.sp, color = Muted, letterSpacing = 1.sp)
                        Text(Audio.q.ctx.name.ifBlank { song.album }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.size(48.dp))
                }
                // favorite beside the title
                Heart(Fav.on(song.id, song.starred), Modifier.offset { IntOffset((W - sidePad - px(48.dp)).roundToInt(), (titleY1 - px(8.dp)).roundToInt()) }) { Fav.toggle(song.id, song.starred) }

                // seek bar + times
                Column(Modifier.offset { IntOffset(sidePad.roundToInt(), seekY1.roundToInt()) }.width(with(d) { (W - 2 * sidePad).toDp() })) {
                    SeekBar({ pos }, { durMs }, { ms -> pos = ms; Audio.seekTo(ms) })
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtTime((pos / 1000).toInt()), style = MaterialTheme.typography.labelSmall, color = Muted)
                        Spacer(Modifier.weight(1f))
                        Text("-" + fmtTime(((durMs - pos) / 1000).toInt()), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
                // transport (the big play button is the element above, in the middle)
                Row(
                    Modifier.offset { IntOffset(0, (controlsY1 - px(32.dp)).roundToInt()) }.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconBtn(Ic.Shuffle, "Shuffle", tint = if (Audio.q.shuffle) Accent else Color.White.copy(alpha = 0.75f)) { Audio.toggleShuffle() }
                    IconBtn(Ic.Prev, "Previous", size = 56.dp, glyph = 36.dp) { Audio.prev() }
                    Spacer(Modifier.size(72.dp))
                    IconBtn(Ic.Next, "Next", size = 56.dp, glyph = 36.dp) { Audio.next() }
                    IconBtn(if (Audio.q.repeat == 2) Ic.RepeatOne else Ic.Repeat, "Repeat", tint = if (Audio.q.repeat > 0) Accent else Color.White.copy(alpha = 0.75f)) { Audio.cycleRepeat() }
                }
                // quality, lyrics, up next, output
                Row(
                    Modifier.offset { IntOffset(0, rowY1.roundToInt()) }.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.weight(1f)) { Badge(if (Downloads.has(song.id)) qualityOf(song).ifBlank { "Downloaded" } else qualityOf(song)) }
                    IconBtn(Ic.Lyrics, "Lyrics") { showLyrics = true }
                    IconBtn(Ic.Queue, "Up next") { showQueue = true }
                    IconBtn(Ic.Output, "Output") {
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
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / w * dur()).toLong().coerceIn(0, dur())); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / w).coerceIn(0f, 1f); haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragEnd = { drag?.let { f -> onSeek((f * dur()).toLong()) }; drag = null; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    onDragCancel = { drag = null },
                ) { change, _ -> change.consume(); drag = (change.position.x / w).coerceIn(0f, 1f) }
            },
    ) {
        w = size.width
        val cy = size.height / 2
        val frac = drag ?: (pos().toFloat() / dur()).coerceIn(0f, 1f)
        val th = 4.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(Color.White, Offset(0f, cy - th / 2), Size(size.width * frac, th), CornerRadius(th / 2))
        drawCircle(Color.White, if (drag != null) 9.dp.toPx() else 6.dp.toPx(), Offset(size.width * frac, cy))
    }
}
