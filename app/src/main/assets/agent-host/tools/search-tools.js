// Search tools: glob (filename patterns with ** support) and grep (regex over file contents).

import fs from 'node:fs';
import path from 'node:path';
import { resolveWithin } from './fs-tools.js';

const SKIP_DIRS = new Set(['node_modules', '.git', '.gradle', 'build', '.cxx', 'dist', '.cache', '.idea']);
const MAX_FILE_BYTES = 1024 * 1024;
const MAX_RESULTS = 100;

function* walk(dir) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue;
      yield* walk(full);
    } else if (e.isFile()) {
      yield full;
    }
  }
}

function segRx(pat) {
  let out = '';
  for (const ch of pat) {
    if (ch === '*') out += '.*';
    else if (ch === '?') out += '.';
    else out += ch.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${out}$`);
}

export function matchGlob(relPath, pattern) {
  const parts = pattern.split('/');
  const segs = relPath.split('/');
  const m = (pi, si) => {
    if (pi === parts.length) return si === segs.length;
    if (parts[pi] === '**') {
      for (let k = si; k <= segs.length; k++) if (m(pi + 1, k)) return true;
      return false;
    }
    if (si >= segs.length) return false;
    return segRx(parts[pi]).test(segs[si]) && m(pi + 1, si + 1);
  };
  return m(0, 0);
}

function toRel(root, abs) {
  return path.relative(root, abs).split(path.sep).join('/');
}

export function createSearchTools({ workspaceRoot }) {
  const root = path.resolve(workspaceRoot);

  const glob = {
    name: 'glob',
    description:
      'Find files by name pattern, e.g. "**/*.ts" or "*.kt". The pattern is matched against paths relative to the search directory. Returns paths relative to workspace root.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        pattern: { type: 'string' },
        path: { type: 'string', description: 'Subdirectory to search in (default: workspace root)' },
      },
      required: ['pattern'],
    },
    execute({ pattern, path: base }) {
      const abs = resolveWithin(root, base ?? '.');
      const results = [];
      for (const f of walk(abs)) {
        // pattern 相对「搜索目录」匹配：glob({path:'src', pattern:'*.js'}) 找的是
        // src/foo.js。以前按 workspace 根匹配，子目录里搜非 ** 模式恒为空。
        if (matchGlob(toRel(abs, f), pattern)) results.push(toRel(root, f));
        if (results.length >= 500) break;
      }
      return results.length ? results.join('\n') : '(no matches)';
    },
  };

  const grep = {
    name: 'grep',
    description: 'Search file contents with a JavaScript regular expression. Returns "path:line: text" matches.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        pattern: { type: 'string', description: 'Regular expression' },
        path: { type: 'string', description: 'Subdirectory to search in (default: workspace root)' },
        glob: { type: 'string', description: 'Optional filename filter, e.g. "*.js"' },
      },
      required: ['pattern'],
    },
    execute({ pattern, path: base, glob: filter }) {
      let rx;
      try {
        rx = new RegExp(pattern);
      } catch (err) {
        throw new Error(`invalid regex: ${err.message}`);
      }
      const abs = resolveWithin(root, base ?? '.');
      const filterRx = filter ? segRx(filter) : null;
      const out = [];
      outer: for (const f of walk(abs)) {
        if (filterRx && !filterRx.test(path.basename(f))) continue;
        let stat;
        try {
          stat = fs.statSync(f);
        } catch {
          continue;
        }
        if (stat.size > MAX_FILE_BYTES) continue;
        let text;
        try {
          text = fs.readFileSync(f, 'utf8');
        } catch {
          continue;
        }
        if (text.includes('\0')) continue; // binary
        const rel = toRel(root, f);
        const lines = text.split('\n');
        for (let i = 0; i < lines.length; i++) {
          if (rx.test(lines[i])) {
            out.push(`${rel}:${i + 1}: ${lines[i].trim().slice(0, 300)}`);
            if (out.length >= MAX_RESULTS) break outer;
          }
        }
      }
      return out.length ? out.join('\n') : '(no matches)';
    },
  };

  return [glob, grep];
}
