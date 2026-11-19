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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                ScreenTitle(stringResource(R.string.tab_downloads))
                Text("${fmtBytes(dl.totalBytes())} · ${songsLabel(dl.done.size)}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
            }
            if (dl.done.isNotEmpty()) item(key = "ps") { PlayShuffle(Offline.songs, Ctx("downloads", "all")) }
            if (dl.queue.isNotEmpty()) item {
                val cur = dl.queue.firstOrNull { it.id == dl.currentId } ?: dl.queue.first()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).background(Surface1, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.status_downloading), style = MaterialTheme.typography.labelLarge, color = Accent)
                            Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(if (dl.paused) stringResource(R.string.status_paused) else dl.status?.text(), pluralStringResource(R.plurals.fmt_in_queue, dl.queue.size, dl.queue.size)).joinToString(" · "),
                                color = Muted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton({ if (dl.paused) dl.resumeAll() else dl.pauseAll() }) { Text(stringResource(if (dl.paused) R.string.action_resume_all else R.string.action_pause_all), color = Accent) }
                    }
                    Spacer(Modifier.height(6.dp))
                    val p = if (cur.id == dl.currentId && cur.size > 0) (dl.currentBytes.toFloat() / cur.size).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth().height(4.dp), Accent, Surface2)
                }
            }
            if (dl.pinned.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.title_albums_playlists)) }
                items(dl.pinned.toList(), key = { "${it.kind}-${it.id}" }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    val songs = p.songIds.mapNotNull { dl.done[it]?.song }
                    val pr = dl.progress(p.songIds.mapNotNull { id -> dl.done[id]?.song ?: dl.queue.firstOrNull { it.id == id } })
                    val ctx = Ctx(p.kind, p.id, p.name)
                    val kindName = stringResource(when (p.kind) { "album" -> R.string.label_album; "artist" -> R.string.label_artist; else -> R.string.label_playlist })
                    val progress = pluralStringResource(R.plurals.fmt_songs_progress, p.songIds.size, songs.size, p.songIds.size)
                    MediaRow(p.coverArt, p.name, round = p.kind == "artist", sub = "$kindName · $progress · ${fmtBytes(songs.sumOf { dl.done[it.id]?.bytes ?: 0 })}" + if (pr < 1f && dl.queue.isNotEmpty()) " · " + stringResource(R.string.status_downloading) else "",
                        trailing = {
                            Box {
                                IconBtn(Ic.More, stringResource(R.string.a11y_more), size = 44.dp, tint = Muted) { menu = true }
                                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                                    DropdownMenuItem({ Text(stringResource(R.string.action_play)) }, { menu = false; if (songs.isNotEmpty()) Audio.play(songs, 0, ctx) })
                                    DropdownMenuItem({ Text(stringResource(R.string.action_shuffle)) }, { menu = false; if (songs.isNotEmpty()) Audio.shuffleAll(songs, ctx) })
                                    RepeatMenuItem(p.kind, p.id, songs, ctx) { menu = false }
                                    DropdownMenuItem({ Text(stringResource(R.string.action_remove_download)) }, { menu = false; dl.removeContainer(p) })
                                }
                            }
                        }) { Nav.push(when (p.kind) { "album" -> Route.Album(p.id, "dl", p.coverArt); "artist" -> Route.Artist(p.id, "dl"); else -> Route.Playlist(p.id, "dl", p.coverArt) }) }
                }
            }
            val loose = dl.loose.map { it.song }
            if (loose.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.title_songs)) }
                itemsIndexed(loose, key = { _, s -> "l-${s.id}" }) { i, s -> SongRow(s, showCover = true, queue = loose, index = i, ctx = Ctx("downloads", "loose")) }
            }
            if (dl.done.isEmpty() && dl.queue.isEmpty()) item {
                Empty(stringResource(R.string.empty_downloads), icon = Ic.Download)
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
    Text(title.uppercase(java.util.Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = caps(0.8f), modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp))
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
            if (value != null) Text(value, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 130.dp))
            if (value != null && onClick != null) Icon(Ic.Chevron, null, Modifier.size(18.dp).mirrorRtl(), Muted)
            trailing?.invoke()
            InfoButton(title, info)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 56.dp), color = Color.White.copy(alpha = 0.06f))
    }
}

@Composable
private fun Choice(title: String, options: List<Int>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Surface2, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    title = { Text(title) },
    text = {
        Column {
            options.forEach { k ->
                val n = kbpsName(k)
                Row(Modifier.fillMaxWidth().clickable { onPick(k); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(k == selected, { onPick(k); onDismiss() }, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                    Text(n)
                }
            }
        }
    },
)

private val kbpsOptions = listOf(0, 320, 256, 192, 128)
@Composable
private fun kbpsName(k: Int) = if (k <= 0) stringResource(R.string.label_original) else stringResource(R.string.fmt_kbps, k)

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
            item { ScreenTitle(stringResource(R.string.title_settings)) }
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
                                Text(stringResource(if (up) R.string.status_online else if (Settings.offlineMode) R.string.settings_offline_mode else R.string.status_unreachable), style = MaterialTheme.typography.bodySmall, color = if (up) Green else Orange)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Row(Modifier.fillMaxWidth().clickable { dialog = "signout" }.padding(start = 16.dp, end = 0.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.action_sign_out), color = Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        InfoButton(stringResource(R.string.label_server), stringResource(R.string.info_server))
                    }
                }
            }
            item {
                Section(stringResource(R.string.settings_group_playback)) {
                    SettingRow(stringResource(R.string.settings_wifi_quality), stringResource(R.string.info_wifi_quality),
                        Ic.Wifi, Accent, kbpsName(Settings.wifiKbps), onClick = { dialog = "wifi" })
                    SettingRow(stringResource(R.string.settings_mobile_quality), stringResource(R.string.info_mobile_quality),
                        Ic.Cell, Accent, kbpsName(Settings.mobileKbps), onClick = { dialog = "mobile" })
                    SwitchRow(stringResource(R.string.settings_resume), stringResource(R.string.info_resume),
                        Ic.PlayCircle, Accent, Settings.resume) { Settings.resume = it }
                    SwitchRow(stringResource(R.string.settings_scrobble), stringResource(R.string.info_scrobble),
                        Ic.History, Accent, Settings.scrobble, divider = false) { Settings.scrobble = it }
                }
                Section(stringResource(R.string.tab_downloads)) {
                    SettingRow(stringResource(R.string.settings_download_quality), stringResource(R.string.info_download_quality),
                        Ic.Tune, Green, kbpsName(Settings.dlKbps), onClick = { dialog = "dl" })
                    SwitchRow(stringResource(R.string.settings_download_mobile), stringResource(R.string.info_download_mobile),
                        Ic.Download, Green, Settings.dlMobile, divider = false) { Settings.dlMobile = it }
                }
                Section(stringResource(R.string.settings_group_offline)) {
                    SwitchRow(stringResource(R.string.settings_offline_mode), stringResource(R.string.info_offline_mode),
                        Ic.CloudOff, Orange, Settings.offlineMode, divider = false) { Settings.offlineMode = it; Audio.refresh(); if (!it) Net.ping() }
                }
                Section(stringResource(R.string.settings_group_storage)) {
                    SettingRow(stringResource(R.string.tab_downloads), stringResource(R.string.info_downloads),
                        Ic.Storage, Green, sub = "${fmtBytes(dlBytes)} · ${songsLabel(Downloads.done.size)}", below = { UsageBar(dlBytes.toFloat() / total, Green) },
                        trailing = { TextButton({ dialog = "rmdl" }, enabled = Downloads.done.isNotEmpty()) { Text(stringResource(R.string.action_remove_all), color = if (Downloads.done.isNotEmpty()) Accent else Muted) } })
                    SettingRow(stringResource(R.string.settings_cache), stringResource(R.string.info_cache),
                        Ic.Storage, Blue, sub = stringResource(R.string.fmt_cache_size, fmtBytes(cacheBytes)), below = { UsageBar(cacheBytes.toFloat() / total, Blue) }, divider = false,
                        trailing = { TextButton({ Api.clearCache(); dialog = "x" }) { Text(stringResource(R.string.action_clear), color = Accent) } })
                }
                Section(stringResource(R.string.title_about)) {
                    SettingRow(stringResource(R.string.title_about), stringResource(R.string.info_about),
                        Ic.Info, Pink, sub = "Spinwell $version", divider = false)
                }
                Text("git.borodutch.com/backmeupplz/spinwell", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 28.dp, top = 8.dp))
            }
        }
        BackButton(Modifier.padding(top = LocalTop.current))
    }
    when (dialog) {
        "wifi" -> Choice(stringResource(R.string.settings_wifi_quality), kbpsOptions, Settings.wifiKbps, { Settings.wifiKbps = it }) { dialog = "" }
        "mobile" -> Choice(stringResource(R.string.settings_mobile_quality), kbpsOptions, Settings.mobileKbps, { Settings.mobileKbps = it }) { dialog = "" }
        "dl" -> Choice(stringResource(R.string.settings_download_quality), kbpsOptions.filter { it in setOf(0, 320, 192) }, Settings.dlKbps, { Settings.dlKbps = it }) { dialog = "" }
        "signout" -> AlertDialog({ dialog = "" }, containerColor = Surface2, title = { Text(stringResource(R.string.title_sign_out_confirm)) }, text = { Text(stringResource(R.string.msg_sign_out_confirm)) },
            confirmButton = { TextButton({ dialog = ""; Nav.stack.clear(); Nav.stack.add(Route.Home); Store.signOut() }) { Text(stringResource(R.string.action_sign_out), color = Accent) } }, dismissButton = { TextButton({ dialog = "" }) { Text(stringResource(R.string.action_cancel)) } })
        "rmdl" -> AlertDialog({ dialog = "" }, containerColor = Surface2, title = { Text(stringResource(R.string.title_remove_all_downloads)) }, text = { Text(stringResource(R.string.msg_remove_all_downloads, fmtBytes(Downloads.totalBytes()))) },
            confirmButton = { TextButton({ dialog = ""; Downloads.removeAll() }) { Text(stringResource(R.string.action_remove_all), color = Accent) } }, dismissButton = { TextButton({ dialog = "" }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
