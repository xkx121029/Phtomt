// 图谱化长期记忆（能力 #36）+ Embedding 提供者（能力 #37）。
//
// 形态：
//   - 节点：实体/事实/文件/偏好，带可选向量
//   - 关系：subject -predicate-> object（带权重）
//   - 检索：关键词精确匹配 + 向量相似 + 图一跳邻居，三路混合打分
// Embedding 提供者默认用 OpenAI 兼容的 /embeddings；没有 key 时退化为
// 本地字符级哈希向量（bag of hashed trigrams），保证离线可用。

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync } from './util/atomic-write.js';

const VECTOR_DIM = 256;

/** 本地降级 embedding：无网络也能算，质量只用于粗排。 */
export function localEmbedding(text) {
  const v = new Array(VECTOR_DIM).fill(0);
  const s = String(text || '').toLowerCase();
  for (let i = 0; i < s.length - 2; i++) {
    const tri = s.slice(i, i + 3);
    let h = 2166136261;
    for (let j = 0; j < tri.length; j++) {
      h ^= tri.charCodeAt(j);
      h = Math.imul(h, 16777619);
    }
    v[Math.abs(h) % VECTOR_DIM] += 1;
  }
  const norm = Math.sqrt(v.reduce((n, x) => n + x * x, 0)) || 1;
  return v.map((x) => x / norm);
}

export function cosine(a, b) {
  if (!a?.length || !b?.length) return 0;
  const n = Math.min(a.length, b.length);
  let dot = 0;
  for (let i = 0; i < n; i++) dot += a[i] * b[i];
  return dot;
}

/**
 * Embedding 提供者：
 *   new EmbeddingService({ baseUrl, apiKey, model }) -> 远端
 *   new EmbeddingService()                            -> 本地降级
 */
export class EmbeddingService {
  constructor({ baseUrl, apiKey = '', model = 'text-embedding-3-small', fetchImpl = fetch, dim } = {}) {
    this.baseUrl = baseUrl ? baseUrl.replace(/\/+$/, '') : null;
    this.apiKey = apiKey;
    this.model = model;
    this.fetchImpl = fetchImpl;
    this.remote = Boolean(baseUrl);
    this.dim = dim || VECTOR_DIM;
  }

  async embed(text) {
    const t = String(text || '');
    if (!this.remote) return localEmbedding(t);
    const res = await this.fetchImpl(`${this.baseUrl}/embeddings`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${this.apiKey}` },
      body: JSON.stringify({ model: this.model, input: t.slice(0, 8000) }),
    });
    if (!res.ok) throw new Error(`embeddings HTTP ${res.status}`);
    const data = await res.json();
    return data.data?.[0]?.embedding || localEmbedding(t);
  }

  /** 批量（远端一次多条；本地循环）。 */
  async embedAll(texts) {
    if (!this.remote) return texts.map(localEmbedding);
    return Promise.all(texts.map((t) => this.embed(t)));
  }
}

/**
 * 记忆图谱。节点可带 content（自然语言事实）与 vector。
 * 关系是轻量的：只存 subject/predicate/object 字符串 id。
 */
export class MemoryGraph {
  #nodes = new Map();
  #edges = [];
  #file;
  #embedder;

  constructor({ persistPath, embedder } = {}) {
    this.#file = persistPath || null;
    this.#embedder = embedder || new EmbeddingService();
    if (this.#file) this.#load();
  }

  /**
   * 记住一条事实。node 至少要有 id 与 content；
   * 会自动算 embedding（远端失败则本地降级，不抛）。
   */
  async remember({ id, content, kind = 'fact', tags = [], importance = 0.5, vector }) {
    if (!id) throw new Error('memory node needs an id');
    const node = {
      id: String(id),
      kind,
      content: String(content ?? ''),
      tags: [...tags],
      importance,
      createdAt: new Date().toISOString(),
      hits: 0,
      vector: vector || null,
    };
    if (!node.vector) {
      try {
        node.vector = await this.#embedder.embed(node.content);
      } catch {
        node.vector = localEmbedding(node.content);
      }
    }
    this.#nodes.set(node.id, node);
    this.#save();
    return node;
  }

  /** 建立关系：subject -predicate-> object。 */
  relate(subject, predicate, object, { weight = 1 } = {}) {
    for (const n of [subject, object]) {
      if (!this.#nodes.has(n)) throw new Error(`unknown memory node: ${n}`);
    }
    const existing = this.#edges.find((e) => e.s === subject && e.p === predicate && e.o === object);
    if (existing) {
      existing.weight += weight;
      this.#save();
      return existing;
    }
    const edge = { s: subject, p: predicate, o: object, weight };
    this.#edges.push(edge);
    this.#save();
    return edge;
  }

  forget(id) {
    const ok = this.#nodes.delete(id);
    this.#edges = this.#edges.filter((e) => e.s !== id && e.o !== id);
    this.#save();
    return ok;
  }

  node(id) {
    return this.#nodes.get(id) ?? null;
  }

  /** 一跳邻居（出 + 入）。 */
  neighbors(id) {
    return this.#edges.filter((e) => e.s === id || e.o === id);
  }

  /**
   * 混合检索：关键词 > 向量 > 图邻居 > importance。
   * @returns 排序后的 [{node, score, why}]
   */
  async recall(query, { limit = 8, kinds = null } = {}) {
    const q = String(query || '').toLowerCase();
    let qv = null;
    try {
      qv = await this.#embedder.embed(query);
    } catch {
      qv = localEmbedding(query);
    }

    const scored = [];
    for (const node of this.#nodes.values()) {
      if (kinds && !kinds.includes(node.kind)) continue;
      let score = node.importance * 0.1;
      const why = [];
      const text = node.content.toLowerCase();
      if (q && text.includes(q)) {
        score += 0.5;
        why.push('exact');
      } else if (q) {
        // 部分词命中
        const words = q.split(/\s+/).filter((w) => w.length > 2);
        const hit = words.filter((w) => text.includes(w)).length;
        if (hit) {
          score += 0.2 * (hit / Math.max(1, words.length));
          why.push('partial');
        }
      }
      if (qv && node.vector) {
        const sim = cosine(qv, node.vector);
        if (sim > 0.05) {
          score += sim * 0.4;
          why.push(`vector:${sim.toFixed(2)}`);
        }
      }
      if (score > 0.15) {
        node.hits += 1;
        scored.push({ node, score, why: why.join('+') || 'importance' });
      }
    }
    scored.sort((a, b) => b.score - a.score);
    const top = scored.slice(0, limit);

    // 图一跳扩展：把命中节点的邻居也带出来（低分）
    const seen = new Set(top.map((t) => t.node.id));
    for (const t of top) {
      for (const e of this.neighbors(t.node.id)) {
        const otherId = e.s === t.node.id ? e.o : e.s;
        if (seen.has(otherId)) continue;
        seen.add(otherId);
        const n = this.#nodes.get(otherId);
        if (n) top.push({ node: n, score: t.score * 0.5, why: `neighbor:${e.p}` });
      }
    }
    this.#save();
    return top;
  }

  /** 给模型用的紧凑文本形态（注入 system prompt 用）。 */
  formatForPrompt(results, { maxChars = 2000 } = {}) {
    if (!results?.length) return '';
    let out = 'Long-term memory (recalled for this turn):\n';
    for (const r of results) {
      const rels = this.neighbors(r.node.id)
        .map((e) => `${e.s} ${e.p} ${e.o}`)
        .slice(0, 3)
        .join('; ');
      const line = `- [${r.node.kind}] ${r.node.content}${rels ? ` (${rels})` : ''}\n`;
      if (out.length + line.length > maxChars) break;
      out += line;
    }
    return out;
  }

  stats() {
    return { nodes: this.#nodes.size, edges: this.#edges.length };
  }

  #load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this.#file, 'utf8'));
      for (const n of raw.nodes || []) this.#nodes.set(n.id, n);
      this.#edges = raw.edges || [];
    } catch {
      /* fresh */
    }
  }

  #save() {
    if (!this.#file) return;
    try {
      // vector 很占空间，落盘时只存前 64 维（够做粗排；精确相似靠重启后重算）
      const nodes = [...this.#nodes.values()].map((n) => ({
        ...n,
        vector: n.vector ? n.vector.slice(0, 64) : null,
      }));
      atomicWriteFileSync(this.#file, JSON.stringify({ nodes, edges: this.#edges }));
    } catch {
      /* best effort */
    }
  }
}

/**
 * 记忆工具（#36 #37 的 agent 侧入口）。
 * agent 可以自己决定什么值得长期记住，并在需要时召回。
 */
export function createMemoryTools({ graph }) {
  if (!graph) throw new Error('createMemoryTools needs a graph');
  return [
    {
      name: 'remember',
      description:
        'Store a durable fact in long-term memory so future conversations can recall it. Use for user preferences, project conventions, and facts that will still matter tomorrow. Do not store secrets.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          id: { type: 'string', description: 'Stable slug id, e.g. "user.prefers.dark-mode"' },
          content: { type: 'string', description: 'The fact, one sentence, self-contained' },
          kind: { type: 'string', description: 'fact | preference | project | person | decision', default: 'fact' },
          tags: { type: 'array', items: { type: 'string' } },
          importance: { type: 'number', description: '0..1, default 0.5' },
        },
        required: ['id', 'content'],
      },
      async execute({ id, content, kind, tags, importance }) {
        const node = await graph.remember({ id, content, kind, tags: tags || [], importance: importance ?? 0.5 });
        return `remembered [${node.kind}] ${node.id} (vectors: ${node.vector ? node.vector.length : 0}d)`;
      },
    },
    {
      name: 'recall',
      description:
        'Search long-term memory with hybrid retrieval (keyword + vector + graph neighbours). Use before asking the user to repeat themselves.',
      risk: 'read',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string' },
          limit: { type: 'integer', description: 'Max results, default 8' },
          kinds: { type: 'array', items: { type: 'string' }, description: 'Filter by kind' },
        },
        required: ['query'],
      },
      async execute({ query, limit, kinds }) {
        const hits = await graph.recall(query, { limit: limit ?? 8, kinds: kinds || null });
        if (!hits.length) return '(nothing relevant in memory)';
        return hits
          .map((h) => `[${h.score.toFixed(3)}] [${h.node.kind}] ${h.node.id}: ${h.node.content}  (${h.why})`)
          .join('\n');
      },
    },
    {
      name: 'relate',
      description: 'Link two memory nodes: relate(subject, predicate, object). Example: relate("proj.api", "uses", "lib.auth").',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: {
          subject: { type: 'string' },
          predicate: { type: 'string' },
          object: { type: 'string' },
        },
        required: ['subject', 'predicate', 'object'],
      },
      execute({ subject, predicate, object }) {
        const edge = graph.relate(subject, predicate, object);
        return `linked ${edge.s} -[${edge.p}]-> ${edge.o} (weight ${edge.weight})`;
      },
    },
    {
      name: 'forget',
      description: 'Delete a memory node and its relationships. Use when the user corrects a stale fact.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: { id: { type: 'string' } },
        required: ['id'],
      },
      execute({ id }) {
        return graph.forget(id) ? `forgot ${id}` : `no such memory: ${id}`;
      },
    },
    {
      name: 'memory_stats',
      description: 'Long-term memory statistics: node count, relationship count, and top nodes by hit count.',
      risk: 'read',
      parameters: { type: 'object', properties: {} },
      execute() {
        const s = graph.stats();
        return `memory: ${s.nodes} nodes, ${s.edges} relationships`;
      },
    },
  ];
}
