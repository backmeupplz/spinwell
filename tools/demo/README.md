# Demo server (demo.spinwell.app)

A public Navidrome for App Store / Google Play reviewers and for the store screenshots. Everything in it is original: invented
artists, synthesized music and generated cover art (nothing copyrighted, no faces, no real labels).

- **URL:** https://demo.spinwell.app, login `reviewer` / `spinwell-review-2026` (not secret, it goes into the review notes).
  `reviewer` is a non-admin user; `ND_ENABLEUSEREDITING=false` stops it from changing its own password, and it cannot touch the library.
- **Admin:** user `admin`, password in Vaultwarden (OpenClaw Credentials, "Spinwell demo Navidrome admin").
- **Library:** 14 albums, 102 songs, 8 artists (Russian and Japanese ones included), 2 multi-disc albums, 1 compilation, FLAC 24/96,
  FLAC 16/44.1, MP3 and Opus, synced `.lrc` lyrics on 13 tracks, 4 playlists, starred items and play history for `reviewer`. About 180 MB.

## Where it runs

Tugport on hetzner-main, project `spinwell`, service `demo` (`deluan/navidrome:0.64.2`, same version as the real server). Volumes
`spinwell_demo_data` (SQLite, `/data`) and `spinwell_demo_music` (`/music`, read-only in the container). Rollout is `stop_first`
because SQLite must never be opened by two containers. Capped at 1 CPU and 768 MB. DNS: an unproxied A record in the spinwell.app
Cloudflare zone, certificate from Tugport (Let's Encrypt). The config is all `ND_*` env vars on the service: external services off
(no Last.fm/Spotify/Deezer lookups, so no stock photos), sharing off, downloads on, scanner schedule off (trigger a scan after
changing the library), transcoding config locked (the default `aac`, `mp3` and `opus` transcodings are what the iPhone Opus path uses).

**Uptime:** one container on a shared box, no failover; it restarts by itself (Tugport) and survives host reboots. Expect it up
whenever hetzner-main is up. The volumes are not in the Tugport backup jobs, but everything is rebuildable from this folder.

## Rebuild the library

`synth.py` (numpy/scipy synthesizer), `covers.py` (SVG rendered with headless Chrome) and `catalog.py` (artists, albums, lyrics,
playlists) are deterministic. Needs `python3`, `numpy`, `scipy`, `ffmpeg` (libopus, libmp3lame) and Google Chrome:

```sh
python3 -m venv /tmp/v && /tmp/v/bin/pip install numpy scipy
/tmp/v/bin/python tools/demo/build.py /tmp/demo-lib            # ~4 min on 4 cores; --only low-tide ...; --covers-only; --no-covers
```

Test locally: `docker run -p 4540:4533 -v /tmp/demo-lib/Music:/music:ro -e ND_ENABLEEXTERNALSERVICES=false deluan/navidrome:0.64.2`.

## Redeploy / update the server

The Mac's uplink is slow (home exit node), so do not upload audio from the Mac: ship the sources to the server and build there
(covers are small and need Chrome, so upload only those):

```sh
scp tools/demo/{synth,catalog,build,covers}.py root@100.119.195.36:/root/spinwell-build/
ssh root@100.119.195.36 'docker run --rm --cpus 2 -v /root/spinwell-build:/b python:3.12-slim sh -c "apt-get update -qq && apt-get install -y -qq ffmpeg && pip install -q numpy scipy && cd /b && nice -n 19 python build.py /b/out --no-covers --jobs 2"'
rsync -a --include='*/' --include='*.jpg' --exclude='*' <demo-lib>/Music/ root@100.119.195.36:/root/spinwell-build/out/Music/
ssh root@100.119.195.36 'rsync -a --delete /root/spinwell-build/out/Music/ /var/lib/docker/volumes/spinwell_demo_music/_data/'
```

Then scan (as admin, e.g. `POST /rest/startScan?fullScan=true`), and re-seed the reviewer account (also the way to restore it if
someone with the public password wrecks the playlists or stars):

```sh
python3 tools/demo/seed.py https://demo.spinwell.app reviewer spinwell-review-2026 --history   # --history only on a fresh database
tools/demo/verify.sh                                                                          # ping, albums, aac, Range, lyrics, art, lockdown
```

Service changes go through the Tugport API on the server (`/root/.tugport-agent.env`); after changing env, redeploy
(`POST /api/services/<id>/deploy`), since a restart does not load new env.
