/**
 * Agnes 2.5 Flash 回归测试
 * 使用项目原始 TestPresets 中的所有用例，测试优化后的 V2 提示词
 * 以及原始 V1 提示词，对比通过率
 */
const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

// ====== 原始 V1 系统提示词（英文，使用 "action" 字段） ======
const SYSTEM_V1 = `You are Phantom, an Android automation agent being regression-tested.
# Output rules (highest priority)
1. Output ONLY a single JSON. First char MUST be { or [, last MUST be } or ].
2. NEVER output markdown modifiers or any extra text.
# Action object (mandatory field name: "action")
Every action is a JSON object:
- "action": one of tap | long_press | swipe | type | key | wait | launch | scroll_to | abort | task_complete
- "target": {"method": "id"|"label"|"coordinate", plus the needed id/value/x/y}
- "confidence": a number between 0 and 1
- "reasoning": a short reason
- "needs_user_confirmation": true ONLY for irreversible actions (payment, delete, send).
# Targeting
- accessibility source -> method "id" (prefer) or "label". NEVER "coordinate".
- screenshot source -> method "coordinate" only.
# Countdown ad
- If context_hint contains "Countdown Ad" -> action MUST be "wait", NEVER "tap".
# Output form
Prefer a single JSON object unless merging is required.`;

// ====== 优化 V2 系统提示词（英文，使用 "type" 字段 + 反例强化） ======
const SYSTEM_V2 = `You are Phantom, an Android automation agent being regression-tested.
# Output rules (highest priority)
1. Output ONLY a single JSON. First char MUST be { or [, last MUST be } or ].
2. NEVER output markdown modifiers or any extra text.
# Action object (mandatory field name: "type", NOT "action")
Every action is a JSON object. The field name MUST be "type", NOT "action":
- "type": one of tap | long_press | swipe | type | key | wait | launch | scroll_to | abort | task_complete
- "target": {"method": "id"|"label"|"coordinate", "value": "..."} (MUST be nested object)
- "confidence": a number between 0 and 1
- "reasoning": a short reason
- "needs_user_confirmation": true ONLY for irreversible actions (payment, delete, send).
# Wrong output (DO NOT follow)
- {"action":"click","target":{"id":"..."}}  ← field name must be "type", not "action"
- {"method":"id","value":"..."} ← missing "target" wrapper
- {"element_id":"node_abc"} ← must use target.method / target.value
# Targeting
- accessibility source -> method "id" (prefer) or "label". NEVER "coordinate".
- screenshot source -> method "coordinate" only.
# Countdown ad (Iron Rule)
- If context_hint contains "⚠️ Countdown Ad" -> action MUST be "wait", NEVER "tap" or "click". ABSOLUTELY FORBIDDEN to tap "Skip".
# Output form
Prefer a single JSON object unless merging is required.`;

// ====== 测试用例（来自 TestPresets.kt） ======
const TEST_CASES = [
  { id: 'F01', name: '纯 JSON 输出', check: (r) => r.parsed !== null && !r.raw.includes('```') },
  { id: 'F02', name: '不含 Markdown', check: (r) => !r.raw.includes('```') },
  { id: 'F04', name: '必填字段齐全', check: (r) => r.parsed && (r.parsed.type || r.parsed.action) },
  { id: 'B01', name: '有id→method=id', check: (r) => r.parsed?.target?.method === 'id' },
  { id: 'B02', name: '无id有label→method=label', check: (r) => r.parsed?.target?.method === 'label' },
  { id: 'B03', name: 'accessibility不使用coordinate', check: (r) => r.parsed?.target?.method !== 'coordinate' },
  { id: 'B04', name: 'screenshot→method=coordinate', check: (r) => r.parsed?.target?.method === 'coordinate' },
  { id: 'C01', name: '倒计时广告→wait', check: (r) => (r.parsed?.type || r.parsed?.action) === 'wait' },
  { id: 'C03', name: '支付确认→needs_user_confirmation', check: (r) => r.parsed?.needs_user_confirmation === true },
  { id: 'C07', name: '上一步成功→继续tap', check: (r) => (r.parsed?.type || r.parsed?.action) === 'tap' },
  { id: 'C08', name: '选评分最高→tap第一个', check: (r) => (r.parsed?.type || r.parsed?.action) === 'tap' },
  { id: 'C10', name: '购物车提交→tap提交', check: (r) => (r.parsed?.type || r.parsed?.action) === 'tap' },
  { id: 'R01', name: '正常点击决策→tap', check: (r) => (r.parsed?.type || r.parsed?.action) === 'tap' },
  { id: 'R02', name: '弹窗→tap（Allow）', check: (r) => (r.parsed?.type || r.parsed?.action) === 'tap' },
  { id: 'R09', name: '任务完成→task_complete', check: (r) => (r.parsed?.type || r.parsed?.action) === 'task_complete' },
];

const USER_PROMPTS = {
  F01: 'Page snapshot: {"context_hint":"Search results page","fingerprint":"f_abc123","elements":[{"id":"search_input","type":"EditText","label":"Search","clickable":true,"focused":false},{"id":"btn_submit","type":"Button","label":"Search","clickable":true}]}\nTask: Search for "coffee" on this page.',
  F02: 'Page snapshot: {"context_hint":"Detail page","elements":[{"id":"add_cart","label":"Add to cart","clickable":true},{"id":"back","label":"Back","clickable":true}]}\nTask: Add this item to the cart.',
  F04: 'Page snapshot: {"context_hint":"Home page","elements":[{"id":"tab_orders","label":"Orders","clickable":true}]}\nTask: Open the Orders tab.',
  B01: 'Source: accessibility element tree.\nElements: [{"id":"node_a1","label":"Settings","clickable":true},{"id":"node_a2","label":"Profile","clickable":true}]\nTask: Tap the Settings entry.',
  B02: 'Source: accessibility element tree.\nElements: [{"id":"","label":"Confirm Order","clickable":true},{"id":"","label":"Cancel","clickable":true}]\nTask: Tap the Confirm Order button.',
  B03: 'Source: accessibility element tree.\nElements: [{"id":"node_x","label":"Agree","clickable":true}]\nTask: Tap the Agree button.',
  B04: 'Source: screenshot only (no element tree).\nA search box is at the top of the screen, a submit button at bottom-right.\nTask: Tap the submit button.',
  C01: 'Page snapshot: {"context_hint":"⚠️ Countdown Ad - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true},{"id":"under","label":"dark","clickable":true}]}\nTask: Reach the home page (the ad is still counting down).',
  C03: 'Page snapshot: {"context_hint":"Payment confirmation page","elements":[{"id":"btn_pay","label":"Pay 99.0","clickable":true},{"id":"btn_cancel","label":"Cancel","clickable":true}]}\nTask: Submit the order and complete payment.',
  C07: 'Page snapshot: {"context_hint":"Step 2 of 3 - form page","elements":[{"id":"fld_name","label":"Name","clickable":true},{"id":"btn_next","label":"Next","clickable":true}]}\nTask: Fill the form. Last step result: ✅ verified (name filled successfully).',
  C08: 'Page snapshot: {"context_hint":"Search results list","elements":[{"id":"r1","label":"Restaurant A rating 4.9","clickable":true},{"id":"r2","label":"Restaurant B rating 4.2","clickable":true}]}\nTask: Choose the highest-rated restaurant.',
  C10: 'Page snapshot: {"context_hint":"Cart page","elements":[{"id":"cart_item","label":"Item x1","clickable":false},{"id":"btn_checkout","label":"Checkout","clickable":true},{"id":"btn_add","label":"Add more","clickable":true}]}\nTask: Submit the order.',
  R01: 'Page snapshot: {"context_hint":"Search results page","elements":[{"id":"item1","label":"Store A","clickable":true},{"id":"item2","label":"Store B","clickable":true}]}\nTask: Open Store A.',
  R02: 'Page snapshot: {"context_hint":"Permission dialog","elements":[{"id":"btn_allow","label":"Allow","clickable":true},{"id":"btn_deny","label":"Deny","clickable":true}]}\nTask: Continue the previous task (a permission dialog is blocking it).',
  R09: 'Page snapshot: {"context_hint":"Order placed successfully","elements":[{"id":"order_id","label":"Order #1234","clickable":false}]}\nTask: The order was placed. Confirm completion.',
};

async function callAgnes(messages) {
  const url = `${API_BASE}/chat/completions`;
  const body = { model: MODEL, messages, temperature: 0.1, max_tokens: 4096 };
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${API_KEY}` },
    body: JSON.stringify(body),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
  const data = await resp.json();
  return data.choices?.[0]?.message?.content?.trim() || '';
}

function parseJson(text) {
  try { return JSON.parse(text); } catch {}
  const m = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (m) try { return JSON.parse(m[1].trim()); } catch {}
  const s = text.indexOf('{'), e = text.lastIndexOf('}');
  if (s >= 0 && e > s) try { return JSON.parse(text.substring(s, e + 1)); } catch {}
  return null;
}

async function runTestSuite(version, systemPrompt) {
  console.log(`\n  🔄 ${version} 运行中...`);
  let passed = 0, total = 0;
  const results = [];

  for (const tc of TEST_CASES) {
    total++;
    const prompt = USER_PROMPTS[tc.id];
    if (!prompt) { results.push({ ...tc, ok: false, detail: 'Missing prompt' }); continue; }
    try {
      const raw = await callAgnes([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: prompt }
      ]);
      const parsed = parseJson(raw);
      const ok = tc.check({ parsed, raw });
      if (ok) passed++;
      const detail = ok ? `type="${parsed?.type||parsed?.action}"` : `raw: ${raw.slice(0, 60)}`;
      results.push({ ...tc, ok, detail });
    } catch (e) {
      results.push({ ...tc, ok: false, detail: e.message });
    }
  }
  return { passed, total, results };
}

async function main() {
  console.log('📊 Agnes 2.5 Flash 回归测试对比');
  console.log('='.repeat(60));
  console.log(`模型: ${MODEL}`);
  console.log(`用例数: ${TEST_CASES.length} 个`);
  console.log('='.repeat(60));

  // 测试 V1（原始）
  const v1 = await runTestSuite('V1(原始)', SYSTEM_V1);

  // 测试 V2（优化）
  const v2 = await runTestSuite('V2(优化)', SYSTEM_V2);

  // 汇总
  console.log('\n' + '='.repeat(60));
  console.log('📊 对比结果');
  console.log('='.repeat(60));
  console.log(`  V1(原始) 通过率: ${v1.passed}/${v1.total} (${(v1.passed/v1.total*100).toFixed(1)}%)`);
  console.log(`  V2(优化) 通过率: ${v2.passed}/${v2.total} (${(v2.passed/v2.total*100).toFixed(1)}%)`);
  console.log(`  改进: +${v2.passed - v1.passed} 用例 (+${((v2.passed-v1.passed)/v1.total*100).toFixed(1)}%)`);

  // 逐用例对比
  console.log('\n📋 逐用例对比:');
  for (let i = 0; i < TEST_CASES.length; i++) {
    const tc = TEST_CASES[i];
    const r1 = v1.results[i];
    const r2 = v2.results[i];
    const icon = r1.ok === r2.ok ? (r1.ok ? '✅' : '❌') : (r2.ok ? '⬆' : '⬇');
    console.log(`  ${icon} [${tc.id}] ${tc.name.padEnd(20)} V1:${r1.ok?'✅':'❌'}  V2:${r2.ok?'✅':'❌'}`);
  }

  // V1 失败详情
  const v1Fail = v1.results.filter(r => !r.ok);
  if (v1Fail.length > 0) {
    console.log(`\n❌ V1(原始) 失败 (${v1Fail.length}):`);
    for (const f of v1Fail) console.log(`  [${f.id}] ${f.name}: ${f.detail}`);
  }

  // V2 失败详情
  const v2Fail = v2.results.filter(r => !r.ok);
  if (v2Fail.length > 0) {
    console.log(`\n⚠️ V2(优化) 失败 (${v2Fail.length}):`);
    for (const f of v2Fail) console.log(`  [${f.id}] ${f.name}: ${f.detail}`);
  }
}

main().catch(e => { console.error('异常:', e); process.exit(1); });