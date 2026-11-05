// 消息流式 revision tracker（能力 #42）：把 SSE 增量合并成稳定的全文。
//
// 背景：provider 吐的是 delta（"he" + "llo"），UI 如果每次全量 setState 会
// 抖动；而不同 provider 的分片边界也不同（有的按 token，有的按整句）。
// 做法：累积增量，同时维护一个「revision 版本号」，UI 只在 revision 变化时
// 重渲染；并保留最近 N 个 checkpoint 便于断线重连后做 diff 对齐。

export class TextStreamRevisionTracker {
  constructor({ checkpointEvery = 8, keepCheckpoints = 16 } = {}) {
    this.text = '';
    this.revision = 0;
    this.deltas = 0;
    this.checkpointEvery = checkpointEvery;
    this.keepCheckpoints = keepCheckpoints;
    this.checkpoints = [];
    this.finished = false;
  }

  /** 收到一个增量文本。返回新 revision。 */
  push(delta) {
    if (delta == null) return this.revision;
    this.text += String(delta);
    this.deltas += 1;
    this.revision += 1;
    if (this.deltas % this.checkpointEvery === 0) {
      this.checkpoints.push({ revision: this.revision, text: this.text });
      if (this.checkpoints.length > this.keepCheckpoints) this.checkpoints.shift();
    }
    return this.revision;
  }

  /** 流结束：落一个终态 checkpoint，返回全文。 */
  finish() {
    this.finished = true;
    this.checkpoints.push({ revision: this.revision, text: this.text });
    if (this.checkpoints.length > this.keepCheckpoints) this.checkpoints.shift();
    return this.text;
  }

  /**
   * 断线重连后对齐：把本地已渲染的 text 与 tracker 的终态做对比。
   * 返回 { action: 'append'|'replace'|'identical', patch }。
   */
  reconcile(localText) {
    if (localText === this.text) return { action: 'identical', patch: '' };
    if (this.text.startsWith(localText)) {
      return { action: 'append', patch: this.text.slice(localText.length) };
    }
    // 分片边界不同导致的不匹配：找最长公共前缀，尾部替换
    let i = 0;
    const n = Math.min(localText.length, this.text.length);
    while (i < n && localText[i] === this.text[i]) i += 1;
    return { action: 'replace', patch: this.text.slice(i), from: i };
  }
}

/**
 * 合并多个并发流（主回复 + 思考链 + 工具进度）到一条有序序列，
 * UI 侧按 seq 渲染即可，不需要自己排序。
 */
export class HotStreamMerger {
  constructor() {
    this.seq = 0;
    this.parts = [];
  }

  add(kind, delta) {
    this.seq += 1;
    this.parts.push({ seq: this.seq, kind, delta: String(delta ?? '') });
    return this.seq;
  }

  /** 渲染用：按 kind 聚合。 */
  render() {
    const byKind = new Map();
    for (const p of this.parts) byKind.set(p.kind, (byKind.get(p.kind) || '') + p.delta);
    return byKind;
  }
}
