import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { beatF } from '../beat';
import { C, F } from '../tokens';
import { vDevY } from './vertical-beat';

/** 数字落定那一拍 = 局部第 8 拍（全局 b72，全片最强 kick 0.94） */
const SLAM = beatF(8);

const SW = 524;
const TOP = 744;

/**
 * V6 · 完成（竖版）。
 * 横版是「设备在右、读数在左」的横向对位；竖版把读数提到画面上半区做成主角（数字 250px），
 * 设备压到下半区。全片两个全画面级冲击之一仍钉在这里的 b72。
 */
export const V6Done: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const push = spring({ frame, fps, config: { damping: 24, mass: 1.1, stiffness: 120 } });
  const enterY = interpolate(push, [0, 1], [110, 0]);

  const jumps = [beatF(2.6), beatF(5.2), SLAM];
  const count = jumps.filter((j) => frame >= j).length;

  const slamT = frame - SLAM;
  const numScale =
    slamT < 0
      ? 1
      : interpolate(
          spring({ frame: slamT, fps, config: { damping: 14, mass: 0.6, stiffness: 230 } }),
          [0, 1],
          [1.34, 1]
        );
  const framePump =
    slamT < 0 ? 1 : 1 + 0.012 * Math.exp(-slamT / 5) * Math.cos(slamT * 0.9);
  const flash =
    slamT < 0
      ? 0
      : interpolate(slamT, [0, 7], [0.5, 0], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        });

  return (
    <AbsoluteFill style={{ transform: `scale(${framePump})` }}>
      <Device
        src="textures/screens/agent-done.png"
        sw={SW}
        y={vDevY(SW, TOP) + enterY}
        glow="#2e6b57"
        glowStrength={0.55}
      />

      {/* 读数主体：竖版把它做成画面的上半区主角 */}
      <div
        style={{
          position: 'absolute',
          left: 76,
          right: 76,
          top: 190,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
        }}
      >
        <div
          style={{
            fontFamily: F.mono,
            fontSize: 34,
            letterSpacing: '0.16em',
            color: C.ink3,
            marginBottom: 26,
            opacity: interpolate(frame, [beatF(1), beatF(2.4)], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          页面指纹前后比对
        </div>

        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <span
            style={{
              fontFamily: F.mono,
              fontSize: 248,
              lineHeight: 0.88,
              fontWeight: 500,
              color: C.jade,
              transform: `scale(${numScale})`,
              transformOrigin: 'center bottom',
              display: 'inline-block',
              textShadow: `0 0 ${44 * (1 + flash * 3)}px rgba(var(--rgb-jade),${0.18 + flash})`,
            }}
          >
            {count}
          </span>
          <span
            style={{
              fontFamily: F.mono,
              fontSize: 116,
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
            fontSize: 48,
            fontWeight: 400,
            color: C.ink,
            marginTop: 22,
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
            fontSize: 32,
            color: C.ink3,
            marginTop: 18,
            opacity: interpolate(frame, [SLAM - 6, SLAM + 8], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          平均响应 1.7s · 28.6k tokens
        </div>
      </div>
    </AbsoluteFill>
  );
};
