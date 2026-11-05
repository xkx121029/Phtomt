// 可观测性基础设施：事件环缓冲 + 活动日志 + 运行态快照。
//
// 无人值守时人不在屏幕前，「发生了什么」必须可事后追查：
//   - EventLog 保留最近 N 条结构化事件（内存环 + 可选 JSONL 落盘）
//   - text/thinking 这类高频增量不进日志，避免把磁盘写满、把日志淹没
//   - RunTracker 从事件流派生「当前有哪些 run 在跑、跑到第几轮、卡在哪个工具」
//
// 不依赖任何外部库；落盘失败只降级为「仅内存」。

import fs from 'node:fs';
import path from 'node:path';

/** 高频/纯增量事件：不落盘（内存里仍保留，供 UI 断线重连拉取）。 */
const SKIP_PERSIST = new Set(['text', 'thinking', 'subagent_text', 'heartbeat']);

export class EventLog {
  constructor({ persistPath = null, capacity = 500, maxBytes = 4 * 1024 * 1024 } = {}) {
    this.capacity = Math.max(5, Math.floor(capacity) || 500);
    this.maxBytes = maxBytes;
    this.persistPath = persistPath ? path.resolve(persistPath) : null;
    this.buffer = [];
    this.seq = 0;
    this.counts = Object.create(null);
    this.dropped = 0;
    this.error = null;
    if (this.persistPath) {
      try {
        fs.mkdirSync(path.dirname(this.persistPath), { recursive: true });
        this.#rotateIfNeeded();
      } catch (err) {
        this.error = err.message;
        this.persistPath = null;
      }
    }
  }

  /** 追加一条事件，返回带 seq/ts 的存储行。 */
  push(event) {
    const row = { seq: ++this.seq, ts: event?.ts ?? Date.now(), ...event };
    this.counts[row.type || 'unknown'] = (this.counts[row.type || 'unknown'] || 0) + 1;
    this.buffer.push(row);
    if (this.buffer.length > this.capacity) {
      const excess = this.buffer.length - this.capacity;
      this.buffer.splice(0, excess);
      this.dropped += excess;
    }
    if (this.persistPath && !SKIP_PERSIST.has(row.type)) this.#append(row);
    return row;
  }

  /** 取最近的事件；since 是 seq（只取更大的），type 可精确过滤。 */
  recent({ limit = 100, since = 0, type = null } = {}) {
    const rows = this.buffer.filter((r) => r.seq > since && (!type || r.type === type));
    return rows.slice(-Math.max(1, limit));
  }

  stats() {
    return {
      seq: this.seq,
      buffered: this.buffer.length,
      dropped: this.dropped,
      counts: { ...this.counts },
      persistPath: this.persistPath,
      error: this.error,
    };
  }

  /**
   * 按状态目录轮转：超过上限就挪成 .1（至多保留一份）。
   * 无人值守可能跑几天，不能让日志无限长大。
   */
  #rotateIfNeeded() {
    if (!this.persistPath) return;
    try {
      const stat = fs.statSync(this.persistPath);
      if (stat.size > this.maxBytes) {
        const old = `${this.persistPath}.1`;
        fs.rmSync(old, { force: true });
        fs.renameSync(this.persistPath, old);
      }
    } catch {
      /* 文件不存在是正常情况 */
    }
  }

  #append(row) {
    try {
      fs.appendFileSync(this.persistPath, `${JSON.stringify(row)}\n`, 'utf8');
    } catch (err) {
      // 一次失败就关掉落盘，避免每条事件都重复报错拖慢主循环。
      this.error = err.message;
      this.persistPath = null;
    }
  }
}

/**
 * 从事件流维护「活跃 run」视图。Router 每收到一条事件就喂一次，
 * 这样 agent_status 不必扫描日志，也不会因为漏事件而漂移太多。
 */
export class RunTracker {
  constructor({ staleMs = 5 * 60_000 } = {}) {
    this.runs = new Map(); // runId -> view
    this.staleMs = staleMs;
    this.startedAt = Date.now();
  }

  apply(event) {
    const runId = event?.runId;
    if (!runId) return;
    const now = event.ts ?? Date.now();
    let view = this.runs.get(runId);
    if (!view) {
      view = {
        runId,
        sessionId: event.sessionId ?? null,
        subagentId: event.subagentId ?? null,
        parentRunId: event.parentRunId ?? null,
        startedAt: now,
        iteration: 0,
        phase: 'starting',
        currentTool: null,
        activeTools: 0,
        toolCalls: 0,
        failedTools: 0,
        lastEventAt: now,
      };
      this.runs.set(runId, view);
    }
    view.lastEventAt = now;
    if (event.sessionId) view.sessionId = event.sessionId;

    switch (event.type) {
      case 'run_start':
        view.startedAt = now;
        view.phase = 'running';
        break;
      case 'iteration':
        view.iteration = event.index ?? view.iteration;
        view.phase = 'model';
        break;
      case 'provider_request':
        view.phase = 'waiting_model';
        break;
      case 'provider_first_token':
        view.phase = 'streaming';
        break;
      case 'tool_start':
        view.phase = 'tools';
        view.currentTool = event.name ?? null;
        view.activeTools += 1;
        view.toolCalls += 1;
        break;
      case 'tool_end':
        view.activeTools = Math.max(0, view.activeTools - 1);
        if (event.ok === false) view.failedTools += 1;
        if (!view.activeTools) view.currentTool = null;
        break;
      case 'done':
      case 'cancelled':
        this.runs.delete(runId);
        break;
      default:
        break;
    }
    this.#prune(now);
  }

  snapshot() {
    this.#prune(Date.now());
    return [...this.runs.values()].map((v) => ({
      ...v,
      elapsedMs: Date.now() - v.startedAt,
    }));
  }

  #prune(now) {
    for (const [id, view] of this.runs) {
      if (now - view.lastEventAt > this.staleMs) this.runs.delete(id);
    }
  }
}
