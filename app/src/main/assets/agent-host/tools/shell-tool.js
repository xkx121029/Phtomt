// Shell tool. Runs a command inside the workspace as the app's own UID.
//
// 说明：原实现把「跑命令」抽象成一条「通道」（standard / root / shizuku / adb /
// accessibility 五级提权），那是手机操控层的一部分。该能力已移交 HPA，本仓库只保留
// 最基本的一条路径——以应用自身权限执行命令，供构建 / 测试 / git / 查文件使用。

import { exec, execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolveWithin } from './fs-tools.js';

/**
 * 子进程能看到的变量：白名单，外加一条「名字像密钥就不传」的黑名单。
 *
 * 以前是整个 process.env 原样继承，而宿主自己是从 LocalAgent 的 env 起来的，
 * 里面带着 HAPPY_ACA_API_KEY 与 HAPPY_ACA_TOKEN。于是一句 `printenv`
 * 就能把 provider 密钥和桥 token 原样交到模型手里（进而进远端日志）。
 * 用户从没同意过这件事，模型也不需要它们：跑构建、跑 git 用不到 provider key。
 */
const ENV_ALLOW = [
  // 进程 / 动态库查找（缺了 node、git、java 全都起不来）
  'PATH', 'Path', 'PATHEXT', 'LD_LIBRARY_PATH', 'DYLD_LIBRARY_PATH',
  // 主目录与临时目录
  'HOME', 'TMPDIR', 'TMP', 'TEMP',
  // Windows 平台必需（cmd.exe 与 CRLF 处理都读这些）
  'SystemRoot', 'SYSTEMROOT', 'windir', 'COMSPEC', 'ComSpec', 'USERPROFILE',
  'APPDATA', 'LOCALAPPDATA', 'ProgramData', 'ProgramFiles', 'ProgramFiles(x86)',
  'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE',
  // 语言与终端
  'LANG', 'LANGUAGE', 'LC_ALL', 'LC_CTYPE', 'TZ',
  // Android 上构建链要用的目录
  'ANDROID_ROOT', 'ANDROID_DATA', 'ANDROID_STORAGE', 'ANDROID_HOME', 'ANDROID_SDK_ROOT',
  'JAVA_HOME', 'GRADLE_USER_HOME',
  // node 自己的加载路径与 CA 包（宿主起子进程时同样需要）
  'NODE_PATH', 'NODE_EXTRA_CA_CERTS',
  // 宿主自己设的、非敏感的运行时约定
  'HAPPY_ACA_SHELL', 'HAPPY_ACA_ANDROID', 'HAPPY_ACA_WORKSPACE', 'HAPPY_ACA_STATE_DIR',
];

/** 名字像密钥的一律不传，哪怕有人图方便先把它加进了白名单。 */
const ENV_SECRET = /(^|_)(API_?KEY|KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL)(_|$)/i;

function childEnv(cwd) {
  const env = {};
  for (const k of ENV_ALLOW) {
    if (ENV_SECRET.test(k)) continue;
    if (process.env[k] !== undefined) env[k] = process.env[k];
  }
  // 确定性、非交互的输出：无颜色码、不分页，失败以退出码体现而不是卡在提示符上。
  env.PWD = cwd;
  env.NO_COLOR = '1';
  env.TERM = 'dumb';
  env.CI = '1';
  return env;
}

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
      env: childEnv(cwd),
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
