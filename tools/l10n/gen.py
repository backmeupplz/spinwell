#!/usr/bin/env python3
"""One source for both apps' UI strings.

  tools/l10n/strings.json   key -> {"en": text | {"one":..,"other":..}, "comment": why/where, "only": "android"|"ios"?, "translate": false?}
  tools/l10n/tr/<loc>.json  key -> text | {category: text}   (one file per UI locale)

Writes app/src/main/res/values*/strings.xml and ios/Spinwell/Localizable.xcstrings (+ InfoPlist.xcstrings).
Placeholders are written Android style: `%s` / `%d` for one argument, `%1$s %2$d` for several, `%%` for a percent sign.
The iOS key is the English text with `%s` -> `%@` and `%d` -> `%lld` (what SwiftUI/String(localized:) look up).
Run from anywhere: python3 tools/l10n/gen.py
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/l10n"
RES = ROOT / "app/src/main/res"
IOS = ROOT / "ios/Spinwell"

LOCALES = ["ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt-BR", "pt-PT", "ru", "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant"]
FOLDER = {"id": "in", "pt-BR": "pt", "pt-PT": "pt-rPT", "zh-Hans": "b+zh+Hans", "zh-Hant": "b+zh+Hant"}
ORDER = ["zero", "one", "two", "few", "many", "other"]
# CLDR plural categories each UI language needs for integers
CATS = {
    "en": ["one", "other"], "ar": ORDER, "de": ["one", "other"], "es": ["one", "many", "other"], "fr": ["one", "many", "other"],
    "hi": ["one", "other"], "id": ["other"], "it": ["one", "many", "other"], "ja": ["other"], "ko": ["other"], "nl": ["one", "other"],
    "pl": ["one", "few", "many", "other"], "pt-BR": ["one", "many", "other"], "pt-PT": ["one", "many", "other"],
    "ru": ["one", "few", "many", "other"], "sv": ["one", "other"], "th": ["other"], "tr": ["one", "other"],
    "uk": ["one", "few", "many", "other"], "vi": ["other"], "zh-Hans": ["other"], "zh-Hant": ["other"],
}
PH = re.compile(r"%(?:(\d+)\$)?([sd%])")


def placeholders(text):
    return [(m.group(1), m.group(2)) for m in PH.finditer(text) if m.group(2) != "%"]


def texts(v):
    return list(v.values()) if isinstance(v, dict) else [v]


def check_text(where, en, t):
    a, b = placeholders(en), placeholders(t)
    assert sorted(x[1] for x in a) == sorted(x[1] for x in b) or (len(a) <= 1 and len(b) <= 1 and [x[1] for x in a] == [x[1] for x in b]), f"{where}: placeholders differ: {en!r} vs {t!r}"
    if len(b) > 1:
        assert all(x[0] for x in b), f"{where}: several placeholders need %1$s style positions: {t!r}"


def android_escape(t):
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace('"', '\\"')
    return "\\" + t if t[:1] in "@?" else t


def ios_value(t):
    return re.sub(r"%(\d+)\$s", r"%\1$@", re.sub(r"%(\d+)\$d", r"%\1$lld", t)).replace("%s", "%@").replace("%d", "%lld")


def xml(loc, strings, tr):
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for key, s in strings.items():
        if s.get("only") == "ios":
            continue
        if loc and s.get("translate") is False:
            continue
        v = s["en"] if loc is None else tr[loc][key]
        if isinstance(v, dict):
            out.append(f'    <plurals name="{key}">')
            for c in ORDER:
                if c in v:
                    out.append(f'        <item quantity="{c}">{android_escape(v[c])}</item>')
            out.append("    </plurals>")
        else:
            nt = ' translatable="false"' if s.get("translate") is False else ""
            out.append(f'    <string name="{key}"{nt}>{android_escape(v)}</string>')
    out.append("</resources>\n")
    return "\n".join(out)



def main():
    import os
    strings = json.loads(Path(os.environ.get("STRINGS", SRC / "strings.json")).read_text())
    tr = {l: json.loads((SRC / "tr" / f"{l}.json").read_text()) if (SRC / "tr" / f"{l}.json").exists() else {} for l in LOCALES}
    errors = []
    only = sys.argv[sys.argv.index("--check") + 1].split(",") if "--check" in sys.argv else None  # validate just these locales, write nothing
    if "--en" in sys.argv:  # English only (while extracting strings): just values/strings.xml, no checks
        (RES / "values").mkdir(parents=True, exist_ok=True)
        (RES / "values/strings.xml").write_text(xml(None, strings, tr))
        return
    for key, s in strings.items():
        en = s["en"]
        if isinstance(en, dict):
            assert "other" in en, key
        for l in only or LOCALES:
            if s.get("translate") is False:
                continue
            v = tr[l].get(key)
            if v is None:
                errors.append(f"{l}: missing {key}")
                continue
            if isinstance(en, dict) != isinstance(v, dict):
                errors.append(f"{l}: {key} plural mismatch")
                continue
            if isinstance(v, dict):
                need = set(CATS[l])
                if set(v) != need:
                    errors.append(f"{l}: {key} plural forms {sorted(v)} != {sorted(need)}")
                    continue
            en_other = en["other"] if isinstance(en, dict) else en
            for t in texts(v):
                try:
                    # plural forms may legitimately spell the count out ("one book"), so only the "other" form must carry it
                    if not isinstance(v, dict) or t is v["other"]:
                        check_text(f"{l} {key}", en_other, t)
                except AssertionError as e:
                    errors.append(str(e))
    for l in only or LOCALES:
        for k in tr[l]:
            if k not in strings:
                errors.append(f"{l}: unknown key {k}")
    if errors:
        print("\n".join(errors[:60]), f"\n{len(errors)} problems", file=sys.stderr)
        sys.exit(1)
    if only:
        print("ok", only)
        return

    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "values/strings.xml").write_text(xml(None, strings, tr))
    for l in LOCALES:
        d = RES / f"values-{FOLDER.get(l, l)}"
        d.mkdir(exist_ok=True)
        (d / "strings.xml").write_text(xml(l, strings, tr))

    # iOS
    cat = {}
    for key, s in strings.items():
        if s.get("only") == "android" or key.startswith("plist_"):
            continue
        en = s["en"]
        ik = re.sub(r"%\d+\$", "%", ios_value(en["other"] if isinstance(en, dict) else en))  # Swift's key for "\(a) \(b)" has no positions; the values keep them
        def unit(t):
            return {"stringUnit": {"state": "translated", "value": ios_value(t)}}
        def loc(v):
            n = s.get("count_arg")
            if n and isinstance(v, dict):
                # the plural follows argument n, not the first one: Xcode wants a substitution, whose variants spell out the whole sentence
                sub = {"argNum": n, "formatSpecifier": "lld", "variations": {"plural": {c: unit(t.replace(f"%{n}$d", "%arg")) for c, t in sorted(v.items())}}}
                return {"stringUnit": {"state": "translated", "value": "%#@count@"}, "substitutions": {"count": sub}}
            return {"variations": {"plural": {c: unit(t) for c, t in sorted(v.items())}}} if isinstance(v, dict) else unit(v)
        locs = {"en": loc(en)}
        if s.get("translate") is not False:
            locs.update({l: loc(tr[l][key]) for l in LOCALES})
        entry = {"comment": s.get("comment", ""), "extractionState": "manual", "localizations": locs}
        if s.get("translate") is False:
            entry["shouldTranslate"] = False
        if ik in cat:
            assert cat[ik]["localizations"] == entry["localizations"], f"iOS key collision with different translations: {ik!r} ({key})"
            continue
        cat[ik] = entry
    dump = lambda d: json.dumps(d, ensure_ascii=False, indent=2, separators=(",", " : "), sort_keys=True) + "\n"
    (IOS / "Localizable.xcstrings").write_text(dump({"sourceLanguage": "en", "strings": cat, "version": "1.0"}))

    # Info.plist usage text: Info.plist key names are written in strings.json as `plist.<Key>`
    plist = {}
    for key, s in strings.items():
        if key.startswith("plist_"):
            name = s["plist"]
            locs = {"en": {"stringUnit": {"state": "translated", "value": s["en"]}}}
            locs.update({l: {"stringUnit": {"state": "translated", "value": tr[l][key]}} for l in LOCALES})
            plist[name] = {"comment": s.get("comment", ""), "extractionState": "manual", "localizations": locs}
    (IOS / "InfoPlist.xcstrings").write_text(dump({"sourceLanguage": "en", "strings": plist, "version": "1.0"}))
    print(f"{len(strings)} strings, {len(LOCALES)} locales")


if __name__ == "__main__":
    main()
