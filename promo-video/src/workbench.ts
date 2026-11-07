import React from 'react';
import { Film } from './film/Film';
import { DURATION, FPS, SHOTS, shotDur } from './film/beat';
import { BGM, SFX } from './film/audio';
import { C } from './film/tokens';
import { S1Sentence } from './film/shots/S1Sentence';
import { S2Rise } from './film/shots/S2Rise';
import { S3Plan } from './film/shots/S3Plan';
import { S4Scan } from './film/shots/S4Scan';
import { S5Exec } from './film/shots/S5Exec';
import { S6Done } from './film/shots/S6Done';
import { S7Wall } from './film/shots/S7Wall';
import { S8Outro } from './film/shots/S8Outro';

/** Film 需要 bgm prop，工作台按无 props 组件调用 */
const FilmWithBgm: React.FC = () => React.createElement(Film, { bgm: true });

/**
 * 动效工作台清单（`node workbench/scripts/open.mjs <本工程>`）。
 * 时间表全部从 beat.ts / audio.ts 派生，不手抄第二份：
 * 改了 SHOTS 或 SFX，工作台重新导入即得到新的一版。
 */
export const WORKBENCH = {
  name: 'Happy Agent · 一句话，手机自己做完',
  fps: FPS,
  width: 1920,
  height: 1080,
  total: DURATION,
  background: C.bg,
  shots: [
    { id: 's1-sentence', label: 'S1 一句话', from: SHOTS.s1_sentence.from, duration: shotDur('s1_sentence'), component: S1Sentence },
    { id: 's2-rise', label: 'S2 设备升起', from: SHOTS.s2_rise.from, duration: shotDur('s2_rise'), component: S2Rise },
    { id: 's3-plan', label: 'S3 拆计划', from: SHOTS.s3_plan.from, duration: shotDur('s3_plan'), component: S3Plan },
    { id: 's4-scan', label: 'S4 框选控件', from: SHOTS.s4_scan.from, duration: shotDur('s4_scan'), component: S4Scan },
    { id: 's5-exec', label: 'S5 执行', from: SHOTS.s5_exec.from, duration: shotDur('s5_exec'), component: S5Exec },
    { id: 's6-done', label: 'S6 3/3 生效', from: SHOTS.s6_done.from, duration: shotDur('s6_done'), component: S6Done },
    { id: 's7-wall', label: 'S7 四宫格', from: SHOTS.s7_wall.from, duration: shotDur('s7_wall'), component: S7Wall },
    { id: 's8-outro', label: 'S8 品牌收场', from: SHOTS.s8_outro.from, duration: shotDur('s8_outro'), component: S8Outro },
  ],
  sfx: SFX.map((s) => ({
    from: s.from,
    duration: s.durationInFrames ?? 30,
    src: `audio/sfx/${s.src}`,
    volume: s.volume,
  })),
  bgm: [{ from: 0, duration: DURATION, src: BGM.file, volume: BGM.volume }],
  original: FilmWithBgm,
};

export type WorkbenchManifest = typeof WORKBENCH;
