// 本地 Web 服务器（能力 #20 的 Node 侧实现：静态预览 + 热重载提示）。
//
// 为什么放在 agent-core 而不是 Android：HACA 的 agent 就跑在手机上的 Node 里，
// 一个几十行的 http server 足够预览静态站点/前端项目，而 WebView 只需要
// 访问 http://127.0.0.1:<port>。这样预览能力不依赖任何原生代码。
//
// 安全：只服务 workspaceRoot 内的文件（防目录穿越），默认只监听 127.0.0.1。

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { resolveWithin } from './tools/fs-tools.js';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
  '.wasm': 'application/wasm',
  '.map': 'application/json; charset=utf-8',
};

export function mimeFor(filePath) {
  return MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
}

/** 目录请求 -> index.html 候选列表。 */
function indexCandidates(relPath) {
  const base = relPath === '/' ? '/index.html' : `${relPath.replace(/\/$/, '')}/index.html`;
  return [base, `${base.replace(/index\.html$/, '')}README.md`];
}

export class LocalPreviewServer {
  /**
   * @param {Object} opts
   * @param {string} opts.root             要服务的目录（通常 = workspaceRoot）
   * @param {string[]} opts.mounts         [{ prefix: '/app', dir: 'public' }] 把子目录挂到虚拟路径
   * @param {boolean} opts.injectLiveReload 给 HTML 注入热重载脚本（开发用）
   */
  constructor({ root, mounts = [], injectLiveReload = true, onRequest } = {}) {
    if (!root) throw new Error('LocalPreviewServer needs a root');
    this.root = path.resolve(root);
    this.mounts = mounts;
    this.injectLiveReload = injectLiveReload;
    this.onRequest = onRequest || (() => {});
    this.server = null;
    this.watches = new Map(); // file -> fs.FSWatcher
    this.clients = new Set(); // SSE 客户端
    this.port = 0;
  }

  /** 解析 URL 路径 -> 绝对文件路径。越界返回 null。 */
  resolveFile(urlPath) {
    let p = decodeURIComponent(urlPath.split('?')[0].split('#')[0]);
    if (p === '/' || p === '') p = '/index.html';

    for (const m of this.mounts) {
      if (m.prefix === '/' || p === m.prefix || p.startsWith(`${m.prefix}/`)) {
        const rest = p === m.prefix ? '/' : p.slice(m.prefix.length);
        const abs = path.resolve(this.root, m.dir, `.${rest}`);
        return inside(abs, this.root) ? abs : null;
      }
    }
    const abs = path.resolve(this.root, `.${p}`);
    return inside(abs, this.root) ? abs : null;
  }

  async start({ port = 0, host = '127.0.0.1' } = {}) {
    if (this.server) return this.url();
    this.server = http.createServer((req, res) => {
      void this.#handle(req, res);
    });
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(port, host, () => resolve());
    });
    const addr = this.server.address();
    this.port = typeof addr === 'object' && addr ? addr.port : port;
    this.#startWatcher();
    return this.url();
  }

  url(subPath = '/') {
    return `http://127.0.0.1:${this.port}${subPath.startsWith('/') ? subPath : `/${subPath}`}`;
  }

  /** 请求日志（给 agent 汇报“预览服务已就绪”用）。 */
  requests() {
    return this._requests || [];
  }

  async #handle(req, res) {
    const urlPath = req.url || '/';

    // 热重载 SSE 通道
    if (urlPath === '/__haca_live') {
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      res.write(': connected\n\n');
      this.clients.add(res);
      req.on('close', () => this.clients.delete(res));
      return;
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { 'content-type': 'text/plain' });
      res.end('method not allowed');
      return;
    }

    let file = this.resolveFile(urlPath);
    if (!file) {
      res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('403 forbidden: path escapes preview root');
      return;
    }

    let stat = statOrNull(file);
    if (stat?.isDirectory()) {
      file = indexCandidates(urlPath)
        .map((c) => this.resolveFile(c))
        .find((c) => c && statOrNull(c)?.isFile());
      stat = file ? statOrNull(file) : null;
    }

    if (!file || !stat) {
      // SPA 回退：非 html 请求不兜底，避免把 404 变成奇怪的内容
      if (!urlPath.endsWith('.html') && !path.extname(urlPath)) {
        file = this.resolveFile('/index.html');
        stat = file ? statOrNull(file) : null;
      }
      if (!file || !stat) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end(`404 not found: ${urlPath}`);
        this.#log(urlPath, 404);
        return;
      }
    }

    const ext = path.extname(file).toLowerCase();
    const isHtml = ext === '.html' || ext === '.htm';
    const headers = {
      'content-type': mimeFor(file),
      'cache-control': 'no-store',
      'content-length': stat.size,
    };

    if (isHtml && this.injectLiveReload) {
      let html;
      try {
        html = fs.readFileSync(file, 'utf8');
      } catch {
        res.writeHead(500, { 'content-type': 'text/plain' });
        res.end('500 cannot read');
        return;
      }
      const injected = injectLiveReloadScript(html);
      const buf = Buffer.from(injected, 'utf8');
      res.writeHead(200, { ...headers, 'content-length': buf.length });
      res.end(req.method === 'HEAD' ? undefined : buf);
      this.#log(urlPath, 200);
      this.onRequest({ url: urlPath, file, status: 200 });
      return;
    }

    res.writeHead(200, headers);
    if (req.method === 'HEAD') res.end();
    else fs.createReadStream(file).pipe(res);
    this.#log(urlPath, 200);
    this.onRequest({ url: urlPath, file, status: 200 });
  }

  #log(url, status) {
    this._requests = [...(this._requests || []), { url, status, at: Date.now() }].slice(-200);
  }

  /** 监听被服务目录的文件变化，推 SSE 通知浏览器自动刷新。 */
  #startWatcher() {
    if (this.watching) return;
    this.watching = true;
    try {
      const watcher = fs.watch(this.root, { recursive: true }, (_event, filename) => {
        if (!filename) return;
        if (filename.includes('node_modules') || filename.includes('.git')) return;
        this.notifyReload(path.basename(String(filename)));
      });
      watcher.on('error', () => {
        /* 某些平台不支持 recursive watch，降级为不热重载 */
        this.watching = false;
      });
      this.rootWatcher = watcher;
    } catch {
      this.watching = false;
    }
  }

  notifyReload(what = '') {
    const payload = `data: ${JSON.stringify({ type: 'reload', what, at: Date.now() })}\n\n`;
    for (const c of this.clients) {
      try {
        c.write(payload);
      } catch {
        this.clients.delete(c);
      }
    }
  }

  async stop() {
    for (const c of this.clients) {
      try {
        c.end();
      } catch {
        /* ignore */
      }
    }
    this.clients.clear();
    try {
      this.rootWatcher?.close();
    } catch {
      /* ignore */
    }
    this.watching = false;
    if (!this.server) return;
    await new Promise((resolve) => this.server.close(resolve));
    this.server = null;
    this.port = 0;
  }

  /** 启动服务并注册成 agent 工具（preview_start / preview_stop / preview_url）。 */
  tools({ workspaceRoot } = {}) {
    const root = workspaceRoot || this.root;
    const self = this;
    return [
      {
        name: 'preview_start',
        description:
          'Start a local static web server for the workspace and return its URL. Use it to preview HTML/frontend projects inside the app WebView. ' +
          'Mount a subdirectory with { mounts: [{ prefix, dir }] }.',
        risk: 'exec',
        parameters: {
          type: 'object',
          properties: {
            port: { type: 'integer', description: 'Port, 0 = auto (default 0)' },
            dir: { type: 'string', description: 'Directory to serve, relative to workspace (default "." = workspace root)' },
            mounts: {
              type: 'array',
              description: 'Virtual path mounts',
              items: {
                type: 'object',
                properties: { prefix: { type: 'string' }, dir: { type: 'string' } },
                required: ['prefix', 'dir'],
              },
            },
          },
        },
        async execute({ port, dir, mounts }) {
          if (self.server) return `preview already running at ${self.url()}`;
          if (dir) {
            // dir 必须落在 workspace 内：否则一个参数就能把本地 HTTP 服务
            // 变成宿主任意文件的读取通道（含 shared_prefs 里的密钥）。
            const next = path.resolve(root, dir);
            if (!inside(next, root)) {
              throw new Error(`preview dir escapes workspace: ${dir}`);
            }
            self.root = next;
          }
          if (Array.isArray(mounts)) {
            for (const m of mounts) {
              if (m?.dir && !inside(path.resolve(self.root, m.dir), self.root)) {
                throw new Error(`preview mount dir escapes workspace: ${m.dir}`);
              }
            }
            self.mounts = mounts;
          }
          const url = await self.start({ port: port ?? 0 });
          return `preview running at ${url} (serving ${self.root})`;
        },
      },
      {
        name: 'preview_stop',
        description: 'Stop the local preview web server.',
        risk: 'exec',
        parameters: { type: 'object', properties: {} },
        async execute() {
          if (!self.server) return 'preview was not running';
          const url = self.url();
          await self.stop();
          return `preview stopped (was ${url})`;
        },
      },
      {
        name: 'preview_url',
        description: 'Get the URL of the running preview server, or start one if not running. Also shows recent requests.',
        risk: 'read',
        parameters: { type: 'object', properties: {} },
        async execute() {
          if (!self.server) {
            const url = await self.start({ port: 0 });
            return `${url}\n(served ${self.root}; started on demand)`;
          }
          const reqs = self.requests().slice(-10).map((r) => `${r.status} ${r.url}`).join('\n');
          return `${self.url()}\nserving ${self.root}\nrecent requests:\n${reqs || '(none yet)'}`;
        },
      },
    ];
  }
}

function injectLiveReloadScript(html) {
  if (html.includes('__haca_live')) return html;
  const script = `<script>
(function () {
  try {
    var es = new EventSource('/__haca_live');
    es.onmessage = function () { location.reload(); };
  } catch (e) {}
})();
</script>`;
  if (/<\/body>/i.test(html)) return html.replace(/<\/body>/i, `${script}\n</body>`);
  return html + script;
}

function inside(abs, root) {
  const r = path.resolve(root);
  return abs === r || abs.startsWith(r + path.sep);
}

function statOrNull(p) {
  try {
    return fs.statSync(p);
  } catch {
    return null;
  }
}

/** 组合式工具工厂：给 index.js 用。 */
export function createPreviewTools(opts) {
  const server = new LocalPreviewServer(opts);
  return { server, tools: server.tools(opts) };
}