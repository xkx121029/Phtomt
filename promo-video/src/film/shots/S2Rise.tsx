import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig, Easing } from 'remotion';
import { Device } from '../chrome/Device';
import { Caption } from '../chrome/Caption';
import { beatF } from '../beat';
import { C } from '../tokens';

/**
 * S2 · 设备升起。单主角立传：暗场里一台设备自下升起，屏幕自发光亮起。
 * 一条完整动作弧：升起 → 落定 → 点亮 → 相机缓推。
 */
export const S2Rise: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  // 升起：spring 落定，带 1.03 过冲（落地隐喻，允许 y1>1）
  const rise = spring({
    frame,
    fps,
    config: { damping: 22, mass: 1.05, stiffness: 110 },
    durationInFrames: beatF(6),
  });
  const y = interpolate(rise, [0, 1], [260, 0]);
  const rotX = interpolate(rise, [0, 1], [26, 3.2]);
  const rotY = interpolate(rise, [0, 1], [-17, 0]);
  const scale = interpolate(rise, [0, 1], [0.9, 1]);

  // 点亮：屏幕亮度 0→1
  const lit = interpolate(frame, [beatF(1.2), beatF(4.4)], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.4, 0, 0.2, 1),
  });

  // 落定后相机极缓推近（后段）
  const push = interpolate(frame, [beatF(6), durationInFrames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.33, 0, 0.15, 1),
  });

  const glow = interpolate(lit, [0, 1], [0.06, 0.62]);

  return (
    <AbsoluteFill>
      <Device
        src="textures/screens/home.png"
        sw={392}
        x={212}
        y={y}
        rotX={rotX}
        rotY={rotY}
        scale={scale * (1 + push * 0.05)}
        brightness={lit}
        glow="#2e6b57"
        glowStrength={glow * 1.5}
        shadow={0.6 + rise * 0.4}
      />
      <Caption
        text="它先看清你的屏幕"
        lead="无障碍服务把整屏读成 46 个可点控件的树"
        duration={durationInFrames}
        delay={beatF(4.6)}
      />
    </AbsoluteFill>
  );
};
