#!/usr/bin/env python3
"""Word (or, for Indic scripts, character) error rate of lab ASR reports."""
import json, re, sys, glob, os, unicodedata

def norm(s):
    s = unicodedata.normalize("NFC", s.lower())
    s = re.sub(r"[^\w\s]", " ", s)
    return s.split()

def edit(a, b):
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
            prev = cur
    return d[len(b)]

for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.asr.*.json"))):
    r = json.load(open(f))
    ref, hyp = norm(r["reference"]), norm(r["transcript"])
    latin = all(ord(c) < 0x250 for c in "".join(ref))
    if latin:
        rate, unit = edit(ref, hyp) / max(1, len(ref)), "WER"
    else:
        rc, hc = list("".join(ref)), list("".join(hyp))
        rate, unit = edit(rc, hc) / max(1, len(rc)), "CER"
    st = {s["name"]: s["ms"] for s in r.get("stages", [])}
    print(f"{r['case']:22s} {r['model']:6s} lang={r.get('language')!s:3s} {unit}={rate:5.1%}  run={st.get('whisper-run','?')}ms  load={st.get('whisper-load','?')}ms")
    if len(sys.argv) > 2:
        print("   REF:", r["reference"][:170]); print("   HYP:", r["transcript"][:170])
