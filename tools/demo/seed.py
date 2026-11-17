#!/usr/bin/env python3
"""Re-runnable: gives the demo `reviewer` account its playlists, stars, ratings and play history (Home shelves).
usage: seed.py [BASE_URL] [USER] [PASSWORD] [--history]   (defaults: https://demo.spinwell.app reviewer spinwell-review-2026)
Playlists and stars are reset on every run. Play history is only added with --history (scrobbles can't be undone through the API)."""
import hashlib, json, os, random, sys, time, urllib.parse, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog

args = [a for a in sys.argv[1:] if not a.startswith("--")]
BASE, USER, PW = (args + ["https://demo.spinwell.app", "reviewer", "spinwell-review-2026"][len(args):])[:3]


def api(method, **p):
    salt = "%08x" % random.getrandbits(32)
    q = [("u", USER), ("t", hashlib.md5((PW + salt).encode()).hexdigest()), ("s", salt), ("v", "1.16.1"), ("c", "seed"), ("f", "json")]
    for k, v in p.items():
        q += [(k, x) for x in (v if isinstance(v, list) else [v])]
    r = json.load(urllib.request.urlopen(f"{BASE}/rest/{method}?" + urllib.parse.urlencode(q), timeout=60))["subsonic-response"]
    assert r["status"] == "ok", (method, r)
    return r


albums = {}
off = 0
while True:
    page = api("getAlbumList2", type="alphabeticalByName", size=100, offset=off).get("albumList2", {}).get("album", [])
    for a in page:
        albums[a["name"]] = a["id"]
    if len(page) < 100:
        break
    off += 100
song, artist_id = {}, {}
for al in catalog.ALBUMS + [catalog.SAMPLER]:
    songs = api("getAlbum", id=albums[al["title"]])["album"]["song"]
    songs.sort(key=lambda s: (s.get("discNumber", 1), s.get("track", 0)))
    for i, s in enumerate(songs, 1):
        song[(al["id"], i)] = s["id"]
    if "artist" in al:
        artist_id[al["artist"]] = songs[0]["artistId"]
album_id = {al["id"]: albums[al["title"]] for al in catalog.ALBUMS + [catalog.SAMPLER]}
print(f"resolved {len(song)} songs in {len(album_id)} albums")

# playlists: replace
for pl in api("getPlaylists").get("playlists", {}).get("playlist", []):
    api("deletePlaylist", id=pl["id"])
for name, items in catalog.PLAYLISTS.items():
    api("createPlaylist", name=name, songId=[song[k] for k in items])
print("playlists:", list(catalog.PLAYLISTS))

# stars: reset
st = api("getStarred2").get("starred2", {})
for kind, key in (("id", "song"), ("albumId", "album"), ("artistId", "artist")):
    ids = [x["id"] for x in st.get(key, [])]
    if ids:
        api("unstar", **{kind: ids})
api("star", id=[song[k] for k in catalog.STARRED], albumId=[album_id[a] for a in catalog.STARRED_ALBUMS], artistId=[artist_id[a] for a in catalog.STARRED_ARTISTS])
for k, rate in zip(catalog.STARRED, (5, 5, 4, 5, 4, 4, 5, 3, 4, 5)):
    api("setRating", id=song[k], rating=rate)
print("starred", len(catalog.STARRED), "songs,", len(catalog.STARRED_ALBUMS), "albums,", len(catalog.STARRED_ARTISTS), "artists")

if "--history" in sys.argv:
    rng = random.Random(7)
    keys = list(song)
    fav = [("neon-orchard", 2), ("low-tide", 1), ("quiet-water", 2), ("salt-cedar", 3), ("neon-highway", 2), ("blue-hour", 1)]
    now = time.time()
    plays = []
    for k in keys:  # a long tail of 1-3 plays spread over three weeks
        if rng.random() < 0.55:
            plays += [(k, now - rng.uniform(1, 21) * 86400)] * 1
            plays += [(k, now - rng.uniform(1, 21) * 86400) for _ in range(rng.randint(0, 2))]
    for i, k in enumerate(fav):  # most played
        plays += [(k, now - rng.uniform(0.2, 14) * 86400) for _ in range(9 - i)]
    recent = [("neon-orchard", 5), ("estaciones", 1), ("after-rain", 1), ("blue-hour", 4), ("fieldnotes", 2), ("november", 1), ("concrete", 1), ("low-tide", 3), ("sunday-brushes", 2), ("winter-orchard", 1)]
    plays += [(k, now - (len(recent) - i) * 25 * 60) for i, k in enumerate(recent)]  # distinct albums in the last few hours
    for k, t in sorted(plays, key=lambda p: p[1]):
        api("scrobble", id=song[k], time=int(t * 1000), submission="true")
    print("scrobbled", len(plays), "plays")
