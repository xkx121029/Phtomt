"""在片头使用区间内拟合节拍网格，并用「kick 落在整拍 vs 落在半拍」判别倍频歧义。

用法: uv run --with librosa --with scipy --python 3.11 tools/beat_grid.py <audio> <outjson> [sec]
"""
import json
import sys

import numpy as np
import librosa
from scipy.signal import butter, sosfilt

SRC = sys.argv[1]
OUT = sys.argv[2]
WINDOW = float(sys.argv[3]) if len(sys.argv) > 3 else 70.0

y, sr = librosa.load(SRC, sr=None, mono=True, duration=WINDOW + 10)
harm, perc = librosa.effects.hpss(y)
perc = perc / (np.abs(perc).max() + 1e-9)

# kick / snare / hihat 三分类包络
def band_env(sig, lo, hi, hop=128):
    sos = butter(4, [lo, hi], btype="band", fs=sr, output="sos")
    env = librosa.onset.onset_strength(y=sosfilt(sos, sig), sr=sr, hop_length=hop)
    return env / (env.max() + 1e-9), librosa.times_like(env, sr=sr, hop_length=hop)

env_k, t_k = band_env(perc, 40, 160)
env_s, t_s = band_env(perc, 150, 500)
env_h, t_h = band_env(perc, 6000, 14000)

def at(env, times, t):
    i = int(np.argmin(np.abs(times - t)))
    return float(env[i])

tempo, beats = librosa.beat.beat_track(y=perc, sr=sr, tightness=400, units="time")
beats = beats[beats <= WINDOW]
i = np.arange(len(beats))
A = np.vstack([i, np.ones_like(i)]).T
(T, t0), *_ = np.linalg.lstsq(A, beats, rcond=None)
resid = beats - (t0 + i * T)
print(f"beats={len(beats)}  T={T:.6f}s  bpm={60/T:.3f}  t0={t0:.4f}s  resid±{np.abs(resid).max()*1000:.1f}ms")

# 倍频歧义：kick 能量在整拍 vs 半拍位置
def kick_ratio(Tc):
    n = int((WINDOW - t0) / Tc)
    grid = t0 + np.arange(n) * Tc
    half = grid + Tc / 2
    a = np.mean([at(env_k, t_k, t) for t in grid])
    b = np.mean([at(env_k, t_k, t) for t in half])
    return a, b, a / (b + 1e-9)

cands = []
for mult, name in ((0.5, "half"), (1.0, "base"), (2.0, "double")):
    Tc = T / mult
    a, b, r = kick_ratio(Tc)
    cands.append(dict(mult=mult, name=name, bpm=60 / Tc, onbeat=round(a, 4),
                      offbeat=round(b, 4), ratio=round(r, 3)))
    print(f"  {name:6s} bpm={60/Tc:7.3f}  onbeat={a:.3f} offbeat={b:.3f} ratio={r:.2f}")

best = max(cands, key=lambda c: c["ratio"])
T = 60.0 / best["bpm"]
print("winner:", best)

n = int((WINDOW - t0) / T) + 1
grid = [float(t0 + k * T) for k in range(n)]

hits = []
for kind, env, times in (("kick", env_k, t_k), ("snare", env_s, t_s), ("hihat", env_h, t_h)):
    peak = librosa.util.peak_pick(env, pre_max=3, post_max=3, pre_avg=6, post_avg=6,
                                 delta=0.14, wait=4)
    for p in peak:
        if times[p] <= WINDOW + 2:
            hits.append(dict(t=round(float(times[p]), 4), s=round(float(env[p]), 3), k=kind))
hits.sort(key=lambda h: h["t"])

beat_kick = [dict(b=k, t=round(t, 4), e=round(at(env_k, t_k, t), 3)) for k, t in enumerate(grid)]
top = sorted(beat_kick, key=lambda x: -x["e"])[:14]

rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=512)[0]
rt = librosa.times_like(rms, sr=sr, hop_length=512)
sec = []
tt = 0.0
while tt < WINDOW + 2:
    m = (rt >= tt) & (rt < tt + 1.0)
    if m.any():
        sec.append(dict(t=round(tt, 2), rms=round(float(rms[m].mean()), 5)))
    tt += 1.0

json.dump(dict(src=SRC.split("/")[-1], window=WINDOW, bpm=best["bpm"], t0=t0, T=T,
               nbeats=len(grid), beats=[round(b, 4) for b in grid], hits=hits,
               beat_kick=beat_kick, top_kick=top, rms=sec, candidates=cands,
               resid_max_ms=round(float(np.abs(resid).max() * 1000), 2)),
          open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("top kicks:", json.dumps(top[:8], ensure_ascii=False))
print("rms head:", json.dumps(sec[:10], ensure_ascii=False))
print("wrote", OUT)
