#!/bin/sh
# Smoke-tests the demo server as the public reviewer account.
# usage: tools/demo/verify.sh [BASE_URL] [USER] [PASSWORD]
set -e
BASE=${1:-https://demo.spinwell.app}; U=${2:-reviewer}; P=${3:-spinwell-review-2026}
Q="u=$U&p=$P&v=1.16.1&c=verify&f=json"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
ok() { echo "ok   $*"; }; bad() { echo "FAIL $*"; exit 1; }
json() { curl -fsS "$BASE/rest/$1?$Q${2:+&$2}"; }
py() { python3 -c "import sys,json;d=json.load(sys.stdin)['subsonic-response'];$1"; }

json ping | py "assert d['status']=='ok';print(d['serverVersion'])" >/dev/null && ok ping
n=$(json getAlbumList2 "type=alphabeticalByName&size=500" | py "print(len(d['albumList2']['album']))"); [ "$n" -ge 14 ] && ok "$n albums" || bad "albums=$n"
# opus source transcoded to aac: must really be AAC (ADTS) bytes
oid=$(json search3 "query=%D0%9F%D0%B8%D1%81%D1%8C%D0%BC%D0%BE&songCount=1" | py "print(d['searchResult3']['song'][0]['id'])")
curl -fsS "$BASE/rest/stream?$Q&id=$oid&format=aac&maxBitRate=128" -o "$tmp/s.aac" --max-time 60
codec=$(ffprobe -v error -show_entries stream=codec_name -of csv=p=0 "$tmp/s.aac"); [ "$codec" = aac ] && ok "stream format=aac (opus source) -> $codec" || bad "aac stream codec=$codec"
# range download of the 24/96 FLAC
fid=$(json search3 "query=Salt%20Flat&songCount=1" | py "print(d['searchResult3']['song'][0]['id'])")
json getSong "id=$fid" | py "s=d['song'];assert s['bitDepth']==24 and s['samplingRate']==96000,s" && ok "flac 24/96"
code=$(curl -s -o "$tmp/r.bin" -D "$tmp/h" -w "%{http_code}" -H "Range: bytes=0-1023" "$BASE/rest/download?$Q&id=$fid")
grep -qi '^content-range:' "$tmp/h" && [ "$code" = 206 ] && ok "download Range -> 206" || bad "download range code=$code"
lid=$(json search3 "query=%D0%A2%D0%B8%D1%85%D0%B0%D1%8F&songCount=1" | py "print(d['searchResult3']['song'][0]['id'])")
json getLyricsBySongId "id=$lid" | py "l=d['lyricsList']['structuredLyrics'][0];assert l['synced'] and len(l['line'])>3" && ok "synced lyrics"
json getArtists | py "a=[x for i in d['artists']['index'] for x in i['artist']];assert len(a)>=8;print(len(a))" >/dev/null && ok "artists"
aid=$(json getArtists | py "print([x for i in d['artists']['index'] for x in i['artist'] if x['name']=='Orla Voss'][0]['coverArt'])")
curl -fsS "$BASE/rest/getCoverArt?$Q&id=$aid&size=300" -o "$tmp/a.jpg" && [ "$(file -b --mime-type "$tmp/a.jpg")" = image/jpeg ] && ok "artist image (jpeg)" || bad "artist image"
json getPlaylists | py "assert len(d['playlists']['playlist'])>=4" && ok playlists
json getStarred2 | py "assert len(d['starred2']['song'])>=8" && ok starred
json getAlbumList2 "type=recent&size=10" | py "assert len(d['albumList2']['album'])>=5" && ok "recently played"
json getAlbumList2 "type=frequent&size=10" | py "assert len(d['albumList2']['album'])>=5" && ok "most played"
# lockdown: non-admin must not manage users or change its own password
login=$(curl -fsS -X POST "$BASE/auth/login" -H 'content-type: application/json' -d "{\"username\":\"$U\",\"password\":\"$P\"}")
tok=$(echo "$login" | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])"); uid=$(echo "$login" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
c1=$(curl -s -o /dev/null -w "%{http_code}" -H "x-nd-authorization: Bearer $tok" -X POST "$BASE/api/user" -H 'content-type: application/json' -d '{"userName":"x","password":"xxxxxxxx","isAdmin":false}')
[ "$c1" = 403 ] && ok "reviewer cannot create users (403)" || bad "create user code=$c1"
c2=$(curl -s -o /dev/null -w "%{http_code}" -H "x-nd-authorization: Bearer $tok" -X PUT "$BASE/api/user/$uid" -H 'content-type: application/json' -d "{\"password\":\"hacked-hacked\",\"currentPassword\":\"$P\"}")
c3=$(curl -s -o /dev/null -w "%{http_code}" -H "x-nd-authorization: Bearer $tok" -X DELETE "$BASE/api/song/$fid")
echo "info password change -> $c2, delete song -> $c3 (expect non-2xx)"
case $c2$c3 in 2*) bad "reviewer could modify state";; esac
curl -fsS "$BASE/rest/ping?u=$U&p=$P&v=1.16.1&c=verify&f=json" >/dev/null && ok "password unchanged"
echo "all checks passed"
