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
- Artist images: `getArtistInfo2.largeImageUrl` (absolute URL from the server); fall back to the first album's cover.

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
  (`dataSync`) with a progress notification, like `DlService`. iOS: foreground `URLSession` download tasks with resume
  data (like ABS+); a background session is fine too if simpler since auth is in the query string.
  Files: `<files>/media/<sha256(server|user)>/<songId>.<ext>`, plus `downloads.json` holding each downloaded song's full
  song JSON + pinned containers, so offline mode can list and play them with full metadata. iOS excludes the media
  directory from iCloud backup.
- **Pending actions:** star/unstar and scrobbles made offline go to a persisted outbox replayed (in order) when online.

## Connectivity and offline mode

- "Online" means the server answers, not that there is internet (Tailscale off on Wi-Fi is the common case).
- **Offline switches itself on, quickly:** at launch the screens render from the cache at once while `ping` (4 s hard timeout,
  DNS included) runs in the background; a failed ping means offline. Any later request that fails with a connection error, timeout,
  DNS failure or a 502/503/504 flips offline immediately and the current screen reloads from cache/downloads. Platform network
  callbacks (Android `ConnectivityManager` default-network callback incl. VPN/transport changes; iOS `NWPathMonitor`) re-ping.
  A request we cancel ourselves (search typing) never counts as a failure.
- **And back off, by itself:** while offline the app re-pings every 20 s and whenever it returns to the foreground; any ping or
  request that gets an answer (even a refused login) means online: the banner disappears, the outbox flushes, downloads resume,
  open screens refresh. Overlapping pings collapse into one.
  iOS also pings every 10 s while online and in front (flipping offline only after a second failed ping a second later), so a
  server that goes away while nothing is being requested is noticed; Android relies on requests and network callbacks.
- Playback: if a streamed song fails because the server is gone, the app marks offline and skips to the next downloaded song of
  the queue (wrapping when repeating all) instead of stopping with an error; consecutive failures are capped at the queue length.
- Offline: a slim banner under the top bar ("Offline · showing downloads", tap to retry). Browsing uses the JSON cache plus
  `downloads.json`, which holds every downloaded song's full metadata, so:
  - Library › **Artists**: every artist with at least one downloaded song, A to Z (photo/album count from the cached artist list
    when there is one), then the rest of the cached list dimmed under "Not downloaded".
  - Library › **Albums**: downloaded albums first (built from downloads, enriched from the cache), the cached rest dimmed.
    **Playlists**: pinned (downloaded) playlists first, even when never cached, the rest dimmed. **Songs**: the downloaded songs.
  - **Artist page**: hero + Play/Shuffle over the downloaded songs ("Downloaded songs", expandable) and "Downloaded albums"
    (from cache + downloads; works for artists whose page was never cached). Album/playlist pages never cached are rebuilt from downloads.
  - Home gets a "Downloaded albums" shelf; search runs locally over downloaded songs, albums and artists.
  - Songs that aren't downloaded are dimmed and can't be played; the play queue skips them. The Downloads tab works fully.
- Manual "Offline mode" setting forces offline (no network calls at all, not even covers not on disk).

## Screens

Bottom navigation with 4 tabs: **Home**, **Library**, **Search**, **Downloads**. The mini player floats above the tab bar
on every screen. Settings open from a gear on Home.

- **Sign in:** app name + glyph, fields Server (placeholder `https://music.example.ts.net`), Username, Password
  (masked), Sign in button with progress; clear error text (unreachable / wrong password / not a Subsonic server).
- **Density:** the app is compact, in the way of Apple Music / Spotify lists: list rows 56 dp/pt with 40 dp thumbnails and 16 side
  padding; section titles 20 sp; screen titles 24 sp; body 15 sp; pills and buttons 40 dp tall (touch targets stay 44/48);
  covers 6 (thumbs), 8 (cards), 10-12 (heroes); spacing steps 4/8/12/16/24; empty states are an icon and one sentence.
- **Home:** "Shuffle all" pill (getRandomSongs size=200; offline: all downloads shuffled) and "Favorites" pill;
  then horizontal shelves of ~140 dp album cards: Recently played (`recent`), Recently added (`newest`), Most played
  (`frequent`), Favorite albums (`starred`), Rediscover (`random`, size 20). Shelves with nothing are hidden. Pull to refresh.
- **Library:** segmented chips Artists · Albums · Playlists · Songs · Favorites.
  Artists: A–Z index list with artist image circles + album count, with a fast-scroll letter rail.
  Albums: grid of ~100 dp+ cards, 3 across on Android and 2 on iPhone (sort menu: name / artist / recently added / year / most played), paged 60 at a time.
  Playlists: list with cover + song count + duration. Songs: `search3` with empty query paged.
  Favorites: starred artists, albums and songs. **Songs, Favorites' songs, Downloads, search song results and every
  album/playlist/artist page have Play and Shuffle.**
- **Artist:** ~240 dp hero (artist image cropped, darkened gradient into the background color, name over it), action row Play +
  Shuffle + **Download** (all their songs, pinned like an album; ring while downloading, tap again to remove) + Favorite + `…`,
  Top songs (5, expandable), Albums as rows sorted newest first (singles/EPs after albums when `releaseTypes` says so),
  "Similar artists" row, bio from `getArtistInfo2.biography` (HTML stripped, 3 lines, tap to expand).
- **Album:** backdrop = cover's dominant color fading into the background; ~208 dp cover with soft shadow (the first tracks are visible without scrolling on a 6.1" phone); title,
  artist (tappable → artist), meta line `2019 · Synthpop · 8 songs · 34 min`, a quality badge from the songs
  (`FLAC 24/96`, `FLAC 16/44.1`, `MP3 320`, `OPUS`; mixed → highest); action row: Play (filled), Shuffle, Download
  (with ring progress / check when done; tapping it again offers Remove download), Favorite heart, `…` menu (Play next, Add to queue,
  **Repeat album/playlist/artist** = play it with repeat-all on, or stop repeating it, Go to artist). Track list grouped by disc ("Disc 2" headers only
  when >1 disc), row = track number (or animated equalizer bars when it's the playing song), title, featured artists
  when they differ from the album artist, duration, small download state icon. Album notes from `getAlbumInfo2` at
  the bottom if present.
- **Playlist:** like Album, cover = playlist cover; meta = owner · N songs · duration.
- **Search:** a compact field, auto-focused on every visit; results appear while typing (`search3`, 200 ms debounce; a newer
  keystroke cancels the stale request, the previous results stay on screen under a thin progress line, no empty flash).
  Query, results and recents survive visiting a result and coming back. Sections: **Top result** (an exact, else prefix, name
  match), Artists (3, "See all"), Albums (4, "See all"), Songs (5 + "See all", with Play/Shuffle). Matching ignores case and
  accents (and folds ё), so Cyrillic and mixed case work; every word must match. Tapping a song plays it with the song results
  as the queue; the song row menu is there. **Recent searches** (last 10, clearable, tap to reuse, x to forget one) show
  when the field is empty; a search counts once it shows results for 1.5 s, on the keyboard's search key, or when you open
  a result. The keyboard hides when you scroll. Offline: searches downloaded songs, albums and artists locally.
- **Downloads:** storage used at the top; "Downloading" section (current song progress, queued count, pause/resume
  all); then pinned albums/playlists and loose songs; swipe or menu to remove. Empty state explains how to download.
- **Song rows** show their download state (ring while queued/downloading, check when saved). **Swipe a row left** to download it
  (or remove it when saved); the row springs back. **Song row menu** (long-press or `…`; also the `…` in the full player's top
  bar): Play next, Add to queue, Download / Remove download, Favorite/Unfavorite, Go to album, Go to artist, Song info (format,
  bitrate, sample rate, bit depth, size, path, play count).
- **Mini player:** cover thumb, title + artist, play/pause, thin progress line along its bottom edge; swipe left/right
  skips; tap or swipe up expands into the full player.
- **Full player:** background = vertical gradient from the cover's dominant color to near-black, crossfading on
  track change. Large cover (rounded 12, shadow) that shrinks to ~85 % with a spring when paused; swipe the cover
  left/right to skip with a parallax slide. Title (2 lines max) + artist (tappable) + favorite heart; seek bar with
  elapsed/remaining, draggable with haptics; transport: shuffle, previous, play/pause (large, morphing icon), next,
  repeat (off/all/one). Shuffle and repeat show their state with the accent color plus a dot under the icon, and give a short haptic tick.
  The layout is built from the bottom up (controls, seek bar, title) and the cover takes the rest, so it fits a 5.4" phone without clipping. Bottom row: quality badge, Lyrics, Up next, output picker (iOS AVRoutePickerView; Android
  system output switcher intent). Drag down to dismiss.
- **Up next** (sheet from the player, Spotify style): header has Shuffle and Repeat toggles; "Now playing" row; "Next in queue" (songs added with Play
  next / Add to queue) with a Clear button; "Next from <context name>" (the rest of the album/playlist/shuffle).
  Drag handle to reorder, swipe to remove, tap to jump. Shuffle on/off animates the reorder.
- **Lyrics** (sheet/page from the player): synced lyrics with the active line large and bright, others dim, smooth
  auto-scroll; tap a line to seek there. Unsynced lyrics shown plain. "No lyrics" state. Servers without `songLyrics` fall back to the plain legacy `getLyrics`
  (artist + title); nothing is requested until the extension list has loaded.
- **Settings:** grouped cards under small section headers (Playback, Downloads, Offline, Storage, About), a tinted rounded-square
  icon per row, the current value on the right with a chevron for pickers, switches for toggles, and an ⓘ on every row that shows a short
  explanation (sheet or popover). Make the explanation a **required** parameter of the settings-row component so a setting can't
  exist without one. A header card shows the server host, `user · Navidrome version`, an online/offline status dot, and Sign out. The two storage
  rows carry a small bar with their share of Spinwell's own storage. Rows:
  - Server (header card): `user @ host`, version; Sign out (confirm; keeps downloads, which come back on signing in to the same account).
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
- **Repeat** (off → all → one) lives in the full player, the Up next header, and as "Repeat album/playlist/artist" in a container's `…`
  menu. Repeat all loops the whole queue (the context, plus anything queued); repeat one loops the song. Shuffle and repeat are
  saved on every change, independently of the queue, so they survive relaunches and an emptied queue. Android: the lock screen /
  notification / Auto session carries shuffle and repeat buttons in the app's state (media3 custom commands), and a controller
  changing repeat is mirrored into the app. iOS: `MPRemoteCommandCenter` shuffle/repeat commands mirror the same state.
- **Starting playback is cheap:** the player gets just the song to play at once and the rest of the queue in
  chunks of 6 on the following frames (building a 60-song queue in the tap handler held the first frame for ~70 ms on the OnePlus 8, ~250 ms in a debug build; it is now ~12 ms there); queue JSON is written off the
  main thread; the cover (600 and 1200 px) and its color are prefetched when a song is pressed.
- Previous: restarts the song if more than 3 s in, else goes back.
- **Restore on launch:** persist queue + index + position (every 5 s while playing, and on pause/background/track change)
  + `wasPlaying`. On launch load the queue into the player paused at the saved position, immediately, before any
  network call. If `wasPlaying`: open the full player (expanded), and start playing if "Resume playing on open" is on.
  If not playing: just show the mini player.
- **Restore the screen:** the navigation stack (tab, library sub-tab, pushed album/playlist/artist pages) is saved when the
  app stops and restored on launch, so a relaunch lands where you were.
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
  first frame on launch from cache with no spinner. Target cold start < 500 ms on the OnePlus 8 and 60/120 fps scrolling.

## Checks

- Unit tests (Android JVM tests, iOS XCTest target), small: auth token, URL building + iOS transcode rule,
  `smartShuffle` (all songs kept, `first` first, no adjacent same artist when feasible, seed determinism),
  queue ops (play next / add to queue / remove / move / shuffle off restores), download resume (`.part` + Range against a
  tiny local HTTP server), JSON cache key excludes auth.
- Local test server: `tools/fixtures.sh <dir>` builds a tagged library; run Navidrome 0.64.2 on port 4533 against it
  (README "Test server"). Real server: `https://music.icefish-betta.ts.net` (credentials only on the developer's machine).

## Localization

Both apps ship in English plus ar de es fr hi id it ja ko nl pl pt-BR pt-PT ru sv th tr uk vi zh-Hans zh-Hant, from one source table (`tools/l10n/strings.json`, translations in `tools/l10n/tr/`), so meaning and wording match across platforms. Plurals use each language's real forms. Arabic is right-to-left: back arrows and chevrons mirror, the seek bar fills from the right, and nothing in a button, pill or settings row may clip with long German or Russian text (rows wrap, pills take two lines).
