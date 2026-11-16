package com.borodutch.spinwell

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
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

// ---------- downloads ----------

@Composable
fun DownloadsScreen() {
    val dl = Downloads
    Column(Modifier.fillMaxSize().padding(top = LocalTop.current)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottom.current + 16.dp)) {
            item {
                ScreenTitle("Downloads")
                Text("${fmtBytes(dl.totalBytes())} · ${songsLabel(dl.done.size)}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
            }
            if (dl.done.isNotEmpty()) item(key = "ps") { PlayShuffle(Offline.songs, Ctx("downloads", "all", "Downloads")) }
            if (dl.queue.isNotEmpty()) item {
                val cur = dl.queue.firstOrNull { it.id == dl.currentId } ?: dl.queue.first()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).background(Surface1, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
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
                    Spacer(Modifier.height(6.dp))
                    val p = if (cur.id == dl.currentId && cur.size > 0) (dl.currentBytes.toFloat() / cur.size).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth().height(4.dp), Accent, Surface2)
                }
            }
            if (dl.pinned.isNotEmpty()) {
                item { SectionTitle("Albums and playlists") }
                items(dl.pinned.toList(), key = { "${it.kind}-${it.id}" }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    val songs = p.songIds.mapNotNull { dl.done[it]?.song }
                    val pr = dl.progress(p.songIds.mapNotNull { id -> dl.done[id]?.song ?: dl.queue.firstOrNull { it.id == id } })
                    val ctx = Ctx(p.kind, p.id, p.name)
                    MediaRow(p.coverArt, p.name, round = p.kind == "artist", sub = "${p.kind.replaceFirstChar { it.uppercase() }} · ${songs.size}/${p.songIds.size} songs · ${fmtBytes(songs.sumOf { dl.done[it.id]?.bytes ?: 0 })}" + if (pr < 1f && dl.queue.isNotEmpty()) " · downloading" else "",
                        trailing = {
                            Box {
                                IconBtn(Ic.More, "More", size = 44.dp, tint = Muted) { menu = true }
                                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                                    DropdownMenuItem({ Text("Play") }, { menu = false; if (songs.isNotEmpty()) Audio.play(songs, 0, ctx) })
                                    DropdownMenuItem({ Text("Shuffle") }, { menu = false; if (songs.isNotEmpty()) Audio.shuffleAll(songs, ctx) })
                                    RepeatMenuItem(p.kind, p.id, songs, ctx) { menu = false }
                                    DropdownMenuItem({ Text("Remove download") }, { menu = false; dl.removeContainer(p) })
                                }
                            }
                        }) { Nav.push(when (p.kind) { "album" -> Route.Album(p.id, "dl", p.coverArt); "artist" -> Route.Artist(p.id, "dl"); else -> Route.Playlist(p.id, "dl", p.coverArt) }) }
                }
            }
            val loose = dl.loose.map { it.song }
            if (loose.isNotEmpty()) {
                item { SectionTitle("Songs") }
                itemsIndexed(loose, key = { _, s -> "l-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = loose, index = i, ctx = Ctx("downloads", "loose", "Downloads")) }
            }
            if (dl.done.isEmpty() && dl.queue.isEmpty()) item {
                Empty("Nothing downloaded yet. Tap the download arrow on an album, playlist or artist, or swipe a song left. Downloads play without a connection.", icon = Ic.Download)
            }
        }
    }
}

// ---------- settings ----------

private val Green = Color(0xFF5CCB8A)
private val Orange = Color(0xFFFFB067)
private val Blue = Color(0xFF6FB6FF)
private val Pink = Color(0xFFFF8FB1)

/** a rounded square carrying an icon in its own tint, the mark of every settings row */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp = 32.dp) =
    Box(Modifier.size(size).background(tint.copy(alpha = 0.16f), RoundedCornerShape(size / 3.6f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.56f), tint)
    }

/** a titled group of rows on one card */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1), content = content)
}

/** how much of Spinwell's own storage a row takes */
@Composable
private fun UsageBar(frac: Float, color: Color) =
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface2)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }

/** A settings row. [info] has no default on purpose: a setting can't exist without its explanation. */
@Composable
fun SettingRow(
    title: String, info: String, icon: ImageVector, tint: Color, value: String? = null, sub: String? = null, divider: Boolean = true,
    onClick: (() -> Unit)? = null, below: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(start = 12.dp, end = 0.dp).heightIn(min = 54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (sub != null) Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall)
                below?.invoke()
            }
            if (value != null) Text(value, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(max = 130.dp))
            if (value != null && onClick != null) Icon(Ic.Chevron, null, Modifier.size(18.dp), Muted)
            trailing?.invoke()
            InfoButton(title, info)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 56.dp), color = Color.White.copy(alpha = 0.06f))
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
private fun SwitchRow(title: String, info: String, icon: ImageVector, tint: Color, on: Boolean, divider: Boolean = true, set: (Boolean) -> Unit) =
    SettingRow(title, info, icon, tint, divider = divider, onClick = { set(!on) }, trailing = {
        Switch(on, set, Modifier.padding(end = 2.dp), colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF16112E)))
    })

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf("") }
    val cacheBytes by produceState(0L, dialog) { value = withContext(Dispatchers.IO) { Api.cacheSize() } }
    val s = Store.session
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    val dlBytes = Downloads.totalBytes()
    val total = (cacheBytes + dlBytes).coerceAtLeast(1)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = LocalTop.current + 48.dp, bottom = LocalBottom.current + 16.dp)) {
            item { ScreenTitle("Settings") }
            item {
                // the account: server, user, version, and whether it answers right now
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Ic.Server, Accent, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s?.server?.substringAfter("://").orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s?.user}" + if (Store.serverVersion.isNotBlank()) " · Navidrome ${Store.serverVersion.substringBefore(' ')}" else "", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                val up = !Net.offline
                                Box(Modifier.size(8.dp).background(if (up) Green else Orange, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(if (up) "Online" else if (Settings.offlineMode) "Offline mode" else "Can't reach the server", style = MaterialTheme.typography.bodySmall, color = if (up) Green else Orange)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Row(Modifier.fillMaxWidth().clickable { dialog = "signout" }.padding(start = 16.dp, end = 0.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sign out", color = Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        InfoButton("Server", "The server you're signed in to. Signing out keeps your downloads; they come back when you sign in to the same account.")
                    }
                }
            }
            item {
                Section("Playback") {
                    SettingRow("Streaming quality on Wi-Fi", "How songs are sent when you're on Wi-Fi. Original plays the file as it is on your server, so FLAC stays lossless; a number asks the server to convert to MP3 at that bitrate, which uses less data but can't sound better than the original.",
                        Ic.Wifi, Accent, kbpsName(Settings.wifiKbps), onClick = { dialog = "wifi" })
                    SettingRow("Streaming quality on mobile data", "The same choice for when you're on mobile data. A lower bitrate saves your data plan and starts faster on a weak signal.",
                        Ic.Cell, Accent, kbpsName(Settings.mobileKbps), onClick = { dialog = "mobile" })
                    SwitchRow("Resume playing on open", "If music was playing when the app was closed, start it again right away when you open the app. When this is off, the player opens where you left off, paused, so one tap continues.",
                        Ic.PlayCircle, Accent, Settings.resume) { Settings.resume = it }
                    SwitchRow("Report plays to server", "Tell your server what you listen to, so Recently played and Most played stay accurate and play counts go up. Plays made offline are sent once you're back online.",
                        Ic.History, Accent, Settings.scrobble, divider = false) { Settings.scrobble = it }
                }
                Section("Downloads") {
                    SettingRow("Download quality", "How songs are saved for offline listening. Original keeps the exact file from your server and takes the most space; 320 or 192 asks the server to convert to MP3, which can make a FLAC album several times smaller.",
                        Ic.Tune, Green, kbpsName(Settings.dlKbps), onClick = { dialog = "dl" })
                    SwitchRow("Download over mobile data", "When this is off, downloads wait for Wi-Fi and start again by themselves once you're connected.",
                        Ic.Download, Green, Settings.dlMobile, divider = false) { Settings.dlMobile = it }
                }
                Section("Offline") {
                    SwitchRow("Offline mode", "Use only what's downloaded and never contact the server. The app also switches to this by itself whenever your server can't be reached, for example when Tailscale is off.",
                        Ic.CloudOff, Orange, Settings.offlineMode, divider = false) { Settings.offlineMode = it; if (!it) Net.ping() }
                }
                Section("Storage") {
                    SettingRow("Downloads", "Songs saved for offline listening. Removing them frees the space; you can download them again any time.",
                        Ic.Storage, Green, sub = "${fmtBytes(dlBytes)} · ${songsLabel(Downloads.done.size)}", below = { UsageBar(dlBytes.toFloat() / total, Green) },
                        trailing = { TextButton({ dialog = "rmdl" }, enabled = Downloads.done.isNotEmpty()) { Text("Remove all", color = if (Downloads.done.isNotEmpty()) Accent else Muted) } })
                    SettingRow("Cache", "Album covers and library pages kept on your phone so screens open instantly and you can browse offline. Clearing it is safe; it fills up again as you browse.",
                        Ic.Storage, Blue, sub = "Covers and library: ${fmtBytes(cacheBytes)}", below = { UsageBar(cacheBytes.toFloat() / total, Blue) }, divider = false,
                        trailing = { TextButton({ Api.clearCache(); dialog = "x" }) { Text("Clear", color = Accent) } })
                }
                Section("About") {
                    SettingRow("About", "Spinwell is a free, open source player for your own Navidrome or OpenSubsonic server. No ads, no analytics.",
                        Ic.Info, Pink, sub = "Spinwell $version", divider = false)
                }
                Text("git.borodutch.com/backmeupplz/spinwell", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 28.dp, top = 8.dp))
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
