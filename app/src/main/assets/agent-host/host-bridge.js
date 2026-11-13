// Host bridge: lets the embedded agent call *into* the Android app.
//
// Why this exists
// ---------------
// The agent runs as a Node process spawned by the app. Everything so far flows
// one way: Kotlin writes a JSON-RPC request to the host's stdin, the host writes
// events/responses to stdout. But some capabilities can only be implemented in
// Kotlin — the built-in browser's WebView, the device workspace files, the code
// editor / preview windows, the Logcat + SQLite toolbox. Those need a reverse call.
//
// Protocol
// --------
// The pipe stays newline-delimited JSON. The host asks with
//   {"id":"h1","type":"request","method":"browser_open","params":{...}}
// and the app answers on the same stdout it already reads:
//   {"id":"h1","type":"response","result":{...}}   |   {"id":"h1","type":"error","error":"..."}
//
// The `type` field is what keeps the two directions apart: host->app lines are
// "request", app->host lines are "event"/"response"/"error". A host line that
// arrives without `type` is a legacy app request and is handled as before.

import { EventEmitter } from 'node:events';

/** 宿主应用没有实现该能力（未启动 / 版本过旧 / 该通道不可用）。 */
export class HostUnavailableError extends Error {
  constructor(message, { method } = {}) {
    super(message);
    this.name = 'HostUnavailableError';
    this.method = method;
  }
}

export class HostBridge extends EventEmitter {
  #pending = new Map();
  #seq = 0;
  #capabilities = null;
  #probing = null;

  constructor({ send = null, timeoutMs = 30000, enabled = true, token = null } = {}) {
    super();
    this.send = send;
    this.timeoutMs = timeoutMs;
    this.enabled = enabled;
    // 与宿主 App 约定的共享密钥：每个反向请求都带上，App 侧校验后才执行
    // 设备侧能力（浏览器/工作区/编辑器/预览/工具箱）。没有它，任何能写进
    // 这条管道的一方都能驱动设备能力。
    this.token = token || null;
  }

  /** 绑定输出通道（host-stdio 拿到 stdout writer 后调用）。 */
  attach(send) {
    this.send = send;
    return this;
  }

  /** 宿主是否已经声明过自己的能力表（有表 = 支持反向调用）。 */
  get capabilities() {
    return this.#capabilities;
  }

  /**
   * 这条 stdin 行是不是给桥的响应？
   * 有 `id` 且 `type` 是 response/error，且 id 在本桥的待处理表里 —— 三者缺一不可，
   * 否则会误吞应用自己发的请求（应用的 id 是 "c1/c2/..."，桥的是 "h1/h2/..."）。
   */
  accepts(line) {
    if (!line || typeof line !== 'object') return false;
    const type = line.type;
    if (type !== 'response' && type !== 'error') return false;
    return this.#pending.has(String(line.id));
  }

  /** 路由一条宿主发来的响应；返回是否被消费。 */
  handle(line) {
    if (!this.accepts(line)) return false;
    const entry = this.#pending.get(String(line.id));
    this.#pending.delete(String(line.id));
    clearTimeout(entry.timer);
    if (line.type === 'error') {
      // 宿主明确拒绝（凭据不匹配）时给一句能对症的话：以前宿主静默丢弃，
      // 这里只能等满 30s 变成 "host did not answer"，排查方向完全是错的。
      const text = line.code === 'HOST_UNAUTHORIZED'
        ? 'host rejected the request (token mismatch); reopen the coding page and retry'
        : String(line.error ?? 'host error');
      const err = new Error(text);
      err.code = line.code;
      entry.reject(err);
    } else {
      entry.resolve(line.result ?? {});
    }
    return true;
  }

  /**
   * 向宿主发一次调用。宿主没接线、能力表里没声明、或超时，都抛错而不是静默返回空值——
   * 上层据此降级（比如 shell 换通道）并给出可读的解释。
   */
  call(method, params = {}, { timeoutMs = this.timeoutMs } = {}) {
    if (!this.enabled || typeof this.send !== 'function') {
      return Promise.reject(new HostUnavailableError('no host bridge: this agent runs standalone', { method }));
    }
    const id = `h${++this.#seq}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(new HostUnavailableError(`host did not answer ${method} within ${timeoutMs}ms`, { method }));
      }, timeoutMs);
      this.#pending.set(id, { resolve, reject, timer, method });
      try {
        this.send({ id, type: 'request', method, params: this.token ? { ...params, token: this.token } : params });
      } catch (err) {
        this.#pending.delete(id);
        clearTimeout(timer);
        reject(new HostUnavailableError(`cannot reach host: ${err.message}`, { method }));
      }
    });
  }

  /** 探一次宿主能力表；失败不算致命，缓存结果免得每个工具调用都探。 */
  async probe({ force = false } = {}) {
    if (this.#capabilities && !force) return this.#capabilities;
    if (this.#probing) return this.#probing;
    this.#probing = (async () => {
      try {
        const caps = await this.call('host_capabilities', {}, { timeoutMs: 5000 });
        this.#capabilities = {
          platform: caps.platform ?? 'unknown',
          version: caps.version ?? '',
          channels: Array.isArray(caps.channels) ? caps.channels : [],
          capabilities: Array.isArray(caps.capabilities) ? caps.capabilities : [],
          ...caps,
        };
      } catch (err) {
        // 独立跑（桌面 / 测试）时没有宿主，这是正常状态而不是错误。
        this.#capabilities = { platform: 'standalone', version: '', channels: [], capabilities: [], error: err.message };
      } finally {
        this.#probing = null;
      }
      return this.#capabilities;
    })();
    return this.#probing;
  }

  /** 宿主是否声明了某个能力开关。 */
  supports(name) {
    return Boolean(this.#capabilities?.capabilities?.includes(name));
  }

  /** 未完成的调用数（退出前等待收尾用）。 */
  get pending() {
    return this.#pending.size;
  }

  /** 宿主退出：把所有在途调用一次性失败掉，别让它们挂到超时。 */
  fail(reason) {
    for (const [, entry] of this.#pending) {
      clearTimeout(entry.timer);
      entry.reject(new HostUnavailableError(reason ?? 'host closed', { method: entry.method }));
    }
    this.#pending.clear();
  }
}
