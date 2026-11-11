import React from 'react';
import { AbsoluteFill, Easing, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { GeometryStage } from '../chrome/GeometryStage';
import { C, F } from '../tokens';

type Example = {
  src: string;
  lang: string;
  file: string;
  note: string;
};

/** 三个真实（复刻页 1:1 截出）的设备内编码示例 */
const EXAMPLES: Example[] = [
  { src: 'textures/screens/coding-python-card.png', lang: 'Python', file: 'archive_clips.py', note: '批量归档录像' },
  { src: 'textures/screens/coding-compose-card.png', lang: 'Kotlin', file: 'PulseDot.kt', note: 'Compose 呼吸灯' },
  { src: 'textures/screens/coding-canvas-card.png', lang: 'TypeScript', file: 'geometry.ts', note: '几何动画' },
];

/**
 * S7b · 立体几何场景里的三个编码示例。
 *
 * 与 S7a 四宫格共用 s7_wall 的 8 拍区间（各占 4 拍），全片时间线、
 * 音乐钉帧、SFX 一律不动。几何场景在这里是主角（showcase 档），
 * 三张代码卡按透视排布、错拍浮起，不给全画面冲击。
 */
export const S7Coding: React.FC<{ vertical?: boolean }> = ({ vertical = false }) => {
  const frame = useCurrentFrame();
  const enter = (i: number) =>
    interpolate(frame, [i * 6, i * 6 + 14], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    });

  const cardW = 470;
  const cardH = cardW * 0.86 + 76; // 图片区 + 语言 chip 条
  const layout = vertical
    ? [
        // 竖版「上二下一」：三张卡的 chip 条都露得出来
        { x: -0.24, y: 0.44, rot: -5, scale: 0.94 },
        { x: 0.24, y: 0.44, rot: 5, scale: 0.94 },
        { x: 0, y: 0.77, rot: 0, scale: 0.92 },
      ]
    : [
        { x: -0.31, y: 0.50, rot: -7, scale: 0.9 },
        { x: 0, y: 0.48, rot: 0, scale: 1.02 },
        { x: 0.31, y: 0.50, rot: 7, scale: 0.9 },
      ];

  return (
    <AbsoluteFill style={{ backgroundColor: C.bg }}>
      <GeometryStage strength={1} showcase />

      {/* 场景里的一条基准地平线 + 三个锚点，把卡片串成一组 */}
      <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} aria-hidden>
        <line
          x1="12%"
          y1={vertical ? '31%' : '22%'}
          x2="88%"
          y2={vertical ? '31%' : '22%'}
          stroke={C.jade}
          strokeOpacity={0.16}
          strokeWidth={1}
        />
        {[0.21, 0.5, 0.79].map((p, i) => (
          <circle key={i} cx={`${p * 100}%`} cy={vertical ? '31%' : '22%'} r={4} fill={C.jade} fillOpacity={0.5} />
        ))}
      </svg>

      {/* 眉标 + 主标题 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: vertical ? 150 : 92,
          textAlign: 'center',
          opacity: interpolate(frame, [0, 12], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }),
        }}
      >
        <div
          style={{
            fontFamily: F.sans,
            fontSize: 34,
            fontWeight: 600,
            color: C.jade,
            letterSpacing: '0.26em',
          }}
        >
          设备内编码
        </div>
        <div
          style={{
            fontFamily: F.sans,
            fontSize: vertical ? 76 : 72,
            fontWeight: 700,
            color: C.ink,
            letterSpacing: '0.02em',
            marginTop: 12,
            textShadow: '0 10px 40px rgba(var(--rgb-halo),0.8)',
          }}
        >
          真的在写代码
        </div>
      </div>

      {EXAMPLES.map((e, i) => {
        const p = enter(i);
        const l = layout[i];
        return (
          <div
            key={e.src}
            style={{
              position: 'absolute',
              left: '50%',
              top: '50%',
              width: cardW,
              height: cardH,
              marginLeft: -cardW / 2,
              marginTop: -cardH / 2,
              transform: [
                `translate(${l.x * (vertical ? 1080 : 1920)}px, ${(l.y - 0.5) * (vertical ? 1920 : 1080) + (1 - p) * 46}px)`,
                `rotate(${l.rot}deg)`,
                `scale(${l.scale})`,
              ].join(' '),
              opacity: p,
              transformOrigin: 'center center',
            }}
          >
            <div
              style={{
                borderRadius: 22,
                overflow: 'hidden',
                border: `1px solid ${C.line2}`,
                background: C.panel,
                boxShadow: '0 40px 90px -30px rgba(var(--rgb-shadow),calc(0.95 * var(--shade))), 0 0 0 1px rgba(var(--rgb-jade),0.06)',
              }}
            >
              <Img
                src={staticFile(e.src)}
                style={{ width: '100%', height: cardW * 0.86, objectFit: 'cover', display: 'block' }}
              />
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 12,
                  padding: '18px 20px',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  borderTop: `1px solid ${C.line}`,
                  background: C.sunken,
                }}
              >
                <span
                  style={{
                    fontFamily: F.mono,
                    fontSize: 26,
                    color: C.jade,
                    border: `1px solid ${C.jadeDim}`,
                    background: C.jadeDim,
                    borderRadius: 9,
                    padding: '4px 10px',
                    flex: 'none',
                  }}
                >
                  {e.lang}
                </span>
                <span
                  style={{
                    fontFamily: F.mono,
                    fontSize: 26,
                    color: C.ink2,
                    flex: 1,
                    minWidth: 0,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                >
                  {e.file}
                </span>
              </div>
            </div>
          </div>
        );
      })}

      {/* 底部一句：把「写代码」收回到产品叙事 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: vertical ? 120 : 62,
          textAlign: 'center',
          fontFamily: F.sans,
          fontSize: 38,
          color: C.ink2,
          letterSpacing: '0.08em',
          opacity: interpolate(frame, [16, 28], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }),
        }}
      >
        同一台手机 · 同一个工作区 · 编译真的能过
      </div>
    </AbsoluteFill>
  );
};
