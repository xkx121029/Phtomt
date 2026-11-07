"""在选定窗口内定稿节拍网格，并把 BGM 裁成与拍 0 严格对齐的 wav。

用法: uv run --with librosa --with scipy --python 3.11 tools/finalize_grid.py <audio> <start_t> <nbeats> <outjson> <outwav>
"""
import json
import subprocess
import sys

import numpy as np
import librosa
from scipy.signal import butter, sosfilt

SRC, START, NB = sys.argv[1], float(sys.argv[2]), int(sys.argv[3])
OUTJ, OUTW = sys.argv[4], sys.argv[5]
SR_OUT = 48000

y, sr = librosa.load(SRC, sr=None, mono=True)
harm, perc = librosa.effects.hpss(y)
perc = perc / (np.abs(perc).max() + 1e-9)


def band(sig, lo, hi, hop=64):
    sos = butter(4, [lo, hi], btype="band", fs=sr, output="sos")
    env = librosa.onset.onset_strength(y=sosfilt(sos, sig), sr=sr, hop_length=hop)
    return env / (env.max() + 1e-9), librosa.times_like(env, sr=sr, hop_length=hop)


env_k, t_k = band(perc, 40, 160)
env_s, t_s = band(perc, 150, 500)
env_h, t_h = band(perc, 6000, 14000)


def at(env, times, x):
    return float(env[int(np.argmin(np.abs(times - x)))])


# 只在窗口内拟合，避免全曲漂移
lo, hi = START - 2.0, START + NB * 0.6
tempo, beats = librosa.beat.beat_track(y=perc, sr=sr, tightness=400, units="time")
beats = beats[(beats > lo) & (beats < hi)]
i = np.arange(len(beats))
A = np.vstack([i, np.ones_like(i)]).T
(T, t0), *_ = np.linalg.lstsq(A, beats, rcond=None)
resid = float(np.abs(beats - (t0 + i * T)).max() * 1000)
print(f"window fit: beats={len(beats)} T={T:.6f} bpm={60/T:.3f} t0(unwrapped)={t0:.4f} resid±{resid:.1f}ms")

# 相位校正：让网格落在 kick 上（librosa 常锁到反拍）
best_ph, best_v = 0.0, -1.0
for ph in np.arange(0, T, T / 96):
    g = t0 + ph + np.arange(int((hi - t0) / T)) * T
    g = g[(g >= lo) & (g < hi)]
    v = np.mean([at(env_k, t_k, x) for x in g])
    if v > best_v:
        best_v, best_ph = v, ph
print(f"phase correction {best_ph:.5f}s ({best_ph/T:.3f}T)  kick mean {best_v:.4f}")

# 找最接近 START 的网格拍，作为拍 0
raw = t0 + best_ph + np.arange(-4, int((hi - t0) / T) + 4) * T
b0 = float(raw[np.argmin(np.abs(raw - START))])
print(f"beat0 = {b0:.4f}s (target {START})")

beats_out = [round(b0 + k * T, 5) for k in range(NB)]
beat_kick = [dict(b=k, e=round(at(env_k, t_k, t), 3)) for k, t in enumerate(beats_out)]
top = sorted(range(NB), key=lambda k: -beat_kick[k]["e"])[:16]
print("strongest kick beats:", [(k, beat_kick[k]["e"]) for k in top[:10]])

hits = []
for kind, env, times in (("kick", env_k, t_k), ("snare", env_s, t_s), ("hihat", env_h, t_h)):
    pk = librosa.util.peak_pick(env, pre_max=3, post_max=3, pre_avg=6, post_avg=6,
                                delta=0.14, wait=4)
    for p in pk:
        t = float(times[p])
        if beats_out[0] - 0.5 <= t <= beats_out[-1] + 1.0:
            hits.append(dict(t=round(t - b0, 4), s=round(float(env[p]), 3), k=kind))
hits.sort(key=lambda h: h["t"])

json.dump(dict(src=SRC.split("/")[-1], bpm=60 / T, T=T, beat0_abs=b0, nbeats=NB,
               duration=NB * T, beats_abs=beats_out,
               beats_rel=[round(k * T, 5) for k in range(NB)],
               hits=hits, beat_kick=beat_kick, top_kick=top, resid_ms=round(resid, 2)),
          open(OUTJ, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

# 裁 BGM：从 beat0 起，多留 1.2s 尾巴
dur = NB * T + 1.2
subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", f"{b0:.5f}", "-t", f"{dur:.4f}",
                "-i", SRC, "-ar", str(SR_OUT), "-ac", "2", OUTW], check=True)
print(f"bgm cut → {OUTW}  ({dur:.3f}s)")
print("wrote", OUTJ)
