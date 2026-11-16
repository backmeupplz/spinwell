package com.borodutch.spinwell

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class Main : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        if (Store.session != null) Audio.ensureService(this)
        setContent { SpinwellTheme { Root() } }
    }

    override fun onStop() {
        super.onStop()
        if (Store.session != null) Audio.savePos()
    }
}

@Composable
fun Root() {
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (Store.session == null) SignIn() else Shell()
    }
}

@Composable
fun SignIn() {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Store.p.getString("server", "") ?: "") }
    var user by remember { mutableStateOf(Store.p.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(Store.signInNotice) }
    fun go() {
        if (busy) return
        busy = true; error = null
        scope.launch { try { Store.signIn(server, user, pass) } catch (e: Exception) { error = e.message ?: "Couldn't sign in." }; busy = false }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp).background(Surface2, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Glyph(Ic.Note, Accent, 48.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Spinwell", style = MaterialTheme.typography.headlineMedium)
        Text("Your own music, anywhere.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, focusedLabelColor = Accent, cursorColor = Accent)
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true, colors = fieldColors,
            placeholder = { Text("https://music.example.ts.net", color = Muted.copy(alpha = 0.6f)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, colors = fieldColors)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }))
        AnimatedVisibility(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(24.dp))
        Button(::go, Modifier.fillMaxWidth().height(52.dp), enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF16112E))) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), Color(0xFF16112E), strokeWidth = 2.5.dp) else Text("Sign in", fontWeight = FontWeight700)
        }
    }
}

private val FontWeight700 = androidx.compose.ui.text.font.FontWeight.Bold

private val tabs = listOf(
    Triple(Route.Home, "Home", Ic.Home), Triple(Route.Library, "Library", Ic.Library),
    Triple(Route.Search, "Search", Ic.Search), Triple(Route.Downloads, "Downloads", Ic.Download),
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Shell() {
    val ctx = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        Store.loadExtensions()
    }
    val exp = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val hasPlayer = Audio.q.items.isNotEmpty()
    // restored while playing: open the full player once
    LaunchedEffect(Unit) { if (Audio.restoredWasPlaying && Audio.q.items.isNotEmpty()) exp.animateTo(1f, tween(450)) }
    BackHandler(exp.value > 0.5f) { scope.launch { exp.animateTo(0f, tween(300)) } }
    BackHandler(exp.value <= 0.5f && Nav.stack.size > 1) { Nav.pop() }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val barH = 60.dp + navBottom
    val miniH = 64.dp
    val offline = Net.offline
    val top = statusTop + if (offline) 30.dp else 0.dp
    val bottom = barH + if (hasPlayer) miniH + 14.dp else 0.dp

    CompositionLocalProvider(LocalBottom provides bottom, LocalTop provides top) {
        Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout {
                AnimatedContent(
                    Nav.top, Modifier.fillMaxSize(), label = "nav",
                    transitionSpec = {
                        val detail = targetState !is Route.Home && targetState !is Route.Library && targetState !is Route.Search && targetState !is Route.Downloads
                        if (Nav.forward && detail) (fadeIn(tween(320)) + slideInHorizontally(tween(340)) { it / 14 }) togetherWith fadeOut(tween(200))
                        else if (!Nav.forward) fadeIn(tween(260)) togetherWith (fadeOut(tween(220)) + slideOutHorizontally(tween(260)) { it / 14 })
                        else fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                    },
                ) { route ->
                    CompositionLocalProvider(LocalShared provides this@SharedTransitionLayout, LocalVis provides this@AnimatedContent) {
                        Box(Modifier.fillMaxSize().background(Bg)) {
                            when (route) {
                                Route.Home -> HomeScreen()
                                Route.Library -> LibraryScreen()
                                Route.Search -> SearchScreen()
                                Route.Downloads -> DownloadsScreen()
                                Route.Settings -> SettingsScreen()
                                is Route.Album -> CollectionScreen(route.id, false, route.share, route.cover)
                                is Route.Playlist -> CollectionScreen(route.id, true, route.share, route.cover)
                                is Route.Artist -> ArtistScreen(route.id, route.share)
                            }
                        }
                    }
                }
            }
            OfflineBanner(statusTop)

            // tab bar slides away as the full player opens
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { translationY = exp.value * size.height * 1.2f }
                    .background(Bg.copy(alpha = 0.97f)).padding(bottom = navBottom).height(60.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { (route, label, icon) ->
                    val sel = Nav.tab == route
                    val tint by animateColorAsStateCompat(if (sel) Accent else Muted)
                    Column(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { Nav.go(route) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Glyph(icon, tint, 24.dp)
                        Text(label, fontSize = 11.sp, color = tint, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (hasPlayer) PlayerSheet(exp, barH, miniH, statusTop, navBottom)
        }
    }
}

@Composable
private fun animateColorAsStateCompat(c: Color) = androidx.compose.animation.animateColorAsState(c, tween(200), label = "tint")

@Composable
fun BoxScope.OfflineBanner(statusTop: androidx.compose.ui.unit.Dp) {
    AnimatedVisibility(Net.offline, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Box(
            Modifier.fillMaxWidth().background(Surface2).clickable { Settings.offlineMode = false; Net.ping() }.height(30.dp + statusTop).padding(top = statusTop),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (Settings.offlineMode) "Offline mode · showing downloads" else "Offline · showing downloads · tap to retry", fontSize = 12.sp, color = Color(0xFFFFC78A))
        }
    }
}
