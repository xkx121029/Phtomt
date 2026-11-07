import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig, Easing } from 'remotion';
import { Device } from '../chrome/Device';
import { Caption } from '../chrome/Caption';
import { C, F } from '../tokens';
import { SCREEN } from '../tokens';
import { beatF } from '../beat';

const SW = 430;
const DX = 66;
const DY = -38;
const SCALE = SW / SCREEN.w;
const SH = (SW * SCREEN.h) / SCREEN.w;
const LEFT = 960 + DX - SW / 2;
const TOP = 540 + DY - SH / 2;

/** 逻辑屏坐标 → 画面坐标（正视机位，无透视变形，可直接换算） */
const proj = (lx: number, ly: number) => ({ x: LEFT + lx * SCALE, y: TOP + ly * SCALE });

/** 被识别的控件（bbox 来自模拟器 uiautomator 的 bounds × 412/1080） */
const TARGETS = [
  { x1: 11, y1: 137, x2: 401, y2: 211, tag: 'text', name: '亮度 50%' },
  { x1: 11, y1: 268, x2: 401, y2: 325, tag: 'switch', name: '锁屏 · 通知' },
  { x1: 11, y1: 560, x2: 401, y2: 627, tag: 'row', name: '显示大小和文字' },
  { x1: 11, y1: 728, x2: 401, y2: 772, tag: 'row', name: '屏保 时钟' },
];

const SCAN_FROM = beatF(0.5);
const SCAN_TO = beatF(7.6);

export const S4Scan: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  // 极缓推镜（卡片要求全程 1.000→1.035）
  const push = interpolate(frame, [0, durationInFrames], [1, 1.035], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 扫描线严格匀速（卡片命门：匀速才读作「机器在读」，缓动即失真）
  const scanY = interpolate(frame, [SCAN_FROM, SCAN_TO], [-30, SCREEN.h + 30], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const scanOn = frame >= SCAN_FROM && frame <= SCAN_TO + 4;

  /** 扫描线越过 bbox 下缘那一帧 */
  const trigFrame = (y2: number) =>
    SCAN_FROM + ((y2 + 30) / (SCREEN.h + 60)) * (SCAN_TO - SCAN_FROM);

  const found = TARGETS.filter((t) => frame >= trigFrame(t.y2)).length;

  return (
    <AbsoluteFill>
      <Device
        src="textures/device/settings-zh.png"
        sw={SW}
        x={DX}
        y={DY}
        scale={push}
        glow="#28596f"
        glowStrength={0.34}
      >
        {/* 扫描线：3px 光芯 + 140px 渐隐尾迹 */}
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
                  'linear-gradient(180deg, rgba(132,182,212,0) 0%, rgba(132,182,212,0.10) 100%)',
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
                boxShadow: `0 0 10px 1px rgba(132,182,212,0.55)`,
              }}
            />
          </>
        ) : null}

        {/* 四角取景框 + 逐字标签（画在屏内，跟着相机走） */}
        {TARGETS.map((t, i) => {
          const tf = trigFrame(t.y2);
          const s = spring({
            frame: frame - tf,
            fps,
            config: { damping: 15, stiffness: 210, mass: 0.9 },
          });
          if (frame < tf) return null;
          const expand = (1 - s) * 42;
          const cx1 = t.x1 - expand;
          const cy1 = t.y1 - expand;
          const cx2 = t.x2 + expand;
          const cy2 = t.y2 + expand;
          const len = 16;
          const w = 2.4;
          const corners = (
            [
              { l: cx1, t: cy1, bl: true, bt: true },
              { l: cx2 - len, t: cy1, bl: false, bt: true },
              { l: cx1, t: cy2 - len, bl: true, bt: false },
              { l: cx2 - len, t: cy2 - len, bl: false, bt: false },
            ] as const
          ).map((c, k) => (
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

      {/* 画面空间的机器标注：右列，带引出线。字号 36px 保证可读 */}
      {TARGETS.map((t, i) => {
        const tf = trigFrame(t.y2);
        if (frame < tf) return null;
        const p1 = proj(t.x2, (t.y1 + t.y2) / 2);
        const labelX = 1262;
        const labelY = p1.y;
        const appear = interpolate(frame, [tf, tf + 8], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        });
        const chars = Math.max(0, Math.floor((frame - tf - 4) / 1.2));
        const full = `${t.tag} · ${t.name}`;
        const shown = full.slice(0, Math.max(0, chars));
        return (
          <div key={i} style={{ opacity: appear }}>
            {/* 引出线：从框右缘到标签 */}
            <div
              style={{
                position: 'absolute',
                left: p1.x + 6,
                top: labelY,
                width: Math.max(0, (labelX - p1.x - 20) * interpolate(frame, [tf, tf + 10], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' })),
                height: 1,
                background: `linear-gradient(90deg, rgba(132,182,212,0.55), rgba(132,182,212,0.15))`,
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: labelX,
                top: labelY - 17,
                display: 'flex',
                alignItems: 'baseline',
                gap: 12,
                whiteSpace: 'nowrap',
              }}
            >
              <span
                style={{
                  fontFamily: F.mono,
                  fontSize: 32,
                  color: C.mist,
                  opacity: 0.75,
                }}
              >
                {String(i + 1).padStart(2, '0')}
              </span>
              <span
                style={{
                  fontFamily: F.mono,
                  fontSize: 44,
                  color: C.ink,
                  letterSpacing: '0.01em',
                }}
              >
                {shown}
                {chars < full.length ? (
                  <span style={{ color: C.mist, opacity: 0.8 }}>|</span>
                ) : null}
              </span>
            </div>
          </div>
        );
      })}

      {/* 顶部状态行：机器读数的计数 */}
      <div
        style={{
          position: 'absolute',
          left: 132,
          top: 96,
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
            width: 9,
            height: 9,
            borderRadius: 9,
            background: scanOn ? C.mist : C.jade,
            boxShadow: `0 0 12px ${scanOn ? C.mist : C.jade}`,
          }}
        />
        <span style={{ fontFamily: F.mono, fontSize: 42, color: C.ink2, letterSpacing: '0.06em' }}>
          识别控件 {String(found).padStart(2, '0')} / 04
        </span>
      </div>

      <Caption
        text="每一步都对准真实控件"
        lead="不猜坐标，不盲点"
        duration={durationInFrames}
        delay={beatF(1.2)}
      />
    </AbsoluteFill>
  );
};
