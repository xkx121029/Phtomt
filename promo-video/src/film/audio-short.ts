import { beatF } from './beat';
import { sfxFrom } from './audio';

/**
 * 15s 横版短版（32 拍 = 443f）。
 * 结构：钩子(5) → 扫控件(8) → 自己动手(7) → 品牌(12)，drop 落在第 13 拍（= 原曲 b72）。
 */
export const SHORT = {
  t1: 0,
  t2: beatF(5),
  t3: beatF(13),
  t4: beatF(20),
  total: beatF(32),
} as const;

const T2 = SHORT.t2;
const T3 = SHORT.t3;
const T4 = SHORT.t4;

/** S4 四个取景框在短版里的锁定时点（与 S4Scan 同公式，只是起点不同） */
const lockF = (y2: number) =>
  T2 + Math.round(beatF(0.5) + ((y2 + 30) / 952) * (beatF(7.6) - beatF(0.5)));

export type ShortCue = {
  note: string;
  from: number;
  src: string;
  volume: number;
  durationInFrames?: number;
};

const IMPACT = 'fs-755054.mp3';
const WHOOSH = 'fs-427823.mp3';
const RISER = 'fs-859482.mp3';
const SPARKLE = 'fs-844398-norm.mp3';
const CLICK = 'fs-278205.mp3';
const TYPE = 'fs-527726.mp3';

export const SHORT_SFX: ShortCue[] = [
  { note: 'T1 打字', from: 8, src: TYPE, volume: 0.85, durationInFrames: 30 },
  { note: 'T2 转场气声', from: sfxFrom(T2 + 4, WHOOSH), src: WHOOSH, volume: 0.45, durationInFrames: 26 },
  { note: 'T2 锁定 亮度', from: sfxFrom(lockF(211), CLICK), src: CLICK, volume: 0.36, durationInFrames: 9 },
  { note: 'T2 锁定 锁屏', from: sfxFrom(lockF(325), CLICK), src: CLICK, volume: 0.32, durationInFrames: 9 },
  { note: 'T2 锁定 显示大小', from: sfxFrom(lockF(627), CLICK), src: CLICK, volume: 0.28, durationInFrames: 9 },
  { note: 'T2 锁定 屏保', from: sfxFrom(lockF(772), CLICK), src: CLICK, volume: 0.24, durationInFrames: 9 },
  { note: 'T3 drop（本片最重）', from: sfxFrom(T3, IMPACT), src: IMPACT, volume: 0.8, durationInFrames: 46 },
  { note: 'T3 第 1 步落定', from: sfxFrom(T3 + beatF(1.6), CLICK), src: CLICK, volume: 0.46, durationInFrames: 10 },
  { note: 'T3 第 2 步落定', from: sfxFrom(T3 + beatF(3.4), CLICK), src: CLICK, volume: 0.4, durationInFrames: 10 },
  { note: 'T3 第 3 步落定', from: sfxFrom(T3 + beatF(5.2), CLICK), src: CLICK, volume: 0.34, durationInFrames: 10 },
  { note: 'T4 落标冲击', from: sfxFrom(T4 + beatF(4), IMPACT), src: IMPACT, volume: 0.78, durationInFrames: 50 },
  { note: 'T4 riser 铺到落标', from: sfxFrom(T4 + beatF(4), RISER), src: RISER, volume: 0.5, durationInFrames: 84 },
  { note: 'T4 字标余韵', from: sfxFrom(T4 + beatF(9), SPARKLE), src: SPARKLE, volume: 0.7, durationInFrames: 60 },
];

export const SHORT_BGM = {
  file: 'audio/bgm-short.mp3',
  volume: 0.44,
  fadeIn: 8,
  fadeOut: 26,
};
