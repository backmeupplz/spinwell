"""Procedural, original music for the demo library. numpy + scipy only. Deterministic per (style, seed)."""
import numpy as np
from scipy import signal

TAU = 2 * np.pi
MODES = {"major": [0, 2, 4, 5, 7, 9, 11], "minor": [0, 2, 3, 5, 7, 8, 10], "dorian": [0, 2, 3, 5, 7, 9, 10], "lydian": [0, 2, 4, 6, 7, 9, 11]}
PROGS = {
    "major": [[0, 5, 3, 4], [0, 3, 5, 4], [0, 4, 5, 3], [5, 3, 0, 4], [0, 2, 3, 4], [3, 4, 2, 5]],
    "minor": [[0, 5, 2, 6], [0, 3, 6, 2], [0, 5, 3, 4], [0, 6, 5, 4], [5, 6, 0, 0], [0, 2, 3, 6]],
    "dorian": [[0, 3, 0, 6], [0, 1, 3, 0], [0, 3, 6, 0]],
    "lydian": [[0, 1, 0, 4], [0, 4, 1, 0], [0, 2, 1, 4]],
}


def mtof(m):
    return 440.0 * 2 ** ((m - 69) / 12)


class Mix:
    """Two buses (dry, ducked) plus reverb send; time-positioned stereo adds."""

    def __init__(s, sr, secs):
        s.sr, s.n = sr, int(sr * secs)
        s.bus = {k: np.zeros((2, s.n)) for k in ("dry", "duck", "rev")}

    def add(s, sig, t, pan=0.0, gain=1.0, send=0.2, bus="dry"):
        i = int(t * s.sr)
        if i < 0 or i >= s.n:
            return
        sig = sig[: s.n - i]
        l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
        for ch, g in ((0, l), (1, r)):
            s.bus[bus][ch, i : i + len(sig)] += sig * gain * g
            s.bus["rev"][ch, i : i + len(sig)] += sig * gain * g * send


def adsr(n, sr, dur, a, dec, sus, rel):
    t = np.arange(n) / sr
    e = np.minimum(t / max(a, 1e-4), 1.0)
    e = e * np.where(t < a, 1.0, sus + (1 - sus) * np.exp(-(t - a) / max(dec, 1e-4)))
    e = e * np.where(t < dur, 1.0, np.exp(-(t - dur) / max(rel, 1e-3) * 4))
    return e


class Voices:
    def __init__(s, sr, rng):
        s.sr, s.rng = sr, rng
        s._drum = {}

    def _t(s, dur, rel):
        n = int((dur + rel) * s.sr)
        return n, np.arange(n) / s.sr

    def _harm(s, f, t, k_max, rolloff, detune_cents=(0,)):
        out = np.zeros_like(t)
        for dc in detune_cents:
            ff = f * 2 ** (dc / 1200) * (1 + 0.0003 * s.rng.standard_normal())
            ph0 = s.rng.uniform(0, TAU)
            for k in range(1, k_max + 1):
                if k * ff < s.sr * 0.45:
                    out += np.sin(TAU * k * ff * t + ph0 * k) * k ** -rolloff
        return out / len(detune_cents)

    def pad(s, f, dur, rel=1.0):
        n, t = s._t(dur, rel)
        return s._harm(f, t, 9, 1.5, (-9, 0, 8)) * adsr(n, s.sr, dur, 0.5, 1, 1, rel) / 2.2

    def string(s, f, dur, rel=0.6):
        n, t = s._t(dur, rel)
        vib = 1 + 0.003 * np.sin(TAU * 5.4 * t) * np.minimum(t / 0.8, 1)
        out = np.zeros_like(t)
        for dc in (-6, 6):
            ff = f * 2 ** (dc / 1200)
            ph = TAU * np.cumsum(ff * vib) / s.sr
            for k in range(1, 13):
                if k * ff < s.sr * 0.45:
                    out += np.sin(k * ph) * k ** -1.1
        return out * adsr(n, s.sr, dur, 0.28, 1, 1, rel) / 3

    def epiano(s, f, dur, rel=0.5):
        n, t = s._t(dur, rel)
        car = np.sin(TAU * f * t + 1.6 * np.exp(-3.5 * t) * np.sin(TAU * f * t))
        tine = 0.12 * np.sin(TAU * f * 14 * t) * np.exp(-18 * t)
        e = np.exp(-t * 1.7) * np.minimum(t / 0.004, 1) * np.where(t < dur, 1, np.exp(-(t - dur) * 14))
        return (car + tine) * e * 0.7

    def pluck(s, f, dur, bright=1.0, rel=0.3):
        n, t = s._t(max(dur, 0.8), rel)
        out = np.zeros_like(t)
        for k in range(1, 13):
            if k * f < s.sr * 0.45:
                out += np.sin(TAU * k * f * t * (1 + 0.0004 * k * k)) / k * np.exp(-t * (2.2 + k * 1.6 / bright))
        click = signal.sosfilt(signal.butter(2, 3000, "low", fs=s.sr, output="sos"), s.rng.standard_normal(n)) * np.exp(-t * 400) * 0.08
        return (out + click) * 0.8

    def piano(s, f, dur, rel=0.4):
        n, t = s._t(max(dur, 1.4), rel)
        out = np.zeros_like(t)
        for dc in (-1.5, 1.5):
            ff = f * 2 ** (dc / 1200)
            for k in range(1, 11):
                fk = k * ff * np.sqrt(1 + 0.0004 * k * k)
                if fk < s.sr * 0.45:
                    out += np.sin(TAU * fk * t) * k ** -1.15 * np.exp(-t * (0.9 + 0.55 * k + f / 500))
        hammer = signal.sosfilt(signal.butter(2, 2500, "low", fs=s.sr, output="sos"), s.rng.standard_normal(n)) * np.exp(-t * 300) * 0.05
        e = np.minimum(t / 0.004, 1) * np.where(t < dur + 0.05, 1, np.exp(-(t - dur) * 10))
        return (out / 2 + hammer) * e * 0.9

    def bass(s, f, dur, rel=0.08, drive=0.25):
        n, t = s._t(dur, rel)
        out = np.sin(TAU * f * t) + 0.4 * np.sin(TAU * 2 * f * t) + drive * np.sin(TAU * 3 * f * t)
        return out * adsr(n, s.sr, dur, 0.006, 0.4, 0.55, rel) * 0.7

    def acid(s, f, dur, rel=0.03, cutoff=3.0):
        n, t = s._t(dur, rel)
        out = np.zeros_like(t)
        for k in range(1, 25):
            if k * f < s.sr * 0.45:
                out += np.sin(TAU * k * f * t) / k * (np.exp(-t * (6 + 0.6 * k)) * cutoff * 0.25 + 0.12 * k ** -0.2 * (k < 6))
        return out * adsr(n, s.sr, dur, 0.003, 0.2, 0.8, rel) * 0.8

    def lead(s, f, dur, rel=0.25):
        n, t = s._t(dur, rel)
        vib = 1 + 0.004 * np.sin(TAU * 5.5 * t) * np.minimum(np.maximum(t - 0.25, 0) / 0.4, 1)
        ph = TAU * np.cumsum(f * vib) / s.sr
        out = sum(np.sin(k * ph) / k for k in range(1, 8) if k * f < s.sr * 0.45) + 0.4 * np.sin(ph / 2)
        return out * adsr(n, s.sr, dur, 0.012, 0.3, 0.75, rel) * 0.5

    def flute(s, f, dur, rel=0.2):
        n, t = s._t(dur, rel)
        vib = 1 + 0.005 * np.sin(TAU * 5.0 * t) * np.minimum(np.maximum(t - 0.2, 0) / 0.3, 1)
        ph = TAU * np.cumsum(f * vib) / s.sr
        breath = signal.sosfilt(signal.butter(2, [f * 1.5, f * 4], "band", fs=s.sr, output="sos"), s.rng.standard_normal(n)) * 0.08
        return (np.sin(ph) + 0.28 * np.sin(2 * ph) + 0.08 * np.sin(3 * ph) + breath) * adsr(n, s.sr, dur, 0.06, 0.2, 0.85, rel) * 0.55

    def bell(s, f, dur, rel=0.5):
        n, t = s._t(max(dur, 2.0), rel)
        out = sum(a * np.sin(TAU * f * r * t) * np.exp(-t * d) for r, a, d in ((1, 1, 1.1), (2.756, .45, 1.8), (5.404, .25, 3.2), (8.933, .12, 5)) if f * r < s.sr * 0.45)
        return out * np.minimum(t / 0.003, 1) * 0.6

    # ---- drums (cached per voice, scaled by velocity)
    def drum(s, name):
        if name in s._drum:
            return s._drum[name]
        sr, rng = s.sr, s.rng
        sos = lambda w, ty: signal.butter(2, w, ty, fs=sr, output="sos")
        noise = lambda n: rng.standard_normal(n)
        if name == "kick":
            t = np.arange(int(0.45 * sr)) / sr
            f = 44 + 95 * np.exp(-t * 32)
            d = np.sin(TAU * np.cumsum(f) / sr) * np.exp(-t * 7) + 0.15 * np.exp(-t * 400) * noise(len(t))
        elif name == "softkick":
            t = np.arange(int(0.4 * sr)) / sr
            d = np.sin(TAU * np.cumsum(48 + 40 * np.exp(-t * 28)) / sr) * np.exp(-t * 9) * 0.8
        elif name == "snare":
            t = np.arange(int(0.3 * sr)) / sr
            d = signal.sosfilt(sos([1400, 7000], "band"), noise(len(t))) * np.exp(-t * 16) * 1.5 + np.sin(TAU * 190 * t) * np.exp(-t * 28) * 0.5
        elif name == "clap":
            t = np.arange(int(0.3 * sr)) / sr
            n_ = signal.sosfilt(sos([1000, 3500], "band"), noise(len(t)))
            e = sum(np.exp(-np.maximum(t - o, 0) * 40) * (t >= o) for o in (0, 0.011, 0.023)) * 0.4 + np.exp(-t * 14)
            d = n_ * e * 1.4
        elif name in ("hat", "openhat"):
            t = np.arange(int((0.5 if name == "openhat" else 0.09) * sr)) / sr
            d = signal.sosfilt(sos(7500, "high"), noise(len(t))) * np.exp(-t * (9 if name == "openhat" else 70)) * 0.8
        elif name == "ride":
            t = np.arange(int(1.2 * sr)) / sr
            d = signal.sosfilt(sos(5000, "high"), noise(len(t))) * np.exp(-t * 7) * 0.4
            d += sum(np.sin(TAU * f * t) * 0.05 for f in (3100, 4630, 6130, 7790)) * np.exp(-t * 3)
        elif name == "brush":
            t = np.arange(int(0.25 * sr)) / sr
            d = signal.sosfilt(sos([2500, 9000], "band"), noise(len(t))) * np.minimum(t / 0.03, 1) * np.exp(-t * 14)
        elif name == "shaker":
            t = np.arange(int(0.12 * sr)) / sr
            d = signal.sosfilt(sos([5000, 10000], "band"), noise(len(t))) * np.minimum(t / 0.012, 1) * np.exp(-t * 35)
        else:
            raise KeyError(name)
        s._drum[name] = d / (np.abs(d).max() + 1e-9)
        return s._drum[name]


class Ctx:
    pass


def make_ctx(spec, sr):
    c = Ctx()
    rng = np.random.default_rng(spec["seed"])
    c.rng, c.sr = rng, sr
    c.bpb = spec.get("bpb", 4)
    c.beat = 60.0 / spec["bpm"]
    c.barlen = c.beat * c.bpb
    c.mode = spec.get("mode", "major")
    c.root = spec.get("root", 48)
    iv = MODES[c.mode]
    c.deg = lambda i: c.root + iv[i % 7] + 12 * (i // 7)
    c.bars = max(8, int(round(spec["secs"] / c.barlen / 4)) * 4)
    c.secs = c.bars * c.barlen
    progs = PROGS[spec.get("progmode", c.mode)]
    pa = progs[rng.integers(len(progs))]
    pb = progs[rng.integers(len(progs))]
    n = c.bars
    c.lvl, c.prog = [], []
    intro = 2 if n < 20 else 4
    for b in range(n):
        lv = 0 if b < intro else (1 if b < intro + (n // 2 - intro) // 2 else 2) if b < n // 2 else 3 if b < 3 * n // 4 else 2 if b < n - 2 else 0
        c.lvl.append(lv)
        p = pb if n // 2 <= b < 3 * n // 4 else pa
        c.prog.append(0 if b == n - 1 else p[b % 4])
    return c


def chord(c, d, seventh=False, ninth=False):
    notes = [c.deg(d), c.deg(d + 2), c.deg(d + 4)]
    if c.mode == "minor" and d % 7 == 4:  # harmonic-minor dominant
        notes[1] += 1
    if seventh:
        notes.append(c.deg(d + 6))
    if ninth:
        notes.append(c.deg(d + 8))
    return notes


def fold(notes, lo):
    return sorted(lo + (n - lo) % 12 for n in notes)


def melody(c, density=1.0, base=7, lo=0, hi=9, swing=0.0, rests=0.15):
    """Motif-based melody. Returns [(time_s, dur_s, midi)]. Notes are chord-relative scale steps so it stays consonant."""
    rng, out = c.rng, []
    beats2 = 2 * c.bpb

    def motif():
        m, pos, p = [], 0.0, int(rng.choice([0, 2, 4]))
        durs, w = [0.5, 1, 1.5, 2, 3], np.array([2 * density, 4, 1.5, 2 / density, 0.6 / density])
        while pos < beats2 - 0.01:
            d = float(rng.choice(durs, p=w / w.sum()))
            d = min(d, beats2 - pos)
            on_bar = abs(pos % c.bpb) < 1e-6
            p = int(rng.choice([0, 2, 4, 7])) if on_bar else int(np.clip(p + rng.choice([-2, -1, -1, 0, 1, 1, 2]), lo, hi))
            m.append((pos, d, p, rng.random() < rests))
            pos += d
        return m

    motifs = [motif(), motif()]
    for b in range(c.bars):
        if c.lvl[b] == 0 and b < c.bars - 2:
            continue
        sec = (b // 8) % 2
        m = motifs[sec]
        idx = b % 2
        for j, (pos, d, p, rest) in enumerate(m):
            if not (idx * c.bpb <= pos < (idx + 1) * c.bpb) or rest:
                continue
            if (b // 2) % 2 == 1 and j >= len(m) - 2:  # answer phrase varies its ending
                p = int(rng.choice([0, 2, 4]))
            off = pos - idx * c.bpb
            if swing and abs(off % 1 - 0.5) < 1e-6:
                off += swing
            if b >= c.bars - 2:
                p, d = 0, c.bpb * 1.5
            out.append((b * c.barlen + off * c.beat, d * c.beat * 0.95, c.deg(c.prog[b] + base + p)))
    return out


def humanize(c, t, ms=8):
    return max(0.0, t + c.rng.normal(0, ms / 1000))


def reverb_ir(sr, rt, rng, damp=6000):
    n = int(sr * rt)
    t = np.arange(n) / sr
    ir = rng.standard_normal((2, n)) * np.exp(-t * 6.9 / rt)
    sos = signal.butter(1, damp, "low", fs=sr, output="sos")
    ir = signal.sosfilt(sos, ir, axis=1)
    ir[:, : int(0.012 * sr)] *= np.linspace(0, 1, int(0.012 * sr))
    return ir / np.sqrt((ir ** 2).sum(axis=1, keepdims=True)) * 0.5


# ---------------------------------------------------------------- styles
def ambient(mx, v, c):
    for b in range(c.bars):
        ch = fold(chord(c, c.prog[b], seventh=True, ninth=True), c.root + 12)
        t = b * c.barlen
        if b % 2 == 0 or c.lvl[b] >= 2:
            for n in ch:
                mx.add(v.pad(mtof(n), c.barlen * 2.0, 1.5), t, c.rng.uniform(-.6, .6), 0.17, 0.6)
        if b % 2 == 0:
            mx.add(v.bass(mtof(c.deg(c.prog[b]) - 12), c.barlen * 1.9, 0.5, 0.05), t, 0, 0.55, 0.1)
        if c.lvl[b] >= 2:  # bell arpeggio
            for i in range(c.bpb * 2):
                n = ch[(i * 2 + b) % len(ch)] + 12 + 12 * (i % 3 == 2)
                mx.add(v.bell(mtof(n), 1.5), t + i * c.beat / 2, np.sin(i + b) * .7, 0.16, 0.5)
        if c.lvl[b] >= 3 and b % 2 == 0:
            mx.add(v.drum("shaker"), t + c.beat * 1.5, .3, 0.2, 0.2)
    for t, d, n in melody(c, 0.5, base=14, lo=0, hi=6, rests=0.35):
        mx.add(v.bell(mtof(n), d), humanize(c, t), c.rng.uniform(-.4, .4), 0.3, 0.7)
    return dict(rt=3.6, wet=0.5, lp=12000)


def lofi(mx, v, c):
    sw = 0.17
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        ch = fold(chord(c, c.prog[b], seventh=True, ninth=c.rng.random() < .4), c.root + 12)
        for off in ((0, 1.5, 2.5) if lv >= 2 else (0, 2.5)):
            for i, n in enumerate(ch):
                mx.add(v.epiano(mtof(n), 1.0), humanize(c, t + (off + (sw if off % 1 else 0)) * c.beat + i * .012, 6), -.2 + i * .1, 0.17, 0.3)
        mx.add(v.bass(mtof(c.deg(c.prog[b]) - 12), 1.1 * c.beat), t, 0, 0.8, 0.1)
        if lv >= 2:
            mx.add(v.bass(mtof(c.deg(c.prog[b] + (4 if b % 2 else 0)) - 12), 0.8 * c.beat), t + (2.5 + sw) * c.beat, 0, 0.6, 0.1)
        if lv >= 1:
            for beat, nm, g in ((0, "kick", .85), (2.5 + sw, "kick", .6 * (b % 2 + 1) / 2), (1, "snare", .5), (3, "snare", .55)):
                if lv < 2 and nm == "snare" and beat == 3:
                    continue
                mx.add(v.drum(nm), humanize(c, t + beat * c.beat, 5), 0, g * c.rng.uniform(.85, 1), 0.12)
            for i in range(8):
                mx.add(v.drum("hat"), humanize(c, t + (i / 2 + (sw if i % 2 else 0)) * c.beat, 4), .25, (.2 if i % 2 == 0 else .12) * c.rng.uniform(.6, 1.1), 0.05)
    for t, d, n in melody(c, 0.9, base=14, lo=0, hi=7, swing=sw):
        mx.add(v.flute(mtof(n), d), humanize(c, t, 10), .25, 0.3, 0.25)
    # vinyl crackle
    k = np.zeros(mx.n)
    k[c.rng.integers(0, mx.n, int(mx.n / mx.sr * 14))] = c.rng.uniform(.1, 1, int(mx.n / mx.sr * 14)) * c.rng.choice([-1, 1], int(mx.n / mx.sr * 14))
    hiss = signal.sosfilt(signal.butter(2, 3000, "high", fs=mx.sr, output="sos"), c.rng.standard_normal(mx.n)) * 0.004
    mx.bus["dry"] += (k * 0.05 + hiss)[None, :]
    return dict(rt=1.6, wet=0.3, lp=7500)


def folk(mx, v, c):
    pat = [0, 2, 1, 2, 0, 2, 1, 2] if c.bpb == 4 else [0, 1, 2, 1, 2, 1]
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        ch = fold(chord(c, c.prog[b]), c.root + 7)
        ch = [ch[0], ch[1], ch[2], ch[0] + 12]
        for i, p in enumerate(pat):
            n = ch[[0, 1, 2, 3][p if p < 3 else 3]]
            if i % 2 == 0 and p == 0:
                n = c.deg(c.prog[b]) - 12
            mx.add(v.pluck(mtof(n), c.beat), humanize(c, t + i * c.beat / 2, 5), (i % 3 - 1) * .25, 0.5 if i % 2 == 0 else 0.4, 0.2)
        if lv >= 2:
            for i in range(8 if c.bpb == 4 else 6):
                mx.add(v.drum("shaker"), t + i * c.beat / 2, .35 if i % 2 else -.35, 0.25 if i % 2 else 0.14, 0.1)
            for off in (0, 2):
                mx.add(v.drum("softkick"), t + off * c.beat, 0, 0.7, 0.05)
        if lv >= 3:
            for n in ch[:3]:
                mx.add(v.string(mtof(n + 12), c.barlen), t, -.5, 0.12, 0.3)
    for t, d, n in melody(c, 1.0, base=14, lo=0, hi=7):
        mx.add(v.flute(mtof(n), d), humanize(c, t, 9), .15, 0.38, 0.3)
    return dict(rt=1.9, wet=0.35, lp=14000)


def synthwave(mx, v, c):
    bnote = lambda b, i: c.deg(c.prog[b]) - 12 + (12 if i % 4 == 3 else 0)
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        ch = fold(chord(c, c.prog[b], seventh=True), c.root + 12)
        for n in ch:
            mx.add(v.string(mtof(n), c.barlen, 0.4), t, c.rng.uniform(-.7, .7), 0.2, 0.5, bus="duck")
        if lv >= 1:
            for i in range(8):
                mx.add(v.acid(mtof(bnote(b, i)), c.beat * 0.45, cutoff=1.5), t + i * c.beat / 2, 0, 0.5, 0.05)
            for beat in (0, 1, 2, 3):
                mx.add(v.drum("kick"), t + beat * c.beat, 0, 0.9, 0.05)
            for beat in (1, 3):
                mx.add(v.drum("snare"), t + beat * c.beat, 0, 0.55, 0.5)
            for i in range(8):
                mx.add(v.drum("hat"), t + i * c.beat / 2, .3, 0.16 if i % 2 else 0.1, 0.05)
        if lv >= 3:
            for i in range(16):
                mx.add(v.lead(mtof(ch[i % 4] + 12), c.beat * 0.22), t + i * c.beat / 4, (i % 4 - 1.5) * .25, 0.17, 0.3)
    for t, d, n in melody(c, 1.2, base=14, lo=0, hi=7):
        mx.add(v.lead(mtof(n), d), humanize(c, t, 6), 0, 0.3, 0.35)
    c.kicks = [(b * c.barlen + k * c.beat) for b in range(c.bars) if c.lvl[b] >= 1 for k in range(4)]
    return dict(rt=2.2, wet=0.4, lp=15000, duck=0.55)


def jazz(mx, v, c):
    sw = 0.17
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        d = c.prog[b]
        ch = fold(chord(c, d, seventh=True, ninth=True), c.root + 12)
        nxt = c.deg(c.prog[b + 1]) if b + 1 < c.bars else c.deg(0)
        walk = [c.deg(d) - 12, c.deg(d + 2) - 12, c.deg(d + 4) - 12, nxt - 12 + (-1 if nxt - 12 > c.deg(d) - 12 else 1)]
        for i, n in enumerate(walk):
            mx.add(v.bass(mtof(n), c.beat * 0.9, 0.06, 0.2), humanize(c, t + i * c.beat, 6), -.1, 0.7, 0.1)
        for beat in (0, 1, 1 + 2 * sw + .5 - .5 + sw, 2, 3, 3 + sw + .5):
            pass
        for beat, g in ((0, .5), (1, .35), (1 + .5 + sw, .22), (2, .5), (3, .35), (3 + .5 + sw, .22)):
            mx.add(v.drum("ride"), humanize(c, t + beat * c.beat, 5), .3, g * .2, 0.3)
        if lv >= 1:
            for beat in (1, 3):
                mx.add(v.drum("hat"), t + beat * c.beat, -.2, 0.18, 0.05)
            for beat, g in ((.5 + sw, .5), (2.5 + sw, .45)) if lv < 3 else ((0, .45), (1.5 + sw, .5), (2.5 + sw, .5), (3.5 + sw, .4)):
                for i, n in enumerate(ch):
                    mx.add(v.piano(mtof(n), c.beat * .6), humanize(c, t + beat * c.beat + i * .01, 9), -.3 + i * .12, 0.3 * g, 0.25)
        if lv >= 1 and b % 2 == 1:
            mx.add(v.drum("brush"), t + 3.7 * c.beat, 0, 0.2, 0.1)
    for t, d, n in melody(c, 1.3, base=14, lo=0, hi=8, swing=sw):
        mx.add(v.piano(mtof(n), d), humanize(c, t, 12), .25, 0.5, 0.3)
    return dict(rt=1.4, wet=0.25, lp=14000)


def piano_post(mx, v, c):
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        d = c.prog[b]
        ch = fold(chord(c, d), c.root + 12)
        mx.add(v.piano(mtof(c.deg(d) - 12), c.barlen), t, -.3, 0.6, 0.3)
        mx.add(v.piano(mtof(c.deg(d) - 24), c.barlen), t, -.3, 0.4, 0.3)
        arp = [ch[0], ch[1], ch[2], ch[1] + 12, ch[2], ch[1], ch[0] + 12, ch[1]]
        if lv >= 1:
            for i, n in enumerate(arp[: c.bpb * 2]):
                mx.add(v.piano(mtof(n), c.beat * .6), humanize(c, t + i * c.beat / 2, 8), .25, 0.25 + 0.05 * (lv >= 3), 0.35)
        if lv >= 2 and b % 2 == 0:
            for n in ch:
                mx.add(v.string(mtof(n), c.barlen * 2, 0.8), t, .4, 0.14 + 0.05 * (lv == 3), 0.5)
        if lv >= 3 and b % 2 == 0:
            mx.add(v.drum("softkick"), t, 0, 0.6, 0.2)
            mx.add(v.drum("softkick"), t + c.beat * 2, 0, 0.45, 0.2)
    for t, d, n in melody(c, 0.6, base=14, lo=0, hi=8, rests=0.25):
        mx.add(v.piano(mtof(n), d), humanize(c, t, 15), .1, 0.45, 0.35)
    return dict(rt=3.0, wet=0.45, lp=15000)


def house(mx, v, c):
    pat = [0, 0, 12, 0, 7, 0, 12, 0, 0, 0, 12, 0, 10, 0, 7, 12]
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        ch = fold(chord(c, c.prog[b], seventh=True), c.root + 12)
        for n in ch:
            mx.add(v.pad(mtof(n), c.barlen, .3), t, c.rng.uniform(-.5, .5), 0.14, 0.4, bus="duck")
        if lv >= 1:
            for beat in range(4):
                mx.add(v.drum("kick"), t + beat * c.beat, 0, 0.95, 0.03)
                mx.add(v.drum("openhat"), t + (beat + .5) * c.beat, .2, 0.22, 0.1)
            if lv >= 2:
                for beat in (1, 3):
                    mx.add(v.drum("clap"), t + beat * c.beat, 0, 0.5, 0.25)
                for i in range(16):
                    mx.add(v.drum("hat"), t + i * c.beat / 4, -.3, 0.08 + 0.08 * (i % 4 == 2), 0.05)
                for i, o in enumerate(pat):
                    if i % 4 != 1 or lv >= 3:
                        mx.add(v.acid(mtof(c.deg(c.prog[b]) - 12 + o), c.beat * .2, cutoff=1.8 + 2.2 * np.sin(b / 3 + i / 8) ** 2), t + i * c.beat / 4, 0, 0.5, 0.04)
        if lv >= 3:
            for off in (.5, 1.75, 2.5):
                for n in ch:
                    mx.add(v.epiano(mtof(n + 12), c.beat * .4), t + off * c.beat, .3, 0.16, 0.25)
    for t, d, n in melody(c, 0.8, base=14, lo=0, hi=7, rests=.3):
        mx.add(v.bell(mtof(n), d), t, .2, 0.3, 0.45)
    c.kicks = [(b * c.barlen + k * c.beat) for b in range(c.bars) if c.lvl[b] >= 1 for k in range(4)]
    return dict(rt=1.5, wet=0.28, lp=16000, duck=0.6)


def waltz(mx, v, c):
    for b in range(c.bars):
        t, lv = b * c.barlen, c.lvl[b]
        d = c.prog[b]
        ch = fold(chord(c, d), c.root + 7)
        mx.add(v.pluck(mtof(c.deg(d) - 12), c.beat * 2, 0.9), t, -.3, 0.65, 0.2)
        for beat in (1, 2):
            for i, n in enumerate(ch):
                mx.add(v.pluck(mtof(n), c.beat, 0.8), humanize(c, t + beat * c.beat + i * .02, 6), .1 + i * .08, 0.28, 0.2)
        if lv >= 3:
            for n in ch:
                mx.add(v.string(mtof(n + 12), c.barlen, .5), t, -.4, 0.13, 0.35)
        if lv >= 2 and b % 2 == 0:
            mx.add(v.drum("softkick"), t, 0, 0.35, 0.05)
    for t, d, n in melody(c, 1.0, base=14, lo=0, hi=8):
        mx.add(v.pluck(mtof(n), d, 1.2), humanize(c, t, 8), .2, 0.5, 0.3)
    return dict(rt=2.0, wet=0.3, lp=14000)


STYLES = dict(ambient=ambient, lofi=lofi, folk=folk, synthwave=synthwave, jazz=jazz, piano=piano_post, house=house, waltz=waltz)


def render(style, seed, bpm, secs, sr=44100, mode="major", root=48, bpb=4):
    """-> float32 array (n, 2)."""
    spec = dict(seed=seed, bpm=bpm, secs=secs, mode=mode, root=root, bpb=bpb)
    c = make_ctx(spec, sr)
    mx = Mix(sr, c.secs + 4.0)
    v = Voices(sr, c.rng)
    cfg = STYLES[style](mx, v, c)
    ir = reverb_ir(sr, cfg["rt"], c.rng)
    wet = np.stack([signal.fftconvolve(mx.bus["rev"][ch], ir[ch])[: mx.n] for ch in (0, 1)])
    duck = mx.bus["duck"]
    if "duck" in cfg:
        g = np.ones(mx.n)
        tt = np.arange(mx.n) / sr
        for k in c.kicks:
            i = int(k * sr)
            seg = tt[i : i + int(.3 * sr)] - k
            g[i : i + len(seg)] = np.minimum(g[i : i + len(seg)], 1 - cfg["duck"] * np.exp(-seg / .09))
        duck = duck * g
    out = mx.bus["dry"] + duck + wet * cfg["wet"]
    out = signal.sosfilt(signal.butter(2, 28, "high", fs=sr, output="sos"), out, axis=1)
    out = signal.sosfilt(signal.butter(2, cfg["lp"], "low", fs=sr, output="sos"), out, axis=1)
    n_end = int(c.secs * sr + 1.8 * sr)  # let the last chord ring, then fade
    out = out[:, :n_end]
    out *= np.minimum(np.arange(out.shape[1]) / (0.01 * sr), 1) * np.minimum(np.maximum((out.shape[1] - np.arange(out.shape[1])) / (1.8 * sr), 0), 1)
    rms = np.sqrt((out ** 2).mean())
    out = out * (10 ** (-17 / 20) / rms)
    out = np.tanh(out * 1.1) / 1.1 * 0.97  # soft ceiling
    return out.T.astype(np.float32)


if __name__ == "__main__":
    import sys, wave
    st = sys.argv[1]
    a = render(st, 7, 90, 30)
    print(st, a.shape, "peak", np.abs(a).max(), "dur", len(a) / 44100)
