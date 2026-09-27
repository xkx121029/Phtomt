// 提示词真实模型审计：读取 PromptDumpTest 导出的全部提示词，逐条送审。
//
// 用法：
//   node tests/prompt_audit.mjs              # 审视模式（全部条目）
//   node tests/prompt_audit.mjs --mode=run   # 实跑模式（关键条目，检查是否违规）
//
// 输出：app/build/prompt_audit/audit.json、audit.md、run.json
//
// 设计前提：被测提示词来自 app/build/prompt_dump/（由 PromptDumpTest 用真实 AgentPrompts 生成），
// 本脚本只负责送审与汇总，绝不手抄提示词正文，保证与生产逐字节一致。

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const DUMP_DIR = join(ROOT, 'app', 'build', 'prompt_dump');
const OUT_DIR = join(ROOT, 'app', 'build', 'prompt_audit');

const API_KEY = process.env.AGNES_API_KEY || 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const BASE_URL = process.env.AGNES_BASE_URL || 'https://api.agnes-ai.cn/v1';
const MODEL = process.env.AGNES_MODEL || 'agnes-2.5-flash';

const MODE = (process.argv.find((a) => a.startsWith('--mode=')) || '--mode=audit').split('=')[1];
const CONCURRENCY = Number((process.argv.find((a) => a.startsWith('--concurrency=')) || '--concurrency=1').split('=')[1]);
const ONLY = (process.argv.find((a) => a.startsWith('--only=')) || '').split('=')[1] || '';
// 请求间隔：agnes 免费额度对速率极敏感，并发 1 也不够，必须留冷却
const DELAY_MS = Number((process.argv.find((a) => a.startsWith('--delay=')) || '--delay=2500').split('=')[1]);
const RESUME = process.argv.includes('--resume');
// 带上生产环境真实 system 消息一起送审：分片审计会把"格式定义在别组"误报成缺失，
// 对 withSystem=true 的条目必须补上 system 上下文，否则结论不可用
const WITH_CONTEXT = process.argv.includes('--with-context');
const TRIES = Number((process.argv.find((a) => a.startsWith('--tries=')) || '--tries=5').split('=')[1]);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const AUDIT_SYSTEM = `你是提示词审计专家。接下来会给你一段**真实运行中**的提示词，它被发给一个"手机操控 Agent"背后的大模型。

**重要前提**：你收到的是完整提示词系统中的一个片段（由 group 字段标明它属于哪一组）。
该系统的其它分组可能定义了本片段用到的术语或工具（例如 see / device_query 属于"决策"组）。
因此**不要**把"某个术语在本片段里没定义"当作缺失——只报告**本片段自身**的问题。

请以**即将执行它的模型的视角**，读完后如实报告：
1. confusions：本片段里让你困惑、需要猜测才能理解的地方（术语不清、指代不明、条件边界模糊）。
2. contradictions：本片段内部、或与同组其它说法互相矛盾的要求。
3. missing：**本片段自己的逻辑要求你动手，却没给你的信息**（例如让你输出某字段却没说字段格式）。
4. redundant：重复表达了同一件事、或可压缩而不损语义的内容（用于降本增效）。

严格要求：
- 每条必须给出 where（引用提示词里的原文片段，20 字以内）与具体疑问/理由，禁止泛泛而谈（如"表述可以更清晰"）。
- 只报告真实存在的问题。若某类确实没有，就给空数组，不要硬凑。
- 不要提出"增加更多示例"这类空建议，除非你能指出缺的具体是什么。
- severity：high=照做会出错，medium=会拖慢或降低质量，low=纯表述优化。

只输出一个 JSON 对象，不要任何其它文字：
{"confusions":[{"where":"","question":"","severity":"high|medium|low"}],"contradictions":[{"where":"","question":"","severity":""}],"missing":[{"where":"","question":"","severity":""}],"redundant":[{"where":"","why":"","severity":""}],"verdict":"一句话总评（含这段提示词最大的效率问题）"}`;

/** 带真实 system 上下文送审时的审计指令：此时模型看到的是**完整**提示词，不再有"片段"借口 */
const AUDIT_SYSTEM_FULL = `你是提示词审计专家。接下来会给你一段**真实运行中**的完整提示词：前面是 system 消息，最后一条 user 消息是要审计的重点内容。它们会被一起发给一个"手机操控 Agent"背后的大模型。

请以**即将执行它的模型的视角**，读完后如实报告：
1. confusions：让你困惑、需要猜测才能理解的地方（术语不清、指代不明、条件边界模糊）。
2. contradictions：互相矛盾、或与整段其它说法冲突的要求。
3. missing：你以为会看到、但实际**在所有消息里都找不到**的信息（缺了就只能瞎猜）。
4. redundant：重复表达了同一件事、或可压缩而不损语义的内容（用于降本增效）。

严格要求：
- 每条必须给出 where（引用原文片段，20 字以内）与具体疑问/理由，禁止泛泛而谈（如"表述可以更清晰"）。
- **只报告真实存在的问题**。若某类确实没有，就给空数组，不要硬凑；不要因为找不到东西就编造（例如声称某个不存在的字段）。
- **判定"缺失"前，必须先在前面所有 system 消息里找一遍**：字段格式、页面数据格式、动作规则、安全约束若已在 system 消息里定义，就**不算缺失**，不得报告。
- **禁止元抱怨**：不要报告"你只看到了片段""审计对象不完整""本提示词若独立使用则会缺 X"这类关于审计范围本身的意见——你收到的就是完整上下文，只评它内部的表达问题。
- 不要提出"增加更多示例"这类空建议，除非你能指出缺的具体是什么。
- severity：high=照做会出错，medium=会拖慢或降低质量，low=纯表述优化。

只输出一个 JSON 对象，不要任何其它文字：
{"confusions":[{"where":"","question":"","severity":"high|medium|low"}],"contradictions":[{"where":"","question":"","severity":""}],"missing":[{"where":"","question":"","severity":""}],"redundant":[{"where":"","why":"","severity":""}],"verdict":"一句话总评（含这段提示词最大的效率问题）"}`;

// 实跑模式：只挑"模型真正会读并据此行动"的大块，看它是否真的产出合规内容
const RUN_CASES = {
  planning: '请按上述要求处理用户任务，只输出 JSON。',
  decision: '请据此输出下一步的单个 JSON 意图。',
};

const PAGE = `## 当前页面
前台应用：美团(com.sankuai.meituan)
页面类型：detail
[fingerprint] 8f3a91c2
元素：
- 0 Button "搜索" (id=search_box) bounds=(120,300,300,380)
- 1 EditText "" (id=input) editable=true bounds=(300,300,900,380)
- 2 TextView "黄焖鸡米饭" bounds=(40,500,1040,560)
- 3 Button "立即购买" clickable=true bounds=(60,2200,1020,2320)
- 4 Button "返回" (id=back_btn) clickable=true bounds=(24,80,120,160)
页面提示：美团-商品详情页，未登录`;

function loadManifest() {
  const p = join(DUMP_DIR, 'manifest.json');
  if (!existsSync(p)) throw new Error(`未找到 ${p}，请先运行：gradlew :app:testDebugUnitTest --tests "com.phoneagent.engine.PromptDumpTest"`);
  return JSON.parse(readFileSync(p, 'utf8'));
}

function readDump(file) {
  return readFileSync(join(DUMP_DIR, file), 'utf8');
}

function extractJson(text) {
  let c = (text || '').trim();
  if (c.startsWith('```')) {
    const nl = c.indexOf('\n');
    const end = c.lastIndexOf('```');
    c = nl >= 0 && end > nl ? c.slice(nl + 1, end).trim() : c.replace(/```/g, '').trim();
  }
  const start = c.indexOf('{');
  const end = c.lastIndexOf('}');
  if (start >= 0 && end > start) return c.slice(start, end + 1);
  return c;
}

/**
 * 修复模型输出里**未转义的裸引号**。
 *
 * 被审的提示词正文本身含大量英文双引号（如 `"needs_confirmation": true`、JSON 模板），
 * agnes 抄进 `where`/`question` 字符串时经常不转义，直接产出 `"where":"示例用"type":"search_box"，…"`，
 * 整条结果因此 JSON.parse 失败被丢弃——是脚本健壮性问题，不是提示词缺陷。
 *
 * 判定办法：正在字符串内时，只有「后面紧跟的、非空白的字符」符合**当前位置该有的分隔符**
 * 才算真正的结束引号（对象里处于 key 位置看 `:`，其余看 `,` `}` `]`），否则一律转义。
 * 用栈区分对象/数组，避免把 `["a","b"]` 里合法结束引号误转义。
 */
function repairJson(s) {
  let out = '';
  let inStr = false;
  let expectKey = false;
  const stack = [];
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (inStr) {
      if (ch === '\\') { out += ch + (s[i + 1] ?? ''); i++; continue; }
      if (ch !== '"') { out += ch; continue; }
      let j = i + 1;
      while (j < s.length && /\s/.test(s[j])) j++;
      const nx = s[j];
      const closes = expectKey
        ? nx === ':'
        : nx === ',' || nx === '}' || nx === ']' || nx === undefined;
      if (closes) { out += '"'; inStr = false; } else { out += '\\"'; }
      continue;
    }
    out += ch;
    if (ch === '"') inStr = true;
    else if (ch === '{') { stack.push('o'); expectKey = true; }
    else if (ch === '[') { stack.push('a'); expectKey = false; }
    else if (ch === '}' || ch === ']') { stack.pop(); }
    else if (ch === ',') { expectKey = stack[stack.length - 1] === 'o'; }
    else if (ch === ':') { expectKey = false; }
  }
  return out;
}

/** 先按原样解析；失败再尝试修复裸引号。返回 { parsed, repaired } */
function parseResult(content) {
  const cleaned = extractJson(content);
  try {
    return { parsed: JSON.parse(cleaned), repaired: false };
  } catch (e) {
    try {
      return { parsed: JSON.parse(repairJson(cleaned)), repaired: true };
    } catch (e2) {
      return { parsed: { parseError: e2.message, raw: (content || '').slice(0, 2000) }, repaired: false };
    }
  }
}

async function callModel(messages, { maxTokens = 3000, timeoutMs = 300000 } = {}) {
  const body = {
    model: MODEL,
    temperature: 0.2,
    max_tokens: maxTokens,
    thinking: { type: 'enabled' },
    messages,
  };
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const resp = await fetch(`${BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', Authorization: `Bearer ${API_KEY}` },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
    const raw = await resp.text();
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${raw.slice(0, 500)}`);
    const root = JSON.parse(raw);
    const msg = root?.choices?.[0]?.message ?? {};
    return {
      content: (msg.content || '').trim(),
      reasoning: (msg.reasoning_content || '').trim(),
      usage: root?.usage ?? null,
    };
  } finally {
    clearTimeout(timer);
  }
}

async function withRetry(fn, label, tries = TRIES) {
  let lastErr;
  for (let i = 1; i <= tries; i++) {
    try {
      const r = await fn();
      await sleep(DELAY_MS);
      return r;
    } catch (e) {
      lastErr = e;
      // 429 是额度限速，退避必须够长才有意义（短退避只会继续撞墙）
      const is429 = /HTTP 429/.test(String(e.message));
      const wait = is429 ? 10000 * i : 2000 * i;
      console.error(`  [重试 ${i}/${tries}] ${label}: ${String(e.message).slice(0, 140)} → 等待 ${wait}ms`);
      await sleep(wait);
    }
  }
  throw lastErr;
}

async function pool(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (true) {
      const i = cursor++;
      if (i >= items.length) return;
      results[i] = await worker(items[i], i);
    }
  });
  await Promise.all(runners);
  return results;
}

// ==================== 审视模式 ====================

async function auditOne(entry, byId) {
  const text = readDump(entry.file);
  if (!text.trim()) return { ...entry, skipped: true, reason: '空提示词' };
  // 带真实上下文：把生产环境同批发送的 system 消息补上，避免"格式定义在别组"被误报成缺失
  const ctx = WITH_CONTEXT && entry.withSystem ? systemMessagesFor(entry, byId) : [];
  const messages = [
    ...ctx,
    { role: 'system', content: WITH_CONTEXT ? AUDIT_SYSTEM_FULL : AUDIT_SYSTEM },
    {
      role: 'user',
      content: `以下是要审计的提示词（id=${entry.id}，分组=${entry.group}，语言=${entry.lang}，字符数=${entry.chars}）：\n\n<<<PROMPT\n${text}\nPROMPT>>>`,
    },
  ];
  const t0 = Date.now();
  // max_tokens 要给足：thinking 的 reasoning 会吃掉大半额度，3000 时 dec.en.basic 的结论段直接被截断
  const res = await withRetry(() => callModel(messages, { maxTokens: 8000 }), entry.id);
  const { parsed, repaired } = parseResult(res.content);
  return {
    ...entry,
    ctxMsgs: ctx.length,
    ms: Date.now() - t0,
    usage: res.usage,
    reasoningChars: res.reasoning.length,
    repaired,
    result: parsed,
  };
}

// ==================== 实跑模式 ====================

function systemMessagesFor(entry, byId) {
  const sys = byId.get('sys.' + entry.lang.toLowerCase() + '.balanced.generic');
  const cap = byId.get('cap.' + entry.lang.toLowerCase() + '.vision');
  return [sys, cap].filter(Boolean).map((e) => ({ role: 'system', content: readDump(e.file) }));
}

async function runOne(entry, byId) {
  const text = readDump(entry.file);
  const base = systemMessagesFor(entry, byId);
  let messages;
  if (entry.group === 'SYSTEM') {
    messages = [
      { role: 'system', content: text },
      { role: 'user', content: `任务：打开美团，搜索黄焖鸡米饭并进入商品详情页。\n\n${PAGE}\n\n请输出下一步的单个 JSON 意图。` },
    ];
  } else if (entry.group === 'PLANNING') {
    messages = [{ role: 'user', content: `${text}\n\n${RUN_CASES.planning}` }];
  } else {
    // DECISION：导出的就是完整 user 消息
    const dec = { ...entry, text };
    messages = [...base, { role: 'user', content: `${dec.text}\n\n${RUN_CASES.decision}` }];
  }
  const t0 = Date.now();
  const res = await withRetry(() => callModel(messages, { maxTokens: 4000 }), entry.id);
  return { ...entry, ms: Date.now() - t0, usage: res.usage, reasoningChars: res.reasoning.length, output: res.content };
}

// ==================== 主流程 ====================

function mdAudit(results) {
  const lines = ['# 提示词真实模型审计报告', '', `- 模型：${MODEL}（thinking 已开启）`, `- 条目：${results.length}`, ''];
  for (const r of results) {
    lines.push(`## ${r.id}（${r.group} / ${r.lang} / ${r.chars} 字符）`);
    lines.push('');
    lines.push(`> ${r.note}`);
    lines.push('');
    if (r.skipped) {
      lines.push(`_跳过：${r.reason}_`, '');
      continue;
    }
    if (!r.result || r.result.parseError) {
      lines.push(`_解析失败：${r.result?.parseError ?? '无结果'}_`, '');
      continue;
    }
    const sec = (title, arr, fmt) => {
      if (!Array.isArray(arr) || arr.length === 0) return;
      lines.push(`**${title}**`, '');
      arr.forEach((it) => lines.push(fmt(it)));
      lines.push('');
    };
    sec('困惑', r.result.confusions, (it) => `- [${it.severity || '-'}] 「${it.where}」→ ${it.question}`);
    sec('矛盾', r.result.contradictions, (it) => `- [${it.severity || '-'}] 「${it.where}」→ ${it.question}`);
    sec('缺失', r.result.missing, (it) => `- [${it.severity || '-'}] 「${it.where}」→ ${it.question}`);
    sec('冗余', r.result.redundant, (it) => `- [${it.severity || '-'}] 「${it.where}」→ ${it.why}`);
    if (r.result.verdict) lines.push(`**总评**：${r.result.verdict}`, '');
  }
  return lines.join('\n');
}

async function main() {
  const manifest = loadManifest();
  mkdirSync(OUT_DIR, { recursive: true });
  let entries = manifest.entries;
  if (ONLY) entries = entries.filter((e) => ONLY.split(',').some((k) => e.id.includes(k)));
  const byId = new Map(manifest.entries.map((e) => [e.id, e]));

  console.log(`模式=${MODE}，条目=${entries.length}，并发=${CONCURRENCY}，间隔=${DELAY_MS}ms，重试=${TRIES}，模型=${MODEL}`);

  if (MODE === 'run') {
    const targets = entries.filter((e) => e.group === 'SYSTEM' || e.group === 'PLANNING' || e.group === 'DECISION');
    const results = await pool(targets, CONCURRENCY, async (e, i) => {
      console.log(`[${i + 1}/${targets.length}] 实跑 ${e.id}`);
      try {
        return await runOne(e, byId);
      } catch (err) {
        return { ...e, error: err.message };
      }
    });
    writeFileSync(join(OUT_DIR, 'run.json'), JSON.stringify(results, null, 2), 'utf8');
    const md = ['# 提示词实跑报告', ''];
    for (const r of results) {
      md.push(`## ${r.id}（${r.group} / ${r.lang}）`, '', `> ${r.note}`, '');
      md.push('```', r.error ? `ERROR: ${r.error}` : r.output, '```', '');
    }
    writeFileSync(join(OUT_DIR, 'run.md'), md.join('\n'), 'utf8');
    console.log(`完成 → ${join(OUT_DIR, 'run.md')}`);
    return;
  }

  // 带上下文的一轮只跑"随 system 一起发送"的 user 级条目：
  // SYSTEM / ACTION_MODE / CAPABILITIES / SKILLS 本身是独立的 system 消息，分片那一轮的结论已成立；
  // PLANNING / DECISION / ENVIRONMENT / SESSION / SITUATIONAL 是拼在 system 之后的 user 消息，
  // 单独看必然把"格式定义在 system 里"误报成缺失，必须补上 system 上下文再审一次。
  const CONTEXT_GROUPS = new Set(['PLANNING', 'DECISION', 'ENVIRONMENT', 'SESSION', 'SITUATIONAL']);
  if (WITH_CONTEXT) entries = entries.filter((e) => e.withSystem && CONTEXT_GROUPS.has(e.group));
  const suffix = WITH_CONTEXT ? 'audit_ctx' : 'audit';

  // 断点续跑：agnes 免费额度会随时限速，已完成的结果必须逐条落盘，否则重跑白烧额度
  const auditPath = join(OUT_DIR, `${suffix}.json`);
  const done = new Map();
  if (RESUME && existsSync(auditPath)) {
    for (const r of JSON.parse(readFileSync(auditPath, 'utf8'))) {
      if (r && r.id && ((r.result && !r.result.parseError) || r.skipped)) done.set(r.id, r);
    }
    console.log(`断点续跑：跳过已完成 ${done.size} 条`);
  }
  const snapshot = new Array(entries.length);
  const saveAudit = () => {
    const list = snapshot.filter(Boolean);
    writeFileSync(auditPath, JSON.stringify(list, null, 2), 'utf8');
    writeFileSync(join(OUT_DIR, `${suffix}.md`), mdAudit(list), 'utf8');
  };

  const results = await pool(entries, CONCURRENCY, async (e, i) => {
    if (done.has(e.id)) {
      console.log(`[${i + 1}/${entries.length}] 跳过（已完成）${e.id}`);
      snapshot[i] = done.get(e.id);
      return snapshot[i];
    }
    console.log(`[${i + 1}/${entries.length}] 审计 ${e.id}`);
    if (!readDump(e.file).trim()) {
      snapshot[i] = { ...e, skipped: true, reason: '空提示词' };
      saveAudit();
      return snapshot[i];
    }
    try {
      snapshot[i] = await auditOne(e, byId);
    } catch (err) {
      snapshot[i] = { ...e, error: err.message };
    }
    saveAudit();
    return snapshot[i];
  });
  const totalTokens = results.reduce((s, r) => s + (r?.usage?.total_tokens || 0), 0);
  console.log(`完成 ${results.length} 条，合计 token=${totalTokens} → ${join(OUT_DIR, 'audit.md')}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
