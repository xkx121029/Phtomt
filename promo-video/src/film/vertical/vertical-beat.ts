import { DURATION, beatF } from '../beat';

/**
 * 竖版 9:16 长版（1080×1920）。
 *
 * 与横版共用同一条节拍网格、同一张镜头表、同一套 SFX 钉帧表——
 * 竖版不是「另一个片子」，是同一支片在另一个画幅里的重新构图。
 * 因此 total 必须等于 beatF(104)，拍号一个不改，只是每镜的机位与分区重排。
 */
export const VERTICAL = {
  total: DURATION,
  /** 安全区左右边距 */
  margin: 76,
  /** 眉标基线 */
  kickerY: 148,
  /** 主标题顶部 */
  headY: 206,
  /** 底部字幕基线（距底） */
  captionBottom: 132,
} as const;

/**
 * 竖版构图分区。9:16 的语法和 16:9 不同：
 * 横版是「设备在左、字在右下」的横向对位，竖版是「字在上、设备在下、下缘出血」的纵向叠置。
 * 设备因此可以放到 sw≈600–620（屏高 1300–1340），几乎是满幅——这是竖版最大的红利。
 */
export const VDEV = {
  /** 顶部留白 → 设备上缘 */
  top: 560,
  sw: 600,
} as const;

/**
 * 竖版设备摆放：Device 的原点是画幅中心（540, 960），
 * 要把机身顶端压到 `top`，偏移量就是 top + 机身半高 − 960。
 */
export const vDevY = (sw: number, top: number) => {
  const bezel = sw * 0.033;
  const bodyH = (sw * 892) / 412 + bezel * 2;
  return Math.round(top + bodyH / 2 - 960);
};

/** 竖版每镜的拍号（与横版 SHOTS 完全同值，只是收在一处便于竖版镜头引用） */
export const VB = {
  s1: 0,
  s2: 12,
  s3: 28,
  s4: 40,
  s5: 52,
  s6: 64,
  s7: 76,
  s8: 84,
  end: 104,
} as const;

export { beatF };
