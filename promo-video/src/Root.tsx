import React from 'react';
import { Composition } from 'remotion';
import { Film } from './film/Film';
import { FilmShort } from './film/Short';
import { FilmVertical } from './film/vertical/FilmVertical';
import { DURATION, FPS } from './film/beat';
import { SHORT } from './film/audio-short';
import { VERTICAL } from './film/vertical/vertical-beat';

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="HappyAgentPromo"
        component={Film}
        durationInFrames={DURATION}
        fps={FPS}
        width={1920}
        height={1080}
        defaultProps={{ bgm: true, theme: 'dark' }}
      />
      <Composition
        id="HappyAgentShort"
        component={FilmShort}
        durationInFrames={SHORT.total}
        fps={FPS}
        width={1920}
        height={1080}
        defaultProps={{ bgm: true, theme: 'dark' }}
      />
      <Composition
        id="HappyAgentVertical"
        component={FilmVertical}
        durationInFrames={VERTICAL.total}
        fps={FPS}
        width={1080}
        height={1920}
        defaultProps={{ bgm: true, theme: 'dark' }}
      />
    </>
  );
};
