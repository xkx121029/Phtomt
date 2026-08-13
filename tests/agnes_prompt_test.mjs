/**
 * Agnes 2.5 Flash 提示词测试脚本
 * 测试 HPA 项目所有中英文提示词在 Agnes 模型上的表现
 * 
 * 用法: node tests/agnes_prompt_test.mjs
 */

const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

// ==================== 测试结果收集 ====================
const results = [];
let passed = 0, failed = 0;

function report(group, lang, caseId, caseName, ok, detail) {
  results.push({ group, lang, caseId, caseName, ok, detail });
  if (ok) passed++; else failed++;
  const icon = ok ? '✅' : '❌';
  console.log(`  ${icon} [${group}/${lang}/${caseId}] ${caseName}${ok ? '' : '\n      ' + detail}`);
}

// ==================== API 调用 ====================
async function callAgnes(messages, temperature = 0.1) {
  const url = `${API_BASE}/chat/completions`;
  const body = {
    model: MODEL,
    messages,
    temperature,
    max_tokens: 4096,
  };
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${API_KEY}` },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const err = await resp.text();
    throw new Error(`HTTP ${resp.status}: ${err}`);
  }
  const data = await resp.json();
  return data.choices?.[0]?.message?.content?.trim() || '';
}

function parseJson(text) {
  try {
    // 先尝试直接解析
    return JSON.parse(text);
  } catch {
    // 尝试提取 JSON 代码块
    const m = text.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (m) {
      try { return JSON.parse(m[1].trim()); } catch {}
    }
    // 尝试提取第一个 { 到最后一个 }
    const start = text.indexOf('{');
    const end = text.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try { return JSON.parse(text.substring(start, end + 1)); } catch {}
    }
    return null;
  }
}

// ==================== 1. 系统提示词测试 ====================
async function testSystemPrompt() {
  console.log('\n========== 1. 系统提示词 (System Prompt) ==========');

  const SYSTEM_CN = `你是 Phantom，一个 Android 手机操控 Agent。

# 铁律（违反任何一条 = 任务失败）
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
3. 禁止在 JSON 前后添加解释、问候、评论。
4. 拿不准做什么 → 输出 {"type":"task_done","reason":"原因"}。
5. 你是用户的手。不让用户操作手机。每步由你完成。
6. 每步只输出一个动作（除非满足合并条件）。

# 动作类型
| type | 必填字段 | 示例 |
|------|----------|------|
| tap | target | {"type":"tap","target":{"method":"id","value":"node_a3f2"},"reasoning":"点击搜索框","expected":"键盘弹出","confidence":0.9} |
| long_press | target,durationMs | {"type":"long_press","target":{"method":"label","value":"删除"},"durationMs":1500,"reasoning":"长按删除项","expected":"弹出菜单","confidence":0.8} |
| swipe | direction,distancePx | {"type":"swipe","direction":"up","distancePx":500,"reasoning":"向上滚动","expected":"显示更多内容","confidence":0.9} |
| type | target,text | {"type":"type","target":{"method":"id","value":"input_1"},"text":"关键词","reasoning":"输入搜索词","expected":"文字填入","confidence":0.95} |
| key | keycode | {"type":"key","keycode":"BACK","reasoning":"返回上页","expected":"返回成功","confidence":0.9} |
| wait | timeout_ms | {"type":"wait","timeout_ms":3000,"reasoning":"等待加载","expected":"加载完成","confidence":0.7} |
| launch | packageName | {"type":"launch","packageName":"com.example.app","reasoning":"启动应用","expected":"应用打开","confidence":0.95} |
| scroll_to | target | {"type":"scroll_to","target":{"method":"label","value":"设置"},"reasoning":"滚动到设置","expected":"设置项可见","confidence":0.85} |
| task_complete | summary | {"type":"task_complete","summary":"任务已完成","reasoning":"所有步骤执行完毕","confidence":1.0} |
| abort | reason | {"type":"abort","reason":"找不到目标控件","confidence":0.3} |

# 决策原则
1. 先处理意外（弹窗/权限/错误），再执行原计划。
2. 支付/删除/发送 → needs_user_confirmation = true。
3. 弹窗按钮优先级：允许 > 同意 > 确定 > 知道了 > 关闭 > 取消 > 以后再说 > 跳过。

# 禁止输出
❌ "好的，我来分析…" + JSON
❌ \`\`\`json ... \`\`\`
❌ 空字符串 / null
✅ 以 { 开头，以 } 结尾，中间纯 JSON。

只输出 JSON。`;

  const SYSTEM_EN = `You are Phantom, an Android device automation agent.

# Iron Rules (violation = task failure)
1. Response = pure JSON only. First char = {, last char = }.
2. NEVER output \`\`\`json, \`\`\`, or any Markdown markers.
3. NEVER add explanations, greetings, or commentary before/after JSON.
4. Unsure what to do → {"type":"task_done","reason":"state reason"}.
5. You are the user's hands. Never ask user to operate. Every step by you.
6. One action per step (unless merge conditions met).

# Action Types
| type | Required fields | Example |
|------|-----------------|---------|
| tap | target | {"type":"tap","target":{"method":"id","value":"node_a3f2"},"reasoning":"tap search","expected":"keyboard appears","confidence":0.9} |
| long_press | target,durationMs | {"type":"long_press","target":{"method":"label","value":"delete"},"durationMs":1500,"reasoning":"long press item","expected":"menu appears","confidence":0.8} |
| swipe | direction,distancePx | {"type":"swipe","direction":"up","distancePx":500,"reasoning":"scroll up","expected":"more content","confidence":0.9} |
| type | target,text | {"type":"type","target":{"method":"id","value":"input_1"},"text":"keyword","reasoning":"enter search term","expected":"text filled","confidence":0.95} |
| key | keycode | {"type":"key","keycode":"BACK","reasoning":"go back","expected":"previous page","confidence":0.9} |
| wait | timeout_ms | {"type":"wait","timeout_ms":3000,"reasoning":"wait for load","expected":"loading done","confidence":0.7} |
| launch | packageName | {"type":"launch","packageName":"com.example.app","reasoning":"launch app","expected":"app opens","confidence":0.95} |
| scroll_to | target | {"type":"scroll_to","target":{"method":"label","value":"Settings"},"reasoning":"scroll to settings","expected":"settings visible","confidence":0.85} |
| task_complete | summary | {"type":"task_complete","summary":"task done","reasoning":"all steps completed","confidence":1.0} |
| abort | reason | {"type":"abort","reason":"target not found","confidence":0.3} |

# Decision Principles
1. Handle unexpected (dialog/permission/error) before planned step.
2. Payment/deletion/send → needs_user_confirmation = true.
3. Dialog button priority: Allow > Agree > OK > Got it > Close > Cancel > Not now > Skip.

# Forbidden Output
❌ "Let me analyze..." + JSON
❌ \`\`\`json ... \`\`\`
❌ empty string / null
✅ Starts with {, ends with }, pure JSON.

Output ONLY JSON.`;

  // 测试：纯 JSON 输出 (CN)
  const msgCN = [
    { role: 'system', content: SYSTEM_CN },
    { role: 'user', content: 'Page snapshot: {"elements":[{"id":"btn","label":"搜索","clickable":true}]}\nTask: 点击搜索按钮。' }
  ];
  try {
    const raw = await callAgnes(msgCN);
    const parsed = parseJson(raw);
    const ok = parsed !== null && parsed.type === 'tap' && !raw.includes('```');
    report('SYS', 'CN', 'S01', '纯 JSON 输出（中文）', ok, ok ? '' : `解析失败或含 Markdown: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('SYS', 'CN', 'S01', '纯 JSON 输出（中文）', false, e.message);
  }

  // 测试：纯 JSON 输出 (EN)
  const msgEN = [
    { role: 'system', content: SYSTEM_EN },
    { role: 'user', content: 'Page snapshot: {"elements":[{"id":"btn","label":"Search","clickable":true}]}\nTask: Tap the search button.' }
  ];
  try {
    const raw = await callAgnes(msgEN);
    const parsed = parseJson(raw);
    const ok = parsed !== null && parsed.type === 'tap' && !raw.includes('```');
    report('SYS', 'EN', 'S02', 'Pure JSON output (English)', ok, ok ? '' : `Parse failed or has markdown: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('SYS', 'EN', 'S02', 'Pure JSON output (English)', false, e.message);
  }

  // 测试：字段名使用 "type" 而非 "action" (CN)
  try {
    const raw = await callAgnes(msgCN);
    const parsed = parseJson(raw);
    const hasType = parsed && parsed.type !== undefined;
    const hasAction = parsed && parsed.action !== undefined;
    report('SYS', 'CN', 'S03', '使用 type 字段（中文）', hasType, hasType ? '' : `使用 action 代替 type: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('SYS', 'CN', 'S03', '使用 type 字段（中文）', false, e.message);
  }

  // 测试：字段名使用 "type" 而非 "action" (EN)
  try {
    const raw = await callAgnes(msgEN);
    const parsed = parseJson(raw);
    const hasType = parsed && parsed.type !== undefined;
    report('SYS', 'EN', 'S04', 'Use "type" field (English)', hasType, hasType ? '' : `Uses "action" instead of "type": ${raw.slice(0, 100)}`);
  } catch (e) {
    report('SYS', 'EN', 'S04', 'Use "type" field (English)', false, e.message);
  }
}

// ==================== 2. 决策提示词测试 ====================
async function testDecisionPrompt() {
  console.log('\n========== 2. 每步决策 (Decision) ==========');

  const SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。
回复只能是纯 JSON。首字符 = {，末字符 = }。
禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
只输出 JSON。`;

  // 测试：倒计时广告 → wait (CN)
  const decisionCN = `【执行决策】

任务：打开首页
步骤：1/3 等待广告结束
上一步结果：无
连续失败：0
页面提示：⚠️ 疑似倒计时广告 - 5秒倒计时广告覆盖页面

# 输出
正常 → 单个动作 JSON。
只输出 JSON。禁止 \`\`\`json 标记，禁止 JSON 前后任何文字。

## 当前页面
{"context_hint":"⚠️ 疑似倒计时广告 - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"跳过 5","clickable":true}]}`;

  const msgCN = [
    { role: 'system', content: SYSTEM },
    { role: 'user', content: decisionCN }
  ];
  try {
    const raw = await callAgnes(msgCN);
    const parsed = parseJson(raw);
    const isWait = parsed && parsed.type === 'wait';
    report('DEC', 'CN', 'D01', '倒计时广告 → wait', isWait, isWait ? '' : `期望 type=wait, 实际: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('DEC', 'CN', 'D01', '倒计时广告 → wait', false, e.message);
  }

  // 测试：倒计时广告 → wait (EN)
  const decisionEN = `【Execution Decision】

Task: Open the home page
Step: 1/3 wait for ad to finish
Last step result: none
Consecutive failures: 0
Page hint: ⚠️ Countdown Ad - 5s countdown ad covering the page

# Output
Normal → single action JSON.
Output ONLY JSON. No \`\`\`json markers. No text before/after JSON.

## Current Page
{"context_hint":"⚠️ Countdown Ad - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true}]}`;

  const msgEN = [
    { role: 'system', content: SYSTEM },
    { role: 'user', content: decisionEN }
  ];
  try {
    const raw = await callAgnes(msgEN);
    const parsed = parseJson(raw);
    const isWait = parsed && parsed.type === 'wait';
    report('DEC', 'EN', 'D02', 'Countdown ad → wait', isWait, isWait ? '' : `Expected type=wait, got: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('DEC', 'EN', 'D02', 'Countdown ad → wait', false, e.message);
  }

  // 测试：支付确认 → needs_user_confirmation=true (CN)
  const payCN = `【执行决策】

任务：提交订单并完成支付
步骤：3/3 确认支付
上一步结果：✅ 已确认成功: tap(选择支付方式)
连续失败：0
页面提示：支付确认页

# 输出
正常 → 单个动作 JSON。
只输出 JSON。禁止 \`\`\`json 标记。

## 当前页面
{"context_hint":"Payment confirmation page","elements":[{"id":"btn_pay","label":"支付 99.0","clickable":true},{"id":"btn_cancel","label":"取消","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: payCN }]);
    const parsed = parseJson(raw);
    const hasConfirm = parsed && parsed.needs_user_confirmation === true;
    report('DEC', 'CN', 'D03', '支付确认 → needs_user_confirmation=true', hasConfirm, hasConfirm ? '' : `缺少 needs_user_confirmation: ${raw.slice(0, 120)}`);
  } catch (e) {
    report('DEC', 'CN', 'D03', '支付确认 → needs_user_confirmation=true', false, e.message);
  }

  // 测试：找不到控件 → abort (CN)
  const abortCN = `【执行决策】

任务：点击设置按钮
步骤：2/3 查找设置
上一步结果：❌ 未生效: scroll_to(设置)
连续失败：3
页面提示：当前页面无设置按钮

# 失败处理
| 连续失败次数 | 动作 |
|-------------|------|
| 1~2 次 | 换方式重试 |
| 3 次 | 输出 abort |

# 输出
只输出 JSON。

## 当前页面
{"context_hint":"Settings page not found","elements":[{"id":"btn_back","label":"返回","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: abortCN }]);
    const parsed = parseJson(raw);
    const isAbort = parsed && (parsed.type === 'abort' || parsed.type === 'task_done');
    report('DEC', 'CN', 'D04', '连续失败3次 → abort', isAbort, isAbort ? '' : `期望 abort, 实际: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('DEC', 'CN', 'D04', '连续失败3次 → abort', false, e.message);
  }
}

// ==================== 3. 规划提示词测试 ====================
async function testPlanningPrompt() {
  console.log('\n========== 3. 歧义检测 + 规划 (Planning) ==========');

  const SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。
回复只能是纯 JSON。首字符 = {，末字符 = }。
禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
只输出 JSON。`;

  // 无歧义任务 (CN)
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

只输出 JSON。首字符 = {，末字符 = }。`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: planCN }], 0.3);
    const parsed = parseJson(raw);
    const hasPlan = parsed && parsed.needs_clarification === false && parsed.plan && Array.isArray(parsed.plan.steps);
    const stepCount = hasPlan ? parsed.plan.steps.length : 0;
    const hasDescription = hasPlan && parsed.plan.steps[0] && parsed.plan.steps[0].description;
    report('PLN', 'CN', 'P01', '无歧义任务→返回计划', hasPlan && hasDescription, 
      hasPlan ? `步骤数: ${stepCount}` : `解析失败: ${raw.slice(0, 120)}`);
  } catch (e) {
    report('PLN', 'CN', 'P01', '无歧义任务→返回计划', false, e.message);
  }

  // 无歧义任务 (EN)
  const planEN = `【Mode: Ambiguity Detection + Task Planning】

User task: Open WeChat, send a message to Zhang San saying "let's have dinner together"
User preferences: none
Installed apps: unknown

# Planning Requirements
1. 5~10 atomic steps. Each step does one thing.
2. Each step: action description + expected result.
3. Strict sequential order. No skipping.

# Output Format
No ambiguity: {"needs_clarification":false,"plan":{"steps":[{"description":"specific action","intent":"expected result"}],"estimated_time_seconds":sec,"confidence":0~1}}
Ambiguity: {"needs_clarification":true,"clarification":{"question":"ask in user's voice","options":[{"id":"id","label":"title","description":"how it executes","is_default":bool}]}}

Output ONLY JSON. First char = {, last = }.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: planEN }], 0.3);
    const parsed = parseJson(raw);
    const hasPlan = parsed && parsed.needs_clarification === false && parsed.plan && Array.isArray(parsed.plan.steps);
    const stepCount = hasPlan ? parsed.plan.steps.length : 0;
    report('PLN', 'EN', 'P02', 'No ambiguity→return plan', hasPlan, 
      hasPlan ? `Steps: ${stepCount}` : `Parse failed: ${raw.slice(0, 120)}`);
  } catch (e) {
    report('PLN', 'EN', 'P02', 'No ambiguity→return plan', false, e.message);
  }

  // 有歧义任务 (CN)
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
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: ambigCN }], 0.3);
    const parsed = parseJson(raw);
    const hasAmbiguity = parsed && parsed.needs_clarification === true && parsed.clarification;
    const hasOptions = hasAmbiguity && Array.isArray(parsed.clarification.options) && parsed.clarification.options.length >= 2;
    const hasManual = hasOptions && parsed.clarification.options.some(o => o.id === 'manual');
    report('PLN', 'CN', 'P03', '模糊任务→检测歧义+选项', hasAmbiguity && hasOptions,
      hasAmbiguity ? `选项数: ${parsed.clarification.options.length}, 含manual: ${hasManual}` : `解析失败: ${raw.slice(0, 120)}`);
  } catch (e) {
    report('PLN', 'CN', 'P03', '模糊任务→检测歧义+选项', false, e.message);
  }
}

// ==================== 4. 寻址方式测试 ====================
async function testTargeting() {
  console.log('\n========== 4. 寻址方式 (Targeting) ==========');

  const SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。
回复只能是纯 JSON。首字符 = {，末字符 = }。
只输出 JSON。

# 寻址策略
| 条件 | method | value |
|------|--------|-------|
| 元素有 id | id | id 值 |
| 无 id 有 label | label | label 文字 |
| 目标不在元素树（图片/图表） | coordinate | "横比例,竖比例" |

禁止：有 id 时用 coordinate。`;

  // 中文：有 id → 用 id
  const targetingCN = `Page snapshot: {"context_hint":"Settings page","elements":[{"id":"node_settings","label":"Settings","clickable":true},{"id":"node_profile","label":"Profile","clickable":true}]}
Task: Tap the Settings entry.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: targetingCN }]);
    const parsed = parseJson(raw);
    const methodId = parsed && parsed.target && parsed.target.method === 'id';
    report('TGT', 'CN', 'T01', '有id→method=id', methodId, methodId ? '' : `method: ${parsed?.target?.method}, raw: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('TGT', 'CN', 'T01', '有id→method=id', false, e.message);
  }

  // 中文：无 id 有 label → 用 label
  const labelCN = `Page snapshot: {"context_hint":"Order page","elements":[{"id":"","label":"Confirm Order","clickable":true},{"id":"","label":"Cancel","clickable":true}]}
Task: Tap the Confirm Order button.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: labelCN }]);
    const parsed = parseJson(raw);
    const methodLabel = parsed && parsed.target && parsed.target.method === 'label';
    report('TGT', 'CN', 'T02', '无id有label→method=label', methodLabel, methodLabel ? '' : `method: ${parsed?.target?.method}, raw: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('TGT', 'CN', 'T02', '无id有label→method=label', false, e.message);
  }

  // 中文：screenshot → coordinate
  const screenshotCN = `Page snapshot: {"context_hint":"Screenshot only","source":"screenshot"}
A search box is at the top of the screen, a submit button at bottom-right.
Task: Tap the submit button.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: screenshotCN }]);
    const parsed = parseJson(raw);
    const methodCoord = parsed && parsed.target && parsed.target.method === 'coordinate';
    report('TGT', 'CN', 'T03', '截图→method=coordinate', methodCoord, methodCoord ? '' : `method: ${parsed?.target?.method}, raw: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('TGT', 'CN', 'T03', '截图→method=coordinate', false, e.message);
  }
}

// ==================== 5. 动作合并测试 ====================
async function testMerge() {
  console.log('\n========== 5. 动作合并 (Action Merge) ==========');

  const SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。
回复只能是纯 JSON。首字符 = { 或 [，末字符 = } 或 ]。
只输出 JSON。

# 动作合并（max 2）
条件（全部满足才可合并）：
- 页面来自元素树（accessibility）
- 第一个动作不跳转、不大幅改变页面结构

| 允许 | 禁止 |
|------|------|
| 输入+搜索 | 第一个动作跳转新页面 |
| 关闭弹窗+点击目标 | 第一个是 swipe |
| 短等待(≤2000ms)+点击 | 页面来自截图 |
| 输入+回车 | |`;

  // 合并：输入+搜索 (accessibility)
  const mergeCN = `Source: accessibility element tree.
Elements: [{"id":"search_input","label":"Search","focused":true,"clickable":false},{"id":"btn_submit","label":"Search","clickable":true}]
Task: Type "pizza" and search.`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: mergeCN }]);
    const isArray = raw.trim().startsWith('[');
    if (isArray) {
      const arr = parseJson(raw);
      const valid = Array.isArray(arr) && arr.length <= 2 && arr.every(a => a.type);
      report('MRG', 'CN', 'M01', '输入+搜索→返回数组', valid, valid ? `数组长度: ${arr.length}` : `数组格式错误: ${raw.slice(0, 100)}`);
    } else {
      report('MRG', 'CN', 'M01', '输入+搜索→返回数组', false, `未返回数组: ${raw.slice(0, 100)}`);
    }
  } catch (e) {
    report('MRG', 'CN', 'M01', '输入+搜索→返回数组', false, e.message);
  }
}

// ==================== 6. 验证提示词测试 ====================
async function testVerifyPrompt() {
  console.log('\n========== 6. 执行验证 (Verify) ==========');

  const SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。
回复只能是纯 JSON。首字符 = {，末字符 = }。
只输出 JSON。`;

  const verifyCN = `【执行验证】

上一个动作：tap(搜索按钮)
预期结果：键盘弹出

判断动作是否达到预期，输出：
{"success":true,"reason":"判断依据","next_hint":"成功→建议下一步"}
{"success":false,"reason":"判断依据","next_hint":"失败→调整建议"}

只输出 JSON。首字符 = {，末字符 = }。

## 当前页面
{"context_hint":"Keyboard visible on screen","elements":[{"id":"input_field","label":"","focused":true,"clickable":false}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: verifyCN }]);
    const parsed = parseJson(raw);
    const hasSuccess = parsed && (parsed.success === true || parsed.success === false);
    report('VRF', 'CN', 'V01', '验证→返回success字段', hasSuccess, hasSuccess ? '' : `解析失败: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('VRF', 'CN', 'V01', '验证→返回success字段', false, e.message);
  }
}

// ==================== 7. 完整应用场景测试 ====================
async function testRealScenarios() {
  console.log('\n========== 7. 完整应用场景 (Real Scenarios) ==========');

  const SYSTEM = `你是 Phantom，一个 Android 手机操控 Agent。
回复只能是纯 JSON。首字符 = {，末字符 = }。
禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
只输出 JSON。`;

  // 场景：微信聊天 (CN)
  const wechatCN = `【执行决策】

任务：打开微信，给张三发消息"晚上一起吃饭"
步骤：1/5 打开微信
上一步结果：无
连续失败：0
页面提示：桌面

# 输出
正常 → 单个动作 JSON。
只输出 JSON。禁止 \`\`\`json 标记，禁止 JSON 前后任何文字。

## 当前页面
{"context_hint":"Android home screen with app icons","elements":[{"id":"icon_wechat","label":"微信","clickable":true},{"id":"icon_alipay","label":"支付宝","clickable":true},{"id":"icon_settings","label":"设置","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: wechatCN }]);
    const parsed = parseJson(raw);
    const isLaunch = parsed && (parsed.type === 'tap' || parsed.type === 'launch');
    const targetWechat = parsed && parsed.target && (
      (parsed.target.value && parsed.target.value.includes('微信')) ||
      (parsed.target.label && parsed.target.label.includes('微信'))
    );
    report('REAL', 'CN', 'R01', '微信聊天→点击微信图标', isLaunch, 
      isLaunch ? `动作: ${parsed.type}, target: ${JSON.stringify(parsed.target)}` : `解析失败: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('REAL', 'CN', 'R01', '微信聊天→点击微信图标', false, e.message);
  }

  // 场景：弹窗处理 (CN)
  const dialogCN = `【执行决策】

任务：继续之前的操作
步骤：2/5 处理权限弹窗
上一步结果：⚠️ 已发送未确认
连续失败：0
页面提示：权限请求弹窗

# 输出
只输出 JSON。

## 当前页面
{"context_hint":"Permission dialog","elements":[{"id":"btn_allow","label":"允许","clickable":true},{"id":"btn_deny","label":"拒绝","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: dialogCN }]);
    const parsed = parseJson(raw);
    const tapAllow = parsed && parsed.type === 'tap' && parsed.target && (
      (parsed.target.value && parsed.target.value.includes('允许')) ||
      (parsed.target.value && parsed.target.value.includes('allow'))
    );
    report('REAL', 'CN', 'R02', '权限弹窗→点击允许', tapAllow, 
      tapAllow ? '' : `动作: ${parsed?.type}, target: ${JSON.stringify(parsed?.target)}, raw: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('REAL', 'CN', 'R02', '权限弹窗→点击允许', false, e.message);
  }

  // 场景：订单提交 (EN)
  const orderEN = `【Execution Decision】

Task: Complete the order
Step: 4/5 submit order
Last step result: ✅ verified success (item added to cart)
Consecutive failures: 0
Page hint: Cart page

# Output
Output ONLY JSON.

## Current Page
{"context_hint":"Cart page with items","elements":[{"id":"cart_item","label":"Item x1","clickable":false},{"id":"btn_checkout","label":"Checkout","clickable":true},{"id":"btn_add","label":"Add more","clickable":true}]}`;

  try {
    const raw = await callAgnes([{ role: 'system', content: SYSTEM }, { role: 'user', content: orderEN }]);
    const parsed = parseJson(raw);
    const tapCheckout = parsed && parsed.type === 'tap' && parsed.target && (
      parsed.target.value === 'btn_checkout' || 
      (parsed.target.value && parsed.target.value.toLowerCase().includes('checkout'))
    );
    report('REAL', 'EN', 'R03', 'Cart→tap checkout', tapCheckout, 
      tapCheckout ? '' : `动作: ${parsed?.type}, target: ${JSON.stringify(parsed?.target)}, raw: ${raw.slice(0, 100)}`);
  } catch (e) {
    report('REAL', 'EN', 'R03', 'Cart→tap checkout', false, e.message);
  }
}

// ==================== 主流程 ====================
async function main() {
  console.log('🔬 Agnes 2.5 Flash 提示词测试套件');
  console.log(`模型: ${MODEL}`);
  console.log(`时间: ${new Date().toISOString()}`);
  console.log('='.repeat(60));

  // 并发执行所有测试组
  await Promise.all([
    testSystemPrompt(),
    testDecisionPrompt(),
    testPlanningPrompt(),
    testTargeting(),
    testMerge(),
    testVerifyPrompt(),
    testRealScenarios(),
  ]);

  // 汇总报告
  console.log('\n' + '='.repeat(60));
  console.log('📊 测试汇总');
  console.log('='.repeat(60));
  console.log(`通过: ${passed}  |  失败: ${failed}  |  总计: ${passed + failed}`);
  console.log(`通过率: ${(passed / (passed + failed) * 100).toFixed(1)}%`);
  console.log();

  // 按语言分组统计
  const cnResults = results.filter(r => r.lang === 'CN');
  const enResults = results.filter(r => r.lang === 'EN');
  const cnPass = cnResults.filter(r => r.ok).length;
  const enPass = enResults.filter(r => r.ok).length;
  console.log(`中文测试: ${cnPass}/${cnResults.length} (${(cnPass/cnResults.length*100).toFixed(1)}%)`);
  console.log(`英文测试: ${enPass}/${enResults.length} (${(enPass/enResults.length*100).toFixed(1)}%)`);

  // 按组统计
  const groups = [...new Set(results.map(r => r.group))];
  console.log();
  for (const g of groups) {
    const gr = results.filter(r => r.group === g);
    const gp = gr.filter(r => r.ok).length;
    const gt = gr.length;
    console.log(`  ${g}: ${gp}/${gt} (${(gp/gt*100).toFixed(1)}%)`);
  }

  // 输出失败详情
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