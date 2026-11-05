// MCP 协议服务端（能力 #23 的另一半：HACA 作为 MCP server 被外部调用）。
//
// 用途：别的 agent / IDE（Claude Desktop、Cursor 等）可以把 HACA 的
// workspace 工具通过 MCP 拉过去用。传输用 stdio：外部宿主 spawn 本进程，
// 逐行读写 JSON-RPC 2.0（与 client.js 的 stdio 客户端对称）。
//
// 形态：一个极小的 JSON-RPC 分发器 + ToolRegistry 适配。
//   initialize -> protocolVersion / capabilities / serverInfo
//   tools/list  -> registry.schemas() 映射成 MCP 工具
//   tools/call  -> registry.execute() 结果包成 MCP content blocks
// 不含业务逻辑，全部委托给已有的 ToolRegistry。

import { createInterface } from 'node:readline';
import { VERSION, MCP_PROTOCOL_VERSION } from '../version.js';

const PROTOCOL_VERSION = MCP_PROTOCOL_VERSION;

const SERVER_INFO = { name: 'happy_ACA', version: VERSION };

/** MCP content block 化：HACA 工具返回纯文本，直接包成 text block。 */
function toContent(output) {
  return [{ type: 'text', text: String(output ?? '') }];
}

export class McpServer {
  constructor({ registry, workspaceRoot = process.cwd(), onNotification, approval = null } = {}) {
    if (!registry) throw new Error('McpServer needs a ToolRegistry');
    this.registry = registry;
    this.workspaceRoot = workspaceRoot;
    this.onNotification = onNotification || (() => {});
    // 外部 MCP 客户端（Claude Desktop / Cursor）通过 tools/call 驱动本进程的工具时，
    // 必须过同一条审批策略——否则「所有写操作走人工确认」的承诺在这里被绕开。
    // 没配 onRequest 时 read 类放行、write/exec 一律拒绝（不静默执行）。
    this.approval = approval;
    this.initialized = false;
    this._callLog = [];
  }

  /**
   * 处理一个 JSON-RPC 请求对象，返回响应对象（或 null 表示 notification）。
   * 与 client.js 解耦：这里不关心传输，输入输出都是普通对象。
   */
  async handle(request) {
    const { id, method, params = {} } = request || {};
    const isNotification = id === undefined || id === null;

    const reply = (result) => (isNotification ? null : { jsonrpc: '2.0', id, result });
    const fail = (code, message) => (isNotification ? null : { jsonrpc: '2.0', id, error: { code, message } });

    try {
      switch (method) {
        case 'initialize':
          this.initialized = true;
          return reply({
            protocolVersion: PROTOCOL_VERSION,
            capabilities: { tools: { listChanged: false }, logging: {} },
            serverInfo: SERVER_INFO,
            instructions:
              'happy_ACA workspace tools. Every path is confined to the workspace root. ' +
              'Use workspace_overview first to orient.',
          });

        case 'notifications/initialized':
          this.onNotification('initialized', params);
          return null;

        case 'ping':
          return reply({});

        case 'tools/list':
          return reply({
            tools: this.registry.list().map((t) => ({
              name: t.name,
              description: t.description,
              inputSchema: t.parameters,
              ...(t.risk ? { annotations: { readOnlyHint: t.risk === 'read' } } : {}),
            })),
          });

        case 'tools/call': {
          const name = params?.name;
          if (!name) return fail(-32602, 'tools/call requires params.name');
          const tool = this.registry.get(name);
          if (!tool) return fail(-32602, `unknown tool: ${name}`);
          // 审批：与聊天/工作流完全同路。没配审批策略 = 嵌入方明确选择放开
          // （自行负责）；配了就走策略，审批器抛错（没人能答）时按拒绝处理。
          let approved = true;
          if (this.approval) {
            try {
              approved = await this.approval.check(tool, params.arguments ?? {}, { workspaceRoot: this.workspaceRoot });
            } catch {
              approved = false;
            }
          }
          if (!approved) {
            this._callLog.push({ name, ok: false, ms: 0, denied: true });
            if (this._callLog.length > 500) this._callLog.shift();
            return reply({
              content: [{ type: 'text', text: 'DENIED: the approval policy rejected this tool call.' }],
              isError: true,
            });
          }
          const started = Date.now();
          const result = await this.registry.execute(name, params.arguments ?? {}, {
            workspaceRoot: this.workspaceRoot,
          });
          this._callLog.push({ name, ok: result.ok, ms: Date.now() - started });
          if (this._callLog.length > 500) this._callLog.shift();
          return reply({
            content: toContent(result.output),
            isError: !result.ok,
          });
        }

        case 'resources/list':
          return reply({ resources: [] });

        case 'prompts/list':
          return reply({ prompts: [] });

        default:
          return fail(-32601, `method not found: ${method}`);
      }
    } catch (err) {
      return fail(-32603, `internal error: ${err?.message || err}`);
    }
  }

  /** 最近调用统计，供 host-cli / 调试用。 */
  stats() {
    const ok = this._callLog.filter((c) => c.ok).length;
    return { calls: this._callLog.length, ok, failed: this._callLog.length - ok };
  }
}

/**
 * stdio 循环：一行一个 JSON-RPC 请求，一行一个响应。
 * 供 host-cli `mcp-serve` 子命令与 Android 壳（node 进程）复用。
 */
export function serveOverStdio(server, { input = process.stdin, output = process.stdout } = {}) {
  const rl = createInterface({ input, terminal: false });
  rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    void (async () => {
      let request;
      try {
        request = JSON.parse(trimmed);
      } catch {
        output.write(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }) + '\n');
        return;
      }
      const response = await server.handle(request);
      if (response) output.write(JSON.stringify(response) + '\n');
    })();
  });
  rl.on('close', () => process.exit(0));
  return rl;
}
