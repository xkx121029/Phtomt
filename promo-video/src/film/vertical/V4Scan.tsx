import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Device } from '../chrome/Device';
import { C, F, SCREEN } from '../tokens';
import { beatF } from '../beat';
import { vDevY } from './vertical-beat';

const SW = 620;
const TOP = 570;
const MARGIN = 76;

/** 被识别的控件（bbox 来自模拟器 uiautomator 的 bounds × 412/1080） */
const TARGETS = [
  { x1: 11, y1: 137, x2: 401, y2: 211, tag: 'text', name: '亮度 50%' },
  { x1: 11, y1: 268, x2: 401, y2: 325, tag: 'switch', name: '锁屏 · 通知' },
  { x1: 11, y1: 560, x2: 401, y2: 627, tag: 'row', name: '显示大小和文字' },
  { x1: 11, y1: 728, x2: 401, y2: 772, tag: 'row', name: '屏保 时钟' },
];

const SCAN_FROM = beatF(0.5);
const SCAN_TO = beatF(7.6);

/**
 * V4 · 框选控件（竖版）。
 * 横版把标注放在设备右侧、用引出线连到框；9:16 两侧只有 ~200px，放不下。
 * 竖版改成「清单 + 同刻出现」：屏内只留四角括线（不压内容——真机界面从 x=11 铺到 x=401，
 * 屏内没有一处空隙能安全承载编号牌），编号与标签全部上提到画面顶部，
 * 靠「扫描线越过框底那一帧」的同步出现来建立对应关系，不靠引出线。
 */
export const V4Scan: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const push = interpolate(frame, [0, durationInFrames], [1, 1.03], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const scanY = interpolate(frame, [SCAN_FROM, SCAN_TO], [-30, SCREEN.h + 30], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const scanOn = frame >= SCAN_FROM && frame <= SCAN_TO + 4;

  const trigFrame = (y2: number) =>
    SCAN_FROM + ((y2 + 30) / (SCREEN.h + 60)) * (SCAN_TO - SCAN_FROM);

  const found = TARGETS.filter((t) => frame >= trigFrame(t.y2)).length;

  return (
    <AbsoluteFill>
      <Device
        src="textures/device/settings-zh.png"
        sw={SW}
        y={vDevY(SW, TOP)}
        scale={push}
        glow="#28596f"
        glowStrength={0.34}
      >
        {scanOn ? (
          <>
            <div
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                top: scanY - 52,
                height: 52,
                background:
                  'linear-gradient(180deg, rgba(var(--rgb-mist),0) 0%, rgba(var(--rgb-mist),0.10) 100%)',
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                top: scanY,
                height: 2,
                background: C.mist,
                boxShadow: `0 0 10px 1px rgba(var(--rgb-mist),0.55)`,
              }}
            />
          </>
        ) : null}

        {TARGETS.map((t, i) => {
          const tf = trigFrame(t.y2);
          if (frame < tf) return null;
          const s = spring({
            frame: frame - tf,
            fps,
            config: { damping: 15, stiffness: 210, mass: 0.9 },
          });
          const expand = (1 - s) * 42;
          const cx1 = t.x1 - expand;
          const cy1 = t.y1 - expand;
          const cx2 = t.x2 + expand;
          const cy2 = t.y2 + expand;
          const len = 16;
          const w = 2.4;
          const corners = [
            { l: cx1, t: cy1, bl: true, bt: true },
            { l: cx2 - len, t: cy1, bl: false, bt: true },
            { l: cx1, t: cy2 - len, bl: true, bt: false },
            { l: cx2 - len, t: cy2 - len, bl: false, bt: false },
          ].map((c, k) => (
            <div
              key={k}
              style={{
                position: 'absolute',
                left: c.l,
                top: c.t,
                width: len,
                height: len,
                borderLeft: c.bl ? `${w}px solid ${C.mist}` : undefined,
                borderRight: !c.bl ? `${w}px solid ${C.mist}` : undefined,
                borderTop: c.bt ? `${w}px solid ${C.mist}` : undefined,
                borderBottom: !c.bt ? `${w}px solid ${C.mist}` : undefined,
                borderTopLeftRadius: c.bl && c.bt ? 3.5 : 0,
                borderTopRightRadius: !c.bl && c.bt ? 3.5 : 0,
                borderBottomLeftRadius: c.bl && !c.bt ? 3.5 : 0,
                borderBottomRightRadius: !c.bl && !c.bt ? 3.5 : 0,
                opacity: s,
              }}
            />
          ));

          return <React.Fragment key={i}>{corners}</React.Fragment>;
        })}
      </Device>

      {/* 顶部读数：机器数到第几个 */}
      <div
        style={{
          position: 'absolute',
          left: MARGIN,
          top: 142,
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          opacity: interpolate(frame, [0, 12], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
          }),
        }}
      >
        <span
          style={{
            width: 10,
            height: 10,
            borderRadius: 10,
            background: scanOn ? C.mist : C.jade,
            boxShadow: `0 0 12px ${scanOn ? C.mist : C.jade}`,
          }}
        />
        <span style={{ fontFamily: F.mono, fontSize: 38, color: C.ink2, letterSpacing: '0.06em' }}>
          识别控件 {String(found).padStart(2, '0')} / 04
        </span>
      </div>

      {/* 01–04 读数清单：与屏内编号一一对应 */}
      <div style={{ position: 'absolute', left: MARGIN, top: 224 }}>
        {TARGETS.map((t, i) => {
          const tf = trigFrame(t.y2);
          if (frame < tf) return null;
          const appear = interpolate(frame, [tf, tf + 8], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          });
          const chars = Math.max(0, Math.floor((frame - tf - 3) / 1.1));
          const full = `${t.tag} · ${t.name}`;
          const shown = full.slice(0, Math.max(0, chars));
          return (
            <div
              key={i}
              style={{
                display: 'flex',
                alignItems: 'baseline',
                gap: 16,
                height: 62,
                opacity: appear,
                transform: `translateX(${(1 - appear) * -16}px)`,
              }}
            >
              {/* 同形括角：与屏内四角取景框同一种语法，暗示「这一行来自那个框」 */}
              <span
                style={{
                  width: 15,
                  height: 15,
                  borderLeft: `2px solid ${C.mist}`,
                  borderTop: `2px solid ${C.mist}`,
                  opacity: 0.85,
                  alignSelf: 'center',
                }}
              />
              <span style={{ fontFamily: F.mono, fontSize: 32, color: C.mist, opacity: 0.72 }}>
                {String(i + 1).padStart(2, '0')}
              </span>
              <span
                style={{
                  fontFamily: F.mono,
                  fontSize: 40,
                  color: C.ink,
                  letterSpacing: '0.01em',
                  whiteSpace: 'nowrap',
                }}
              >
                {shown}
                {chars < full.length ? (
                  <span style={{ color: C.mist, opacity: 0.8 }}>|</span>
                ) : null}
              </span>
            </div>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};
