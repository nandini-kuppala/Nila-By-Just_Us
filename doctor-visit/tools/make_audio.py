#!/usr/bin/env python3
"""Render testdata/visits.json to 16 kHz mono WAV with the Mac's own voices.

The phone never plays these: the lab app reads the files directly, the same way
it reads a recording, so a test run is silent on the device.
"""
import json, os, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
out = os.path.join(root, "testdata", "audio")
os.makedirs(out, exist_ok=True)
visits = json.load(open(os.path.join(root, "testdata", "visits.json")))["visits"]
only = set(sys.argv[1:])
for v in visits:
    if only and v["id"] not in only:
        continue
    with tempfile.TemporaryDirectory() as tmp:
        aiff = os.path.join(tmp, "v.aiff")
        subprocess.run(["say", "-v", v["voice"], "-r", "165", "-o", aiff, v["transcript"]], check=True)
        wav = os.path.join(out, v["id"] + ".wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav], check=True)
    secs = (os.path.getsize(wav) - 44) / 32000
    print(f"{v['id']:24s} {v['voice']:6s} {secs:5.1f}s")
