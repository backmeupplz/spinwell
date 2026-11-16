package com.borodutch.spinwell

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

sealed interface Route {
    data object Home : Route
    data object Library : Route
    data object Search : Route
    data object Downloads : Route
    data object Settings : Route
    /** [share] names the list the card came from, so the cover's shared-element key is unique on that screen */
    data class Album(val id: String, val share: String = "", val cover: String = "") : Route
    data class Playlist(val id: String, val share: String = "", val cover: String = "") : Route
    data class Artist(val id: String, val share: String = "") : Route
}

object Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    var forward by mutableStateOf(true)
    var libraryTab by mutableStateOf(0)
    val top get() = stack.last()
    val tab get() = stack.first()

    fun push(r: Route) { if (top != r) { forward = true; stack.add(r) } }
    fun pop(): Boolean { if (stack.size <= 1) return false; forward = false; stack.removeAt(stack.lastIndex); return true }
    // the stack survives the process: one line per route, fields split by a control char
    private fun enc(r: Route) = when (r) {
        is Route.Album -> listOf("Album", r.id, r.share, r.cover)
        is Route.Playlist -> listOf("Playlist", r.id, r.share, r.cover)
        is Route.Artist -> listOf("Artist", r.id, r.share)
        else -> listOf(r.toString())
    }.joinToString("\u001f")
    private fun dec(l: String): Route? = l.split("\u001f").let { f ->
        when (f[0]) {
            "Home" -> Route.Home; "Library" -> Route.Library; "Search" -> Route.Search; "Downloads" -> Route.Downloads; "Settings" -> Route.Settings
            "Album" -> Route.Album(f[1], f[2], f.getOrElse(3) { "" }); "Playlist" -> Route.Playlist(f[1], f[2], f.getOrElse(3) { "" }); "Artist" -> Route.Artist(f[1], f[2])
            else -> null
        }
    }
    fun save() { Store.p.edit().putString("nav", stack.joinToString("\n") { enc(it) }).putInt("libTab", libraryTab).apply() }
    fun restore() {
        val r = runCatching { Store.p.getString("nav", null)?.lines()?.mapNotNull { dec(it) } }.getOrNull()?.takeIf { it.isNotEmpty() && it.first() in setOf(Route.Home, Route.Library, Route.Search, Route.Downloads) } ?: return
        stack.clear(); stack.addAll(r); libraryTab = Store.p.getInt("libTab", 0).coerceIn(0, 4)
    }

    fun go(tab: Route) { forward = true; if (stack.size == 1 && stack[0] == tab) return; stack.clear(); stack.add(tab) }
}

val LocalBottom = compositionLocalOf { 0.dp }
val LocalTop = compositionLocalOf { 0.dp }
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
val LocalVis = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCover(key: String?): Modifier {
    if (key == null) return this
    val s = LocalShared.current ?: return this
    val v = LocalVis.current ?: return this
    return with(s) { this@sharedCover.sharedElement(rememberSharedContentState(key), v) }
}
