#!/usr/bin/env python3
"""Builds the Spinwell demo library: python3 build.py OUT_DIR [--only album-id ...] [--jobs N] [--no-covers]
Deterministic: same output every run. Needs numpy, scipy, ffmpeg and Google Chrome."""
import argparse, os, random, subprocess, sys, zlib
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog, covers, synth

CODEC = {
    "flac24": (96000, ["-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24"], "flac"),
    "flac16": (44100, ["-c:a", "flac", "-sample_fmt", "s16"], "flac"),
    "mp3": (44100, ["-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3"], "mp3"),
    "opus": (48000, ["-c:a", "libopus", "-b:a", "112k"], "opus"),
}


def lrc(lines, dur):
    t0, step = min(8.0, dur * 0.15), (dur - 4 - min(8.0, dur * 0.15)) / len(lines)
    return "\n".join(f"[{int((t0 + i * step) // 60):02d}:{(t0 + i * step) % 60:05.2f}]{l}" for i, l in enumerate(lines)) + "\n"


def job(j):
    path, style, seed, bpm, secs, mode, root, bpb, fmt, tags, lyrics = j
    sr, codec, _ = CODEC[fmt]
    audio = synth.render(style, seed, bpm, secs, sr=sr, mode=mode, root=root, bpb=bpb)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    meta = [x for k, v in tags.items() for x in ("-metadata", f"{k}={v}")]
    p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(sr), "-ac", "2", "-i", "-", *codec, *meta, path], stdin=subprocess.PIPE)
    p.communicate(audio.tobytes())
    assert p.returncode == 0, path
    if lyrics:
        open(os.path.splitext(path)[0] + ".lrc", "w").write(lrc(lyrics, len(audio) / sr))
    return path


def plan(only):
    jobs = []
    A = catalog.ARTISTS
    for al in catalog.ALBUMS + [catalog.SAMPLER]:
        if only and al["id"] not in only:
            continue
        comp = al["id"] == "sampler"
        folder = os.path.join("Various Artists" if comp else A[al["artist"]]["dir"], al["dir"])
        discs = al.get("discs", [len(al["tracks"])])
        n_tracks = len(al["tracks"])
        idx = 0
        for d, per in enumerate(discs, 1):
            for tn in range(1, per + 1):
                item = al["tracks"][idx]
                idx += 1
                rng = random.Random(zlib.crc32(f"{al['id']}-{idx}".encode()))
                if comp:
                    title, ak, style, mode, bpm, bpb = item
                    secs = rng.uniform(40, 56)
                    fmt, artist = "mp3", A[ak]["name"]
                    genre = A[ak]["genre"]
                else:
                    title, style, mode = item, al["style"], rng.choice(al["modes"])
                    bpm, bpb = rng.randint(*al["bpm"]), al.get("bpb", 4)
                    secs, fmt, artist = rng.uniform(*al["secs"]), al["fmt"], A[al["artist"]]["name"]
                    genre = A[al["artist"]]["genre"]
                tags = dict(title=title, artist=artist, album_artist="Various Artists" if comp else artist, album=al["title"], date=al["year"],
                            genre=genre, track=f"{tn}/{per}", disc=f"{d}/{len(discs)}")
                if comp:
                    tags["compilation"] = 1
                ext = CODEC[fmt][2]
                path = os.path.join(OUT, "Music", folder, f"{d}-{tn:02d}.{ext}")
                jobs.append((path, style, rng.randrange(1 << 30), bpm, secs, mode, rng.choice([43, 45, 46, 48, 50]), bpb, fmt, tags, catalog.LYRICS.get((al["id"], idx))))
    return jobs


def make_covers(only):
    A, svgs, outs = catalog.ARTISTS, [], []
    for al in catalog.ALBUMS + [catalog.SAMPLER]:
        if only and al["id"] not in only:
            continue
        comp = al["id"] == "sampler"
        folder = os.path.join(OUT, "Music", "Various Artists" if comp else A[al["artist"]]["dir"], al["dir"])
        svgs.append(covers.svg(al["cover"], al["title"], "Various Artists" if comp else A[al["artist"]]["name"], seed=zlib.crc32(al["id"].encode()) % 997))
        outs.append(os.path.join(folder, "cover.jpg"))
    if not only:
        art = dict(kestrel=("aurora", 1.5, 600, 500), nova=("gridsun", 1.7, 600, 520), lanterns=("papercut", 1.6, 720, 560), marlo=("blocks", 1.5, 800, 420),
                   daria=("moon", 1.7, 600, 420), yakou=("rain", 1.1, 600, 600), orla=("rings", 1.5, 600, 540), tomas=("tiles", 1.8, 600, 600))
        for k, (st, z, cx, cy) in art.items():
            svgs.append(covers.svg(st, "", "", seed=zlib.crc32(k.encode()) % 991 + 7, show=False, zoom=z, cx=cx, cy=cy))
            outs.append(os.path.join(OUT, "Music", A[k]["dir"], "artist.jpg"))
    covers.render(svgs, outs)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--no-covers", action="store_true")
    ap.add_argument("--covers-only", action="store_true")
    a = ap.parse_args()
    OUT = os.path.abspath(a.out)
    jobs = [] if a.covers_only else plan(a.only)
    with Pool(a.jobs) as p:
        for i, done in enumerate(p.imap_unordered(job, jobs), 1):
            print(f"[{i}/{len(jobs)}] {os.path.relpath(done, OUT)}", flush=True)
    if not a.no_covers:
        make_covers(a.only)
    print("done:", len(jobs), "tracks")
