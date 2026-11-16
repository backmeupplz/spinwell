# Navi+

Navi+ is a small, fast player for your own [Navidrome](https://www.navidrome.org) (or any OpenSubsonic) server, native on Android and iPhone. Browse artists, albums, playlists and favorites, download music for offline listening, and pick up exactly where you left off. Free and open source, no ads, no analytics. It's an independent app, not affiliated with the Navidrome project.

What the apps do and how they behave is in [SPEC.md](SPEC.md).

## Build

Android (JDK 21 + Android SDK): `./gradlew testDebugUnitTest assembleDebug`

iPhone (Xcode 27): `xcodebuild -project ios/NaviPlus.xcodeproj -scheme NaviPlus -destination 'generic/platform=iOS Simulator' build`

## Test server

`tools/fixtures.sh <dir>` makes a small tagged library (several artists, multi-disc, FLAC 24/96, Opus, AAC, MP3, covers, synced lyrics). Run Navidrome 0.64.2 against it:

```sh
ND_MUSICFOLDER=<dir> ND_DATAFOLDER=<data> ND_ADDRESS=0.0.0.0 ND_PORT=4533 navidrome &
curl -X POST localhost:4533/auth/createAdmin -H 'content-type: application/json' -d '{"username":"admin","password":"admin"}'
```

The Android emulator reaches it at `http://10.0.2.2:4533`, the iOS Simulator at `http://localhost:4533`.
