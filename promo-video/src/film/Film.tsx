import React from 'react';
import { AbsoluteFill, Sequence } from 'remotion';
import { C } from './tokens';
import { SHOTS, shotDur, beatF } from './beat';
import { Stage } from './chrome/Stage';
import { S1Sentence } from './shots/S1Sentence';
import { S2Rise } from './shots/S2Rise';
import { S3Plan } from './shots/S3Plan';
import { S4Scan } from './shots/S4Scan';
import { S5Exec } from './shots/S5Exec';
import { S6Done } from './shots/S6Done';
import { S7Wall } from './shots/S7Wall';
import { S7Coding } from './shots/S7Coding';
import { S8Outro } from './shots/S8Outro';
import { BgmTrack, SfxTrack } from './Sound';

/** S7 的 8 拍拆成「四宫格 4 拍 + 几何场景里的编码示例 4 拍」，总时长不变 */
const S7B_FROM = beatF(80);

/** 全片装配。镜头边界全部来自 SHOTS（拍号派生），不写裸帧号。 */
export const Film: React.FC<{ bgm: boolean }> = ({ bgm }) => {
  return (
    <AbsoluteFill style={{ backgroundColor: C.bg }}>
      <Sequence from={SHOTS.s1_sentence.from} durationInFrames={shotDur('s1_sentence')} name="S1 一句话">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.075} topLight={0.03} scrim={0.18} geometry={0.78} />
          <S1Sentence />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s2_rise.from} durationInFrames={shotDur('s2_rise')} name="S2 升起">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.1} topLight={0.03} scrim={0.62} geometry={0.46} />
          <S2Rise />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s3_plan.from} durationInFrames={shotDur('s3_plan')} name="S3 规划">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.12} topLight={0.02} scrim={0.58} geometry={0.32} />
          <S3Plan />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s4_scan.from} durationInFrames={shotDur('s4_scan')} name="S4 框选">
        <AbsoluteFill>
          <Stage glow={C.mist} glowStrength={0.1} topLight={0.04} scrim={0.6} geometry={0.3} />
          <S4Scan />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s5_exec.from} durationInFrames={shotDur('s5_exec')} name="S5 执行">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.13} topLight={0.02} scrim={0.62} geometry={0.3} />
          <S5Exec />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHOTS.s6_done.from} durationInFrames={shotDur('s6_done')} name="S6 完成">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.12} topLight={0.02} scrim={0.56} geometry={0.34} />
          <S6Done />
        </AbsoluteFill>
      </Sequence>

      <Sequence
        from={SHOTS.s7_wall.from}
        durationInFrames={S7B_FROM - SHOTS.s7_wall.from}
        name="S7a 四宫格"
      >
        <S7Wall />
      </Sequence>

      <Sequence
        from={S7B_FROM}
        durationInFrames={SHOTS.s7_wall.to - S7B_FROM}
        name="S7b 几何场景 · 编码示例"
      >
        <S7Coding />
      </Sequence>

      <Sequence from={SHOTS.s8_outro.from} durationInFrames={shotDur('s8_outro')} name="S8 收尾">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.06} topLight={0.015} scrim={0.34} geometry={0.6} />
          <S8Outro />
        </AbsoluteFill>
      </Sequence>

      {/* 声音：BGM 受 bgm prop 控制，SFX 始终保留 */}
      {bgm ? <BgmTrack /> : null}
      <SfxTrack />
    </AbsoluteFill>
  );
};
