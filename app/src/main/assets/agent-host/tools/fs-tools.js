// Filesystem tools. Every path is confined to the workspace root (path escape throws).

import fs from 'node:fs';
import path from 'node:path';
import { truncate } from '../providers/base.js';

export function resolveWithin(root, p) {
  const rootAbs = path.resolve(root);
  const abs = path.resolve(rootAbs, String(p ?? '.'));
  if (abs !== rootAbs && !abs.startsWith(rootAbs + path.sep)) {
    throw new Error(`path escapes workspace: ${p}`);
  }
  return abs;
}

const MAX_READ_LINES = 2000;

/** 一次删除/复制能记录快照的文件上限：再多 undo 快照会撑爆会话文件。 */
const MAX_SNAPSHOT_FILES = 2000;

/** tree 不进去的目录：这些地方进去只会淹没有用信息。 */
const SKIP_DIRS = new Set(['node_modules', '.git', 'build', 'dist', '.gradle', '.haca', '__pycache__', '.venv']);

function formatBytes(n) {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}K`;
  return `${(n / 1024 / 1024).toFixed(1)}M`;
}

export function createFsTools({ workspaceRoot }) {
  const root = path.resolve(workspaceRoot);

  const read_file = {
    name: 'read_file',
    description:
      'Read a text file from the workspace, returned with line numbers. Use offset/limit for large files instead of re-reading.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root' },
        offset: { type: 'integer', description: '1-based line to start from' },
        limit: { type: 'integer', description: 'Max number of lines to return (default 2000)' },
      },
      required: ['path'],
    },
    execute({ path: p, offset, limit }) {
      const abs = resolveWithin(root, p);
      const stat = fs.statSync(abs);
      if (!stat.isFile()) throw new Error(`not a file: ${p}`);
      const lines = fs.readFileSync(abs, 'utf8').split('\n');
      const start = Math.max(1, offset ?? 1);
      const max = Math.min(MAX_READ_LINES, Math.max(1, limit ?? MAX_READ_LINES));
      const end = Math.min(lines.length, start - 1 + max);
      const width = String(end).length;
      let out = lines
        .slice(start - 1, end)
        .map((l, i) => `${String(start + i).padStart(width)} | ${l}`)
        .join('\n');
      if (end < lines.length) out += `\n... [file has ${lines.length} lines, showing ${start}-${end}]`;
      return out || '(empty file)';
    },
  };

  const write_file = {
    name: 'write_file',
    description: 'Create or overwrite a file in the workspace. Parent directories are created automatically.',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root' },
        content: { type: 'string', description: 'Full file content' },
      },
      required: ['path', 'content'],
    },
    async execute({ path: p, content }, ctx) {
      const abs = resolveWithin(root, p);
      await ctx?.onBeforeWrite?.(abs);
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      const text = String(content ?? '');
      fs.writeFileSync(abs, text, 'utf8');
      return `wrote ${p} (${text.length} chars)`;
    },
  };

  const edit_file = {
    name: 'edit_file',
    description:
      'Replace an exact string in a file. old_string must be unique unless replace_all is set. Read the file first.',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string' },
        old_string: { type: 'string', description: 'Exact text to replace (must be unique in the file)' },
        new_string: { type: 'string', description: 'Replacement text' },
        replace_all: { type: 'boolean', description: 'Replace every occurrence instead of requiring uniqueness' },
      },
      required: ['path', 'old_string', 'new_string'],
    },
    async execute({ path: p, old_string, new_string, replace_all }, ctx) {
      if (!old_string) throw new Error('old_string must not be empty');
      const abs = resolveWithin(root, p);
      const raw = fs.readFileSync(abs, 'utf8');
      const count = raw.split(old_string).length - 1;
      if (count === 0) throw new Error(`old_string not found in ${p}`);
      if (count > 1 && !replace_all) {
        throw new Error(`old_string appears ${count} times in ${p}; add more context or set replace_all=true`);
      }
      await ctx?.onBeforeWrite?.(abs);
      const next = replace_all ? raw.split(old_string).join(new_string) : raw.replace(old_string, new_string);
      fs.writeFileSync(abs, next, 'utf8');
      return `edited ${p} (${count} replacement${count > 1 ? 's' : ''})`;
    },
  };

  const list_dir = {
    name: 'list_dir',
    description: 'List a directory in the workspace. Directories are shown with a trailing slash.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Directory relative to workspace root (default: root)' },
      },
    },
    execute({ path: p }) {
      const abs = resolveWithin(root, p ?? '.');
      const stat = fs.statSync(abs);
      if (!stat.isDirectory()) throw new Error(`not a directory: ${p ?? '.'}`);
      const entries = fs.readdirSync(abs, { withFileTypes: true }).slice(0, 500);
      return entries.map((e) => e.name + (e.isDirectory() ? '/' : '')).join('\n') || '(empty)';
    },
  };

  /** 递归收集一个目录下的所有文件（用于快照与统计）。 */
  const walkFiles = (dir, out = [], limit = MAX_SNAPSHOT_FILES) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walkFiles(abs, out, limit);
      } else {
        out.push(abs);
        if (out.length > limit) throw new Error(`directory has more than ${limit} files; delete in smaller batches`);
      }
    }
    return out;
  };

  const make_dir = {
    name: 'make_dir',
    description: 'Create a directory in the workspace (parents included). No-op if it already exists.',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: { path: { type: 'string', description: 'Directory path relative to workspace root' } },
      required: ['path'],
    },
    execute({ path: p }) {
      const abs = resolveWithin(root, p);
      if (fs.existsSync(abs)) {
        if (fs.statSync(abs).isDirectory()) return `${p} already exists`;
        throw new Error(`${p} exists and is not a directory`);
      }
      fs.mkdirSync(abs, { recursive: true });
      return `created ${p}`;
    },
  };

  const delete_path = {
    name: 'delete_path',
    description:
      'Delete a file, or a directory with recursive:true. Unlike `rm` via run_command, this records an undo snapshot of every file it removes.',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Path relative to workspace root' },
        recursive: { type: 'boolean', description: 'Required to delete a non-empty directory' },
      },
      required: ['path'],
    },
    async execute({ path: p, recursive = false }, ctx) {
      const abs = resolveWithin(root, p);
      // 删工作区根目录是不可恢复的（快照本身也在里面），直接拒绝。
      if (abs === root) throw new Error('refusing to delete the workspace root');
      if (!fs.existsSync(abs)) throw new Error(`no such path: ${p}`);
      const st = fs.lstatSync(abs);

      if (!st.isDirectory()) {
        await ctx?.onBeforeWrite?.(abs);
        fs.rmSync(abs);
        return `deleted ${p}`;
      }

      const entries = fs.readdirSync(abs);
      if (entries.length && !recursive) {
        throw new Error(`${p} is a non-empty directory (${entries.length} entries); pass recursive:true to delete it`);
      }
      const files = walkFiles(abs);
      // 逐个记快照：undo 时按逆序把内容写回去，空目录自然被重建。
      for (const f of files) await ctx?.onBeforeWrite?.(f);
      fs.rmSync(abs, { recursive: true, force: true });
      return `deleted ${p} (${files.length} file${files.length === 1 ? '' : 's'})`;
    },
  };

  const copy_path = {
    name: 'copy_path',
    description: 'Copy a file or directory inside the workspace. Overwrites only when overwrite:true.',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: {
        from: { type: 'string', description: 'Source path relative to workspace root' },
        to: { type: 'string', description: 'Destination path relative to workspace root' },
        overwrite: { type: 'boolean', description: 'Allow replacing an existing destination' },
      },
      required: ['from', 'to'],
    },
    async execute({ from, to, overwrite = false }, ctx) {
      const src = resolveWithin(root, from);
      const dst = resolveWithin(root, to);
      if (!fs.existsSync(src)) throw new Error(`no such path: ${from}`);
      if (fs.existsSync(dst) && !overwrite) throw new Error(`${to} already exists; pass overwrite:true to replace it`);

      const st = fs.statSync(src);
      if (st.isDirectory()) {
        if (fs.existsSync(dst)) for (const f of walkFiles(dst)) await ctx?.onBeforeWrite?.(f);
        fs.cpSync(src, dst, { recursive: true, force: true });
        return `copied ${from} -> ${to} (directory)`;
      }
      await ctx?.onBeforeWrite?.(dst);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(src, dst);
      return `copied ${from} -> ${to}`;
    },
  };

  const read_files = {
    name: 'read_files',
    description:
      'Read several files in one call. Cheaper than repeated read_file round-trips when you already know which files matter.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        paths: { type: 'array', items: { type: 'string' }, description: 'File paths relative to workspace root (max 20)' },
        max_lines: { type: 'integer', description: 'Max lines per file (default 400)' },
      },
      required: ['paths'],
    },
    execute({ paths, max_lines = 400 }) {
      if (!Array.isArray(paths) || !paths.length) throw new Error('paths must be a non-empty array');
      if (paths.length > 20) throw new Error('at most 20 files per call');
      const perFile = Math.min(MAX_READ_LINES, Math.max(1, max_lines));
      const chunks = [];
      for (const p of paths) {
        const abs = resolveWithin(root, p);
        try {
          const stat = fs.statSync(abs);
          if (!stat.isFile()) {
            chunks.push(`=== ${p} ===\n(directory — use list_dir)`);
            continue;
          }
          const lines = fs.readFileSync(abs, 'utf8').split('\n');
          const shown = lines.slice(0, perFile);
          const more = lines.length > perFile ? `\n... [${lines.length - perFile} more lines]` : '';
          chunks.push(`=== ${p} (${lines.length} lines) ===\n${shown.join('\n')}${more}`);
        } catch (err) {
          chunks.push(`=== ${p} ===\n(error: ${err.message})`);
        }
      }
      return truncate(chunks.join('\n\n'), 60000);
    },
  };

  const tree = {
    name: 'tree',
    description:
      'Directory tree with sizes, skipping node_modules/.git/build. Faster than chaining list_dir when you need the shape of a project.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Root of the tree (default: workspace root)' },
        depth: { type: 'integer', description: 'Max depth (default 3, max 6)' },
      },
    },
    execute({ path: p, depth = 3 }) {
      const start = resolveWithin(root, p ?? '.');
      const maxDepth = Math.min(6, Math.max(1, depth));
      const lines = [];
      let count = 0;
      const recurse = (dir, prefix, level) => {
        if (level > maxDepth || count > 800) return;
        let entries;
        try {
          entries = fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => {
            if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.name.localeCompare(b.name);
          });
        } catch {
          return;
        }
        const visible = entries.filter((e) => !SKIP_DIRS.has(e.name));
        visible.forEach((entry, i) => {
          if (count++ > 800) return;
          const last = i === visible.length - 1;
          const abs = path.join(dir, entry.name);
          const branch = last ? '└─ ' : '├─ ';
          if (entry.isDirectory()) {
            lines.push(`${prefix}${branch}${entry.name}/`);
            recurse(abs, prefix + (last ? '   ' : '│  '), level + 1);
          } else {
            let size = 0;
            try {
              size = fs.statSync(abs).size;
            } catch {
              /* 并发删除等，忽略 */
            }
            lines.push(`${prefix}${branch}${entry.name} (${formatBytes(size)})`);
          }
        });
      };
      const stat = fs.statSync(start);
      if (!stat.isDirectory()) throw new Error(`not a directory: ${p ?? '.'}`);
      recurse(start, '', 1);
      if (count > 800) lines.push('... [truncated at 800 entries]');
      return lines.join('\n') || '(empty)';
    },
  };

  return [read_file, write_file, edit_file, list_dir, make_dir, delete_path, copy_path, read_files, tree];
}
