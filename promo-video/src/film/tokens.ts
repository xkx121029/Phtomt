/**
 * 全片唯一视觉事实源。取自产品 ui/theme/Color.kt（玄青）与 promo/ui-film.html。
 * 任何镜头不得引入本文件之外的色值。
 */
export const C = {
  bg: '#070908',
  ink: '#E8EAE6',
  ink2: '#B7BDB6',
  ink3: '#6A716B',
  ink4: '#3F4640',
  jade: '#5FD9B4',
  jadeDim: 'rgba(95,217,180,0.16)',
  amber: '#F0A868',
  mist: '#84B6D4',
  panel: '#1B1F1C',
  panel2: '#20251F',
  sunken: '#0C0F0D',
  line: 'rgba(232,234,230,0.10)',
  line2: 'rgba(232,234,230,0.18)',
} as const;

export const F = {
  sans: '"Noto Sans SC","Microsoft YaHei","Source Han Sans SC",sans-serif',
  serif: '"Noto Serif SC","Source Han Serif SC",serif',
  mono: '"Cascadia Mono","JetBrains Mono",Consolas,monospace',
} as const;

/** 设备逻辑屏（与 promo/app.html 的 412×892 同坐标系） */
export const SCREEN = { w: 412, h: 892 } as const;

/** 字幕档位（Q11：1080p 下有效字高 ≥56 / 辅助 ≥32） */
export const TYPE = {
  caption: 56,
  captionLead: 38,
  tag: 30,
  wordmark: 116,
} as const;

/** 动效 tokens：能量中低 × 严肃偏冷，见 DESIGN-SPEC §2 */
export const MOTION = {
  enterFrames: 24,
  ease: [0.16, 1, 0.3, 1] as const,
  camEase: [0.33, 0, 0.15, 1] as const,
  overshoot: 1.035,
} as const;
