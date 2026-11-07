import React from 'react';
import { Audio, Sequence, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { DURATION } from './beat';
import { BGM, SFX } from './audio';

/** SFX 轨：全部从 audio.ts 的中央表派生，画面组件里不放音频 */
export const SfxTrack: React.FC = () => {
  return (
    <>
      {SFX.map((c, i) => (
        <Sequence
          key={i}
          from={c.from}
          durationInFrames={c.durationInFrames ?? 30}
          name={`SFX ${c.note}`}
        >
          <Audio src={staticFile(`audio/sfx/${c.src}`)} volume={c.volume} />
        </Sequence>
      ))}
    </>
  );
};

/** BGM 轨：单独受 bgm prop 控制，交付带/不带 BGM 两版 */
export const BgmTrack: React.FC = () => {
  const frame = useCurrentFrame();
  const volume = interpolate(
    frame,
    [0, BGM.fadeIn, DURATION - BGM.fadeOut, DURATION],
    [0, BGM.volume, BGM.volume, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );
  return <Audio src={staticFile(BGM.file)} volume={volume} />;
};
