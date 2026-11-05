// 上下文窗口动态管理（能力 #39）：滑动窗口 + 总结 hook。
//
// 问题：手机上一次会话跑几十轮工具调用，messages 轻松超过模型上下文，
//       直接全量发送会爆炸或被截断得莫名其妙。
// 方案（对齐 Operit 的 ChatMemoryWindowPlanner 思路，自己实现）：
//   1. 永远保留 system + 最近 N 条消息（保留尾部，agent 才知道自己刚做了什么）
//   2. 中间的老消息按批总结：整批替换成一条 {role:'user', content:'[summary] ...'}
//   3. 总结由 TaskKind 'summarize' 绑定的（便宜）模型生成；失败就退化为硬截断
// 4. 总结结果写回 session.summaries，重启后仍生效

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync } from './util/atomic-write.js';

export class ContextWindowPlanner {
  constructor({
    maxMessages = 40,
    keepRecent = 16,
    summarizeBatch = 12,
    maxCharsPerMessage = 20000,
  } = {}) {
    this.maxMessages = maxMessages;
    this.keepRecent = keepRecent;
    this.summarizeBatch = summarizeBatch;
    this.maxCharsPerMessage = maxCharsPerMessage;
  }

  /**
   * 决定一次请求实际携带哪些消息。
   * @returns {{messages: Array, summarized: number}}
   */
  plan(session) {
    const all = session.messages || [];
    const system = all.filter((m) => m.role === 'system');
    const rest = all.filter((m) => m.role !== 'system');

    // 超长单条消息先截断（工具输出最容易炸）
    const clipped = rest.map((m) => {
      const len = typeof m.content === 'string' ? m.content.length : 0;
      if (len > this.maxCharsPerMessage) {
        return { ...m, content: `${m.content.slice(0, this.maxCharsPerMessage)}\n... [clipped ${len - this.maxCharsPerMessage} chars]` };
      }
      return m;
    });

    const alreadySummarized = session.summaries || [];
    if (clipped.length <= this.maxMessages) {
      return { messages: [...system, ...alreadySummarized, ...clipped], summarized: alreadySummarized.length };
    }

    // 已有总结块 + 尾部保留，中间部分继续压缩进总结
    let headCount = clipped.length - this.keepRecent;
    // 切点绝不允许落在「assistant(toolCalls)」和它的 tool 结果之间：
    // 请求里出现孤儿 tool 消息（或只有 tool_calls 没有结果）会被
    // OpenAI/Anthropic 直接 400 拒掉。所以切点一律往前移到消息边界。
    while (headCount > 0 && clipped[headCount] && clipped[headCount].role === 'tool') {
      headCount -= 1;
    }
    const head = clipped.slice(0, headCount);
    const tail = clipped.slice(headCount);
    return {
      messages: [...system, ...alreadySummarized, ...tail],
      summarized: alreadySummarized.length,
      pending: head, // 交给 summarize() 压缩后并入 summaries
    };
  }

  /**
   * 把 pending 块总结成一条消息，追加到 session.summaries。
   * summarizer: async (messages) => string  通常用便宜模型。
   * 总结完的头部会从 session.messages 里移除——否则下次 plan() 还会把
   * 同一批消息当 pending，每轮重复总结，summaries 无限膨胀。
   */
  async summarize(session, summarizer) {
    const planned = this.plan(session);
    const head = planned.pending;
    if (!head?.length) return { summarized: false };

    const transcript = head
      .map((m) => {
        if (m.role === 'assistant' && m.toolCalls?.length) {
          const calls = m.toolCalls.map((t) => `${t.name}(${JSON.stringify(t.arguments).slice(0, 200)})`).join(', ');
          return `assistant: ${m.content || '(no text)'} [tools: ${calls}]`;
        }
        const text = typeof m.content === 'string' ? m.content : JSON.stringify(m.content);
        return `${m.role}: ${text.slice(0, 1500)}`;
      })
      .join('\n');

    let summary;
    try {
      summary = await summarizer(transcript);
    } catch {
      // 总结失败不能毁掉会话：降级为机械摘要（保留可读性，不保留全部细节）
      summary = `[auto-summary of ${head.length} earlier messages]\n${transcript.slice(0, 4000)}`;
    }
    // 总结器返回空（比如 mock provider 没有剧本）同样降级，别把空壳写进 summaries。
    if (!summary || !String(summary).trim()) {
      summary = `[auto-summary of ${head.length} earlier messages]\n${transcript.slice(0, 4000)}`;
    }
    // 按引用移除被压缩的消息（plan() 对未截断的消息返回原对象引用）。
    const compressed = new Set(head);
    session.messages = (session.messages || []).filter((m) => !compressed.has(m));
    session.summaries = session.summaries || [];
    session.summaries.push({
      role: 'user',
      content: `[earlier conversation summary]\n${summary}\n[/summary]`,
      _cachedAt: new Date().toISOString(),
    });
    // 缓存价值随时间衰减：太多总结块时合并最早的两个
    if (session.summaries.length > 6) {
      session.summaries = [mergeSummaries(session.summaries), ...session.summaries.slice(1)];
    }
    return { summarized: true, from: head.length, to: session.summaries.length };
  }
}

function mergeSummaries(list) {
  return {
    role: 'user',
    content: `[merged earlier summaries]\n${list.map((s) => s.content).join('\n---\n')}\n[/merged]`,
  };
}

/**
 * 对话历史分组 + 分支 + 迁移（能力 #38）。
 * 一个 session 由多个 branch 组成，每个 branch 是一条线性消息链；
 * check point 是从某个消息处分叉的落点。默认只有 'main'。
 */
export class ConversationStore {
  #sessions = new Map();
  #file;

  constructor({ persistPath } = {}) {
    this.#file = persistPath || null;
    if (this.#file) this.#load();
  }

  /** 取（或建）一个会话的分支视图。 */
  branches(sessionId) {
    const s = this.#sessions.get(sessionId);
    return s ? Object.keys(s) : [];
  }

  messagesOf(sessionId, branch = 'main') {
    const s = this.#sessions.get(sessionId);
    if (!s) return [];
    // main 直接挂会话自身；其他分支是 { parent, messages }
    if (branch === 'main') return s.main;
    const b = s[branch];
    return b ? b.messages : [];
  }

  /** 从 main 或某分支的 index 处分叉出新分支，拷贝该点之后的消息。 */
  fork(sessionId, { fromBranch = 'main', atIndex, name }) {
    const s = this.#sessions.get(sessionId);
    if (!s) throw new Error(`unknown session: ${sessionId}`);
    const source = fromBranch === 'main' ? s.main : s[fromBranch]?.messages;
    if (!Array.isArray(source)) throw new Error(`unknown branch: ${fromBranch}`);
    const cut = typeof atIndex === 'number' ? Math.max(0, Math.min(source.length, atIndex)) : source.length;
    const branchName = name || `b_${Math.random().toString(36).slice(2, 8)}`;
    s[branchName] = {
      parent: fromBranch,
      forkedAt: new Date().toISOString(),
      messages: source.slice(0, cut).map(clone),
    };
    this.#save();
    return { sessionId, branch: branchName, messages: s[branchName].messages.length };
  }

  push(sessionId, branch, message) {
    const s = this.#sessions.get(sessionId) || this.create(sessionId);
    const list = branch === 'main' ? s.main : (s[branch] = s[branch] || { parent: 'main', messages: [] });
    list.messages.push(clone(message));
    this.#save();
    return list.messages.length;
  }

  create(sessionId) {
    const id = sessionId || `c_${Math.random().toString(36).slice(2, 10)}`;
    const s = { id, main: [], createdAt: new Date().toISOString() };
    this.#sessions.set(id, s);
    return s;
  }

  /** 删除整个会话。 */
  delete(sessionId) {
    return this.#sessions.delete(sessionId);
  }

  list() {
    return [...this.#sessions.values()].map((s) => ({
      id: s.id,
      createdAt: s.createdAt,
      branches: Object.keys(s).filter((k) => k !== 'id' && k !== 'main' && k !== 'createdAt').length,
      messages: s.main.length,
    }));
  }

  #load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this.#file, 'utf8'));
      for (const s of raw.sessions || []) this.#sessions.set(s.id, s);
    } catch {
      /* fresh */
    }
  }

  #save() {
    if (!this.#file) return;
    try {
      atomicWriteFileSync(this.#file, JSON.stringify({ sessions: [...this.#sessions.values()] }, null, 1));
    } catch {
      /* best effort */
    }
  }
}

function clone(o) {
  return JSON.parse(JSON.stringify(o));
}
