// Shell tool. Runs a command inside the workspace as the app's own UID.
//
// 说明：原实现把「跑命令」抽象成一条「通道」（standard / root / shizuku / adb /
// accessibility 五级提权），那是手机操控层的一部分。该能力已移交 HPA，本仓库只保留
// 最基本的一条路径——以应用自身权限执行命令，供构建 / 测试 / git / 查文件使用。

import { exec, execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolveWithin } from './fs-tools.js';

/** Pick a usable shell: Android has no /bin/sh, desktop does. */
function pickShell() {
  const candidates = [
    process.env.HAPPY_ACA_SHELL,
    // Android (bionic/mksh) — must be tried first on device.
    '/system/bin/sh',
    '/bin/sh',
    '/bin/bash',
  ].filter(Boolean);
  for (const c of candidates) {
    try {
      if (existsSync(c)) return c;
    } catch {
      /* ignore */
    }
  }
  return undefined; // fall back to exec()'s default
}

function runLocal({ shell, argv, cwd, ms, ctx, fallbackCommand }) {
  return new Promise((resolve) => {
    const opts = {
      cwd,
      timeout: ms,
      maxBuffer: 32 * 1024 * 1024,
      windowsHide: true,
      env: {
        ...process.env,
        PWD: cwd,
        // Deterministic, non-interactive output: no colour codes, no paging,
        // and failures surface as non-zero exit codes rather than prompts.
        NO_COLOR: '1',
        TERM: 'dumb',
        CI: '1',
      },
    };

    const done = (err, stdout, stderr) => {
      const out = (stdout || '') + (stderr ? `\n[stderr]\n${stderr}` : '');
      if (err && err.killed) return resolve(`error: command timed out after ${ms / 1000}s\n${out}`);
      if (err && err.code) return resolve(`error: exit code ${err.code}\n${out}`);
      resolve(out.trim() || '(no output)');
    };

    // No POSIX shell on the box (stock Windows): hand the raw command to the
    // platform shell via exec(), which is exactly what this tool did before
    // channels existed. Trying to spawn a `-c` argv there yields ENOENT.
    const child = shell
      ? execFile(shell, argv, opts, done)
      : exec(fallbackCommand, opts, done);

    ctx?.signal?.addEventListener('abort', () => child.kill(), { once: true });
  });
}

export function createShellTool({ workspaceRoot } = {}) {
  const root = resolveWithin(workspaceRoot, '.');
  const legacyShell = pickShell();

  const execTool = {
    name: 'run_command',
    description:
      'Run a shell command inside the workspace (builds, tests, git, file inspection). ' +
      'Combined stdout/stderr, capped. Runs with the app\'s own privileges.',
    risk: 'exec',
    parameters: {
      type: 'object',
      properties: {
        command: { type: 'string' },
        cwd: { type: 'string', description: 'Working directory relative to workspace root (default: root)' },
        timeout: { type: 'integer', description: 'Seconds, default 60, max 300' },
      },
      required: ['command'],
    },
    async execute({ command, cwd, timeout }, ctx) {
      const abs = resolveWithin(root, cwd ?? '.');
      const ms = Math.min(300, Math.max(1, timeout ?? 60)) * 1000;
      return runLocal({ shell: legacyShell, argv: ['-c', command], cwd: abs, ms, ctx, fallbackCommand: command });
    },
  };

  return [execTool];
}
