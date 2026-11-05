// Git 工具（只读 / 写入两个入口）。
//
// 为什么拆成两个：`git status` 一天要跑几十次，如果和 `git commit` 共用一个 exec 风险的
// 工具，每次看状态都要点一次审批，实际没人受得了。只读子命令走 read（自动放行），
// 会改仓库的子命令走 write（照常询问）。
//
// 安全边界：
//   - 参数以数组直传 spawn，不经过 shell，天然没有注入面；
//   - 子命令走白名单，`config` / `remote` / `filter-branch` 这类能改变执行行为的命令不开放；
//   - cwd 固定在工作区，越界路径由 git 自己按仓库根处理。

import { spawn } from 'node:child_process';

const READ_SUBCOMMANDS = new Set([
  'status',
  'diff',
  'log',
  'show',
  'blame',
  'rev-parse',
  'ls-files',
  'shortlog',
  'describe',
]);

/** `git branch` / `git stash` / `git tag` 同时是读和写，按第一个参数区分。 */
const LIST_ONLY_FLAGS = new Set(['-l', '--list', '-v', '--verbose', '-a', '--all']);

const WRITE_SUBCOMMANDS = new Set(['add', 'commit', 'init', 'restore', 'checkout', 'switch', 'rm', 'mv', 'reset', 'tag']);

const MAX_OUTPUT = 30000;

function runGit(cwd, args, { timeoutMs = 20000 } = {}) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      // 关掉仓库配置里的「自动执行」面：不可信仓库（clone 下来的）可以在
      // .git/config 里埋 core.fsmonitor / diff.external / core.editor 之类，
      // 让一条自动放行的只读命令变成任意代码执行或 20s 挂起。
      // （alias 无法覆盖内置子命令，白名单按字面名校验，不受影响。）
      child = spawn(
        'git',
        ['-c', 'core.fsmonitor=false', '-c', 'diff.external=', '-c', 'core.editor=true', ...args],
        {
          cwd,
          env: {
            ...process.env,
            GIT_PAGER: 'cat',
            PAGER: 'cat',
            GIT_TERMINAL_PROMPT: '0',
            GIT_CONFIG_NOSYSTEM: '1',
            GIT_ATTR_NOSYSTEM: '1',
          },
        },
      );
    } catch (err) {
      reject(new Error(`git is not available on this device: ${err.message}`));
      return;
    }
    let out = '';
    let err = '';
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill();
      reject(new Error(`git ${args.join(' ')} timed out after ${timeoutMs / 1000}s`));
    }, timeoutMs);

    child.stdout.on('data', (b) => {
      if (out.length < MAX_OUTPUT) out += b.toString('utf8');
    });
    child.stderr.on('data', (b) => {
      if (err.length < MAX_OUTPUT) err += b.toString('utf8');
    });
    child.on('error', (e) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      // ENOENT = 设备上没装 git（Android 默认就是没有），要说人话。
      reject(
        e.code === 'ENOENT'
          ? new Error('git is not installed in this environment; use read_file/list_dir/grep instead, or install git first')
          : new Error(`git failed to start: ${e.message}`),
      );
    });
    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const body = [out.trim(), err.trim()].filter(Boolean).join('\n');
      // 非零退出必须如实变成失败：以前 resolve 出去会让 registry 判 ok:true，
      // agent / workflow 的失败分支（onError 等）永远看不到 git 报错。
      // 例外：--exit-code / --check / --quiet 的语义本身就是「用退出码表达结果」。
      const exitCodeIsData = code === 1 && /--(exit-code|check|quiet)/.test(args.join(' '));
      if (code !== 0 && !exitCodeIsData) {
        reject(new Error(`git ${args[0]} failed (exit ${code})\n${body || '(no output)'}`));
        return;
      }
      resolve(body || '(no output)');
    });
  });
}

function assertArgs(args) {
  if (!Array.isArray(args) || !args.length) throw new Error('args must be a non-empty string array, e.g. ["status","--short"]');
  for (const a of args) {
    if (typeof a !== 'string') throw new Error('every arg must be a string');
    if (a.length > 500) throw new Error('arg too long');
  }
  const sub = args[0];
  if (!sub || sub.startsWith('-')) throw new Error('first arg must be a git subcommand');
  return sub;
}

export function createGitTools({ workspaceRoot }) {
  const cwd = workspaceRoot;

  const git_read = {
    name: 'git_read',
    description:
      'Run a read-only git command in the workspace: status, diff, log, show, blame, rev-parse, ls-files, shortlog, describe, or "branch --list". Pass args as an array, e.g. ["diff","--stat"] or ["log","--oneline","-n","20"].',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        args: {
          type: 'array',
          items: { type: 'string' },
          description: 'Git arguments, subcommand first (e.g. ["status","--short"])',
        },
      },
      required: ['args'],
    },
    async execute({ args }) {
      const sub = assertArgs(args);
      const isListBranch = sub === 'branch' && (args.length === 1 || LIST_ONLY_FLAGS.has(args[1]));
      const isListStash = sub === 'stash' && (args.length === 1 || args[1] === 'list');
      if (!READ_SUBCOMMANDS.has(sub) && !isListBranch && !isListStash) {
        throw new Error(`"git ${sub}" is not a read-only subcommand here; use git_write`);
      }
      return runGit(cwd, args);
    },
  };

  const git_write = {
    name: 'git_write',
    description:
      'Run a git command that changes the repo: add, commit, init, restore, checkout, switch, rm, mv, reset, tag. Requires user approval. Pass args as an array, e.g. ["commit","-m","message"] or ["add","-A"].',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: {
        args: {
          type: 'array',
          items: { type: 'string' },
          description: 'Git arguments, subcommand first (e.g. ["add","-A"])',
        },
      },
      required: ['args'],
    },
    async execute({ args }) {
      const sub = assertArgs(args);
      if (!WRITE_SUBCOMMANDS.has(sub)) {
        throw new Error(`"git ${sub}" is not a write subcommand here; use git_read for status/diff/log`);
      }
      // 空 commit message 是最常见的误用，提前拦住，别让 git 自己报一堆用法。
      if (sub === 'commit' && !args.some((a) => a === '-m' || a === '--message' || a.startsWith('--message='))) {
        throw new Error('commit needs a message: ["commit","-m","..."]');
      }
      return runGit(cwd, args);
    },
  };

  return [git_read, git_write];
}
