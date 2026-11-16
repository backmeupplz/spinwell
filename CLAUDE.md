# Navi+ for agents

- SPEC.md is the contract for both apps; change it in the same commit when behavior changes.
- Android lives in `app/` (package com.borodutch.naviplus), iPhone in `ios/`. Sister app with the patterns we copy: `~/code/absplus`.
- Context rules: grep, then read line ranges; never cat whole big files or full build logs (`| tail -40`, `-quiet`).
  Screenshots only to check a screen you changed, resized small (`sips -Z 800`), not after every step.
- Android: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew testDebugUnitTest assembleDebug`.
- Local test server: README "Test server" (admin/admin on :4533).
