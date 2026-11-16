package com.borodutch.naviplus

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
