# Navi+ spec

Navi+ (working name) is a small, fast, beautiful Navidrome / OpenSubsonic player, native on both phones:
Android at the repo root (`app/`, Kotlin, Jetpack Compose + Material 3, media3) and iPhone in `ios/` (SwiftUI, AVFoundation).
Both apps follow this document; when a platform has to differ, it says so here. Sister app: ABS+ (`~/code/absplus`),
whose project layout, cover cache and download design we reuse.

- Android: package `com.borodutch.naviplus`, minSdk 29, target/compile 36, AGP/Gradle versions as ABS+. Dependencies:
  Compose BOM + material3 + activity-compose + navigation-compose (or hand-rolled nav), media3-exoplayer +
  media3-session. Nothing else (no Coil, no Retrofit, no Room, no kotlinx-serialization: `org.json` + `HttpURLConnection`
  are enough). R8 on for release.
- iOS: bundle `com.borodutch.naviplus`, team `ACWP4F58HZ`, iOS 26.1+, iPhone only, Swift 5 mode, no packages.
  Project file like ABS+ (`PBXFileSystemSynchronizedRootGroup`, so new files need no pbxproj edits). Info.plist:
  `UIBackgroundModes = [audio]`, `NSAllowsArbitraryLoads = true` (home servers are often plain http on the LAN).
- English only for now. Dark theme only (music app; art carries the color).

## Server API (OpenSubsonic, tested against Navidrome 0.64.2)

- Base `<server>/rest/<method>?u=<user>&t=<md5(password+salt)>&s=<salt>&v=1.16.1&c=NaviPlus&f=json`.
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
  On launch and when the network changes: `ping` with a 4 s timeout. Failure → offline. Any later successful request → online.
- Offline: a slim banner under the top bar ("Offline · showing downloads", tap to retry). Browsing uses the JSON cache.
  Songs that aren't downloaded are dimmed and can't be played; albums/playlists with no downloaded songs are dimmed.
  The Downloads tab works fully. Play queue skips non-downloaded songs.
- Manual "Offline mode" setting forces offline (no network calls at all, not even covers not on disk).

## Screens

Bottom navigation with 4 tabs: **Home**, **Library**, **Search**, **Downloads**. The mini player floats above the tab bar
on every screen. Settings open from a gear on Home.

- **Sign in:** app name + glyph, fields Server (placeholder `https://music.example.ts.net`), Username, Password
  (masked), Sign in button with progress; clear error text (unreachable / wrong password / not a Subsonic server).
- **Home:** big "Shuffle all" pill (getRandomSongs size=200; offline: all downloads shuffled) and "Favorites" pill;
  then horizontal shelves of album cards: Recently played (`recent`), Recently added (`newest`), Most played
  (`frequent`), Favorite albums (`starred`), Rediscover (`random`, size 20). Shelves with nothing are hidden. Pull to refresh.
- **Library:** segmented chips Artists · Albums · Playlists · Songs · Favorites.
  Artists: A–Z index list with artist image circles + album count, with a fast-scroll letter rail.
  Albums: 2-column grid (sort menu: name / artist / recently added / year / most played), paged 60 at a time.
  Playlists: list with cover + song count + duration. Songs: `search3` with empty query paged.
  Favorites: starred artists, albums and songs.
- **Artist:** hero (artist image, darkened gradient into the background color, name over it), Play + Shuffle buttons,
  Top songs (5, expandable), Albums grid sorted newest first (singles/EPs after albums when `releaseTypes` says so),
  "Similar artists" row, bio from `getArtistInfo2.biography` (HTML stripped, 3 lines, tap to expand).
- **Album:** backdrop = cover's dominant color fading into the background; large cover with soft shadow; title,
  artist (tappable → artist), meta line `2019 · Synthpop · 8 songs · 34 min`, a quality badge from the songs
  (`FLAC 24/96`, `FLAC 16/44.1`, `MP3 320`, `OPUS`; mixed → highest); action row: Play (filled), Shuffle, Download
  (with ring progress / check when done), Favorite heart, `…` menu. Track list grouped by disc ("Disc 2" headers only
  when >1 disc), row = track number (or animated equalizer bars when it's the playing song), title, featured artists
  when they differ from the album artist, duration, small download state icon. Album notes from `getAlbumInfo2` at
  the bottom if present.
- **Playlist:** like Album, cover = playlist cover; meta = owner · N songs · duration.
- **Search:** field auto-focused, `search3` debounced 250 ms, sections Artists (row), Albums (row), Songs (list).
  Offline: searches downloaded songs locally.
- **Downloads:** storage used at the top; "Downloading" section (current song progress, queued count, pause/resume
  all); then pinned albums/playlists and loose songs; swipe or menu to remove. Empty state explains how to download.
- **Song row menu** (long-press or `…`): Play next, Add to queue, Go to album, Go to artist, Favorite/Unfavorite,
  Download / Remove download, Song info (format, bitrate, sample rate, bit depth, size, path, play count).
- **Mini player:** cover thumb, title + artist, play/pause, thin progress line along its bottom edge; swipe left/right
  skips; tap or swipe up expands into the full player.
- **Full player:** background = vertical gradient from the cover's dominant color to near-black, crossfading on
  track change. Large cover (rounded 12, shadow) that shrinks to ~85 % with a spring when paused; swipe the cover
  left/right to skip with a parallax slide. Title (2 lines max) + artist (tappable) + favorite heart; seek bar with
  elapsed/remaining, draggable with haptics; transport: shuffle, previous, play/pause (large, morphing icon), next,
  repeat (off/all/one). Bottom row: quality badge, Lyrics, Up next, output picker (iOS AVRoutePickerView; Android
  system output switcher intent). Drag down to dismiss.
- **Up next** (sheet from the player, Spotify style): "Now playing" row; "Next in queue" (songs added with Play
  next / Add to queue) with a Clear button; "Next from <context name>" (the rest of the album/playlist/shuffle).
  Drag handle to reorder, swipe to remove, tap to jump. Shuffle on/off animates the reorder.
- **Lyrics** (sheet/page from the player): synced lyrics with the active line large and bright, others dim, smooth
  auto-scroll; tap a line to seek there. Unsynced lyrics shown plain. "No lyrics" state.
- **Settings:** every row has an ⓘ button that shows a short explanation (sheet or popover). Make the explanation a
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
  first frame on launch from cache with no spinner. Target cold start < 500 ms on the OnePlus 8 and 60/120 fps scrolling.

## Checks

- Unit tests (Android JVM tests, iOS XCTest target), small: auth token, URL building + iOS transcode rule,
  `smartShuffle` (all songs kept, `first` first, no adjacent same artist when feasible, seed determinism),
  queue ops (play next / add to queue / remove / move / shuffle off restores), download resume (`.part` + Range against a
  tiny local HTTP server), JSON cache key excludes auth.
- Local test server: `tools/fixtures.sh <dir>` builds a tagged library; run Navidrome 0.64.2 on port 4533 against it
  (README "Test server"). Real server: `https://music.icefish-betta.ts.net` (credentials only on the developer's machine).
