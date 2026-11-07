import { SHOTS, beatF } from './beat';

/**
 * 声音时间线（唯一事实源）。画面组件不内嵌音频，全部在这里集中钉帧。
 * 卡点片一律写 `SHOTS.<shot>.from + beatF(n)`，不写裸绝对帧号。
 */

/** 输出音轨偏移：Remotion 4.x + AAC 48k mp4 的 encoder priming，渲后交叉相关实测校正 */
export const OUTPUT_AUDIO_OFFSET_F = 0.56;

/** 各音源自身的能量峰滞后（帧），来自 audio-manifest.json 的 energy_peak_seconds × 30 */
const PEAK_F: Record<string, number> = {
  'fs-755054.mp3': 6, // impact 0.215s
  'fs-427823.mp3': 6, // whoosh 0.215s
  'fs-859482.mp3': 69, // riser 2.285s
  'fs-844398-norm.mp3': 20, // sparkle 0.665s
  'fs-278205.mp3': 1, // click 0.035s
  'fs-527726.mp3': 0, // typing（连续音，不按峰值对齐）
};

export const sfxFrom = (targetPeakF: number, src: string) =>
  Math.max(0, Math.round(targetPeakF - (PEAK_F[src] ?? 0) - OUTPUT_AUDIO_OFFSET_F));

const IMPACT = 'fs-755054.mp3';
const WHOOSH = 'fs-427823.mp3';
const RISER = 'fs-859482.mp3';
const SPARKLE = 'fs-844398-norm.mp3';
const CLICK = 'fs-278205.mp3';
const TYPE = 'fs-527726.mp3';

export type Cue = {
  /** 动作说明，便于终检逐条对照画面 */
  note: string;
  from: number;
  src: string;
  volume: number;
  durationInFrames?: number;
};

const S1 = SHOTS.s1_sentence.from;
const S2 = SHOTS.s2_rise.from;
const S3 = SHOTS.s3_plan.from;
const S4 = SHOTS.s4_scan.from;
const S5 = SHOTS.s5_exec.from;
const S6 = SHOTS.s6_done.from;
const S7 = SHOTS.s7_wall.from;
const S8 = SHOTS.s8_outro.from;

/** S4 四个控件的取景框锁定时点（扫描线越过 bbox 下缘那一帧，与 S4Scan 同公式） */
const lockF = (y2: number) =>
  S4 + Math.round(beatF(0.5) + ((y2 + 30) / 952) * (beatF(7.6) - beatF(0.5)));

export const SFX: Cue[] = [
  // S1 · 一句话被打出来
  { note: 'S1 打字', from: S1 + beatF(2.2), src: TYPE, volume: 0.85, durationInFrames: Math.round(11 * 2.2) + 4 },

  // S2 · 设备升起落地，气声托住
  { note: 'S2 升起落定', from: sfxFrom(S2 + beatF(6), WHOOSH), src: WHOOSH, volume: 0.55, durationInFrames: 30 },

  // S3 · 计划逐行揭盖 + 批准点击
  { note: 'S3 第 1 行揭盖', from: sfxFrom(S3 + beatF(1.0), CLICK), src: CLICK, volume: 0.42, durationInFrames: 10 },
  { note: 'S3 第 2 行揭盖', from: sfxFrom(S3 + beatF(2.8), CLICK), src: CLICK, volume: 0.36, durationInFrames: 10 },
  { note: 'S3 第 3 行揭盖', from: sfxFrom(S3 + beatF(4.6), CLICK), src: CLICK, volume: 0.32, durationInFrames: 10 },
  { note: 'S3 批准执行', from: sfxFrom(S3 + beatF(9), CLICK), src: CLICK, volume: 0.46, durationInFrames: 12 },

  // S4 · 四个控件依次锁定（音量阶梯递减，防机枪感）
  { note: 'S4 锁定 亮度', from: sfxFrom(lockF(211), CLICK), src: CLICK, volume: 0.36, durationInFrames: 9 },
  { note: 'S4 锁定 锁屏', from: sfxFrom(lockF(325), CLICK), src: CLICK, volume: 0.32, durationInFrames: 9 },
  { note: 'S4 锁定 显示大小', from: sfxFrom(lockF(627), CLICK), src: CLICK, volume: 0.28, durationInFrames: 9 },
  { note: 'S4 锁定 屏保', from: sfxFrom(lockF(772), CLICK), src: CLICK, volume: 0.24, durationInFrames: 9 },

  // S5 · drop：执行开始的那一下
  { note: 'S5 drop 落拍', from: sfxFrom(S5, IMPACT), src: IMPACT, volume: 0.62, durationInFrames: 46 },
  { note: 'S5 第 1 步落定', from: sfxFrom(S5 + beatF(1.6), CLICK), src: CLICK, volume: 0.48, durationInFrames: 10 },
  { note: 'S5 第 2 步落定', from: sfxFrom(S5 + beatF(3.4), CLICK), src: CLICK, volume: 0.42, durationInFrames: 10 },
  { note: 'S5 第 3 步落定', from: sfxFrom(S5 + beatF(5.2), CLICK), src: CLICK, volume: 0.36, durationInFrames: 10 },

  // S6 · 计数跳动 → b72 全片最强 kick
  { note: 'S6 计数 2', from: sfxFrom(S6 + beatF(2.6), CLICK), src: CLICK, volume: 0.4, durationInFrames: 9 },
  { note: 'S6 计数 3', from: sfxFrom(S6 + beatF(5.2), CLICK), src: CLICK, volume: 0.42, durationInFrames: 9 },
  { note: 'S6 3/3 砸落', from: sfxFrom(S6 + beatF(8), IMPACT), src: IMPACT, volume: 0.78, durationInFrames: 50 },

  // S7 · 四宫格硬切，一格格亮起来
  { note: 'S7 第 1 格', from: sfxFrom(S7, CLICK), src: CLICK, volume: 0.38, durationInFrames: 9 },
  { note: 'S7 第 2 格', from: sfxFrom(S7 + 4, CLICK), src: CLICK, volume: 0.34, durationInFrames: 9 },
  { note: 'S7 第 3 格', from: sfxFrom(S7 + 8, CLICK), src: CLICK, volume: 0.3, durationInFrames: 9 },
  { note: 'S7 第 4 格', from: sfxFrom(S7 + 12, CLICK), src: CLICK, volume: 0.26, durationInFrames: 9 },
  { note: 'S7 换色板', from: sfxFrom(S7 + beatF(4), CLICK), src: CLICK, volume: 0.32, durationInFrames: 10 },

  // S8 · riser → 落标 → 余韵（镜头收束句式）
  { note: 'S8 riser 铺到 b88', from: sfxFrom(S8 + beatF(4), RISER), src: RISER, volume: 0.5, durationInFrames: 90 },
  { note: 'S8 品牌落定', from: sfxFrom(S8 + beatF(4), IMPACT), src: IMPACT, volume: 0.72, durationInFrames: 52 },
  { note: 'S8 字标余韵', from: sfxFrom(S8 + beatF(12), SPARKLE), src: SPARKLE, volume: 0.78, durationInFrames: 118 },
];

/** BGM 起播与淡入淡出（本片 = 片中 0 秒即第 0 拍） */
export const BGM = {
  file: 'audio/bgm.mp3',
  volume: 0.42,
  fadeIn: 14,
  fadeOut: 34,
};
