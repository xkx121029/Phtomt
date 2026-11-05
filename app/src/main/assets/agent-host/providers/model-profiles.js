// 多套模型配置 + 密钥池 + 任务绑定（能力 #7）。
//
// 形态：
//   - 多个「配置档」（profile）：每个档 = 一个 preset + key + model + 参数
//   - 每个档可挂多个 key（密钥池），轮询使用，某个 key 失败自动切下一个
//   - 任务绑定：按任务类型（chat / summarize / title / embed）选档，
//     例如便宜模型跑总结、强模型跑主对话
// 落盘为 JSON（settings 目录下），Android 壳通过 RPC 读写。

import fs from 'node:fs';
import path from 'node:path';
import { createProviderFromPreset, presetById } from './presets.js';
import { atomicWriteFileSync } from '../util/atomic-write.js';

/** 内置任务类型。未知任务回落到 'chat'。 */
export const TASK_KINDS = ['chat', 'summarize', 'title', 'embed', 'judge'];

export class ModelProfileStore {
  #profiles = new Map();
  #bindings = new Map();
  #file;

  constructor({ persistPath } = {}) {
    this.#file = persistPath || null;
    if (this.#file) this.#load();
  }

  /** 新增/更新一个配置档。keys 为字符串数组（密钥池）。 */
  upsert(profile) {
    if (!profile?.id) throw new Error('profile.id is required');
    const keys = Array.isArray(profile.keys) ? profile.keys.filter(Boolean) : [];
    const rec = {
      id: String(profile.id),
      label: profile.label || profile.id,
      presetId: profile.presetId || 'custom',
      baseUrl: profile.baseUrl || '',
      model: profile.model || '',
      keys,
      extraHeaders: profile.extraHeaders || {},
      numCtx: profile.numCtx,
      keepAlive: profile.keepAlive,
      enabled: profile.enabled !== false,
      createdAt: profile.createdAt || new Date().toISOString(),
    };
    this.#profiles.set(rec.id, rec);
    this.#save();
    return rec;
  }

  remove(id) {
    const ok = this.#profiles.delete(id);
    for (const [task, pid] of this.#bindings) {
      if (pid === id) this.#bindings.delete(task);
    }
    this.#save();
    return ok;
  }

  get(id) {
    return this.#profiles.get(id) ?? null;
  }

  list() {
    // keys 是密钥池，绝不外泄：只回个数。落盘文件本身仍是明文（状态目录在
    // 应用私有空间），要更进一步需要系统 keystore，见 ROADMAP。
    return [...this.#profiles.values()].map(({ keys, ...rest }) => ({ ...rest, keyCount: keys.length }));
  }

  /** 任务绑定：task -> profileId。 */
  bind(task, profileId) {
    if (!TASK_KINDS.includes(task)) throw new Error(`unknown task kind: ${task}`);
    if (!this.#profiles.has(profileId)) throw new Error(`unknown profile: ${profileId}`);
    this.#bindings.set(task, profileId);
    this.#save();
  }

  unbind(task) {
    this.#bindings.delete(task);
    this.#save();
  }

  bindings() {
    return Object.fromEntries(this.#bindings);
  }

  /** 取某任务绑定的档；未绑定时回落到第一个启用的档。 */
  profileForTask(task = 'chat') {
    const id = this.#bindings.get(task);
    if (id && this.#profiles.has(id)) return this.#profiles.get(id);
    const first = [...this.#profiles.values()].find((p) => p.enabled);
    return first ?? null;
  }

  /**
   * 为某任务造一个带密钥池轮询的 Provider。
   * 返回 { provider, profile }；provider.stream 内部自动换 key 重试。
   */
  providerForTask(task = 'chat') {
    const profile = this.profileForTask(task);
    if (!profile) return { provider: null, profile: null };
    return { provider: new KeyedProvider(profile), profile };
  }

  #load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this.#file, 'utf8'));
      for (const p of raw.profiles || []) this.#profiles.set(p.id, p);
      for (const [t, id] of Object.entries(raw.bindings || {})) this.#bindings.set(t, id);
    } catch {
      /* 无文件或损坏：空库起步 */
    }
  }

  #save() {
    if (!this.#file) return;
    try {
      atomicWriteFileSync(
        this.#file,
        JSON.stringify(
          { profiles: [...this.#profiles.values()], bindings: Object.fromEntries(this.#bindings) },
          null,
          2,
        ),
      );
    } catch {
      /* 落盘尽力而为 */
    }
  }
}

/**
 * 密钥池 Provider：包住一个 profile，stream 时按 keys 轮询；
 * 遇到 401/403/429 类错误自动换下一个 key 重试（最多 keys.length 次）。
 */
export class KeyedProvider {
  constructor(profile) {
    this.profile = profile;
    this._cursor = 0;
  }

  #nextKey() {
    const keys = this.profile.keys?.length ? this.profile.keys : [''];
    const key = keys[this._cursor % keys.length];
    this._cursor += 1;
    return key;
  }

  #build(key) {
    return createProviderFromPreset({
      presetId: this.profile.presetId,
      apiKey: key,
      baseUrl: this.profile.baseUrl,
      model: this.profile.model,
      extraHeaders: this.profile.extraHeaders,
      numCtx: this.profile.numCtx,
      keepAlive: this.profile.keepAlive,
    });
  }

  get model() {
    return this.profile.model || presetById(this.profile.presetId)?.defaultModel || '';
  }

  async probe({ signal } = {}) {
    const key = this.#nextKey();
    const provider = this.#build(key);
    if (typeof provider.probe === 'function') return provider.probe({ signal });
    return { ok: true, model: this.model };
  }

  async *stream(args) {
    const attempts = Math.max(1, this.profile.keys?.length || 1);
    let lastErr;
    for (let i = 0; i < attempts; i++) {
      const key = this.#nextKey();
      const provider = this.#build(key);
      let produced = false;
      try {
        for await (const ev of provider.stream(args)) {
          produced = true;
          yield ev;
        }
        return;
      } catch (err) {
        lastErr = err;
        // 已经吐出去的事件无法收回，一旦开始产出就不能换 key 重试。
        if (produced) throw err;
        const status = err?.status;
        const retryable = status === 401 || status === 403 || status === 429 || status === 402;
        if (!retryable) throw err;
      }
    }
    throw lastErr || new Error('all keys exhausted');
  }
}
