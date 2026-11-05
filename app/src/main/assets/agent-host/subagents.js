// 并行子 agent：把互相独立的工作流拆给多个「自己的上下文」的 agent 同时跑。
//
// 为什么需要：无人值守的长任务里，串行回复的最大瓶颈不是工具，是模型的上下文。
// 子 agent 各自从干净上下文出发，主 agent 只拿到压缩后的结论，既并行又省 token。
//
// 安全边界：
//   - 子 agent 默认不能再派发子 agent（maxDepth 控制），避免无限套娃
//   - 子 agent 与主 agent 共用调度器：跨 agent 的文件写冲突仍按路径串行
//   - 子 agent 的审批结论回传到主会话（父 UI 能看到、能批准），不会因为
//     子会话 id 被 UI 过滤掉而默默超时拒绝
//   - 父任务取消会中止所有子任务；每个子任务也有自己的硬超时

import { Agent } from './agent.js';
import { ApprovalPolicy } from './approvals.js';
import { ToolRegistry } from './tools/registry.js';
import { truncate } from './providers/base.js';
import { DEFAULT_PERSONA, PERSONAS, PERSONA_IDS, effectiveRisks, personaRosterText, resolvePersona } from './personas.js';

const DEFAULT_TIMEOUT_MS = 8 * 60_000;
const MAX_TASKS_PER_CALL = 6;

function labelOf(task, i) {
  const raw = String(task?.label ?? task?.name ?? '').trim();
  return raw ? raw.slice(0, 40) : `task-${i + 1}`;
}

function promptOf(task) {
  const text = String(task?.prompt ?? task?.task ?? '').trim();
  if (!text) throw new Error('each subagent task needs a non-empty prompt');
  return text;
}

/** 子 agent 看到的通用边界提示；具体职责由 persona.prompt 负责。 */
const SUBAGENT_SYSTEM_PROMPT = `You are a focused coding sub-agent working on one delegated task inside the same workspace as the parent agent.
Do the task yourself with the available tools, then reply with a concise report: what you changed, what you verified, and anything the parent still needs to know.
Do not ask the user questions; make reasonable decisions and state them.
Report in the same language the parent used in the task description.`;

/** 人设的系统提示 = 通用边界 + 角色职责。 */
function systemPromptFor(persona) {
  return `${SUBAGENT_SYSTEM_PROMPT}\n\n[persona: ${persona.id} / ${persona.name}]\n${persona.prompt}`;
}

/**
 * 按人设的 risk 白名单裁剪工具表。
 * 只读人设天然拿不到 write/exec；再叠加 read_only 只会更严。
 */
function registryFor(parentRegistry, { risks = ['read', 'write', 'exec'], allowSubagents = false } = {}) {
  const registry = new ToolRegistry();
  for (const tool of parentRegistry.list()) {
    if (!allowSubagents && tool.name.startsWith('spawn_subagent')) continue;
    if (!risks.includes(tool.risk || 'read')) continue;
    registry.register(tool);
  }
  return registry;
}

export class SubagentRunner {
  /**
   * @param {object} opts
   * @param {Agent} opts.parent                     主 agent（提供 provider/registry/审批/工作区）
   * @param {number} [opts.maxConcurrent=3]         同时跑的子 agent 数
   * @param {number} [opts.maxDepth=1]              允许的嵌套层数
   * @param {number} [opts.timeoutMs]               单个子任务硬超时
   * @param {(ev:object)=>void} [opts.onEvent]      事件出口（默认走 parent.onEvent，动态读取，避免被 Router 覆盖后失联）
   */
  constructor({
    parent,
    store = null,
    maxConcurrent = 3,
    maxDepth = 1,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    onEvent = null,
    maxIterations = 12,
  } = {}) {
    if (!parent) throw new Error('SubagentRunner: parent agent is required');
    this.parent = parent;
    this.store = store;
    this.maxConcurrent = Math.max(1, maxConcurrent);
    this.maxDepth = Math.max(0, maxDepth);
    this.timeoutMs = timeoutMs;
    this.maxIterations = maxIterations;
    this._seq = 0;
    this._onEvent = onEvent;
    this.active = new Map(); // subagentId -> {label, startedAt, parentRunId, sessionId}
    this.completed = 0;
    this.failed = 0;
  }

  #emit(event) {
    const fn = this._onEvent || ((ev) => this.parent.onEvent?.(ev));
    try {
      fn(event);
    } catch {
      /* 观测失败不能影响子任务 */
    }
  }

  snapshot() {
    return {
      active: [...this.active.values()].map((v) => ({ ...v, elapsedMs: Date.now() - v.startedAt })),
      completed: this.completed,
      failed: this.failed,
      maxConcurrent: this.maxConcurrent,
    };
  }

  /**
   * 并发跑一批子任务，等全部结束再返回可读报告。
   * tasks: [{ label?, prompt, persona?, read_only? }]
   */
  async run(tasks, ctx = {}, { depth = 1 } = {}) {
    if (!Array.isArray(tasks) || !tasks.length) throw new Error('tasks must be a non-empty array');
    if (tasks.length > MAX_TASKS_PER_CALL) throw new Error(`at most ${MAX_TASKS_PER_CALL} subagents per call`);
    if (depth > this.maxDepth) throw new Error(`subagent nesting limit reached (maxDepth=${this.maxDepth})`);

    // 人设在整个批次开始前统一解析：一个写错的 persona 直接报错，
    // 而不是跑到第 3 个任务时才发现前两个已经改了文件。
    const normalized = tasks.map((task, i) => ({
      label: labelOf(task, i),
      prompt: promptOf(task),
      persona: resolvePersona(task?.persona ?? task?.role),
      readOnly: Boolean(task.read_only ?? task.readOnly ?? false),
    }));
    // 重名会让报告难读，也容易让主模型搞混；就地加序号。
    const seen = new Map();
    for (const t of normalized) {
      const n = (seen.get(t.label) || 0) + 1;
      seen.set(t.label, n);
      if (n > 1) t.label = `${t.label}#${n}`;
    }

    const startedAt = Date.now();
    const results = new Array(normalized.length);
    let cursor = 0;

    const worker = async () => {
      while (true) {
        const index = cursor++;
        if (index >= normalized.length) return;
        results[index] = await this.#runOne(normalized[index], ctx, depth);
      }
    };
    const workers = Array.from({ length: Math.min(this.maxConcurrent, normalized.length) }, () => worker());
    await Promise.all(workers);

    const summary = results
      .map((r) => {
        const head = `[subagent ${r.personaName}/${r.label}] ${r.ok ? 'ok' : 'failed'} — ${(r.durationMs / 1000).toFixed(1)}s, ` +
          `${r.toolCalls} tool calls${r.usage?.output_tokens ? `, ${r.usage.output_tokens} output tokens` : ''}`;
        return `${head}\n${r.error ? `error: ${r.error}` : r.text || '(no text report)'}`;
      })
      .join('\n\n');
    this.#emit({
      type: 'subagents_done',
      sessionId: ctx?.session?.id ?? null,
      runId: ctx?.runId ?? null,
      total: results.length,
      ok: results.filter((r) => r.ok).length,
      durationMs: Date.now() - startedAt,
    });
    return truncate(`parallel subagents finished (${results.filter((r) => r.ok).length}/${results.length} ok)\n\n${summary}`, 20000);
  }

  async #runOne(task, ctx, depth) {
    const id = `sub_${(++this._seq).toString(36)}_${Date.now().toString(36)}`;
    const startedAt = Date.now();
    const parentSession = ctx?.session ?? null;
    const parentRunId = ctx?.runId ?? null;
    const persona = task.persona;
    const risks = effectiveRisks(persona, task.readOnly);
    const systemPrompt = systemPromptFor(persona);
    this.active.set(id, {
      id,
      label: task.label,
      persona: persona.id,
      personaName: persona.name,
      startedAt,
      parentRunId,
      sessionId: parentSession?.id ?? null,
    });

    this.#emit({
      type: 'subagent_start',
      sessionId: parentSession?.id ?? null,
      runId: parentRunId,
      subagentId: id,
      label: task.label,
      persona: persona.id,
      personaName: persona.name,
      readOnly: task.readOnly,
      tools: risks,
      promptPreview: task.prompt.slice(0, 200),
    });

    const session = (this.store ? this.store.create({ systemPrompt }) : null) ?? {
      id: `sub_session_${id}`,
      messages: [{ role: 'system', content: systemPrompt }],
      runs: [],
    };

    const pieces = [];
    const ac = new AbortController();
    const onParentAbort = () => ac.abort();
    ctx?.signal?.addEventListener('abort', onParentAbort, { once: true });
    const timer = setTimeout(() => ac.abort(), this.timeoutMs);
    timer.unref?.();

    const childOnEvent = (ev) => {
      // 子会话 id 不在父 UI 的过滤名单里：统一改写成父会话 id，
      // 否则子 agent 的进度与审批在编码页上完全不可见。
      const base = {
        ...ev,
        sessionId: parentSession?.id ?? ev.sessionId,
        subagentId: id,
        persona: persona.id,
        personaName: persona.name,
        parentRunId,
      };
      if (ev.type === 'text') {
        pieces.push(String(ev.delta ?? ''));
        this.#emit({ ...base, type: 'subagent_text', delta: ev.delta });
        return;
      }
      if (ev.type === 'thinking') {
        this.#emit({ ...base, type: 'subagent_thinking' });
        return;
      }
      if (ev.type === 'tool_start' || ev.type === 'tool_end' || ev.type === 'tool_approval') {
        this.#emit({ ...base, type: 'subagent_tool', phase: ev.type === 'tool_start' ? 'start' : 'end', toolType: ev.type });
        return;
      }
      // run_start / iteration / heartbeat / done 等照常透传（带 subagentId 供 UI 分组）。
      this.#emit(base);
    };

    try {
      const childApproval = new ApprovalPolicy({
        mode: this.parent.approval.mode,
        // 审批请求带着父会话上下文上抛，UI 才会显示这张卡而不是默默超时。
        onRequest: (req, childCtx) =>
          this.parent.approval.onRequest
            ? this.parent.approval.onRequest(req, {
                ...childCtx,
                session: parentSession ?? childCtx?.session,
                subagentId: id,
                parentRunId,
              })
            : false,
      });

      const childAgent = new Agent({
        provider: this.parent.provider,
        registry: registryFor(this.parent.registry, {
          risks,
          allowSubagents: depth < this.maxDepth,
        }),
        workspaceRoot: this.parent.workspaceRoot,
        approval: childApproval,
        systemPrompt,
        maxIterations: this.maxIterations,
        onEvent: childOnEvent,
        // 共享同一份 limits / ledger / planner / 调度器：配额与写锁是全局的。
        limits: this.parent.limits,
        dangerGuard: this.parent.dangerGuard,
        ledger: this.parent.ledger,
        planner: this.parent.planner,
        changeTracker: this.parent.changeTracker,
        skills: this.parent.skills,
        mcp: this.parent.mcp,
        scheduler: this.parent.scheduler,
        // 嵌套：只有还没到深度上限才把 runner 挂给子 agent。
        subagents: depth < this.maxDepth ? this : null,
        subagentDepth: depth,
        heartbeatMs: this.parent.heartbeatMs,
      });

      const { text, run, cancelled } = await childAgent.prompt(session, task.prompt, {
        signal: ac.signal,
        subagent: { id, parentRunId },
      });
      this.store?.touch?.(session);
      const durationMs = Date.now() - startedAt;
      const timedOut = ac.signal.aborted && !ctx?.signal?.aborted;
      const result = {
        id,
        label: task.label,
        persona: persona.id,
        personaName: persona.name,
        ok: !cancelled && !timedOut,
        text: text || pieces.join(''),
        durationMs,
        usage: run?.usage ?? null,
        toolCalls: run?.toolCalls?.length ?? 0,
        error: timedOut ? `timed out after ${this.timeoutMs / 1000}s` : cancelled ? 'cancelled by parent' : null,
      };
      result.ok = result.ok && result.error == null;
      if (result.ok) this.completed += 1;
      else this.failed += 1;
      this.#emit({
        type: 'subagent_end',
        sessionId: parentSession?.id ?? null,
        runId: parentRunId,
        subagentId: id,
        label: task.label,
        persona: persona.id,
        personaName: persona.name,
        ok: result.ok,
        durationMs,
        toolCalls: result.toolCalls,
        usage: result.usage,
        error: result.error,
        textPreview: (result.text || '').slice(0, 400),
      });
      return result;
    } catch (err) {
      const result = {
        id,
        label: task.label,
        persona: persona.id,
        personaName: persona.name,
        ok: false,
        text: '',
        durationMs: Date.now() - startedAt,
        usage: null,
        toolCalls: 0,
        error: err.message,
      };
      this.failed += 1;
      this.#emit({
        type: 'subagent_end',
        sessionId: parentSession?.id ?? null,
        runId: parentRunId,
        subagentId: id,
        label: task.label,
        persona: persona.id,
        personaName: persona.name,
        ok: false,
        durationMs: result.durationMs,
        error: err.message,
      });
      return result;
    } finally {
      clearTimeout(timer);
      ctx?.signal?.removeEventListener?.('abort', onParentAbort);
      this.active.delete(id);
    }
  }
}

/**
 * 暴露给模型的工具。`concurrency: 'none'` 是必须的：
 * 它内部已经并发跑子 agent，若再被调度器套上全局写锁，
 * 会和子 agent 的写工具形成「父等子、子等父」的环路。
 */
export function createSubagentTools({ runner, maxTasks = MAX_TASKS_PER_CALL }) {
  return [
    {
      name: 'spawn_subagents',
      description:
        'Run independent tasks in parallel, each in its own context, each with a persona that sets its role and ' +
        `tool permissions. Personas — ${personaRosterText()}. ` +
        'Read-only personas (architect/reviewer/researcher) cannot modify files; only implementer/docwriter can write, ' +
        'tester can run commands. Give every task a self-contained prompt and a distinct label. ' +
        'Use this for workstreams that do not depend on each other.',
      risk: 'write',
      concurrency: 'none',
      parameters: {
        type: 'object',
        properties: {
          tasks: {
            type: 'array',
            minItems: 1,
            maxItems: maxTasks,
            description: 'Tasks to run concurrently',
            items: {
              type: 'object',
              properties: {
                label: { type: 'string', description: 'Short unique name for the subagent' },
                prompt: { type: 'string', description: 'Self-contained task description' },
                persona: {
                  type: 'string',
                  enum: PERSONA_IDS,
                  description:
                    `Role of this subagent (default ${DEFAULT_PERSONA}). ` +
                    `${PERSONAS.architect.name}=plan only, ${PERSONAS.reviewer.name}=critique only, ` +
                    `${PERSONAS.tester.name}=run commands only, ${PERSONAS.researcher.name}=look up facts only, ` +
                    `${PERSONAS.docwriter.name}=docs only, ${PERSONAS.implementer.name}=make the change.`,
                },
                read_only: { type: 'boolean', description: 'Force read-only tools even for a writing persona' },
              },
              required: ['prompt'],
            },
          },
        },
        required: ['tasks'],
      },
      execute({ tasks }, ctx) {
        return runner.run(tasks, ctx, { depth: (ctx?.subagentDepth ?? 0) + 1 });
      },
    },
  ];
}

export { SUBAGENT_SYSTEM_PROMPT, MAX_TASKS_PER_CALL, PERSONAS, PERSONA_IDS, resolvePersona };
