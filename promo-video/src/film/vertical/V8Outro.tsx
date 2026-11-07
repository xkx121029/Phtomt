import React from 'react';
import { AbsoluteFill, Easing, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { C, F } from '../tokens';
import { beatF } from '../beat';
import { mulberry32 } from '../lib/rand';

const TILE = 96;
const GAP = 22;
const MARK_X = 540 - (TILE * 2 + GAP) / 2; // 433
const MARK_Y = 722;
const FILLED = 1;
const CX = 540;
const CY = MARK_Y + TILE + GAP / 2;

const WORD = 'Happy Agent';

/** 四块象限 → 四块图元，收拢成品牌标志（竖版：从 540×960 的四格收进来） */
const BLOCKS = [
  { src: 'textures/screens/memory.png', qx: 0, qy: 0 },
  { src: 'textures/screens/home.png', qx: 540, qy: 0 },
  { src: 'textures/screens/coding.png', qx: 0, qy: 960 },
  { src: 'textures/screens/memory-pages.png', qx: 540, qy: 960 },
];

/**
 * V8 · 收尾（竖版）。
 * 横版是「标记在左、字标在右」的横向排布；竖版改成纵向居中：
 * 图元在上、字标在下、再下面是标语 —— 这是 9:16 唯一能同时装下三段的排法。
 */
export const V8Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const SLAM = beatF(4);
  const MARK = beatF(12);

  const conv = interpolate(frame, [4, SLAM], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.25, 0, 0.2, 1),
  });

  const t = frame - SLAM;
  const pop = t < 0 ? 0 : Math.exp(-t / 7) * Math.cos(t * 0.75);
  const framePump = t < 0 ? 1 : 1 + 0.022 * Math.exp(-t / 5) * Math.cos(t * 0.9);
  const flash =
    t < 0
      ? 0
      : interpolate(t, [0, 9], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const bloom =
    t < 0
      ? 0
      : interpolate(t, [0, 24], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });

  const letters = WORD.split('');
  const rnd = mulberry32(861106);

  return (
    <AbsoluteFill style={{ transform: `scale(${framePump})` }}>
      {/* 舞台光：字标背后低位聚光 */}
      <div
        style={{
          position: 'absolute',
          left: CX,
          top: CY + 120,
          width: 1240,
          height: 1400,
          transform: 'translate(-50%,-50%)',
          background:
            'radial-gradient(closest-side, rgba(95,217,180,0.16), rgba(95,217,180,0.05) 46%, transparent 74%)',
          opacity: interpolate(frame, [MARK - 40, MARK + 20], [0.25, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
          }),
          filter: 'blur(16px)',
        }}
      />

      {/* b88 光爆：紧核 + 外扩环 */}
      <div
        style={{
          position: 'absolute',
          left: CX,
          top: CY,
          width: 760,
          height: 760,
          transform: 'translate(-50%,-50%)',
          background:
            'radial-gradient(closest-side, rgba(226,255,247,0.95), rgba(95,217,180,0.34) 26%, transparent 62%)',
          opacity: flash * 0.6,
          mixBlendMode: 'screen',
          pointerEvents: 'none',
        }}
      />
      {t >= 0 ? (
        <div
          style={{
            position: 'absolute',
            left: CX,
            top: CY,
            width: 190 + Math.min(t, 20) * 30,
            height: 190 + Math.min(t, 20) * 30,
            transform: 'translate(-50%,-50%)',
            borderRadius: '50%',
            border: `3px solid rgba(140,240,214,${Math.max(0, 0.8 - t / 20)})`,
            boxShadow: `0 0 40px rgba(95,217,180,${Math.max(0, 0.4 - t / 24)})`,
            pointerEvents: 'none',
          }}
        />
      ) : null}

      {/* 四块收拢成图元 */}
      {BLOCKS.map((b, i) => {
        const col = i % 2;
        const row = Math.floor(i / 2);
        const fromX = col * 540;
        const fromY = row * 960;
        const toX = MARK_X + col * (TILE + GAP);
        const toY = MARK_Y + row * (TILE + GAP);
        const x = fromX + (toX - fromX) * conv;
        const y = fromY + (toY - fromY) * conv;
        const w = 540 + (TILE - 540) * conv;
        const h = 960 + (TILE - 960) * conv;
        const isFilled = i === FILLED;
        const imgO = interpolate(frame, [4, 12], [1, 0], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        });
        const strokeO = interpolate(conv, [0.25, 0.95], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        });
        const r = 18 + (16 - 18) * conv;
        const scale = t < 0 ? 1 : 1 + pop * 0.1;

        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: x,
              top: y,
              width: w,
              height: h,
              borderRadius: 2 + r * conv,
              overflow: 'hidden',
              transform: `scale(${scale})`,
              transformOrigin: 'center center',
              backgroundColor: isFilled ? `rgba(95,217,180,${strokeO})` : 'rgba(9,12,11,0.92)',
              border: `${Math.round(8 * conv)}px solid rgba(95,217,180,${strokeO})`,
              boxShadow: `0 0 ${30 * bloom}px rgba(95,217,180,${0.3 * bloom})`,
            }}
          >
            <Img
              src={staticFile(b.src)}
              style={{ width: '100%', height: '100%', objectFit: 'cover', opacity: imgO }}
            />
          </div>
        );
      })}

      {/* 字标：图元下方居中，逐字 letterpress */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: MARK_Y + TILE * 2 + GAP + 78,
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'baseline',
          whiteSpace: 'nowrap',
        }}
      >
        {letters.map((ch, i) => {
          const at = SLAM + 6 + i * 3.2;
          const p = interpolate(frame, [at, at + 14], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          });
          const jitter = (rnd() - 0.5) * 6;
          return (
            <span
              key={i}
              style={{
                fontFamily: F.sans,
                fontSize: 104,
                fontWeight: 700,
                letterSpacing: '-0.018em',
                color: C.ink,
                display: 'inline-block',
                whiteSpace: 'pre',
                opacity: p,
                transform: `translateY(${(1 - p) * 26 + jitter * 0.2}px)`,
                textShadow: `0 ${6 * p}px ${24 * p}px rgba(0,0,0,0.7)`,
              }}
            >
              {ch}
            </span>
          );
        })}
      </div>

      {/* 长出的横线 + 标语 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: MARK_Y + TILE * 2 + GAP + 268,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
        }}
      >
        <div
          style={{
            width: interpolate(frame, [MARK, MARK + 22], [0, 640], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            height: 2,
            background: `linear-gradient(90deg, rgba(95,217,180,0.06), ${C.jade}, rgba(95,217,180,0.06))`,
            marginBottom: 30,
          }}
        />
        <div
          style={{
            fontFamily: F.sans,
            fontSize: 44,
            fontWeight: 400,
            letterSpacing: '0.04em',
            color: C.ink2,
            opacity: interpolate(frame, [MARK + 6, MARK + 26], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
            transform: `translateY(${interpolate(frame, [MARK + 6, MARK + 26], [10, 0], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            })}px)`,
            whiteSpace: 'nowrap',
          }}
        >
          一句话，手机自己做完
        </div>
      </div>

      {/* 金尘 */}
      {t > 0
        ? Array.from({ length: 26 }).map((_, i) => {
            const r = mulberry32(9001 + i * 37);
            const x0 = 300 + r() * 520;
            const y0 = 1180 + r() * 140;
            const sp = 0.5 + r() * 0.9;
            const size = 1.5 + r() * 2.5;
            const life = 60 + r() * 90;
            const age = Math.min(t, life);
            const o = Math.sin((age / life) * Math.PI) * 0.5;
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  left: x0 + Math.sin((age + i) * 0.02) * 16,
                  top: y0 - age * sp,
                  width: size,
                  height: size,
                  borderRadius: size,
                  background: i % 4 === 0 ? C.ink : C.jade,
                  opacity: o,
                }}
              />
            );
          })
        : null}
    </AbsoluteFill>
  );
};
