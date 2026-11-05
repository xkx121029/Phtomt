// The agent loop: stream provider events -> accumulate tool calls -> approvals -> execute -> loop.
//
// 并行语义（无人值守编码环境的关键）：
//   - 同一轮里的多个工具调用不再串行 await，而是交给 ToolScheduler：
//     只读工具完全并行，写工具按目标路径串行（不同路径仍并行），shell 串行。
//   - 审批仍然逐个按调用顺序询问：交互必须稳定，不能三个弹窗同时跳出来。
//   - 工具结果按原始调用顺序回填到 session.messages，provider 契约不受并发影响。

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ApprovalPolicy } from './approvals.js';
import { truncate } from './providers/base.js';
import { TextStreamRevisionTracker } from './stream-tracker.js';
import { ToolScheduler } from './tool-scheduler.js';

/**
 * Describe the host truthfully so the model plans for the machine it is
 * actually on. On a phone this is the difference between "run npm test" and
 * "rewrite the test to run without a toolchain".
 */
function hostEnvironment() {
  const isAndroid = process.platform === 'android' || Boolean(process.env.HAPPY_ACA_ANDROID);
  const lines = [`platform: ${process.platform} (${process.arch}), node ${process.version}`];
  if (isAndroid) {
    lines.push(
      'This is an Android phone running the agent fully on-device. The shell the run_command tool uses is mksh ' +
        '(/system/bin/sh): POSIX-ish but no bash, no coreutils pipeline niceties, no compiler ' +
        'toolchain, no package manager, and no network package installs.',
    );
    lines.push(
      'So: verify changes by reading files and running available commands (ls, cat, grep, ' +
        'find, sed, node). Do NOT promise to compile, install dependencies, or run a test ' +
        'suite that needs a toolchain from the shell — say plainly what you could not verify.',
    );
    lines.push(
      'Builds are the exception: a companion app ("coding environment") can run Gradle on-device ' +
        '(PRoot Linux + JDK + Gradle + Android SDK). Call coding_env action=status first; when something ' +
        'is missing run action=setup, which is repeatable and stops at steps only the user can do ' +
        '(installing the companion app, granting storage access). Only claim a build passed when ' +
        'coding_env reported ready=true and the build result says so.',
    );
    lines.push(
      'The built-in skill "happyac-coding" documents this build workflow, the shared-storage mirror ' +
        '(happyac-build/), and the workspace conventions. Run list_skills, then activate_skill ' +
        '"happyac-coding" before doing device-side Android work.',
    );
  }
  lines.push(`cwd: ${process.cwd()}`, `cpus: ${os.cpus().length}, memory: ${Math.round(os.totalmem() / 1024 ** 3)}GB`);
  return lines.join('\n');
}

export const DEFAULT_SYSTEM_PROMPT = `You are happy_ACA, an agentic coding assistant working inside a workspace directory on the user's device.
You have tools to read, write and edit files, search the codebase, inspect the workspace, apply patches, and run shell commands.

Start with workspace_overview to learn the layout before touching anything.

Environment you are running in:
${hostEnvironment()}

Rules:
- Read before you write. Make the smallest change that solves the task.
- Never delete or overwrite a file you have not read.
- Prefer apply_patch for targeted edits; use write_file for new or fully rewritten files.
- For work that takes more than two or three tool calls, write the plan with todo_write first,
  keep exactly one item in_progress, and mark items completed as soon as they are actually done.
- Use delete_path / copy_path / make_dir rather than shelling out to rm / cp / mkdir:
  they stay inside the workspace and are covered by undo.
- Use git_read to inspect changes (status / diff / log) before assuming what the repo looks like.
- Use web_fetch to check library docs or API references instead of guessing from memory.
- Reads run in parallel automatically: batch independent read_file / read_files calls in one turn.
- Writes to different files also run in parallel; writes to the same file are serialized for you.
- For large independent workstreams, use spawn_subagents to run several coding sub-agents in parallel,
  each with its own context and a persona that matches its role (implementer / architect / reviewer /
  tester / researcher / docwriter); give each a self-contained prompt and a distinct label.
- Delegate blind spots to the right persona: architect to plan, reviewer to find defects, tester to run
  and report evidence, researcher to verify facts — do not ask one sub-agent to both write and review.
- For multi-step work, state a short plan first, then act.
- Verify your work with the tools you actually have, and be explicit about anything you could not verify.
- When done, report concisely what changed and what you verified, in the user's language.`;

/**
 * 把一次模型调用的 usage 累加到 run 上。一个 run 可能有多轮（工具回合），
 * 成本统计要的是整轮总和，不是最后一轮的数字。
 */
function mergeUsage(acc, add) {
  if (!add) return acc;
  if (!acc) return { ...add };
  const out = { ...acc };
  for (const [key, value] of Object.entries(add)) {
    if (typeof value === 'number') out[key] = (typeof out[key] === 'number' ? out[key] : 0) + value;
  }
  return out;
}

/** 工具参数摘要：给 UI/日志一行看得懂的东西，避免把整个文件内容塞进事件流。 */
function previewArgs(args, max = 300) {
  if (!args || typeof args !== 'object') return '';
  try {
    const s = JSON.stringify(args);
    return s.length > max ? `${s.slice(0, max)}…` : s;
  } catch {
    return '';
  }
}

/** 工具输出摘要：可观测性要的是「够判断」，不是全文。 */
function previewOutput(text, max = 400) {
  const s = String(text ?? '');
  if (s.length <= max) return s;
  return `${s.slice(0, max)}…`;
}

export class Agent {
  constructor({
    provider,
    registry,
    workspaceRoot = process.cwd(),
    approval,
    systemPrompt = DEFAULT_SYSTEM_PROMPT,
    maxIterations = 25,
    onEvent,
    limits = null,
    dangerGuard = null,
    ledger = null,
    planner = null,
    changeTracker = null,
    skills = null,
    mcp = null,
    scheduler = null,
    subagents = null,
    subagentDepth = 0,
    heartbeatMs = 15_000,
  }) {
    this.provider = provider;
    this.registry = registry;
    this.workspaceRoot = path.resolve(workspaceRoot);
    this.approval = approval instanceof ApprovalPolicy ? approval : new ApprovalPolicy(approval || {});
    this.systemPrompt = systemPrompt;
    this.maxIterations = maxIterations;
    this.onEvent = onEvent || (() => {});
    this.limits = limits;
    this.dangerGuard = dangerGuard;
    this.ledger = ledger;
    this.planner = planner;
    this.changeTracker = changeTracker;
    // SkillManager（可选）：激活的 skill 的指令文本按轮注入 system prompt。
    this.skills = skills || null;
    // McpHub（可选）：MCP 工具靠 ctx.mcp.get(serverName) 找到自己的客户端——
    // 不注入的话所有 mcp_* 工具一调用就报 "not connected"。
    this.mcp = mcp || null;
    // 工具并发调度器：多个 Agent（主 agent + 子 agent）共享同一份时，
    // 跨 agent 的写冲突也按路径串行。
    this.scheduler = scheduler || new ToolScheduler({ maxConcurrent: limits?.maxConcurrentTools ?? 4 });
    // 子 agent 入口（可选）：注入后本轮 ctx 里能派发并行子任务。
    this.subagents = subagents || null;
    // 当前 agent 所处的子 agent 层级（0 = 主 agent）：派发工具据此判断还能不能继续嵌套。
    this.subagentDepth = subagentDepth;
    this.heartbeatMs = heartbeatMs;
    this._runSeq = 0;
  }

  #emit(event) {
    try {
      this.onEvent({ ts: Date.now(), ...event });
    } catch {
      /* a broken UI must never kill the agent */
    }
  }

  #recordBefore(run, absPath) {
    if (run.changes.some((c) => c.path === absPath)) return;
    let before = null;
    try {
      before = fs.readFileSync(absPath, 'utf8');
    } catch {
      before = null;
    }
    run.changes.push({ path: absPath, before });
  }

  /**
   * 总结器：把被裁掉的旧消息压缩成一段摘要（走同一个 provider，
   * 通常应由便宜的 summarize 档位模型承担；调用方失败时 planner 会降级）。
   */
  async #summarize(transcript) {
    const out = [];
    try {
      for await (const ev of this.provider.stream({
        messages: [
          {
            role: 'system',
            content:
              'Summarize the following conversation transcript. Preserve decisions, file paths, ' +
              'commands run and their outcomes, and anything still pending. Reply with the summary only.',
          },
          { role: 'user', content: transcript },
        ],
        tools: [],
      })) {
        if (ev.type === 'text') out.push(ev.delta);
      }
    } catch {
      /* planner.summarize 有自己的降级路径 */
    }
    return out.join('');
  }

  /** 把已激活 skill 的指令文本作为额外 system 消息插到主 system prompt 之后。 */
  #withSkillPrompts(messages) {
    if (!this.skills || typeof this.skills.activePrompts !== 'function') return messages;
    const extra = this.skills.activePrompts();
    if (!extra) return messages;
    const injected = { role: 'system', content: `[active skill instructions]\n${extra}` };
    const out = [];
    let placed = false;
    for (const m of messages) {
      out.push(m);
      if (!placed && m.role === 'system') {
        out.push(injected);
        placed = true;
      }
    }
    if (!placed) out.unshift(injected);
    return out;
  }

  /**
   * Run one user turn to completion.
   * Returns { text, run, session }. Emits events: text / thinking / tool_start / tool_approval /
   * tool_end / run_start / iteration / provider_request / provider_first_token / heartbeat / done.
   */
  async prompt(session, text, { signal, subagent = null } = {}) {
    session.messages.push({ role: 'user', content: text });
    const startedAt = Date.now();
    const run = {
      id: `run_${(++this._runSeq).toString(36)}_${Date.now().toString(36)}`,
      changes: [],
      toolCalls: [],
      usage: null,
      startedAt,
      iterations: 0,
      subagent,
    };
    session.runs.push(run);
    const ctx = {
      workspaceRoot: this.workspaceRoot,
      session,
      runId: run.id,
      signal,
      // 子 agent 派发走同一份运行时（由 index.js 注入的具体实现读取）。
      subagents: this.subagents,
      subagentDepth: this.subagentDepth,
      // MCP 工具靠这个找到自己的客户端（McpHub.get(serverName)）。
      mcp: this.mcp,
      onBeforeWrite: (abs) => {
        this.#recordBefore(run, abs);
        // 工作区变更追踪与 agent 的 undo 快照共用同一个「写前」钩子。
        this.changeTracker?.beforeWrite?.(abs, { runId: run.id, sessionId: session.id });
      },
    };
    let finalText = '';
    this.#emit({
      type: 'run_start',
      sessionId: session.id,
      runId: run.id,
      textPreview: String(text ?? '').slice(0, 200),
      maxIterations: this.maxIterations,
      ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
    });

    // 心跳：无人值守时最怕「进程还活着但没有任何输出」。
    // 定时把 elapsed / iteration / 当前在跑的工具数播出去，UI 与外部监控都能判断是否卡死。
    const heartbeat = this.heartbeatMs > 0
      ? setInterval(() => {
          this.#emit({
            type: 'heartbeat',
            sessionId: session.id,
            runId: run.id,
            elapsedMs: Date.now() - startedAt,
            iteration: run.iterations,
            tools: run.toolCalls.filter((c) => c.pending).length,
            ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
          });
        }, this.heartbeatMs)
      : null;
    heartbeat?.unref?.();

    try {
      for (let iter = 0; iter < this.maxIterations; iter++) {
        run.iterations = iter + 1;
        const pending = new Map(); // stream index -> { id, name, argsJson }
        const assistant = { role: 'assistant', content: '', toolCalls: [] };
        // 流式增量合并成稳定全文（revision 语义），UI/重连对齐都吃这一份。
        const textTracker = new TextStreamRevisionTracker();
        let usage = null;

        // 上下文窗口：超长会话只带裁剪后的消息，避免撑爆模型上下文。
        let planned = this.planner ? this.planner.plan(session) : { messages: session.messages };
        // 被裁掉的头部交给总结器压缩（失败会降级成机械摘要，不饿死会话）。
        // summarize 会把头部换成了总结块，所以之后必须重新 plan——
        // 否则本轮请求用的还是压缩前的旧窗口，总结白做。
        if (planned.pending?.length && typeof this.planner?.summarize === 'function') {
          try {
            await this.planner.summarize(session, (t) => this.#summarize(t));
            planned = this.planner.plan(session);
          } catch {
            /* 总结失败不该毁掉一轮对话：继续用未压缩的窗口 */
          }
        }
        const requestMessages = this.#withSkillPrompts(planned.messages);

        this.#emit({
          type: 'iteration',
          sessionId: session.id,
          runId: run.id,
          index: iter + 1,
          messages: requestMessages.length,
          ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
        });

        const requestStartedAt = Date.now();
        let firstEventAt = 0;
        let streamEvents = 0;
        this.#emit({
          type: 'provider_request',
          sessionId: session.id,
          runId: run.id,
          iteration: iter + 1,
          model: this.provider?.model ?? null,
          messages: requestMessages.length,
          tools: this.registry.schemas().length,
        });

        for await (const ev of this.provider.stream({ messages: requestMessages, tools: this.registry.schemas(), signal })) {
          if (signal?.aborted) break;
          streamEvents += 1;
          if (!firstEventAt) {
            firstEventAt = Date.now();
            // 首包延迟是网关/模型健康度最直接的指标；没有它，卡住时无法区分
            // 「网关在缓冲」和「模型在思考」。
            this.#emit({
              type: 'provider_first_token',
              sessionId: session.id,
              runId: run.id,
              iteration: iter + 1,
              ms: firstEventAt - requestStartedAt,
              kind: ev.type,
            });
          }
          if (ev.type === 'text') {
            textTracker.push(ev.delta);
            assistant.content = textTracker.text;
            this.#emit({
              type: 'text',
              delta: ev.delta,
              sessionId: session.id,
              runId: run.id,
              ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
            });
          } else if (ev.type === 'thinking') {
            // 推理过程不进 assistant.content：它不该被回灌给模型，也不该占正文 token。
            this.#emit({
              type: 'thinking',
              delta: ev.delta,
              sessionId: session.id,
              runId: run.id,
              ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
            });
          } else if (ev.type === 'tool_call') {
            const key = ev.index ?? pending.size;
            if (!pending.has(key)) pending.set(key, { id: ev.id, name: ev.name, argsJson: '' });
            const p = pending.get(key);
            if (ev.id) p.id = ev.id;
            if (ev.name) p.name = ev.name;
            p.argsJson += ev.argsDelta || '';
          } else if (ev.type === 'finish') {
            usage = ev.usage;
          }
        }
        if (signal?.aborted) {
          this.#emit({ type: 'cancelled', sessionId: session.id, runId: run.id });
          return { text: '(cancelled)', run, session, cancelled: true };
        }
        this.#emit({
          type: 'provider_stream_end',
          sessionId: session.id,
          runId: run.id,
          iteration: iter + 1,
          events: streamEvents,
          ms: Date.now() - requestStartedAt,
        });

        for (const [key, p] of pending) {
          let args = {};
          if (p.argsJson) {
            try {
              args = JSON.parse(p.argsJson);
            } catch {
              args = { _parse_error: p.argsJson.slice(0, 500) };
            }
          }
          assistant.toolCalls.push({ id: p.id || `call_${key}`, name: p.name || '(unnamed)', arguments: args });
        }
        session.messages.push(assistant);

        // 用量记账：每一轮模型调用都过账（by-day/by-model 报表的数据源）。
        if (usage) {
          run.usage = mergeUsage(run.usage, usage);
          this.ledger?.record({
            model: this.provider?.model,
            runId: run.id,
            sessionId: session.id,
            task: subagent ? 'subagent' : 'chat',
            usage,
          });
        }

        if (!assistant.toolCalls.length) {
          finalText = assistant.content;
          this.#emit({
            type: 'done',
            sessionId: session.id,
            runId: run.id,
            iterations: iter + 1,
            durationMs: Date.now() - startedAt,
            toolCount: run.toolCalls.length,
            usage: run.usage,
            ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
          });
          return { text: finalText, run, session };
        }

        await this.#runToolCalls(session, run, assistant.toolCalls, ctx, subagent);
      }

      const lastAssistant = [...session.messages].reverse().find((m) => m.role === 'assistant');
      finalText = (lastAssistant?.content ?? '') + `\n\n[stopped: reached ${this.maxIterations} tool iterations]`;
      this.#emit({
        type: 'done',
        sessionId: session.id,
        runId: run.id,
        iterations: this.maxIterations,
        durationMs: Date.now() - startedAt,
        toolCount: run.toolCalls.length,
        usage: run.usage,
        stopped: true,
        ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
      });
      return { text: finalText, run, session };
    } finally {
      if (heartbeat) clearInterval(heartbeat);
    }
  }

  /**
   * 一轮里的全部工具调用：先按顺序审批，再交给调度器并发执行，最后按原顺序回填消息。
   * 审批必须串行——同时弹三个确认框在手机上是灾难；执行才是并发的部分。
   */
  async #runToolCalls(session, run, calls, ctx, subagent) {
    const entries = [];
    for (const call of calls) {
      const tool = this.registry.get(call.name);
      run.toolCalls.push({ callId: call.id, name: call.name, arguments: call.arguments, pending: true });
      let pre = null;
      let danger = { dangerous: false };

      this.#emit({
        type: 'tool_start',
        sessionId: session.id,
        runId: run.id,
        callId: call.id,
        name: call.name,
        risk: tool?.risk || 'read',
        arguments: call.arguments,
        argsPreview: previewArgs(call.arguments),
        parallel: calls.length > 1,
        ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
      });

      // ok 必须来自真实执行结果，不能靠 output 的字符串前缀猜——
      // 工具正常返回一段以 "error:" 开头的日志/代码会被误判成失败。
      if (!tool) {
        pre = `error: unknown tool "${call.name}"`;
      } else {
        danger = this.dangerGuard?.inspect(tool, call.arguments) ?? { dangerous: false };
        if (danger.dangerous && this.approval.mode === 'bypass') {
          // bypass 意味着「别问我」，不是「允许我毁掉数据」：危险命令照样硬拦。
          this.#emit({
            type: 'tool_approval',
            sessionId: session.id,
            runId: run.id,
            callId: call.id,
            name: call.name,
            approved: false,
            danger: danger.id,
            why: danger.why,
            ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
          });
          pre =
            `DENIED: blocked by DangerGuard (${danger.id}: ${danger.why}). ` +
            'If this is genuinely required, explain the risk to the user and ask them to run it themselves.';
        } else {
          const approved = await this.approval.check(tool, call.arguments, ctx);
          this.#emit({
            type: 'tool_approval',
            sessionId: session.id,
            runId: run.id,
            callId: call.id,
            name: call.name,
            approved,
            ...(danger.dangerous ? { danger: danger.id, why: danger.why } : {}),
            ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
          });
          if (!approved) pre = 'DENIED: the user rejected this action.';
        }
      }

      entries.push({ call, tool, pre, execStartedAt: 0 });
    }

    const runnable = entries.filter((e) => e.pre == null && e.tool);
    const results = await this.scheduler.runAll(
      runnable.map((e) => ({ tool: e.tool, args: e.call.arguments, entry: e })),
      async (item) => {
        const e = item.entry;
        e.execStartedAt = Date.now();
        // 过执行闸：数量/参数体积/超时在 limits 里兜底；并发由调度器统一管理，
        // 这里跳过 limits 的并发闸，否则多子 agent 共享 limits 时会互相误杀。
        const execute = (args, c) => this.registry.execute(e.call.name, args, c);
        try {
          return this.limits
            ? await this.limits.run(e.tool, e.call.arguments, ctx, execute, { concurrency: false })
            : await execute(e.call.arguments, ctx);
        } catch (err) {
          return { ok: false, output: `error: ${err.message}` };
        }
      },
    );
    const byEntry = new Map();
    runnable.forEach((e, i) => byEntry.set(e, results[i]));

    for (const e of entries) {
      const r = byEntry.get(e);
      const ok = e.pre == null && r ? r.ok !== false : false;
      const output = truncate(e.pre ?? r?.output ?? '');
      const durationMs = e.execStartedAt ? Date.now() - e.execStartedAt : 0;
      const record = run.toolCalls.find((c) => c.callId === e.call.id);
      if (record) {
        record.pending = false;
        record.ok = ok;
        record.durationMs = durationMs;
      }
      session.messages.push({ role: 'tool', toolCallId: e.call.id, name: e.call.name, content: output });
      this.#emit({
        type: 'tool_end',
        sessionId: session.id,
        runId: run.id,
        callId: e.call.id,
        name: e.call.name,
        ok,
        durationMs,
        bytes: Buffer.byteLength(output, 'utf8'),
        outputPreview: previewOutput(output),
        ...(e.tool === undefined ? { unknownTool: true } : {}),
        ...(subagent ? { subagentId: subagent.id, parentRunId: subagent.parentRunId } : {}),
      });
    }
  }

  /** Restore every file touched by a run, in reverse order. */
  undo(session, runId) {
    const run = session.runs.find((r) => r.id === runId);
    if (!run) return { restored: [], error: `unknown run ${runId}` };
    const restored = [];
    for (const change of [...run.changes].reverse()) {
      try {
        if (change.before === null) {
          fs.rmSync(change.path, { force: true });
        } else {
          fs.mkdirSync(path.dirname(change.path), { recursive: true });
          fs.writeFileSync(change.path, change.before, 'utf8');
        }
        restored.push(change.path);
      } catch (err) {
        return { restored, error: `failed to restore ${change.path}: ${err.message}` };
      }
    }
    run.changes = [];
    return { restored };
  }
}
