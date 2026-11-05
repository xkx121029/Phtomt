// Sessions: conversation state + per-run change records (for undo/checkpoints).
// Optional JSONL persistence so a restarted host (embedded or desktop) resumes.

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync } from './util/atomic-write.js';

let seq = 0;

export class Session {
  constructor({ id, systemPrompt, messages, createdAt, runs, summaries } = {}) {
    this.id = id || `s_${(++seq).toString(36)}_${Date.now().toString(36)}`;
    this.messages = Array.isArray(messages) && messages.length
      ? messages
      : systemPrompt
        ? [{ role: 'system', content: systemPrompt }]
        : [];
    // runs 带 before 快照，持久化后 undo 才能跨进程重启；只留最近若干条避免文件无限膨胀。
    this.runs = Array.isArray(runs) ? runs : [];
    // 上下文窗口产生的总结块：不持久化的话重启后长会话的早期历史就真没了。
    this.summaries = Array.isArray(summaries) ? summaries : [];
    this.createdAt = createdAt || new Date().toISOString();
  }
}

/** 持久化时每个会话保留的 run 条数（每条含文件快照，太多会让 JSONL 失控）。 */
const MAX_PERSISTED_RUNS = 20;

/** 持久化时保留的总结块条数（与 context-window 的合并策略对齐）。 */
const MAX_PERSISTED_SUMMARIES = 8;

export class SessionStore {
  #sessions = new Map();
  #persistPath;

  constructor({ persistPath } = {}) {
    this.#persistPath = persistPath || null;
    if (this.#persistPath) this.#load();
  }

  create(opts = {}) {
    const s = new Session(opts);
    this.#sessions.set(s.id, s);
    this.#save();
    return s;
  }

  get(id) {
    return this.#sessions.get(id) ?? null;
  }

  list() {
    return [...this.#sessions.values()].map((s) => ({
      id: s.id,
      createdAt: s.createdAt,
      messages: s.messages.length,
      runs: s.runs.length,
    }));
  }

  delete(id) {
    const ok = this.#sessions.delete(id);
    this.#save();
    return ok;
  }

  /** Called after a prompt completes: persists message history. */
  touch(session) {
    if (this.#sessions.has(session.id)) this.#save();
  }

  #load() {
    try {
      const raw = fs.readFileSync(this.#persistPath, 'utf8');
      for (const line of raw.split('\n')) {
        if (!line.trim()) continue;
        const rec = JSON.parse(line);
        const s = new Session(rec);
        this.#sessions.set(s.id, s);
      }
    } catch {
      /* no file or corrupt line: start fresh */
    }
  }

  #save() {
    if (!this.#persistPath) return;
    try {
      const lines = [...this.#sessions.values()]
        .map((s) =>
          JSON.stringify({
            id: s.id,
            createdAt: s.createdAt,
            messages: s.messages,
            runs: s.runs.slice(-MAX_PERSISTED_RUNS),
            summaries: (s.summaries || []).slice(-MAX_PERSISTED_SUMMARIES),
          }),
        )
        .join('\n');
      // 原子写：半截 JSONL 会让下次 load 整个回到 fresh，全部会话静默清空。
      atomicWriteFileSync(this.#persistPath, lines + '\n');
    } catch {
      /* persistence is best-effort; never break the agent for it */
    }
  }
}
