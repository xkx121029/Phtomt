"""给候选 BGM 各找 104 拍最佳窗口，按「能量水平 + 后段更强」排序。

用法: uv run --with librosa --with scipy --python 3.11 tools/pick_bgm.py <dir> [nbeats]
"""
import glob
import os
import sys

import numpy as np
import librosa
from scipy.signal import butter, sosfilt

DIR = sys.argv[1]
NB = int(sys.argv[2]) if len(sys.argv) > 2 else 104


def analyze(path):
    y, sr = librosa.load(path, sr=None, mono=True)
    _, perc = librosa.effects.hpss(y)
    perc = perc / (np.abs(perc).max() + 1e-9)
    sos = butter(4, [40, 160], btype="band", fs=sr, output="sos")
    env = librosa.onset.onset_strength(y=sosfilt(sos, perc), sr=sr, hop_length=64)
    env = env / (env.max() + 1e-9)
    tt = librosa.times_like(env, sr=sr, hop_length=64)

    tempo, beats = librosa.beat.beat_track(y=perc, sr=sr, tightness=400, units="time")
    i = np.arange(len(beats))
    A = np.vstack([i, np.ones_like(i)]).T
    (T, t0), *_ = np.linalg.lstsq(A, beats, rcond=None)
    resid = float(np.abs(beats - (t0 + i * T)).max() * 1000)

    def at(x):
        return float(env[int(np.argmin(np.abs(tt - x)))])

    # 相位校正：在 0..T 内扫，取 kick 包络均值最高的相位
    best_ph, best_v = 0.0, -1
    for ph in np.arange(0, T, T / 48):
        g = t0 + ph + np.arange(int((120 - t0) / T)) * T
        g = g[g < 118]
        v = np.mean([at(x) for x in g])
        if v > best_v:
            best_v, best_ph = v, ph
    t0 = t0 + best_ph

    g = t0 + np.arange(int((len(y) / sr - t0) / T)) * T
    e = np.array([at(x) for x in g])

    best = None
    for s in range(0, max(1, len(e) - NB)):
        seg = e[s:s + NB]
        head, tail = seg[: NB // 2].mean(), seg[NB // 2:].mean()
        score = seg.mean() * 0.6 + (tail - head) * 2.0 + tail * 0.8
        if best is None or score > best[0]:
            best = (score, s, seg.mean(), head, tail, seg.min())
    return dict(file=os.path.basename(path), bpm=60 / T, t0=float(t0), T=float(T),
                resid_ms=round(resid, 1), start_beat=best[1],
                start_t=round(float(t0 + best[1] * T), 3),
                mean=round(float(best[2]), 3), head=round(float(best[3]), 3),
                tail=round(float(best[4]), 3), min=round(float(best[5]), 3),
                end_t=round(float(t0 + (best[1] + NB) * T), 3))


rows = []
for p in sorted(glob.glob(os.path.join(DIR, "*.mp3"))):
    try:
        r = analyze(p)
        r["score"] = round(r["mean"] * 0.6 + (r["tail"] - r["head"]) * 2.0 + r["tail"] * 0.8, 3)
        rows.append(r)
        print(f"{r['file']:24s} bpm={r['bpm']:7.3f} resid±{r['resid_ms']:5.1f}ms  "
              f"start=b{r['start_beat']}@{r['start_t']}s  mean={r['mean']:.3f} "
              f"head={r['head']:.3f} tail={r['tail']:.3f}  score={r['score']:.3f}")
    except Exception as ex:  # noqa: BLE001
        print("FAIL", p, ex)

print("\nranked:")
for r in sorted(rows, key=lambda x: -x["score"]):
    print(f"  {r['score']:.3f}  {r['file']}")
