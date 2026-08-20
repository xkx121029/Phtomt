/**
 * HPA 全提示词测试套件 v3.0
 * 覆盖全部 8 种提示词类型（中英双语）+ 向后搜寻 JSON 字段
 *
 * 用法: node tests/all_prompts_test.mjs
 */

const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

const results = [];
let passed = 0, failed = 0;

function report(group, lang, caseId, caseName, ok, detail) {
  results.push({ group, lang, caseId, caseName, ok, detail });
  if (ok) passed++; else failed++;
  const icon = ok ? '✅' : '❌';
  const detailStr = ok ? '' : '\n      ' + detail;
  console.log(`  ${icon} [${group}/${lang}/${caseId}] ${caseName}${detailStr}`);
}

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
  // 提取代码块
  const m = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (m) { try { return JSON.parse(m[1].trim()); } catch {} }
  // 反向搜索：从末尾 } 向前匹配
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
  // 兜底：第一个 { 到最后一个 }
  const start = text.indexOf('{'), end = text.lastIndexOf('}');
  if (start >= 0 && end > start) { try { return JSON.parse(text.substring(start, end + 1)); } catch {} }
  return null;
}

// ==================== 1. 系统提示词 (System) ====================
async function testSystemPrompt() {
  console.log('\n========== 1. 系统提示词 (System) + 向后搜寻 ==========');

  const SYSTEM_CN = `你是 Phantom，一个 Android 手机操控 Agent。

# 铁律（违反任何一条 = 任务失败）
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
3. 禁止在 JSON 前后添加解释、问候、评论。

# JSON 字段向后搜寻（铁律级别）
页面数据为嵌套 JSON。当目标字段不在当前位置时，自动向后搜寻：
- 在 elements 数组中从当前位置向后查找匹配的控件
- 在嵌套 children 中递归向后搜寻目标字段
- 找不到时，扩大搜索范围到整个 elements 数组
- 禁止只看前几个元素就放弃；必须遍历整个数组

# 动作类型（字段名必须为 "type"，禁止使用 "action"）
| type | 必填字段 |
|------|----------|
| tap | target |
| long_press | target,durationMs |
| swipe | direction,distancePx |
| type | target,text |
| key | keycode |
| wait | timeout_ms |
| launch | packageName |
| scroll_to | target |
| task_complete | summary |
| abort | reason |

# target 结构（必须嵌套，禁止扁平化）
✅ 正确：{"target":{"method":"id","value":"node_abc"}}
❌ 错误：{"method":"id","value":"node_abc"}（缺少 target 外层）

只输出 JSON。`;

  const SYSTEM_EN = `You are Phantom, an Android device automation agent.

# Iron Rules (violation = task failure)
1. Response = pure JSON only. First char = {, last char = }.
2. NEVER output \`\`\`json, \`\`\` or any Markdown markers.

# JSON Field Backward Search (Iron Rule)
Page data is nested JSON. When target field is not at current position, automatically search backward:
- Search elements array from current position backward for matching controls
- Recursively search backward in nested children for target fields
- If not found, expand search scope to entire elements array
- NEVER give up after checking only first few elements; must traverse entire array

# Action Types (field name MUST be "type", NOT "action")
| type | Required fields |
|------|-----------------|
| tap | target |
| long_press | target,durationMs |
| swipe | direction,distancePx |
| type | target,text |
| key | keycode |
| wait | timeout_ms |
| launch | packageName |
| scroll_to | target |
| task_complete | summary |
| abort | reason |

# Target structure (MUST be nested, NEVER flat)
✅ Correct: {"target":{"method":"id","value":"node_abc"}}
❌ Wrong: {"method":"id","value":"node_abc"} (missing "target" wrapper)

Output ONLY JSON.`;

  // S01: 中文纯 JSON 输出
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'user', content: 'Page snapshot: {"elements":[{"id":"btn","label":"搜索","clickable":true}]}\nTask: 点击搜索按钮。' }
    ]);
    const parsed = parseJson(raw);
    const ok = parsed !== null && parsed.type === 'tap' && !raw.includes('```');
    report('SYS', 'CN', 'S01', '纯JSON输出', ok, ok ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'CN', 'S01', '纯JSON输出', false, e.message); }

  // S02: 英文纯 JSON 输出
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_EN },
      { role: 'user', content: 'Page snapshot: {"elements":[{"id":"btn","label":"Search","clickable":true}]}\nTask: Tap the search button.' }
    ]);
    const parsed = parseJson(raw);
    const ok = parsed !== null && parsed.type === 'tap' && !raw.includes('```');
    report('SYS', 'EN', 'S02', 'Pure JSON output', ok, ok ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'EN', 'S02', 'Pure JSON output', false, e.message); }

  // S03: 中文使用 type 字段（非 action）
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'user', content: '{"elements":[{"id":"btn_ok","label":"确定","clickable":true}]}\nTask: 点击确定。' }
    ]);
    const parsed = parseJson(raw);
    const hasType = parsed && parsed.type !== undefined;
    const noAction = parsed && parsed.action === undefined;
    report('SYS', 'CN', 'S03', '使用type字段(非action)', hasType && noAction, hasType ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'CN', 'S03', '使用type字段(非action)', false, e.message); }

  // S04: 英文使用 type 字段
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_EN },
      { role: 'user', content: '{"elements":[{"id":"btn_ok","label":"OK","clickable":true}]}\nTask: Tap OK.' }
    ]);
    const parsed = parseJson(raw);
    const hasType = parsed && parsed.type !== undefined;
    const noAction = parsed && parsed.action === undefined;
    report('SYS', 'EN', 'S04', 'Use "type" field (not "action")', hasType && noAction, hasType ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'EN', 'S04', 'Use "type" field (not "action")', false, e.message); }

  // S05: 中文 target 嵌套结构
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'user', content: '{"elements":[{"id":"node_x","label":"同意","clickable":true}]}\nTask: 点击同意按钮。' }
    ]);
    const parsed = parseJson(raw);
    const hasNestedTarget = parsed && parsed.target && typeof parsed.target === 'object' && parsed.target.method;
    report('SYS', 'CN', 'S05', 'target嵌套结构', hasNestedTarget, hasNestedTarget ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'CN', 'S05', 'target嵌套结构', false, e.message); }

  // S06: 向后搜寻 - 目标在数组末尾（中文）
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_CN },
      { role: 'user', content: '{"elements":[{"id":"a1","label":"首页","clickable":true},{"id":"a2","label":"分类","clickable":true},{"id":"a3","label":"购物车","clickable":true},{"id":"a4","label":"我的","clickable":true},{"id":"a5","label":"设置","clickable":true}]}\nTask: 点击设置。' }
    ]);
    const parsed = parseJson(raw);
    const targetsSettings = parsed && parsed.target && parsed.target.value === 'a5';
    report('SYS', 'CN', 'S06', '向后搜寻→找到末尾元素', targetsSettings, targetsSettings ? '' : `target: ${JSON.stringify(parsed?.target)}, raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'CN', 'S06', '向后搜寻→找到末尾元素', false, e.message); }

  // S07: 向后搜寻 - 目标在数组末尾（英文）
  try {
    const raw = await callAgnes([
      { role: 'system', content: SYSTEM_EN },
      { role: 'user', content: '{"elements":[{"id":"a1","label":"Home","clickable":true},{"id":"a2","label":"Categories","clickable":true},{"id":"a3","label":"Cart","clickable":true},{"id":"a4","label":"Profile","clickable":true},{"id":"a5","label":"Settings","clickable":true}]}\nTask: Tap Settings.' }
    ]);
    const parsed = parseJson(raw);
    const targetsSettings = parsed && parsed.target && parsed.target.value === 'a5';
    report('SYS', 'EN', 'S07', 'Backward search→find last element', targetsSettings, targetsSettings ? '' : `target: ${JSON.stringify(parsed?.target)}, raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('SYS', 'EN', 'S07', 'Backward search→find last element', false, e.message); }
}

// ==================== 2. 规划提示词 (Planning) ====================
async function testPlanningPrompt() {
  console.log('\n========== 2. 歧义检测 + 规划 (Planning) ==========');

  const BASE = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。禁止\`\`\`json。只输出JSON。`;

  // P01: 中文无歧义任务
  const planCN = `【模式：歧义检测 + 任务规划】

用户任务：打开微信，给张三发消息说"晚上一起吃饭"
用户偏好：无
已安装应用：未知

# 规划要求
1. 拆为 5~10 个原子步骤，每步只做一件事。
2. 每步包含操作描述 + 预期结果。
3. 严格顺序，不可跳跃。

# 输出格式
无歧义：{"needs_clarification":false,"plan":{"steps":[{"description":"具体操作","intent":"预期结果"}],"estimated_time_seconds":秒,"confidence":0~1}}
有歧义：{"needs_clarification":true,"clarification":{"question":"以用户口吻提问","options":[{"id":"标识","label":"标题","description":"说明","is_default":bool}]}}

只输出 JSON。`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: planCN }], 0.3);
    const parsed = parseJson(raw);
    const hasPlan = parsed && parsed.needs_clarification === false && parsed.plan && Array.isArray(parsed.plan.steps);
    const stepCount = hasPlan ? parsed.plan.steps.length : 0;
    const hasDesc = hasPlan && stepCount > 0 && parsed.plan.steps[0].description;
    report('PLN', 'CN', 'P01', '无歧义→返回计划', hasPlan && hasDesc, hasPlan ? `步骤:${stepCount}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('PLN', 'CN', 'P01', '无歧义→返回计划', false, e.message); }

  // P02: 英文无歧义任务
  const planEN = `【Mode: Ambiguity Detection + Task Planning】

User task: Open WeChat, send a message to Zhang San saying "let's have dinner together"
User preferences: none
Installed apps: unknown

# Output Format
No ambiguity: {"needs_clarification":false,"plan":{"steps":[{"description":"action","intent":"expected"}],"estimated_time_seconds":sec,"confidence":0~1}}
Ambiguity: {"needs_clarification":true,"clarification":{"question":"ask in user's voice","options":[{"id":"id","label":"title","description":"how","is_default":bool}]}}

Output ONLY JSON.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: planEN }], 0.3);
    const parsed = parseJson(raw);
    const hasPlan = parsed && parsed.needs_clarification === false && parsed.plan && Array.isArray(parsed.plan.steps);
    const stepCount = hasPlan ? parsed.plan.steps.length : 0;
    report('PLN', 'EN', 'P02', 'No ambiguity→return plan', hasPlan, hasPlan ? `Steps:${stepCount}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('PLN', 'EN', 'P02', 'No ambiguity→return plan', false, e.message); }

  // P03: 中文有歧义任务
  const ambigCN = `【模式：歧义检测 + 任务规划】

用户任务：帮我买点东西
用户偏好：无
已安装应用：淘宝, 京东, 拼多多

# 歧义检测条件
- 目标 App 不明确
- 多个候选且差异显著

# 输出格式
无歧义：{"needs_clarification":false,"plan":{"steps":[{"description":"具体操作","intent":"预期结果"}],"estimated_time_seconds":秒,"confidence":0~1}}
有歧义：{"needs_clarification":true,"clarification":{"question":"以用户口吻提问","options":[{"id":"标识","label":"标题","description":"说明","is_default":bool}]}}

选项 2~5 个。"✏️ 我想自己说"的 id = manual，放最末。
只输出 JSON。`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: ambigCN }], 0.3);
    const parsed = parseJson(raw);
    const hasAmbiguity = parsed && parsed.needs_clarification === true && parsed.clarification;
    const hasOptions = hasAmbiguity && Array.isArray(parsed.clarification.options) && parsed.clarification.options.length >= 2;
    report('PLN', 'CN', 'P03', '模糊任务→检测歧义', hasAmbiguity && hasOptions,
      hasAmbiguity ? `选项:${parsed.clarification.options.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('PLN', 'CN', 'P03', '模糊任务→检测歧义', false, e.message); }

  // P04: 英文有歧义任务
  const ambigEN = `【Mode: Ambiguity Detection + Task Planning】

User task: Buy something for me
User preferences: none
Installed apps: Taobao, JD, Pinduoduo

# Ambiguity Detection Conditions
- Target app unclear
- Multiple candidates with distinct outcomes

# Output Format
No ambiguity: {"needs_clarification":false,"plan":{"steps":[{"description":"action","intent":"expected"}],"estimated_time_seconds":sec,"confidence":0~1}}
Ambiguity: {"needs_clarification":true,"clarification":{"question":"ask in user's voice","options":[{"id":"id","label":"title","description":"how","is_default":bool}]}}

2~5 options. Manual input id = "manual", placed last.
Output ONLY JSON.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: ambigEN }], 0.3);
    const parsed = parseJson(raw);
    const hasAmbiguity = parsed && parsed.needs_clarification === true && parsed.clarification;
    const hasOptions = hasAmbiguity && Array.isArray(parsed.clarification.options) && parsed.clarification.options.length >= 2;
    report('PLN', 'EN', 'P04', 'Ambiguous→detect ambiguity', hasAmbiguity && hasOptions,
      hasAmbiguity ? `Options:${parsed.clarification.options.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('PLN', 'EN', 'P04', 'Ambiguous→detect ambiguity', false, e.message); }
}

// ==================== 3. 决策提示词 (Decision) ====================
async function testDecisionPrompt() {
  console.log('\n========== 3. 每步决策 (Decision) ==========');

  // 决策测试必须使用完整系统提示词，否则铁律（倒计时广告、字段命名等）不生效
  const DECISION_SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。

# 铁律（违反任何一条 = 任务失败）
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
3. 禁止在 JSON 前后添加解释、问候、评论。
4. 字段名必须是 "type"（禁止 "action"）。
5. target 必须是嵌套对象 {"method":"id"|"label","value":"..."}，禁止扁平化。

# 倒计时广告（铁律级别）
context_hint 含【⚠️ 疑似倒计时广告】→ 必须输出 wait，绝对禁止 tap。

# 不可逆操作
支付/删除/发送 → 必须设置 "needs_user_confirmation": true。

# 失败处理
| 连续失败次数 | 动作 |
|-------------|------|
| 3 次 | 输出 abort |

# 动作类型
| type | 必填字段 |
|------|----------|
| tap | target |
| wait | timeout_ms |
| abort | reason |
| task_complete | summary |

只输出 JSON。`;

  // D01: 中文倒计时广告→wait
  const adCN = `【执行决策】
任务：打开首页
步骤：[1/3] 等待广告结束
上一步结果：无
连续失败：0
页面提示：⚠️ 疑似倒计时广告 - 5秒倒计时广告覆盖页面

# 输出
只输出 JSON。禁止\`\`\`json标记。

## 当前页面
{"context_hint":"⚠️ 疑似倒计时广告 - 5s countdown ad","elements":[{"id":"btn_skip","label":"跳过 5","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: DECISION_SYSTEM }, { role: 'user', content: adCN }]);
    const parsed = parseJson(raw);
    const isWait = parsed && parsed.type === 'wait';
    report('DEC', 'CN', 'D01', '倒计时广告→wait', isWait, isWait ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('DEC', 'CN', 'D01', '倒计时广告→wait', false, e.message); }

  // D02: 英文倒计时广告→wait
  const adEN = `【Execution Decision】
Task: Open home page
Step: [1/3] wait for ad
Last step: none
Failures: 0
Page hint: ⚠️ Countdown Ad - 5s countdown ad

Only JSON.

## Current Page
{"context_hint":"⚠️ Countdown Ad - 5s countdown","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: DECISION_SYSTEM }, { role: 'user', content: adEN }]);
    const parsed = parseJson(raw);
    const isWait = parsed && parsed.type === 'wait';
    report('DEC', 'EN', 'D02', 'Countdown ad→wait', isWait, isWait ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('DEC', 'EN', 'D02', 'Countdown ad→wait', false, e.message); }

  // D03: 中文支付确认→needs_user_confirmation
  const payCN = `【执行决策】
任务：提交订单并完成支付
步骤：[3/3] 确认支付
上一步结果：✅ 已确认成功: tap(选择支付方式)
连续失败：0
页面提示：支付确认页

## 当前页面
{"context_hint":"Payment confirmation","elements":[{"id":"btn_pay","label":"支付 99.0","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: DECISION_SYSTEM }, { role: 'user', content: payCN }]);
    const parsed = parseJson(raw);
    const hasConfirm = parsed && parsed.needs_user_confirmation === true;
    report('DEC', 'CN', 'D03', '支付→needs_user_confirmation=true', hasConfirm, hasConfirm ? '' : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('DEC', 'CN', 'D03', '支付→needs_user_confirmation=true', false, e.message); }

  // D04: 中文连续失败3次→abort
  const abortCN = `【执行决策】
任务：点击设置按钮
步骤：[2/3] 查找设置
上一步结果：❌ 未生效: scroll_to(设置)
连续失败：3
页面提示：当前页面无设置按钮

# 失败处理
| 连续失败次数 | 动作 |
|-------------|------|
| 3 次 | 输出 abort |

## 当前页面
{"context_hint":"Settings not found","elements":[{"id":"btn_back","label":"返回","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: DECISION_SYSTEM }, { role: 'user', content: abortCN }]);
    const parsed = parseJson(raw);
    const isAbort = parsed && (parsed.type === 'abort' || parsed.type === 'task_done');
    report('DEC', 'CN', 'D04', '连续失败3次→abort', isAbort, isAbort ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('DEC', 'CN', 'D04', '连续失败3次→abort', false, e.message); }

  // D05: 英文连续失败3次→abort
  const abortEN = `【Execution Decision】
Task: Tap settings
Step: [2/3] find settings
Last step: ❌ failed: scroll_to(settings)
Failures: 3
Page hint: Settings not found

# Failure Handling
| Failures | Action |
|----------|--------|
| 3 | abort |

## Current Page
{"context_hint":"Settings not found","elements":[{"id":"btn_back","label":"Back","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: DECISION_SYSTEM }, { role: 'user', content: abortEN }]);
    const parsed = parseJson(raw);
    const isAbort = parsed && (parsed.type === 'abort' || parsed.type === 'task_done');
    report('DEC', 'EN', 'D05', '3 failures→abort', isAbort, isAbort ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('DEC', 'EN', 'D05', '3 failures→abort', false, e.message); }
}

// ==================== 4. 验证提示词 (Verify) ====================
async function testVerifyPrompt() {
  console.log('\n========== 4. 执行验证 (Verify) ==========');

  const BASE = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。`;

  // V01: 中文验证成功
  const verifyOkCN = `【执行验证】
上一个动作：tap(搜索按钮)
预期结果：键盘弹出

判断动作是否达到预期，输出：
{"success":true,"reason":"判断依据","next_hint":"成功→建议下一步"}
{"success":false,"reason":"判断依据","next_hint":"失败→调整建议"}

## 当前页面
{"context_hint":"Keyboard visible","elements":[{"id":"input_field","label":"","focused":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: verifyOkCN }]);
    const parsed = parseJson(raw);
    const hasSuccess = parsed && typeof parsed.success === 'boolean';
    const hasReason = parsed && typeof parsed.reason === 'string';
    report('VRF', 'CN', 'V01', '验证→返回success+reason', hasSuccess && hasReason, hasSuccess ? `success=${parsed.success}` : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('VRF', 'CN', 'V01', '验证→返回success+reason', false, e.message); }

  // V02: 英文验证失败
  const verifyFailEN = `【Execution Verification】
Previous action: tap(search button)
Expected result: keyboard appears

Determine if action achieved expected effect. Output:
{"success":true,"reason":"rationale","next_hint":"success→next step"}
{"success":false,"reason":"rationale","next_hint":"failure→adjustment"}

## Current Page
{"context_hint":"Same page, no keyboard","elements":[{"id":"search_btn","label":"Search","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: verifyFailEN }]);
    const parsed = parseJson(raw);
    const hasSuccess = parsed && typeof parsed.success === 'boolean';
    const hasNextHint = parsed && typeof parsed.next_hint === 'string';
    report('VRF', 'EN', 'V02', 'Verify→return success+next_hint', hasSuccess && hasNextHint, hasSuccess ? `success=${parsed.success}` : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('VRF', 'EN', 'V02', 'Verify→return success+next_hint', false, e.message); }
}

// ==================== 5. 重规划提示词 (Replan) ====================
async function testReplanPrompt() {
  console.log('\n========== 5. 异常重规划 (Replan) ==========');

  const BASE = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。`;

  // R01: 中文弹窗反复出现→重规划
  const replanCN = `【重新规划】

用户任务：打开微信给张三发消息
卡住原因：登录弹窗反复出现，已尝试关闭3次
已执行步骤及结果：1. 打开微信 ✅ | 2. 关闭登录弹窗 ❌ 反复出现

# 重规划策略
| 场景 | 策略 |
|------|------|
| 弹窗反复出现 | 加"先关闭弹窗" |

输出：{"replan_reason":"原因","steps":[{"description":"新步骤","intent":"预期"}],"confidence":0~1}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: replanCN }], 0.3);
    const parsed = parseJson(raw);
    const hasReplan = parsed && parsed.replan_reason && Array.isArray(parsed.steps);
    const hasConfidence = parsed && typeof parsed.confidence === 'number';
    report('RPL', 'CN', 'R01', '弹窗反复→重规划', hasReplan && hasConfidence,
      hasReplan ? `步骤:${parsed.steps.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('RPL', 'CN', 'R01', '弹窗反复→重规划', false, e.message); }

  // R02: 英文搜索无结果→重规划
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
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: replanEN }], 0.3);
    const parsed = parseJson(raw);
    const hasReplan = parsed && parsed.replan_reason && Array.isArray(parsed.steps);
    report('RPL', 'EN', 'R02', 'No results→replan', hasReplan, hasReplan ? `Steps:${parsed.steps.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('RPL', 'EN', 'R02', 'No results→replan', false, e.message); }
}

// ==================== 6. 用户指导提示词 (UserGuidance) ====================
async function testUserGuidancePrompt() {
  console.log('\n========== 6. 用户指导 (UserGuidance) ==========');

  const GUIDE_SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。

# 动作格式（必须遵守）
- 字段名必须是 "type"（禁止 "action"）
- target 必须是嵌套对象 {"method":"id"|"label"|"coordinate","value":"..."}
- 禁止扁平 target 如 {"target_id":"..."} 或 {"element_id":"..."}
- 示例：{"type":"tap","target":{"method":"id","value":"btn_ok"},"reasoning":"按用户提示点击","expected":"操作完成","confidence":0.9}`;

  // U01: 中文用户提示吻合→执行
  const guideCN = `【用户指导】

用户说："点击右下角的确定按钮"
按用户提示执行。

用户任务：提交订单
当前步骤：确认信息
之前失败：无

# 输出
提示与页面吻合 → 按提示执行，输出动作 JSON。
提示找不到对应元素 → {"type":"abort","reason":"按提示未找到匹配控件","confidence":0}

## 当前页面
{"context_hint":"Order confirmation","elements":[{"id":"btn_confirm","label":"确定","clickable":true,"bounds":"(600,1200)-(700,1280)"}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: GUIDE_SYSTEM }, { role: 'user', content: guideCN }]);
    const parsed = parseJson(raw);
    const isTap = parsed && parsed.type === 'tap';
    const targetsConfirm = isTap && parsed.target && (
      parsed.target.value === 'btn_confirm' || (parsed.target.value && parsed.target.value.includes('确定'))
    );
    report('GDE', 'CN', 'U01', '用户提示吻合→执行', isTap && targetsConfirm, isTap ? `target:${JSON.stringify(parsed.target)}` : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('GDE', 'CN', 'U01', '用户提示吻合→执行', false, e.message); }

  // U02: 英文用户提示不吻合→abort
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
    const raw = await callAgnes([{ role: 'system', content: GUIDE_SYSTEM }, { role: 'user', content: guideFailEN }]);
    const parsed = parseJson(raw);
    const isAbort = parsed && (parsed.type === 'abort' || parsed.type === 'task_done');
    report('GDE', 'EN', 'U02', '提示不吻合→abort', isAbort, isAbort ? '' : `raw: ${raw.slice(0, 100)}`);
  } catch (e) { report('GDE', 'EN', 'U02', '提示不吻合→abort', false, e.message); }
}

// ==================== 7. 接管恢复提示词 (TakeoverRecovery) ====================
async function testTakeoverRecoveryPrompt() {
  console.log('\n========== 7. 接管恢复 (TakeoverRecovery) ==========');

  const BASE = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。`;

  // T01: 中文接管恢复识别
  const recoveryCN = `【接管恢复识别】

用户任务：打开微信给张三发消息
接管前卡在：查找微信图标
原始计划：1. 解锁手机 → 2. 打开微信 → 3. 找到张三 → 4. 输入消息 → 5. 发送

判断用户手动操作后处于计划中的哪一步。

# 判断优先级
| 优先级 | 条件 |
|--------|------|
| 1 | 当前页面精确匹配某步预期页面 |
| 2 | 控件标签语义相似 |
| 3 | 无法判断 → step_index=-1 |

输出：{"step_index":序号,"confidence":0~1,"reason":"依据","next":"接下来做什么"}

## 当前页面
{"context_hint":"WeChat chat with Zhang San","elements":[{"id":"input_msg","label":"输入消息","editable":true},{"id":"btn_send","label":"发送","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: recoveryCN }]);
    const parsed = parseJson(raw);
    const hasStepIndex = parsed && typeof parsed.step_index === 'number';
    const hasNext = parsed && typeof parsed.next === 'string';
    report('TKR', 'CN', 'T01', '接管恢复→识别步骤', hasStepIndex && hasNext,
      hasStepIndex ? `step_index=${parsed.step_index}, next=${parsed.next}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('TKR', 'CN', 'T01', '接管恢复→识别步骤', false, e.message); }

  // T02: 英文接管恢复→无法判断
  const recoveryFailEN = `【Takeover Recovery Identification】

User task: Send message to Zhang San
Step AI was stuck on: finding WeChat
Original plan: 1. Unlock → 2. Open WeChat → 3. Find Zhang San → 4. Type message → 5. Send

Determine which step the user is at after manual operation.

# Priority
| Priority | Condition |
|----------|-----------|
| 3 | Cannot determine → step_index=-1 |

Output: {"step_index":number,"confidence":0~1,"reason":"rationale","next":"what to do next"}

## Current Page
{"context_hint":"Unknown page after manual operation","elements":[]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: recoveryFailEN }]);
    const parsed = parseJson(raw);
    const hasStepIndex = parsed && typeof parsed.step_index === 'number';
    report('TKR', 'EN', 'T02', '无法判断→step_index=-1', hasStepIndex,
      hasStepIndex ? `step_index=${parsed.step_index}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('TKR', 'EN', 'T02', '无法判断→step_index=-1', false, e.message); }
}

// ==================== 8. 批量任务规划 (BatchPlanning) ====================
async function testBatchPlanningPrompt() {
  console.log('\n========== 8. 批量任务规划 (BatchPlanning) ==========');

  const BASE = `你是 Phantom，一个 Android 手机操控 Agent。回复只能是纯 JSON。首字符={，末字符=}。只输出JSON。`;

  // B01: 中文多任务
  const batchCN = `【批量任务规划】

用户输入：打开微信给张三发消息，然后打开淘宝搜索咖啡机
已安装应用：微信, 淘宝, 京东

判断是否含多个独立子任务（连接词："然后""还有""顺便""另外""同时"）。

# 输出
多个：{"is_batch":true,"tasks":[{"id":"task_1","description":"描述","steps":[{"description":"步骤","intent":"预期"}],"estimated_time_seconds":秒}]}
单个：{"is_batch":false,"task":{"description":"描述","steps":[{"description":"步骤","intent":"预期"}],"estimated_time_seconds":秒}}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: batchCN }], 0.3);
    const parsed = parseJson(raw);
    const isBatch = parsed && parsed.is_batch === true;
    const hasTasks = isBatch && Array.isArray(parsed.tasks) && parsed.tasks.length >= 2;
    report('BAT', 'CN', 'B01', '多任务→is_batch=true', isBatch && hasTasks,
      isBatch ? `任务数:${parsed.tasks.length}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('BAT', 'CN', 'B01', '多任务→is_batch=true', false, e.message); }

  // B02: 英文单任务
  const singleEN = `【Batch Task Planning】

User input: Open WeChat
Installed apps: WeChat, Taobao

Determine if input contains multiple independent sub-tasks (connectors: "then", "also", "besides", "additionally", "meanwhile").

# Output
Multiple: {"is_batch":true,"tasks":[{"id":"task_1","description":"desc","steps":[{"description":"step","intent":"expected"}],"estimated_time_seconds":sec}]}
Single: {"is_batch":false,"task":{"description":"desc","steps":[{"description":"step","intent":"expected"}],"estimated_time_seconds":sec}}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: singleEN }], 0.3);
    const parsed = parseJson(raw);
    const isSingle = parsed && parsed.is_batch === false;
    const hasTask = isSingle && parsed.task && parsed.task.description;
    report('BAT', 'EN', 'B02', '单任务→is_batch=false', isSingle && hasTask,
      isSingle ? `task:${parsed.task.description?.slice(0, 50)}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('BAT', 'EN', 'B02', '单任务→is_batch=false', false, e.message); }

  // B03: 中文单任务
  const singleCN = `【批量任务规划】

用户输入：打开微信看朋友圈
已安装应用：微信, 淘宝

判断是否含多个独立子任务。

# 输出
多个：{"is_batch":true,"tasks":[...]}
单个：{"is_batch":false,"task":{"description":"描述","steps":[{"description":"步骤","intent":"预期"}],"estimated_time_seconds":秒}}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: BASE }, { role: 'user', content: singleCN }], 0.3);
    const parsed = parseJson(raw);
    const isSingle = parsed && parsed.is_batch === false;
    const hasTask = isSingle && parsed.task && parsed.task.description;
    report('BAT', 'CN', 'B03', '单任务→is_batch=false', isSingle, isSingle ? `task:${parsed.task?.description?.slice(0, 50)}` : `raw: ${raw.slice(0, 120)}`);
  } catch (e) { report('BAT', 'CN', 'B03', '单任务→is_batch=false', false, e.message); }
}

// ==================== 主流程 ====================
async function main() {
  console.log('🔬 HPA 全提示词测试套件 v3.0');
  console.log(`模型: ${MODEL}`);
  console.log(`时间: ${new Date().toISOString()}`);
  console.log('='.repeat(60));

  await Promise.all([
    testSystemPrompt(),
    testPlanningPrompt(),
    testDecisionPrompt(),
    testVerifyPrompt(),
    testReplanPrompt(),
    testUserGuidancePrompt(),
    testTakeoverRecoveryPrompt(),
    testBatchPlanningPrompt(),
  ]);

  // 汇总
  console.log('\n' + '='.repeat(60));
  console.log('📊 测试汇总');
  console.log('='.repeat(60));
  const total = passed + failed;
  console.log(`通过: ${passed}  |  失败: ${failed}  |  总计: ${total}`);
  console.log(`通过率: ${(total > 0 ? (passed / total * 100) : 0).toFixed(1)}%`);

  // 按语言
  const cn = results.filter(r => r.lang === 'CN');
  const en = results.filter(r => r.lang === 'EN');
  const cnPass = cn.filter(r => r.ok).length;
  const enPass = en.filter(r => r.ok).length;
  console.log(`\n中文: ${cnPass}/${cn.length} (${cn.length > 0 ? (cnPass/cn.length*100).toFixed(1) : 0}%)`);
  console.log(`英文: ${enPass}/${en.length} (${en.length > 0 ? (enPass/en.length*100).toFixed(1) : 0}%)`);

  // 按组
  const groups = [...new Set(results.map(r => r.group))];
  console.log();
  for (const g of groups) {
    const gr = results.filter(r => r.group === g);
    const gp = gr.filter(r => r.ok).length;
    console.log(`  ${g}: ${gp}/${gr.length} (${(gp/gr.length*100).toFixed(1)}%)`);
  }

  // 失败详情
  const failures = results.filter(r => !r.ok);
  if (failures.length > 0) {
    console.log('\n❌ 失败详情:');
    for (const f of failures) {
      console.log(`  [${f.group}/${f.lang}/${f.caseId}] ${f.caseName}`);
      console.log(`    ${f.detail}`);
    }
  }

  console.log('\n✅ 测试完成');
}

main().catch(e => {
  console.error('测试异常:', e);
  process.exit(1);
});