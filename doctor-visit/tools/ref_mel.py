#!/usr/bin/env python3
"""Reference log-mel for a WAV, numpy only, following WhisperFeatureExtractor.

Written next to the WAV as <name>.mel<n>.bin (float32, [n_mels][3000]) for the
JVM test that holds the Kotlin port to it.
"""
import sys, wave
import numpy as np

wav, n_mels, filt, out = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
with wave.open(wav) as w:
    x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
x = x[: 480000]
x = np.pad(x, (0, 480000 - len(x)))
filters = np.fromfile(filt, dtype="<f4").reshape(n_mels, 201)
win = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 400)).astype(np.float64)
xp = np.pad(x.astype(np.float64), (200, 200), mode="reflect")
frames = np.lib.stride_tricks.sliding_window_view(xp, 400)[::160][:3001]
spec = np.abs(np.fft.rfft(frames * win, axis=1)) ** 2          # [3001, 201]
mel = np.maximum(filters.astype(np.float64) @ spec.T, 1e-10)   # [n_mels, 3001]
log = np.log10(mel)[:, :-1]
log = np.maximum(log, log.max() - 8.0)
log = (log + 4.0) / 4.0
log.astype("<f4").tofile(out)
print("wrote", out, log.shape, float(log.min()), float(log.max()))
