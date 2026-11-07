import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { C, F, TYPE } from '../tokens';

/**
 * 叙事字幕。Q11：主字幕有效字高 56px（5.2% 帧高），副行 34px（3.1%）。
 * 入场 10f 上浮 + 淡入，出场 8f 淡出；两者由调用方的 duration 控制。
 */
export const Caption: React.FC<{
  text: string;
  lead?: string;
  duration: number;
  /** 入场延迟（帧） */
  delay?: number;
  /** 相对画面左下的位置 */
  left?: number;
  bottom?: number;
  align?: 'left' | 'center';
}> = ({ text, lead, duration, delay = 0, left = 132, bottom = 104, align = 'left' }) => {
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
  const dy = (1 - inP) * 14;

  return (
    <div
      style={{
        position: 'absolute',
        left: align === 'left' ? left : 0,
        right: align === 'center' ? 0 : undefined,
        bottom,
        display: 'flex',
        flexDirection: 'column',
        alignItems: align === 'center' ? 'center' : 'flex-start',
        gap: 16,
        opacity: o,
        transform: `translateY(${dy}px)`,
      }}
    >
      <div
        style={{
          fontFamily: F.sans,
          fontSize: TYPE.caption,
          fontWeight: 500,
          lineHeight: 1.24,
          letterSpacing: '0.005em',
          color: C.ink,
          textShadow: '0 2px 24px rgba(0,0,0,0.75)',
          whiteSpace: 'nowrap',
        }}
      >
        {text}
      </div>
      {lead ? (
        <div
          style={{
            fontFamily: F.sans,
            fontSize: TYPE.captionLead,
            fontWeight: 400,
            lineHeight: 1.4,
            color: C.ink2,
            letterSpacing: '0.01em',
            whiteSpace: 'nowrap',
            textShadow: '0 2px 18px rgba(0,0,0,0.8)',
          }}
        >
          {lead}
        </div>
      ) : null}
    </div>
  );
};
