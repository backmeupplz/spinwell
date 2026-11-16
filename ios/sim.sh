#!/bin/sh
# usage: ios/sim.sh <name> [launch args...]  -> builds nothing; reinstalls the last Simulator build, launches with args, saves shots/ios/<name>.png (800px)
cd "$(dirname "$0")"
SIM=${SIM:-1BCF52F6-1D84-477C-97C3-BCB1BEAB1E7F}
N=$1; shift
xcrun simctl terminate $SIM com.borodutch.naviplus 2>/dev/null
xcrun simctl install $SIM "$(find build/Build/Products/Debug-iphonesimulator -name NaviPlus.app -maxdepth 1)"
xcrun simctl launch $SIM com.borodutch.naviplus -server http://localhost:4533 -user admin -pass admin "$@" >/dev/null
sleep ${WAIT:-5}
mkdir -p ../shots/ios
xcrun simctl io $SIM screenshot /tmp/naviplus-$N.png >/dev/null 2>&1
sips -Z 800 /tmp/naviplus-$N.png --out ../shots/ios/$N.png >/dev/null
