// 工作区增强（能力 #32 变更追踪 / #33 备份导出 / #31 项目模板 / #28 多根）。
//
// #32：HACA 原本只有 resolveWithin 做越界防护，但 agent 不知道「我这次改了什么」。
//      WorkspaceChangeTracker 记录每个 run 的文件变更 + GitIgnore 感知，
//      让 agent 能问「哪些文件被改过」「这个目录是不是被 ignore 了」。
// #33：undo 只在进程内存里（run.changes），进程一死就没了。WorkspaceBackupManager
//      做落盘快照 + 打包导出（tar 无关，用 JSON+base64 的自描述 zip-lite），
//      让用户能把项目从手机带走。
// #31：脚手架。新项目不用 agent 从零猜 package.json 布局。
// #28：多工作区后端 —— WorkspaceRegistry 抽象 local / 外部目录两种根。

import fs from 'node:fs';
import path from 'node:path';
import { resolveWithin } from './tools/fs-tools.js';
import { atomicWriteFileSync } from './util/atomic-write.js';

const DEFAULT_IGNORES = ['node_modules', '.git', '.gradle', 'build', '.idea', '.cache', '__pycache__', 'dist', '.next', '.venv'];

/**
 * 相对路径统一成 POSIX 分隔符。
 * path.relative 在 Windows 上返回 `node_modules\x.js`，而 gitignore 规则、
 * 变更记录、备份清单全部按 `/` 匹配 —— 不归一化的话，Windows 上
 * 嵌套路径会绕过 ignore 检查，记录里的路径也会和备份里的对不上。
 */
function relPosix(root, abs) {
  return path.relative(root, abs).split(path.sep).join('/');
}

/**
 * 极简 .gitignore 匹配：支持目录名、行内 # 注释、* 通配、? 单字符、
 * 末尾 / 只匹配目录、前缀锚定（/foo 只在根）、结尾锚定（foo/）。
 * 不追求 100% 兼容 git，但对 agent 场景足够，且零依赖。
 */
export class GitIgnoreFilter {
  constructor({ root, extraRules = [], readFile = null } = {}) {
    this.root = root;
    this.rules = [];
    this.source = 'defaults';
    if (readFile) {
      this.source = readFile;
      for (const line of String(readFile).split('\n')) this.#addRule(line);
    }
    for (const r of extraRules) this.#addRule(r);
    for (const d of DEFAULT_IGNORES) this.rules.push({ pattern: d, dirOnly: false, anchored: false, negate: false, source: 'default' });
  }

  #addRule(raw) {
    let line = String(raw || '').trim();
    if (!line || line.startsWith('#')) return;
    let negate = false;
    if (line.startsWith('!')) {
      negate = true;
      line = line.slice(1).trim();
    }
    const dirOnly = line.endsWith('/');
    if (dirOnly) line = line.slice(0, -1);
    const anchored = line.startsWith('/');
    if (anchored) line = line.slice(1);
    if (!line) return;
    this.rules.push({ pattern: line, dirOnly, anchored, negate, source: 'gitignore' });
  }

  /** 相对路径 + 是否目录 -> 是否被忽略。 */
  ignores(relPath, isDir = false) {
    let ignored = false;
    for (const r of this.rules) {
      if (r.dirOnly && !isDir) continue;
      if (!matchRule(r, relPath, isDir)) continue;
      ignored = !r.negate;
    }
    return ignored;
  }

  /** 路径的任一祖先目录被忽略，则整条路径忽略。 */
  isIgnored(relPath, isDir = false) {
    if (this.ignores(relPath, isDir)) return true;
    const parts = relPath.split('/').filter(Boolean);
    let acc = '';
    for (let i = 0; i < parts.length - 1; i++) {
      acc = acc ? `${acc}/${parts[i]}` : parts[i];
      if (this.ignores(acc, true)) return true;
    }
    return false;
  }
}

function globToRe(pattern) {
  let re = '';
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === '*') {
      if (pattern[i + 1] === '*') {
        // ** 跨目录
        re += '.*';
        i += 1;
        if (pattern[i + 1] === '/') i += 1;
      } else {
        re += '[^/]*';
      }
    } else if (c === '?') re += '[^/]';
    else if ('.+^${}()|[]\\'.includes(c)) re += '\\' + c;
    else re += c;
  }
  return new RegExp(`^${re}$`);
}

function matchRule(rule, relPath, isDir) {
  const candidates = [relPath];
  if (!rule.anchored) candidates.push(relPath.split('/').pop());
  for (const cand of candidates) {
    if (!cand) continue;
    if (globToRe(rule.pattern).test(cand)) return true;
    // 目录前缀：忽略 foo 等价于忽略 foo/ 下所有东西
    if (isDir && cand.startsWith(`${rule.pattern}/`)) return true;
  }
  return false;
}

/** 读取工作区的 .gitignore（没有就返回空串）。 */
export function readGitIgnore(root) {
  const p = path.join(root, '.gitignore');
  try {
    return fs.readFileSync(p, 'utf8');
  } catch {
    return '';
  }
}

/**
 * 变更追踪（#32）。agent 每次写文件前调 recordBefore（已有的 onBeforeWrite 钩子），
 * 变更记录落盘到 <stateDir>/workspace-changes.json，重启后仍能回答
 * 「这个会话改了哪些文件」。
 */
export class WorkspaceChangeTracker {
  constructor({ workspaceRoot, stateDir } = {}) {
    this.root = path.resolve(workspaceRoot || process.cwd());
    this.stateDir = stateDir || null;
    this.file = this.stateDir ? path.join(this.stateDir, 'workspace-changes.json') : null;
    this.records = []; // { at, runId, sessionId, path, action, bytes }
    this.snapshots = new Map(); // path -> before 内容（内存，用于生成 diff）
    this.filter = new GitIgnoreFilter({ root: this.root, readFile: readGitIgnore(this.root) });
    if (this.file) this.#load();
  }

  /** agent 写文件前的钩子，与 fs-tools 的 ctx.onBeforeWrite 对接。 */
  beforeWrite = (absPath, meta = {}) => {
    const rel = relPosix(this.root, absPath);
    if (this.filter.isIgnored(rel, false)) return;
    let before = null;
    try {
      before = fs.readFileSync(absPath, 'utf8');
    } catch {
      before = null;
    }
    if (!this.snapshots.has(absPath)) this.snapshots.set(absPath, before);
    this.records.push({
      at: new Date().toISOString(),
      runId: meta.runId || null,
      sessionId: meta.sessionId || null,
      path: rel,
      action: before === null ? 'create' : 'modify',
      bytes: before === null ? 0 : Buffer.byteLength(before, 'utf8'),
    });
    this.#save();
  };

  /** 显式记录删除/移动。 */
  recordDelete(relOrAbs, meta = {}) {
    const rel = path.isAbsolute(relOrAbs) ? relPosix(this.root, relOrAbs) : relOrAbs.split('\\').join('/');
    if (this.filter.isIgnored(rel, false)) return;
    this.records.push({ at: new Date().toISOString(), runId: meta.runId || null, sessionId: meta.sessionId || null, path: rel, action: 'delete', bytes: 0 });
    this.#save();
  }

  /** 某个 run 改过的文件（相对路径，去重，保留顺序）。 */
  changedBy(runId) {
    const seen = new Map();
    for (const r of this.records) {
      if (runId && r.runId !== runId) continue;
      seen.set(r.path, r.action);
    }
    return [...seen.entries()].map(([p, action]) => ({ path: p, action }));
  }

  /** 最近 N 条变更，给 agent 一个「刚才我动了什么」的短视图。 */
  recent(limit = 30) {
    return this.records.slice(-limit).reverse();
  }

  /** 变更摘要（按 action 计数 + 体积）。 */
  summary(runId = null) {
    const rows = runId ? this.records.filter((r) => r.runId === runId) : this.records;
    const byAction = {};
    for (const r of rows) byAction[r.action] = (byAction[r.action] || 0) + 1;
    return { total: rows.length, byAction, files: new Set(rows.map((r) => r.path)).size };
  }

  /** 未被 ignore 的文件清单（受深度/数量限制），供 agent 感知项目结构。 */
  listFiles({ maxDepth = 4, limit = 500 } = {}) {
    const out = [];
    const walk = (dir, rel, level) => {
      if (out.length >= limit || level > maxDepth) return;
      let entries;
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        const r = rel ? `${rel}/${e.name}` : e.name;
        if (this.filter.isIgnored(r, e.isDirectory())) continue;
        if (e.isDirectory()) walk(path.join(dir, e.name), r, level + 1);
        else out.push(r);
        if (out.length >= limit) return;
      }
    };
    walk(this.root, '', 1);
    return out;
  }

  clear() {
    this.records = [];
    this.snapshots.clear();
    this.#save();
  }

  #load() {
    try {
      this.records = JSON.parse(fs.readFileSync(this.file, 'utf8')).records || [];
    } catch {
      this.records = [];
    }
  }

  #save() {
    if (!this.file) return;
    try {
      atomicWriteFileSync(this.file, JSON.stringify({ records: this.records.slice(-2000) }));
    } catch {
      /* best effort */
    }
  }
}

/**
 * 备份与导出（#33）。
 * 格式：一个 JSON 头 + 每个文件的 base64 内容，单文件写出。
 * 不用 tar/zip 依赖——手机上 node 的 zlib 也够，但自定义格式更可控。
 */
export class WorkspaceBackupManager {
  constructor({ workspaceRoot, filter, maxFileBytes = 5 * 1024 * 1024 } = {}) {
    this.root = path.resolve(workspaceRoot || process.cwd());
    this.filter = filter || new GitIgnoreFilter({ root: this.root, readFile: readGitIgnore(this.root) });
    this.maxFileBytes = maxFileBytes;
  }

  /** 打包成 { meta, files: [{path, encoding, data}] }。 */
  pack({ maxFiles = 2000 } = {}) {
    const files = [];
    let skipped = 0;
    let totalBytes = 0;
    const walk = (dir, rel) => {
      if (files.length >= maxFiles) return;
      let entries;
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        if (files.length >= maxFiles) return;
        const r = rel ? `${rel}/${e.name}` : e.name;
        if (this.filter.isIgnored(r, e.isDirectory())) continue;
        const abs = path.join(dir, e.name);
        if (e.isDirectory()) walk(abs, r);
        else if (e.isFile()) {
          let st;
          try {
            st = fs.statSync(abs);
          } catch {
            continue;
          }
          if (st.size > this.maxFileBytes) {
            skipped += 1;
            continue;
          }
          const buf = fs.readFileSync(abs);
          totalBytes += buf.length;
          files.push({
            path: r,
            bytes: st.size,
            mtime: new Date(st.mtimeMs).toISOString(),
            encoding: 'base64',
            data: buf.toString('base64'),
          });
        }
      }
    };
    walk(this.root, '');
    return {
      meta: { workspace: this.root, packedAt: new Date().toISOString(), files: files.length, bytes: totalBytes, skippedTooLarge: skipped },
      files,
    };
  }

  /** 打包并写到一个 .haca-backup.json 文件，返回路径。dest 必须落在 workspace 内。 */
  export(destPath) {
    const bundle = this.pack();
    // 与 restore() 一致地过 resolveWithin：备份目的地不收口的话，
    // 一个 read 级工具就能把整个工作区写到任意绝对路径/覆盖任意文件。
    const abs = resolveWithin(this.root, destPath || 'workspace-backup.haca-backup.json');
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, JSON.stringify(bundle), 'utf8');
    return { path: abs, ...bundle.meta };
  }

  /** 单文件快照：只备份给定相对路径列表（用于「改之前先存一份」）。 */
  snapshot(relPaths = []) {
    const out = {};
    for (const rel of relPaths) {
      const abs = resolveWithin(this.root, rel);
      try {
        out[rel] = fs.readFileSync(abs, 'utf8');
      } catch {
        out[rel] = null;
      }
    }
    return out;
  }

  /**
   * 从快照恢复。接受两种输入：
   *   - `{ rel: content }` 映射（snapshot() 的产物，content=null 表示删除）
   *   - `{ meta, files: [{ path, encoding, data }] }` 打包件（pack()/export() 的产物）
   * 后一种必须解 base64——否则写进磁盘的是 base64 文本，文件全被毁掉。
   */
  restore(snapshot) {
    const restored = [];

    if (Array.isArray(snapshot?.files)) {
      for (const entry of snapshot.files) {
        const abs = resolveWithin(this.root, entry.path);
        const buf = entry.encoding === 'base64' ? Buffer.from(entry.data, 'base64') : Buffer.from(entry.data, 'utf8');
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, buf);
        restored.push({ path: entry.path, action: 'restored' });
      }
      return restored;
    }

    for (const [rel, content] of Object.entries(snapshot || {})) {
      const abs = resolveWithin(this.root, rel);
      if (content === null) {
        fs.rmSync(abs, { force: true });
        restored.push({ path: rel, action: 'deleted' });
      } else {
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, content, 'utf8');
        restored.push({ path: rel, action: 'restored' });
      }
    }
    return restored;
  }
}

/**
 * 项目模板（#31）。每种模板就是一组文件内容，scaffold 时原样写出。
 * 刻意保持极简：给 agent 一个正确的起点，而不是一个臃肿的脚手架。
 */
export const PROJECT_TEMPLATES = [
  {
    id: 'node-esm',
    label: 'Node.js (ESM)',
    description: 'package.json + 一个可跑的 src/index.js + 基础测试脚本',
    files: {
      'package.json': '{\n  "name": "{{name}}",\n  "version": "0.1.0",\n  "type": "module",\n  "main": "src/index.js",\n  "scripts": {\n    "start": "node src/index.js",\n    "test": "node --test test/*.test.js"\n  }\n}\n',
      'src/index.js': 'export function main() {\n  console.log("{{name}} is running");\n}\n\nmain();\n',
      'test/index.test.js': 'import test from "node:test";\nimport assert from "node:assert/strict";\nimport { main } from "../src/index.js";\n\ntest("main does not throw", () => {\n  assert.doesNotThrow(() => main());\n});\n',
      '.gitignore': 'node_modules\ndist\n',
      'README.md': '# {{name}}\n\nCreated with happy_ACA scaffold.\n',
    },
  },
  {
    id: 'web-static',
    label: '静态网页（HTML/CSS/JS）',
    description: 'index.html + styles.css + app.js，无构建步骤',
    files: {
      'index.html': '<!doctype html>\n<html lang="zh">\n<head>\n  <meta charset="utf-8" />\n  <meta name="viewport" content="width=device-width, initial-scale=1" />\n  <title>{{name}}</title>\n  <link rel="stylesheet" href="styles.css" />\n</head>\n<body>\n  <main id="app"></main>\n  <script type="module" src="app.js"></script>\n</body>\n</html>\n',
      'styles.css': ':root {\n  color-scheme: light dark;\n  font-family: system-ui, sans-serif;\n}\nbody {\n  margin: 0;\n  display: grid;\n  place-items: center;\n  min-height: 100dvh;\n}\n',
      'app.js': 'const app = document.querySelector("#app");\napp.textContent = "{{name}} is running";\n',
      '.gitignore': 'node_modules\ndist\n',
    },
  },
  {
    id: 'node-cli',
    label: 'Node CLI',
    description: '带参数解析的最小命令行工具',
    files: {
      'package.json': '{\n  "name": "{{name}}",\n  "version": "0.1.0",\n  "type": "module",\n  "bin": { "{{name}}": "./bin/cli.js" },\n  "scripts": { "start": "node bin/cli.js" }\n}\n',
      'bin/cli.js': '#!/usr/bin/env node\nconst args = process.argv.slice(2);\nconsole.log(`{{name}}: ${args.length ? args.join(" ") : "(no args)"}`);\n',
      '.gitignore': 'node_modules\n',
    },
  },
  {
    id: 'python-basic',
    label: 'Python 基础项目',
    description: 'pyproject.toml + src 包 + 测试目录',
    files: {
      'pyproject.toml': '[project]\nname = "{{name}}"\nversion = "0.1.0"\nrequires-python = ">=3.10"\n\n[build-system]\nrequires = ["setuptools"]\nbuild-backend = "setuptools.build_meta"\n',
      'src/{{name}}/__init__.py': 'def main() -> None:\n    print("{{name}} is running")\n\n\nif __name__ == "__main__":\n    main()\n',
      'tests/test_main.py': 'from {{name}} import main\n\n\ndef test_main():\n    assert main() is None\n',
      '.gitignore': '__pycache__/\n.venv/\n*.egg-info/\n',
    },
  },
  {
    id: 'empty',
    label: '空项目',
    description: '只有 README 与 .gitignore',
    files: { 'README.md': '# {{name}}\n', '.gitignore': '' },
  },
];

export class ProjectScaffold {
  constructor({ workspaceRoot }) {
    this.root = path.resolve(workspaceRoot || process.cwd());
  }

  static list() {
    return PROJECT_TEMPLATES.map((t) => ({ id: t.id, label: t.label, description: t.description, files: Object.keys(t.files) }));
  }

  /** 在 workspace 下建子目录并写模板文件。返回创建的文件列表。 */
  create(templateId, dirName, { name } = {}) {
    const tpl = PROJECT_TEMPLATES.find((t) => t.id === templateId);
    if (!tpl) throw new Error(`unknown template: ${templateId}`);
    const safeName = String(name || dirName || 'app').replace(/[^\w.-]/g, '-');
    const dest = resolveWithin(this.root, dirName || tpl.id);
    fs.mkdirSync(dest, { recursive: true });

    const written = [];
    for (const [rel, content] of Object.entries(tpl.files)) {
      // 路径 key 里的 {{name}} 也要替换：只替换 content 会落出一个字面
      // `{{name}}` 目录，而 import 语句用的是真名——模板直接不可用。
      const relPath = rel.replaceAll('{{name}}', safeName);
      const abs = resolveWithin(dest, relPath);
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      fs.writeFileSync(abs, content.replaceAll('{{name}}', safeName), 'utf8');
      written.push(path.relative(dest, abs).replace(/\\/g, '/'));
    }
    return { template: tpl.id, dir: path.relative(this.root, dest).replace(/\\/g, '/') || '.', files: written };
  }
}

/**
 * 多工作区注册表（#28 的可落地子集：本地目录 + 外部存储目录）。
 * 不是完整的 SAF/SFTP 抽象——那部分 Android 侧才有意义；这里负责
 * 「一个 agent host 可以服务多个工作区」的状态管理。
 */
export class WorkspaceRegistry {
  constructor({ defaultRoot } = {}) {
    this.defaultId = 'default';
    this.entries = new Map();
    if (defaultRoot) {
      this.entries.set(this.defaultId, { id: this.defaultId, root: path.resolve(defaultRoot), kind: 'local', label: 'default' });
    }
  }

  add({ id, root, kind = 'local', label }) {
    if (!id || !root) throw new Error('workspace needs id and root');
    const abs = path.resolve(root);
    const rec = { id: String(id), root: abs, kind, label: label || id };
    this.entries.set(rec.id, rec);
    return rec;
  }

  remove(id) {
    if (id === this.defaultId) throw new Error('cannot remove the default workspace');
    return this.entries.delete(id);
  }

  get(id = this.defaultId) {
    return this.entries.get(id) ?? null;
  }

  resolve(id = this.defaultId) {
    const e = this.entries.get(id);
    if (!e) throw new Error(`unknown workspace: ${id}`);
    return e.root;
  }

  list() {
    return [...this.entries.values()].map((e) => ({ ...e, exists: fs.existsSync(e.root) }));
  }

  /** 切到某工作区：返回该工作区的核心工具集（fresh registry 由调用方建）。 */
  toolset(id = this.defaultId) {
    return { workspace: this.entries.get(id) ?? null };
  }
}

/**
 * 工作区工具（#31 #32 #33 的 agent 侧入口）。
 * 这些工具让 agent 能感知 ignore 规则、回答「改了什么」、以及导出项目。
 */
export function createWorkspaceTools({ tracker, backup, scaffold }) {
  const tools = [];

  if (tracker) {
    tools.push(
      {
        name: 'workspace_changes',
        description:
          'List files this agent changed (optionally filtered to one run), plus a summary. Gitignore-respecting, so ignored build output does not appear.',
        risk: 'read',
        parameters: {
          type: 'object',
          properties: {
            run_id: { type: 'string', description: 'Filter to a single run id' },
            recent: { type: 'integer', description: 'Return the N most recent change records, default 30' },
          },
        },
        execute({ run_id, recent }) {
          const summary = tracker.summary(run_id || null);
          const rows = run_id ? tracker.changedBy(run_id) : tracker.recent(recent ?? 30);
          const lines = [`changes: ${summary.total} records across ${summary.files} files (${JSON.stringify(summary.byAction)})`];
          for (const r of rows) lines.push(`  ${r.action.padEnd(6)} ${r.path}`);
          return lines.join('\n');
        },
      },
      {
        name: 'workspace_files',
        description: 'List non-ignored files in the workspace (gitignore-aware, capped). Use to understand project structure without noise.',
        risk: 'read',
        parameters: {
          type: 'object',
          properties: {
            max_depth: { type: 'integer', description: 'Default 4' },
            limit: { type: 'integer', description: 'Default 500' },
          },
        },
        execute({ max_depth, limit }) {
          const files = tracker.listFiles({ maxDepth: max_depth ?? 4, limit: limit ?? 500 });
          return files.join('\n') || '(no tracked files)';
        },
      },
    );
  }

  if (scaffold) {
    tools.push(
      {
        name: 'list_project_templates',
        description: 'List available project scaffolding templates (node-esm, web-static, node-cli, python-basic, empty).',
        risk: 'read',
        parameters: { type: 'object', properties: {} },
        execute() {
          return ProjectScaffold.list()
            .map((t) => `${t.id} — ${t.label}: ${t.description}\n    files: ${t.files.join(', ')}`)
            .join('\n');
        },
      },
      {
        name: 'scaffold_project',
        description: 'Create a new project from a template inside the workspace. Use when the user asks to start a new project.',
        risk: 'write',
        parameters: {
          type: 'object',
          properties: {
            template_id: { type: 'string' },
            dir_name: { type: 'string', description: 'Directory to create, relative to workspace root' },
            name: { type: 'string', description: 'Project name used in files (defaults to dir_name)' },
          },
          required: ['template_id', 'dir_name'],
        },
        execute({ template_id, dir_name, name }) {
          const r = scaffold.create(template_id, dir_name, { name });
          return `created ${r.template} project at ${r.dir}:\n${r.files.map((f) => `  ${f}`).join('\n')}`;
        },
      },
    );
  }

  if (backup) {
    tools.push(
      {
        name: 'workspace_backup',
        description:
          'Pack the workspace (gitignore-aware, skipping huge files) into a single .haca-backup.json file so the user can take the project off the device.',
        risk: 'read',
        parameters: {
          type: 'object',
          properties: {
            dest: { type: 'string', description: 'Output path relative to workspace (default: workspace-backup.haca-backup.json)' },
          },
        },
        execute({ dest }) {
          const r = backup.export(dest || 'workspace-backup.haca-backup.json');
          return `backed up ${r.files} files (${(r.bytes / 1024).toFixed(0)}KB) -> ${r.path}${r.skippedTooLarge ? ` (${r.skippedTooLarge} files too large, skipped)` : ''}`;
        },
      },
      {
        name: 'workspace_restore',
        description: 'Restore files from a snapshot object produced earlier (used together with pre-edit snapshots).',
        risk: 'write',
        parameters: {
          type: 'object',
          properties: { snapshot: { type: 'object', description: 'Map of relative path -> original content (null = delete)' } },
          required: ['snapshot'],
        },
        execute({ snapshot }) {
          const restored = backup.restore(snapshot);
          return restored.map((r) => `${r.action} ${r.path}`).join('\n') || '(nothing to restore)';
        },
      },
    );
  }

  return tools;
}