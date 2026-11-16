# Spinwell spec

Spinwell is a small, fast, beautiful Navidrome / OpenSubsonic player, native on both phones:
Android at the repo root (`app/`, Kotlin, Jetpack Compose + Material 3, media3) and iPhone in `ios/` (SwiftUI, AVFoundation).
Both apps follow this document; when a platform has to differ, it says so here. Sister app: ABS+ (`~/code/absplus`),
whose project layout, cover cache and download design we reuse.

- Android: package `com.borodutch.spinwell`, minSdk 29, target/compile 36, AGP/Gradle versions as ABS+. Dependencies:
  Compose BOM + material3 + activity-compose + navigation-compose (or hand-rolled nav), media3-exoplayer +
  media3-session. Nothing else (no Coil, no Retrofit, no Room, no kotlinx-serialization: `org.json` + `HttpURLConnection`
  are enough). R8 on for release.
- iOS: bundle `com.borodutch.spinwell`, team `ACWP4F58HZ`, iOS 26.1+, iPhone only, Swift 5 mode, no packages.
  Project file like ABS+ (`PBXFileSystemSynchronizedRootGroup`, so new files need no pbxproj edits). Info.plist:
  `UIBackgroundModes = [audio]`, `NSAllowsArbitraryLoads = true` (home servers are often plain http on the LAN).
- App icon: the record from `tools/icon.svg` (same master on both platforms; iOS icon is that SVG rendered at 1024 px, opaque and full-bleed). The sign-in screen shows the same record on a transparent background (`Logo.imageset`).
- English only for now. Dark theme only (music app; art carries the color).

## Server API (OpenSubsonic, tested against Navidrome 0.64.2)

- Base `<server>/rest/<method>?u=<user>&t=<md5(password+salt)>&s=<salt>&v=1.16.1&c=Spinwell&f=json`.
  At sign-in, generate a random salt, compute the token, call `ping`, and store server + user + token + salt
  (never the password). iOS: Keychain. Android: app-private SharedPreferences.
- Server URL normalization: trim spaces and trailing `/`, add `https://` when no scheme is given.
- Errors: `subsonic-response.status == "failed"` with `error.code`/`message`; code 40/41 = wrong credentials → back to sign-in with the message.
- Methods used: `ping`, `getOpenSubsonicExtensions` (call once per launch online; gate `getLyricsBySongId` on
  `songLyrics`, `getTopSongs`-by-id on `topSongsByArtistId`), `getArtists`, `getArtist`, `getArtistInfo2`, `getTopSongs`,
  `getSimilarSongs2`, `getAlbum`, `getAlbumInfo2`, `getAlbumList2` (types `recent`, `newest`, `frequent`, `random`,
  `starred`, `alphabeticalByName`; size + offset paging), `getPlaylists`, `getPlaylist`, `getStarred2`, `getRandomSongs`,
  `search3`, `getSong`, `star`/`unstar`, `scrobble`, `getLyricsBySongId`, `getCoverArt`, `stream`, `download`.
- Cover art: `getCoverArt?id=<coverArt>&size=<px>`. Ids embed a content hash (`al-<id>_<hash>`), so cache forever by
  `id+size`. Request the size actually drawn (rounded up to 150/300/600/1200 px): 300 for list rows/grids, 600 for
  page heroes, 1200 for the player. Playlists have `coverArt` (`pl-…`) too.
- Streaming URL: `stream?id=…&maxBitRate=<kbps or 0>&format=<fmt>` (+ auth). Original quality = `maxBitRate=0`, no `format`.
  A lower quality = `format=mp3&maxBitRate=<n>`. `download?id=` serves the original file and honors `Range` (206).
  `stream` with a transcode also answers ranges once the server has cached the transcode, but don't rely on it.
- **iOS codecs:** AVPlayer can't play Ogg Opus/Vorbis (the real library has 276 `.opus` songs) or WMA/APE/etc.
  On iOS, any song whose `suffix` is not one of `mp3 m4a m4b aac alac flac wav aif aiff caf` always streams and
  downloads as `stream?format=aac&maxBitRate=256` (verified: Navidrome answers `audio/aac` ADTS), saved as `.aac`.
- Artist images: iOS draws `getCoverArt` of the artist's own `coverArt` id (`ar-…`, goes through the authenticated cover cache, works offline) then `getArtistInfo2.largeImageUrl` (fetched as a plain URL, cached by hash) when the artist has no cover id, then the newest album's cover. On Navidrome 0.64.2 the `ar-…` cover is the same picture as `largeImageUrl` (checked), so the first choice is already the real artist photo and renders from cache with no pop-in.

## Local data

- **JSON cache:** every library GET (everything except stream/download/scrobble/star) is cached on disk keyed by
  method + sorted params minus auth, per server+user. Screens render the cached copy instantly, then refresh in the
  background and swap in the result if it changed (stale-while-revalidate). This is the "opens instantly" feel and also
  what offline browsing reads.
- **Cover cache:** memory LRU (1/8 of heap on Android; NSCache on iOS) over a disk cache directory, decoded at the
  target size. Reuse ABS+'s `Ui.kt` cover loader on Android and `MediaLoader`/`UI.swift` approach on iOS. Covers needed
  by downloaded songs are fetched at 600 and 1200 when downloading so the player looks right offline.
- **Downloads** (copy ABS+'s design, `app/src/main/java/com/borodutch/absplus/Dl.kt`, iOS `Abs.swift` `Downloader`):
  the unit is the song, deduped by song id (a song in two playlists is stored once). Album/playlist/artist "Download"
  enqueues their songs and remembers the container as "pinned" (so the Downloads screen can list albums/playlists and
  newly added playlist songs get picked up on the next refresh). Queue persisted, one file at a time, `<id>.part` +
  `Range` resume, exponential backoff 2 s → 60 s while the server is unreachable. Android: foreground service
  (`dataSync`) with a progress notification, like `DlService`. iOS: a foreground `URLSession` data task per song appending to
  `<id>.part` with `Range` resume (ignored ranges restart from zero), wrapped in a background task so a download finishes after
  the app is backgrounded; no background `URLSession`.
  Files: `<files>/media/<sha256(server|user)>/<songId>.<ext>`, plus `downloads.json` holding each downloaded song's full
  song JSON + pinned containers, so offline mode can list and play them with full metadata. iOS excludes the media
  directory from iCloud backup.
- **Pending actions:** star/unstar and scrobbles made offline go to a persisted outbox replayed (in order) when online.

## Connectivity and offline mode

- "Online" means the server answers, not that there is internet (Tailscale off on Wi-Fi is the common case).
  **Offline switches by itself, both ways, within a few seconds:**
  - Launch: screens render from the cache at once, `ping` (4 s timeout) runs in the background; failure → offline.
  - Any request that fails with a connection error, timeout or DNS failure (or a 502/503/504 from a proxy in front of the server) → offline immediately; library requests time out after 10 s. Every screen keys its load on the offline flag, so the current screen reloads from cache/downloads at the flip, and again from the server when it is back.
  - Network changes (iOS `NWPathMonitor`; Android `ConnectivityManager`, VPN changes included) → re-`ping`.
  - While online and in front, a `ping` every 10 s notices a server that went away when nothing was being requested; it only flips offline after a second failed `ping` a second later, so one slow answer doesn't drop the preloaded next song (a failing real request flips at once).
  - While offline: `ping` every 20 s and whenever the app comes to the foreground. A successful ping (or any successful request) → online, the banner goes, the outbox flushes, downloads resume.
  - Playback: a streamed song that fails (error status or 6 s stuck buffering followed by a failed ping) flips offline and playback skips to the next downloaded song instead of stopping; a preloaded stream the phone can no longer fetch is dropped when the flip happens.
- Offline: a slim banner under the top bar ("Offline · showing downloads", tap to retry). Browsing uses the JSON cache.
  Songs that aren't downloaded are dimmed and can't be played; albums/playlists with no downloaded songs are dimmed.
  The Downloads tab works fully. Play queue skips non-downloaded songs.
  **Library offline:** Artists lists every artist with a downloaded song (track artists and album artists, so featured and compilation tracks count) under a "Downloaded" header, then the rest of the cached list dimmed; the artist page shows the downloaded albums and downloaded songs of that artist (cache + downloads) and Play/Shuffle play those. Albums: downloaded albums first, the cached rest dimmed. Playlists: pinned (downloaded) ones first, the rest dimmed. Search runs locally over downloaded artists, albums and songs.
- Manual "Offline mode" setting forces offline (no network calls at all, not even covers not on disk).

## Screens

Bottom navigation with 4 tabs: **Home**, **Library**, **Search**, **Downloads**. The mini player floats above the tab bar
on every screen. Settings open from a gear on Home.

- **Sign in:** app name + glyph, fields Server (placeholder `https://music.example.ts.net`), Username, Password
  (masked), Sign in button with progress; clear error text (unreachable / wrong password / not a Subsonic server).
- **Density:** list rows are ~56 pt with 40 pt thumbnails and 16 pt side padding; section titles are title-3 (~20 pt) and screens use inline titles; pill buttons are 40 pt tall inside a 44 pt hit area; round action buttons 40 pt (44 to hit); shelf cards 140 wide; album/playlist hero cover 200; artist hero 240 high. Corner radii: 6 thumbnails in rows, 8 cards, 12 hero covers, capsules for buttons. Empty states use a symbol and one sentence.
- **Home:** "Shuffle all" pill (getRandomSongs size=200; offline: all downloads shuffled) and "Favorites" pill;
  then horizontal shelves of album cards: Recently played (`recent`), Recently added (`newest`), Most played
  (`frequent`), Favorite albums (`starred`), Rediscover (`random`, size 20). Shelves with nothing are hidden. Pull to refresh.
- **Library:** segmented chips Artists · Albums · Playlists · Songs · Favorites.
  Artists: A–Z index list with artist image circles + album count, with a fast-scroll letter rail.
  Albums: 2-column grid (sort menu: name / artist / recently added / year / most played), paged 60 at a time.
  Playlists: list with cover + song count + duration. Songs: `search3` with empty query paged (Shuffle uses `getRandomSongs`).
  Favorites: starred artists, albums and songs.
  **Every container has Play and Shuffle:** album, playlist, artist, Favorites songs, Library Songs, search song results, Downloads.
- **Artist:** hero 240 pt (artist image, darkened gradient into the background color, name over it), action row Play, Shuffle, Download (all their songs, pinned like an album, ring progress / check, tap again to remove), `…` (Repeat artist),
  Top songs (5, expandable; offline "Downloaded songs"), Albums as a shelf sorted newest first (singles/EPs after albums when `releaseTypes` says so),
  "Similar artists" row, bio from `getArtistInfo2.biography` (HTML stripped, 3 lines, tap to expand).
- **Album:** backdrop = cover's dominant color fading into the background; 200 pt cover with soft shadow (the first tracks are visible without scrolling on a 6.1" phone); title,
  artist (tappable → artist), meta line `2019 · Synthpop · 8 songs · 34 min`, a quality badge from the songs
  (`FLAC 24/96`, `FLAC 16/44.1`, `MP3 320`, `OPUS`; mixed → highest); action row: Play (filled), Shuffle, Download
  (with ring progress / check when done), Favorite heart, `…` menu (Play next, Add to queue, Repeat album/playlist: loops that container with repeat-all, or toggles it when it is already the playing context). Track list grouped by disc ("Disc 2" headers only
  when >1 disc), row = track number (or animated equalizer bars when it's the playing song), title, featured artists
  when they differ from the album artist, duration, small download state icon. Album notes from `getAlbumInfo2` at
  the bottom if present.
- **Playlist:** like Album, cover = playlist cover; meta = owner · N songs · duration.
- **Search:** field focused whenever the tab shows; results appear while typing (`search3` debounced 200 ms, the previous request is cancelled and the previous results stay up until the new ones arrive, no empty flash). Sections: Top result (the first artist, album or song whose name equals the query, else starts with it), Artists, Albums, Songs (5 + "See all N songs", with Play and Shuffle; the queue is all song results; each row has the full song menu). Empty field: the last 10 searches (recorded on submit or when a result is tapped), with Clear. Case and accents are folded (Cyrillic, ё/е locally; the server already matches Cyrillic in any case); offline the same search runs over downloads. The keyboard dismisses on scroll.
- **Downloads:** storage used and Play/Shuffle at the top; "Downloading" section (current song progress, queued count, pause/resume
  all); then pinned albums/playlists and loose songs; swipe or menu to remove. Empty state explains how to download.
- **Songs and downloading:** every song row shows its state (downloaded check, spinner, queued dot); swiping a row left downloads it (or removes the download), the same item is in the row menu (which the full player's `…` menu shows too). Tapping a song starts decoding its big cover and color on press-down, so nothing is computed while the player expands.
- **Song row menu** (long-press or `…`): Play next, Add to queue, Go to album, Go to artist, Favorite/Unfavorite,
  Download / Remove download, Song info (format, bitrate, sample rate, bit depth, size, path, play count).
- **Mini player:** cover thumb, title + artist, play/pause, thin progress line along its bottom edge; swipe left/right
  skips; tap or swipe up expands into the full player.
- **Full player:** background = vertical gradient from the cover's dominant color to near-black, crossfading on
  track change. Large cover (rounded 12, shadow) that shrinks to ~85 % with a spring when paused; swipe the cover
  left/right to skip with a parallax slide. Title (2 lines max) + artist (tappable) + favorite heart; seek bar with
  elapsed/remaining, draggable with haptics; transport: shuffle, previous, play/pause (large, morphing icon), next,
  repeat (off/all/one); shuffle and repeat show accent color plus a dot when on and give a short haptic. The cover takes the height that is left, so the layout fits an iPhone 13 mini (5.4"). Bottom row: quality badge, Lyrics, Up next, output picker (iOS AVRoutePickerView; Android
  system output switcher intent). Drag down to dismiss.
- **Up next** (sheet from the player, Spotify style): "Now playing" row; "Next in queue" (songs added with Play
  next / Add to queue) with a Clear button; "Next from <context name>" (the rest of the album/playlist/shuffle).
  Drag handle to reorder, swipe to remove, tap to jump. The header has shuffle and repeat toggles (animated reorder on shuffle).
- **Lyrics** (sheet/page from the player): synced lyrics with the active line large and bright, others dim, smooth
  auto-scroll; tap a line to seek there. Unsynced lyrics shown plain. "No lyrics" state.
- **Settings:** inset-grouped list, an account card on top (server host, user, version, online/offline dot), every row has a tinted SF Symbol tile like iOS Settings and its value on the right, Storage has a usage bar (downloads vs cache) and free space; Sign out is its own section. Every row has an ⓘ button that shows a short explanation (iOS: a small bottom sheet sized to its text, with the list dimmed behind it; never a popover floating over other rows). Make the explanation a
  **required** parameter of the settings-row component so a setting can't exist without one. Rows:
  - Server: `user @ host`, version; Sign out (confirm; keeps downloads, which come back on signing in to the same account).
  - Streaming quality on Wi-Fi: Original / 320 / 256 / 192 / 128 kbps (default Original).
    ⓘ "How songs are sent when you're on Wi-Fi. Original plays the file as it is on your server, so FLAC stays lossless; a number asks the server to convert to MP3 at that bitrate, which uses less data but can't sound better than the original."
  - Streaming quality on mobile data: same options (default 192).
    ⓘ "The same choice for when you're on mobile data. A lower bitrate saves your data plan and starts faster on a weak signal."
  - Download quality: Original / 320 / 192 (default Original).
    ⓘ "How songs are saved for offline listening. Original keeps the exact file from your server and takes the most space; 320 or 192 asks the server to convert to MP3, which can make a FLAC album several times smaller."
  - Download over mobile data (default off).
    ⓘ "When this is off, downloads wait for Wi-Fi and start again by themselves once you're connected."
  - Resume playing on open (default off).
    ⓘ "If music was playing when the app was closed, start it again right away when you open the app. When this is off, the player opens where you left off, paused, so one tap continues."
  - Offline mode (default off).
    ⓘ "Use only what's downloaded and never contact the server. The app also switches to this by itself whenever your server can't be reached, for example when Tailscale is off."
  - Report plays to server (default on).
    ⓘ "Tell your server what you listen to, so Recently played and Most played stay accurate and play counts go up. Plays made offline are sent once you're back online."
  - Cache: "Covers and library: 84 MB" + Clear.
    ⓘ "Album covers and library pages kept on your phone so screens open instantly and you can browse offline. Clearing it is safe; it fills up again as you browse."
  - Downloads: "1.2 GB · 214 songs" + Remove all (confirm).
    ⓘ "Songs saved for offline listening. Removing them frees the space; you can download them again any time."
  - About: version, link to source.

## Playback, queue, shuffle

- Android: `MediaSessionService` + ExoPlayer (lock screen, notification, headset/Bluetooth buttons, Android Auto-ready
  session). Audio focus + becoming-noisy handled by ExoPlayer (`setAudioAttributes(…, true)`, `setHandleAudioBecomingNoisy(true)`).
  iOS: `AVQueuePlayer` (current + next item preloaded), `AVAudioSession(.playback)`, interruptions and route changes,
  `MPNowPlayingInfoCenter` (title, artist, album, artwork, duration, elapsed, rate), `MPRemoteCommandCenter`
  (play, pause, toggle, next, previous, changePlaybackPosition, like).
- Shuffle and repeat live in one queue state that is persisted; repeat (off → all → one) also survives a launch with an empty queue. Repeat all loops the current context, repeat one loops the song. iOS exposes both through `changeShuffleModeCommand` / `changeRepeatModeCommand` and keeps their current values in sync.
- Each song plays from its downloaded file when there is one, else the stream URL. Gapless where the format allows.
- **Queue model** (app-owned, shared by player and Up next, persisted on every change):
  `items: [Song]` (play order), `original: [Song]` (context order, to undo shuffle), `index`, `added: Set<itemKey>`
  (entries from Play next/Add to queue), `context: {kind, id, name}`, `shuffle`, `repeat`.
  Play next inserts at `index+1` (marked added); Add to queue inserts after the last added entry that follows the current one.
  Items get a unique key so the same song can appear twice.
- **Smart shuffle:** a pure function `smartShuffle(songs, first, seed)`: random permutation, then spread so no two
  adjacent songs share an artist (and, secondarily, an album) whenever the input allows it; `first` (current song)
  stays at position 0. Turning shuffle off restores context order and continues from the current song.
  Starting from a Shuffle button turns shuffle on and starts from a random song.
- Previous: restarts the song if more than 3 s in, else goes back.
- **Restore on launch:** persist queue + index + position (every 5 s while playing, and on pause/background/track change)
  + `wasPlaying`. On launch load the queue into the player paused at the saved position, immediately, before any
  network call. If `wasPlaying`: open the full player (expanded), and start playing if "Resume playing on open" is on.
  If not playing: just show the mini player.
- **Scrobble:** `submission=false` when a song starts, `submission=true` once it has played 50 % or 4 min; outbox when offline.

## Motion and look

- Shared-element transitions: album/playlist cover → page hero (Android `SharedTransitionLayout`; iOS
  `.navigationTransition(.zoom)` + `.matchedTransitionSource`), mini player → full player (cover, title, play button
  move together), smooth spring physics, 250–400 ms. Nothing pops: content fades/slides in, list cells don't jump
  when covers load (fixed aspect boxes with a placeholder tinted by a cheap average color).
- Dominant color: computed once per cover from a 16×16 downsample (ignore near-black/white pixels), cached by cover id.
- Equalizer bars for the playing song, morphing play/pause icon, haptics on long-press menus and seek, subtle press
  scale on cards.
- Performance: no work on the main thread beyond layout; lazy lists everywhere; covers decoded at display size;
  first frame on launch from cache with no spinner (iOS reads Home's cached shelves synchronously and pre-decodes their first covers while the UI builds). Target cold start < 500 ms on the OnePlus 8 and 60/120 fps scrolling.

## Checks

- Unit tests (Android JVM tests, iOS XCTest target), small: auth token, URL building + iOS transcode rule,
  `smartShuffle` (all songs kept, `first` first, no adjacent same artist when feasible, seed determinism),
  queue ops (play next / add to queue / remove / move / shuffle off restores), download resume (`.part` + Range against a
  tiny local HTTP server), JSON cache key excludes auth.
- Local test server: `tools/fixtures.sh <dir>` builds a tagged library; run Navidrome 0.64.2 on port 4533 against it
  (README "Test server"). Real server: `https://music.icefish-betta.ts.net` (credentials only on the developer's machine).
- iOS test hooks (`-open`, `-playalbum`, `-script`, `-server/-user/-pass`, `-offline`, `-tab`, ...) exist only in Debug builds (`#if DEBUG` in `Hooks.swift`); Release ignores all launch arguments.
