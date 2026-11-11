import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { C, F } from '../tokens';
import { VERTICAL } from './vertical-beat';

/**
 * 竖版头部：眉标 → 主标题（可 2 行）→ 副行。
 * 竖版字幕在画面上方、居中、单栏，字号比横版大一档：
 * 1080 宽下主标题 76px（7.0% 帧宽），副行 38px，比横版更「近」。
 */
export const VHeadline: React.FC<{
  kicker?: string;
  /** 用 \n 断行（不靠自动折行，避免不同字体度量下断行位置漂移） */
  text: string;
  lead?: string;
  duration: number;
  delay?: number;
  top?: number;
  size?: number;
  align?: 'left' | 'center';
}> = ({
  kicker,
  text,
  lead,
  duration,
  delay = 0,
  top = VERTICAL.headY,
  size = 76,
  align = 'center',
}) => {
  const frame = useCurrentFrame();
  const t = frame - delay;
  const inP = interpolate(t, [0, 14], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const outP = interpolate(frame, [duration - 10, duration], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const o = inP * outP;
  const dy = (1 - inP) * 18;
  const center = align === 'center';

  return (
    <div
      style={{
        position: 'absolute',
        left: VERTICAL.margin,
        right: VERTICAL.margin,
        top,
        display: 'flex',
        flexDirection: 'column',
        alignItems: center ? 'center' : 'flex-start',
        gap: 22,
        opacity: o,
        transform: `translateY(${dy}px)`,
        textAlign: center ? 'center' : 'left',
      }}
    >
      {kicker ? (
        <div
          style={{
            fontFamily: F.mono,
            fontSize: 30,
            fontWeight: 400,
            letterSpacing: '0.2em',
            color: C.ink3,
            textTransform: 'uppercase',
            whiteSpace: 'nowrap',
          }}
        >
          {kicker}
        </div>
      ) : null}

      <div
        style={{
          fontFamily: F.sans,
          fontSize: size,
          fontWeight: 500,
          lineHeight: 1.2,
          letterSpacing: '0.004em',
          color: C.ink,
          textShadow: '0 3px 30px rgba(var(--rgb-halo),0.8)',
          whiteSpace: 'pre-line',
        }}
      >
        {text}
      </div>

      {lead ? (
        <div
          style={{
            fontFamily: F.sans,
            fontSize: 38,
            fontWeight: 400,
            lineHeight: 1.42,
            letterSpacing: '0.01em',
            color: C.ink2,
            textShadow: '0 2px 20px rgba(var(--rgb-halo),0.85)',
            whiteSpace: 'pre-line',
          }}
        >
          {lead}
        </div>
      ) : null}
    </div>
  );
};

/**
 * 竖版底部字幕：设备下缘出血时，字幕只能落在设备上方的下半区，
 * 所以给一层比横版更宽的压暗（scrimH 520）再落字。
 */
export const VCaption: React.FC<{
  text: string;
  lead?: string;
  duration: number;
  delay?: number;
  bottom?: number;
}> = ({ text, lead, duration, delay = 0, bottom = VERTICAL.captionBottom }) => {
  const frame = useCurrentFrame();
  const t = frame - delay;
  const inP = interpolate(t, [0, 12], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const outP = interpolate(frame, [duration - 10, duration], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const o = inP * outP;

  return (
    <div
      style={{
        position: 'absolute',
        left: VERTICAL.margin,
        right: VERTICAL.margin,
        bottom,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 14,
        opacity: o,
        transform: `translateY(${(1 - inP) * 14}px)`,
        textAlign: 'center',
      }}
    >
      <div
        style={{
          fontFamily: F.sans,
          fontSize: 64,
          fontWeight: 500,
          lineHeight: 1.24,
          color: C.ink,
          textShadow: '0 2px 26px rgba(var(--rgb-halo),0.8)',
          whiteSpace: 'nowrap',
        }}
      >
        {text}
      </div>
      {lead ? (
        <div
          style={{
            fontFamily: F.sans,
            fontSize: 36,
            fontWeight: 400,
            lineHeight: 1.4,
            color: C.ink2,
            letterSpacing: '0.012em',
            whiteSpace: 'nowrap',
            textShadow: '0 2px 18px rgba(var(--rgb-halo),0.85)',
          }}
        >
          {lead}
        </div>
      ) : null}
    </div>
  );
};
