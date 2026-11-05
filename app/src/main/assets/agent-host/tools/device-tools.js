// Device-aware tools: capabilities that only make sense when the agent runs on
// a phone (or any embedded host) rather than a developer machine. They stay
// workspace-scoped like the fs tools.
//
// 说明：这里只有「工作区概览 / 文件状态 / 打补丁 / 移动」四个纯工作区工具，
// 与「手机操控」无关（那部分归 HPA），所以保留不动。

import fs from 'node:fs';
import path from 'node:path';
import { resolveWithin } from './fs-tools.js';

const IGNORE_DIRS = new Set(['node_modules', '.git', '.gradle', 'build', '.idea', '.cache', '__pycache__']);

/**
 * workspace_overview — a cheap tree + size summary so the model can orient
 * itself in one call instead of globbing blindly. Capped hard: this is the
 * first thing the model reaches for on a phone where every read is precious.
 */
export function createDeviceTools({ workspaceRoot }) {
  const root = path.resolve(workspaceRoot);

  return [
    {
      name: 'workspace_overview',
      description:
        'Summarise the workspace: entry files, directory tree (capped), file counts and total size by extension. Call this first to orient yourself.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          depth: { type: 'integer', description: 'Tree depth, default 2, max 4' },
        },
      },
      execute({ depth }) {
        const maxDepth = Math.min(4, Math.max(1, depth ?? 2));
        const byExt = new Map();
        let files = 0;
        let dirs = 0;
        let bytes = 0;

        const walk = (dir, rel, level) => {
          let entries;
          try {
            entries = fs.readdirSync(dir, { withFileTypes: true });
          } catch {
            return;
          }
          for (const e of entries) {
            // Skip heavy/derived dirs anywhere in the tree so the summary stays cheap.
            if (IGNORE_DIRS.has(e.name)) continue;
            const abs = path.join(dir, e.name);
            const r = rel ? `${rel}/${e.name}` : e.name;
            if (e.isDirectory()) {
              dirs += 1;
              if (level < maxDepth) walk(abs, r, level + 1);
            } else if (e.isFile()) {
              files += 1;
              try {
                bytes += fs.statSync(abs).size;
              } catch {
                /* ignore */
              }
              const ext = path.extname(e.name) || '(no ext)';
              byExt.set(ext, (byExt.get(ext) ?? 0) + 1);
            }
          }
        };
        walk(root, '', 1);

        const topExt = [...byExt.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
        let listing;
        try {
          listing = fs.readdirSync(root);
        } catch {
          listing = [];
        }

        const fmt = (n) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`);

        return [
          `workspace: ${root}`,
          `${files} files, ${dirs} dirs, ${fmt(bytes)} total`,
          '',
          'top level:',
          ...listing.slice(0, 40).map((n) => `  ${n}`),
          listing.length > 40 ? `  ... (+${listing.length - 40} more)` : '',
          '',
          'by extension:',
          ...topExt.map(([ext, n]) => `  ${ext}: ${n}`),
        ]
          .filter((l) => l !== '')
          .join('\n');
      },
    },

    {
      name: 'file_stat',
      description:
        'Stat a path: exists, type, size in lines and bytes, modified time. Cheap way to check a file before reading or editing it.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: 'Path relative to workspace root' } },
        required: ['path'],
      },
      execute({ path: p }) {
        const abs = resolveWithin(root, p);
        if (!fs.existsSync(abs)) return `missing: ${p}`;
        const st = fs.statSync(abs);
        if (st.isDirectory()) {
          const n = fs.readdirSync(abs).length;
          return `${p}: directory, ${n} entries`;
        }
        let lines = 0;
        try {
          lines = fs.readFileSync(abs, 'utf8').split('\n').length;
        } catch {
          return `${p}: binary, ${st.size} bytes, mtime ${new Date(st.mtimeMs).toISOString()}`;
        }
        return `${p}: ${st.size} bytes, ${lines} lines, mtime ${new Date(st.mtimeMs).toISOString()}`;
      },
    },

    {
      name: 'apply_patch',
      description:
        'Apply a unified diff to a file (context-based, not line-number based). Safer than rewriting a whole file when changing a few lines. Fails loudly if the context does not match.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'File path relative to workspace root' },
          patch: { type: 'string', description: 'Unified diff hunks (@@ ... @@ with -/+ lines)' },
        },
        required: ['path', 'patch'],
      },
      async execute({ path: p, patch }, ctx) {
        const abs = resolveWithin(root, p);
        if (!fs.existsSync(abs)) throw new Error(`no such file: ${p}`);
        const src = fs.readFileSync(abs, 'utf8');
        const hadFinalNewline = src.endsWith('\n');
        const lines = src.split('\n');
        if (hadFinalNewline) lines.pop();

        const out = [];
        let cursor = 0; // index into `lines`
        const patchLines = patch.split('\n');

        for (let i = 0; i < patchLines.length; i += 1) {
          const line = patchLines[i];
          const hunk = /^@@\s*-(\d+)(?:,\d+)?\s*\+(\d+)(?:,\d+)?\s*@@/.exec(line);
          if (!hunk) continue;
          const start = Number(hunk[1]) - 1;
          if (start < cursor) throw new Error(`overlapping hunks at line ${start + 1}`);
          // copy untouched lines before the hunk
          while (cursor < start) out.push(lines[cursor++]);

          i += 1; // first hunk body line
          while (i < patchLines.length && !patchLines[i].startsWith('@@')) {
            const h = patchLines[i];
            if (h.startsWith('-')) {
              const expected = h.slice(1);
              const actual = lines[cursor];
              if (actual === undefined) throw new Error(`hunk expects line ${cursor + 1} but file ended`);
              if (actual.trimEnd() !== expected.trimEnd()) {
                throw new Error(
                  `context mismatch at line ${cursor + 1}:\n  expected: ${expected}\n  actual:   ${actual}`,
                );
              }
              cursor += 1;
            } else if (h.startsWith('+')) {
              out.push(h.slice(1));
            } else if (h.startsWith(' ') || h === '') {
              const expected = h.startsWith(' ') ? h.slice(1) : '';
              const actual = lines[cursor];
              if (expected.trimEnd() !== String(actual ?? '').trimEnd()) {
                throw new Error(`context mismatch at line ${cursor + 1}:\n  expected: ${expected}\n  actual:   ${actual}`);
              }
              out.push(lines[cursor]);
              cursor += 1;
            } else if (h.startsWith('\\')) {
              // "\ No newline at end of file" marker — ignore
            } else {
              break;
            }
            i += 1;
          }
          i -= 1;
        }
        while (cursor < lines.length) out.push(lines[cursor++]);

        // 写前快照：apply_patch 是模型最常用的改文件方式，不记快照就等于 undo 保护不到它。
        await ctx?.onBeforeWrite?.(abs);
        fs.writeFileSync(abs, out.join('\n') + (hadFinalNewline ? '\n' : ''), 'utf8');
        return `patched ${p}: ${lines.length} -> ${out.length} lines`;
      },
    },

    {
      name: 'move_file',
      description: 'Move or rename a file/directory inside the workspace. Refuses to escape the workspace.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          from: { type: 'string', description: 'Source path relative to workspace root' },
          to: { type: 'string', description: 'Destination path relative to workspace root' },
        },
        required: ['from', 'to'],
      },
      async execute({ from, to }, ctx) {
        const src = resolveWithin(root, from);
        const dst = resolveWithin(root, to);
        if (!fs.existsSync(src)) throw new Error(`no such file: ${from}`);
        // 两处都快照：源（移走前的内容）与目标（原本不存在 → undo 时删除）。
        await ctx?.onBeforeWrite?.(src);
        await ctx?.onBeforeWrite?.(dst);
        fs.mkdirSync(path.dirname(dst), { recursive: true });
        fs.renameSync(src, dst);
        return `moved ${from} -> ${to}`;
      },
    },
  ];
}
