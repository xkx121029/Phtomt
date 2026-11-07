import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, F } from '../tokens';
import { beatF } from '../beat';
import { mulberry32 } from '../lib/rand';

const SENTENCE = '帮我把手机字体调大一点';

/** S1 · 一句话：暗场里一条玻璃胶囊，一句话被打出来。
 *  typeStartBeat / charFrames 可调，供 15s 短版把打字压紧（长版用默认值）；
 *  pillTop / pillW 可调，供竖版把胶囊落到 9:16 画面里（横版用默认值）。 */
export const S1Sentence: React.FC<{
  typeStartBeat?: number;
  charFrames?: number;
  pillTop?: number;
  pillW?: number;
  glowW?: number;
  poolW?: number;
}> = ({
  typeStartBeat = 2.2,
  charFrames = 2.2,
  pillTop = 470,
  pillW = 1060,
  glowW = 1180,
  poolW = 880,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const TYPE_START = beatF(typeStartBeat);
  const CHAR_FRAMES = charFrames;
  const TYPE_END = TYPE_START + SENTENCE.length * CHAR_FRAMES;

  const pillIn = spring({ frame: frame - 6, fps, config: { damping: 200, mass: 0.6 } });
  const pillScale = interpolate(pillIn, [0, 1], [0.955, 1]);
  const pillOpacity = interpolate(frame, [6, 22], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 打字进度：字符数（浮点，末字带轻微减速）
  const charsRaw = Math.max(0, (frame - TYPE_START) / CHAR_FRAMES);
  const typed = SENTENCE.slice(0, Math.min(SENTENCE.length, Math.floor(charsRaw)));
  const typing = frame >= TYPE_START && frame < TYPE_END + 3;

  // 语音暖光随打字进度退去（glass-pill-dictation-typing 的核心语法）
  const progress = Math.min(1, charsRaw / SENTENCE.length);
  const warm = interpolate(progress, [0, 0.82, 1], [1, 0.42, 0.12], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 收尾：光标塌成一个点，交给 S2 的屏幕光
  const collapse = interpolate(frame, [beatF(10.4), beatF(11.6)], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const pillFade = interpolate(frame, [beatF(10.2), beatF(11.4)], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 光标闪烁（确定性：按帧号取模）
  const blink = Math.floor((frame - TYPE_START) / 8) % 2 === 0;
  const cursorOn = typing || blink;

  // 语音条：4 根细柱，确定性起伏
  const rnd = mulberry32(20261106);
  const bars = [0, 1, 2, 3].map((i) => {
    const seed = rnd();
    const speed = 0.18 + seed * 0.1;
    const live = frame < TYPE_START + 8 ? 1 : Math.max(0, 1 - (frame - TYPE_START - 8) / 20);
    const h = (8 + Math.abs(Math.sin(frame * speed + i * 1.7)) * 30) * live;
    return { h: Math.max(3, h), live };
  });

  const dotScale = 1 + collapse * 0.9;
  const dotGlow = collapse * 26;

  // 相机缓推：3.7s 内 1.00 → 1.045，给静止画面一点呼吸
  const cam = interpolate(frame, [0, 166], [1, 1.045], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.33, 0, 0.15, 1),
  });

  return (
    <AbsoluteFill style={{ transform: `scale(${cam})`, transformOrigin: '50% 54%' }}>
      {/* 胶囊外环境辉光 */}
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: pillTop,
          width: glowW,
          height: 460,
          transform: 'translate(-50%,-50%)',
          background: `radial-gradient(closest-side, rgba(95,217,180,${
            0.10 * warm + 0.02
          }), transparent 70%)`,
          filter: 'blur(10px)',
        }}
      />

      {/* 胶囊下方的落地光池：给它一点重量 */}
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: pillTop + 126,
          width: poolW,
          height: 120,
          transform: 'translate(-50%,-50%)',
          background:
            'radial-gradient(closest-side, rgba(95,217,180,0.10), rgba(95,217,180,0.03) 52%, transparent 76%)',
          filter: 'blur(16px)',
          opacity: pillOpacity * pillFade * (0.5 + warm * 0.5),
        }}
      />

      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: pillTop,
          transform: `translate(-50%,-50%) scale(${pillScale})`,
          opacity: pillOpacity * pillFade,
        }}
      >
        {/* 眉标 */}
        <div
          style={{
            fontFamily: F.sans,
            fontSize: 34,
            fontWeight: 400,
            letterSpacing: '0.22em',
            color: C.ink3,
            marginBottom: 24,
            paddingLeft: 6,
            opacity: interpolate(frame, [10, 26], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
          }}
        >
          你说的一句话
        </div>

        {/* 玻璃胶囊 */}
          <div
            style={{
              position: 'relative',
              width: pillW,
              height: 152,
            borderRadius: 76,
            background:
              'linear-gradient(180deg, rgba(38,44,41,0.92) 0%, rgba(22,26,24,0.95) 100%)',
            boxShadow: [
              'inset 0 0 0 1px rgba(232,234,230,0.14)',
              'inset 0 1px 0 rgba(255,255,255,0.10)',
              '0 26px 64px -18px rgba(0,0,0,0.9)',
            ].join(','),
            display: 'flex',
            alignItems: 'center',
            paddingLeft: 34,
            paddingRight: 34,
            overflow: 'hidden',
          }}
        >
          {/* 内部强调色暖光，自左向右铺开，随打字退去 */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background:
                'linear-gradient(90deg, rgba(95,217,180,0.20) 0%, rgba(95,217,180,0.07) 46%, rgba(95,217,180,0) 78%)',
              opacity: warm,
              pointerEvents: 'none',
            }}
          />

          {/* 语音条 */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 5,
              width: 48,
              marginRight: 24,
              flexShrink: 0,
            }}
          >
            {bars.map(({ h, live }, i) => (
              <div
                key={i}
                style={{
                  width: 4,
                  height: h,
                  borderRadius: 2,
                  background: C.jade,
                  opacity: (0.35 + 0.65 * warm) * live,
                }}
              />
            ))}
          </div>

          {/* 打出的字 */}
          <div
            style={{
              fontFamily: F.sans,
              fontSize: 58,
              fontWeight: 500,
              letterSpacing: '0.01em',
              color: C.ink,
              whiteSpace: 'nowrap',
              display: 'flex',
              alignItems: 'center',
            }}
          >
            <span>{typed}</span>
            <span
              style={{
                display: 'inline-block',
                width: 3,
                height: 56,
                marginLeft: 4,
                borderRadius: 2,
                background: C.jade,
                opacity: cursorOn ? 1 : 0.12,
                boxShadow: `0 0 ${dotGlow}px rgba(95,217,180,${0.5 * collapse})`,
                transform: `scaleY(${1 - collapse * 0.55}) scale(${dotScale})`,
              }}
            />
          </div>
        </div>

        {/* 下方提示 */}
        <div
          style={{
            fontFamily: F.sans,
            fontSize: 30,
            color: C.ink4,
            letterSpacing: '0.08em',
            marginTop: 26,
            paddingLeft: 6,
            opacity:
              interpolate(frame, [14, 30], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }) *
              interpolate(frame, [TYPE_START, TYPE_START + 14], [1, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
          }}
        >
          按住说话 · 或者直接打字
        </div>
      </div>
    </AbsoluteFill>
  );
};
