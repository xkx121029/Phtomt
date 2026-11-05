// JS 工具运行时（能力 #22，参考 Operit JsEngine 的形态，独立实现）。
//
// 痛点：HACA 的内置工具是预编译的 Node 代码，agent 遇到一次性需求
// （“把这个目录下所有 json 合并”“按第二列排序”）要么改 C++ 级重编译，
// 要么写一长串 shell。让 agent 当场写一个 JS 小工具、立即注册、立即复用，
// 是把 agent 从「改代码的人」变成「造工具的人」的关键一步。
//
// 实现取舍：
//   - Node 侧没有 QuickJS，用 node:vm 做沙箱（Host 已经是 Node 运行时）
//   - 沙箱只暴露白名单 API：fs 的读写（限定 workspace）、path、fetch、
//     JSON、console。不给 require、process、child_process
//   - 工具代码以 CJS 形态在 vm 里跑（module/exports/require 由沙箱提供假的），
//     这样一份工具文件既能被 node:vm 跑，也能被开发者本地 node 跑
//   - 超时：execute 在 vm 上下文里跑（vm timeout 能杀掉同步死循环），
//     异步部分再用 Promise.race 兜底（挂住的 fetch 不会拖死 agent）
//
// 诚实声明（别被"沙箱"两个字骗了）：
//   node:vm 不是安全边界。宿主的 Buffer / 结构化 API 被注入 context 后，
//   `Buffer.constructor('return process')()` 这类跨 realm 桥可以拿到真 process。
//   这里的隔离是「防止工具无意间搞坏 host」，不是「防止恶意工具越权」——
//   恶意代码本来就能通过 agent 已有的 run_command 拿到同样的权限。
//   要真 containment 需要独立进程/worker，见 ROADMAP。

import vm from 'node:vm';
import fs from 'node:fs';
import path from 'node:path';
import { resolveWithin } from './tools/fs-tools.js';

const SANDBOX_APIS = ['JSON', 'Math', 'Date', 'Object', 'Array', 'String', 'Number', 'Boolean', 'RegExp', 'Map', 'Set', 'Promise', 'parseInt', 'parseFloat', 'isNaN', 'encodeURIComponent', 'decodeURIComponent', 'TextEncoder', 'TextDecoder', 'structuredClone'];

export class JsToolError extends Error {
  constructor(message, { cause } = {}) {
    super(message);
    this.name = 'JsToolError';
    this.cause = cause;
  }
}

/**
 * 在沙箱里执行一段 JS 工具源码。
 *
 * @param {string} code      CJS 模块源码：module.exports = { name, description, parameters, execute }
 * @param {Object} opts
 * @param {string} opts.workspaceRoot  沙箱 fs 的根
 * @param {number} opts.timeoutMs      默认 5000
 * @param {Function} opts.fetchImpl    沙箱内的 fetch
 * @returns {{ name, description, parameters, risk, execute }}
 */
export function loadJsTool(code, { workspaceRoot, timeoutMs = 5000, fetchImpl = fetch } = {}) {
  const root = workspaceRoot ? path.resolve(workspaceRoot) : null;
  // execute 每次调用的超时（默认与编译超时一致，可调大）。
  const execTimeoutMs = Math.max(1000, timeoutMs);

  // 沙箱 fs：只读目录列表 + 限定 workspace 内的读写
  const sandboxFs = {
    readFileSync(p, enc) {
      const abs = root ? resolveWithin(root, p) : path.resolve(String(p));
      return fs.readFileSync(abs, enc);
    },
    writeFileSync(p, data, enc) {
      const abs = root ? resolveWithin(root, p) : path.resolve(String(p));
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      fs.writeFileSync(abs, data, enc);
    },
    existsSync(p) {
      try {
        const abs = root ? resolveWithin(root, p) : path.resolve(String(p));
        return fs.existsSync(abs);
      } catch {
        return false;
      }
    },
    readdirSync(p) {
      const abs = root ? resolveWithin(root, p ?? '.') : path.resolve(String(p ?? '.'));
      return fs.readdirSync(abs);
    },
    statSync(p) {
      const abs = root ? resolveWithin(root, p) : path.resolve(String(p));
      const st = fs.statSync(abs);
      return { size: st.size, mtimeMs: st.mtimeMs, isFile: st.isFile(), isDirectory: st.isDirectory() };
    },
    mkdirSync(p) {
      const abs = root ? resolveWithin(root, p) : path.resolve(String(p));
      fs.mkdirSync(abs, { recursive: true });
    },
  };

  const logs = [];
  const sandboxConsole = {
    log: (...a) => logs.push(a.map(stringify).join(' ')),
    warn: (...a) => logs.push(`[warn] ${a.map(stringify).join(' ')}`),
    error: (...a) => logs.push(`[error] ${a.map(stringify).join(' ')}`),
  };

  const sandbox = {
    module: { exports: {} },
    exports: {},
    console: sandboxConsole,
    fetch: fetchImpl,
    setTimeout: (_fn, _ms) => {
      throw new JsToolError('setTimeout is not available inside a tool; use async/await instead');
    },
    Buffer,
  };
  // JS 内建对象白名单
  // 注意命名：这个 vm context 与工具 execute(args, ctx) 的 ctx 形参不是一回事，
  // 写成 ctx 会被形参遮蔽，vm.runInContext 拿到普通对象直接报错。
  const vmContext = vm.createContext(sandbox);
  for (const api of SANDBOX_APIS) {
    if (api in globalThis) sandbox[api] = globalThis[api];
  }
  // 沙箱专用 API（不进全局，通过注入的参数暴露）
  sandbox.__haca = { fs: sandboxFs, path, logs };

  const wrapper = `(function(module, exports, require, __haca, console, fetch, Buffer) {\n${code}\n})`;
  let fn;
  try {
    fn = vm.runInContext(wrapper, vmContext, { timeout: timeoutMs });
  } catch (err) {
    throw new JsToolError(`failed to compile tool: ${err.message}`, { cause: err });
  }

  // 假的 require：只允许少量内置模块，其余一律拒绝
  const fakeRequire = (id) => {
    if (id === 'fs') return sandboxFs;
    if (id === 'path') return path;
    throw new JsToolError(`require("${id}") is not allowed in a sandboxed tool`);
  };

  let exported;
  try {
    fn(sandbox.module, sandbox.module.exports, fakeRequire, sandbox.__haca, sandboxConsole, fetchImpl, Buffer);
    exported = sandbox.module.exports;
  } catch (err) {
    throw new JsToolError(`failed to evaluate tool: ${err.message}`, { cause: err });
  }

  const tool = exported?.default || exported;
  if (!tool?.name || typeof tool?.execute !== 'function') {
    throw new JsToolError('tool must export { name, description, parameters, execute }');
  }
  if (!tool.parameters || tool.parameters.type !== 'object') {
    throw new JsToolError(`tool ${tool.name}: parameters must be a JSON Schema of type object`);
  }

  return {
    name: String(tool.name),
    description: String(tool.description || ''),
    parameters: tool.parameters,
    risk: tool.risk === 'read' ? 'read' : 'exec',
    _jsTool: true,
    async execute(args, ctx) {
      // 双保险超时：
      //   1. vm 上下文内执行 —— vm 的 timeout 能中断同步死循环（while(true){}）
      //   2. Promise.race —— 异步挂死（fetch 不返回）也能把调用收回来
      // 任何一路超时都抛 JsToolError，agent 回合继续，不会把 host 永久挂住。
      let timer;
      try {
        // 参数用引用传进 context（不能拼进源码——那是注入面），且必须在
        // runInContext 之前就位，因为代码是立即执行的。
        sandbox.toolArgs = args ?? {};
        sandbox.toolCtx = ctx;
        const running = vm.runInContext(
          'module.exports.execute(toolArgs, toolCtx)',
          vmContext,
          { timeout: execTimeoutMs, displayErrors: true },
        );
        const guarded = await Promise.race([
          (async () => running)(),
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(new JsToolError(`tool ${tool.name} timed out after ${execTimeoutMs}ms`)), execTimeoutMs + 250);
          }),
        ]);
        return guarded == null ? '(ok)' : String(guarded);
      } catch (err) {
        if (err instanceof JsToolError) throw err;
        throw new JsToolError(`tool ${tool.name} threw: ${err.message}`, { cause: err });
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

function stringify(v) {
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

/**
 * 动态 JS 工具注册表：agent 调 create_js_tool 落一个 .js 文件到
 * <workspace>/.haca/tools/，加载后注册进 ToolRegistry；重启后自动恢复。
 */
export class JsToolStore {
  constructor({ registry, toolsDir, workspaceRoot, fetchImpl } = {}) {
    if (!registry || !toolsDir) throw new Error('JsToolStore needs registry and toolsDir');
    this.registry = registry;
    this.toolsDir = toolsDir;
    this.workspaceRoot = workspaceRoot || null;
    this.fetchImpl = fetchImpl;
    this.loaded = new Map(); // name -> file
  }

  /** 扫描 toolsDir，把每个 .js 加载注册。返回 { loaded, errors }。 */
  loadAll() {
    const loaded = [];
    const errors = [];
    let files = [];
    try {
      files = fs.readdirSync(this.toolsDir).filter((f) => f.endsWith('.js'));
    } catch {
      return { loaded, errors };
    }
    for (const file of files) {
      try {
        const code = fs.readFileSync(path.join(this.toolsDir, file), 'utf8');
        const tool = loadJsTool(code, { workspaceRoot: this.workspaceRoot, fetchImpl: this.fetchImpl });
        this.registry.register({ ...tool, _source: `jstool:${file}` });
        this.loaded.set(tool.name, file);
        loaded.push(tool.name);
      } catch (err) {
        errors.push({ file, error: err.message });
      }
    }
    return { loaded, errors };
  }

  /** 新增一个工具：写文件 + 加载 + 注册。 */
  create({ name, code }) {
    if (!/^[a-z][a-z0-9_]*$/i.test(String(name || ''))) {
      throw new JsToolError('tool name must be alphanumeric/underscore');
    }
    const file = `${name}.js`;
    // 先编译验证，坏代码不落盘
    const tool = loadJsTool(code, { workspaceRoot: this.workspaceRoot, fetchImpl: this.fetchImpl });
    fs.mkdirSync(this.toolsDir, { recursive: true });
    fs.writeFileSync(path.join(this.toolsDir, file), code, 'utf8');
    this.registry.register({ ...tool, _source: `jstool:${file}` });
    this.loaded.set(tool.name, file);
    return { name: tool.name, file };
  }

  remove(name) {
    const file = this.loaded.get(name);
    if (!file) return { removed: false };
    this.registry.unregister(name);
    try {
      fs.rmSync(path.join(this.toolsDir, file), { force: true });
    } catch {
      /* best effort */
    }
    this.loaded.delete(name);
    return { removed: true, file };
  }

  list() {
    return [...this.loaded.entries()].map(([name, file]) => ({ name, file }));
  }
}

/** create_js_tool 工具：让 agent 当场造工具。 */
export function createJsToolTools({ store }) {
  return [
    {
      name: 'create_js_tool',
      description:
        'Create a new JS tool that becomes callable immediately (and after restart). ' +
        'The code is a CommonJS module: module.exports = { name, description, parameters, risk?, execute(args, ctx) }. ' +
        'Inside execute you have require("fs")/require("path") scoped to the workspace, plus fetch and console. ' +
        'Use this for one-off transformations instead of long shell pipelines.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          name: { type: 'string', description: 'Tool name, [a-zA-Z0-9_], becomes the callable tool name' },
          code: { type: 'string', description: 'CommonJS module source' },
        },
        required: ['name', 'code'],
      },
      execute({ name, code }) {
        const created = store.create({ name, code });
        return `created tool "${created.name}" (${created.file}); call it as ${created.name}`;
      },
    },
    {
      name: 'list_js_tools',
      description: 'List JS tools created with create_js_tool.',
      risk: 'read',
      parameters: { type: 'object', properties: {} },
      execute() {
        const list = store.list();
        return list.length ? list.map((t) => `${t.name} (${t.file})`).join('\n') : '(no custom JS tools)';
      },
    },
    {
      name: 'remove_js_tool',
      description: 'Delete a JS tool created with create_js_tool.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: { name: { type: 'string' } },
        required: ['name'],
      },
      execute({ name }) {
        return JSON.stringify(store.remove(name));
      },
    },
  ];
}
