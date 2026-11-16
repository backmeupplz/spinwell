#!/bin/sh
# usage: ios/dev.sh [launch args...]  -> signed Debug build to the paired iPhone, install, launch (needs the devtest keychain unlocked)
cd "$(dirname "$0")"
UDID=${UDID:-00008110-001865590E7A401E}
security unlock-keychain -p "$(cat ~/.config/absplus-devtest-keychain.pw)" ~/Library/Keychains/absplus-devtest.keychain-db
xcodebuild -quiet -project Spinwell.xcodeproj -scheme Spinwell -destination id=$UDID -derivedDataPath build -allowProvisioningUpdates \
  -authenticationKeyPath ~/.appstoreconnect/private_keys/AuthKey_3FT23S9NSM.p8 -authenticationKeyID 3FT23S9NSM \
  -authenticationKeyIssuerID a18f42ff-6c44-410c-badf-50b11de8dbb8 DEVELOPMENT_TEAM=ACWP4F58HZ CODE_SIGN_STYLE=Automatic CODE_SIGN_IDENTITY="Apple Development" build 2>&1 | tail -15
xcrun devicectl device install app --device $UDID build/Build/Products/Debug-iphoneos/Spinwell.app 2>&1 | grep -E "error|installed" 
xcrun devicectl device process launch --device $UDID --terminate-existing com.borodutch.spinwell -- "$@" 2>&1 | tail -3
