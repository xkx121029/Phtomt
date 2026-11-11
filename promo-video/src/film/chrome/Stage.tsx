import React from 'react';
import { AbsoluteFill } from 'remotion';
import { C, getTheme, themeGlow } from '../tokens';
import { GeometryStage } from './GeometryStage';

/**
 * 舞台背景：纯色场 + 极淡色辉 + 静态胶片颗粒 + 暗角；深/浅主题由 tokens 驱动。
 * 颗粒用固定 SVG 噪声（不逐帧变化），避免闪动与不确定性。
 */
export const Stage: React.FC<{
  /** 主辉光颜色与强度（0–1），随镜头换色 */
  glow?: string;
  glowStrength?: number;
  /** 顶部冷光柱强度 */
  topLight?: number;
  /** 底部压暗（放字幕时用） */
  scrim?: number;
  /** 辉光椭圆尺寸：横版 1500×1300，竖版 1300×1500 */
  glowW?: number;
  glowH?: number;
  /** 底部压暗高度：横版 420，竖版 520 */
  scrimH?: number;
  /** 立体几何场景强度 0–1；0 = 关闭（默认），信息密集的镜头调低 */
  geometry?: number;
}> = ({
  glow = C.jade,
  glowStrength = 0.1,
  topLight = 0.05,
  scrim = 0.55,
  glowW = 1500,
  glowH = 1300,
  scrimH = 420,
  geometry = 0,
}) => {
  const isLight = getTheme() === 'light';
  return (
    <AbsoluteFill style={{ backgroundColor: C.bg }}>
      {/* 立体几何场景：压在辉光/压暗/颗粒之下，永远不抢前景文字 */}
      {geometry > 0 ? <GeometryStage strength={geometry} /> : null}
      {/* 中央主辉光 */}
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: '50%',
          width: glowW,
          height: glowH,
          transform: 'translate(-50%,-50%)',
          background: `radial-gradient(closest-side, ${themeGlow(glow)}${Math.round(
            glowStrength * 255
          )
            .toString(16)
            .padStart(2, '0')}, transparent 72%)`,
          filter: 'blur(20px)',
        }}
      />
      {/* 顶部冷光 */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: `linear-gradient(180deg, rgba(var(--rgb-mist),${
            topLight * 2.2
          }) 0%, transparent 34%)`,
        }}
      />
      {/* 底部字幕压暗 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 0,
          height: scrimH,
          background: `linear-gradient(0deg, rgba(var(--rgb-bg),${scrim}) 0%, rgba(var(--rgb-bg),0) 100%)`,
        }}
      />
      {/* 胶片颗粒（静态） */}
      <AbsoluteFill
        style={{
          opacity: isLight ? 0.028 : 0.055,
          mixBlendMode: 'overlay',
          backgroundImage:
            "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='3' stitchTiles='stitch'/></filter><rect width='160' height='160' filter='n'/></svg>\")",
          backgroundSize: '160px 160px',
        }}
      />
      {/* 暗角 */}
      <AbsoluteFill
        style={{
          background:
            'radial-gradient(118% 88% at 50% 46%, transparent 42%, rgba(var(--rgb-shadow),calc(0.72 * var(--shade))) 100%)',
        }}
      />
    </AbsoluteFill>
  );
};
