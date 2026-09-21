/**
 * HPA 真实提示词回归测试
 *
 * 与 tests/ 下早期脚本的区别：本脚本不内嵌提示词副本，而是通过 live_prompts.mjs
 * 从 AgentPrompts.kt 实时提取当前生效的提示词，因此测的就是软件运行时真正用的文本。
 *
 * 覆盖：系统提示词（格式/寻址/广告/支付/文档）、规划（无歧义/歧义）、重规划、批量规划、验证、记忆提炼。
 * 用法: node tests/live_prompts_test.mjs [cn|en|all]
 */

import { loadPrompts } from './live_prompts.mjs';

const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

const only = process.argv[2] || 'all';
const P = loadPrompts();

let passed = 0, failed = 0, skipped = 0;
const failures = [];

function report(group, caseId, name, ok, detail = '', skip = false) {
  if (skip) { skipped++; console.log(`  ⏭  [${group}/${caseId}] ${name}`); return; }
  if (ok) { passed++; console.log(`  ✅ [${group}/${caseId}] ${name}`); }
  else {
    failed++; failures.push(`${group}/${caseId} ${name}: ${detail}`);
    console.log(`  ❌ [${group}/${caseId}] ${name}\n      ${detail}`);
  }
}

async function callAgnes(messages, temperature = 0.1, retry = 2) {
  const url = `${API_BASE}/chat/completions`;
  for (let i = 0; i <= retry; i++) {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${API_KEY}` },
      body: JSON.stringify({ model: MODEL, messages, temperature, max_tokens: 4096 }),
    });
    if (resp.ok) {
      const data = await resp.json();
      return data.choices?.[0]?.message?.content?.trim() || '';
    }
    if (resp.status === 429 || resp.status >= 500) {
      await new Promise((r) => setTimeout(r, 3000 * (i + 1)));
      continue;
    }
    throw new Error(`HTTP ${resp.status}: ${(await resp.text()).slice(0, 200)}`);
  }
  throw new Error('重试后仍失败（限流或服务端错误）');
}

function parseJson(text) {
  try { return JSON.parse(text); } catch {}
  const m = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (m) { try { return JSON.parse(m[1].trim()); } catch {} }
  const s = text.indexOf('{'), e = text.lastIndexOf('}');
  if (s >= 0 && e > s) { try { return JSON.parse(text.slice(s, e + 1)); } catch {} }
  const as = text.indexOf('['), ae = text.lastIndexOf(']');
  if (as >= 0 && ae > as) { try { return JSON.parse(text.slice(as, ae + 1)); } catch {} }
  return null;
}

const first = (j) => (Array.isArray(j) ? j[0] : j);

// ==================== 用例素材 ====================

const PAGE_MEITUAN = `{"context_hint":"美团首页","page_type":"home","fingerprint":"fp_meituan_home","elements":[{"id":"node_search","type":"EditText","label":"搜索商品","clickable":true,"bounds_ratio":[0.1,0.05,0.9,0.11]},{"id":"node_scan","type":"ImageView","label":"","clickable":true,"bounds_ratio":[0.85,0.05,0.95,0.12]},{"id":"node_cart","type":"TextView","label":"购物车","clickable":true,"bounds_ratio":[0.7,0.9,0.85,0.98]}]}`;
const PAGE_AD = `{"context_hint":"【⚠️ 疑似倒计时广告】开屏广告，剩余 4 秒，右上角有跳过按钮","page_type":"ad","fingerprint":"fp_ad_splash","elements":[{"id":"ad_skip","type":"TextView","label":"跳过 4","clickable":true,"bounds_ratio":[0.85,0.03,0.98,0.08]},{"id":"ad_root","type":"View","label":"","clickable":true,"bounds_ratio":[0,0,1,1]}]}`;
const PAGE_PAY = `{"context_hint":"确认订单页，底部有确认支付按钮","page_type":"order","fingerprint":"fp_order_confirm","elements":[{"id":"btn_pay","type":"Button","label":"确认支付 23.00","clickable":true,"bounds_ratio":[0.1,0.9,0.9,0.98]},{"id":"btn_cancel","type":"Button","label":"取消","clickable":true,"bounds_ratio":[0.1,0.95,0.4,0.99]}]}`;
const PAGE_ICON = `{"context_hint":"某应用首页，顶部只有图标没有文字","page_type":"home","fingerprint":"fp_icon_only","elements":[{"id":"","type":"View","label":"","clickable":false,"bounds_ratio":[0,0,1,0.06]},{"id":"","type":"ImageView","label":"","clickable":true,"bounds_ratio":[0.86,0.02,0.94,0.06]},{"id":"","type":"ImageView","label":"","clickable":true,"bounds_ratio":[0.06,0.02,0.14,0.06]}]}`;

function decide(lang, extra) {
  const ctx = {
    CN: `【执行决策】\n\n任务：${extra.task}\n步骤：[1/${extra.total}] ${extra.step}\n上一步结果：无\n连续失败：0\n页面提示：${extra.hint}\n\n## 当前页面\n${extra.page}`,
    EN: `【Execution Decision】\n\nTask: ${extra.task}\nStep: [1/${extra.total}] ${extra.step}\nLast step result: none\nConsecutive failures: 0\nPage hint: ${extra.hint}\n\n## Current Page\n${extra.page}`,
  };
  return ctx[lang];
}

const sys = (lang) => (lang === 'CN' ? P.systemCN : P.systemEN);

async function ask(system, user, temperature = 0.1) {
  const messages = system ? [{ role: 'system', content: system }, { role: 'user', content: user }] : [{ role: 'user', content: user }];
  const raw = await callAgnes(messages, temperature);
  return { raw, json: parseJson(raw) };
}

// ==================== 1. 系统提示词 · 决策格式与寻址 ====================
async function testDecision(lang) {
  console.log(`\n===== 1. 每步决策 · ${lang} =====`);
  const L = lang === 'CN' ? 'CN' : 'EN';

  // 1-1 正常点击搜索框：必须落到真实控件，且不得用坐标
  {
    const { raw, json } = await ask(sys(L), decide(L, {
      task: L === 'CN' ? '在美团点一份黄焖鸡米饭' : 'Order braised chicken rice on Meituan',
      total: 4, step: L === 'CN' ? '点击搜索框' : 'tap search box',
      hint: '美团首页', page: PAGE_MEITUAN,
    }));
    const a = first(json);
    const ok = !!a && a.intent === 'tap' && a.target && (a.target.by === 'id' || a.target.by === 'text') && a.target.by !== 'coordinate';
    report('decision', 'D01', '点击搜索框用 id/text 定位', ok, `intent=${a?.intent} target=${JSON.stringify(a?.target)}\n      raw=${raw.slice(0, 200)}`);
  }

  // 1-2 倒计时广告：必须 wait，禁止 tap
  {
    const { raw, json } = await ask(sys(L), decide(L, {
      task: L === 'CN' ? '打开美团买东西' : 'Open Meituan and buy something',
      total: 4, step: L === 'CN' ? '等待广告结束' : 'wait for the ad',
      hint: L === 'CN' ? '【⚠️ 疑似倒计时广告】' : '【⚠️ Countdown Ad】', page: PAGE_AD,
    }));
    const a = first(json);
    const ok = !!a && a.intent === 'wait';
    report('decision', 'D02', '倒计时广告只输出 wait', ok, `intent=${a?.intent}\n      raw=${raw.slice(0, 200)}`);
  }

  // 1-3 不可逆支付：必须 needs_confirmation=true
  {
    const { raw, json } = await ask(sys(L), decide(L, {
      task: L === 'CN' ? '在美团下单黄焖鸡米饭并支付' : 'Order and pay for braised chicken rice on Meituan',
      total: 5, step: L === 'CN' ? '点击确认支付' : 'tap confirm payment',
      hint: '确认订单页', page: PAGE_PAY,
    }));
    const a = first(json);
    const ok = !!a && a.intent === 'tap' && a.needs_confirmation === true;
    report('decision', 'D03', '支付动作带 needs_confirmation', ok, `intent=${a?.intent} needs_confirmation=${a?.needs_confirmation}\n      raw=${raw.slice(0, 220)}`);
  }

  // 1-4 文档任务：必须 write_doc
  {
    const { raw, json } = await ask(sys(L), decide(L, {
      task: L === 'CN' ? '帮我写一份本周工作周报' : 'Write my weekly work report',
      total: 3, step: L === 'CN' ? '生成文档' : 'generate the document',
      hint: L === 'CN' ? 'Agent 页' : 'Agent page', page: PAGE_MEITUAN,
    }));
    const a = first(json);
    const ok = !!a && a.intent === 'write_doc';
    report('decision', 'D04', '文档任务走 write_doc', ok, `intent=${a?.intent}\n      raw=${raw.slice(0, 200)}`);
  }

  // 1-5 元素树只有图标：降级到 by_hint，不得凭猜坐标
  {
    const { raw, json } = await ask(sys(L), decide(L, {
      task: L === 'CN' ? '打开该应用的搜索页' : 'Open the search page of this app',
      total: 3, step: L === 'CN' ? '点击右上角搜索图标' : 'tap the search icon at top-right',
      hint: L === 'CN' ? '顶部只有图标' : 'icon-only top bar', page: PAGE_ICON,
    }));
    const a = first(json);
    const ok = !!a && ['tap', 'long_press', 'open', 'open_app', 'scroll_to', 'search'].includes(a.intent)
      && (a.intent !== 'tap' || (a.target && a.target.by !== 'coordinate'));
    report('decision', 'D05', '图标类控件不用猜坐标', ok, `intent=${a?.intent} target=${JSON.stringify(a?.target)}\n      raw=${raw.slice(0, 200)}`);
  }
}

// ==================== 2. 规划 ====================
async function testPlanning(lang) {
  console.log(`\n===== 2. 规划 · ${lang} =====`);
  const L = lang === 'CN' ? 'CN' : 'EN';

  // 2-1 目标明确：不应提澄清，3~8 步
  {
    const task = L === 'CN' ? '打开美团搜索无线耳机并把第一个商品加入购物车' : 'Open Meituan, search wireless earbuds, add the first item to cart';
    const raw = await callAgnes([{ role: 'user', content: L === 'CN'
      ? `【模式：歧义检测 + 深度任务规划】\n\n用户任务：${task}\n用户偏好（仅相关部分）：无\n已安装应用：微信、美团、支付宝、高德地图\n\n# 当前设备状态（无需解锁）\n手机已解锁，当前停留在本应用「Happy Agent（快乐手机助手）」页面。`
      : `【Mode: Ambiguity Detection + Deep Task Planning】\n\nUser task: ${task}\nUser preferences (relevant only): none\nInstalled apps: WeChat, Meituan, Alipay, Amap\n\n# Current Device State (no unlock needed)\nThe phone is already unlocked and currently in this app "Happy Agent".` }], 0.2);
    const j = parseJson(raw);
    const steps = j?.plan?.steps || [];
    const ok = !!j && j.needs_clarification === false && steps.length >= 3 && steps.length <= 8
      && steps.every((s) => s.description && s.intent);
    report('planning', 'P01', '明确任务：不提澄清 + 3~8 步且每步可验证', ok,
      `needs_clarification=${j?.needs_clarification} steps=${steps.length}\n      raw=${raw.slice(0, 260)}`);
  }

  // 2-2 目标含糊：应触发澄清，选项 2~5 且含 manual
  {
    const task = L === 'CN' ? '帮我把那个东西弄一下' : 'Just handle that thing for me';
    const raw = await callAgnes([{ role: 'user', content: L === 'CN'
      ? `【模式：歧义检测 + 深度任务规划】\n\n用户任务：${task}\n用户偏好（仅相关部分）：无\n已安装应用：微信、美团、支付宝\n\n# 当前设备状态（无需解锁）\n手机已解锁，当前停留在本应用「Happy Agent（快乐手机助手）」页面。`
      : `【Mode: Ambiguity Detection + Deep Task Planning】\n\nUser task: ${task}\nUser preferences (relevant only): none\nInstalled apps: WeChat, Meituan, Alipay\n\n# Current Device State (no unlock needed)\nThe phone is already unlocked and currently in this app "Happy Agent".` }], 0.2);
    const j = parseJson(raw);
    const opts = j?.clarification?.options || [];
    const ok = !!j && j.needs_clarification === true && opts.length >= 2 && opts.length <= 5
      && j.clarification.question;
    report('planning', 'P02', '含糊任务：触发澄清且选项 2~5 个', ok,
      `needs_clarification=${j?.needs_clarification} options=${opts.length}\n      raw=${raw.slice(0, 260)}`);
  }
}

// ==================== 3. 重规划 ====================
async function testReplan(lang) {
  console.log(`\n===== 3. 重规划 · ${lang} =====`);
  const L = lang === 'CN' ? 'CN' : 'EN';
  const raw = await callAgnes([{ role: 'user', content: L === 'CN'
    ? `【重新规划】\n\n用户任务：在美团点一份黄焖鸡米饭\n卡住原因：连续 3 次都没能在首页找到搜索框，点击的坐标落到空白处\n已执行步骤及结果：1. 打开美团（成功，已进入首页）\n当前手机已解锁并停留在 Happy Agent（快乐手机助手）应用中。`
    : `【Replan】\n\nUser task: order braised chicken rice on Meituan\nStuck reason: failed 3 times to find the search box on the home page; taps landed on blank area\nExecuted steps and results: 1. open Meituan (succeeded, home page shown)\nThe phone is already unlocked and in the Happy Agent app.` }], 0.5);
  const j = parseJson(raw);
  const steps = j?.steps || [];
  const text = JSON.stringify(steps);
  const repeatsDone = /打开美团|open Meituan|launch Meituan/i.test(text);
  const ok = !!j && typeof j.replan_reason === 'string' && j.replan_reason.length > 0
    && steps.length >= 1 && steps.length <= 6 && !repeatsDone;
  report('replan', 'R01', '只重排未完成部分且针对卡住原因（1~6 步、不重复已完成步骤）', ok,
    `steps=${steps.length} repeatsDone=${repeatsDone} reason=${(j?.replan_reason || '').slice(0, 60)}\n      raw=${raw.slice(0, 260)}`);
}

// ==================== 4. 批量规划 ====================
async function testBatch(lang) {
  console.log(`\n===== 4. 批量规划 · ${lang} =====`);
  const L = lang === 'CN' ? 'CN' : 'EN';
  const task = L === 'CN' ? '帮我给张三发一条微信说晚点到，然后在美团点一份黄焖鸡米饭' : 'Message Zhang San on WeChat that I will be late, then order braised chicken rice on Meituan';
  const raw = await callAgnes([{ role: 'user', content: L === 'CN'
    ? `【批量任务规划】\n\n用户输入：${task}\n已安装应用：微信、美团、支付宝`
    : `【Batch Task Planning】\n\nUser input: ${task}\nInstalled apps: WeChat, Meituan, Alipay` }], 0.2);
  const j = parseJson(raw);
  const tasks = j?.tasks || [];
  const ok = !!j && j.is_batch === true && tasks.length >= 2
    && tasks.every((t) => t.description && Array.isArray(t.steps) && t.steps.length >= 1);
  report('batch', 'B01', '多子任务识别为批量且各自带步骤', ok,
    `is_batch=${j?.is_batch} tasks=${tasks.length}\n      raw=${raw.slice(0, 240)}`);
}

// ==================== 5. 验证 / 记忆提炼 ====================
async function testHelpers(lang) {
  console.log(`\n===== 5. 验证与记忆提炼 · ${lang} =====`);
  const L = lang === 'CN' ? 'CN' : 'EN';

  {
    const raw = await callAgnes([{ role: 'user', content: L === 'CN'
      ? `【执行验证】\n\n上一个动作：点击"搜索商品"框\n预期结果：键盘弹出且输入框获得焦点\n\n当前页面：{"elements":[{"id":"node_search","label":"搜索商品","focused":true}],"context_hint":"美团首页"}`
      : `【Execution Verification】\n\nPrevious action: tap the "Search products" box\nExpected: keyboard appears and the field is focused\n\nCurrent page: {"elements":[{"id":"node_search","label":"Search products","focused":true}],"context_hint":"Meituan home"}` }], 0.1);
    const j = parseJson(raw);
    const ok = !!j && typeof j.success === 'boolean' && typeof j.reason === 'string';
    report('helper', 'H01', '执行验证输出 success/reason', ok, `json=${raw.slice(0, 160)}`);
  }

  {
    const raw = await callAgnes([{ role: 'user', content: L === 'CN'
      ? `【记忆提炼】\n\n刚完成的任务：在美团点一份黄焖鸡米饭\n结果：已完成，订单提交成功\n执行过程摘要：\n1. 打开美团 ✅\n2. 搜索"黄焖鸡米饭" ✅\n3. 选第一家店下单 ✅`
      : `【Memory Distillation】\n\nTask just finished: order braised chicken rice on Meituan\nOutcome: completed, order submitted\nExecution summary:\n1. open Meituan ✅\n2. search "braised chicken rice" ✅\n3. order from the first shop ✅` }], 0.1);
    const j = parseJson(raw);
    const mems = j?.memories;
    const ok = !!j && Array.isArray(mems) && mems.length <= 3
      && mems.every((m) => m.content && ['preference', 'fact', 'habit', 'tip'].includes(m.category));
    report('helper', 'H02', '记忆提炼输出 memories（≤3 条且分类合法）', ok, `json=${raw.slice(0, 200)}`);
  }
}

// ==================== main ====================
(async () => {
  console.log(`提示词来源: ${P.source}`);
  console.log(`字符量: systemCN=${P.systemCN.length} systemEN=${P.systemEN.length} planCN=${P.planCN.length} decisionCN=${P.decisionCN.length} replanCN=${P.replanCN.length}`);
  try {
    if (only === 'cn' || only === 'all') {
      await testDecision('CN');
      await testPlanning('CN');
      await testReplan('CN');
      await testBatch('CN');
      await testHelpers('CN');
    }
    if (only === 'en' || only === 'all') {
      await testDecision('EN');
      await testPlanning('EN');
      await testReplan('EN');
      await testBatch('EN');
    }
  } catch (e) {
    console.log(`\n⛔ 运行中断: ${e.message}`);
    failed++;
  }

  console.log(`\n================ 结果 ================`);
  console.log(`通过 ${passed} / 失败 ${failed}${skipped ? ` / 跳过 ${skipped}` : ''}`);
  if (failures.length) {
    console.log('\n失败明细:');
    failures.forEach((f, i) => console.log(`${i + 1}. ${f.split('\n')[0]}`));
  }
  process.exitCode = failed ? 1 : 0;
})();