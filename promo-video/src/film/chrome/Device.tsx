import React from 'react';
import { Img, staticFile, useVideoConfig } from 'remotion';
import { C, SCREEN, themeGlow } from '../tokens';

export type DeviceXform = {
  x?: number;
  y?: number;
  rotX?: number;
  rotY?: number;
  rotZ?: number;
  scale?: number;
};

/**
 * 3D 设备。屏幕纹理来自 public/textures/screens/ 的 4x 截图，
 * 机身由 CSS 绘制（非复刻场景，见 DESIGN-SPEC §3 风险记录）。
 *
 * 内容层用 CSS `zoom` 做布局级放大（Q2）：overlay 里的文字按放大后的
 * 设备尺寸栅格化，再向下采样，3D 透视下不糊。
 */
export const Device: React.FC<{
  src: string;
  /** 屏幕在输出像素里的显示宽度 */
  sw: number;
  x?: number;
  y?: number;
  rotX?: number;
  rotY?: number;
  rotZ?: number;
  scale?: number;
  /** 0 = 全黑，1 = 原亮度 */
  brightness?: number;
  /** 屏幕背后的环境辉光色 */
  glow?: string;
  glowStrength?: number;
  /** 是否绘制机身（S1 之类只要屏幕时关掉） */
  framed?: boolean;
  /** 落地阴影强度 */
  shadow?: number;
  /** 屏幕内容的额外放大（相机「贴近屏幕」）：1 = 整屏贴合，>1 = 推进特写 */
  contentZoom?: number;
  /** 内容放大时锁定的逻辑屏坐标点（默认屏幕中心） */
  focus?: { x: number; y: number };
  children?: React.ReactNode;
  style?: React.CSSProperties;
}> = ({
  src,
  sw,
  x = 0,
  y = 0,
  rotX = 0,
  rotY = 0,
  rotZ = 0,
  scale = 1,
  brightness = 1,
  glow = '#2f6a56',
  glowStrength = 0.5,
  framed = true,
  shadow = 1,
  contentZoom = 1,
  focus,
  children,
  style,
}) => {
  const sh = (sw * SCREEN.h) / SCREEN.w;
  const bezel = framed ? sw * 0.033 : 0; // ≈ 真机边框占屏宽比例
  const bodyW = sw + bezel * 2;
  const bodyH = sh + bezel * 2;
  const bodyR = framed ? sw * 0.148 : sw * 0.104;
  const screenR = sw * 0.104; // 43 / 412

  // 透视原点取画幅中心：横版 960×540、竖版 540×960 都对，不写死
  const { width: vw, height: vh } = useVideoConfig();

  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        perspective: 2000,
        perspectiveOrigin: `${vw / 2}px ${vh / 2}px`,
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: '50%',
          width: bodyW,
          height: bodyH,
          marginLeft: -bodyW / 2,
          marginTop: -bodyH / 2,
          transform: `translate3d(${x}px, ${y}px, 0) rotateZ(${rotZ}deg) rotateY(${rotY}deg) rotateX(${rotX}deg) scale(${scale})`,
          transformStyle: 'preserve-3d',
          willChange: 'transform',
        }}
      >
        {/* 屏幕光溢到环境里 */}
        <div
          style={{
            position: 'absolute',
            left: '50%',
            top: '50%',
            width: bodyW * 2.6,
            height: bodyH * 1.9,
            marginLeft: -bodyW * 1.3,
            marginTop: -bodyH * 0.95,
            borderRadius: '50%',
            background: `radial-gradient(closest-side, ${themeGlow(glow)}${Math.round(
              glowStrength * 255
            )
              .toString(16)
              .padStart(2, '0')}, transparent 70%)`,
            filter: 'blur(48px)',
            pointerEvents: 'none',
          }}
        />

        {/* 机身：金属边框（锥形渐变模拟斜射边缘光）+ 深色内框 */}
        {framed ? (
          <>
            <div
              style={{
                position: 'absolute',
                inset: 0,
                borderRadius: bodyR,
                background:
                  'conic-gradient(from 208deg at 50% 50%, #080b0a 0deg, #767e73 26deg, #2a302c 64deg, #0a0e0c 128deg, #050807 196deg, #1c211e 250deg, #7d857a 300deg, #141917 332deg, #080b0a 360deg)',
                boxShadow: [
                  `0 ${30 * shadow}px ${80 * shadow}px -${20 * shadow}px rgba(var(--rgb-shadow),0.94)`,
                  `0 ${8 * shadow}px ${22 * shadow}px -${8 * shadow}px rgba(var(--rgb-shadow),0.75)`,
                  'inset 0 0 0 1px rgba(var(--rgb-shadow),0.85)',
                ].join(','),
              }}
            />
            {/* 内框：深色玻璃面板，压住金属边缘 */}
            <div
              style={{
                position: 'absolute',
                inset: Math.max(1.6, sw * 0.0042),
                borderRadius: bodyR - sw * 0.004,
                background:
                  'linear-gradient(158deg,#1a201d 0%,#0c100e 34%,#070a09 62%,#121614 100%)',
                boxShadow: 'inset 0 1px 1px rgba(255,255,255,0.10), inset 0 -1px 1px rgba(var(--rgb-shadow),0.9)',
              }}
            />
            {/* 侧键 */}
            <div
              style={{
                position: 'absolute',
                right: -1.6,
                top: bodyH * 0.205,
                width: 2,
                height: bodyH * 0.092,
                borderRadius: 2,
                background: 'linear-gradient(90deg,#040706,#2b322c,#060a08)',
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: -1.6,
                top: bodyH * 0.232,
                width: 2,
                height: bodyH * 0.062,
                borderRadius: 2,
                background: 'linear-gradient(270deg,#040706,#2b322c,#060a08)',
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: -1.6,
                top: bodyH * 0.31,
                width: 2,
                height: bodyH * 0.062,
                borderRadius: 2,
                background: 'linear-gradient(270deg,#040706,#2b322c,#060a08)',
              }}
            />
          </>
        ) : null}

        {/* 屏幕 */}
        <div
          style={{
            position: 'absolute',
            left: bezel,
            top: bezel,
            width: sw,
            height: sh,
            borderRadius: screenR,
            overflow: 'hidden',
            background: '#000',
            boxShadow: framed
              ? 'inset 0 0 0 1px rgba(var(--rgb-shadow),0.9), 0 0 0 0.5px rgba(255,255,255,0.06)'
              : undefined,
          }}
        >
          {/* 内容层：layout 级放大保证锐度（Q2）。
              zoom z 把内容放大到 z 倍布局尺寸，再把锁定点移到屏心：
              (fx, fy) → (sw/2, sh/2) ⟹ left = sw/(2z) − fx · top = sh/(2z) − fy */}
          <div
            style={{
              position: 'absolute',
              left: (() => {
                const z = (sw / SCREEN.w) * contentZoom;
                return sw / (2 * z) - (focus ? focus.x : SCREEN.w / 2);
              })(),
              top: (() => {
                const z = (sw / SCREEN.w) * contentZoom;
                return sh / (2 * z) - (focus ? focus.y : SCREEN.h / 2);
              })(),
              width: SCREEN.w,
              height: SCREEN.h,
              zoom: (sw / SCREEN.w) * contentZoom,
            }}
          >
            <Img
              src={staticFile(src)}
              style={{
                position: 'absolute',
                inset: 0,
                width: SCREEN.w,
                height: SCREEN.h,
                objectFit: 'cover',
              }}
            />
            {children}
          </div>

          {/* 亮度衰减 */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: '#000',
              opacity: 1 - brightness,
              pointerEvents: 'none',
            }}
          />

          {/* 玻璃反光：对角高光 + 顶端镜面（克制，不压暗内容） */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: [
                'linear-gradient(150deg, rgba(255,255,255,0.030) 0%, rgba(255,255,255,0.007) 12%, rgba(255,255,255,0) 30%, rgba(255,255,255,0) 82%, rgba(255,255,255,0.012) 100%)',
                'radial-gradient(130% 46% at 24% -10%, rgba(255,255,255,0.035), transparent 64%)',
              ].join(','),
              pointerEvents: 'none',
              mixBlendMode: 'screen',
            }}
          />
          {/* 屏幕内暗角（轻） */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background:
                'radial-gradient(136% 108% at 50% 50%, transparent 66%, rgba(var(--rgb-shadow),0.26) 100%)',
              pointerEvents: 'none',
            }}
          />
          {/* 玻璃边缘：一圈极细的内高光，读作「屏幕是嵌进去的」 */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              borderRadius: screenR,
              boxShadow:
                'inset 0 0 0 1px rgba(255,255,255,0.045), inset 0 1px 0 rgba(255,255,255,0.07)',
              pointerEvents: 'none',
            }}
          />
        </div>

        {/* 机身侧缘的品牌色反光：暗场里唯一的轮廓线索 */}
        {framed ? (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              borderRadius: bodyR,
              boxShadow:
                'inset 1px 1px 0 rgba(var(--rgb-jade),0.16), inset 0 0 24px rgba(var(--rgb-jade),0.05), inset -1px -1px 0 rgba(255,255,255,0.045)',
              pointerEvents: 'none',
            }}
          />
        ) : null}
      </div>
    </div>
  );
};
