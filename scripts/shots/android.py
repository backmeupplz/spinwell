# python3 scripts/shots/android.py <apk> <out dir> [language ...]   (run from the repo root)
# The Play store shots, like iOS's StoreShots test: Home, the Library, an album, the full player, synced lyrics and the
# Downloads tab, with the app in each language (per-app locale, Android 13+), from one fresh install and sign-in, to
# <out dir>/<language>/<n>-<name>.png. Drives the emulator in ANDROID_SERIAL with adb and uiautomator, finding controls by
# the app's own strings in app/src/main/res/values-*/strings.xml. Signs in to the public demo server (tools/demo): the
# `reviewer` account on https://demo.spinwell.app, overridable with SPINWELL_URL, SPINWELL_USER and SPINWELL_PASS. Languages
# default to every one the app has. Run it once per device size (phone, 7" and 10" tablet) into <raw dir>/{phone,tab7,tab10}
# for compose.py.
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

APP = 'com.borodutch.spinwell'
URL = os.environ.get('SPINWELL_URL', 'https://demo.spinwell.app')
USER = os.environ.get('SPINWELL_USER', 'reviewer')
PASS = os.environ.get('SPINWELL_PASS', 'spinwell-review-2026')
ALBUM = 'Low Tide Atlas'  # FLAC 24/96: the album and player shots
LYRICS_ALBUM, LYRICS_SONG = 'Neon Orchard', 'Sunset Avenue'  # a track with synced lyrics
RES = 'app/src/main/res'
# app language -> values folder
FOLDERS = {'zh-Hans': 'b+zh+Hans', 'zh-Hant': 'b+zh+Hant', 'pt-BR': 'pt', 'pt-PT': 'pt-rPT', 'id': 'in'}


def languages_of_app():
    found = sorted(d[len('values-'):] for d in os.listdir(RES) if re.fullmatch(r'values-([a-z]{2}|b\+zh\+Han[st]|pt-rPT|in)', d))
    return ['en'] + sorted({next((k for k, v in FOLDERS.items() if v == f), f) for f in found})


def strings(folder):
    path = os.path.join(RES, folder, 'strings.xml')
    return {e.get('name'): (e.text or '').replace("\\'", "'").replace('\\"', '"') for e in ET.parse(path).getroot().iter('string')} if os.path.exists(path) else {}


def adb(*args, check=True):
    return subprocess.run(['adb', *args], check=check, capture_output=True).stdout


def nodes():
    for _ in range(5):
        xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', check=False).decode('utf8', 'replace')
        xml = xml[xml.find('<?xml'):xml.rfind('>') + 1]
        try:
            return list(ET.fromstring(xml).iter('node'))
        except ET.ParseError:
            time.sleep(1)
    return []


def top(n):
    return int(re.findall(r'\d+', n.get('bounds'))[1])


def find(match, timeout=60, last=False, above=None, below=None):
    """The first (or with last, the lowest) on-screen node whose text, content description or hint passes match, and
    with above or below, whose top is above or below that y"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = nodes()
        crash = [n for n in found if n.get('resource-id') in ('android:id/aerr_wait', 'android:id/aerr_close')]
        if crash:
            tap(crash[0])
            continue
        hits = [n for n in found if any(match(n.get(a) or '') for a in ('text', 'content-desc', 'hint')) and n.get('bounds') != '[0,0][0,0]'
                and (above is None or top(n) < above) and (below is None or top(n) > below)]
        if hits:
            return hits[-1] if last else hits[0]
        time.sleep(1)
    out = os.environ.get('SHOTS_FAIL_DIR', '.')
    open(os.path.join(out, 'zz-fail.png'), 'wb').write(adb('exec-out', 'screencap', '-p'))
    print('\n'.join(f"{n.get('text')!r} {n.get('content-desc')!r} {n.get('bounds')}" for n in nodes() if n.get('text') or n.get('content-desc')))
    raise SystemExit(f'nothing on screen matches {match.__doc__}')


def tap(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def swipe(x1, y1, x2, y2, ms=300):
    adb('shell', 'input', 'swipe', *map(str, (x1, y1, x2, y2, ms)))


def exactly(s):
    m = lambda v: v == s
    m.__doc__ = repr(s)
    return m


def containing(s):
    m = lambda v: s in v
    m.__doc__ = f'... {s!r} ...'
    return m


def size():
    w, h = map(int, adb('shell', 'wm', 'size').split()[-1].split(b'x'))
    return w, h


def demo_status_bar():
    adb('shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1')
    for extra in (['clock', '-e', 'hhmm', '0941'], ['battery', '-e', 'level', '100', '-e', 'plugged', 'false'],
                  ['network', '-e', 'wifi', 'show', '-e', 'level', '4'], ['network', '-e', 'mobile', 'show', '-e', 'level', '4', '-e', 'datatype', 'none'],
                  ['notifications', '-e', 'visible', 'false']):
        adb('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '-e', 'command', 'enter')
        adb('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '-e', 'command', *extra)
    adb('shell', 'cmd', 'notification', 'set_dnd', 'on', check=False)


def sign_in():
    en = strings('values')
    adb('shell', 'cmd', 'locale', 'set-app-locales', APP, '--locales', 'en')
    adb('shell', 'monkey', '-p', APP, '-c', 'android.intent.category.LAUNCHER', '1')
    for hint, value in (('label_server', URL), ('label_username', USER), ('label_password', PASS)):
        field = find(exactly(en[hint]))
        tap(field)
        typed = ''
        done = lambda: typed == value or (hint == 'label_password' and len(typed) == len(value))  # a password shows as dots
        for _ in range(8):  # a busy emulator drops typed characters, so check what arrived and type what's missing
            if done():
                break
            if len(typed) < len(value) and (value.startswith(typed) or hint == 'label_password'):
                missing = value[len(typed):]
            else:
                adb('shell', 'input', 'keycombination', '113', '29')  # ctrl+a, then delete
                adb('shell', 'input', 'keyevent', '67')
                missing = value
            adb('shell', 'input', 'text', missing.replace(' ', '%s'))
            time.sleep(1)
            typed = next((n.get('text') or '' for n in nodes() if n.get('focused') == 'true' and n.get('class') == 'android.widget.EditText'), '')
        if not done():
            raise SystemExit(f'typing {hint} failed')
    adb('shell', 'input', 'keyevent', '111')  # escape: hide the keyboard
    tap(find(exactly(en['action_sign_in'])))
    find(exactly(en['tab_library']))
    adb('shell', 'am', 'force-stop', APP)


def shot(folder, name):
    time.sleep(3)  # let covers load
    open(os.path.join(folder, name + '.png'), 'wb').write(adb('exec-out', 'screencap', '-p'))


# ---------------------------------------------------------------- the walk
SETUP_ALBUMS = ['Low Tide Atlas', 'Neon Orchard', 'Blue Hour at Pier Nine', 'Salt and Cedar', 'Sunday Brushes', 'Cuatro Estaciones del Sur', 'Fieldnotes from a Slow Planet']


def tab(t, name):
    tap(find(exactly(t['tab_' + name]), last=True))


def open_album(t, name, low):
    tab(t, 'library')
    tap(find(exactly(t['title_albums'])))
    w, h = size()
    for _ in range(5):  # the grid can be taller than the screen: scroll until the album shows
        try:
            tap(find(containing(name), above=low, timeout=6))
            return
        except SystemExit:
            swipe(w // 2, h * 6 // 10, w // 2, h * 3 // 10)
    tap(find(containing(name), above=low))


def download_setup():
    """One-time: download a few albums so the Downloads tab has content, and wait for them to finish."""
    t = strings('values')
    adb('shell', 'cmd', 'locale', 'set-app-locales', APP, '--locales', 'en')
    adb('shell', 'am', 'start', '-W', '-n', APP + '/.Main')
    low = size()[1] * 3 // 4
    for name in SETUP_ALBUMS:
        open_album(t, name, low)
        tap(find(exactly(t['action_download'])))
        time.sleep(2)
        adb('shell', 'input', 'keyevent', '4')
    tab(t, 'downloads')
    find(exactly(t['title_albums_playlists']))
    deadline = time.time() + 900
    while time.time() < deadline:  # "Downloading" stays on the rows (and the banner) until every song is saved
        if not any('Downloading' in (n.get('text') or '') for n in nodes()):
            break
        time.sleep(10)
    # something played and paused, so every language starts with the mini player on screen
    open_album(t, ALBUM, low)
    tap(find(exactly(t['action_play']), above=low))
    time.sleep(4)
    adb('shell', 'input', 'keyevent', '127')
    time.sleep(1)
    adb('shell', 'am', 'force-stop', APP)


def walk(language, out):
    folder = os.path.join(out, language)
    os.makedirs(folder, exist_ok=True)
    t = strings('values') | strings('values-' + FOLDERS.get(language, language)) if language != 'en' else strings('values')
    low = size()[1] * 3 // 4  # the mini player sits below this
    adb('shell', 'am', 'force-stop', APP)
    adb('shell', 'cmd', 'locale', 'set-app-locales', APP, '--locales', language)
    demo_status_bar()  # again, in case a busy emulator restarted the system UI
    adb('shell', 'am', 'start', '-W', '-n', APP + '/.Main')

    # a restored full player (it was playing when the app stopped) hides the tabs: collapse it first
    find(lambda v: v in (t['tab_home'], t['a11y_minimize_player']))
    minimize = [n for n in nodes() if n.get('content-desc') == t['a11y_minimize_player']]
    if minimize:
        tap(minimize[0])
    tab(t, 'home')
    find(exactly(t['action_shuffle_all']))
    shot(folder, '1-home')

    tab(t, 'library')
    tap(find(exactly(t['title_albums'])))
    find(containing(ALBUM), above=low)
    shot(folder, '2-library')
    tap(find(containing(ALBUM), above=low))
    play = find(exactly(t['action_play']), above=low)
    shot(folder, '3-album')
    tap(play)
    time.sleep(3)

    # the mini player's title (the lowest node naming the first song) opens the full player
    tap(find(containing('Salt Flat Sunrise'), last=True, below=low))
    find(exactly(t['a11y_minimize_player']))
    time.sleep(5)  # a few seconds in, so the seek bar shows progress
    shot(folder, '4-player')
    tap(find(exactly(t['a11y_minimize_player'])))

    # lyrics: a song with synced lyrics, a few lines in
    open_album(t, LYRICS_ALBUM, low)
    tap(find(exactly(LYRICS_SONG), below=0))
    time.sleep(2)
    tap(find(containing(LYRICS_SONG), last=True, below=low))
    tap(find(exactly(t['title_lyrics'])))
    find(containing('The sun is falling'))
    time.sleep(8)
    shot(folder, '5-lyrics')
    tap(find(exactly(t['action_close'])))
    tap(find(exactly(t['a11y_minimize_player'])))

    tab(t, 'downloads')
    find(exactly(t['title_albums_playlists']))
    shot(folder, '6-downloads')
    adb('shell', 'input', 'keyevent', '127')  # pause, so the next language starts on a quiet player


def main():
    apk, out = sys.argv[1], os.path.abspath(sys.argv[2])
    languages = sys.argv[3:] or languages_of_app()
    os.makedirs(out, exist_ok=True)
    os.environ['SHOTS_FAIL_DIR'] = out
    for scale in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):  # less work, steadier dumps
        adb('shell', 'settings', 'put', 'global', scale, '0')
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'wm', 'density', 'reset')
    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    demo_status_bar()
    # A tablet's launcher keeps its taskbar (app icons) over the bottom of every app, so turn the launcher off for the walk
    home = None
    if size()[0] * 160 // int(adb('shell', 'wm', 'density').split()[-1]) >= 600:
        for package in adb('shell', 'pm', 'list', 'packages', '-d').decode().split():  # one a killed walk left off
            if 'launcher' in package:
                adb('shell', 'pm', 'enable', package.split(':')[-1])
        home = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-c', 'android.intent.category.HOME',
                   '-a', 'android.intent.action.MAIN').decode().split()[-1].split('/')[0]
        if home == 'com.android.settings':
            home = None
        else:
            adb('shell', 'pm', 'disable-user', '--user', '0', home)
    failed = []
    try:
        adb('uninstall', APP, check=False)
        adb('install', '-r', apk)
        adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS', check=False)  # no permission prompt over a shot
        sign_in()
        download_setup()
        for language in languages:
            for attempt in range(2):  # a busy emulator now and then misses a step, so a language gets a second walk
                try:
                    walk(language, out)
                    break
                except SystemExit as e:
                    print(language, 'attempt', attempt + 1, e, flush=True)
            else:
                failed.append(language)
                continue
            print(language, ' '.join(sorted(os.listdir(os.path.join(out, language)))), flush=True)
    finally:
        if home:
            adb('shell', 'pm', 'enable', home)
    if failed:
        raise SystemExit('failed: ' + ' '.join(failed))


if __name__ == '__main__':
    main()
