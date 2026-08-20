/**
 * 重试被限流的测试用例（顺序执行，间隔2秒）
 */
const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

async function callAgnes(messages, temperature = 0.1) {
  const url = `${API_BASE}/chat/completions`;
  const body = { model: MODEL, messages, temperature, max_tokens: 4096 };
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${API_KEY}` },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const err = await resp.text();
    throw new Error(`HTTP ${resp.status}: ${err.slice(0, 200)}`);
  }
  const data = await resp.json();
  return data.choices?.[0]?.message?.content?.trim() || '';
}

function parseJson(text) {
  try { return JSON.parse(text); } catch {}
  const m = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (m) { try { return JSON.parse(m[1].trim()); } catch {} }
  const lastBrace = text.lastIndexOf('}');
  if (lastBrace >= 0) {
    let depth = 0, inString = false, escape = false;
    for (let i = lastBrace; i >= 0; i--) {
      const c = text[i];
      if (escape) { escape = false; continue; }
      if (c === '\\' && inString) { escape = true; continue; }
      if (c === '"') { inString = !inString; continue; }
      if (inString) continue;
      if (c === '}') depth++;
      else if (c === '{') { depth--; if (depth === 0) { try { return JSON.parse(text.substring(i, lastBrace + 1)); } catch {} break; } }
    }
  }
  const start = text.indexOf('{'), end = text.lastIndexOf('}');
  if (start >= 0 && end > start) { try { return JSON.parse(text.substring(start, end + 1)); } catch {} }
  return null;
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function testRPL_EN_R02() {
  console.log('\n--- RPL/EN/R02: No results→replan ---');
  const replanEN = `【Replan】
User task: Search for "xyz123" and buy it
Stuck reason: No results found for "xyz123"
Executed steps: 1. Open app ✅ | 2. Search "xyz123" ❌ no results

# Replan Strategy
| Scenario | Strategy |
|----------|----------|
| No search results | change keyword or app |

Output: {"replan_reason":"why","steps":[{"description":"new step","intent":"expected"}],"confidence":0~1}`;

  try {
    await sleep(2000);
    const raw = await callAgnes([{ role: 'system', content: '你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。' }, { role: 'user', content: replanEN }], 0.3);
    const parsed = parseJson(raw);
    const hasReplan = parsed && parsed.replan_reason && Array.isArray(parsed.steps);
    console.log(hasReplan ? `✅ PASS (Steps:${parsed.steps.length})` : `❌ FAIL: ${raw.slice(0, 120)}`);
    return hasReplan;
  } catch (e) { console.log(`❌ ERROR: ${e.message}`); return false; }
}

async function testPLN_EN_P04() {
  console.log('\n--- PLN/EN/P04: Ambiguous→detect ambiguity ---');
  const ambigEN = `【Mode: Ambiguity Detection + Task Planning】
User task: Buy something for me
User preferences: none
Installed apps: Taobao, JD, Pinduoduo

# Ambiguity Detection Conditions
- Target app unclear
- Multiple candidates with distinct outcomes

# Output Format
No ambiguity: {"needs_clarification":false,"plan":{"steps":[...]}}
Ambiguity: {"needs_clarification":true,"clarification":{"question":"ask","options":[{"id":"id","label":"title","description":"how","is_default":bool}]}}

2~5 options. Manual input id = "manual", placed last.
Output ONLY JSON.`;

  try {
    await sleep(2000);
    const raw = await callAgnes([{ role: 'system', content: '你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。' }, { role: 'user', content: ambigEN }], 0.3);
    const parsed = parseJson(raw);
    const hasAmbiguity = parsed && parsed.needs_clarification === true && parsed.clarification;
    const hasOptions = hasAmbiguity && Array.isArray(parsed.clarification.options) && parsed.clarification.options.length >= 2;
    console.log(hasAmbiguity && hasOptions ? `✅ PASS (Options:${parsed.clarification.options.length})` : `❌ FAIL: ${raw.slice(0, 120)}`);
    return hasAmbiguity && hasOptions;
  } catch (e) { console.log(`❌ ERROR: ${e.message}`); return false; }
}

async function testGDE_EN_U02() {
  console.log('\n--- GDE/EN/U02: Hint mismatch→abort ---');
  const GUIDE_SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。

# 动作格式（必须遵守）
- 字段名必须是 "type"（禁止 "action"）
- target 必须是嵌套对象 {"method":"id"|"label"|"coordinate","value":"..."}`;

  const guideFailEN = `【User Guidance】
User said: "click the delete button"
Follow the user's hint.

User task: View profile
Current step: check info
Previous failures: none

# Output
Hint matches page → follow it, output action JSON.
No matching control → {"type":"abort","reason":"Following hint, no matching control","confidence":0}

## Current Page
{"context_hint":"Profile page","elements":[{"id":"btn_edit","label":"Edit","clickable":true},{"id":"btn_back","label":"Back","clickable":true}]}`;

  try {
    await sleep(2000);
    const raw = await callAgnes([{ role: 'system', content: GUIDE_SYSTEM }, { role: 'user', content: guideFailEN }]);
    const parsed = parseJson(raw);
    const isAbort = parsed && (parsed.type === 'abort' || parsed.type === 'task_done');
    console.log(isAbort ? '✅ PASS' : `❌ FAIL: ${raw.slice(0, 100)}`);
    return isAbort;
  } catch (e) { console.log(`❌ ERROR: ${e.message}`); return false; }
}

async function main() {
  console.log('🔁 重试被限流的3个测试');
  const r1 = await testRPL_EN_R02();
  const r2 = await testPLN_EN_P04();
  const r3 = await testGDE_EN_U02();
  console.log(`\n重试结果: ${[r1, r2, r3].filter(Boolean).length}/3 通过`);
}

main().catch(e => { console.error('异常:', e); process.exit(1); });