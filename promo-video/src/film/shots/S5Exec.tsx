import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { Caption } from '../chrome/Caption';
import { beatF } from '../beat';
import { C } from '../tokens';

/** 三步完成的时刻（相对本镜头），钉在拍上 */
const STEPS = [beatF(1.6), beatF(3.4), beatF(5.2)];

/**
 * S5 · 执行。drop 落在本镜第一拍：设备带 1.5% 压下就位，
 * 随后三步依次在日志里落定（每次一枚 jade 涟漪 + 底部进度线推进）。
 */
export const S5Exec: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  // 开场：1.5% 压下 + 辉光脉冲（元素层，不是全画面冲击）
  const settle = spring({ frame, fps, config: { damping: 16, mass: 0.7, stiffness: 220 } });
  const scale = interpolate(settle, [0, 1], [1.045, 1]);
  const glowPulse = interpolate(frame, [0, 14], [1.35, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const push = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.33, 0, 0.15, 1),
  });

  // 底部进度线：跟着三步推进
  const progress = interpolate(
    frame,
    [0, STEPS[0], STEPS[1], STEPS[2], durationInFrames],
    [0.32, 0.55, 0.78, 1, 1],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );

  return (
    <AbsoluteFill>
      <Device
        src="textures/screens/agent-run.png"
        sw={474}
        x={0}
        y={0}
        contentZoom={1.0 + push * 0.07}
        focus={{ x: 206, y: 440 }}
        scale={scale}
        glow="#2e6b57"
        glowStrength={0.52 * glowPulse}
      >
        {/* 三次落定：整行卡片描边亮起再熄灭（bbox 取自 layout.json 的 .tool） */}
        {STEPS.map((sf, i) => {
          const t = frame - sf;
          if (t < 0) return null;
          const o = interpolate(t, [0, 2, 13], [0, 1, 0], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
          });
          return (
            <div
              key={i}
              style={{
                position: 'absolute',
                left: 16,
                top: 206 + i * 76,
                width: 358,
                height: 62,
                borderRadius: 16,
                border: `2px solid rgba(95,217,180,${o})`,
                background: `rgba(95,217,180,${0.10 * o})`,
                boxShadow: `0 0 ${26 * o}px rgba(95,217,180,${0.55 * o}), inset 0 0 ${22 * o}px rgba(95,217,180,${0.3 * o})`,
              }}
            />
          );
        })}

        {/* 屏底进度线：本步骤推进的物理读数 */}
        <div
          style={{
            position: 'absolute',
            left: 20,
            bottom: 146,
            width: 372,
            height: 2.5,
            borderRadius: 2,
            background: 'rgba(232,234,230,0.10)',
          }}
        >
          <div
            style={{
              width: `${progress * 100}%`,
              height: '100%',
              borderRadius: 2,
              background: C.jade,
              boxShadow: `0 0 10px rgba(95,217,180,0.6)`,
            }}
          />
        </div>
      </Device>

      <Caption
        text="它开始自己动手"
        lead="44 个意图，由端侧转译成点击与滑动"
        duration={durationInFrames}
        delay={beatF(1.6)}
      />
    </AbsoluteFill>
  );
};
