import React from 'react';
import { AbsoluteFill, Easing, interpolate, useCurrentFrame } from 'remotion';
import { Device } from '../chrome/Device';
import { C, F } from '../tokens';
import { beatF } from '../beat';

const QW = 960;
const QH = 540;
/** 四格向外让出的缝，让它们读作「四张卡」而不是被切断的一张图 */
const PAD = 18;
type Quad = {
  src: string;
  label: string;
  focusY: number;
  /** 起始错拍帧 */
  delay: number;
};

/**
 * 四格的取景中心。屏幕内容层按 QW 满宽放大（zoom≈2.26），
 * 纵向只露出一段窗口 —— 靠上下两端向底色渐隐，切边不会被读成「切坏的字」。
 */
const QUADS: Quad[] = [
  { src: 'textures/screens/memory.png', label: '记忆图谱', focusY: 372, delay: 0 },
  { src: 'textures/screens/memory-pages.png', label: '页面记忆', focusY: 430, delay: 2 },
  { src: 'textures/screens/coding.png', label: '设备内编码', focusY: 430, delay: 4 },
  { src: 'textures/screens/home.png', label: '主题换肤', focusY: 470, delay: 6 },
];

/**
 * S7 · 四宫格并行。硬切无转场，四象限各自跑慢推镜（错拍 4f），
 * 第四象限在 b80 换色板（唯一真实的状态变化）。
 * qw/qh 是单格尺寸：横版 960×540 铺满 2×2，竖版传 540×960。
 */
export const S7Wall: React.FC<{ qw?: number; qh?: number; glowW?: number; glowH?: number }> = ({
  qw = QW,
  qh = QH,
  glowW = 1200,
  glowH = 900,
}) => {
  const frame = useCurrentFrame();
  const swapAt = beatF(4);

  return (
    <AbsoluteFill style={{ backgroundColor: C.bg }}>
      {QUADS.map((q, i) => {
        const col = i % 2;
        const row = Math.floor(i / 2);
        const local = frame - q.delay;
        const push = interpolate(local, [0, 150], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
          easing: Easing.bezier(0.33, 0, 0.15, 1),
        });
        const isTheme = i === 3;
        const swap = isTheme && frame >= swapAt;

        const tw = qw - PAD * 2;
        const th = qh - PAD * 2;
        const zoomBase = 1.0;
        const zoom = zoomBase + push * 0.05;

        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: col * qw + PAD,
              top: row * qh + PAD,
              width: tw,
              height: th,
              overflow: 'hidden',
              borderRadius: 20,
              backgroundColor: '#070908',
              boxShadow:
                'inset 0 0 0 1px rgba(232,234,230,0.10), 0 18px 60px -30px rgba(0,0,0,0.9)',
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: '50%',
                top: '50%',
                width: glowW,
                height: glowH,
                transform: 'translate(-50%,-50%)',
                background:
                  'radial-gradient(closest-side, rgba(95,217,180,0.10), transparent 70%)',
                filter: 'blur(12px)',
              }}
            />

            <Device
              src={q.src}
              sw={tw}
              framed={false}
              contentZoom={zoom}
              focus={{ x: 206, y: q.focusY }}
              glow="#2e6b57"
              glowStrength={0.32}
              shadow={0.8}
            />

            {/* 第四格换色板：同一屏换成赭石 */}
            {swap ? (
              <Device
                src="textures/screens/home-ochre.png"
                sw={tw}
                framed={false}
                contentZoom={zoom}
                focus={{ x: 206, y: q.focusY }}
                glow="#6b4a2e"
                glowStrength={0.32}
                shadow={0.8}
                style={{
                  opacity: interpolate(frame, [swapAt, swapAt + 8], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  }),
                }}
              />
            ) : null}

            {/* 上下渐隐：把纵向切边化成「窗口」，而不是被裁断的界面 */}
            <div
              style={{
                position: 'absolute',
                inset: 0,
                background: [
                  'linear-gradient(to bottom, #070908 2%, rgba(7,9,8,0) 22%)',
                  'linear-gradient(to top, #070908 12%, rgba(7,9,8,0) 34%)',
                  'linear-gradient(to right, rgba(7,9,8,0.72) 0%, rgba(7,9,8,0) 15%)',
                  'linear-gradient(to left, rgba(7,9,8,0.72) 0%, rgba(7,9,8,0) 15%)',
                ].join(','),
                pointerEvents: 'none',
              }}
            />

            {/* 标签压板：让标签永远压在干净的底色上，不和屏内文字撞车 */}
            <div
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                bottom: 0,
                height: 170,
                background:
                  'linear-gradient(to top, rgba(7,9,8,0.95) 0%, rgba(7,9,8,0.78) 40%, rgba(7,9,8,0) 100%)',
                pointerEvents: 'none',
              }}
            />

            {/* 象限标签 */}
            <div
              style={{
                position: 'absolute',
                left: 40,
                bottom: 34,
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                opacity: interpolate(local, [2, 9], [0, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                }),
              }}
            >
              <span
                style={{
                  width: 7,
                  height: 7,
                  borderRadius: 7,
                  background: C.jade,
                  boxShadow: `0 0 10px ${C.jade}`,
                }}
              />
              <span
                style={{
                  fontFamily: F.sans,
                  fontSize: 34,
                  fontWeight: 500,
                  color: C.ink,
                  letterSpacing: '0.02em',
                  textShadow: '0 2px 16px rgba(0,0,0,0.9)',
                }}
              >
                {q.label}
              </span>
            </div>
          </div>
        );
      })}
    </AbsoluteFill>
  );
};
