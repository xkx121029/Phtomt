import React from 'react';
import { AbsoluteFill, Audio, Sequence, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { C } from './tokens';
import { Stage } from './chrome/Stage';
import { S1Sentence } from './shots/S1Sentence';
import { S4Scan } from './shots/S4Scan';
import { S5Exec } from './shots/S5Exec';
import { S8Outro } from './shots/S8Outro';
import { SHORT, SHORT_BGM, SHORT_SFX } from './audio-short';

const sfxDur = (d?: number) => d ?? 30;

const ShortBgm: React.FC = () => {
  const frame = useCurrentFrame();
  const volume = interpolate(
    frame,
    [0, SHORT_BGM.fadeIn, SHORT.total - SHORT_BGM.fadeOut, SHORT.total],
    [0, SHORT_BGM.volume, SHORT_BGM.volume, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );
  return <Audio src={staticFile(SHORT_BGM.file)} volume={volume} />;
};

/**
 * 15s 横版短版：钩子 → 它看清屏幕 → 它自己动手 → 品牌。
 * 四个镜头复用长版组件，只压内部节奏（打字加快、落标提前），时间表来自 audio-short.ts。
 */
export const FilmShort: React.FC<{ bgm: boolean }> = ({ bgm }) => {
  return (
    <AbsoluteFill style={{ backgroundColor: C.bg }}>
      <Sequence from={SHORT.t1} durationInFrames={SHORT.t2 - SHORT.t1} name="T1 一句话">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.075} topLight={0.03} scrim={0.18} geometry={0.78} />
          <S1Sentence typeStartBeat={0.6} charFrames={1.6} />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHORT.t2} durationInFrames={SHORT.t3 - SHORT.t2} name="T2 框选控件">
        <AbsoluteFill>
          <Stage glow={C.mist} glowStrength={0.1} topLight={0.04} scrim={0.6} geometry={0.34} />
          <S4Scan />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHORT.t3} durationInFrames={SHORT.t4 - SHORT.t3} name="T3 执行">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.13} topLight={0.02} scrim={0.62} geometry={0.32} />
          <S5Exec />
        </AbsoluteFill>
      </Sequence>

      <Sequence from={SHORT.t4} durationInFrames={SHORT.total - SHORT.t4} name="T4 品牌">
        <AbsoluteFill>
          <Stage glow={C.jade} glowStrength={0.06} topLight={0.015} scrim={0.34} geometry={0.6} />
          <S8Outro slamBeat={4} markBeat={9} />
        </AbsoluteFill>
      </Sequence>

      {bgm ? <ShortBgm /> : null}
      {SHORT_SFX.map((c, i) => (
        <Sequence key={i} from={c.from} durationInFrames={sfxDur(c.durationInFrames)} name={`SFX ${c.note}`}>
          <Audio src={staticFile(`audio/sfx/${c.src}`)} volume={c.volume} />
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};
