import React from 'react';
import { AbsoluteFill, Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { Caption } from '../chrome/Caption';
import { beatF } from '../beat';
import { C, F } from '../tokens';

/** 计划面板在真机屏里的 bbox（layout.json: x21 y551 w370 h236 含 2px 余量），按 780 宽等比放大 */
const CARD_PANEL_W = 370;
const CARD_PANEL_H = 236;
const CARD_W = 858;
const S = CARD_W / CARD_PANEL_W;
const CARD_H = CARD_PANEL_H * S;
const CARD_X = 592; // 卡片中心
const CARD_Y = 512;

/** 三行相对面板顶部的带区（.pstep y 616/652/688，h36；面板顶 551） */
const BANDS = [0, 1, 2].map((i) => ({
  top: (616 - 551 + i * 36) * S,
  h: 36 * S,
}));
const ROW_START = [beatF(1.0), beatF(2.8), beatF(4.6)];

/**
 * S3 · 规划。设备退成背景，计划面板抽成前景卡做 3D 特写；
 * 四行自上而下揭出（row-embed 语法），每行落定亮一道 jade 缝。
 * 面板底色实测为纯 #1B1F1C，同色遮罩揭盖不可见。
 */
export const S3Plan: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  // 卡片入场：3D 抬起 → 落定，之后极缓推近
  const enter = spring({ frame, fps, config: { damping: 26, mass: 1.05, stiffness: 90 } });
  const push = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.33, 0, 0.15, 1),
  });
  const cardRotY = interpolate(enter, [0, 1], [-16, -7]);
  const cardRotX = interpolate(enter, [0, 1], [9, 2.4]);
  const cardZ = interpolate(enter, [0, 1], [-160, 0]) - push * 40;
  const cardScale = interpolate(enter, [0, 1], [0.94, 1]) * (1 + push * 0.03);

  const clickAt = beatF(9);
  const ripple = interpolate(frame, [clickAt, clickAt + 20], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const ringR = spring({ frame: frame - clickAt, fps, config: { damping: 18, mass: 0.5, stiffness: 160 } });

  return (
    <AbsoluteFill>
      {/* 背景设备：退后、压暗，只提供「这还是那台手机」的语境 */}
      <Device
        src="textures/screens/agent-plan.png"
        sw={340}
        x={648}
        y={40}
        rotY={-13}
        rotX={3}
        brightness={0.42}
        glow="#2e6b57"
        glowStrength={0.42}
        shadow={0.8}
      />

      {/* 前景：计划面板 3D 特写卡 */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          perspective: 1800,
          perspectiveOrigin: '960px 540px',
        }}
      >
        <div
          style={{
            position: 'absolute',
            left: CARD_X - CARD_W / 2,
            top: CARD_Y - CARD_H / 2,
            width: CARD_W,
            height: CARD_H,
            transform: `translateZ(${cardZ}px) rotateY(${cardRotY}deg) rotateX(${cardRotX}deg) scale(${cardScale})`,
            transformStyle: 'preserve-3d',
            opacity: interpolate(enter, [0, 0.55], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          {/* 卡片投影 */}
          <div
            style={{
              position: 'absolute',
              inset: -4,
              borderRadius: 34,
              boxShadow: '0 60px 120px -30px rgba(0,0,0,0.95), 0 0 0 1px rgba(232,234,230,0.10)',
            }}
          />
          <div
            style={{
              position: 'absolute',
              inset: 0,
              borderRadius: 30,
              overflow: 'hidden',
              background: C.panel,
            }}
          >
            <Img
              src={staticFile('textures/screens/agent-plan-panel.png')}
              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
            />

            {/* 四行揭盖 */}
            {BANDS.map((b, i) => {
              const start = ROW_START[i];
              const p = interpolate(frame, [start, start + 13], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              });
              const coverH = b.h * (1 - p);
              const seamO = interpolate(p, [0, 0.6, 1], [0, 1, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              });
              return (
                <div
                  key={i}
                  style={{
                    position: 'absolute',
                    left: 14,
                    top: b.top,
                    width: CARD_W - 28,
                    height: b.h,
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      position: 'absolute',
                      left: 0,
                      right: 0,
                      bottom: 0,
                      height: coverH,
                      background: C.panel,
                    }}
                  />
                  <div
                    style={{
                      position: 'absolute',
                      left: 10,
                      right: 10,
                      top: b.h - coverH,
                      height: 2.5,
                      background: C.jade,
                      opacity: seamO,
                      boxShadow: `0 0 16px ${C.jade}`,
                    }}
                  />
                </div>
              );
            })}

            {/* 批准按钮的一次点击反馈 */}
            <div
              style={{
                position: 'absolute',
                left: 272 * S,
                top: 205 * S,
                width: 106 * S,
                height: 30 * S,
                marginLeft: (-106 * S) / 2,
                marginTop: (-30 * S) / 2,
                borderRadius: 30,
                border: `2px solid ${C.jade}`,
                opacity: (1 - ripple) * 0.85,
                transform: `scale(${0.55 + ringR * 0.85})`,
              }}
            />
          </div>
        </div>
      </div>

      <Caption
        text="再拆成能执行的计划"
        lead="3 步，每一步都要有页面证据"
        duration={durationInFrames}
        delay={beatF(1)}
      />
    </AbsoluteFill>
  );
};
