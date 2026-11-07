import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { beatF } from '../beat';
import { C } from '../tokens';
import { VHeadline } from './VChrome';
import { vDevY } from './vertical-beat';

const SW = 620;
const TOP = 560;
const STEPS = [beatF(1.6), beatF(3.4), beatF(5.2)];

/**
 * V5 · 执行（竖版）。
 * 横版把设备放中央、屏内工具行靠「整行描边」读进度；竖版同理，
 * 但取景更近（contentZoom 推 6%），工具行因此比横版大——这是竖版唯一允许「更近」的理由：
 * 观众要看清是哪一行在亮。
 */
export const V5Exec: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

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
        sw={SW}
        y={vDevY(SW, TOP)}
        contentZoom={1.06 + push * 0.06}
        focus={{ x: 206, y: 420 }}
        scale={scale}
        glow="#2e6b57"
        glowStrength={0.52 * glowPulse}
      >
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

      <VHeadline
        kicker="自己动手"
        text="它开始自己动手"
        lead="44 个意图，由端侧转译成点击与滑动"
        top={196}
        duration={durationInFrames}
        delay={beatF(1.6)}
      />
    </AbsoluteFill>
  );
};
