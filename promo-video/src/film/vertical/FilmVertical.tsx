import React from 'react';
import { AbsoluteFill, Sequence } from 'remotion';
import { C, setTheme, themeCSS, type Theme } from '../tokens';
import { SHOTS, shotDur, beatF } from '../beat';
import { Stage } from '../chrome/Stage';
import { S1Sentence } from '../shots/S1Sentence';
import { S7Wall } from '../shots/S7Wall';
import { S7Coding } from '../shots/S7Coding';
import { BgmTrack, SfxTrack } from '../Sound';
import { V2Rise } from './V2Rise';
import { V3Plan } from './V3Plan';
import { V4Scan } from './V4Scan';
import { V5Exec } from './V5Exec';
import { V6Done } from './V6Done';
import { V8Outro } from './V8Outro';

/** 竖版舞台参数：辉光更高更窄，底部压暗更深（字幕区在设备下缘上方） */
const V_STAGE = { glowW: 1300, glowH: 1520, scrimH: 560 } as const;

/** 胶囊随屏宽放大，环境辉光与落地光池按同一比例跟随（横版默认 1180 / 880） */
const V1_PILL = { pillTop: 958, pillW: 900, glowW: 999, poolW: 747 } as const;
/** 竖版四宫格单格 540×960，格内辉光比横版的 1200×900 更窄更高 */
const V7_GLOW = { w: 630, h: 1543.7 } as const;
/** 与横版共用：S7 后 4 拍让给几何场景里的编码示例 */
const S7B_FROM = beatF(80);

/**
 * 竖版 9:16 长版装配。
 * 拍号、镜头边界、SFX 钉帧全部沿用横版（beat.ts / audio.ts）——
 * 竖版只换构图，不换节奏，也不换声音。
 */
export const FilmVertical: React.FC<{ bgm: boolean; theme?: Theme }> = ({ bgm, theme = 'dark' }) => {
  setTheme(theme);
  return (
    <AbsoluteFill style={{ backgroundColor: C.bg, ...themeCSS(theme) }}>
      <Sequence from={SHOTS.s1_sentence.from} durationInFrames={shotDur('s1_sentence')} name="V1 一句话">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.075} topLight={0.03} scrim={0.16} geometry={0.8} {...V_STAGE} />
          <S1Sentence {...V1_PILL} />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s2_rise.from} durationInFrames={shotDur('s2_rise')} name="V2 升起">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.1} topLight={0.03} scrim={0.5} geometry={0.44} {...V_STAGE} />
          <V2Rise />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s3_plan.from} durationInFrames={shotDur('s3_plan')} name="V3 规划">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.12} topLight={0.02} scrim={0.46} geometry={0.3} {...V_STAGE} />
          <V3Plan />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s4_scan.from} durationInFrames={shotDur('s4_scan')} name="V4 框选">
        <AbsoluteFill>
          <Stage glow={C.mist} glowStrength={0.1} topLight={0.04} scrim={0.46} geometry={0.3} {...V_STAGE} />
          <V4Scan />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s5_exec.from} durationInFrames={shotDur('s5_exec')} name="V5 执行">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.13} topLight={0.02} scrim={0.5} geometry={0.3} {...V_STAGE} />
          <V5Exec />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s6_done.from} durationInFrames={shotDur('s6_done')} name="V6 完成">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.12} topLight={0.02} scrim={0.44} geometry={0.34} {...V_STAGE} />
          <V6Done />
        </AbsoluteFill>
      </Sequence>

      <Sequence
        from={SHOTS.s7_wall.from}
        durationInFrames={S7B_FROM - SHOTS.s7_wall.from}
        name="V7a 四宫格"
      >
        <S7Wall qw={540} qh={960} glowW={V7_GLOW.w} glowH={V7_GLOW.h} />
      </Sequence>

      <Sequence
        from={S7B_FROM}
        durationInFrames={SHOTS.s7_wall.to - S7B_FROM}
        name="V7b 几何场景 · 编码示例"
      >
        <S7Coding vertical />
      </Sequence>

      <Sequence from={SHOTS.s8_outro.from} durationInFrames={shotDur('s8_outro')} name="V8 收尾">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.06} topLight={0.015} scrim={0.3} geometry={0.58} {...V_STAGE} />
          <V8Outro />
        </AbsoluteFill>
      </Sequence>

      {bgm ? <BgmTrack /> : null}
      <SfxTrack />
    </AbsoluteFill>
  );
};
