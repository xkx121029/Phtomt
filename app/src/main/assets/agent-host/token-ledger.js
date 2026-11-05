// Token 统计与成本追踪（能力 #26，ROADMAP v0.2 待办）。
//
// 每次 provider 回 finish 事件带 usage，agent 循环把它记到 run 上；
// TokenLedger 汇总成「按模型 / 按天 / 按 run」三类视图，并换算成本。
// 价格表是每百万 token 的美元价，未知模型走 default 档。

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync } from './util/atomic-write.js';

const PRICING_PER_MTOK = {
  'gpt-5-codex': { in: 1.25, out: 10.0, cachedIn: 0.125 },
  'gpt-5': { in: 1.25, out: 10.0 },
  'gpt-5-mini': { in: 0.25, out: 2.0 },
  'o4-mini': { in: 1.1, out: 4.4 },
  'claude-sonnet-4-5': { in: 3.0, out: 15.0, cachedIn: 0.3 },
  'claude-opus-4-1': { in: 15.0, out: 75.0 },
  'claude-haiku-4-5': { in: 1.0, out: 5.0 },
  'grok-4': { in: 3.0, out: 15.0 },
  'grok-code-fast-1': { in: 0.2, out: 1.5 },
  'kimi-k2-0905-preview': { in: 0.6, out: 2.5 },
  'deepseek-chat': { in: 0.27, out: 1.1 },
  'deepseek-reasoner': { in: 0.55, out: 2.19 },
  'glm-4.6': { in: 0.6, out: 2.2 },
  'qwen3-coder-plus': { in: 0.5, out: 1.5 },
  'qwen3-max': { in: 1.2, out: 6.0 },
  'doubao-seed-code': { in: 0.2, out: 1.0 },
  'MiniMax-M2': { in: 0.3, out: 1.2 },
  'gemini-2.5-pro': { in: 1.25, out: 10.0 },
  'llama-3.3-70b-versatile': { in: 0.59, out: 0.79 },
  'codestral-latest': { in: 0.3, out: 0.9 },
  default: { in: 1.0, out: 3.0 },
};

/** 取价格档：精确匹配 → 前缀匹配（如 claude-sonnet-4-5-20260101）→ default。 */
export function priceFor(model) {
  if (!model) return PRICING_PER_MTOK.default;
  if (PRICING_PER_MTOK[model]) return PRICING_PER_MTOK[model];
  const key = Object.keys(PRICING_PER_MTOK).find((k) => k !== 'default' && model.startsWith(k));
  return key ? PRICING_PER_MTOK[key] : PRICING_PER_MTOK.default;
}

/** 单次用量记录。 */
export function costOf({ model, input_tokens = 0, output_tokens = 0, cached_input_tokens = 0 }) {
  const p = priceFor(model);
  const freshIn = Math.max(0, input_tokens - cached_input_tokens);
  const usd =
    (freshIn / 1e6) * p.in +
    (cached_input_tokens / 1e6) * (p.cachedIn ?? p.in) +
    (output_tokens / 1e6) * p.out;
  return Math.round(usd * 1e6) / 1e6;
}

export class TokenLedger {
  #file;

  constructor({ persistPath } = {}) {
    this.records = [];
    this.#file = persistPath || null;
    if (this.#file) this.#load();
  }

  /** 记一次模型调用。model 从 provider 侧取，缺失时记 'unknown'。 */
  record({ model, runId, sessionId, task = 'chat', usage }) {
    if (!usage) return null;
    const input = usage.input_tokens ?? usage.prompt_tokens ?? 0;
    const output = usage.output_tokens ?? usage.completion_tokens ?? 0;
    // 三种方言的缓存 token：OpenAI（prompt_tokens_details.cached_tokens）、
    // OpenAI 旧字段（cached_input_tokens）、Anthropic（cache_read_input_tokens）。
    // 不认 Anthropic 字段的话，命中缓存的 token 会按全价计入，成本严重高估。
    const cached =
      usage.cached_input_tokens ??
      usage.prompt_tokens_details?.cached_tokens ??
      usage.cache_read_input_tokens ??
      0;
    const rec = {
      at: new Date().toISOString(),
      model: model || 'unknown',
      runId: runId || null,
      sessionId: sessionId || null,
      task,
      input_tokens: input,
      output_tokens: output,
      cached_input_tokens: cached,
      cost_usd: costOf({ model, input_tokens: input, output_tokens: output, cached_input_tokens: cached }),
    };
    this.records.push(rec);
    this.#save();
    return rec;
  }

  /** 按模型汇总。 */
  byModel() {
    const m = new Map();
    for (const r of this.records) {
      const cur = m.get(r.model) || { model: r.model, calls: 0, input_tokens: 0, output_tokens: 0, cost_usd: 0 };
      cur.calls += 1;
      cur.input_tokens += r.input_tokens;
      cur.output_tokens += r.output_tokens;
      cur.cost_usd += r.cost_usd;
      m.set(r.model, cur);
    }
    return [...m.values()].map((x) => ({ ...x, cost_usd: round6(x.cost_usd) }));
  }

  /** 按天汇总（本地时区）。 */
  byDay() {
    const m = new Map();
    for (const r of this.records) {
      const day = r.at.slice(0, 10);
      const cur = m.get(day) || { day, calls: 0, input_tokens: 0, output_tokens: 0, cost_usd: 0 };
      cur.calls += 1;
      cur.input_tokens += r.input_tokens;
      cur.output_tokens += r.output_tokens;
      cur.cost_usd += r.cost_usd;
      m.set(day, cur);
    }
    return [...m.values()].sort((a, b) => a.day.localeCompare(b.day)).map((x) => ({ ...x, cost_usd: round6(x.cost_usd) }));
  }

  byRun(runId) {
    const rows = this.records.filter((r) => r.runId === runId);
    return {
      runId,
      calls: rows.length,
      input_tokens: sum(rows, 'input_tokens'),
      output_tokens: sum(rows, 'output_tokens'),
      cost_usd: round6(sum(rows, 'cost_usd')),
    };
  }

  total() {
    return {
      calls: this.records.length,
      input_tokens: sum(this.records, 'input_tokens'),
      output_tokens: sum(this.records, 'output_tokens'),
      cost_usd: round6(sum(this.records, 'cost_usd')),
    };
  }

  #load() {
    try {
      this.records = JSON.parse(fs.readFileSync(this.#file, 'utf8')).records || [];
    } catch {
      this.records = [];
    }
  }

  #save() {
    if (!this.#file) return;
    try {
      atomicWriteFileSync(this.#file, JSON.stringify({ records: this.records.slice(-5000) }));
    } catch {
      /* best effort */
    }
  }
}

function sum(rows, key) {
  return rows.reduce((n, r) => n + (r[key] || 0), 0);
}
function round6(n) {
  return Math.round(n * 1e6) / 1e6;
}

/**
 * 用量工具（#26 的 agent 侧入口 + 设置页数据源）。
 * agent 可以自己查「这次会话花了多少钱」，用户也能在设置页看同一份数据。
 */
export function createUsageTools({ ledger, profileStore }) {
  const tools = [
    {
      name: 'usage_report',
      description:
        'Token usage and cost report. Scope: total, by-model or by-day. Costs are USD estimates from the built-in price table.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          scope: { type: 'string', description: 'total | by-model | by-day | by-run', default: 'total' },
          run_id: { type: 'string', description: 'Required for scope=by-run' },
        },
      },
      execute({ scope = 'total', run_id }) {
        if (scope === 'by-model') {
          const rows = ledger.byModel();
          return rows.length ? rows.map((r) => `${r.model}: ${r.calls} calls, ${r.input_tokens} in / ${r.output_tokens} out, $${r.cost_usd}`).join('\n') : '(no usage recorded)';
        }
        if (scope === 'by-day') {
          const rows = ledger.byDay();
          return rows.length ? rows.map((r) => `${r.day}: ${r.calls} calls, ${r.input_tokens} in / ${r.output_tokens} out, $${r.cost_usd}`).join('\n') : '(no usage recorded)';
        }
        if (scope === 'by-run') {
          if (!run_id) return 'scope=by-run requires run_id';
          const r = ledger.byRun(run_id);
          return `run ${run_id}: ${r.calls} calls, ${r.input_tokens} in / ${r.output_tokens} out, $${r.cost_usd}`;
        }
        const t = ledger.total();
        return `total: ${t.calls} calls, ${t.input_tokens} input tokens, ${t.output_tokens} output tokens, estimated cost $${t.cost_usd}`;
      },
    },
  ];

  if (profileStore) {
    tools.push({
      name: 'model_profiles',
      description:
        'List configured model profiles with their key pool sizes and task bindings (chat / summarize / title / embed / judge).',
      risk: 'read',
      parameters: { type: 'object', properties: {} },
      execute() {
        const list = profileStore.list();
        if (!list.length) return '(no model profiles configured)';
        const bindings = profileStore.bindings();
        const lines = list.map((p) => `${p.id}${p.enabled ? '' : ' (disabled)'} — ${p.label}\n    preset: ${p.presetId}, model: ${p.model || '(default)'}, keys: ${p.keyCount}`);
        const bound = Object.entries(bindings);
        if (bound.length) lines.push(`task bindings:\n${bound.map(([t, id]) => `  ${t} -> ${id}`).join('\n')}`);
        return lines.join('\n');
      },
    });
    tools.push({
      name: 'set_task_model',
      description:
        'Bind a model profile to a task kind so cheap models handle summarisation and strong models handle the main chat. Unbind with null.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          task: { type: 'string', description: 'chat | summarize | title | embed | judge' },
          profile_id: { type: 'string', description: 'Profile id, or empty string to unbind' },
        },
        required: ['task'],
      },
      execute({ task, profile_id }) {
        if (!profile_id) {
          profileStore.unbind(task);
          return `unbound task "${task}" (falls back to the first enabled profile)`;
        }
        profileStore.bind(task, profile_id);
        return `task "${task}" now uses profile "${profile_id}"`;
      },
    });
  }

  return tools;
}
