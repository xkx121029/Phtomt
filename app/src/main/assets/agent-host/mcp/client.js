// MCP 协议客户端（能力 #23，v0.5 核心）。
//
// 实现 Model Context Protocol 的一个可用子集：
//   - stdio 传输：spawn 一个 MCP server 进程，走 JSON-RPC over stdio
//   - streamable HTTP 传输：POST {url} + SSE 响应（MCP 2025-03 之后的形态）
// 握手：initialize -> notifications/initialized -> tools/list
// 调用：tools/call
//
// MCP server 自己声明的工具会以 mcp_<serverName>_<toolName> 的名字注册进
// HACA 的 ToolRegistry，所以 agent 侧零改动。

// MCP 协议的实现参考规范文档；HACA 只做客户端侧，不做 server 框架。
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { VERSION, MCP_PROTOCOL_VERSION } from '../version.js';
import { atomicWriteFileSync } from '../util/atomic-write.js';

/** JSON-RPC 2.0 错误码。 */
const PARSE_ERROR = -32700;
const METHOD_NOT_FOUND = -32601;

let idSeq = 0;

export class McpError extends Error {
  constructor(message, { code, data } = {}) {
    super(message);
    this.name = 'McpError';
    this.code = code;
    this.data = data;
  }
}

/** 把 MCP 的 inputSchema 包装成 HACA 工具形态。 */
function toHacaTool(serverName, tool) {
  return {
    name: `mcp_${serverName}_${tool.name}`,
    description: tool.description || `MCP tool ${tool.name} from server ${serverName}`,
    risk: tool.annotations?.readOnlyHint ? 'read' : 'exec',
    parameters: tool.inputSchema && tool.inputSchema.type === 'object'
      ? tool.inputSchema
      : { type: 'object', properties: {}, additionalProperties: true },
    _mcp: { server: serverName, tool: tool.name },
    async execute(args, ctx) {
      const client = ctx?.mcp?.get?.(serverName);
      if (!client) throw new Error(`MCP server "${serverName}" is not connected`);
      const result = await client.callTool(tool.name, args);
      // MCP 的返回是 content block 数组，摊平成文本给模型
      if (result?.isError) throw new Error(flattenContent(result.content) || 'MCP tool reported an error');
      return flattenContent(result.content) || '(no output)';
    },
  };
}

function flattenContent(content) {
  if (!Array.isArray(content)) return String(content ?? '');
  return content
    .map((c) => {
      if (c.type === 'text') return c.text;
      if (c.type === 'resource') return `[resource ${c.resource?.uri}]\n${c.resource?.text || ''}`;
      return JSON.stringify(c);
    })
    .join('\n');
}

/**
 * stdio 传输的 MCP 客户端。
 *
 *   const client = new McpStdioClient({
 *     name: 'filesystem',
 *     command: 'npx',
 *     args: ['-y', '@modelcontextprotocol/server-filesystem', '/data'],
 *   });
 *   await client.initialize();
 *   const tools = await client.listTools();
 */
export class McpStdioClient {
  constructor({ name, command, args = [], env = {}, cwd }) {
    if (!name || !command) throw new Error('McpStdioClient: name and command are required');
    this.name = name;
    this.command = command;
    this.args = args;
    this.env = env;
    this.cwd = cwd;
    this.child = null;
    this.pending = new Map(); // id -> {resolve, reject}
    this.buffer = '';
    this.serverInfo = null;
    this.capabilities = null;
    this.dead = false; // spawn 失败或进程退出后置位
  }

  async initialize() {
    this.child = spawn(this.command, this.args, {
      cwd: this.cwd,
      env: { ...process.env, ...this.env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.child.stdout.on('data', (chunk) => this.#onData(chunk));
    this.child.stderr.on('data', () => {
      /* MCP server 的日志走 stderr，不属于协议；静默丢弃 */
    });
    // 必须挂 error 监听：有一个配错的 server（比如命令不存在）就会 ENOENT，
    // 没有监听时 Node 会把它升级成未捕获异常，把整个 host 进程带走。
    this.child.on('error', (err) => {
      this.dead = true;
      for (const [, p] of this.pending) p.reject(new McpError(`cannot start "${this.command}": ${err.message}`));
      this.pending.clear();
    });
    this.child.on('exit', (code) => {
      for (const [, p] of this.pending) p.reject(new McpError(`server exited (code ${code})`));
      this.pending.clear();
    });

    const res = await this.#request('initialize', {
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: { roots: { listChanged: false }, sampling: {} },
      clientInfo: { name: 'happy_ACA', version: VERSION },
    });
    this.serverInfo = res.serverInfo;
    this.capabilities = res.capabilities;
    this.#notify('notifications/initialized', {});
    return res;
  }

  async listTools() {
    const res = await this.#request('tools/list', {});
    return res.tools || [];
  }

  async callTool(name, args = {}) {
    return this.#request('tools/call', { name, arguments: args });
  }

  async listResources() {
    const res = await this.#request('resources/list', {});
    return res.resources || [];
  }

  async readResource(uri) {
    return this.#request('resources/read', { uri });
  }

  close() {
    try {
      this.child?.kill();
    } catch {
      /* already gone */
    }
    this.child = null;
  }

  #onData(chunk) {
    this.buffer += chunk.toString('utf8');
    let nl;
    while ((nl = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, nl).trim();
      this.buffer = this.buffer.slice(nl + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        continue; // 半行/非协议输出
      }
      if (msg.id != null && this.pending.has(msg.id)) {
        const p = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new McpError(msg.error.message, { code: msg.error.code, data: msg.error.data }));
        else p.resolve(msg.result);
      }
      // notification（无 id）：HACA 客户端暂不消费，忽略
    }
  }

  #notify(method, params) {
    this.child?.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');
  }

  #request(method, params, timeoutMs = 60000) {
    const id = ++idSeq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new McpError(`timeout on ${method}`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => {
          clearTimeout(timer);
          resolve(v);
        },
        reject: (e) => {
          clearTimeout(timer);
          reject(e);
        },
      });
      try {
        this.child?.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
      } catch (err) {
        // 管道已断（server 启动失败/已退出）：立刻失败，别让调用方等满 60s
        this.pending.delete(id);
        clearTimeout(timer);
        reject(new McpError(`cannot send ${method}: ${err.message}`));
      }
    });
  }
}

/**
 * streamable HTTP 传输的 MCP 客户端（远程 server）。
 * MCP 2025-03 之后推荐形态：POST 一个 JSON-RPC 请求，响应是 SSE 流。
 */
export class McpHttpClient {
  constructor({ name, url, headers = {}, fetchImpl = fetch }) {
    if (!name || !url) throw new Error('McpHttpClient: name and url are required');
    this.name = name;
    this.url = url.replace(/\/+$/, '');
    this.headers = { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...headers };
    this.fetchImpl = fetchImpl;
    this.sessionId = null;
  }

  async initialize() {
    const res = await this.#rpc('initialize', {
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: 'happy_ACA', version: VERSION },
    });
    await this.#notify('notifications/initialized', {});
    return res;
  }

  async listTools() {
    const res = await this.#rpc('tools/list', {});
    return res.tools || [];
  }

  async callTool(name, args = {}) {
    return this.#rpc('tools/call', { name, arguments: args });
  }

  close() {
    this.sessionId = null;
  }

  async #rpc(method, params) {
    const res = await this.fetchImpl(this.url, {
      method: 'POST',
      headers: this.headers,
      body: JSON.stringify({ jsonrpc: '2.0', id: ++idSeq, method, params }),
    });
    const sid = res.headers.get('mcp-session-id');
    if (sid) this.sessionId = sid;
    if (!res.ok) throw new McpError(`HTTP ${res.status} from MCP server`);
    const text = await res.text();
    // 服务端可能直接回 JSON，也可能包一层 SSE
    if (text.startsWith('data:')) {
      const line = text.split('\n').find((l) => l.startsWith('data:'));
      return JSON.parse(line.slice(5).trim());
    }
    return JSON.parse(text);
  }

  async #notify(method, params) {
    await this.fetchImpl(this.url, {
      method: 'POST',
      headers: { ...this.headers, ...(this.sessionId ? { 'mcp-session-id': this.sessionId } : {}) },
      body: JSON.stringify({ jsonrpc: '2.0', method, params }),
    }).catch(() => {
      /* notification 失败不影响主流程 */
    });
  }
}

/**
 * MCP 连接池：管理多个 server，把它们声明的工具统一注册进 ToolRegistry。
 * 这就是「v0.5 MCP Hub」的最小可用形态——后续可扩展到受控端 App。
 */
export class McpHub {
  #file;

  constructor({ registry, persistPath } = {}) {
    this.registry = registry;
    this.clients = new Map(); // name -> client
    this.configs = [];
    this.#file = persistPath || null;
    if (this.#file) this.#load();
  }

  /**
   * 取一个已连接的客户端。工具侧（toHacaTool.execute）靠 ctx.mcp.get(serverName)
   * 找到自己的连接——没有这个方法，所有 mcp_* 工具都会报 "not connected"。
   */
  get(name) {
    return this.clients.get(name) ?? null;
  }

  /** 登记一个 server 配置（连不连另说）。 */
  addServer(config) {
    if (!config?.name) throw new Error('mcp server config needs a name');
    const rec = config.transport === 'http'
      ? { transport: 'http', name: config.name, url: config.url, headers: config.headers || {} }
      : { transport: 'stdio', name: config.name, command: config.command, args: config.args || [], env: config.env || {}, cwd: config.cwd };
    this.configs = [...this.configs.filter((c) => c.name !== rec.name), rec];
    this.#save();
    return rec;
  }

  removeServer(name) {
    this.configs = this.configs.filter((c) => c.name !== name);
    this.disconnect(name);
    this.#save();
  }

  listServers() {
    return this.configs.map((c) => ({
      ...c,
      connected: this.clients.has(c.name),
      tools: this.clients.get(c.name)?._tools?.length ?? 0,
    }));
  }

  /** 连接并拉取工具。失败抛错（调用方决定是否降级）。 */
  async connect(name) {
    const config = this.configs.find((c) => c.name === name);
    if (!config) throw new Error(`unknown MCP server: ${name}`);
    if (this.clients.has(name)) return this.clients.get(name);

    const client =
      config.transport === 'http'
        ? new McpHttpClient({ name, url: config.url, headers: config.headers })
        : new McpStdioClient({ name, command: config.command, args: config.args, env: config.env, cwd: config.cwd });

    await client.initialize();
    const tools = await client.listTools();
    client._tools = tools;

    for (const t of tools) {
      this.registry?.register(toHacaTool(name, t));
    }
    this.clients.set(name, client);
    return client;
  }

  /** 连接所有已配置 server；单个失败不影响其他。 */
  async connectAll() {
    const out = [];
    for (const c of this.configs) {
      try {
        await this.connect(c.name);
        out.push({ name: c.name, ok: true });
      } catch (err) {
        out.push({ name: c.name, ok: false, error: err.message });
      }
    }
    return out;
  }

  disconnect(name) {
    const c = this.clients.get(name);
    if (c) {
      for (const t of c._tools || []) this.registry?.unregister?.(`mcp_${name}_${t.name}`);
      c.close?.();
      this.clients.delete(name);
    }
  }

  disconnectAll() {
    for (const name of [...this.clients.keys()]) this.disconnect(name);
  }

  #load() {
    try {
      this.configs = JSON.parse(fs.readFileSync(this.#file, 'utf8')).servers || [];
    } catch {
      /* fresh */
    }
  }

  #save() {
    if (!this.#file) return;
    try {
      atomicWriteFileSync(this.#file, JSON.stringify({ servers: this.configs }, null, 2));
    } catch {
      /* best effort */
    }
  }
}
