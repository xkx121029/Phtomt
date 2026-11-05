// Transport-agnostic JSON request router. One JSON object per request line:
//   { id, method, params }
// Responses: { id, type: 'response', result } | { id, type: 'error', error }
// Streamed events: { type: 'event', event }

import { APPROVAL_MODES } from './approvals.js';
import { SessionStore } from './session.js';
import { createProviderFromPreset, presetOptions } from './providers/presets.js';

/**
 * 审批请求的默认超时（ms）。UI 不答（切页面、断连、杀进程）时不能把 agent
 * 永久挂起：超时按「拒绝」处理并广播 approval_timeout 事件。
 * 在 Router 构造时读 env，运行中可调（测试/不同部署各取所需）。
 */
const DEFAULT_APPROVAL_TIMEOUT_MS = 120_000;

export class AgentRouter {
  #senders = new Set();

  constructor({ agent, store = new SessionStore(), runtime = null, token, approvalTimeoutMs } = {}) {
    this.agent = agent;
    this.store = store;
    this.runtime = runtime;
    this.token = token || null;
    // 注意：Number(undefined) 是 NaN，而 ?? 不兜底 NaN——直接写
    // `Number(env.X) ?? default` 会把超时设成 1ms 立即触发。
    const fromEnv = Number(process.env.HAPPY_ACA_APPROVAL_TIMEOUT_MS);
    this.approvalTimeoutMs =
      approvalTimeoutMs ?? (Number.isFinite(fromEnv) && fromEnv > 0 ? fromEnv : DEFAULT_APPROVAL_TIMEOUT_MS);
    this.pendingApprovals = new Map(); // callId -> resolve（函数上挂 .timer）
    this.abortControllers = new Map(); // sessionId -> AbortController
    this._callSeq = 0;
    // 可观测性：事件既广播给在线连接，也进环缓冲，供断线后补拉 / 事后追查。
    this.events = runtime?.events ?? null;
    this.runTracker = runtime?.runTracker ?? null;
    this.startedAt = Date.now();

    // Approval: emit an approval_request event, resolve when the host answers.
    this.agent.approval.onRequest = (req, ctx) =>
      new Promise((resolve) => {
        const callId = `apr_${++this._callSeq}`;
        this.pendingApprovals.set(callId, resolve);
        this.#sendEvent({
          type: 'approval_request',
          callId,
          tool: req.tool,
          risk: req.risk,
          args: req.args,
          sessionId: ctx?.session?.id,
          ...(ctx?.subagentId ? { subagentId: ctx.subagentId, parentRunId: ctx.parentRunId } : {}),
        });
        // 超时兜底：没人答就按拒绝收场，agent 继续往下走，绝不挂死。
        const timer = setTimeout(() => {
          if (!this.pendingApprovals.has(callId)) return;
          this.pendingApprovals.delete(callId);
          this.#sendEvent({
            type: 'approval_timeout',
            callId,
            tool: req.tool,
            sessionId: ctx?.session?.id,
            timeoutMs: this.approvalTimeoutMs,
          });
          resolve(false);
        }, this.approvalTimeoutMs);
        this.pendingApprovals.get(callId).timer = timer;
      });

    // Forward every agent event to the host.
    this.agent.onEvent = (ev) => this.#sendEvent(ev);
  }

  // Transports register senders here. Responses travel per-connection (via
  // handleLine's respond callback); events are broadcast to every sender.
  addSender(send) {
    this.#senders.add(send);
  }

  removeSender(send) {
    this.#senders.delete(send);
  }

  // Back-compat single-transport helper (stdio).
  setSender(send) {
    this.addSender(send);
  }

  #sendEvent(event) {
    // 先记录再广播：连接可能在广播途中消失，日志不能漏掉这条。
    const row = this.events ? this.events.push(event) : null;
    const payload = row ? { ...event, seq: row.seq } : event;
    this.runTracker?.apply(payload);
    const msg = { type: 'event', event: payload };
    for (const send of this.#senders) {
      try {
        send(msg);
      } catch {
        /* a dead transport must not break the others */
      }
    }
  }

  async handle(request) {
    const { method, params = {} } = request;
    if (this.token && method !== 'ping' && params.token !== this.token) {
      throw new Error('unauthorized: missing or invalid token');
    }
    switch (method) {
      case 'ping':
        return { pong: true, workspace: this.agent.workspaceRoot, approvalMode: this.agent.approval.mode };

      case 'sessions':
        return this.store.list();

      case 'new_session':
        return { sessionId: this.store.create({ systemPrompt: this.agent.systemPrompt }).id };

      case 'prompt': {
        // 客户端可以自带 sessionId（HPA 编码页的会话 id 落在工作区里，由界面生成）。
        // 未知 id 不能直接报错：界面的会话是按工作区持久化的，host 刚重启 / 刚切工作区时
        // 内存里没有这条会话，但它本来就该是一条新会话——就地按这个 id 建出来即可，
        // 否则界面第一次发消息必然 "unknown session"，之后也永远建不起来。
        const session = params.sessionId
          ? (this.store.get(params.sessionId) ||
              this.store.create({ id: params.sessionId, systemPrompt: this.agent.systemPrompt }))
          : this.store.create({ systemPrompt: this.agent.systemPrompt });
        if (!params.text) throw new Error('prompt requires text');
        const ac = new AbortController();
        this.abortControllers.set(session.id, ac);
        try {
          const { text, run, cancelled } = await this.agent.prompt(session, params.text, { signal: ac.signal });
          this.store.touch(session);
          return { sessionId: session.id, runId: run.id, text, cancelled: Boolean(cancelled) };
        } finally {
          this.abortControllers.delete(session.id);
        }
      }

      case 'cancel': {
        const ac = this.abortControllers.get(params.sessionId);
        if (!ac) return { cancelled: false, reason: 'no active run' };
        ac.abort();
        return { cancelled: true };
      }

      case 'set_approval_mode': {
        // 编码页的「审批档位」要求即时生效：档位是运行期策略，改字段即可。
        // 不走重启宿主那条路——重启会掐断正在跑的这一轮和所有连接。
        if (!APPROVAL_MODES.includes(params.mode)) {
          throw new Error(`unknown approval mode: ${params.mode} (available: ${APPROVAL_MODES.join(', ')})`);
        }
        this.agent.approval.mode = params.mode;
        return { approvalMode: this.agent.approval.mode };
      }

      case 'approve': {
        const entry = this.pendingApprovals.get(params.callId);
        if (!entry) return { ok: false, reason: 'unknown or expired callId' };
        this.pendingApprovals.delete(params.callId);
        if (entry.timer) clearTimeout(entry.timer);
        entry(Boolean(params.approved));
        return { ok: true };
      }

      case 'undo': {
        const session = this.store.get(params.sessionId);
        if (!session) throw new Error(`unknown session: ${params.sessionId}`);
        return this.agent.undo(session, params.runId);
      }

      // —— 运行时子系统（需要 createRuntime 装配的 runtime） ——
      case 'runtime_info':
        return this.#runtimeInfo();
      case 'memory_remember':
        return this.#rt('memory').remember(params);
      case 'memory_recall':
        return this.#rt('memory').recall(params.query, { limit: params.limit });
      case 'memory_stats':
        return this.#rt('memory').stats();
      case 'usage_report':
        return this.#usageReport(params);
      case 'presets_list':
        return presetOptions();
      case 'profiles_list':
        return { profiles: this.#rt('profiles').list(), bindings: this.#rt('profiles').bindings() };
      case 'profiles_upsert':
        return this.#rt('profiles').upsert(params.profile || params);
      case 'profiles_remove':
        return { removed: this.#rt('profiles').remove(params.id) };
      case 'profiles_bind':
        this.#rt('profiles').bind(params.task, params.profileId);
        return { ok: true };
      case 'profiles_unbind':
        this.#rt('profiles').unbind(params.task);
        return { ok: true };
      case 'mcp_servers':
        return this.#rt('mcp').listServers();
      case 'mcp_add':
        this.#rt('mcp').addServer(params.server || params);
        return { ok: true, servers: this.#rt('mcp').listServers() };
      case 'mcp_remove':
        this.#rt('mcp').removeServer(params.name);
        return { ok: true, servers: this.#rt('mcp').listServers() };
      case 'mcp_connect':
        return this.#rt('mcp').connect(params.name).then(() => ({ ok: true, tools: this.agent.registry.list().length }));
      case 'mcp_disconnect':
        this.#rt('mcp').disconnect(params.name);
        return { ok: true };
      case 'skills_list':
        return { skills: this.#rt('skills').list(), errors: this.#rt('skills').errors() };
      case 'skill_activate':
        return this.#rt('skills').activate(params.skillId);
      case 'skill_deactivate':
        return this.#rt('skills').deactivate(params.skillId);
      case 'workflow_list':
        return this.#rt('workflow').list();
      case 'workflow_define':
        return this.#rt('workflow').define(params.workflow || JSON.parse(params.json));
      case 'workflow_remove':
        return { removed: this.#rt('workflow').remove(params.id) };
      case 'workflow_run':
        return this.#rt('workflow').run(params.id, { input: params.input || {} });
      case 'workspace_changes':
        return { summary: this.#rt('tracker').summary(params.runId || null), records: this.#rt('tracker').recent(params.recent ?? 50) };
      case 'workspace_files':
        return this.#rt('tracker').listFiles({ maxDepth: params.maxDepth ?? 4, limit: params.limit ?? 500 });
      case 'workspaces_list':
        return { workspaces: this.#rt('workspaces').list(), current: this.#rt('workspaces').defaultId };
      case 'workspaces_add':
        return this.#rt('workspaces').add({ id: params.id, root: params.root, kind: params.kind, label: params.label });
      case 'workspaces_remove':
        return { removed: this.#rt('workspaces').remove(params.id) };
      case 'project_templates':
        return this.#rt('scaffold').constructor.list();
      case 'project_scaffold': {
        // 应用侧「项目模板」入口（#31）：列出模板之后直接落一个目录，不必先跟模型说一句话。
        if (!params.templateId) throw new Error('project_scaffold requires templateId');
        return this.#rt('scaffold').create(params.templateId, params.dirName || params.templateId, {
          name: params.name,
        });
      }
      case 'preview_status':
        return this.#previewStatus();
      // —— 可观测性 ——
      case 'agent_status':
        return this.#agentStatus();
      case 'agent_events':
        // 断线重连后补拉：since 是上次收到事件的 seq，不会重复也不会漏。
        return { events: this.events ? this.events.recent({ limit: params.limit ?? 200, since: params.since ?? 0, type: params.type ?? null }) : [] };
      case 'activity_log':
        // 面向人的「做了什么」：默认剔除心跳和正文增量，只看结构性动作。
        return { entries: this.#activity(params) };
      case 'host_probe':
        return this.#rt('bridge').probe({ force: params.force });
      case 'host_call':
        // 给 UI 的直通口：应用侧调试/设置页可以主动调一次宿主能力，不必绕 agent。
        return this.#rt('bridge').call(params.method, params.params || {}, { timeoutMs: params.timeoutMs });

      default:
        throw new Error(`unknown method: ${method}`);
    }
  }

  /** 运行时总览 / 健康快照（无人值守巡检查这个）。 */
  #agentStatus() {
    const runtime = this.runtime;
    return {
      ok: true,
      workspace: this.agent.workspaceRoot,
      approvalMode: this.agent.approval.mode,
      model: this.agent.provider?.model ?? null,
      uptimeMs: Date.now() - this.startedAt,
      tools: this.agent.registry.list().length,
      runs: this.runTracker ? this.runTracker.snapshot() : [],
      subagents: runtime?.subagents?.snapshot?.() ?? { active: [], completed: 0, failed: 0 },
      scheduler: this.agent.scheduler?.stats?.() ?? null,
      events: this.events ? this.events.stats() : null,
      pendingApprovals: this.pendingApprovals.size,
      activeSessions: this.abortControllers.size,
      usage: runtime?.ledger?.total?.() ?? null,
      failed: runtime?.failed ?? [],
    };
  }

  /**
   * 人读的活动日志：把事件流压成「一句话一件事」。
   * since 是 seq；type 过滤；默认不给 heartbeat/text（那是噪声，不是活动）。
   */
  #activity({ limit = 60, since = 0, types = null, type = null } = {}) {
    if (!this.events) return [];
    const allow = new Set(
      types || (type ? [type] : ['run_start', 'tool_start', 'tool_end', 'tool_approval', 'iteration', 'done', 'cancelled',
        'subagent_start', 'subagent_end', 'subagents_done']),
    );
    return this.events
      .recent({ limit: Math.min(500, Math.max(1, limit)), since })
      .filter((row) => allow.has(row.type))
      .map((row) => ({
        seq: row.seq,
        ts: row.ts,
        type: row.type,
        runId: row.runId ?? null,
        sessionId: row.sessionId ?? null,
        subagentId: row.subagentId ?? null,
        // 只留「人要看」的字段，避免把整个工具参数/输出灌给界面。
        label:
          row.type === 'tool_start' || row.type === 'tool_end'
            ? row.name
            : row.type === 'subagent_start' || row.type === 'subagent_end'
              ? row.label
              : row.type,
        ok: row.ok ?? null,
        durationMs: row.durationMs ?? null,
        detail: row.type === 'tool_start' ? row.argsPreview : row.type === 'tool_end' ? row.outputPreview : null,
      }));
  }

  /** 取一个必需的运行时子系统；未装配时给出可操作的错误。 */
  #rt(key) {
    const part = this.runtime?.[key];
    if (!part) throw new Error(`method needs runtime.${key}; boot the host with createRuntime()`);
    return part;
  }

  #runtimeInfo() {
    if (!this.runtime) return { runtime: false };
    return {
      runtime: true,
      workspace: this.agent.workspaceRoot,
      approvalMode: this.agent.approval.mode,
      // 带上 risk 和描述：工具页直接渲染这一份，才不会和真实注册表漂移。
      tools: this.agent.registry.list().map((t) => ({
        name: t.name,
        risk: t.risk || 'read',
        description: t.description,
      })),
      subsystems: Object.entries(this.runtime)
        .filter(([, v]) => v && typeof v === 'object' && !Array.isArray(v))
        .map(([k]) => k),
      failed: this.runtime.failed || [],
      usage: this.runtime.ledger?.total() ?? null,
    };
  }

  #usageReport({ scope = 'total', runId = null } = {}) {
    const ledger = this.#rt('ledger');
    if (scope === 'by-model') return ledger.byModel();
    if (scope === 'by-day') return ledger.byDay();
    if (scope === 'by-run') return ledger.byRun(runId);
    return ledger.total();
  }

  /** 本地预览服务器的状态（设置页 / 模型自查用）。 */
  #previewStatus() {
    const server = this.runtime?.preview;
    if (!server) return { running: false };
    return {
      running: Boolean(server.server),
      url: server.server ? server.url() : null,
      root: server.root,
    };
  }

  /** 便捷方法：按预设建一个一次性 provider（设置页试连通性用）。 */
  createProvider(params) {
    return createProviderFromPreset(params);
  }

  /** Handle one raw line (used by stdio and WebSocket transports alike). */
  async handleLine(line, respond) {
    let request;
    try {
      request = JSON.parse(line);
    } catch {
      return respond({ type: 'error', error: 'invalid JSON line' });
    }
    try {
      const result = await this.handle(request);
      respond({ id: request.id, type: 'response', result });
    } catch (err) {
      respond({ id: request.id, type: 'error', error: err.message });
    }
  }
}
