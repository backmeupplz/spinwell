# Spinwell for agents

- SPEC.md is the contract for both apps; change it in the same commit when behavior changes.
- Android lives in `app/` (package com.borodutch.spinwell), iPhone in `ios/`. Sister app with the patterns we copy: `~/code/absplus`.
- Context rules: grep, then read line ranges; never cat whole big files or full build logs (`| tail -40`, `-quiet`).
  Screenshots only to check a screen you changed, resized small (`sips -Z 800`), not after every step.
- Android: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew testDebugUnitTest assembleDebug`.
- Local test server: README "Test server" (admin/admin on :4533).
- UI strings: one table for both apps. Add or change text in `tools/l10n/strings.json` (English + comment) and every language in `tools/l10n/tr/<locale>.json`, then run `python3 tools/l10n/gen.py`; it writes the Android `values*/strings.xml` and the iOS `Localizable.xcstrings`/`InfoPlist.xcstrings` (never edit those by hand). iOS uses the English text as the key (`Text("...")`, `String(localized:)`); `python3 tools/l10n/check_ios.py` flags literals missing from the table. Small caps labels use `caps()` so Arabic/Hindi/Thai are not letter-spaced.
