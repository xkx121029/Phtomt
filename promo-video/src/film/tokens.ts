import type { CSSProperties } from 'react';

/**
 * 全片唯一视觉事实源。取自产品 ui/theme/Color.kt（玄青）与 promo/ui-film.html。
 * 任何镜头不得引入本文件之外的色值。
 *
 * 双主题机制（深色为默认，浅色版由装配层 setTheme('light') 开启）：
 *   1. `C` 是可变对象，组件里直接用 `C.jade` / `C.bg` 的地方自动跟随主题；
 *   2. 需要拼 alpha 的硬编码三元组统一走根层 CSS 变量 `rgba(var(--rgb-jade),0.5)`，
 *      变量由 `themeCSS()` 挂在全片根 AbsoluteFill 上，后代 inline style 可继承。
 * 深色主题的所有取值与历史版本逐字一致，因此默认渲染结果不发生任何变化。
 */
export type Theme = 'dark' | 'light';

export type Palette = {
  bg: string;
  ink: string;
  ink2: string;
  ink3: string;
  ink4: string;
  jade: string;
  jadeDim: string;
  amber: string;
  mist: string;
  panel: string;
  panel2: string;
  sunken: string;
  line: string;
  line2: string;
};

/** 玄青·暗（原始配色，勿改） */
const DARK: Palette = {
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
};

/**
 * 玄青·亮：与 promo/app.html 的 data-theme="light" 同一套色板
 * （light 色板 jade #0E7C66 / mist #4A7C9B / amber #B0592C）。
 * 舞台底不用纯白而用暖白 #F4F5F2，避免大面积死白；主文字改深墨以保证 Q11 对比度。
 */
const LIGHT: Palette = {
  bg: '#F4F5F2',
  ink: '#1B211D',
  ink2: '#48544C',
  ink3: '#5C685E',
  ink4: '#8C988D',
  jade: '#0E7C66',
  jadeDim: 'rgba(14,124,102,0.14)',
  amber: '#B0592C',
  mist: '#4A7C9B',
  panel: '#FFFFFF',
  panel2: '#EDEFEA',
  sunken: '#E3E8E2',
  line: 'rgba(27,33,29,0.12)',
  line2: 'rgba(27,33,29,0.22)',
};

/** 组件里 import 的 C 保持同一引用，setTheme 时原地改值 */
export const C: Palette = { ...DARK };

let currentTheme: Theme = 'dark';

export const getTheme = (): Theme => currentTheme;

/** 必须在装配层组件函数体第一行调用（渲染时同步生效，每帧幂等） */
export const setTheme = (t: Theme = 'dark'): void => {
  if (currentTheme === t) return;
  currentTheme = t;
  Object.assign(C, t === 'light' ? LIGHT : DARK);
};

/**
 * 根层 CSS 变量。`--rgb-*` 是「R,G,B」三元组字符串，专为
 * `rgba(var(--rgb-jade), 0.5)` 这类无法用 C.hex 拼 alpha 的渐变/阴影准备。
 */
export const themeCSS = (t: Theme): CSSProperties => {
  const light = t === 'light';
  return {
    '--rgb-jade': light ? '14,124,102' : '95,217,180',
    '--rgb-mist': light ? '74,124,155' : '132,182,212',
    '--rgb-amber': light ? '176,89,44' : '240,168,104',
    '--rgb-ink': light ? '27,33,29' : '232,234,230',
    '--rgb-bg': light ? '244,245,242' : '7,9,8',
    '--rgb-panel': light ? '255,255,255' : '27,31,28',
    '--rgb-pill': light ? '255,255,255' : '38,44,41',
    '--rgb-pill2': light ? '241,243,239' : '22,26,24',
    // 投影在亮底上改深墨灰，避免纯黑过硬；文字/暗角光晕在亮底上改白
    '--rgb-shadow': light ? '38,52,44' : '0,0,0',
    '--rgb-halo': light ? '255,255,255' : '0,0,0',
    // 卡片投影/地面投影在亮底上统一缩到 30%，深色档 = 1（原值不变）
    '--shade': light ? '0.3' : '1',
    '--bg': light ? LIGHT.bg : DARK.bg,
    '--panel': light ? LIGHT.panel : DARK.panel,
  } as CSSProperties;
};

/** 把暗主题的深色辉光混到白，得到亮底上可读的淡辉光；深色主题原样返回 */
export const themeGlow = (hex: string): string => {
  if (getTheme() !== 'light' || !/^#[0-9a-fA-F]{6}$/.test(hex)) return hex;
  const mix = (v: number) => Math.round(v * 0.3 + 255 * 0.7);
  const r = mix(parseInt(hex.slice(1, 3), 16));
  const g = mix(parseInt(hex.slice(3, 5), 16));
  const b = mix(parseInt(hex.slice(5, 7), 16));
  return `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`;
};

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
