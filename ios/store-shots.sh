#!/bin/sh
# Takes the six App Store screenshots in every app language on one simulator, from the demo server's original library
# (https://demo.spinwell.app, reviewer / spinwell-review-2026), into $OUT/<language>/<n>-<name>.png. Uses the Debug app's
# launch hooks (Hooks.swift), so no UI test is needed and nothing depends on translated labels.
# scripts/shots/compose_ios.py then puts them under each locale's captions.
#   DEVICE    simulator udid (default: the booted iPhone 18 Pro Max, for the 6.9" set)
#   LANGS     languages to take, e.g. "de ar" (default: all 22)
#   OUT       output folder (default ./store-shots)
#   DOWNLOAD  1 = sign in fresh (reinstall) and download the seven albums the Downloads shot lists; takes ~10 minutes.
#             Without it the install must already hold those downloads.
set -e
cd "$(dirname "$0")"
DEVICE="${DEVICE:-$(xcrun simctl list devices booted | sed -n 's/.*iPhone 18 Pro Max (\([0-9A-F-]*\)) (Booted).*/\1/p' | head -1)}"
[ -n "$DEVICE" ] || { echo "Boot an iPhone 18 Pro Max simulator or set DEVICE" >&2; exit 1; }
LANGS="${LANGS:-en ar de es fr hi id it ja ko nl pl pt-BR pt-PT ru sv th tr uk vi zh-Hans zh-Hant}"
OUT="$(mkdir -p "${OUT:-store-shots}" && cd "${OUT:-store-shots}" && pwd)"
ID=com.borodutch.spinwell
LOWTIDE=5YYMdheGwJ2MTdrbJOcNbb  # Low Tide Atlas, FLAC 24/96: album and player
CEDAR=423Bg3ygv9koLIoYYIW7XC    # Salt and Cedar: "Little Boat" has synced lyrics
ALBUMS="$LOWTIDE 6ZoCJGUfpDMrfVpDudhIAq $CEDAR 0ZC61xW9wPth8IVk7sslER 66bCpEAmkhc0W8YmFjGT16 0LYfDtynTub3aVtzcIdbA2 4VVLv9yhnyCzQn3ty80O1T"
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl status_bar "$DEVICE" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 --cellularMode active \
  --cellularBars 4 --batteryState charged --batteryLevel 100
xcodebuild -project Spinwell.xcodeproj -scheme Spinwell -destination "id=$DEVICE" -derivedDataPath build build > "$OUT/build.log" 2>&1 || { tail -30 "$OUT/build.log"; exit 1; }
APP="$(find build/Build/Products/Debug-iphonesimulator -name Spinwell.app -maxdepth 1)"
run() { xcrun simctl terminate "$DEVICE" $ID 2>/dev/null || true; xcrun simctl launch "$DEVICE" $ID "$@" > /dev/null; }
shot() { sleep "$1"; xcrun simctl io "$DEVICE" screenshot "$2" > /dev/null 2>&1; }
# the last song stays loaded but paused, so no language starts with the full player restored over its first screen
settle() { run -playalbum $LOWTIDE -startat 1 -paused 1 "$@"; sleep 6; }
if [ "$DOWNLOAD" = 1 ]; then
  xcrun simctl uninstall "$DEVICE" $ID 2>/dev/null || true
  xcrun simctl install "$DEVICE" "$APP"
  run -signout 1 -server https://demo.spinwell.app -user reviewer -pass spinwell-review-2026; sleep 8
  for a in $ALBUMS; do run -download $a -tab downloads; sleep 15; done
  sleep 240 # an interrupted download resumes, but not while the app is killed
  run -tab downloads; sleep 120
else
  xcrun simctl install "$DEVICE" "$APP"
fi
for lang in $LANGS; do
  loc="$(echo "$lang" | sed -e 's/^pt-BR$/pt_BR/;s/^pt-PT$/pt_PT/;s/^zh-Hans$/zh_CN/;s/^zh-Hant$/zh_TW/;s/^ar$/ar_SA/;s/^en$/en_US/;s/^es$/es_ES/;s/^fr$/fr_FR/;s/^de$/de_DE/;s/^hi$/hi_IN/;s/^id$/id_ID/;s/^it$/it_IT/;s/^ja$/ja_JP/;s/^ko$/ko_KR/;s/^nl$/nl_NL/;s/^pl$/pl_PL/;s/^ru$/ru_RU/;s/^sv$/sv_SE/;s/^th$/th_TH/;s/^tr$/tr_TR/;s/^uk$/uk_UA/;s/^vi$/vi_VN/')"
  L="-AppleLanguages ($lang) -AppleLocale $loc"
  mkdir -p "$OUT/$lang"
  settle $L
  run $L -tab home;                                    shot 14 "$OUT/$lang/1-home.png"
  run $L -tab library -libtab albums;                  shot 14 "$OUT/$lang/2-library.png"
  run $L -open album:$LOWTIDE;                         shot 12 "$OUT/$lang/3-album.png"
  run $L -playalbum $LOWTIDE -startat 1 -player 1;     shot 14 "$OUT/$lang/4-player.png"
  run $L -playalbum $CEDAR -startat 1 -lyrics 1;       shot 22 "$OUT/$lang/5-lyrics.png"
  settle $L
  run $L -tab downloads;                               shot 10  "$OUT/$lang/6-downloads.png"
  echo "$lang: $(ls "$OUT/$lang" | wc -l | tr -d ' ') shots"
done
settle
