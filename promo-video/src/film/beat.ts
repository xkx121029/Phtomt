/**
 * 节拍网格 —— 全片时间线的唯一事实源。
 *
 * 源：assets/audio/bgm/cat-walk.mp3（Arulo · House · Mixkit Free License）
 * 分析产物：analysis/beat_data.json（fit 残差 ±10.5ms，验收通过）
 * BGM 已裁成 public/audio/bgm.wav，其 0 秒 = 第 0 拍。
 */
export const FPS = 30;
export const BPM = 130.014;
export const BEAT_T = 0.46149; // 秒/拍，来自最小二乘拟合，不取整

/** 拍 → 帧。全片所有镜头边界与重音都用它表达，不写裸帧号。 */
export const beatF = (n: number): number => Math.round(n * BEAT_T * FPS);

/** 镜头内局部帧 → 该镜头内的第 n 拍 */
export const localBeat = (shotFrom: number, n: number): number => beatF(n) - shotFrom;

export const TOTAL_BEATS = 104;
export const DURATION = beatF(TOTAL_BEATS); // 1440f = 48.0s

/**
 * 镜头表（DESIGN-SPEC §4）。`from/to` 是绝对帧，全部由拍号派生。
 * 改拍号即全片重排；不要手改帧号。
 */
export const SHOTS = {
  s1_sentence: { from: beatF(0), to: beatF(12) },
  s2_rise: { from: beatF(12), to: beatF(28) },
  s3_plan: { from: beatF(28), to: beatF(40) },
  s4_scan: { from: beatF(40), to: beatF(52) },
  s5_exec: { from: beatF(52), to: beatF(64) },
  s6_done: { from: beatF(64), to: beatF(76) },
  s7_wall: { from: beatF(76), to: beatF(84) },
  s8_outro: { from: beatF(84), to: beatF(104) },
} as const;

export type ShotId = keyof typeof SHOTS;

export const shotDur = (id: ShotId) => SHOTS[id].to - SHOTS[id].from;

/**
 * 全画面级冲击白名单（R4 / music-beat-sync §4）。
 * 全片只有 2 个名额，间隔 16 拍，钉在实测最强 kick 上：
 *   b72 kick 0.94（全片最强，S6 完成屏数字砸落）
 *   b88 kick 0.90（S8 元素合拢定型）
 * 字标落定（b96）只做元素层（letterpress + rule + 舞台光），不占名额。
 */
export const SLAMS = [72, 88] as const;

/** 音乐结构（来自逐拍 kick 能量表，见 analysis/beat_data.json） */
export const SECTIONS = {
  build: [0, 52],
  drop: 52,
  peak: [52, 84],
  breakdown: [84, 88],
  finalSlam: 88,
} as const;
