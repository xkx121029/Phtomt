// 复制自 video-shotcraft assets/lib/helpers/motion.ts（组件库 copy-in 后自由修改）

/** 中心差分求速度：驱动方向性运动模糊 / 拉丝强度，纯函数无状态。 */
export const velocityAt = (
  posAt: (f: number) => { x: number; y: number },
  frame: number,
  dt = 0.5
): { vx: number; vy: number; speed: number; direction: number } => {
  const before = posAt(frame - dt);
  const after = posAt(frame + dt);
  const vx = (after.x - before.x) / (2 * dt);
  const vy = (after.y - before.y) / (2 * dt);
  return { vx, vy, speed: Math.hypot(vx, vy), direction: Math.atan2(vy, vx) };
};

/** 拖尾层 = 主状态在 frame − delay 处的采样，做 drag 层级不用模拟状态。 */
export const lagged = <T>(stateAt: (f: number) => T, frame: number, delayFrames: number): T =>
  stateAt(frame - delayFrames);

/** 闭式阻尼振荡：落地回弹尾巴，t 为距冲击的帧数。 */
export const dampedSettle = (t: number, freq: number, damping: number): number =>
  t <= 0 ? 0 : Math.exp(-damping * t) * Math.sin(2 * Math.PI * freq * t);
