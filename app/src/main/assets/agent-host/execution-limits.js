// 工具执行限制 + 危险命令检测（能力 #27）。
//
// 两层防护，夹在审批与执行之间：
//   1. ToolExecutionLimits — 数量/体积/并发/超时的硬闸（防止 agent 失控循环）
//   2. DangerGuard         — 对 exec 类工具做命令黑名单模式匹配，
//                            命中即升级为必须人工审批，甚至在 bypass 模式下也拦
// 原则：宁可误拦（返回明确理由让模型换方案），不可放过真正的破坏性操作。

import { truncate } from './providers/base.js';

const DEFAULT_LIMITS = {
  maxToolCallsPerRun: 200,
  maxConcurrentTools: 4,
  maxOutputBytes: 1_000_000,
  maxArgsBytes: 200_000,
  defaultTimeoutMs: 60_000,
  maxTimeoutMs: 300_000,
};

/**
 * 把多个 AbortSignal 合成一个：任一触发即触发。
 * 没有这个的话，limits 自己的超时信号传不进工具——工具只听 ctx.signal
 * （agent 的 run 级信号），于是「60s 硬超时」对 run_command 之类从不生效。
 */
function linkedSignal(signals) {
  const ac = new AbortController();
  for (const s of signals) {
    if (!s) continue;
    if (s.aborted) {
      ac.abort(s.reason);
      return ac.signal;
    }
    s.addEventListener('abort', () => ac.abort(s.reason), { once: true });
  }
  return ac.signal;
}

export class ToolExecutionLimits {
  constructor(overrides = {}) {
    Object.assign(this, structuredClone(DEFAULT_LIMITS), overrides);
    this._runCounts = new Map(); // runId -> { calls, startedAt }
    this._active = 0;
  }

  /**
   * 每次工具调用前过闸。不抛异常，返回 { ok, reason, timeoutMs }。
   * concurrency:false 表示「并发由 ToolScheduler 统一管理」——多 agent 共享同一个
   * limits 时，这里的计数闸会把子 agent 的正常并行误判成超限。
   */
  check(tool, args, ctx, { concurrency = true } = {}) {
    const runId = ctx?.runId || '_global';
    let state = this._runCounts.get(runId);
    if (!state) {
      state = { calls: 0 };
      this._runCounts.set(runId, state);
      // 无人值守会连跑几百个 run：长期不清理会让这张表一直长大。
      if (this._runCounts.size > 2000) this._runCounts.clear();
    }
    if (state.calls >= this.maxToolCallsPerRun) {
      return { ok: false, reason: `tool call limit reached (${this.maxToolCallsPerRun} per run)`, code: 'LIMIT_CALLS' };
    }
    if (concurrency && this._active >= this.maxConcurrentTools) {
      return { ok: false, reason: `too many concurrent tools (${this.maxConcurrentTools})`, code: 'LIMIT_CONCURRENCY' };
    }
    const argsBytes = Buffer.byteLength(JSON.stringify(args ?? {}), 'utf8');
    if (argsBytes > this.maxArgsBytes) {
      return { ok: false, reason: `arguments too large (${argsBytes} > ${this.maxArgsBytes} bytes)`, code: 'LIMIT_ARGS' };
    }
    state.calls += 1;
    return { ok: true, timeoutMs: this.defaultTimeoutMs };
  }

  /** 包住执行：计数并发、在超时后 abort 子进程。 */
  async run(tool, args, ctx, execute, { concurrency = true } = {}) {
    const gate = this.check(tool, args, ctx, { concurrency });
    if (!gate.ok) {
      return { ok: false, output: `error: ${gate.reason}`, limited: true, code: gate.code };
    }
    this._active += 1;
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), gate.timeoutMs);
    // 工具拿到的 signal 是「run 级信号 + limit 级信号」的并集：
    // 用户取消和超时兜底两条路都能真正中断工具。
    const signal = linkedSignal([ctx?.signal, ac.signal]);
    try {
      const result = await execute(args, { ...ctx, signal, limitSignal: ac.signal });
      return {
        ok: result.ok !== false,
        output: truncate(String(result.output ?? ''), this.maxOutputBytes),
      };
    } catch (err) {
      if (ac.signal.aborted && !ctx?.signal?.aborted) {
        return { ok: false, output: `error: tool timed out after ${gate.timeoutMs / 1000}s`, code: 'LIMIT_TIMEOUT' };
      }
      throw err;
    } finally {
      clearTimeout(timer);
      this._active -= 1;
    }
  }

  reset(runId) {
    if (runId) this._runCounts.delete(runId);
    else this._runCounts.clear();
  }
}

/**
 * 危险命令模式。命中后：
 *   - 非 bypass 模式：照常走审批（审批文案里标注 danger）
 *   - bypass 模式：仍然拦下（bypass 是“别问我”，不是“允许我毁掉数据”）
 * 只匹配命令串本身，不解析 shell 语法——这是有意的：解析器反而会被绕过。
 */
const DANGER_PATTERNS = [
  { id: 'rm_rf_root', re: /\brm\s+(-[a-zA-Z]*\s+)*-?[a-zA-Z]*[rR][a-zA-Z]*[fF][a-zA-Z]*\s+(\/\s*$|\/\*|\*|~\/?\s*$)/, why: 'recursive delete of a broad path' },
  { id: 'rm_rf_home', re: /\brm\s+(-[a-zA-Z]*\s+)*-?[a-zA-Z]*[rR][a-zA-Z]*[fF][a-zA-Z]*\s+("?\$HOME\/?"?|~\/?\.?)/, why: 'recursive delete inside home' },
  { id: 'mkfs', re: /\bmkfs(\.\w+)?\s+\/dev\//, why: 'format a block device' },
  { id: 'dd_disk', re: /\bdd\b[^|;&]*\bof=\/dev\/(sd|vd|hd|mmcblk|nvme)/, why: 'raw write to a disk device' },
  { id: 'fork_bomb', re: /:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:/, why: 'fork bomb' },
  { id: 'chmod_777_root', re: /\bchmod\s+(-R\s+)?0?777\s+\//, why: 'world-writable system path' },
  { id: 'git_force_push', re: /\bgit\s+push\b[^|;&]*--force\b/, why: 'force push rewrites remote history' },
  { id: 'git_reset_hard', re: /\bgit\s+reset\s+--hard\b/, why: 'discards uncommitted work' },
  { id: 'curl_pipe_sh', re: /\b(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(ba|z|fi)?sh\b/, why: 'remote code piped straight into a shell' },
  { id: 'shutdown', re: /\b(shutdown|poweroff|reboot|halt)\b/, why: 'power state change' },
  { id: 'kill_all', re: /\bkill\s+-9\s+-1\b|\bkillall\b/, why: 'kills every process' },
  { id: 'overwrite_block', re: />\s*\/dev\/(sd|vd|hd|mmcblk|nvme)/, why: 'redirect overwrites a block device' },
];

export class DangerGuard {
  constructor({ extraPatterns = [], allowIds = [] } = {}) {
    this.patterns = [...DANGER_PATTERNS, ...extraPatterns].filter((p) => !allowIds.includes(p.id));
  }

  /**
   * @returns {{dangerous: boolean, id?: string, why?: string}}
   * 只对 exec 类工具生效；read/write 工具的破坏面由 workspace 隔离负责。
   */
  inspect(tool, args) {
    if (tool?.risk !== 'exec') return { dangerous: false };
    const command = String(args?.command ?? '');
    if (!command) return { dangerous: false };
    for (const p of this.patterns) {
      if (p.re.test(command)) return { dangerous: true, id: p.id, why: p.why };
    }
    return { dangerous: false };
  }
}
