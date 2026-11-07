"""渲后音轨偏移实测：把 SFX 原文件对渲出音轨做归一化互相关。

用法：
  python tools/measure_offset.py out/promo.mp4 997 1218

输出每个探针的实测落点与「设计帧号 − 实测落点」的残差。
残差一致（同一管线常数）才说明量准了；只测一个点可能撞上音源自身的峰值滞后。
"""
import subprocess
import sys
import wave

import numpy as np

SR = 48000
FPS = 30


def decode(path, sr=SR):
    raw = f"render-audio-{sr}.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-vn", "-ac", "1",
         "-ar", str(sr), "-acodec", "pcm_s16le", raw],
        check=True,
    )
    with wave.open(raw) as w:
        n = w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float64) / 32768.0
    return data


def src_samples(path, sr=SR):
    raw = "src-probe.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-vn", "-ac", "1",
         "-ar", str(sr), "-acodec", "pcm_s16le", raw],
        check=True,
    )
    with wave.open(raw) as w:
        n = w.getnframes()
        return np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float64) / 32768.0


def best_lag(mix, probe, around, half=1.0):
    """在 around 秒附近 ±half 秒内找与 probe 相关性最高的位置"""
    lo = max(0, int((around - half) * SR))
    hi = min(len(mix), int((around + half) * SR) + len(probe))
    seg = mix[lo:hi]
    p = probe - probe.mean()
    corr = np.correlate(seg - seg.mean(), p, mode="valid")
    idx = int(np.argmax(np.abs(corr)))
    return (lo + idx) / SR


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    video = sys.argv[1]
    probes = [int(x) for x in sys.argv[2:]]
    mix = decode(video)
    impact = src_samples("public/audio/sfx/fs-755054.mp3")
    res = []
    # 互相关给出的是「音源文件头」的落点，峰值还要加上文件自身的峰值位置
    own = float(np.argmax(np.abs(impact))) / SR * FPS
    print(f"探针文件自身峰值：{own:.2f}f")
    for f in probes:
        start = best_lag(mix, impact, f / FPS, half=0.5)
        peak_f = start * FPS + own
        res.append((f, peak_f, f - peak_f))
        print(f"设计 {f}f = {f/FPS:.3f}s  →  实测峰值 {peak_f:.2f}f  →  残差 {f - peak_f:+.2f}f")
    if len(res) > 1:
        rr = [r[2] for r in res]
        print(f"残差一致性：{max(rr)-min(rr):.2f}f（≤0.5f 为合格）")


if __name__ == "__main__":
    main()
