// 任务清单工具。
//
// 为什么值得单独做：多步任务里模型最容易"做着做着忘了第三步"。把计划外化成一份
// 可见的清单（用户也能看到），比在 system prompt 里反复叮嘱有效得多。
// 清单按会话隔离，可选落盘，进程重启后还在。

import fs from 'node:fs';
import path from 'node:path';

const STATUSES = new Set(['pending', 'in_progress', 'completed']);
const MAX_ITEMS = 50;

function render(todos) {
  if (!todos.length) return '(todo list is empty)';
  const mark = { completed: '[x]', in_progress: '[~]', pending: '[ ]' };
  const done = todos.filter((t) => t.status === 'completed').length;
  return `${done}/${todos.length} done\n` + todos.map((t, i) => `${i + 1}. ${mark[t.status]} ${t.content}`).join('\n');
}

export function createTodoTools({ persistPath = null } = {}) {
  /** sessionId -> [{content, status}] */
  const store = new Map();

  if (persistPath) {
    try {
      const raw = JSON.parse(fs.readFileSync(persistPath, 'utf8'));
      for (const [sid, todos] of Object.entries(raw)) {
        if (Array.isArray(todos)) store.set(sid, todos);
      }
    } catch {
      /* 没有或损坏：从空清单开始 */
    }
  }

  const save = () => {
    if (!persistPath) return;
    try {
      fs.mkdirSync(path.dirname(persistPath), { recursive: true });
      fs.writeFileSync(persistPath, JSON.stringify(Object.fromEntries(store)), 'utf8');
    } catch {
      /* 持久化是尽力而为，不能因为它让 agent 挂掉 */
    }
  };

  const key = (ctx) => ctx?.session?.id || '_global';

  const todo_write = {
    name: 'todo_write',
    description:
      'Replace the task list for this session. Use it for multi-step work: mark exactly one item in_progress while you work on it, and completed as soon as it is actually done. The user sees this list.',
    risk: 'write',
    parameters: {
      type: 'object',
      properties: {
        todos: {
          type: 'array',
          description: `Full list (max ${MAX_ITEMS} items). Pass the whole list every time, not a delta.`,
          items: {
            type: 'object',
            properties: {
              content: { type: 'string', description: 'Short imperative description' },
              status: { type: 'string', enum: ['pending', 'in_progress', 'completed'] },
            },
            required: ['content', 'status'],
          },
        },
      },
      required: ['todos'],
    },
    execute({ todos }, ctx) {
      if (!Array.isArray(todos)) throw new Error('todos must be an array');
      if (todos.length > MAX_ITEMS) throw new Error(`at most ${MAX_ITEMS} todos`);
      const cleaned = todos.map((t, i) => {
        const content = String(t?.content ?? '').trim();
        if (!content) throw new Error(`todo ${i + 1} needs content`);
        const status = String(t?.status ?? 'pending');
        if (!STATUSES.has(status)) throw new Error(`todo ${i + 1}: status must be pending|in_progress|completed`);
        return { content, status };
      });
      const running = cleaned.filter((t) => t.status === 'in_progress').length;
      store.set(key(ctx), cleaned);
      save();
      const note = running > 1 ? `\n(note: ${running} items are in_progress; keep only one active at a time)` : '';
      return render(cleaned) + note;
    },
  };

  const todo_read = {
    name: 'todo_read',
    description: 'Read the current task list for this session.',
    risk: 'read',
    parameters: { type: 'object', properties: {} },
    execute(_args, ctx) {
      return render(store.get(key(ctx)) || []);
    },
  };

  return [todo_write, todo_read];
}
