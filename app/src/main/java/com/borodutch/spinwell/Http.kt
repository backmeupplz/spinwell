package com.borodutch.spinwell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

class ApiError(val code: Int, message: String) : IOException(message)
class HttpErr(val code: Int) : IOException("HTTP $code")

/** GET [url] and parse the OpenSubsonic envelope; "failed" becomes an [ApiError]. */
fun getJson(url: String, timeoutMs: Int = 15_000, onOpen: (HttpURLConnection) -> Unit = {}): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        onOpen(c) // lets a caller abort the request from another thread (disconnect)
        c.connectTimeout = minOf(timeoutMs, 6_000); c.readTimeout = timeoutMs
        if (c.responseCode >= 300) throw HttpErr(c.responseCode)
        val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val r = runCatching { JSONObject(body).getJSONObject("subsonic-response") }.getOrNull()
            ?: throw ApiError(0, "Not a Subsonic server")
        if (r.optString("status") == "failed") r.optJSONObject("error").let { throw ApiError(it?.optInt("code") ?: 0, it?.optString("message") ?: str(R.string.err_request_failed)) }
        return r
    } finally { c.disconnect() }
}

/** Continues [part] with the rest of [url] (Range from its size), starting over when the server ignores the range. */
fun resume(url: URL, part: File, onBytes: (Long) -> Unit) {
    val c = url.openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        val have = part.length()
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) { onBytes(have); return } // already complete
        if (code >= 300) throw HttpErr(code)
        val type = c.contentType.orEmpty()
        if (type.startsWith("text") || "json" in type || "xml" in type) throw HttpErr(404) // a Subsonic error body, not audio
        val append = have > 0 && code == 206
        var n = if (append) have else 0L
        onBytes(n)
        c.inputStream.use { i ->
            FileOutputStream(part, append).use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = i.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    n += r
                    onBytes(n)
                }
            }
        }
    } finally { c.disconnect() }
}

class LanServer(val url: String, val name: String)

/** Subsonic servers on this phone's network: an unauthenticated `ping` to every address of its /24 on the usual ports
 *  (Navidrome 4533, Subsonic/Airsonic 4040, gonic 4747). Navidrome doesn't announce itself, so there is nothing to listen for. */
suspend fun findServers(ctx: Context): List<LanServer> = coroutineScope {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    // the Wi-Fi/Ethernet network, not the default one: with Tailscale or another VPN up (or on cellular) that's elsewhere
    @Suppress("DEPRECATION") val lan = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.let { (it.hasTransport(TRANSPORT_WIFI) || it.hasTransport(TRANSPORT_ETHERNET)) && !it.hasTransport(TRANSPORT_VPN) } == true
    } ?: return@coroutineScope emptyList()
    val me = cm.getLinkProperties(lan)?.linkAddresses?.map { it.address }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return@coroutineScope emptyList()
    val net = me.address.take(3).joinToString(".") { (it.toInt() and 255).toString() }
    val io = Dispatchers.IO.limitedParallelism(128)
    (1..254).flatMap { i -> listOf(4533, 4040, 4747).map { "http://$net.$i:$it" } }
        .map { async(io) { probe(lan, it) } }.awaitAll().filterNotNull()
}

private fun probe(lan: Network, base: String): LanServer? = runCatching {
    val c = lan.openConnection(URL("$base/rest/ping?f=json")) as HttpURLConnection
    try {
        c.connectTimeout = 700; c.readTimeout = 1500
        val body = (if (c.responseCode < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        val r = JSONObject(body).getJSONObject("subsonic-response")
        val name = listOf(r.optString("type").replaceFirstChar { it.uppercase() }, r.optString("serverVersion").substringBefore(" ")).filter { it.isNotEmpty() }
        LanServer(base, name.joinToString(" ").ifEmpty { "Subsonic" })
    } finally { c.disconnect() }
}.getOrNull()
