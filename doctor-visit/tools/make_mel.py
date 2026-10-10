#!/usr/bin/env python3
"""Whisper's mel filterbank, written for the app to load.

The same construction as transformers' WhisperFeatureExtractor: 201 frequency
bins (n_fft 400 at 16 kHz), 0-8 kHz, Slaney mel scale and Slaney area
normalisation. Written as float32 little-endian, [n_mels][201].
"""
import os, sys
import numpy as np

def hz_to_mel(f):
    f = np.asarray(f, dtype=np.float64)
    min_log_hz, min_log_mel, logstep = 1000.0, 15.0, 27.0 / np.log(6.4)
    mel = 3.0 * f / 200.0
    return np.where(f >= min_log_hz, min_log_mel + np.log(np.maximum(f, 1e-10) / min_log_hz) * logstep, mel)

def mel_to_hz(m):
    m = np.asarray(m, dtype=np.float64)
    min_log_hz, min_log_mel, logstep = 1000.0, 15.0, np.log(6.4) / 27.0
    f = 200.0 * m / 3.0
    return np.where(m >= min_log_mel, min_log_hz * np.exp(logstep * (m - min_log_mel)), f)

def mel_filters(n_mels, n_freq=201, sr=16000, fmin=0.0, fmax=8000.0):
    fft_freqs = np.linspace(0, sr // 2, n_freq)
    mel_pts = np.linspace(hz_to_mel(fmin), hz_to_mel(fmax), n_mels + 2)
    filter_freqs = mel_to_hz(mel_pts)
    fdiff = np.diff(filter_freqs)
    slopes = np.expand_dims(filter_freqs, 0) - np.expand_dims(fft_freqs, 1)
    down = -slopes[:, :-2] / fdiff[:-1]
    up = slopes[:, 2:] / fdiff[1:]
    fb = np.maximum(np.zeros(1), np.minimum(down, up))          # [n_freq, n_mels]
    enorm = 2.0 / (filter_freqs[2 : n_mels + 2] - filter_freqs[:n_mels])
    fb *= np.expand_dims(enorm, 0)
    return fb.T.astype(np.float32)                                # [n_mels, n_freq]

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for n in (80, 128):
    m = mel_filters(n)
    m.astype("<f4").tofile(os.path.join(out, f"mel_{n}.bin"))
    print(n, m.shape, float(m.sum()))
