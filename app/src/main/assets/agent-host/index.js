// happy_ACA agent-core public API.
//
// 这一层负责两件事：
//   1. 把内部实现（provider / 工具 / 子系统）重新导出，host 侧只 import 这里
//   2. createRuntime()：把零散子系统装配成一个可直接跑的 agent 运行时
// 装配原则：能力都是「可选挂载」——没给 stateDir 就纯内存，缺依赖就跳过该块，
// 任何一块挂载失败都不应该让整个 agent 起不来。

import path from 'node:path';

import { Agent, DEFAULT_SYSTEM_PROMPT } from './agent.js';
import { ToolRegistry } from './tools/registry.js';
import { createFsTools } from './tools/fs-tools.js';
import { createSearchTools } from './tools/search-tools.js';
import { createDeviceTools } from './tools/device-tools.js';
import { createShellTool } from './tools/shell-tool.js';
import { createGitTools } from './tools/git-tools.js';
import { createWebTools } from './tools/web-tools.js';
import { createTodoTools } from './tools/todo-tools.js';
import { createHostTools } from './tools/host-tools.js';
import { ApprovalPolicy } from './approvals.js';
import { SessionStore } from './session.js';
import { AgentRouter } from './rpc.js';
import { WsJsonServer, WsConnection, encodeFrame, decodeFrames } from './ws-server.js';
import { OpenAICompatibleProvider } from './providers/openai-compatible.js';
import { AnthropicProvider } from './providers/anthropic.js';
import { MockProvider, mockText, mockToolCall, mockScriptFromJSON } from './providers/mock.js';
import { ProviderError } from './providers/base.js';
import { VERSION, MCP_PROTOCOL_VERSION } from './version.js';
import { OllamaProvider, normalizeLocalBaseUrl } from './providers/ollama.js';
import { ENDPOINT_PRESETS, presetById, presetOptions, createProviderFromPreset } from './providers/presets.js';
import { TASK_KINDS, ModelProfileStore, KeyedProvider } from './providers/model-profiles.js';
import { TokenLedger, createUsageTools, costOf, priceFor } from './token-ledger.js';
import { ToolExecutionLimits, DangerGuard } from './execution-limits.js';
import { ContextWindowPlanner, ConversationStore } from './context-window.js';
import { TextStreamRevisionTracker, HotStreamMerger } from './stream-tracker.js';
import { MemoryGraph, EmbeddingService, createMemoryTools, localEmbedding, cosine } from './memory-graph.js';
import { McpHub, McpStdioClient, McpHttpClient, McpError } from './mcp/client.js';
import { McpServer, serveOverStdio } from './mcp/server.js';
import { SkillManager, createSkillTools, discoverSkills, loadSkillManifest } from './skills.js';
import { WorkflowEngine, createWorkflowTools, renderTemplate, evalCondition, WorkflowError } from './workflow.js';
import { loadJsTool, JsToolStore, createJsToolTools, JsToolError } from './js-tools.js';
import {
  GitIgnoreFilter,
  PROJECT_TEMPLATES,
  ProjectScaffold,
  WorkspaceBackupManager,
  WorkspaceChangeTracker,
  WorkspaceRegistry,
  createWorkspaceTools,
  readGitIgnore,
} from './workspace.js';
import { LocalPreviewServer, createPreviewTools, mimeFor } from './preview-server.js';
import { HostBridge, HostUnavailableError } from './host-bridge.js';
import { ToolScheduler, ResourceLocks, Semaphore } from './tool-scheduler.js';
import { EventLog, RunTracker } from './observability.js';
import { SubagentRunner, createSubagentTools } from './subagents.js';
import { PERSONAS, PERSONA_IDS, DEFAULT_PERSONA, resolvePersona, effectiveRisks } from './personas.js';

/** 基础工具集：文件 / 搜索 / 设备 / shell / git / 联网。永远是 agent 的底座。 */
export function createDefaultTools({ workspaceRoot }) {
  const registry = new ToolRegistry();
  for (const t of createFsTools({ workspaceRoot })) registry.register(t);
  for (const t of createSearchTools({ workspaceRoot })) registry.register(t);
  for (const t of createDeviceTools({ workspaceRoot })) registry.register(t);
  for (const t of createShellTool({ workspaceRoot })) registry.register(t);
  for (const t of createGitTools({ workspaceRoot })) registry.register(t);
  for (const t of createWebTools()) registry.register(t);
  return registry;
}

/**
 * 装配完整运行时。所有子系统都是可选的，用 `enable` 精确控制；
 * 未提供 stateDir 时全部子系统只活在内存里（测试 / 一次性会话友好）。
 *
 * @returns {{
 *   agent: Agent, registry: ToolRegistry, approval: ApprovalPolicy,
 *   ledger: TokenLedger, profiles: ModelProfileStore, memory: MemoryGraph,
 *   tracker: WorkspaceChangeTracker, backup: WorkspaceBackupManager,
 *   scaffold: ProjectScaffold, workspaces: WorkspaceRegistry,
 *   skills: SkillManager, jsTools: JsToolStore, workflow: WorkflowEngine,
 *   preview: LocalPreviewServer|null, mcp: McpHub, planner: ContextWindowPlanner,
 *   limits: ToolExecutionLimits, dangerGuard: DangerGuard, conversations: ConversationStore,
 *   registered: string[], failed: Array<{component:string, error:string}>
 * }}
 */
export function createRuntime({
  provider = null,
  workspaceRoot = process.cwd(),
  stateDir = null,
  approvalMode = 'risky',
  systemPrompt = DEFAULT_SYSTEM_PROMPT,
  onEvent,
  onApprovalRequest,
  maxIterations = 25,
  skillsRoot = null,
  enable = {},
  limits: limitOverrides = {},
  dangerAllowIds = [],
  preferTaskProvider = false,
  hostBridge = null,
  maxConcurrentTools = 4,
  maxSubagentConcurrency = 3,
  maxSubagentDepth = 1,
  subagentTimeoutMs = 8 * 60_000,
  heartbeatMs = 15_000,
  eventsCapacity = 500,
}) {
  const root = path.resolve(workspaceRoot);
  const state = stateDir ? path.resolve(stateDir) : null;
  const file = (name) => (state ? path.join(state, name) : null);
  const on = (key, fallback) => (enable[key] === undefined ? fallback : enable[key]);
  const registered = [];
  const failed = [];

  // —— 宿主桥 ——
  // bridge 由 host-stdio 在拿到 stdout writer 后 attach()；这里先把对象建好，
  // 让宿主能力工具持有同一个引用。
  const bridge = hostBridge instanceof HostBridge ? hostBridge : new HostBridge({ enabled: false });

  const registry = createDefaultTools({ workspaceRoot: root });

  // —— 宿主能力工具（#11 #12）——
  // 只有在应用真的接上反向通道时才注册：桌面/独立运行时不该看到一堆必然报错的工具。
  if (bridge.enabled) {
    for (const t of createHostTools({ bridge, workspaceRoot: root })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 审批 / 限制 / 危险命令 ——
  const approval = new ApprovalPolicy({ mode: approvalMode, onRequest: onApprovalRequest });
  const limits = new ToolExecutionLimits(limitOverrides);
  const dangerGuard = new DangerGuard({ allowIds: dangerAllowIds });

  // —— 用量账本 + 模型档位 ——
  const ledger = new TokenLedger({ persistPath: file('token-ledger.json') });
  const profiles = new ModelProfileStore({ persistPath: file('model-profiles.json') });
  if (on('usage', true)) {
    for (const t of createUsageTools({ ledger, profileStore: profiles })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 任务清单（多步任务的显式计划，按会话隔离）——
  if (on('todos', true)) {
    for (const t of createTodoTools({ persistPath: file('todos.json') })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 记忆图谱 ——
  const memory = new MemoryGraph({
    persistPath: file('memory-graph.json'),
    embedder: new EmbeddingService({ baseUrl: process.env.HAPPY_ACA_EMBED_BASE_URL || null, apiKey: process.env.HAPPY_ACA_EMBED_API_KEY || '' }),
  });
  if (on('memory', true)) {
    for (const t of createMemoryTools({ graph: memory })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 工作区：变更追踪 / 备份 / 模板 / 多工作区 ——
  const tracker = new WorkspaceChangeTracker({ workspaceRoot: root, stateDir: state });
  const backup = new WorkspaceBackupManager({ workspaceRoot: root, filter: tracker.filter });
  const scaffold = new ProjectScaffold({ workspaceRoot: root });
  const workspaces = new WorkspaceRegistry({ defaultRoot: root });
  if (on('workspace', true)) {
    for (const t of createWorkspaceTools({ tracker, backup, scaffold })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— Skill 包 ——
  const skills = new SkillManager({ registry, skillsRoot, onEvent });
  if (on('skills', true) && skillsRoot) {
    for (const t of createSkillTools({ skillsRoot, manager: skills })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 用户 JS 工具（沙箱） ——
  const jsTools = new JsToolStore({ registry, toolsDir: path.join(root, '.haca', 'tools'), workspaceRoot: root });
  if (on('jsTools', true)) {
    try {
      const { errors } = jsTools.loadAll();
      for (const e of errors) failed.push({ component: `jsTools.${e.file}`, error: e.error });
    } catch (err) {
      failed.push({ component: 'jsTools.loadAll', error: err.message });
    }
    for (const t of createJsToolTools({ store: jsTools })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 工作流引擎（复用同一个审批策略，不绕过人工确认） ——
  const workflow = new WorkflowEngine({ registry, approval, onEvent, persistPath: file('workflows.json') });
  if (on('workflow', true)) {
    for (const t of createWorkflowTools({ engine: workflow })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— 本地预览服务器（静态站点 + 热重载） ——
  let preview = null;
  if (on('preview', true)) {
    preview = new LocalPreviewServer({ root, injectLiveReload: true });
    for (const t of preview.tools({ workspaceRoot: root })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  // —— MCP 客户端：外部 server 的工具并进同一个 registry ——
  const mcp = new McpHub({ registry, persistPath: file('mcp-servers.json') });
  if (on('mcp', true) && mcp.configs.length) {
    mcp.connectAll()
      .then((out) => {
        for (const r of out) if (!r.ok) failed.push({ component: `mcp.${r.name}`, error: r.error });
      })
      .catch((err) => failed.push({ component: 'mcp.connectAll', error: err.message }));
  }

  // —— 上下文窗口 ——
  const planner = new ContextWindowPlanner();
  const conversations = new ConversationStore({ persistPath: file('conversations.json') });

  // —— Provider 选择：外部传入优先，否则按任务档位造一个 ——
  let resolvedProvider = provider;
  if (!resolvedProvider && preferTaskProvider) {
    const { provider: p } = profiles.providerForTask('chat');
    resolvedProvider = p;
  }

  // —— 可观测性 + 并发调度（装配前先建好，供 Router / Agent / 子 agent 共享）——
  // events 落盘到 stateDir/events.jsonl：无人值守时界面断连也留得下痕迹；
  // runTracker 从事件流派生活跃 run 视图，agent_status 直接读它。
  const events = new EventLog({ persistPath: file('events.jsonl'), capacity: eventsCapacity });
  const runTracker = new RunTracker();
  const scheduler = new ToolScheduler({ maxConcurrent: maxConcurrentTools });

  const agent = new Agent({
    provider: resolvedProvider,
    registry,
    workspaceRoot: root,
    approval,
    systemPrompt,
    onEvent,
    maxIterations,
    limits,
    dangerGuard,
    ledger,
    planner,
    changeTracker: tracker,
    // MCP 工具靠 ctx.mcp.get(serverName) 找客户端（见 mcp/client.js toHacaTool）；
    // skill 激活后其指令文本按轮注入 system prompt（见 skills.js activePrompts）。
    mcp,
    skills,
    scheduler,
    heartbeatMs,
  });

  // —— 并行子 agent ——
  // 必须在 Agent 之后建：runner 要拿主 agent 的 provider/registry/审批，
  // 事件出口动态读 agent.onEvent（Router 会替换它，不能在构造时抓快照）。
  let subagents = null;
  if (on('subagents', true)) {
    subagents = new SubagentRunner({
      parent: agent,
      store: null,
      maxConcurrent: maxSubagentConcurrency,
      maxDepth: maxSubagentDepth,
      timeoutMs: subagentTimeoutMs,
    });
    agent.subagents = subagents;
    for (const t of createSubagentTools({ runner: subagents })) {
      registry.register(t);
      registered.push(t.name);
    }
  }

  const store = new SessionStore({ persistPath: file('sessions.jsonl') });

  return {
    agent,
    registry,
    approval,
    store,
    bridge,
    ledger,
    profiles,
    memory,
    tracker,
    backup,
    scaffold,
    workspaces,
    skills,
    jsTools,
    workflow,
    preview,
    mcp,
    planner,
    limits,
    dangerGuard,
    conversations,
    registered,
    failed,
    events,
    runTracker,
    scheduler,
    subagents,
  };
}

export function createAgent({
  provider,
  workspaceRoot = process.cwd(),
  approvalMode = 'risky',
  systemPrompt = DEFAULT_SYSTEM_PROMPT,
  onEvent,
  maxIterations = 25,
  ...opts
}) {
  return new Agent({
    provider,
    registry: createDefaultTools({ workspaceRoot }),
    workspaceRoot,
    approval: new ApprovalPolicy({ mode: approvalMode }),
    systemPrompt,
    onEvent,
    maxIterations,
    ...opts,
  });
}

export function createRouter({ agent, store, runtime, token }) {
  return new AgentRouter({ agent, store: store ?? new SessionStore(), runtime, token });
}

export {
  Agent,
  AgentRouter,
  AnthropicProvider,
  ApprovalPolicy,
  ContextWindowPlanner,
  ConversationStore,
  DangerGuard,
  DEFAULT_SYSTEM_PROMPT,
  ENDPOINT_PRESETS,
  EmbeddingService,
  EventLog,
  GitIgnoreFilter,
  createGitTools,
  createTodoTools,
  createWebTools,
  HotStreamMerger,
  HostBridge,
  HostUnavailableError,
  JsToolError,
  JsToolStore,
  KeyedProvider,
  LocalPreviewServer,
  McpError,
  McpHttpClient,
  McpHub,
  McpServer,
  McpStdioClient,
  MCP_PROTOCOL_VERSION,
  MemoryGraph,
  MockProvider,
  ModelProfileStore,
  OllamaProvider,
  OpenAICompatibleProvider,
  PROJECT_TEMPLATES,
  ProjectScaffold,
  ProviderError,
  PERSONAS,
  PERSONA_IDS,
  DEFAULT_PERSONA,
  ResourceLocks,
  RunTracker,
  Semaphore,
  SessionStore,
  SkillManager,
  SubagentRunner,
  TASK_KINDS,
  TextStreamRevisionTracker,
  TokenLedger,
  ToolExecutionLimits,
  ToolRegistry,
  ToolScheduler,
  WorkflowEngine,
  WorkflowError,
  WorkspaceBackupManager,
  WorkspaceChangeTracker,
  WorkspaceRegistry,
  VERSION,
  WsConnection,
  WsJsonServer,
  cosine,
  costOf,
  createJsToolTools,
  createMemoryTools,
  createPreviewTools,
  createProviderFromPreset,
  createHostTools,
  createShellTool,
  createSkillTools,
  createSubagentTools,
  effectiveRisks,
  resolvePersona,
  createUsageTools,
  createWorkflowTools,
  createWorkspaceTools,
  decodeFrames,
  discoverSkills,
  encodeFrame,
  evalCondition,
  loadJsTool,
  loadSkillManifest,
  localEmbedding,
  mimeFor,
  mockScriptFromJSON,
  mockText,
  mockToolCall,
  normalizeLocalBaseUrl,
  presetById,
  presetOptions,
  priceFor,
  readGitIgnore,
  renderTemplate,
  serveOverStdio,
};
