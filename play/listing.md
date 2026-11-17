# Store listing (en-US)

Google Play's English texts. The other languages live in `fastlane/metadata/android/<Play code>/` (title.txt, short_description.txt,
full_description.txt, changelogs/<versionCode>.txt), and `scripts/store-copy.mjs` checks every limit; the App Store texts are in
`appstore/`. The research behind every language's words (who owns each search in each storefront, and why the name, subtitle and keywords
are what they are) is in the private Prime Radiant repository, `projects/spinwell/drafts/aso-<locale>.md`.

## App name (max 30)
Spinwell for Navidrome

## Short description (max 80)
A fast, beautiful music player for your own Navidrome or OpenSubsonic server.

## Full description (max 4000)
See `full-description.txt` (the file the store upload reads, linked into `fastlane/metadata/android/en-US/`).

## Category
Music & Audio

## Tags (pick in console)
Music player, Music streaming, Offline music

## Contact email
Set in Play Console (the developer account's address).

## Website and privacy policy
- Website: https://spinwell.app
- Privacy policy: https://spinwell.app/privacy/

## Release notes, 1.0.0
`fastlane/metadata/android/<language>/changelogs/1.txt` (versionCode 1): the same text becomes the App Store's "What's New", so it never names a platform.

## Notes
- The name carries "for Navidrome" because that is how people search; every description says Spinwell is independent and not affiliated with the Navidrome project.
- Screenshot order, shared by both stores: 1 Home, 2 Library, 3 Album, 4 Player, 5 Lyrics, 6 Downloads (the captions in `appstore/<locale>/listing.md` follow it).
