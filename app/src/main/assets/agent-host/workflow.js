// 工作流引擎（能力 #24，参考 Operit Workflow 的形态，独立实现）。
//
// 一个 workflow 是一份 JSON：一串步骤，每步要么调工具、要么跑子工作流、
// 要么做简单的条件分支。用途是把「agent 每次都要重新想一遍」的固定流程
// （例如：截图 -> 读图 -> 总结）固化成可复用的一键执行。
//
// 设计原则：
//   1. 数据驱动：workflow 是纯 JSON，可存文件、可分享、可被 agent 读写
//   2. 复用 agent 的既有机制：审批走 ApprovalPolicy、执行走 ToolRegistry，
//      所以工作流里的工具调用和聊天里的工具调用安全等级完全一致
//   3. 变量传递：${{ steps.<id>.output }} 引用前序步骤输出，简单字符串模板
//   4. 落盘：定义写进 workflows.json，重启后仍在（agent 固化下来的流程不该丢）

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync } from './util/atomic-write.js';

/**
 * @typedef {Object} WorkflowStep
 * @property {string} id
 * @property {string} [tool]          要调的工具名
 * @property {Object} [args]          工具参数，值可以是 "${{ ... }}" 模板
 * @property {string} [workflow]      子工作流 id（嵌套复用）
 * @property {Object} [with]          子工作流输入
 * @property {string} [when]          条件表达式（简单 JS 子集，见 evalCondition）
 * @property {string} [set]           把结果存进 vars（默认就是 step id）
 */

const TEMPLATE_RE = /\$\{\{\s*([^}]+?)\s*\}\}/g;

/** 渲染模板串：${{ steps.foo.output }} / ${{ input.name }} / ${{ vars.x }} */
export function renderTemplate(value, scope) {
  if (typeof value === 'string') {
    return value.replace(TEMPLATE_RE, (_, expr) => {
      const v = resolveExpr(expr.trim(), scope);
      return v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
    });
  }
  if (Array.isArray(value)) return value.map((v) => renderTemplate(v, scope));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = renderTemplate(v, scope);
    return out;
  }
  return value;
}

/** 只在 scope 上按点号取值，不做任意求值。 */
function resolveExpr(expr, scope) {
  const parts = expr.split('.').filter(Boolean);
  let cur = scope;
  for (const p of parts) {
    if (cur == null) return undefined;
    cur = cur[p];
  }
  return cur;
}

/**
 * 条件求值：只支持 `a == b` / `a != b` / `a` / `!a` / `a contains b`。
 * 不用 eval()——workflow 文件可能来自第三方，求值器必须无可执行面。
 */
export function evalCondition(expr, scope) {
  const s = String(expr || '').trim();
  if (!s) return true;
  const ne = /^(.+?)\s*(!==|!=)\s*(.+)$/.exec(s);
  if (ne) return String(valOf(ne[1], scope)) !== String(valOf(ne[3], scope));
  const eq = /^(.+?)\s*(===|==)\s*(.+)$/.exec(s);
  if (eq) return String(valOf(eq[1], scope)) === String(valOf(eq[3], scope));
  const has = /^(.+?)\s+contains\s+(.+)$/.exec(s);
  if (has) return String(valOf(has[1], scope)).includes(String(valOf(has[3], scope)));
  if (s.startsWith('!')) return !valOf(s.slice(1), scope);
  return Boolean(valOf(s, scope));
}

function valOf(token, scope) {
  const t = token.trim();
  if (/^".*"$/.test(t) || /^'.*'$/.test(t)) return t.slice(1, -1);
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
  if (t === 'true') return true;
  if (t === 'false') return false;
  return resolveExpr(t, scope);
}

export class WorkflowError extends Error {
  constructor(message, { stepId, cause } = {}) {
    super(message);
    this.name = 'WorkflowError';
    this.stepId = stepId;
    this.cause = cause;
  }
}

/**
 * 工作流引擎。
 *
 *   const engine = new WorkflowEngine({ registry, approval, onEvent });
 *   engine.define({ id: 'shot', steps: [...] });
 *   const result = await engine.run('shot', { input: {...} });
 */
export class WorkflowEngine {
  constructor({ registry, approval = null, onEvent, maxDepth = 4, persistPath = null } = {}) {
    if (!registry) throw new Error('WorkflowEngine needs a ToolRegistry');
    this.registry = registry;
    this.approval = approval;
    this.onEvent = onEvent || (() => {});
    this.maxDepth = maxDepth;
    this.workflows = new Map();
    this.runs = [];
    this._file = persistPath || null;
    if (this._file) this.#load();
  }

  #load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this._file, 'utf8'));
      for (const wf of raw.workflows || []) this.workflows.set(String(wf.id), wf);
    } catch {
      /* 无文件或损坏：空表起步 */
    }
  }

  #save() {
    if (!this._file) return;
    try {
      atomicWriteFileSync(this._file, JSON.stringify({ workflows: [...this.workflows.values()] }, null, 2));
    } catch {
      /* 落盘尽力而为 */
    }
  }

  define(workflow) {
    if (!workflow?.id) throw new Error('workflow needs an id');
    if (!Array.isArray(workflow.steps) || !workflow.steps.length) throw new Error(`workflow ${workflow.id} has no steps`);
    for (const s of workflow.steps) {
      if (!s?.id) throw new Error(`workflow ${workflow.id}: every step needs an id`);
      if (!s.tool && !s.workflow) throw new Error(`workflow ${workflow.id} step ${s.id}: needs tool or workflow`);
    }
    this.workflows.set(String(workflow.id), workflow);
    this.#save();
    return workflow;
  }

  /** 从 JSON 字符串定义（workflow 文件由 agent 写、由用户分享）。 */
  defineFromJSON(json) {
    return this.define(JSON.parse(json));
  }

  list() {
    return [...this.workflows.values()].map((w) => ({
      id: w.id,
      name: w.name || w.id,
      description: w.description || '',
      steps: w.steps.length,
    }));
  }

  get(id) {
    return this.workflows.get(id) ?? null;
  }

  remove(id) {
    const ok = this.workflows.delete(id);
    if (ok) this.#save();
    return ok;
  }

  async run(workflowId, { input = {}, vars = {}, depth = 0, signal, ctx = {} } = {}) {
    const wf = this.workflows.get(workflowId);
    if (!wf) throw new WorkflowError(`unknown workflow: ${workflowId}`);
    if (depth > this.maxDepth) throw new WorkflowError(`workflow nesting too deep at ${workflowId}`);

    const runId = `wf_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
    const run = { id: runId, workflowId, steps: [], startedAt: new Date().toISOString() };
    this.runs.push(run);
    if (this.runs.length > 100) this.runs.shift();

    const scope = { input, vars: { ...vars }, steps: {} };
    this.onEvent({ type: 'workflow_start', runId, workflowId });

    for (const step of wf.steps) {
      if (signal?.aborted) {
        this.onEvent({ type: 'workflow_cancelled', runId, workflowId, stepId: step.id });
        return { ok: false, runId, cancelled: true, steps: run.steps };
      }
      if (step.when && !evalCondition(step.when, scope)) {
        run.steps.push({ id: step.id, skipped: true, reason: `when: ${step.when}` });
        continue;
      }

      const rec = { id: step.id, startedAt: new Date().toISOString() };
      this.onEvent({ type: 'workflow_step_start', runId, workflowId, stepId: step.id });

      try {
        if (step.workflow) {
          const sub = await this.run(step.workflow, {
            input: renderTemplate(step.with || {}, scope),
            vars: scope.vars,
            depth: depth + 1,
            signal,
            ctx,
          });
          rec.output = sub;
          rec.ok = sub.ok;
          // 子工作流失败与工具失败同语义：onError=continue 才放行，否则中断。
          if (!sub.ok && step.onError !== 'continue') {
            run.steps.push(rec);
            this.onEvent({ type: 'workflow_error', runId, workflowId, stepId: step.id, error: sub.error || 'sub-workflow failed' });
            return { ok: false, runId, error: `step ${step.id} failed (sub-workflow)`, steps: run.steps };
          }
        } else {
          const tool = this.registry.get(step.tool);
          if (!tool) throw new WorkflowError(`unknown tool: ${step.tool}`, { stepId: step.id });
          const args = renderTemplate(step.args || {}, scope);

          // 审批与聊天完全同路：工作流不绕过人工确认
          let approved = true;
          if (this.approval) {
            approved = await this.approval.check(tool, args, ctx);
          }
          if (!approved) {
            rec.output = 'DENIED';
            rec.ok = false;
            run.steps.push(rec);
            if (step.onError !== 'continue') {
              return { ok: false, runId, error: `denied at step ${step.id}`, steps: run.steps };
            }
            continue;
          }

          const result = await this.registry.execute(step.tool, args, ctx);
          rec.output = result.output;
          rec.ok = result.ok;
          if (!result.ok && step.onError !== 'continue') {
            run.steps.push(rec);
            this.onEvent({ type: 'workflow_error', runId, workflowId, stepId: step.id, error: result.output });
            return { ok: false, runId, error: `step ${step.id} failed`, steps: run.steps };
          }
        }
      } catch (err) {
        rec.ok = false;
        rec.error = err.message;
        run.steps.push(rec);
        this.onEvent({ type: 'workflow_error', runId, workflowId, stepId: step.id, error: err.message });
        // 抛异常（非法参数、工具崩溃）与 ok:false 同语义：onError=continue 时
        // 记一条失败继续跑，否则才中断——以前抛异常直接整体停，continue 形同虚设。
        if (step.onError !== 'continue') {
          return { ok: false, runId, error: err.message, stepId: step.id, steps: run.steps };
        }
        // continue：这里必须显式跳到下一步，不能掉到通用的 run.steps.push——
        // 那样同一条 rec 会被推进去两次。
        this.onEvent({ type: 'workflow_step_end', runId, workflowId, stepId: step.id, ok: false });
        continue;
      }

      const storeKey = step.set || step.id;
      scope.steps[storeKey] = rec.output;
      run.steps.push(rec);
      this.onEvent({ type: 'workflow_step_end', runId, workflowId, stepId: step.id, ok: rec.ok });
    }

    this.onEvent({ type: 'workflow_done', runId, workflowId });
    return { ok: true, runId, steps: run.steps, output: scope.steps };
  }
}

/** 工作流管理工具：让 agent 能定义/列出/运行工作流。 */
export function createWorkflowTools({ engine }) {
  return [
    {
      name: 'workflow_list',
      description: 'List defined workflows. A workflow is a saved sequence of tool calls that can be re-run in one shot.',
      risk: 'read',
      parameters: { type: 'object', properties: {} },
      execute() {
        const list = engine.list();
        return list.length ? list.map((w) => `${w.id} — ${w.name}: ${w.description} (${w.steps} steps)`).join('\n') : '(no workflows defined)';
      },
    },
    {
      name: 'workflow_define',
      description:
        'Define a workflow from JSON: { id, name?, description?, steps: [{ id, tool, args, when?, onError? } | { id, workflow, with }] }. ' +
        'Use "${{ steps.<id>.output }}" and "${{ input.x }}" templates to pass data between steps.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: { workflow: { type: 'object', description: 'Workflow definition object' } },
        required: ['workflow'],
      },
      execute({ workflow }) {
        const w = engine.define(workflow);
        return `defined workflow "${w.id}" with ${w.steps.length} steps`;
      },
    },
    {
      name: 'workflow_run',
      description: 'Run a defined workflow. Steps execute in order; tool calls go through the same approval policy as chat.',
      risk: 'exec',
      parameters: {
        type: 'object',
        properties: {
          workflow_id: { type: 'string' },
          input: { type: 'object', description: 'Input object available as ${{ input.* }}' },
        },
        required: ['workflow_id'],
      },
      async execute({ workflow_id, input }, ctx) {
        const result = await engine.run(workflow_id, { input: input || {}, signal: ctx?.signal, ctx });
        const lines = [`workflow ${workflow_id}: ${result.ok ? 'ok' : 'failed'}${result.error ? ` (${result.error})` : ''}`];
        for (const s of result.steps) {
          if (s.skipped) lines.push(`  ${s.id}: skipped (${s.reason})`);
          else lines.push(`  ${s.id}: ${s.ok ? 'ok' : 'FAILED'} -> ${String(s.output ?? s.error ?? '').slice(0, 300)}`);
        }
        return lines.join('\n');
      },
    },
  ];
}
