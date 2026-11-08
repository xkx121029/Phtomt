import React from 'react';
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from 'remotion';
import { C } from '../tokens';

/**
 * 立体几何场景背景（现代化层）。
 *
 * 全部是等距投影的实体 + 线框：立方体（三面明暗）、线框球（经纬）、
 * 八面体、圆锥，落在一条透视网格地面上。颜色只取 tokens 的品牌色，
 * 填充一律低透明度，保证任何镜头里文字都压得住（R1/R4：不闪、不抢前景）。
 *
 * 运动只做两类：极慢的上下漂浮与 ±4° 自转，周期 8~14s，帧驱动可复现。
 * 远近景深靠 blur + alpha 分层，不引入 3D 库。
 */

type Kind = 'cube' | 'sphere' | 'octa' | 'cone';

type Solid = {
  kind: Kind;
  /** 画幅比例坐标 */
  fx: number;
  fy: number;
  /** 基准边长（1000px 画幅下） */
  s: number;
  color: string;
  /** 面填充透明度 */
  a: number;
  phase: number;
  /** 景深模糊 px */
  blur: number;
  spin?: number;
};

const SOLIDS: Solid[] = [
  { kind: 'cube', fx: 0.13, fy: 0.30, s: 132, color: C.jade, a: 0.10, phase: 0.2, blur: 1.1, spin: 1 },
  { kind: 'sphere', fx: 0.87, fy: 0.24, s: 116, color: C.mist, a: 0.085, phase: 1.4, blur: 1.8 },
  { kind: 'octa', fx: 0.81, fy: 0.73, s: 104, color: C.jade, a: 0.075, phase: 2.6, blur: 2.2, spin: -1 },
  { kind: 'cone', fx: 0.20, fy: 0.76, s: 96, color: C.amber, a: 0.065, phase: 3.4, blur: 2.6 },
  { kind: 'cube', fx: 0.51, fy: 0.12, s: 72, color: C.jade, a: 0.05, phase: 4.2, blur: 3.4 },
  { kind: 'sphere', fx: 0.47, fy: 0.90, s: 78, color: C.mist, a: 0.045, phase: 5.1, blur: 3.6 },
];

/** 等距立方体：顶面最亮、右侧次之、左侧最暗 —— 单光源在左上 */
const Cube: React.FC<{ s: number; color: string; a: number }> = ({ s, color, a }) => {
  const w = s * 0.866;
  const b = s * 0.5;
  const d = s * 0.92;
  const edge = { stroke: color, strokeOpacity: Math.min(0.5, a * 2.6), strokeWidth: 1.3 };
  return (
    <g>
      <polygon points={`${-w},0 0,${b} 0,${b + d} ${-w},${d}`} fill={color} fillOpacity={a * 0.45} {...edge} />
      <polygon points={`${w},0 0,${b} 0,${b + d} ${w},${d}`} fill={color} fillOpacity={a * 0.8} {...edge} />
      <polygon points={`0,${-b} ${w},0 0,${b} ${-w},0`} fill={color} fillOpacity={a * 1.6} {...edge} />
      {/* 顶棱高光 */}
      <path d={`M0,${-b} L${w},0`} stroke={color} strokeOpacity={Math.min(0.6, a * 3.4)} strokeWidth={1.6} fill="none" />
    </g>
  );
};

/** 线框球：外轮廓 + 三条纬线 + 两条经线 */
const Sphere: React.FC<{ s: number; color: string; a: number }> = ({ s, color, a }) => {
  const r = s * 0.62;
  const st = { stroke: color, strokeOpacity: Math.min(0.42, a * 2.4), strokeWidth: 1.1 };
  return (
    <g>
      <circle r={r} fill={color} fillOpacity={a * 0.35} {...st} />
      <ellipse rx={r} ry={r * 0.30} fill="none" {...st} />
      <ellipse rx={r} ry={r * 0.62} transform="rotate(28)" fill="none" {...st} />
      <ellipse rx={r * 0.42} ry={r} fill="none" {...st} />
      <ellipse rx={r * 0.78} ry={r} transform="rotate(-34)" fill="none" {...st} />
    </g>
  );
};

/** 正八面体：四个可见三角面 */
const Octa: React.FC<{ s: number; color: string; a: number }> = ({ s, color, a }) => {
  const w = s * 0.66;
  const h = s * 0.92;
  const b = s * 0.24;
  const edge = { stroke: color, strokeOpacity: Math.min(0.45, a * 2.8), strokeWidth: 1.2 };
  return (
    <g>
      <polygon points={`0,${-h} ${w},0 0,${b}`} fill={color} fillOpacity={a * 1.5} {...edge} />
      <polygon points={`0,${-h} 0,${b} ${-w},0`} fill={color} fillOpacity={a * 0.6} {...edge} />
      <polygon points={`0,${h} 0,${b} ${w},0`} fill={color} fillOpacity={a * 0.8} {...edge} />
      <polygon points={`0,${h} ${-w},0 0,${b}`} fill={color} fillOpacity={a * 0.35} {...edge} />
    </g>
  );
};

/** 圆锥：椭圆底 + 两条母线 */
const Cone: React.FC<{ s: number; color: string; a: number }> = ({ s, color, a }) => {
  const rx = s * 0.58;
  const ry = s * 0.2;
  const h = s * 1.05;
  const edge = { stroke: color, strokeOpacity: Math.min(0.45, a * 2.8), strokeWidth: 1.2 };
  return (
    <g>
      <path d={`M0,${-h} L${-rx},0 A${rx},${ry} 0 0 0 ${rx},0 Z`} fill={color} fillOpacity={a} {...edge} />
      <ellipse cx={0} cy={0} rx={rx} ry={ry} fill={color} fillOpacity={a * 0.5} {...edge} />
      <path d={`M0,${-h} L${-rx},0 M0,${-h} L${rx},0`} fill="none" {...edge} />
    </g>
  );
};

const Shape: React.FC<{ kind: Kind; s: number; color: string; a: number }> = ({ kind, ...p }) => {
  if (kind === 'cube') return <Cube {...p} />;
  if (kind === 'sphere') return <Sphere {...p} />;
  if (kind === 'octa') return <Octa {...p} />;
  return <Cone {...p} />;
};

/** 透视网格地面：消失点在下方黄金分割附近，横线间距按平方收敛 */
const FloorGrid: React.FC<{ vw: number; vh: number; color: string; strength: number }> = ({
  vw,
  vh,
  color,
  strength,
}) => {
  const vpX = vw * 0.5;
  const vpY = vh * 0.7;
  const st = { stroke: color, strokeOpacity: 0.055 * strength, strokeWidth: 1 };
  const rays = [];
  for (let i = -7; i <= 7; i++) {
    rays.push(<line key={`r${i}`} x1={vpX} y1={vpY} x2={vpX + i * (vw / 6)} y2={vh} {...st} />);
  }
  const rows = [];
  for (let k = 1; k <= 7; k++) {
    const y = vpY + (vh - vpY) * Math.pow(k / 7, 2.1);
    rows.push(<line key={`h${k}`} x1={0} y1={y} x2={vw} y2={y} {...st} />);
  }
  return (
    <g>
      {rays}
      {rows}
      <line x1={0} y1={vpY} x2={vw} y2={vpY} stroke={color} strokeOpacity={0.1 * strength} strokeWidth={1} />
    </g>
  );
};

export const GeometryStage: React.FC<{
  /** 整体强度 0–1，镜头按信息密度调节 */
  strength?: number;
  /** 「展示」档：几何更清晰，用于专门的代码示例镜头 */
  showcase?: boolean;
}> = ({ strength = 0.6, showcase = false }) => {
  const frame = useCurrentFrame();
  const { width: vw, height: vh } = useVideoConfig();
  const t = frame / 30;
  const u = Math.min(vw, vh) / 1000;
  const boost = showcase ? 1.9 : 1;

  return (
    <AbsoluteFill style={{ opacity: strength, pointerEvents: 'none' }}>
      {/* 场景底部的一层冷色空气 */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(75% 55% at 50% 74%, ${C.jade}${showcase ? '14' : '0a'}, transparent 72%)`,
        }}
      />
      <svg width={vw} height={vh} viewBox={`0 0 ${vw} ${vh}`} style={{ position: 'absolute', inset: 0 }}>
        <FloorGrid vw={vw} vh={vh} color={C.jade} strength={boost} />
        {SOLIDS.map((s, i) => {
          const bob = Math.sin(t * 0.42 + s.phase) * 7 * u;
          const rot = s.spin ? Math.sin(t * 0.16 + s.phase) * 4 * s.spin : 0;
          const size = s.s * u * (showcase ? 1.15 : 1);
          return (
            <g
              key={i}
              transform={`translate(${s.fx * vw}, ${s.fy * vh + bob}) rotate(${rot})`}
              opacity={Math.min(1, 0.7 + s.a * boost * 2)}
              style={{ filter: `blur(${s.blur * (showcase ? 0.5 : 1)}px)` }}
            >
              <Shape kind={s.kind} s={size} color={s.color} a={s.a * boost} />
            </g>
          );
        })}
        {/* 近景两枚小立方体：给场景一点纵深锚点 */}
        <g transform={`translate(${vw * 0.07}, ${vh * 0.58 + Math.sin(t * 0.5) * 6 * u})`}>
          <Cube s={44 * u} color={C.jade} a={0.07 * boost} />
        </g>
        <g transform={`translate(${vw * 0.94}, ${vh * 0.51 + Math.cos(t * 0.38) * 6 * u})`}>
          <Octa s={52 * u} color={C.mist} a={0.06 * boost} />
        </g>
      </svg>
      {/* 顶部轻雾：让几何体从暗场里浮出来，而不是贴在黑底上 */}
      <AbsoluteFill
        style={{
          background: `linear-gradient(180deg, rgba(7,9,8,0.72) 0%, transparent 28%, transparent 82%, rgba(7,9,8,0.5) 100%)`,
        }}
      />
    </AbsoluteFill>
  );
};
