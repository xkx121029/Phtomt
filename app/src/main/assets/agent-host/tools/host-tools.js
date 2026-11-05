// Tools that only exist when the agent has a host app to call into (#11 / #12).
//
// The chain is: model → tool → HostBridge.call(method) → app (Kotlin) → device.
// Everything node-side is a thin, honest wrapper: it forwards the call, formats
// the answer, and — when the app cannot deliver — says exactly why instead of
// inventing a result. Availability is discovered at call time, so a tool that is
// listed but unsupported still fails readably rather than silently.
//
// 说明：原先这里还有一组「设备控制」工具（device_info / device_apps /
// device_clipboard / ui_dump / ui_action / screen_capture / screen_record /
// device_notifications / device_overlay）——那是手机操控能力，已移交 HPA。
// 本仓库保留的是与 vibecoding 直接相关的：内置浏览器、设备工作区文件读写、
// 代码编辑器、预览、Logcat 与 SQLite 工具箱，以及设备内构建环境（coding_env）。

import { HostUnavailableError } from '../host-bridge.js';

/** 把桥的失败翻译成模型能照做的指令，而不是一句无法行动的错误。 */
function explain(err, what) {
  if (err instanceof HostUnavailableError) {
    return (
      `error: ${what} is not available here — ${err.message}. ` +
      'This agent is not running inside the app, or the capability is switched off. ' +
      'Do not retry the same call; tell the user what needs enabling.'
    );
  }
  const msg = err?.message ?? String(err);
  return `error: ${what} failed — ${msg}`;
}

export function createHostTools({ bridge, workspaceRoot = null }) {
  if (!bridge) return [];

  // 默认超时由桥决定（60s）；网页加载/等待这类动作需要更长的窗口，单独传。
  const call = (method, params, opts) => bridge.call(method, params, opts);

  /** 绝对路径尽量转成工作区相对路径：模型看到相对路径才知道它在项目里的位置。 */
  const rel = (abs) => {
    if (!abs || !workspaceRoot) return abs;
    const root = workspaceRoot.replace(/[\\/]+$/, '');
    if (abs.startsWith(`${root}/`) || abs.startsWith(`${root}\\`)) {
      return abs.slice(root.length + 1).split('\\').join('/');
    }
    return abs;
  };

  const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(2)}MB`;

  return [
    {
      name: 'browser_open',
      description:
        'Open a web page in the app\'s built-in browser and wait for it to load. Pass a URL to visit a site, or plain words to run a search. Set visible=true when the user should watch the page (it brings the browser to the front). After opening, use browser_read to see what is on the page.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          url: { type: 'string', description: 'URL (http/https) or a search query' },
          visible: { type: 'boolean', description: 'Show the page to the user, default false' },
          timeoutMs: { type: 'integer', description: 'Load timeout in ms, default 25000' },
        },
        required: ['url'],
      },
      async execute({ url, visible, timeoutMs }) {
        try {
          const res = await call(
            'browser_open',
            { url, visible: visible === true, timeoutMs: timeoutMs ?? 25000 },
            { timeoutMs: Math.min(120_000, (timeoutMs ?? 25000) + 8_000) },
          );
          const head = `${res.title ? `${res.title} — ` : ''}${res.url}`;
          if (res.error) return `error: ${res.error}\n${head}`;
          return `${head}${res.finished ? '' : ' (load did not finish in time; the page may still be loading)'}`;
        } catch (err) {
          return explain(err, 'opening a web page');
        }
      },
    },

    {
      name: 'browser_read',
      description:
        'Read the currently open page. mode=text (default) returns the visible text, links returns every link with its text, fields returns the form inputs you can type into, html returns the raw markup (large), title returns just the title. Prefer text or links — html is expensive.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          mode: { type: 'string', enum: ['text', 'links', 'fields', 'html', 'title'] },
          limit: { type: 'integer', description: 'Max rows for links/fields, default 60, max 300' },
        },
      },
      async execute({ mode = 'text', limit }) {
        try {
          const res = await call('browser_read', { mode, limit: Math.min(300, Math.max(1, limit ?? 60)) });
          const where = res.url ? `[${res.url}]\n` : '';
          if (mode === 'title') return `${where}${res.title || '(no title)'}`;
          if (mode === 'html') return `${where}${res.html}`;
          if (mode === 'links') {
            if (!res.links?.length) return `${where}(no links on the page)`;
            return where + res.links.map((l) => `- ${l.text} -> ${l.href}`).join('\n');
          }
          if (mode === 'fields') {
            if (!res.fields?.length) return `${where}(no form fields on the page)`;
            return where + res.fields
              .map((f) => `- ${f.tag}${f.type ? `[${f.type}]` : ''} ${f.id ? `#${f.id}` : ''} ${f.name ? `name=${f.name}` : ''} ${f.placeholder ? `"${f.placeholder}"` : ''}`)
              .join('\n');
          }
          return `${where}${res.title ? `${res.title}\n\n` : ''}${res.text}`;
        } catch (err) {
          return explain(err, 'reading the web page');
        }
      },
    },

    {
      name: 'browser_act',
      description:
        'Interact with the open page. action=click (give selector or text), type (selector + text, optional submit), scroll (dy), wait (selector or text + timeoutMs — waits until it appears), back, reload. Call browser_read first so you know what is actually on the page; prefer clicking by selector when the page has stable ids.',
      risk: 'exec',
      parameters: {
        type: 'object',
        properties: {
          action: {
            type: 'string',
            enum: ['click', 'type', 'scroll', 'wait', 'back', 'reload'],
          },
          selector: { type: 'string', description: 'CSS selector (for click/type/wait)' },
          text: { type: 'string', description: 'Visible text to click, or text to type, or text to wait for' },
          submit: { type: 'boolean', description: 'type: submit the form after typing' },
          dy: { type: 'integer', description: 'scroll: pixels to scroll (negative scrolls up)' },
          timeoutMs: { type: 'integer', description: 'wait/type-submit timeout in ms' },
        },
        required: ['action'],
      },
      async execute(args) {
        try {
          const payload = { ...args };
          if (payload.action === 'wait') payload.timeoutMs = Math.min(120_000, payload.timeoutMs ?? 15000);
          if (payload.action === 'type') payload.timeoutMs = Math.min(120_000, payload.timeoutMs ?? 25000);
          const res = await call('browser_act', payload, { timeoutMs: (payload.timeoutMs ?? 20_000) + 8_000 });
          if (res && res.ok === false) return `error: ${res.error ?? 'the action failed'}`;
          switch (args.action) {
            case 'click':
              return `clicked${args.text ? ` "${args.text}"` : ''}${args.selector ? ` (${args.selector})` : ''}${res.url ? ` -> ${res.url}` : ''}`;
            case 'type':
              return `typed into ${args.selector}${res.submitted ? ` and submitted${res.finished ? '' : ' (navigation did not finish)'}` : ''}${res.url ? ` -> ${res.url}` : ''}`;
            case 'scroll':
              return `scrolled (scrollY=${res.scrollY ?? '?'})`;
            case 'wait':
              return `appeared after ~${res.waitedMs ?? '?'}ms`;
            case 'back':
              return `went back -> ${res.url}`;
            default:
              return `${args.action} done`;
          }
        } catch (err) {
          return explain(err, `the browser action (${args.action})`);
        }
      },
    },

    {
      name: 'browser_screenshot',
      description:
        'Save a PNG of the browser page as it is currently rendered. Only works while the browser UI is actually on screen (a background tab is not rendered) — if it fails, call browser_open with visible=true first.',
      risk: 'read',
      parameters: { type: 'object', properties: {} },
      async execute() {
        try {
          const res = await call('browser_screenshot', {});
          if (res && res.ok === false) return `error: ${res.error}`;
          return `page screenshot saved: ${rel(res.path)} (${res.width}x${res.height}, ${mb(res.bytes)})`;
        } catch (err) {
          return explain(err, 'screenshotting the page');
        }
      },
    },

    {
      name: 'browser_userscripts',
      description:
        "Manage user scripts: small JS files the built-in browser runs on matching pages (a `// @match <pattern>` header decides where; `all` means everywhere). action=list, save (name + code), remove, enable, disable. Use this to make a site behave differently for this agent — e.g. hide a banner, add a helper, auto-fill a known form.",
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['list', 'save', 'remove', 'enable', 'disable'] },
          name: { type: 'string', description: 'Script name (letters, digits, - and _ only)' },
          code: { type: 'string', description: 'Script body when action=save' },
        },
      },
      async execute({ action = 'list', name, code }) {
        try {
          if (action === 'list') {
            const res = await call('userscripts', { action: 'list' });
            if (!res.items?.length) return `no user scripts yet (they live in ${rel(res.dir)})`;
            return res.items
              .map((s) => `${s.enabled ? '[on] ' : '[off] '}${s.name} (${s.match}, ${s.bytes}B)`)
              .join('\n');
          }
          if (action === 'save' && typeof code !== 'string') return 'error: action=save needs `code`';
          const res = await call('userscripts', { action, name, code });
          return `${action} ok: ${res.name ?? res.removed ?? ''}`;
        } catch (err) {
          return explain(err, `the user script action (${action})`);
        }
      },
    },

    // ---- 工作区开发（#29 #30 #31 #34） ----
    // 这一组读写的都是**设备上**的工作区（用户手机上那份目录），不是运行本进程的
    // 机器上的工作区——所以路径不做工作区相对化，原样透传。

    {
      name: 'device_workspace_tree',
      description:
        'List the file tree of the workspace on the phone (the same directory the on-device agent edits). Use it to see what a project actually contains before reading or changing anything.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          maxDepth: { type: 'integer', description: 'Directory depth, default 6' },
          limit: { type: 'integer', description: 'Max entries, default 800' },
        },
      },
      async execute({ maxDepth, limit }) {
        try {
          const rows = await call('workspace_tree', {
            maxDepth: Math.min(10, Math.max(1, maxDepth ?? 6)),
            limit: Math.min(3000, Math.max(1, limit ?? 800)),
          });
          if (!Array.isArray(rows) || rows.length === 0) return 'the workspace is empty';
          return rows
            .map((f) => `${'  '.repeat(Math.max(0, (f.depth ?? 1) - 1))}${f.dir ? f.name + '/' : f.name}`)
            .join('\n');
        } catch (err) {
          return explain(err, 'listing the device workspace');
        }
      },
    },

    {
      name: 'device_file_read',
      description:
        'Read a text file from the phone workspace. Path is relative to the workspace root. Large files are truncated (the result says so).',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: 'Path relative to the workspace root' } },
        required: ['path'],
      },
      async execute({ path }) {
        if (!path) return 'error: `path` is required';
        try {
          const res = await call('workspace_read', { path });
          const head = `${res.path} (${res.bytes}B${res.truncated ? ', truncated' : ''})`;
          return `${head}\n${res.content}`;
        } catch (err) {
          return explain(err, `reading ${path}`);
        }
      },
    },

    {
      name: 'device_file_write',
      description:
        'Write a text file in the phone workspace, creating parent directories as needed. This overwrites the whole file — read it first unless you are sure it does not exist.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'Path relative to the workspace root' },
          content: { type: 'string', description: 'Full new file content' },
        },
        required: ['path', 'content'],
      },
      async execute({ path, content }) {
        if (!path) return 'error: `path` is required';
        if (typeof content !== 'string') return 'error: `content` is required';
        try {
          const res = await call('workspace_write', { path, content });
          return `wrote ${res.path} (${res.bytes}B)`;
        } catch (err) {
          return explain(err, `writing ${path}`);
        }
      },
    },

    {
      name: 'editor_open',
      description:
        'Open the code editor on the phone with a file loaded, so the user can read or edit it by hand. This takes over the screen — use it when the user asked to see/edit code, not as a routine step.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          path: {
            type: 'string',
            description: 'Optional workspace-relative file to open; omit to just show the editor',
          },
        },
      },
      async execute({ path }) {
        try {
          const res = await call('editor_open', { path: path ?? null });
          return `editor opened${res.path ? ` on ${res.path}` : ''}`;
        } catch (err) {
          return explain(err, 'opening the code editor');
        }
      },
    },

    {
      name: 'code_preview',
      description:
        'Render a page in the preview window: either an HTML file inside the phone workspace, or a URL (e.g. the local preview server started for a web project). Use it to show the user what a frontend project looks like.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          target: {
            type: 'string',
            description: 'Workspace-relative HTML path, or an http(s) URL',
          },
        },
        required: ['target'],
      },
      async execute({ target }) {
        if (!target) return 'error: `target` is required';
        try {
          await call('preview_open', { target });
          return `previewing ${target}`;
        } catch (err) {
          return explain(err, `previewing ${target}`);
        }
      },
    },

    {
      name: 'coding_env',
      description:
        'On-device build environment (PRoot Linux + JDK + Gradle + Android SDK, hosted by a companion app). action=status reports what is missing; action=setup advances one step and is repeatable — it may open an installer or a permission screen and return needsUserAction=true, so ask the user to finish that screen and call setup again (finished steps are skipped); action=build runs a Gradle task once ready; action=cancel stops the current task. Never claim the environment is ready from this call alone: read the returned ready flag.',
      risk: 'exec',
      parameters: {
        type: 'object',
        properties: {
          action: {
            type: 'string',
            enum: ['status', 'setup', 'build', 'cancel'],
            description: 'Default: status',
          },
          task: { type: 'string', description: 'Gradle task for action=build, default assembleDebug' },
          timeoutSec: { type: 'integer', description: 'Max wait in seconds, default 1800, max 3600' },
        },
      },
      async execute({ action = 'status', task, timeoutSec }) {
        const method = {
          status: 'env_status',
          setup: 'env_setup',
          build: 'env_build',
          cancel: 'env_cancel',
        }[action];
        if (!method) return `error: unknown action ${action}`;
        const wait = Math.min(3600, Math.max(30, timeoutSec ?? 1800));
        try {
          const res = await call(
            method,
            { task: task ?? 'assembleDebug', timeoutSec: wait },
            { timeoutMs: wait * 1000 + 30_000 },
          );
          return formatEnv(res, action);
        } catch (err) {
          return explain(err, 'the coding environment');
        }
      },
    },

    {
      name: 'android_logcat',
      description:
        'Read recent Android system logs (the developer toolbox). `query` filters lines by substring. On a normal app UID only this app\'s own logs are visible — the reply says so, and do not claim you saw system logs unless it did.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          lines: { type: 'integer', description: 'How many recent lines, default 400' },
          query: { type: 'string', description: 'Case-insensitive substring filter' },
        },
      },
      async execute({ lines, query }) {
        try {
          const res = await call('logcat_read', { lines: lines ?? 400, query: query ?? null }, {
            timeoutMs: 30_000,
          });
          const head = `logcat: ${res.lines} lines${query ? ` matching "${query}"` : ''}`;
          const note = res.note ? `\n[note] ${res.note}` : '';
          const err = res.error ? `\n[stderr] ${res.error}` : '';
          return `${head}${note}${err}\n${res.text}`;
        } catch (err) {
          return explain(err, 'reading logcat');
        }
      },
    },

    {
      name: 'sqlite_browse',
      description:
        "Inspect a SQLite database inside the phone workspace (read-only). action=list finds database files; action=tables lists a database's tables; action=query runs one read-only SQL statement (SELECT/PRAGMA/WITH/EXPLAIN).",
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['list', 'tables', 'query'], description: 'Default: list' },
          path: { type: 'string', description: 'Database path relative to the workspace root (for tables/query)' },
          sql: { type: 'string', description: 'Read-only SQL for action=query' },
          limit: { type: 'integer', description: 'Max rows for action=query, default 100' },
        },
      },
      async execute({ action = 'list', path, sql, limit }) {
        try {
          if (action === 'list') {
            const res = await call('sqlite_databases', {});
            if (!res.items?.length) return 'no .db/.sqlite files found in the workspace';
            return res.items.map((d) => `${d.path} (${d.size}B)`).join('\n');
          }
          if (action === 'tables') {
            if (!path) return 'error: action=tables needs `path`';
            const res = await call('sqlite_tables', { path });
            if (!res.tables?.length) return `${path}: no tables`;
            return res.tables.map((t) => `${t.type} ${t.name}`).join('\n');
          }
          if (action === 'query') {
            if (!path || !sql) return 'error: action=query needs `path` and `sql`';
            const res = await call('sqlite_query', { path, sql, limit: Math.min(500, Math.max(1, limit ?? 100)) });
            const cols = res.columns ?? [];
            const header = cols.join(' | ');
            const rows = (res.rows ?? []).map((r) => cols.map((_, i) => fmtCell(r[i])).join(' | '));
            const tail = res.truncated ? `\n… more rows (capped at ${limit ?? 100})` : '';
            return [header, ...rows].join('\n') + tail;
          }
          return `error: unknown action ${action}`;
        } catch (err) {
          return explain(err, `sqlite ${action}`);
        }
      },
    },
  ];
}

/** 单元格可能是 null / 数字 / 长字符串，输出前统一收成一行短文本。 */
function fmtCell(v) {
  if (v === null || v === undefined) return 'NULL';
  const s = String(v).replace(/\s+/g, ' ');
  return s.length > 80 ? `${s.slice(0, 80)}…` : s;
}

/** 环境 JSON → 几行可读摘要：先结论（ready），再逐个缺口，最后任务进度与日志尾巴。 */
function formatEnv(res, action) {
  if (!res || typeof res !== 'object') return 'error: the app returned no environment status';
  const env = res.environment ?? {};
  const toolchain = res.toolchain ?? {};
  const task = res.task ?? {};
  const lines = [];

  if (action !== 'status') {
    lines.push(`stage: ${res.stage ?? 'unknown'}${res.ok ? ' — done' : ''}`);
    if (res.message) lines.push(res.message);
  }

  lines.push(`ready: ${res.ready ? 'yes' : 'no'} (node runtime ${res.runtimeReady ? 'ok' : 'missing'})`);
  lines.push(
    `companion app: ${res.componentInstalled ? res.componentVersion : 'not installed'}` +
      `${res.componentNeedsInstall ? ' (bundled version is newer — update needed)' : ''}` +
      ` | component storage: ${res.envStorageGranted ? 'granted' : 'missing'}` +
      ` | all-files access: ${res.allFilesAccess ? 'granted' : 'missing'}` +
      `${res.needsStaging ? ' (required: the workspace is private)' : ''}`,
  );
  lines.push(
    `toolchain: linux=${orQuestion(env.linux)} jdk=${orQuestion(env.jdk)}` +
      ` gradle=${orQuestion(env.gradle)} sdk=${orQuestion(env.sdk)} aapt2=${orQuestion(env.aapt2)}`,
  );
  const projectBits = [
    toolchain.gradle ? `Gradle ${toolchain.gradle}` : null,
    toolchain.agp ? `AGP ${toolchain.agp}` : null,
    toolchain.compileSdk ? `compileSdk ${toolchain.compileSdk}` : null,
  ].filter(Boolean);
  lines.push(
    `project: ${res.projectDir ?? '(none)'}${projectBits.length ? ` (${projectBits.join(', ')})` : ''}`,
  );

  const steps = (task.steps ?? []).map((x) => `[${x.state}] ${x.title}`).join('  ');
  const state = task.running ? 'running' : task.finished ? (task.ok ? 'finished ok' : 'finished with error') : 'idle';
  lines.push(`task: ${state}${steps ? `\n  ${steps}` : ''}`);
  const logs = (task.logs ?? []).slice(-8);
  if (logs.length) lines.push(`log tail:\n${logs.map((l) => `  ${l}`).join('\n')}`);

  if (res.needsUserAction) {
    lines.push(
      'next: finish the screen the app just opened, then call coding_env action=setup again' +
        ' (already finished steps are skipped).',
    );
  } else if (!res.ready) {
    lines.push('next: call coding_env action=setup to continue.');
  }
  return lines.join('\n');
}

function orQuestion(v) {
  return v === null || v === undefined || v === '' ? '?' : v;
}
