#!/bin/sh
# Builds a small tagged test library (sine tones, gradient covers, mixed formats) for a local Navidrome.
# usage: tools/fixtures.sh <music dir>
set -e
out=${1:?music dir}
mkdir -p "$out"
n=0
album() { # artist album year genre ext cover_hue tracks [discs]
  ar=$1; al=$2; y=$3; g=$4; ext=$5; hue=$6; tracks=$7; discs=${8:-1}
  d="$out/$ar/$al"; mkdir -p "$d"
  ffmpeg -loglevel error -y -f lavfi -i "gradients=s=1200x1200:c0=0x$hue:c1=0x101018:x0=0:y0=0:x1=1200:y1=1200:d=1" -frames:v 1 "$d/cover.jpg"
  disc=1
  while [ $disc -le $discs ]; do
    t=1
    while [ $t -le $tracks ]; do
      n=$((n+1)); f=$((200 + n * 17))
      name="$(printf %d-%02d $disc $t) Song $t of $al"
      case $ext in
        flac) codec="-c:a flac -sample_fmt s32 -ar 96000" ;;
        opus) codec="-c:a libopus -b:a 96k" ;;
        m4a)  codec="-c:a aac -b:a 192k" ;;
        *)    codec="-c:a libmp3lame -b:a 192k" ;;
      esac
      ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=$f:duration=$((20 + t * 3))" $codec \
        -metadata title="$(echo "$ar $al" | cut -c1-3) Track $t" -metadata artist="$ar" -metadata album_artist="$ar" \
        -metadata album="$al" -metadata date=$y -metadata genre="$g" -metadata track=$t/$tracks -metadata disc=$disc/$discs \
        "$d/$name.$ext"
      t=$((t+1))
    done
    disc=$((disc+1))
  done
}
album "Aurora Vale" "Glass Horizon" 2019 "Synthpop" mp3 ff6a3d 8
album "Aurora Vale" "Night Swimmers" 2022 "Synthpop" flac 3d8bff 6
album "The Lanterns" "Paper Boats" 2015 "Indie Rock" mp3 f2c14e 10 2
album "The Lanterns" "Low Tide EP" 2017 "Indie Rock" opus 2ec4b6 4
album "Mira Okafor" "Salt & Cedar" 2021 "Jazz" m4a c86bfa 7
album "Kobalt Echo" "Signal Lost" 2024 "Electronic" mp3 ff3d8b 9
album "Kobalt Echo" "Remixes, Vol. 1" 2025 "Electronic" flac 7cff6a 5
# synced lyrics sidecar for one track
cat > "$out/Aurora Vale/Glass Horizon/1-01 Song 1 of Glass Horizon.lrc" <<'LRC'
[00:00.50]Light through the glass horizon
[00:05.00]We were running out of time
[00:10.00]Every window was a river
[00:15.00]Every river was a line
LRC
echo "$n tracks in $out"
