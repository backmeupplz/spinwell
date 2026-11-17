package com.borodutch.spinwell

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
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
