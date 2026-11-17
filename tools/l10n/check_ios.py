#!/usr/bin/env python3
"""Best-effort check that the iOS UI strings resolve through the shared table (tools/l10n/strings.json).

Scans ios/Spinwell/*.swift for literals passed to Text/Label/Button/Toggle/Section/Tab/TextField/SecureField/ProgressView/
Link/LabeledContent/Picker/SettingRow, .navigationTitle/.accessibilityLabel/.confirmationDialog/.searchable(prompt:),
`info:`/`description:`, String(localized:) and fails when the literal's iOS key (an interpolation `\\(x)` becomes %@ or %lld,
the check does not tell which: look at the Swift type yourself) is not a key of the table.
Use `Text(verbatim:)` for text that must not be translated. Run from anywhere: python3 tools/l10n/check_ios.py
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CALLS = r"(?:Text|Label|Button|Toggle|Section|Tab|TextField|SecureField|ProgressView|Link|LabeledContent|Picker|SettingRow|ContentUnavailableView|" \
        r"\.navigationTitle|\.accessibilityLabel|\.confirmationDialog|String\(localized:|LocalizedStringResource|LocalizedStringKey)\(\s*"
START = re.compile(r"(?:" + CALLS + r'|\b(?:prompt|info|description|message): )"')
MARK = "\x00"


def read_literal(src, i):
    """src[i] is the opening quote. Returns (text with interpolations as MARK, index after the closing quote)."""
    out, i = [], i + 1
    while src[i] != '"':
        if src[i] == "\\":
            if src[i + 1] == "(":  # interpolation: skip to the matching paren, nested literals included
                depth, i = 1, i + 2
                while depth:
                    if src[i] == '"':
                        _, i = read_literal(src, i)
                        continue
                    depth += {"(": 1, ")": -1}.get(src[i], 0)
                    i += 1
                out.append(MARK)
                continue
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(src[i + 1], src[i + 1]))
            i += 2
            continue
        out.append(src[i])
        i += 1
    return "".join(out), i + 1


def norm(key):
    return re.sub(r"%(?:\d+\$)?(?:@|lld)", MARK, key)


def table_keys():
    keys = set()
    for k, s in json.loads((ROOT / "tools/l10n/strings.json").read_text()).items():
        if s.get("only") == "android" or k.startswith("plist_"):
            continue
        en = s["en"]["other"] if isinstance(s["en"], dict) else s["en"]
        en = re.sub(r"%(\d+)\$[sd]", "%", en).replace("%s", "%@").replace("%d", "%lld").replace("%%", "%")
        keys.add(norm(en))
    return keys


def main():
    keys = table_keys()
    bad = 0
    for f in sorted((ROOT / "ios/Spinwell").glob("*.swift")):
        src = f.read_text()
        for m in START.finditer(src):
            if src[: m.start()].rstrip().endswith("verbatim:"):
                continue
            text, _ = read_literal(src, m.end() - 1)
            if text and norm(text) not in keys:
                line = src.count("\n", 0, m.start()) + 1
                print(f"{f.name}:{line}: not in strings.json: {text.replace(MARK, '%?')!r}")
                bad += 1
        for m in re.finditer(r'\b(?:Text|Button|Label)\((?:(?!systemImage)[^()"\n])*(?<!\?)\?(?!\?)\s*"[^"\n]*"\s*:', src):  # Text(cond ? "a" : "b") is a String: never localized
            print(f"{f.name}:{src.count(chr(10), 0, m.start()) + 1}: ternary of literals gives a String, branch with Text(...) instead")
            bad += 1
    print("ok" if not bad else f"{bad} problems")
    sys.exit(1 if bad else 0)


main()
