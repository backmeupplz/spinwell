package com.borodutch.naviplus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------- search ----------

private class Found(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>)

@Composable
fun SearchScreen() {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Found?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, Net.offline) {
        if (query.isBlank()) { found = null; return@LaunchedEffect }
        delay(250)
        found = if (Net.offline) {
            val q = query.trim().lowercase()
            Found(emptyList(), emptyList(), Downloads.done.values.map { it.song }.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() })
        } else withContext(Dispatchers.IO) {
            val p = mapOf("query" to query.trim(), "artistCount" to "6", "albumCount" to "12", "songCount" to "25")
            runCatching {
                val o = Api.call("search3", p).getJSONObject("searchResult3")
                Found(o.list("artist").map(Artist::of), o.list("album").map(Album::of), o.list("song").map(Song::of))
            }.getOrNull()
        }
    }
    Column(Modifier.fillMaxSize().padding(top = LocalTop.current)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus), singleLine = true,
            placeholder = { Text(if (Net.offline) "Search downloads" else "Artists, albums, songs", color = Muted) },
            leadingIcon = { Glyph(Ic.Search, Muted) },
            trailingIcon = { if (query.isNotEmpty()) IconBtn(Ic.Close, "Clear", size = 40.dp) { query = "" } },
            shape = RoundedCornerShape(50), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedContainerColor = Surface1, focusedContainerColor = Surface1, unfocusedBorderColor = Color.Transparent, cursorColor = Accent),
        )
        val f = found
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            if (f != null) {
                if (f.artists.isNotEmpty()) item {
                    SectionTitle("Artists")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(f.artists, key = { it.id }) { a -> ArtistChip(a) { Nav.push(Route.Artist(a.id, "search")) } } }
                }
                if (f.albums.isNotEmpty()) item {
                    SectionTitle("Albums")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(f.albums, key = { it.id }) { a -> AlbumCard(a, "search") { Nav.push(Route.Album(a.id, "search", a.coverArt)) } } }
                }
                if (f.songs.isNotEmpty()) {
                    item { SectionTitle("Songs") }
                    itemsIndexed(f.songs, key = { i, s -> "$i-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = f.songs, index = i, ctx = Ctx("search", query, "Search: $query")) }
                }
                if (f.artists.isEmpty() && f.albums.isEmpty() && f.songs.isEmpty()) item { Empty("Nothing found for “$query”.") }
            } else if (query.isBlank()) item { Empty("Find anything in your library.") }
        }
    }
}

// ---------- downloads ----------

@Composable
fun DownloadsScreen() {
    val dl = Downloads
    Column(Modifier.fillMaxSize().padding(top = LocalTop.current)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            item {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                Text("${fmtBytes(dl.totalBytes())} · ${dl.done.size} songs", color = Muted, modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 8.dp))
            }
            if (dl.queue.isNotEmpty()) item {
                val cur = dl.queue.firstOrNull { it.id == dl.currentId } ?: dl.queue.first()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).background(Surface1, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Downloading", style = MaterialTheme.typography.labelLarge, color = Accent)
                            Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when { dl.paused -> "Paused"; dl.status.isNotEmpty() -> dl.status; else -> "" } + (if (dl.paused || dl.status.isNotEmpty()) " · " else "") + "${dl.queue.size} in queue",
                                color = Muted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton({ if (dl.paused) dl.resumeAll() else dl.pauseAll() }) { Text(if (dl.paused) "Resume all" else "Pause all", color = Accent) }
                    }
                    Spacer(Modifier.height(10.dp))
                    val p = if (cur.id == dl.currentId && cur.size > 0) (dl.currentBytes.toFloat() / cur.size).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth().height(4.dp), Accent, Surface2)
                }
            }
            if (dl.pinned.isNotEmpty()) {
                item { SectionTitle("Albums and playlists") }
                items(dl.pinned.toList(), key = { "${it.kind}-${it.id}" }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    val songs = p.songIds.mapNotNull { dl.done[it]?.song }
                    Row(Modifier.fillMaxWidth().press { Nav.push(if (p.kind == "album") Route.Album(p.id, "dl", p.coverArt) else Route.Playlist(p.id, "dl", p.coverArt)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(p.coverArt, 300, Modifier.size(56.dp), 8.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val pr = dl.progress(p.songIds.mapNotNull { id -> dl.done[id]?.song ?: dl.queue.firstOrNull { it.id == id } })
                            Text("${if (p.kind == "album") "Album" else "Playlist"} · ${songs.size}/${p.songIds.size} songs · ${fmtBytes(songs.sumOf { dl.done[it.id]?.bytes ?: 0 })}" + if (pr < 1f && dl.queue.isNotEmpty()) " · downloading" else "", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconBtn(Ic.More, "More", tint = Muted) { menu = true }
                            DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                                DropdownMenuItem({ Text("Play") }, { menu = false; if (songs.isNotEmpty()) Audio.play(songs, 0, Ctx(p.kind, p.id, p.name)) })
                                DropdownMenuItem({ Text("Remove download") }, { menu = false; dl.removeContainer(p) })
                            }
                        }
                    }
                }
            }
            val loose = dl.loose.map { it.song }
            if (loose.isNotEmpty()) {
                item { SectionTitle("Songs") }
                itemsIndexed(loose, key = { _, s -> "l-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = loose, index = i, ctx = Ctx("downloads", "loose", "Downloads")) }
            }
            if (dl.done.isEmpty() && dl.queue.isEmpty()) item {
                Empty("Nothing downloaded yet. Open an album or playlist and tap the download arrow, or use ··· on a song. Downloads play without a connection.")
            }
        }
    }
}

// ---------- settings ----------

/** A settings row. [info] has no default on purpose: a setting can't exist without its explanation. */
@Composable
fun SettingRow(title: String, info: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(start = 20.dp, end = 8.dp).heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) Text(value, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
        InfoButton(title, info)
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Surface2, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    title = { Text(title) },
    text = {
        Column {
            options.forEach { (k, n) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(k); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(k == selected, { onPick(k); onDismiss() }, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                    Text(n)
                }
            }
        }
    },
)

private val kbpsOptions = listOf(0 to "Original", 320 to "320 kbps", 256 to "256 kbps", 192 to "192 kbps", 128 to "128 kbps")
private fun kbpsName(k: Int) = if (k <= 0) "Original" else "$k kbps"

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf("") }
    val cacheBytes by produceState(0L, dialog) { value = withContext(Dispatchers.IO) { Api.cacheSize() } }
    val s = Store.session
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = LocalTop.current + 56.dp, bottom = LocalBottom.current + 16.dp)) {
            item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)) }
            item {
                SettingRow("Server", "The server you're signed in to. Signing out keeps your downloads; they come back when you sign in to the same account.",
                    "${s?.user} @ ${s?.server?.substringAfter("://")}" + if (Store.serverVersion.isNotBlank()) " · v${Store.serverVersion.substringBefore(' ')}" else "",
                    { dialog = "signout" }) { Text("Sign out", color = Accent, modifier = Modifier.padding(end = 4.dp)) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Surface2)
                SettingRow("Streaming quality on Wi-Fi",
                    "How songs are sent when you're on Wi-Fi. Original plays the file as it is on your server, so FLAC stays lossless; a number asks the server to convert to MP3 at that bitrate, which uses less data but can't sound better than the original.",
                    kbpsName(Settings.wifiKbps), { dialog = "wifi" })
                SettingRow("Streaming quality on mobile data",
                    "The same choice for when you're on mobile data. A lower bitrate saves your data plan and starts faster on a weak signal.",
                    kbpsName(Settings.mobileKbps), { dialog = "mobile" })
                SettingRow("Download quality",
                    "How songs are saved for offline listening. Original keeps the exact file from your server and takes the most space; 320 or 192 asks the server to convert to MP3, which can make a FLAC album several times smaller.",
                    kbpsName(Settings.dlKbps), { dialog = "dl" })
                SettingRow("Download over mobile data", "When this is off, downloads wait for Wi-Fi and start again by themselves once you're connected.") {
                    Switch(Settings.dlMobile, { Settings.dlMobile = it }, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF16112E)))
                }
                SettingRow("Resume playing on open",
                    "If music was playing when the app was closed, start it again right away when you open the app. When this is off, the player opens where you left off, paused, so one tap continues.") {
                    Switch(Settings.resume, { Settings.resume = it }, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF16112E)))
                }
                SettingRow("Offline mode",
                    "Use only what's downloaded and never contact the server. The app also switches to this by itself whenever your server can't be reached, for example when Tailscale is off.") {
                    Switch(Settings.offlineMode, { Settings.offlineMode = it; if (!it) Net.ping() }, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF16112E)))
                }
                SettingRow("Report plays to server",
                    "Tell your server what you listen to, so Recently played and Most played stay accurate and play counts go up. Plays made offline are sent once you're back online.") {
                    Switch(Settings.scrobble, { Settings.scrobble = it }, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF16112E)))
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Surface2)
                SettingRow("Cache",
                    "Album covers and library pages kept on your phone so screens open instantly and you can browse offline. Clearing it is safe; it fills up again as you browse.",
                    "Covers and library: ${fmtBytes(cacheBytes)}") { TextButton({ Api.clearCache(); dialog = "x" }) { Text("Clear", color = Accent) } }
                SettingRow("Downloads", "Songs saved for offline listening. Removing them frees the space; you can download them again any time.",
                    "${fmtBytes(Downloads.totalBytes())} · ${Downloads.done.size} songs") { TextButton({ dialog = "rmdl" }, enabled = Downloads.done.isNotEmpty()) { Text("Remove all", color = Accent) } }
                SettingRow("About", "Navi+ is a free, open source player for your own Navidrome or OpenSubsonic server. No ads, no analytics.",
                    "Navi+ $version · git.borodutch.com/backmeupplz/naviplus")
            }
        }
        BackButton(Modifier.padding(top = LocalTop.current))
    }
    when (dialog) {
        "wifi" -> Choice("Streaming quality on Wi-Fi", kbpsOptions, Settings.wifiKbps, { Settings.wifiKbps = it }) { dialog = "" }
        "mobile" -> Choice("Streaming quality on mobile data", kbpsOptions, Settings.mobileKbps, { Settings.mobileKbps = it }) { dialog = "" }
        "dl" -> Choice("Download quality", kbpsOptions.filter { it.first in setOf(0, 320, 192) }, Settings.dlKbps, { Settings.dlKbps = it }) { dialog = "" }
        "signout" -> AlertDialog({ dialog = "" }, containerColor = Surface2, title = { Text("Sign out?") }, text = { Text("Your downloads stay on this phone and come back when you sign in to the same account.") },
            confirmButton = { TextButton({ dialog = ""; Nav.stack.clear(); Nav.stack.add(Route.Home); Store.signOut() }) { Text("Sign out", color = Accent) } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } })
        "rmdl" -> AlertDialog({ dialog = "" }, containerColor = Surface2, title = { Text("Remove all downloads?") }, text = { Text("This frees ${fmtBytes(Downloads.totalBytes())}. You can download songs again any time.") },
            confirmButton = { TextButton({ dialog = ""; Downloads.removeAll() }) { Text("Remove all", color = Accent) } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } })
    }
}
