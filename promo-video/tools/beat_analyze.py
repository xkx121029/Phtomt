"""节拍网格测定 + 鼓点分类 + 能量结构（music-beat-sync.md §1-3）。

用法：uv run --with librosa --with scipy --python 3.11 beat_analyze.py <audio> <outdir>
输出 beat_data.json / grid_drift.json。
"""
import json
import os
import sys

import numpy as np
import librosa


def band_env(y, sr, lo, hi):
    from scipy.signal import butter, sosfilt

    sos = butter(4, [lo, hi], btype="band", fs=sr, output="sos")
    env = librosa.onset.onset_strength(y=sosfilt(sos, y), sr=sr, hop_length=128)
    return env, librosa.times_like(env, sr=sr, hop_length=128)


def fit_grid(beats):
    i = np.arange(len(beats))
    A = np.vstack([i, np.ones_like(i)]).T
    (T, t0), *_ = np.linalg.lstsq(A, beats, rcond=None)
    residual = beats - (t0 + i * T)
    return float(T), float(t0), residual


def grid_metrics(beats, T, t0, onsets):
    """按 §3 四指标验收：用网格拍对齐最近的真实瞬态。"""
    n = int((beats[-1] - t0) / T) + 1
    grid = t0 + np.arange(n) * T
    # 每个网格拍找最近瞬态
    d = np.abs(grid[:, None] - onsets[None, :])
    near = d.min(axis=1)
    tol = 0.015  # 15ms
    match = float((near <= tol).mean())
    mean_abs_ms = float(near.mean() * 1000)
    return n, match, mean_abs_ms


def main():
    src = sys.argv[1]
    outdir = sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    y, sr = librosa.load(src, sr=None, mono=True)
    dur = len(y) / sr
    print(f"loaded {src}  sr={sr}  dur={dur:.2f}s")

    # 打击成分（hpss）后再测，避免人声/贝斯把攻击埋掉
    # hpss 返回 (harmonic, percussive)——鼓点检测必须用 percussive
    _harm, y_perc = librosa.effects.hpss(y)
    yh = y_perc / (np.abs(y_perc).max() + 1e-9)

    onsets = librosa.onset.onset_detect(y=yh, sr=sr, backtrack=False, units="time")

    tempo, beats = librosa.beat.beat_track(
        y=yh, sr=sr, tightness=400, units="time", trim=False
    )
    print(f"beat_track tempo={float(np.atleast_1d(tempo)[0]):.2f}  beats={len(beats)}")

    T, t0, residual = fit_grid(beats)
    bpm = 60.0 / T
    print(f"BPM={bpm:.3f}  t0={t0:.4f}s  T={T:.6f}s  残差max={np.abs(residual).max()*1000:.1f}ms")

    # 半倍/双倍歧义：三个候选各算指标
    cands = []
    for mult in (0.5, 1.0, 2.0):
        Tc = T / mult
        # 相位以 beats[0] 为锚
        bc = t0 + np.arange(int((beats[-1] - t0) / Tc) + 1) * Tc
        n, match, mean_abs_ms = grid_metrics(bc, Tc, t0, onsets)
        cands.append(
            dict(mult=mult, bpm=60.0 / Tc, n=n, match=round(match, 4),
                 mean_abs_ms=round(mean_abs_ms, 2))
        )
    best = max(cands, key=lambda c: (c["match"], -c["mean_abs_ms"]))
    print("grid candidates:", json.dumps(cands, ensure_ascii=False))
    print("winner:", best)

    T = 60.0 / best["bpm"]
    nb = int((beats[-1] - t0) / T) + 1
    grid = t0 + np.arange(nb) * T

    # 鼓点三分类
    hits = []
    for kind, lo, hi in (("kick", 40, 160), ("snare", 150, 500), ("hihat", 6000, 14000)):
        env, times = band_env(yh, sr, lo, hi)
        env = env / (env.max() + 1e-9)
        peak = librosa.util.peak_pick(
            env, pre_max=3, post_max=3, pre_avg=6, post_avg=6, delta=0.12, wait=4
        )
        for p in peak:
            hits.append(
                dict(t=round(float(times[p]), 4), s=round(float(env[p]), 3), k=kind)
            )
    hits.sort(key=lambda h: h["t"])

    # 每拍 kick 能量（用于挑最强 hit）
    env_k, times_k = band_env(yh, sr, 40, 160)
    env_k = env_k / (env_k.max() + 1e-9)
    beat_kick = []
    for n, t in enumerate(grid):
        idx = int(np.argmin(np.abs(times_k - t)))
        beat_kick.append(dict(b=n, t=round(float(t), 4), e=round(float(env_k[idx]), 3)))
    top = sorted(beat_kick, key=lambda x: -x["e"])[:12]

    # RMS 能量结构（4s 窗）
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=512)[0]
    rms_t = librosa.times_like(rms, sr=sr, hop_length=512)
    win = 1.0
    sec = []
    tt = 0.0
    while tt < dur:
        m = (rms_t >= tt) & (rms_t < tt + win)
        if m.any():
            sec.append(dict(t=round(tt, 2), rms=round(float(rms[m].mean()), 5)))
        tt += win

    data = dict(
        src=os.path.basename(src), sr=sr, duration=dur, bpm=best["bpm"],
        t0=t0, T=T, nbeats=nb,
        beats=[round(float(b), 4) for b in grid],
        hits=hits, beat_kick=beat_kick, top_kick=top, rms=sec,
    )
    with open(os.path.join(outdir, "beat_data.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    with open(os.path.join(outdir, "grid_drift.json"), "w", encoding="utf-8") as f:
        json.dump(
            dict(candidates=cands, winner=best,
                 residual_max_ms=round(float(np.abs(residual).max() * 1000), 2),
                 reason="按 §3 match 最高、mean_abs_ms 最低投票"),
            f, ensure_ascii=False, indent=1,
        )

    print("top kicks:", json.dumps(top[:8], ensure_ascii=False))
    print("rms tail:", json.dumps(sec[-6:], ensure_ascii=False))
    print("wrote", outdir)


if __name__ == "__main__":
    main()
