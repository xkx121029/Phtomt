import React from 'react';
import { AbsoluteFill, Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { beatF } from '../beat';
import { C } from '../tokens';
import { VCaption, VHeadline } from './VChrome';

/** 计划面板在真机屏里的 bbox（layout.json: x21 y551 w370 h236），竖版铺满安全区宽 928 */
const PANEL_W = 370;
const PANEL_H = 236;
const CARD_W = 928;
const S = CARD_W / PANEL_W;
const CARD_H = PANEL_H * S;
const CARD_X = 540; // 卡片中心（画幅中心）
const CARD_Y = 1000;

/** 三行相对面板顶部的带区（.pstep y 616/652/688，h36；面板顶 551） */
const BANDS = [0, 1, 2].map((i) => ({
  top: (616 - 551 + i * 36) * S,
  h: 36 * S,
}));
const ROW_START = [beatF(1.0), beatF(2.8), beatF(4.6)];

/**
 * V3 · 规划（竖版）。
 * 横版是「设备退后 + 面板抽成前景卡」的两层；竖版只有一层——卡片铺满安全区宽，
 * 因为 9:16 里再塞一台设备，两层都会读不清。卡的倾角也比横版小：卡片越宽，同样的角度越容易出画。
 */
export const V3Plan: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const enter = spring({ frame, fps, config: { damping: 26, mass: 1.05, stiffness: 90 } });
  const push = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.33, 0, 0.15, 1),
  });
  const cardRotY = interpolate(enter, [0, 1], [-13, -5]);
  const cardRotX = interpolate(enter, [0, 1], [8, 2]);
  const cardZ = interpolate(enter, [0, 1], [-180, 0]) - push * 60;
  const cardScale = interpolate(enter, [0, 1], [0.94, 1]) * (1 + push * 0.025);

  const clickAt = beatF(9);
  const ripple = interpolate(frame, [clickAt, clickAt + 20], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const ringR = spring({
    frame: frame - clickAt,
    fps,
    config: { damping: 18, mass: 0.5, stiffness: 160 },
  });

  return (
    <AbsoluteFill>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          perspective: 1800,
          perspectiveOrigin: '540px 960px',
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
          <div
            style={{
              position: 'absolute',
              inset: -4,
              borderRadius: 46,
              boxShadow:
                '0 70px 140px -30px rgba(var(--rgb-shadow),calc(0.95 * var(--shade))), 0 0 0 1px rgba(var(--rgb-ink),0.10)',
            }}
          />
          <div
            style={{
              position: 'absolute',
              inset: 0,
              borderRadius: 40,
              overflow: 'hidden',
              background: C.panel,
            }}
          >
            <Img
              src={staticFile('textures/screens/agent-plan-panel.png')}
              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
            />

            {/* 三行揭盖：每行落定亮一道 jade 缝 */}
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
                    left: 20,
                    top: b.top,
                    width: CARD_W - 40,
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
                      left: 12,
                      right: 12,
                      top: b.h - coverH,
                      height: 3,
                      background: C.jade,
                      opacity: seamO,
                      boxShadow: `0 0 20px ${C.jade}`,
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
                borderRadius: 60,
                border: `3px solid ${C.jade}`,
                opacity: (1 - ripple) * 0.85,
                transform: `scale(${0.55 + ringR * 0.85})`,
              }}
            />
          </div>
        </div>
      </div>

      <VHeadline
        kicker="拆成计划"
        text="再拆成能执行的计划"
        lead="每一步都要有页面证据"
        top={200}
        duration={durationInFrames}
        delay={beatF(1)}
      />

      <VCaption
        text="3 步 · 步步可核验"
        duration={durationInFrames}
        delay={beatF(6.4)}
        bottom={284}
      />
    </AbsoluteFill>
  );
};
