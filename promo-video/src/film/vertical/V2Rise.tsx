import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { beatF } from '../beat';
import { VHeadline } from './VChrome';
import { vDevY } from './vertical-beat';

const SW = 600;
const TOP = 560;

/**
 * V2 · 设备升起（竖版）。
 * 横版里设备是靠右的「单主角」，竖版里设备就是画面的主体：
 * 屏宽 600（屏高 1299），顶端压在 560，下缘基本贴满画幅。
 * 旋转幅度比横版小一半——设备越大，同样的角度读起来越晃。
 */
export const V2Rise: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const rise = spring({
    frame,
    fps,
    config: { damping: 24, mass: 1.15, stiffness: 92 },
    durationInFrames: beatF(7),
  });
  const y = interpolate(rise, [0, 1], [300, 0]);
  const rotX = interpolate(rise, [0, 1], [16, 2]);
  const rotY = interpolate(rise, [0, 1], [-9, 0]);
  const scale = interpolate(rise, [0, 1], [0.92, 1]);

  const lit = interpolate(frame, [beatF(1.2), beatF(4.4)], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.4, 0, 0.2, 1),
  });

  const push = interpolate(frame, [beatF(8), durationInFrames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.33, 0, 0.15, 1),
  });

  const glow = interpolate(lit, [0, 1], [0.06, 0.6]);

  return (
    <AbsoluteFill>
      <Device
        src="textures/screens/home.png"
        sw={SW}
        y={vDevY(SW, TOP) + y}
        rotX={rotX}
        rotY={rotY}
        scale={scale * (1 + push * 0.04)}
        brightness={lit}
        glow="#2e6b57"
        glowStrength={glow * 1.5}
        shadow={0.6 + rise * 0.4}
      />
      <VHeadline
        kicker="先看清屏幕"
        text="它先看清你的屏幕"
        lead="无障碍服务把整屏读成 46 个可点控件的树"
        top={196}
        duration={durationInFrames}
        delay={beatF(4.6)}
      />
    </AbsoluteFill>
  );
};
