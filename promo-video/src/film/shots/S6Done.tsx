import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { Caption } from '../chrome/Caption';
import { beatF } from '../beat';
import { C, F } from '../tokens';

/** 数字落定那一拍 = S6 局部第 8 拍（全局 b72，全片最强 kick 0.94） */
const SLAM = beatF(8);

/**
 * S6 · 完成。完成屏自下方推入，左侧计数 1→4 逐步跳，
 * 在 b72 砸落定型（全片 2 个全画面级冲击之一）。
 */
export const S6Done: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  // 完成屏自下方推入
  const push = spring({
    frame,
    fps,
    config: { damping: 24, mass: 1.1, stiffness: 120 },
  });
  const enterY = interpolate(push, [0, 1], [120, 0]);

  // 计数：0→1→2→3，第三跳落在 SLAM（局部 b8 = 全局 b72）
  const jumps = [beatF(2.6), beatF(5.2), SLAM];
  const count = jumps.filter((j) => frame >= j).length;

  // 冲击：1.35 倍砸到 1.0 + 全画面 1.2% 泵
  const slamT = frame - SLAM;
  const numScale =
    slamT < 0 ? 1 : interpolate(spring({ frame: slamT, fps, config: { damping: 14, mass: 0.6, stiffness: 230 } }), [0, 1], [1.34, 1]);
  const framePump = slamT < 0 ? 1 : 1 + 0.012 * Math.exp(-slamT / 5) * Math.cos(slamT * 0.9);
  const flash = slamT < 0 ? 0 : interpolate(slamT, [0, 7], [0.5, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <AbsoluteFill style={{ transform: `scale(${framePump})` }}>
      <Device
        src="textures/screens/agent-done.png"
        sw={428}
        x={252}
        y={enterY}
        contentZoom={1.0}
        glow="#2e6b57"
        glowStrength={0.55}
      />

      {/* 左侧战果读数 */}
      <div
        style={{
          position: 'absolute',
          left: 170,
          top: 300,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <div
          style={{
            fontFamily: F.mono,
            fontSize: 38,
            letterSpacing: '0.1em',
            color: C.ink3,
            marginBottom: 18,
            opacity: interpolate(frame, [beatF(1), beatF(2.4)], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          页面指纹前后比对
        </div>

        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
          <span
            style={{
              fontFamily: F.mono,
              fontSize: 196,
              lineHeight: 0.9,
              fontWeight: 500,
              color: C.jade,
              transform: `scale(${numScale})`,
              transformOrigin: 'left bottom',
              display: 'inline-block',
              textShadow: `0 0 ${40 * (1 + flash * 3)}px rgba(95,217,180,${0.18 + flash})`,
            }}
          >
            {count}
          </span>
          <span
            style={{
              fontFamily: F.mono,
              fontSize: 96,
              color: C.ink4,
              lineHeight: 1,
              letterSpacing: '-0.02em',
            }}
          >
            /3
          </span>
        </div>

        <div
          style={{
            fontFamily: F.sans,
            fontSize: 40,
            fontWeight: 400,
            color: C.ink,
            marginTop: 14,
            opacity: interpolate(frame, [beatF(4.4), beatF(6)], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          步 · 全部真的生效了
        </div>

        <div
          style={{
            fontFamily: F.mono,
            fontSize: 34,
            color: C.ink3,
            marginTop: 16,
            opacity: interpolate(frame, [SLAM - 6, SLAM + 8], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          平均响应 1.7s · 28.6k tokens
        </div>
      </div>

      <Caption
        text="你只管说，它负责做完"
        duration={durationInFrames}
        delay={beatF(1.2)}
      />
    </AbsoluteFill>
  );
};
