package com.borodutch.spinwell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Covers: memory LRU (1/8 of the heap) over a disk cache, one file per id and size bucket (150/300/600/1200 px).
 * Ids carry a content hash, so a cached file is good forever. The dominant color comes from a 16x16 downsample.
 */
object Covers {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(k: String, b: Bitmap) = b.byteCount
    }
    private val colors = ConcurrentHashMap<String, Int>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pool = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + pool)
    private val inflight = HashMap<String, Deferred<Bitmap?>>()
    private val sizes = intArrayOf(150, 300, 600, 1200)

    fun bucket(px: Int) = sizes.firstOrNull { it >= px } ?: 1200
    private fun key(id: String, size: Int) = "${Store.owner}/$id@$size"
    private fun dir() = File(Store.app.cacheDir, "covers/${Store.owner}").apply { mkdirs() }
    /** an id is a server cover id, or an absolute image URL (artist photos from getArtistInfo2) */
    private fun isUrl(id: String) = id.startsWith("http")
    private fun fname(id: String, size: Int) = (if (isUrl(id)) "u" + sha256(id).take(24) else id) + "_$size"
    /** the server's own share/img URLs take a size; other hosts serve what they serve */
    private fun urlFor(id: String, size: Int, s: Session) =
        if (!isUrl(id)) s.cover(id, size) else if ("/share/img/" in id && '?' !in id) "$id?size=$size" else id
    fun peek(id: String, px: Int): Bitmap? = if (id.isEmpty()) null else mem.get(key(id, bucket(px)))
    /** any size already in memory, biggest first: something to show while the exact size loads */
    fun peekAny(id: String): Bitmap? = if (id.isEmpty()) null else sizes.reversed().firstNotNullOfOrNull { mem.get(key(id, it)) }
    fun color(id: String): Int? = colors[id]
    fun clearMemory() { mem.evictAll(); colors.clear(); missing.clear() }

    suspend fun load(id: String, px: Int): Bitmap? {
        val size = bucket(px)
        val k = key(id, size)
        mem.get(k)?.let { return it }
        if (k in missing) return null
        val d = synchronized(inflight) {
            inflight.getOrPut(k) {
                scope.async { fetch(id, size, k) }.also { it.invokeOnCompletion { synchronized(inflight) { inflight.remove(k) } } }
            }
        }
        return d.await()
    }

    /** the file for [id], downloading it unless offline */
    private fun fetch(id: String, size: Int, k: String): Bitmap? {
        val f = File(dir(), fname(id, size))
        var b = if (f.length() > 0) decode(f, size) else null
        if (b == null && !Net.offline) {
            val s = Store.session ?: return null
            val tmp = File(f.path + ".tmp")
            val c = URL(urlFor(id, size, s)).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8_000; c.readTimeout = 15_000
                if (c.responseCode == 200 && c.contentType?.startsWith("image") == true) {
                    c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    b = decode(tmp, size)
                    if (b != null) tmp.renameTo(f)
                } else if (c.responseCode in 400..499 || c.contentType?.startsWith("image") == false) missing += k
            } catch (_: Exception) { } finally { c.disconnect(); tmp.delete() }
        }
        if (b == null) { // offline or failed: any other size we have beats a blank
            for (o in sizes.reversed()) { val g = File(dir(), fname(id, o)); if (g.length() > 0) { b = decode(g, size); if (b != null) break } }
        }
        if (b != null) { mem.put(k, b); colors.getOrPut(id) { dominant(b) } }
        return b
    }

    /** decoded no bigger than about [size] px on the long side (server covers are already that size; artist photos from other hosts aren't) */
    private fun decode(f: File, size: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var n = 1
        while (maxOf(o.outWidth, o.outHeight) / (n * 2) >= size) n *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = n })
    }.getOrNull()

    /** average of the colorful mid-tone pixels of a 16x16 downsample (near-black and near-white are ignored) */
    fun dominant(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256); s.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (s !== b) s.recycle()
        var r = 0.0; var g = 0.0; var bl = 0.0; var w = 0.0
        var ar = 0.0; var ag = 0.0; var ab = 0.0
        for (p in px) {
            val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
            ar += pr; ag += pg; ab += pb
            val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
            if (mx < 36 || mn > 225) continue
            val wt = 0.3 + (mx - mn) / 255.0 * 2
            r += pr * wt; g += pg * wt; bl += pb * wt; w += wt
        }
        return if (w > 0) rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt()) else rgb((ar / 256).toInt(), (ag / 256).toInt(), (ab / 256).toInt())
    }
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** fetch sizes for offline use (downloads) */
    fun prefetch(id: String, vararg px: Int) { if (id.isNotEmpty()) px.forEach { runBlocking { load(id, it) } } }
}

val LocalCoverPlaceholder = Color(0xFF1C1C24)

/** The dominant color of a cover as Compose state (a neutral until the cover has been seen). */
@Composable
fun rememberDominant(id: String?): State<Color?> = produceState(id?.let { Covers.color(it) }?.let { Color(it) }, id) {
    if (id.isNullOrEmpty()) { value = null; return@produceState }
    Covers.color(id)?.let { value = Color(it); return@produceState }
    Covers.load(id, 150)
    value = Covers.color(id)?.let { Color(it) }
}

/**
 * A square cover at a fixed aspect, so lists never jump when art arrives. [px] is the size drawn;
 * [shareKey] ties it to the same cover on another screen for the shared-element transition.
 */
@Composable
fun CoverArt(id: String?, px: Int, modifier: Modifier = Modifier, radius: Dp = 8.dp, shareKey: String? = null, placeholderIcon: Boolean = true, behind: String? = null) {
    val cid = id.orEmpty()
    // [shown] fades in over [under] (the previous or a smaller version of the same art), so a sharper cover arriving never pops
    var shown by remember(cid, px) { mutableStateOf(Covers.peek(cid, px) ?: Covers.peekAny(cid) ?: behind?.let { Covers.peekAny(it) }) }
    var under by remember(cid, px) { mutableStateOf<Bitmap?>(null) }
    val fade = remember(cid, px) { Animatable(1f) }
    LaunchedEffect(cid, px, Net.offline) {
        if (cid.isEmpty() || Covers.peek(cid, px) != null && shown === Covers.peek(cid, px)) return@LaunchedEffect
        val nb = Covers.load(cid, px) ?: return@LaunchedEffect
        if (nb === shown) return@LaunchedEffect
        under = shown; shown = nb
        fade.snapTo(0f); fade.animateTo(1f, tween(if (under == null) 220 else 160)); under = null
    }
    val tint = Covers.color(cid)?.let { Color(it).copy(alpha = 0.35f).compositeOver(LocalCoverPlaceholder) } ?: LocalCoverPlaceholder
    Box(
        Modifier.sharedCover(shareKey).then(modifier).aspectRatio(1f).clip(RoundedCornerShape(radius)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        val image = remember(shown) { shown?.asImageBitmap() }
        val old = remember(under) { under?.asImageBitmap() }
        if (old != null) Image(old, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (image != null) Image(image, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop)
        else if (placeholderIcon) Icon(Ic.Note, null, tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.fillMaxSize(0.4f))
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a))
}
