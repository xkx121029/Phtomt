// 工具并发调度器：把「同一轮里的多个工具调用」安全地并行起来。
//
// 背景：provider 一轮可以吐出多个 tool_call（并行函数调用），旧实现是逐个 await，
// 一个 3 秒的 grep 会把同轮的 5 个 read_file 一起拖成 15 秒。这里按资源分类：
//   - read                     ：无锁，完全并行（读之间没有冲突）
//   - write（有明确目标路径）：同一路径串行（避免 read-modify-write 覆盖），
//                              不同路径并行
//   - write（无路径，如 git） ：全局写锁串行
//   - exec（shell 命令）       ：全局 exec 锁串行（两次构建/测试并行只会互相踩）
//
// 死锁防护：多键按字典序依次获取，所有调用方顺序一致，不会出现循环等待。

import path from 'node:path';

/** 路径型参数名：这些字段里的值参与锁键计算。 */
const PATH_ARGS = ['path', 'file', 'file_path', 'from', 'to', 'dest', 'destination', 'source', 'target'];

/** 从工具参数里收集会影响文件系统的路径（相对路径原样归一，绝对路径取 basename 的完整串）。 */
function pathsOf(args) {
  const out = [];
  for (const key of PATH_ARGS) {
    if (typeof args?.[key] === 'string' && args[key]) out.push(args[key]);
  }
  if (Array.isArray(args?.paths)) {
    for (const p of args.paths) if (typeof p === 'string' && p) out.push(p);
  }
  return out;
}

function normalize(p) {
  // Windows 与 Android 的路径分隔符都归一成 /，避免同一文件两种写法锁不上。
  return String(p).replace(/\\/g, '/');
}

/** 带键的互斥锁（同一 key 的持有者排队，不同 key 互不影响）。 */
export class ResourceLocks {
  constructor() {
    this.tails = new Map();
    this.waiting = 0;
  }

  acquireOne(key) {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const prev = this.tails.get(key) || Promise.resolve();
    const next = prev.then(() => gate);
    this.tails.set(key, next);
    const cleanup = () => {
      if (this.tails.get(key) === next) this.tails.delete(key);
    };
    // 链尾结束后清掉，避免长期运行后 Map 里堆满已释放的 key。
    next.then(cleanup, cleanup);
    this.waiting += 1;
    return prev.then(() => {
      this.waiting -= 1;
      let done = false;
      return () => {
        if (done) return;
        done = true;
        release();
      };
    });
  }
}

/** 计数信号量：限制同时在跑的工具数量。 */
export class Semaphore {
  constructor(limit) {
    this.limit = Math.max(1, Math.floor(limit) || 1);
    this.active = 0;
    this.waiters = [];
  }

  acquire() {
    if (this.active < this.limit) {
      this.active += 1;
      return Promise.resolve();
    }
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  release() {
    const next = this.waiters.shift();
    if (next) next();
    else this.active = Math.max(0, this.active - 1);
  }
}

export class ToolScheduler {
  constructor({ maxConcurrent = 4, locks = new ResourceLocks() } = {}) {
    this.maxConcurrent = Math.max(1, Math.floor(maxConcurrent) || 1);
    this.locks = locks;
    this.sem = new Semaphore(this.maxConcurrent);
  }

  /** 计算一次工具调用需要持有的锁键。 */
  keysFor(tool, args) {
    if (!tool) return [];
    // 工具可以显式声明「不要调度器管我」（比如 spawn_subagents：它内部自己并发，
    // 若再占用全局写锁会和子 agent 的写工具形成环路）。
    if (tool.concurrency === 'none') return [];
    const risk = tool.risk || 'read';
    if (risk === 'read') {
      const paths = pathsOf(args);
      // 读也按路径串行：和同一文件的写冲突会读到半截内容，
      // 「先读后写」是 agent 铁律，读拿到旧内容比慢一点危险得多。
      return paths.map((p) => `p:${normalize(p)}`);
    }
    if (risk === 'exec') return ['exec'];
    const paths = pathsOf(args);
    return paths.length ? paths.map((p) => `p:${normalize(p)}`) : ['workspace-write'];
  }

  /**
   * 并发执行一批调用，返回与 items 等长、同序的结果数组。
   * execute(item) 内部已经负责把异常转成 { ok, output }，这里不再吞错。
   */
  async runAll(items, execute) {
    if (!items?.length) return [];
    return Promise.all(
      items.map(async (item) => {
        const keys = [...new Set(this.keysFor(item.tool, item.args))].sort();
        await this.sem.acquire();
        const releases = [];
        try {
          for (const key of keys) releases.push(await this.locks.acquireOne(key));
          return await execute(item);
        } finally {
          for (const release of releases) release();
          this.sem.release();
        }
      }),
    );
  }

  stats() {
    return { maxConcurrent: this.maxConcurrent, active: this.sem.active, waiting: this.sem.waiters.length };
  }
}

/** 给测试/调试用：从路径参数推出锁键。 */
export function lockKeysFor(tool, args) {
  return new ToolScheduler().keysFor(tool, args);
}

export { pathsOf as collectToolPaths, normalize as normalizeLockPath };
