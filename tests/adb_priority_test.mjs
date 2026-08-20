/**
 * ADB 优先策略专项测试 + 限流用例重试（顺序执行，间隔 2.5s）
 *
 * 验证最新提示词铁律第8条 + 决策原则第3条：
 * - Shizuku/ADB 可用时 → AI 优先输出 type="shell" + ADB 命令
 * - 仅当 ADB 不可用时 → 才回退 tap/swipe/key
 *
 * 用法: node tests/adb_priority_test.mjs
 */
const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

const results = [];
let passed = 0, failed = 0;

function report(group, caseId, caseName, ok, detail) {
  results.push({ group, caseId, caseName, ok, detail });
  if (ok) passed++; else failed++;
  const icon = ok ? '✅' : '❌';
  console.log(`  ${icon} [${group}/${caseId}] ${caseName}${ok ? '' : '\n      ' + detail}`);
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function callAgnes(messages, temperature = 0.1) {
  const body = { model: MODEL, messages, temperature, max_tokens: 4096 };
  const resp = await fetch(`${API_BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${API_KEY}` },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const err = await resp.text();
    throw new Error(`HTTP ${resp.status}: ${err.slice(0, 150)}`);
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

// 最新系统提示词（含 ADB 优先铁律）+ Shizuku 命令清单
const SYSTEM_CN = `你是 Phantom，一个 Android 手机操控 Agent。

# 铁律（违反任何一条 = 任务失败）
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
3. 禁止在 JSON 前后添加解释、问候、评论。
4. 拿不准做什么 → 输出 {"type":"task_done","reason":"原因"}。
5. 你是用户的手。不让用户操作手机。每步由你完成。
6. 每步只输出一个动作（除非满足合并条件）。
7. 严格按计划分步执行。不跳步，不合并无关操作。
8. Shizuku/ADB 可用时，点击、滑动、按键、输入等操作必须优先使用 type="shell" + ADB 命令，仅当 shell 不可用时才回退到 type="tap"/"swipe"/"key"。（ADB 更可靠，不依赖无障碍元素树）

# 动作类型（字段名必须为 "type"，禁止使用 "action"）
| type | 必填字段 | 示例 |
|------|----------|------|
| tap | target | {"type":"tap","target":{"method":"id","value":"node_a3f2"},"reasoning":"点击","expected":"生效","confidence":0.9} |
| shell | command | {"type":"shell","command":"input tap 500 800","reasoning":"ADB 点击","expected":"点击生效","confidence":0.9} |
| shell | command | {"type":"shell","command":"input swipe 500 800 500 800 1500","reasoning":"ADB 长按","expected":"弹出菜单","confidence":0.85} |
| shell | command | {"type":"shell","command":"input keyevent 4","reasoning":"ADB 返回","expected":"返回上页","confidence":0.9} |
| shell | command | {"type":"shell","command":"input text \\"hello\\"","reasoning":"ADB 输入","expected":"文字填入","confidence":0.9} |
| task_complete | summary | {"type":"task_complete","summary":"任务完成","reasoning":"完成","confidence":1.0} |

# 决策原则
1. 先处理意外（弹窗/权限/错误），再执行原计划。
2. Shizuku 可用时，所有点击/滑动/按键/输入必须用 type="shell" + ADB 命令（如 input tap、input swipe、input keyevent）。ADB 操作不依赖无障碍元素树，更可靠。仅当 ADB 不可用时才用 type="tap"/"swipe"/"key"。

只输出 JSON。`;

const SHIZUKU_NOTICE = `Shizuku: 已连接。你可以使用 type="shell" 执行以下 ADB 预设命令：
点击: input tap {x} {y}
长按: input swipe {x} {y} {x} {y} 1500
上滑: input swipe {x} {y} {x} {y-400} 400
输入文字: input text "文字"
返回: 4 | 主页: 3 | 最近: 187
启动应用: am start -n {包名}/{Activity名}
列出所有Activity: dumpsys package {包名} | grep "Activity" | grep -v "filter"`;

// ==================== ADB 优先专项 ====================
async function adbPriorityTests() {
  console.log('\n========== ADB 优先策略专项测试 ==========');

  // A01: 中文 Shizuku 可用 → 点击用 shell
  try {
    await sleep(2500);
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'system', content: SHIZUKU_NOTICE },
      { role: 'user', content: '{"elements":[{"id":"btn_search","label":"搜索","clickable":true,"bounds_ratio":[0.1,0.1,0.3,0.15]}]}\n任务：点击搜索按钮。' },
    ]);
    const p = parseJson(raw);
    const isShell = p && p.type === 'shell' && typeof p.command === 'string';
    report('ADB', 'A01', '中文Shizuku可用→点击用shell', isShell, isShell ? `command=${p.command}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('ADB', 'A01', '中文Shizuku可用→点击用shell', false, e.message); }

  // A02: 中文 Shizuku 可用 → 长按用 shell
  try {
    await sleep(2500);
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'system', content: SHIZUKU_NOTICE },
      { role: 'user', content: '{"elements":[{"id":"item","label":"删除项","clickable":true}]}\n任务：长按删除项。' },
    ]);
    const p = parseJson(raw);
    const isShell = p && p.type === 'shell' && (p.command || '').includes('swipe');
    report('ADB', 'A02', '中文Shizuku可用→长按用shell', isShell, isShell ? `command=${p.command}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('ADB', 'A02', '中文Shizuku可用→长按用shell', false, e.message); }

  // A03: 中文 Shizuku 可用 → 返回用 shell keyevent
  try {
    await sleep(2500);
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'system', content: SHIZUKU_NOTICE },
      { role: 'user', content: '{"elements":[{"id":"btn_back","label":"返回","clickable":true}]}\n任务：返回上一页。' },
    ]);
    const p = parseJson(raw);
    const isShell = p && p.type === 'shell' && (p.command || '').includes('keyevent');
    report('ADB', 'A03', '中文Shizuku可用→返回用shell', isShell, isShell ? `command=${p.command}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('ADB', 'A03', '中文Shizuku可用→返回用shell', false, e.message); }

  // A04: 中文 Shizuku 可用 → 输入用 shell input text
  try {
    await sleep(2500);
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'system', content: SHIZUKU_NOTICE },
      { role: 'user', content: '{"elements":[{"id":"input_1","label":"","editable":true}]}\n任务：输入关键词"手机"。' },
    ]);
    const p = parseJson(raw);
    const isShell = p && p.type === 'shell' && (p.command || '').toLowerCase().includes('input text');
    report('ADB', 'A04', '中文Shizuku可用→输入用shell', isShell, isShell ? `command=${p.command}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('ADB', 'A04', '中文Shizuku可用→输入用shell', false, e.message); }

  // A05: 中文 Shizuku 不可用 → 回退 tap
  const SYSTEM_NO_ADB = `你是 Phantom，一个 Android 手机操控 Agent。
# 铁律
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止 Markdown。3. 每步一个动作。
4. 字段名必须为 "type"。
5. target 必须是嵌套对象 {"method":"id","value":"..."}。
# 动作类型
| type | 必填字段 |
|------|----------|
| tap | target |
| task_complete | summary |
只输出 JSON。`;
  try {
    await sleep(2500);
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_NO_ADB },
      { role: 'user', content: '{"elements":[{"id":"btn_ok","label":"确定","clickable":true}]}\n任务：点击确定。' },
    ]);
    const p = parseJson(raw);
    const isTap = p && p.type === 'tap' && p.target && typeof p.target === 'object';
    report('ADB', 'A05', 'Shizuku不可用→回退tap', isTap, isTap ? `target=${JSON.stringify(p.target)}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('ADB', 'A05', 'Shizuku不可用→回退tap', false, e.message); }

  // A06: 中文 Shizuku 可用 → 启动应用用 shell am start
  try {
    await sleep(2500);
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'system', content: SHIZUKU_NOTICE },
      { role: 'user', content: '{"elements":[]}\n任务：启动微信。' },
    ]);
    const p = parseJson(raw);
    const isShell = p && p.type === 'shell' && (p.command || '').includes('am start');
    report('ADB', 'A06', '中文Shizuku可用→启动应用用shell', isShell, isShell ? `command=${p.command}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('ADB', 'A06', '中文Shizuku可用→启动应用用shell', false, e.message); }
}

// ==================== 限流用例重试 ====================
async function retryRateLimited() {
  console.log('\n========== 限流用例重试（S04/S05/S06/S07/P03/P04） ==========');

  const BASE = `你是 Phantom，一个 Android 手机操控 Agent。
# 铁律
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止 Markdown。3. 每步一个动作。
4. 字段名必须为 "type"。5. target 必须是嵌套对象 {"method":"id","value":"..."}。
# 动作类型
| type | 必填字段 |
|------|----------|
| tap | target |
| task_complete | summary |
只输出 JSON。`;

  // R01: S04 英文 type 字段
  try {
    await sleep(2500);
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: '{"elements":[{"id":"btn_ok","label":"OK","clickable":true}]}\nTask: Tap OK.' }]);
    const p = parseJson(raw);
    const ok = p && p.type !== undefined && p.action === undefined;
    report('RETRY', 'S04', '英文type字段(非action)', ok, ok ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('RETRY', 'S04', '英文type字段(非action)', false, e.message); }

  // R02: S05 target 嵌套
  try {
    await sleep(2500);
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: '{"elements":[{"id":"node_x","label":"同意","clickable":true}]}\n任务：点击同意。' }]);
    const p = parseJson(raw);
    const ok = p && p.target && typeof p.target === 'object' && p.target.method;
    report('RETRY', 'S05', 'target嵌套结构', ok, ok ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('RETRY', 'S05', 'target嵌套结构', false, e.message); }

  // R03: S06 向后搜寻（中文末尾元素）
  try {
    await sleep(2500);
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: '{"elements":[{"id":"a1","label":"首页","clickable":true},{"id":"a5","label":"设置","clickable":true}]}\n任务：点击设置。' }]);
    const p = parseJson(raw);
    const ok = p && p.target && p.target.value === 'a5';
    report('RETRY', 'S06', '向后搜寻→找到末尾元素', ok, ok ? '' : `target=${JSON.stringify(p?.target)} raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('RETRY', 'S06', '向后搜寻→找到末尾元素', false, e.message); }

  // R04: S07 向后搜寻（英文末尾元素）
  try {
    await sleep(2500);
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: '{"elements":[{"id":"a1","label":"Home","clickable":true},{"id":"a5","label":"Settings","clickable":true}]}\nTask: Tap Settings.' }]);
    const p = parseJson(raw);
    const ok = p && p.target && p.target.value === 'a5';
    report('RETRY', 'S07', 'Backward search→find last element', ok, ok ? '' : `target=${JSON.stringify(p?.target)} raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('RETRY', 'S07', 'Backward search→find last element', false, e.message); }

  // R05: P03 中文模糊任务→歧义检测
  try {
    await sleep(2500);
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: '任务：帮我买点东西\n已安装：淘宝,京东,拼多多\n判断是否需要澄清：目标App不明确。\n输出：{"needs_clarification":true,"clarification":{"question":"问","options":[{"id":"1","label":"选项","description":"说明"}]}} 或 {"needs_clarification":false,"plan":{...}}' }], 0.3);
    const p = parseJson(raw);
    const ok = p && p.needs_clarification === true && p.clarification && Array.isArray(p.clarification.options) && p.clarification.options.length >= 2;
    report('RETRY', 'P03', '模糊任务→歧义检测', ok, ok ? `选项:${p.clarification.options.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('RETRY', 'P03', '模糊任务→歧义检测', false, e.message); }

  // R06: P04 英文模糊任务→歧义检测
  try {
    await sleep(2500);
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: 'Task: Buy something for me\nInstalled: Taobao,JD,Pinduoduo\nDetermine if clarification needed: target app unclear.\nOutput: {"needs_clarification":true,"clarification":{"question":"ask","options":[{"id":"1","label":"option","description":"how"}]}} or {"needs_clarification":false,"plan":{...}}' }], 0.3);
    const p = parseJson(raw);
    const ok = p && p.needs_clarification === true && p.clarification && Array.isArray(p.clarification.options) && p.clarification.options.length >= 2;
    report('RETRY', 'P04', 'Ambiguous→歧义检测', ok, ok ? `Options:${p.clarification.options.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('RETRY', 'P04', 'Ambiguous→歧义检测', false, e.message); }
}

async function main() {
  console.log('🔬 ADB 优先策略专项测试 + 限流重试');
  console.log(`模型: ${MODEL}`);
  console.log('='.repeat(60));

  await adbPriorityTests();
  await retryRateLimited();

  console.log('\n' + '='.repeat(60));
  console.log('📊 测试汇总');
  const total = passed + failed;
  console.log(`通过: ${passed} | 失败: ${failed} | 总计: ${total} | 通过率: ${(total > 0 ? (passed / total * 100) : 0).toFixed(1)}%`);
  const failures = results.filter(r => !r.ok);
  if (failures.length > 0) {
    console.log('\n❌ 失败详情:');
    for (const f of failures) {
      console.log(`  [${f.group}/${f.caseId}] ${f.caseName}`);
      console.log(`    ${f.detail}`);
    }
  }
  console.log('\n✅ 测试完成');
}

main().catch(e => { console.error('测试异常:', e); process.exit(1); });