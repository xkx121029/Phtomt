import React from 'react';
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from 'remotion';
import { C } from '../tokens';

/**
 * 3D 几何场景背景 —— 不是「把几何体贴在背景上」，而是一个有相机的空间。
 *
 * 结构：
 *   perspective 相机 → cameraGroup（缓慢推进 + 微摆）→ 按 Z 深度排布的实体。
 *   实体是真 3D 网格：六面立方体/立柱、经纬环线框球、堆叠圆环圆锥、远端光环门。
 *   所有实体共享一个透视地面（rotateX 84° 的网格平面 + 各自的椭圆投影），
 *   浏览器按 3D 深度自动做遮挡；远处靠 alpha + 全局雾层衰减。
 *
 * 相机推进让近处柱体掠过、远处门环放大 —— 视差来自真实 Z，不是平移贴图。
 * 颜色只取 tokens，面填充极淡，压在辉光/压暗/暗角之下，任何镜头都不抢字（R1/R4）。
 * 不引 3D 库：CSS `transform-style: preserve-3d`，Chromium 下逐帧可复现。
 */

type Kind = 'box' | 'sphere' | 'cone' | 'gate';

type Solid = {
  kind: Kind;
  /** 画幅比例坐标（相机空间） */
  fx: number;
  fy: number;
  /** 深度：负 = 远，正 = 靠近相机 */
  z: number;
  /** 基准尺寸（1000px 画幅下） */
  s: number;
  /** box 专用：高度倍数（立柱） */
  h?: number;
  color: string;
  /** 面填充透明度 */
  a: number;
  phase: number;
  /** 自转速度 deg/s，0 = 静止 */
  spin?: number;
};

const SOLIDS: Solid[] = [
  // 左侧柱廊：同一尺寸、逐级退到远处，透视自然收拢成走廊
  { kind: 'box', fx: 0.05, fy: 0.55, z: -320, s: 128, h: 2.5, color: C.jade, a: 0.10, phase: 0.2 },
  { kind: 'box', fx: 0.09, fy: 0.55, z: -820, s: 128, h: 2.5, color: C.jade, a: 0.09, phase: 1.1 },
  { kind: 'box', fx: 0.14, fy: 0.55, z: -1380, s: 128, h: 2.5, color: C.jade, a: 0.08, phase: 2.0 },
  { kind: 'box', fx: 0.19, fy: 0.55, z: -1980, s: 128, h: 2.5, color: C.jade, a: 0.07, phase: 2.9 },
  // 右侧柱廊
  { kind: 'box', fx: 0.95, fy: 0.55, z: -320, s: 128, h: 2.5, color: C.mist, a: 0.09, phase: 0.7 },
  { kind: 'box', fx: 0.91, fy: 0.55, z: -820, s: 128, h: 2.5, color: C.mist, a: 0.08, phase: 1.6 },
  { kind: 'box', fx: 0.86, fy: 0.55, z: -1380, s: 128, h: 2.5, color: C.mist, a: 0.075, phase: 2.5 },
  { kind: 'box', fx: 0.81, fy: 0.55, z: -1980, s: 128, h: 2.5, color: C.mist, a: 0.065, phase: 3.4 },
  // 悬浮实体：分布在不同深度，彼此会互相遮挡
  { kind: 'sphere', fx: 0.70, fy: 0.27, z: -560, s: 118, color: C.mist, a: 0.085, phase: 1.4, spin: 5 },
  { kind: 'box', fx: 0.28, fy: 0.31, z: -1000, s: 96, color: C.jade, a: 0.08, phase: 2.2, spin: -6 },
  { kind: 'cone', fx: 0.24, fy: 0.72, z: -700, s: 132, color: C.amber, a: 0.065, phase: 3.4, spin: 3 },
  { kind: 'sphere', fx: 0.78, fy: 0.70, z: -1220, s: 104, color: C.jade, a: 0.07, phase: 4.1, spin: -4 },
  { kind: 'box', fx: 0.44, fy: 0.20, z: -1650, s: 88, color: C.mist, a: 0.06, phase: 5.0, spin: 4 },
  { kind: 'cone', fx: 0.62, fy: 0.76, z: -1780, s: 112, color: C.jade, a: 0.055, phase: 5.7, spin: -3 },
  // 远端台基：扁平长方体，让走廊尽头有「建筑」而不是空地
  { kind: 'box', fx: 0.34, fy: 0.60, z: -2050, s: 150, h: 0.32, color: C.jade, a: 0.07, phase: 0 },
  { kind: 'box', fx: 0.66, fy: 0.60, z: -2050, s: 150, h: 0.32, color: C.mist, a: 0.06, phase: 0 },
  // 远端的光环门：场景的消失点
  { kind: 'gate', fx: 0.50, fy: 0.43, z: -2450, s: 250, color: C.jade, a: 0.075, phase: 0 },
];

/** 0–1 → 2 位十六进制 alpha，拼成 8 位色值 */
const hx = (a: number) =>
  Math.round(Math.max(0, Math.min(1, a)) * 255)
    .toString(16)
    .padStart(2, '0');

/** 真六面体：front/back/left/right/top/bottom 各自在 3D 空间里就位 */
const Box3D: React.FC<{ w: number; h: number; d: number; color: string; a: number }> = ({
  w,
  h,
  d,
  color,
  a,
}) => {
  const face = (
    key: string,
    fw: number,
    fh: number,
    transform: string,
    shade: number
  ) => (
    <div
      key={key}
      style={{
        position: 'absolute',
        left: '50%',
        top: '50%',
        width: fw,
        height: fh,
        marginLeft: -fw / 2,
        marginTop: -fh / 2,
        transform,
        background: `linear-gradient(155deg, ${color}${hx(a * shade * 2.8)}, ${color}${hx(
          a * shade * 0.7
        )})`,
        border: `1px solid ${color}${hx(Math.min(0.65, a * 4))}`,
        boxShadow: `inset 0 0 34px ${color}${hx(a * 0.9)}`,
      }}
    />
  );
  return (
    <div style={{ position: 'absolute', inset: 0, transformStyle: 'preserve-3d' }}>
      {face('f', w, h, `translateZ(${d / 2}px)`, 1)}
      {face('b', w, h, `rotateY(180deg) translateZ(${d / 2}px)`, 0.5)}
      {face('r', d, h, `rotateY(90deg) translateZ(${w / 2}px)`, 0.72)}
      {face('l', d, h, `rotateY(-90deg) translateZ(${w / 2}px)`, 0.42)}
      {face('t', w, d, `rotateX(90deg) translateZ(${h / 2}px)`, 1.5)}
      {face('bt', w, d, `rotateX(-90deg) translateZ(${h / 2}px)`, 0.3)}
    </div>
  );
};

/** 线框球：7 条经线（绕 Y）+ 3 条纬线（绕 X），是真实 3D 环而不是画在平面上的椭圆 */
const Sphere3D: React.FC<{ r: number; color: string; a: number }> = ({ r, color, a }) => {
  const ring = (key: string, size: number, transform: string) => (
    <div
      key={key}
      style={{
        position: 'absolute',
        left: '50%',
        top: '50%',
        width: size,
        height: size,
        marginLeft: -size / 2,
        marginTop: -size / 2,
        borderRadius: '50%',
        border: `1px solid ${color}${hx(Math.min(0.5, a * 2.6))}`,
        background: `radial-gradient(closest-side, ${color}${hx(a * 0.32)}, transparent 72%)`,
        transform,
      }}
    />
  );
  return (
    <div style={{ position: 'absolute', inset: 0, transformStyle: 'preserve-3d' }}>
      {Array.from({ length: 7 }, (_, i) => ring(`m${i}`, r * 2, `rotateY(${i * (180 / 7)}deg)`))}
      {[-0.62, 0, 0.62].map((k, i) =>
        ring(`p${i}`, r * 2 * Math.sqrt(1 - k * k), `rotateX(90deg) translateZ(${k * r}px)`)
      )}
    </div>
  );
};

/** 圆锥：9 条水平圆环自底向顶点收半径，堆出锥面骨架 */
const Cone3D: React.FC<{ s: number; color: string; a: number }> = ({ s, color, a }) => {
  const R = s * 0.62;
  const H = s * 1.15;
  return (
    <div style={{ position: 'absolute', inset: 0, transformStyle: 'preserve-3d' }}>
      {Array.from({ length: 9 }, (_, i) => {
        const k = i / 8;
        const rr = Math.max(3, R * (1 - k));
        const y = H / 2 - k * H;
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: '50%',
              top: '50%',
              width: rr * 2,
              height: rr * 2,
              marginLeft: -rr,
              marginTop: -rr,
              borderRadius: '50%',
              border: `1px solid ${color}${hx(Math.min(0.5, a * 2.8))}`,
              background: `radial-gradient(closest-side, ${color}${hx(a * 0.28)}, transparent 74%)`,
              transform: `translateY(${y}px) rotateX(90deg)`,
            }}
          />
        );
      })}
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: '50%',
          width: 7,
          height: 7,
          marginLeft: -3.5,
          marginTop: -3.5,
          borderRadius: '50%',
          background: color,
          boxShadow: `0 0 18px ${color}${hx(a * 4)}`,
          transform: `translateY(${-H / 2}px)`,
        }}
      />
    </div>
  );
};

/** 远端光环门：两道同心竖环 + 中心柔光，作为走廊的消失点 */
const Gate3D: React.FC<{ s: number; color: string; a: number }> = ({ s, color, a }) => (
  <div style={{ position: 'absolute', inset: 0, transformStyle: 'preserve-3d' }}>
    {[1, 0.78, 0.56].map((k, i) => (
      <div
        key={i}
        style={{
          position: 'absolute',
          left: '50%',
          top: '50%',
          width: s * k,
          height: s * k,
          marginLeft: (-s * k) / 2,
          marginTop: (-s * k) / 2,
          borderRadius: '50%',
          border: `${i === 0 ? 2 : 1}px solid ${color}${hx(Math.min(0.6, a * (3 - i)))}`,
          transform: `rotateY(${i * 8}deg)`,
        }}
      />
    ))}
    <div
      style={{
        position: 'absolute',
        left: '50%',
        top: '50%',
        width: s * 0.9,
        height: s * 0.9,
        marginLeft: (-s * 0.9) / 2,
        marginTop: (-s * 0.9) / 2,
        borderRadius: '50%',
        background: `radial-gradient(closest-side, ${color}${hx(a * 1.1)}, transparent 72%)`,
        filter: 'blur(18px)',
      }}
    />
  </div>
);

const Shape3D: React.FC<{ kind: Kind; s: number; h: number; color: string; a: number }> = ({
  kind,
  s,
  h,
  color,
  a,
}) => {
  if (kind === 'box') return <Box3D w={s} h={s * h} d={s} color={color} a={a} />;
  if (kind === 'sphere') return <Sphere3D r={s * 0.62} color={color} a={a} />;
  if (kind === 'cone') return <Cone3D s={s} color={color} a={a} />;
  return <Gate3D s={s} color={color} a={a} />;
};

/** 透视地面 / 顶棚：CSS 3D 旋转出的网格平面，纹理随相机推进滚动 */
const GridPlane: React.FC<{
  color: string;
  travel: number;
  alpha: number;
  ceiling?: boolean;
}> = ({ color, travel, alpha, ceiling = false }) => (
  <div
    style={{
      position: 'absolute',
      left: '50%',
      top: ceiling ? '38%' : '62%',
      // 尺寸不能太大：12000px 的旋转平面会让 Chromium 丢掉右侧合成瓦片（整块透明）
      width: 6000,
      height: 6000,
      marginLeft: -3000,
      marginTop: -3000,
      transform: `translateZ(-120px) rotateX(${ceiling ? -84 : 84}deg)`,
      transformStyle: 'preserve-3d',
      backgroundImage: [
        `repeating-linear-gradient(0deg, ${color}${hx(alpha)} 0px, ${color}${hx(alpha)} 1px, transparent 1px, transparent 150px)`,
        `repeating-linear-gradient(90deg, ${color}${hx(alpha)} 0px, ${color}${hx(alpha)} 1px, transparent 1px, transparent 150px)`,
      ].join(','),
      backgroundPosition: `0px ${-travel * 1.35}px, ${-travel * 1.35}px 0px`,
      maskImage: `radial-gradient(ellipse 58% 58% at 50% 50%, rgba(0,0,0,1) 0%, rgba(0,0,0,0.62) 42%, transparent 88%)`,
      WebkitMaskImage: `radial-gradient(ellipse 58% 58% at 50% 50%, rgba(0,0,0,1) 0%, rgba(0,0,0,0.62) 42%, transparent 88%)`,
    }}
  />
);

export const GeometryStage: React.FC<{
  /** 整体强度 0–1，镜头按信息密度调节 */
  strength?: number;
  /** 展示档：几何更亮、相机推进更快，用于 S7b 编码示例镜头 */
  showcase?: boolean;
}> = ({ strength = 0.6, showcase = false }) => {
  const frame = useCurrentFrame();
  const { width: vw, height: vh } = useVideoConfig();
  const t = frame / 30;
  const u = Math.min(vw, vh) / 1000;
  const boost = showcase ? 1.7 : 1;

  // 相机：沿 Z 推进（近处柱体掠过、远处门环放大）+ 极轻的摆头；封顶避免穿模
  const travel = Math.min(t * (showcase ? 66 : 34), 260);
  const yaw = Math.sin(t * 0.11) * 1.6;

  return (
    <AbsoluteFill style={{ opacity: strength, pointerEvents: 'none' }}>
      {/* 远景天幕：地平线处一层冷光，让空间有「尽头」 */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(78% 34% at 50% 43%, ${C.jade}${hx(0.06 * boost)}, transparent 70%),
                       linear-gradient(180deg, #070908 0%, rgba(7,9,8,0) 34%, rgba(7,9,8,0) 76%, #070908 100%)`,
        }}
      />
      {/* 地平线光带 + 地面反射：把走廊的消失点钉住 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: '44%',
          height: 3,
          background: `linear-gradient(90deg, transparent 8%, ${C.jade}${hx(
            0.24 * boost
          )} 50%, transparent 92%)`,
          filter: 'blur(2px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: '68%',
          width: vw * 0.9,
          height: vh * 0.42,
          marginLeft: -vw * 0.45,
          background: `radial-gradient(ellipse at 50% 0%, ${C.jade}${hx(0.055 * boost)}, transparent 72%)`,
        }}
      />

      <div
        style={{
          position: 'absolute',
          inset: 0,
          perspective: 1400,
          perspectiveOrigin: '50% 46%',
        }}
      >
        <div
          style={{
            position: 'absolute',
            inset: 0,
            transformStyle: 'preserve-3d',
            transform: `translateZ(${travel}px) rotateY(${yaw}deg)`,
          }}
        >
          <GridPlane color={C.jade} travel={travel} alpha={0.11 * boost} />
          {/* 顶棚网格待定：rotateX(-84deg) 的大平面会击穿 3D 合成层，暂时只保留地面 */}

          {SOLIDS.map((s, i) => {
            const x = (s.fx - 0.5) * vw;
            const y = (s.fy - 0.5) * vh + Math.sin(t * 0.42 + s.phase) * 7 * u;
            const size = s.s * u * 1.15;
            const spin = s.spin ? t * s.spin : 0;
            // 深度雾化：远处更淡，近处更实；不使用 filter（会压平 preserve-3d）
            const fog = Math.max(0.22, Math.min(1, (s.z + 2600) / 2600));
            // 注意：不能给 3D 容器设 opacity/filter（会把 preserve-3d 压平成 2D），
            // 雾化一律烘进面填充 alpha
            const a = s.a * boost * (0.25 + 0.75 * fog);
            const isPillar = s.kind === 'box' && (s.h ?? 1) > 1.5;
            const groundY = (0.62 - 0.5) * vh + (s.h ? (size * s.h) / 2 : 0);

            return (
              <React.Fragment key={i}>
                {/* 地面投影：与该实体同一 Z 深度，躺在旋转后的地面上 */}
                <div
                  style={{
                    position: 'absolute',
                    left: '50%',
                    top: '50%',
                    width: size * 2.6,
                    height: size * 2.6,
                    marginLeft: -size * 1.3,
                    marginTop: -size * 1.3,
                    borderRadius: '50%',
                    background: `radial-gradient(closest-side, rgba(0,0,0,0.55), transparent 72%)`,
                    transform: `translate3d(${x}px, ${groundY}px, ${s.z}px) rotateX(84deg)`,
                  }}
                />
                {/* 实体本体 */}
                <div
                  style={{
                    position: 'absolute',
                    left: '50%',
                    top: '50%',
                    width: 0,
                    height: 0,
                    transformStyle: 'preserve-3d',
                    transform: `translate3d(${x}px, ${y}px, ${s.z}px) rotateY(${spin}deg) rotateX(${
                      isPillar ? 0 : Math.sin(t * 0.16 + s.phase) * 4
                    }deg)`,
                  }}
                >
                  <Shape3D kind={s.kind} s={size} h={s.h ?? 1} color={s.color} a={a} />
                </div>
              </React.Fragment>
            );
          })}

          {/* 体积光：几层半透明光幕在不同 Z 上，随相机推进产生视差 */}
          {[-1900, -1200, -600].map((z, i) => (
            <div
              key={z}
              style={{
                position: 'absolute',
                left: '50%',
                top: '50%',
                width: vw * 1.4,
                height: vh * 1.1,
                marginLeft: -vw * 0.7,
                marginTop: -vh * 0.55,
                background: `radial-gradient(closest-side, ${i === 0 ? C.mist : C.jade}${hx(
                  0.035 * boost
                )}, transparent 68%)`,
                transform: `translate3d(0,0,${z}px)`,
              }}
            />
          ))}
        </div>
      </div>

      {/* 全局雾 + 暗角：把前景文字从空间里分离出来 */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(120% 92% at 50% 46%, transparent 38%, rgba(7,9,8,0.82) 100%)`,
        }}
      />
    </AbsoluteFill>
  );
};
