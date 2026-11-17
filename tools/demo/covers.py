"""Original cover art and artist images: SVG compositions rendered with headless Chrome (1200x1200)."""
import math, os, random, subprocess, tempfile

W = 1200
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")


def smooth(pts, closed=False):
    """Catmull-Rom through pts -> SVG path."""
    n = len(pts)
    g = (lambda i: pts[i % n]) if closed else (lambda i: pts[max(0, min(n - 1, i))])
    d = f"M{pts[0][0]:.1f},{pts[0][1]:.1f}"
    for i in range(n if closed else n - 1):
        p0, p1, p2, p3 = g(i - 1), g(i), g(i + 1), g(i + 2)
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d += f"C{c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} {p2[0]:.1f},{p2[1]:.1f}"
    return d + ("Z" if closed else "")


def wave(y, amp, freq, phase, bottom=W, step=40):
    pts = [(x, y + amp * math.sin(x / W * freq * math.tau + phase)) for x in range(-40, W + 41, step)]
    return smooth(pts) + f"L{W + 40},{bottom}L-40,{bottom}Z"


def text(x, y, s, size, fill, family="Avenir Next", weight=400, anchor="start", ls=0, extra=""):
    return f'<text x="{x}" y="{y}" font-family="{family}, Helvetica Neue, Hiragino Sans, Arial" font-size="{size}" fill="{fill}" font-weight="{weight}" text-anchor="{anchor}" letter-spacing="{ls}" {extra}>{s}</text>'


def lines(x, y, parts, size, fill, lh=1.1, **kw):
    return "".join(text(x, y + i * size * lh, p, size, fill, **kw) for i, p in enumerate(parts))


GRAIN = '<filter id="gr" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" stitchTiles="stitch"/><feColorMatrix type="saturate" values="0"/></filter>'


def blur(id_, sd):
    return f'<filter id="{id_}" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="{sd}"/></filter>'


def lin(id_, stops, x2=0, y2=1):
    s = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return f'<linearGradient id="{id_}" x1="0" y1="0" x2="{x2}" y2="{y2}">{s}</linearGradient>'


# ---------------------------------------------------------------- styles: fn(rng, title, artist, show) -> svg inner
def dusk(r, T, A, show):
    d = "<defs>" + lin("sky", [(0, "#14213d"), (.45, "#7b3f6e"), (.72, "#f08a5d"), (1, "#f9d98b")]) + "</defs>"
    d += f'<rect width="{W}" height="{W}" fill="url(#sky)"/><circle cx="760" cy="640" r="190" fill="#fff1c9" opacity=".95"/>'
    cols = ["#e7a27c", "#b86a6a", "#4a5c8a", "#233a63", "#10213a", "#091528"]
    for i, c in enumerate(cols):
        d += f'<path d="{wave(640 + i * 105, 26 + i * 6, 2 + i * .7, r.uniform(0, 6))}" fill="{c}"/>'
    if show:
        d += text(90, 1090, T.upper(), 60, "#f5e9d6", "Futura", 500, ls=14) + text(92, 1140, A.upper(), 30, "#f5e9d6", "Futura", 400, ls=10, extra='opacity=".8"')
    return d


def aurora(r, T, A, show, pal=("#1f7a6d", "#6d4bd0", "#2fb7c9", "#c85fa0"), bg=("#070d1c", "#101a38"), tone="#eaf2ff", serif=False):
    d = "<defs>" + lin("bg", [(0, bg[0]), (1, bg[1])]) + blur("b", 85) + "</defs>" + f'<rect width="{W}" height="{W}" fill="url(#bg)"/>'
    for i in range(5):
        d += f'<ellipse cx="{r.uniform(150, 1050):.0f}" cy="{r.uniform(150, 850):.0f}" rx="{r.uniform(200, 380):.0f}" ry="{r.uniform(130, 260):.0f}" fill="{pal[i % len(pal)]}" opacity="{r.uniform(.5, .85):.2f}" filter="url(#b)" transform="rotate({r.uniform(-40, 40):.0f} 600 600)"/>'
    for i in range(70):
        d += f'<circle cx="{r.uniform(0, W):.0f}" cy="{r.uniform(0, W):.0f}" r="{r.choice([1.5, 2, 2.5, 4]):.1f}" fill="{tone}" opacity="{r.uniform(.25, .9):.2f}"/>'
    d += f'<circle cx="600" cy="560" r="300" fill="none" stroke="{tone}" stroke-width="2.5" opacity=".7"/>'
    if show:
        fam = "Didot" if serif else "Avenir Next"
        parts = T.split(" ")
        mid = (len(parts) + 1) // 2
        d += lines(600, 1010, [" ".join(parts[:mid]), " ".join(parts[mid:])], 58, tone, 1.15, family=fam, weight=300, anchor="middle", ls=3) + text(600, 1130, A.upper(), 28, tone, "Avenir Next", 500, "middle", 12, 'opacity=".8"')
    return d


def aurora2(r, T, A, show):
    return aurora(r, T, A, show, pal=("#a9c4e8", "#e8d5f2", "#7ea3d1", "#f5e6ea"), bg=("#31405f", "#6c7fa8"), tone="#ffffff", serif=True)


def gridsun(r, T, A, show):
    d = "<defs>" + lin("sky", [(0, "#1a0b3d"), (.55, "#7a1f8f"), (1, "#ff5d9e")]) + lin("sun", [(0, "#ffe66d"), (1, "#ff4d8d")]) + blur("g", 10)
    d += '<mask id="m"><rect width="1200" height="1200" fill="#fff"/>' + "".join(f'<rect x="0" y="{560 + i * 28 + i * i * 2}" width="1200" height="{4 + i * 2.6}" fill="#000"/>' for i in range(8)) + "</mask></defs>"
    d += f'<rect width="{W}" height="{W}" fill="url(#sky)"/><circle cx="600" cy="520" r="290" fill="url(#sun)" mask="url(#m)"/>'
    d += f'<rect y="720" width="{W}" height="480" fill="#12062b"/>'
    g = '<g stroke="#ff6ad5" stroke-width="3" opacity=".9">'
    for i in range(-14, 15):
        g += f'<line x1="600" y1="720" x2="{600 + i * 190}" y2="1200"/>'
    for i in range(1, 9):
        g += f'<line x1="0" y1="{720 + (i / 8) ** 2 * 480:.0f}" x2="1200" y2="{720 + (i / 8) ** 2 * 480:.0f}"/>'
    d += g + "</g>"
    if show:
        t = text(600, 150, T.upper(), 110, "#ffe9fb", "Futura", 700, "middle", 8, 'font-style="italic"')
        d += f'<g filter="url(#g)" opacity=".9">{text(600, 150, T.upper(), 110, "#ff4dd2", "Futura", 700, "middle", 8, "font-style=\"italic\"")}</g>{t}' + text(600, 205, A.upper(), 34, "#8ff7ff", "Futura", 500, "middle", 16)
    return d


def papercut(r, T, A, show):
    d = f'<rect width="{W}" height="{W}" fill="#f4e9d3"/><circle cx="820" cy="400" r="150" fill="#e9a23b"/>'
    cols = ["#d9825b", "#c0603f", "#8aa17b", "#4f7a6a", "#2b4a4a"]
    for i, c in enumerate(cols):
        y = 520 + i * 120
        pts = [(x, y + r.uniform(-70, 70)) for x in range(-100, 1300, 200)]
        d += f'<path d="{smooth(pts)}L1300,1300L-100,1300Z" fill="{c}"/>'
        for k in range(3):  # little cedar triangles
            x = r.uniform(80, 1100)
            h = r.uniform(70, 130)
            d += f'<path d="M{x:.0f},{y + 30:.0f}l{h * .3:.0f},0l-{h * .15:.0f},-{h:.0f}z" fill="{cols[min(i + 1, 4)]}" opacity=".9"/>'
    if show:
        d += text(100, 170, T, 86, "#2b2a28", "Georgia", 400, extra='font-style="italic"') + text(104, 230, A.upper(), 30, "#2b2a28", "Avenir Next", 600, ls=10)
    return d


def blocks(r, T, A, show):
    d = f'<rect width="{W}" height="{W}" fill="#143a9e"/><path d="M0,1200V620A580,580 0 0 1 580,1200Z" fill="#f2e6cf"/><circle cx="930" cy="330" r="150" fill="#ff7a2f"/>'
    d += f'<rect x="700" y="650" width="500" height="64" fill="#0a0f24"/><rect x="700" y="730" width="320" height="64" fill="#f2e6cf"/>'
    if show:
        d += lines(80, 170, ["Blue", "Hour", "at Pier", "Nine"], 130, "#f2e6cf", .95, family="Didot", weight=700) + text(84, 1130, A.upper(), 34, "#143a9e", "Avenir Next", 700, ls=14)
    return d


def brush(r, T, A, show):
    d = f'<rect width="{W}" height="{W}" fill="#f6efe3"/>'
    cols = ["#e4572e", "#f3a712", "#29335c", "#a8c686", "#669bbc", "#e4572e"]
    for i, c in enumerate(cols):
        y = 150 + i * 130
        pts = [(x, y + r.uniform(-50, 50)) for x in range(80, 1150, 180)]
        d += f'<path d="{smooth(pts)}" stroke="{c}" stroke-width="{r.uniform(60, 95):.0f}" stroke-linecap="round" fill="none" opacity=".92"/>'
    if show:
        d += text(100, 1060, "Sunday Brushes", 92, "#29335c", "Optima", 700) + text(104, 1125, A.upper(), 30, "#29335c", "Avenir Next", 600, ls=12)
    return d


def moon(r, T, A, show):
    d = "<defs>" + lin("bg", [(0, "#0b1030"), (.55, "#26346b"), (1, "#0d1433")]) + blur("b", 6) + blur("h", 40) + "</defs>"
    d += f'<rect width="{W}" height="{W}" fill="url(#bg)"/>'
    for i in range(120):
        d += f'<circle cx="{r.uniform(0, W):.0f}" cy="{r.uniform(0, 680):.0f}" r="{r.choice([1.3, 1.8, 2.4]):.1f}" fill="#fff" opacity="{r.uniform(.3, .95):.2f}"/>'
    d += '<circle cx="600" cy="400" r="260" fill="#cfd8ff" filter="url(#h)" opacity=".35"/><circle cx="600" cy="400" r="200" fill="#eef1ff"/>'
    for cx, cy, rr in ((540, 340, 38), (660, 450, 52), (610, 300, 22), (520, 470, 26)):
        d += f'<circle cx="{cx}" cy="{cy}" r="{rr}" fill="#c4cdf0" opacity=".55" filter="url(#b)"/>'
    d += '<rect y="700" width="1200" height="500" fill="#0a1128"/>'
    for i in range(34):
        y = 710 + i * 14 + i * i * .25
        w = max(30, 380 - i * 7 + r.uniform(-30, 30))
        x = 600 - w / 2 + r.uniform(-25, 25)
        d += f'<rect x="{x:.0f}" y="{y:.0f}" width="{w:.0f}" height="{3 + i * .12:.1f}" rx="2" fill="#dfe6ff" opacity="{max(.12, .75 - i * .02):.2f}"/>'
    if show:
        d += text(600, 1105, T, 78, "#eef1ff", "Didot", 400, "middle", 6) + text(600, 1155, A.upper(), 26, "#9fb0ea", "Avenir Next", 500, "middle", 10)
    return d


def leaves(r, T, A, show):
    d = "<defs>" + lin("bg", [(0, "#1d1714"), (1, "#3b2a22")]) + blur("b", 7) + "</defs>" + f'<rect width="{W}" height="{W}" fill="url(#bg)"/>'
    cols = ["#d9622b", "#e8a33d", "#a8341f", "#f0c869", "#7a2a1a", "#c97b3c"]
    for i in range(70):
        s = r.uniform(.4, 2.1)
        f = ' filter="url(#b)"' if s > 1.7 or s < .55 else ""
        d += f'<g transform="translate({r.uniform(0, W):.0f} {r.uniform(0, W):.0f}) rotate({r.uniform(0, 360):.0f}) scale({s:.2f})"{f}><path d="M0,-34C26,-22 26,22 0,40C-26,22 -26,-22 0,-34Z" fill="{r.choice(cols)}" opacity="{r.uniform(.6, .95):.2f}"/><path d="M0,-34L0,48" stroke="#1d1714" stroke-width="2" opacity=".5"/></g>'
    if show:
        d += f'<rect x="0" y="880" width="{W}" height="320" fill="#1d1714" opacity=".6"/>' + text(80, 1030, T, 88, "#f4e3c8", "Georgia", 400, extra='font-style="italic"') + text(84, 1100, A.upper(), 28, "#e8a33d", "Avenir Next", 600, ls=10)
    return d


def highway(r, T, A, show):
    d = "<defs>" + lin("sky", [(0, "#2b1055"), (.5, "#d1446f"), (1, "#ffb86b")]) + lin("sun", [(0, "#fff3b0"), (1, "#ff8a5c")]) + "</defs>"
    d += f'<rect width="{W}" height="{W}" fill="url(#sky)"/><circle cx="520" cy="640" r="230" fill="url(#sun)"/>'
    d += '<path d="M0,700L260,640L420,690L640,610L860,680L1200,630L1200,1200L0,1200Z" fill="#2a1038" opacity=".9"/>'
    d += '<path d="M520,700L-300,1200L1500,1200Z" fill="#1a0b26"/><path d="M520,700L-300,1200" stroke="#ff9ee8" stroke-width="6"/><path d="M520,700L1500,1200" stroke="#ff9ee8" stroke-width="6"/>'
    for i in range(7):
        t0, t1 = (i / 7) ** 1.8, ((i + .55) / 7) ** 1.8
        x0, x1, y0, y1 = 520, 520, 700 + t0 * 500, 700 + t1 * 500
        d += f'<path d="M{x0 - 2 - t0 * 14:.0f},{y0:.0f}L{x1 + 2 + t1 * 14:.0f},{y1:.0f}L{x1 - 2 - t1 * 14:.0f},{y1:.0f}L{x0 + 2 + t0 * 14:.0f},{y0:.0f}Z" fill="#ffe29a"/>'
    for i in range(14):
        h = r.uniform(80, 230)
        x = r.uniform(0, W)
        d += f'<rect x="{x:.0f}" y="{690 - h:.0f}" width="{r.uniform(36, 80):.0f}" height="{h:.0f}" fill="#241034" opacity=".85"/>'
    if show:
        d += text(1090, 120, T, 104, "#fff4d9", "Hiragino Mincho ProN", 700, extra='writing-mode="vertical-rl" text-orientation="upright"') + text(100, 1130, "YAKOU BUS", 34, "#fff4d9", "Futura", 500, ls=18)
    return d


def rain(r, T, A, show):
    d = "<defs>" + lin("bg", [(0, "#17333f"), (1, "#3f6b73")]) + blur("b", 14) + "</defs>" + f'<rect width="{W}" height="{W}" fill="url(#bg)"/>'
    for i in range(26):
        d += f'<circle cx="{r.uniform(0, W):.0f}" cy="{r.uniform(300, 1000):.0f}" r="{r.uniform(30, 90):.0f}" fill="{r.choice(["#ffd27a", "#ff9b6a", "#9ee7e0", "#fff0c4"])}" opacity="{r.uniform(.25, .6):.2f}" filter="url(#b)"/>'
    for i in range(190):
        x, y, l = r.uniform(-100, 1300), r.uniform(-100, 1200), r.uniform(60, 220)
        d += f'<line x1="{x:.0f}" y1="{y:.0f}" x2="{x - l * .22:.0f}" y2="{y + l:.0f}" stroke="#e8fbff" stroke-width="{r.uniform(1.2, 3):.1f}" opacity="{r.uniform(.12, .5):.2f}" stroke-linecap="round"/>'
    d += '<rect y="1010" width="1200" height="190" fill="#0e1f27" opacity=".78"/>'
    if show:
        d += text(1090, 130, T, 98, "#f4fbff", "Hiragino Mincho ProN", 600, extra='writing-mode="vertical-rl"') + text(90, 1115, "YAKOU BUS", 32, "#f4fbff", "Futura", 500, ls=18)
    return d


def rings(r, T, A, show):
    d = f'<rect width="{W}" height="{W}" fill="#0b0b0f"/>'
    cols = ["#c8ff3d", "#ff2d95", "#3df0ff", "#ffffff"]
    for i in range(14):
        rad = 560 - i * 38
        d += f'<circle cx="{600 + math.sin(i / 2) * 40:.0f}" cy="{540 + math.cos(i / 2) * 30:.0f}" r="{rad}" fill="none" stroke="{cols[i % 4] if i % 3 else "#0b0b0f"}" stroke-width="{10 + (i % 3) * 8}"/>'
    d += '<circle cx="600" cy="540" r="26" fill="#c8ff3d"/>'
    if show:
        d += '<rect x="0" y="960" width="1200" height="240" fill="#0b0b0f"/>' + text(80, 1090, "CONCRETE", 120, "#ffffff", "Menlo", 700) + text(80, 1160, "GARDEN  /  ORLA VOSS", 32, "#c8ff3d", "Menlo", 400, ls=10)
    return d


def tiles(r, T, A, show):
    seasons = [("#f6c453", "#e8743b", "#fff2cf"), ("#e63946", "#f4a261", "#fff2cf"), ("#c77d2e", "#7a3b1d", "#f3e2c0"), ("#2a4d8f", "#6fa8dc", "#eaf2fb")]
    d = f'<rect width="{W}" height="{W}" fill="#f3e9d6"/>'
    n = 4
    s = W / (n * 2)
    for gy in range(n * 2):
        for gx in range(n * 2):
            q = (gy // n) * 2 + (gx // n)
            a, b, c = seasons[q]
            x, y = gx * s, gy * s
            k = r.randrange(4)
            d += f'<rect x="{x:.0f}" y="{y:.0f}" width="{s:.0f}" height="{s:.0f}" fill="{c}"/>'
            d += f'<g transform="rotate({k * 90} {x + s / 2:.0f} {y + s / 2:.0f})"><path d="M{x:.0f},{y:.0f}L{x + s:.0f},{y:.0f}A{s:.0f},{s:.0f} 0 0 1 {x:.0f},{y + s:.0f}Z" fill="{a}"/><circle cx="{x + s * .25:.0f}" cy="{y + s * .25:.0f}" r="{s * .17:.0f}" fill="{b}"/></g>'
    if show:
        d += f'<rect x="170" y="440" width="860" height="320" fill="#f3e9d6"/><rect x="190" y="460" width="820" height="280" fill="none" stroke="#7a3b1d" stroke-width="4"/>'
        d += text(600, 570, "Cuatro Estaciones", 82, "#3b2514", "Didot", 400, "middle") + text(600, 650, "del Sur", 82, "#3b2514", "Didot", 400, "middle", 0, 'font-style="italic"') + text(600, 710, "TOMÁS ARRIAGA", 28, "#7a3b1d", "Avenir Next", 600, "middle", 12)
    return d


def shapes(r, T, A, show):
    d = f'<rect width="{W}" height="{W}" fill="#f5ecd9"/>'
    d += '<circle cx="360" cy="400" r="260" fill="#e63b2e"/><rect x="560" y="140" width="460" height="460" fill="#1d3f8f"/>'
    d += '<path d="M120,1040A280,280 0 0 1 680,1040Z" fill="#f4b41a"/><path d="M700,1040L1080,380L1080,1040Z" fill="#1c1c1c"/><circle cx="880" cy="820" r="70" fill="#f5ecd9"/>'
    d += '<rect x="120" y="640" width="380" height="36" fill="#1c1c1c"/><rect x="120" y="700" width="240" height="36" fill="#1d3f8f"/>'
    if show:
        d += text(100, 1130, "SPINWELL SAMPLER", 56, "#1c1c1c", "Futura", 700, ls=8) + text(1100, 1130, "VOL. 1", 56, "#e63b2e", "Futura", 700, "end", 8)
    return d


STYLES = dict(dusk=dusk, aurora=aurora, aurora2=aurora2, gridsun=gridsun, papercut=papercut, blocks=blocks, brush=brush, moon=moon,
              leaves=leaves, highway=highway, rain=rain, rings=rings, tiles=tiles, shapes=shapes)


def svg(style, title, artist, seed=1, show=True, zoom=1.0, cx=600, cy=600):
    r = random.Random(seed)
    body = STYLES[style](r, title, artist, show)
    if zoom != 1.0:
        body = f'<g transform="translate({600 - cx * zoom:.0f} {600 - cy * zoom:.0f}) scale({zoom})">{body}</g>'
    grain = f'<defs>{GRAIN}</defs><rect width="{W}" height="{W}" filter="url(#gr)" opacity=".16" style="mix-blend-mode:overlay"/>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" viewBox="0 0 {W} {W}">{body}{grain}</svg>'


def render(svgs, out_paths):
    """Render each svg string to a JPEG at the matching path (headless Chrome -> png -> ffmpeg)."""
    with tempfile.TemporaryDirectory() as td:
        for i, (s, out) in enumerate(zip(svgs, out_paths)):
            html = os.path.join(td, f"{i}.html")
            png = os.path.join(td, f"{i}.png")
            open(html, "w").write(f'<!doctype html><meta charset="utf-8"><style>html,body{{margin:0;background:#000}}svg{{display:block}}</style>{s}')
            for attempt in range(4):  # headless Chrome occasionally exits 2 under load
                try:
                    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1", f"--window-size={W},{W}",
                                    f"--screenshot={png}", "file://" + html], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
                    break
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                    if attempt == 3:
                        raise
            os.makedirs(os.path.dirname(out), exist_ok=True)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", png, "-vf", f"crop={W}:{W}:0:0", "-q:v", "3", out], check=True)
