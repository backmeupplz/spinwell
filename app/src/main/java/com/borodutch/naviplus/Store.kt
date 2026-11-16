package com.borodutch.naviplus

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.reflect.KProperty

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        if (Store.session != null) {
            Downloads.load()
            Audio.init(this)
            Audio.restore()
            Net.start(this)
        }
    }
}

/** A setting kept in prefs and readable from Compose (writes recompose whoever read it). */
class Pref<T>(private val key: String, private val def: T) {
    private var state: MutableState<T>? = null
    @Suppress("UNCHECKED_CAST")
    private fun st(): MutableState<T> = state ?: mutableStateOf(when (def) {
        is Boolean -> Store.p.getBoolean(key, def)
        is Int -> Store.p.getInt(key, def)
        else -> Store.p.getString(key, def as String)
    } as T).also { state = it }
    operator fun getValue(o: Any?, p: KProperty<*>): T = st().value
    operator fun setValue(o: Any?, p: KProperty<*>, v: T) {
        st().value = v
        Store.p.edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); else -> putString(key, v as String) } }.apply()
    }
}

object Settings {
    var wifiKbps by Pref("wifiKbps", 0)
    var mobileKbps by Pref("mobileKbps", 192)
    var dlKbps by Pref("dlKbps", 0)
    var dlMobile by Pref("dlMobile", false)
    var resume by Pref("resume", false)
    var offlineMode by Pref("offlineMode", false)
    var scrobble by Pref("scrobble", true)
    var librarySort by Pref("librarySort", "name")
    fun streamKbps() = if (Net.unmetered()) wifiKbps else mobileKbps
}

object Store {
    lateinit var app: Application
    lateinit var p: SharedPreferences
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var session by mutableStateOf<Session?>(null)
    val ext = mutableStateOf<Set<String>>(emptySet()) // OpenSubsonic extensions of this server
    var serverVersion by mutableStateOf("")

    fun init(a: Application) {
        app = a
        p = a.getSharedPreferences("navi", Context.MODE_PRIVATE)
        val s = p.getString("server", null)
        val t = p.getString("token", null)
        if (s != null && t != null) session = Session(s, p.getString("user", "")!!, t, p.getString("salt", "")!!)
    }

    /** the signed-in account's private directories */
    val owner get() = session?.owner ?: "none"
    fun dir(name: String) = File(app.filesDir, "$name/$owner").apply { mkdirs() }

    suspend fun signIn(serverIn: String, user: String, password: String) {
        val server = normalizeServer(serverIn)
        require(server.isNotEmpty() && user.isNotBlank()) { "Enter your server address, username and password." }
        val salt = (1..8).map { "0123456789abcdef".random() }.joinToString("")
        val s = Session(server, user.trim(), authToken(password, salt), salt)
        withContext(Dispatchers.IO) {
            try { getJson(s.url("ping"), 10_000) }
            catch (e: ApiError) { throw if (e.code == 40 || e.code == 41) Exception(e.message ?: "Wrong username or password.") else Exception("That doesn't look like a Subsonic server.") }
            catch (e: HttpErr) { throw Exception("That doesn't look like a Subsonic server (HTTP ${e.code}).") }
            catch (e: java.io.IOException) { throw Exception("Can't reach the server. Check the address and your connection.") }
        }
        p.edit().putString("server", s.server).putString("user", s.user).putString("token", s.token).putString("salt", s.salt).apply()
        session = s
        Net.reachable = true
        Downloads.load()
        Audio.init(app)
        Audio.restore()
        Net.start(app)
        loadExtensions()
    }

    fun signOut() {
        Audio.clear()
        Downloads.pauseAll(unload = true)
        p.edit().remove("token").remove("queue").remove("pos").apply()
        session = null
    }

    fun loadExtensions() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val r = Api.call("getOpenSubsonicExtensions")
                ext.value = r.list("openSubsonicExtensions").map { it.optString("name") }.toSet()
            }
            runCatching { serverVersion = Api.call("ping").optString("serverVersion") }
        }
    }
}

/** Connectivity: "online" means the server answers, not just that there's a network. */
object Net {
    var reachable by mutableStateOf(true)
    val offline get() = Settings.offlineMode || !reachable
    private var cm: ConnectivityManager? = null
    private var started = false

    fun unmetered(): Boolean {
        val c = cm ?: return true
        val n = c.activeNetwork ?: return false
        return c.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    fun start(ctx: Context) {
        cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (started) { ping(); return }
        started = true
        ping()
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { ping() }
        })
    }

    /** ping with a 4 s timeout; failure means offline, any success means online */
    fun ping() {
        val s = Store.session ?: return
        if (Settings.offlineMode) return
        Store.scope.launch(Dispatchers.IO) {
            val ok = runCatching { getJson(s.url("ping"), 4_000) }.isSuccess
            withContext(Dispatchers.Main) { reachable = ok }
            if (ok) { Outbox.flush(); Downloads.kick() }
        }
    }
    fun ok() { if (!reachable) reachable = true }
    fun failed(e: Throwable) { if (e is java.io.IOException && e !is ApiError && e !is HttpErr) reachable = false }
}

/** Library GETs: JSON cached on disk per account, so screens open from the cache and refresh behind it. */
object Api {
    private val noCache = setOf("ping", "star", "unstar", "scrobble")
    private fun file(method: String, params: Map<String, String>) = File(Store.dir("cache"), cacheKey(method, params) + ".json")

    fun cached(method: String, params: Map<String, String> = emptyMap()): JSONObject? =
        runCatching { file(method, params).takeIf { it.isFile }?.readText()?.let { JSONObject(it) } }.getOrNull()

    /** network call; throws on failure (and flips offline on an unreachable server) */
    fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val s = Store.session ?: throw ApiError(40, "Signed out")
        if (Net.offline) throw java.io.IOException("offline")
        val r = try { getJson(s.url(method, params)) } catch (e: Throwable) { Net.failed(e); throw e }
        Store.scope.launch { Net.ok() }
        if (method !in noCache) runCatching {
            val f = file(method, params)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(r.toString()); tmp.renameTo(f)
        }
        return r
    }

    fun cacheSize(): Long = (File(Store.app.filesDir, "cache/${Store.owner}").walk() + File(Store.app.cacheDir, "covers/${Store.owner}").walk()).filter { it.isFile }.sumOf { it.length() }
    fun clearCache() {
        File(Store.app.filesDir, "cache/${Store.owner}").deleteRecursively()
        File(Store.app.cacheDir, "covers/${Store.owner}").deleteRecursively()
        Covers.clearMemory()
    }
}

/** star/unstar/scrobble made offline wait here and replay in order. */
object Outbox {
    private val lock = Any()
    private fun read() = JSONArray(Store.p.getString("outbox", "[]"))
    fun add(method: String, params: Map<String, String>) = synchronized(lock) {
        Store.p.edit().putString("outbox", read().put(JSONObject().put("m", method).put("p", JSONObject(params))).toString()).apply()
    }

    /** try [method] now; queue it when the server can't be reached */
    fun send(method: String, params: Map<String, String>) {
        Store.scope.launch(Dispatchers.IO) {
            try { Api.call(method, params) } catch (e: ApiError) { } catch (e: java.io.IOException) { add(method, params) }
        }
    }

    fun flush() = synchronized(lock) {
        val a = read()
        if (a.length() == 0) return
        var done = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val p = o.getJSONObject("p")
            val m = p.keys().asSequence().associateWith { p.getString(it) }
            try { Api.call(o.getString("m"), m) } catch (e: ApiError) { } catch (e: java.io.IOException) { break }
            done++
        }
        val left = JSONArray().apply { for (i in done until a.length()) put(a.get(i)) }
        Store.p.edit().putString("outbox", left.toString()).apply()
    }
}

/** Favorite state that survives the lists it came from being stale. */
object Fav {
    private val over: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    fun on(id: String, initial: Boolean) = over[id] ?: initial
    /** [param] is the star API's id kind: id (song), albumId or artistId */
    fun toggle(id: String, initial: Boolean, param: String = "id") {
        val now = !on(id, initial)
        over[id] = now
        Outbox.send(if (now) "star" else "unstar", mapOf(param to id))
    }
}

class RemoteState<T> {
    var value by mutableStateOf<T?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var tick by mutableStateOf(0)
    internal var raw: String? = null
    fun refresh() { tick++ }
}

/** Cached copy first, then the network; swaps the value in only if the server's answer differs. */
@Composable
fun <T : Any> remote(method: String, params: Map<String, String> = emptyMap(), parse: (JSONObject) -> T?): RemoteState<T> {
    val st = remember(method, params) { RemoteState<T>() }
    val parseNow by rememberUpdatedState(parse)
    LaunchedEffect(st, st.tick, Net.offline) {
        if (st.value == null) withContext(Dispatchers.IO) {
            Api.cached(method, params)?.let { j -> runCatching { parseNow(j) }.getOrNull()?.let { st.raw = j.toString(); st.value = it } }
        }
        if (!Net.offline) {
            st.loading = true
            try {
                val j = withContext(Dispatchers.IO) { Api.call(method, params) }
                val s = j.toString()
                if (s != st.raw) withContext(Dispatchers.Default) { parseNow(j) }?.let { st.raw = s; st.value = it }
                st.error = null
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { st.error = e.message }
            st.loading = false
        }
    }
    return st
}
