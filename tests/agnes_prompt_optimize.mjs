/**
 * Agnes 2.5 Flash 优化提示词对比测试
 * 针对第一轮发现的 4 个核心问题设计优化方案：
 *   问题1: Agnes 输出 "action" 而非 "type" → 加入反例强化
 *   问题2: Agnes 输出扁平结构而非嵌套 target → 明确嵌套要求
 *   问题3: 倒计时广告不遵守 → 用"铁律"级别的约束
 *   问题4: 支付确认无 needs_user_confirmation → 明确要求
 */

const API_BASE = 'https://api.agnes-ai.cn/v1';
const API_KEY = 'sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq';
const MODEL = 'agnes-2.5-flash';

const results = [];
let passed = 0, failed = 0;

function report(group, ver, caseId, caseName, ok, detail) {
  results.push({ group, ver, caseId, caseName, ok, detail });
  if (ok) passed++; else failed++;
  const icon = ok ? '✅' : '❌';
  console.log(`  ${icon} [${group}/${ver}/${caseId}] ${caseName}${ok ? '' : '\n      ' + detail}`);
}

async function callAgnes(messages, temperature = 0.1) {
  const url = `${API_BASE}/chat/completions`;
  const body = { model: MODEL, messages, temperature, max_tokens: 4096 };
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

// ====== 优化版 V2 系统提示词 ======
// 核心改动：加入反例、强化字段名约束、嵌套 target 结构明确化
const SYSTEM_V2_CN = `你是 Phantom，一个 Android 手机操控 Agent。

# 铁律（违反任何一条 = 任务失败）
1. 回复只能是纯 JSON。首字符 = {，末字符 = }。
2. 禁止输出 \`\`\`json、\`\`\` 或任何 Markdown 标记。
3. 禁止在 JSON 前后添加解释、问候、评论。
4. 拿不准做什么 → 输出 {"type":"task_done","reason":"原因"}。
5. 你是用户的手。不让用户操作手机。每步由你完成。

# 动作类型（字段名必须为 "type"，禁止使用 "action"）
| type | 必填字段 | 示例 |
|------|----------|------|
| tap | target | {"type":"tap","target":{"method":"id","value":"node_a3f2"},"reasoning":"点击搜索框","expected":"键盘弹出","confidence":0.9} |
| long_press | target,durationMs | {"type":"long_press","target":{"method":"label","value":"删除"},"durationMs":1500,"reasoning":"长按删除项","expected":"弹出菜单","confidence":0.8} |
| swipe | direction,distancePx | {"type":"swipe","direction":"up","distancePx":500,"reasoning":"向上滚动","expected":"显示更多内容","confidence":0.9} |
| wait | timeout_ms | {"type":"wait","timeout_ms":3000,"reasoning":"等待加载","expected":"加载完成","confidence":0.7} |
| task_complete | summary | {"type":"task_complete","summary":"任务已完成","reasoning":"所有步骤执行完毕","confidence":1.0} |
| abort | reason | {"type":"abort","reason":"找不到目标控件","confidence":0.3} |

# target 结构（必须嵌套，禁止扁平化）
✅ 正确：{"target":{"method":"id","value":"node_abc"}}
❌ 错误：{"method":"id","value":"node_abc"}（缺少 target 外层）
❌ 错误：{"element_id":"node_abc"}（必须用 target.method / target.value）

# 倒计时广告（铁律级别）
context_hint 含【⚠️ 疑似倒计时广告】→ 必须输出 wait，禁止 tap。
原因：点击会误触底层元素。绝对禁止点击"跳过"。

# 决策原则
1. 先处理意外（弹窗/权限/错误），再执行原计划。
2. 支付/删除/发送 → 必须设置 "needs_user_confirmation": true。

# 错误输出示例（禁止模仿）
❌ {"action":"click","target":{"id":"..."}}  ← 字段名必须是 type，不是 action
❌ {"action":"tap","element_id":"..."}       ← 缺少 target 嵌套
❌ {"type":"tap","target":"btn_pay"}         ← target 必须是对象，不是字符串

# 正确输出示例
✅ {"type":"tap","target":{"method":"id","value":"btn_allow"},"reasoning":"点击允许","expected":"权限授予","confidence":0.95}
✅ {"type":"wait","timeout_ms":3000,"reasoning":"等待广告结束","expected":"广告消失","confidence":0.9}

只输出 JSON。`;

const SYSTEM_V2_EN = `You are Phantom, an Android device automation agent.

# Iron Rules (violation = task failure)
1. Response = pure JSON only. First char = {, last char = }.
2. NEVER output \`\`\`json, \`\`\`, or any Markdown markers.
3. NEVER add explanations, greetings, or commentary before/after JSON.
4. Unsure what to do → {"type":"task_done","reason":"state reason"}.
5. You are the user's hands. Never ask user to operate. Every step by you.

# Action Types (field name MUST be "type", NOT "action")
| type | Required fields | Example |
|------|-----------------|---------|
| tap | target | {"type":"tap","target":{"method":"id","value":"node_a3f2"},"reasoning":"tap search","expected":"keyboard appears","confidence":0.9} |
| long_press | target,durationMs | {"type":"long_press","target":{"method":"label","value":"delete"},"durationMs":1500,"reasoning":"long press","expected":"menu appears","confidence":0.8} |
| swipe | direction,distancePx | {"type":"swipe","direction":"up","distancePx":500,"reasoning":"scroll up","expected":"more content","confidence":0.9} |
| wait | timeout_ms | {"type":"wait","timeout_ms":3000,"reasoning":"wait for load","expected":"loading done","confidence":0.7} |
| task_complete | summary | {"type":"task_complete","summary":"task done","reasoning":"all steps completed","confidence":1.0} |
| abort | reason | {"type":"abort","reason":"target not found","confidence":0.3} |

# Target structure (MUST be nested, NEVER flat)
✅ Correct: {"target":{"method":"id","value":"node_abc"}}
❌ Wrong: {"method":"id","value":"node_abc"} (missing "target" wrapper)
❌ Wrong: {"element_id":"node_abc"} (must use target.method / target.value)

# Countdown Ads (Iron Rule level)
context_hint contains 【⚠️ Countdown Ad】→ MUST output wait type. NEVER tap.
Reason: clicking will hit the underlying element. ABSOLUTELY FORBIDDEN to tap "Skip".

# Decision Principles
1. Handle unexpected (dialog/permission/error) before planned step.
2. Payment/deletion/send → MUST set "needs_user_confirmation": true.

# Wrong Output Examples (DO NOT follow)
❌ {"action":"click","target":{"id":"..."}}  ← field name must be "type", not "action"
❌ {"action":"tap","element_id":"..."}       ← missing "target" wrapper
❌ {"type":"tap","target":"btn_pay"}         ← target must be object, not string

# Correct Output Examples
✅ {"type":"tap","target":{"method":"id","value":"btn_allow"},"reasoning":"tap allow","expected":"permission granted","confidence":0.95}
✅ {"type":"wait","timeout_ms":3000,"reasoning":"wait for ad","expected":"ad disappears","confidence":0.9}

Output ONLY JSON.`;

// ====== 测试用例 ======
async function runTests() {
  console.log('\n🔬 优化版 V2 提示词测试 (Agnes 2.5 Flash)');
  console.log('='.repeat(60));

  // ===== 测试1: 字段名 =====
  console.log('\n--- 1. 字段名: type vs action ---');
  
  for (const [lang, sys] of [['CN', SYSTEM_V2_CN], ['EN', SYSTEM_V2_EN]]) {
    const msg = [
      { role: 'system', content: sys },
      { role: 'user', content: lang === 'CN' 
        ? 'Page: {"elements":[{"id":"btn","label":"搜索","clickable":true}]}\nTask: 点击搜索按钮。'
        : 'Page: {"elements":[{"id":"btn","label":"Search","clickable":true}]}\nTask: Tap the search button.' }
    ];
    const raw = await callAgnes(msg);
    const parsed = parseJson(raw);
    const hasType = parsed && parsed.type !== undefined;
    const hasAction = parsed && parsed.action !== undefined;
    
    report('FIELD', lang, 'F01', '字段名必须是 type', hasType, 
      hasType ? `type="${parsed.type}"` : `使用 action 代替: ${raw.slice(0, 80)}`);
    
    if (parsed) {
      const hasNestedTarget = parsed.target && typeof parsed.target === 'object' && !Array.isArray(parsed.target);
      const flatTarget = (parsed.method || parsed.element_id) && !hasNestedTarget;
      report('FIELD', lang, 'F02', 'target 必须嵌套', hasNestedTarget,
        hasNestedTarget ? `target=${JSON.stringify(parsed.target)}` : `扁平结构: ${raw.slice(0, 80)}`);
    }
  }

  // ===== 测试2: 倒计时广告 =====
  console.log('\n--- 2. 倒计时广告 ---');
  
  for (const [lang, sys] of [['CN', SYSTEM_V2_CN], ['EN', SYSTEM_V2_EN]]) {
    const msg = [
      { role: 'system', content: sys },
      { role: 'user', content: lang === 'CN'
        ? '【执行决策】\n任务：打开首页\n步骤：1/3\n页面提示：⚠️ 疑似倒计时广告 - 5秒倒计时广告覆盖页面\n\n## 当前页面\n{"context_hint":"⚠️ 疑似倒计时广告 - 5秒倒计时广告覆盖页面","elements":[{"id":"btn_skip","label":"跳过 5","clickable":true}]}\n\n只输出 JSON。'
        : '【Execution Decision】\nTask: Open the home page\nStep: 1/3\nPage hint: ⚠️ Countdown Ad\n\n## Current Page\n{"context_hint":"⚠️ Countdown Ad - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true}]}\n\nOutput ONLY JSON.' }
    ];
    const raw = await callAgnes(msg);
    const parsed = parseJson(raw);
    const isWait = parsed && parsed.type === 'wait';
    report('AD', lang, 'A01', '倒计时广告→输出 wait', isWait,
      isWait ? '' : `输出: ${parsed?.type || '无法解析'}, raw: ${raw.slice(0, 80)}`);
  }

  // ===== 测试3: 支付确认 =====
  console.log('\n--- 3. 支付确认 needs_user_confirmation ---');
  
  for (const [lang, sys] of [['CN', SYSTEM_V2_CN], ['EN', SYSTEM_V2_EN]]) {
    const msg = [
      { role: 'system', content: sys },
      { role: 'user', content: lang === 'CN'
        ? '【执行决策】\n任务：提交订单并完成支付\n步骤：3/3\n\n## 当前页面\n{"context_hint":"支付确认页","elements":[{"id":"btn_pay","label":"支付 99.0","clickable":true}]}\n\n只输出 JSON。'
        : '【Execution Decision】\nTask: Submit the order and complete payment\nStep: 3/3\n\n## Current Page\n{"context_hint":"Payment confirmation page","elements":[{"id":"btn_pay","label":"Pay 99.0","clickable":true}]}\n\nOutput ONLY JSON.' }
    ];
    const raw = await callAgnes(msg);
    const parsed = parseJson(raw);
    const hasConfirm = parsed && parsed.needs_user_confirmation === true;
    const hasType = parsed && parsed.type !== undefined;
    report('PAY', lang, 'P01', '支付→type正确', hasType,
      hasType ? `type="${parsed.type}"` : `raw: ${raw.slice(0, 60)}`);
    report('PAY', lang, 'P02', '支付→needs_user_confirmation=true', hasConfirm,
      hasConfirm ? '' : `缺少确认字段, output: ${raw.slice(0, 80)}`);
  }

  // ===== 测试4: 连续失败3次 → abort =====
  console.log('\n--- 4. 连续失败 → abort ---');
  
  const abortCN = [
    { role: 'system', content: SYSTEM_V2_CN },
    { role: 'user', content: '【执行决策】\n任务：点击设置按钮\n步骤：2/3\n连续失败：3\n上一步结果：❌ 未生效\n\n# 失败处理\n| 连续失败次数 | 动作 |\n|-------------|------|\n| 3 次 | 输出 abort |\n\n## 当前页面\n{"context_hint":"设置按钮未找到","elements":[{"id":"btn_back","label":"返回","clickable":true}]}\n\n只输出 JSON。' }
  ];
  const rawCN = await callAgnes(abortCN);
  const parsedCN = parseJson(rawCN);
  const isAbortCN = parsedCN && (parsedCN.type === 'abort' || parsedCN.type === 'task_done');
  report('ABORT', 'CN', 'X01', '连续3次失败→输出 abort', isAbortCN,
    isAbortCN ? `type="${parsedCN.type}"` : `raw: ${rawCN.slice(0, 80)}`);

  // ===== 测试5: 目标寻址 =====
  console.log('\n--- 5. 目标寻址 ---');
  
  // 有 id → target.method=id
  const targetingMsg = [
    { role: 'system', content: SYSTEM_V2_CN },
    { role: 'user', content: '## 寻址策略\n| 条件 | method | value |\n|------|--------|-------|\n| 元素有 id | id | id 值 |\n| 无 id 有 label | label | label 文字 |\n\nPage: {"elements":[{"id":"node_settings","label":"设置","clickable":true}]}\nTask: 点击设置。\n\n只输出 JSON。' }
  ];
  const rawTgt = await callAgnes(targetingMsg);
  const parsedTgt = parseJson(rawTgt);
  const hasNestedTarget = parsedTgt && parsedTgt.target && typeof parsedTgt.target === 'object';
  const methodIsId = hasNestedTarget && parsedTgt.target.method === 'id';
  report('TGT', 'CN', 'T01', '有id→嵌套target.method=id', methodIsId,
    methodIsId ? '' : `target: ${JSON.stringify(parsedTgt?.target)}, raw: ${rawTgt.slice(0, 80)}`);

  // ===== 测试6: 规划 =====
  console.log('\n--- 6. 任务规划 ---');
  
  const planCN = [
    { role: 'system', content: SYSTEM_V2_CN },
    { role: 'user', content: '【模式：歧义检测 + 任务规划】\n\n用户任务：打开微信，给张三发消息说"晚上一起吃饭"\n\n# 规划要求\n1. 拆为 5~10 个原子步骤。\n\n# 输出格式\n无歧义：{"needs_clarification":false,"plan":{"steps":[{"description":"具体操作","intent":"预期结果"}],"estimated_time_seconds":秒,"confidence":0~1}}\n\n只输出 JSON。', temperature: 0.3 }
  ];
  const rawPlan = await callAgnes(planCN, 0.3);
  const parsedPlan = parseJson(rawPlan);
  const hasPlan = parsedPlan && parsedPlan.plan && Array.isArray(parsedPlan.plan.steps);
  report('PLAN', 'CN', 'L01', '规划→包含steps数组', hasPlan,
    hasPlan ? `${parsedPlan.plan.steps.length} 步` : `raw: ${rawPlan.slice(0, 100)}`);

  // ===== 汇总 =====
  console.log('\n' + '='.repeat(60));
  console.log('📊 优化版 V2 测试汇总');
  console.log('='.repeat(60));
  console.log(`通过: ${passed}  |  失败: ${failed}  |  总计: ${passed + failed}`);
  console.log(`通过率: ${(passed / (passed + failed) * 100).toFixed(1)}%`);
  
  const cnR = results.filter(r => r.lang === 'CN');
  const enR = results.filter(r => r.lang === 'EN');
  console.log(`中文: ${cnR.filter(r=>r.ok).length}/${cnR.length}`);
  console.log(`英文: ${enR.filter(r=>r.ok).length}/${enR.length}`);

  const failures = results.filter(r => !r.ok);
  if (failures.length > 0) {
    console.log('\n❌ 失败详情:');
    for (const f of failures) {
      console.log(`  [${f.group}/${f.ver}/${f.caseId}] ${f.caseName}`);
      console.log(`    ${f.detail}`);
    }
  }
  console.log();
}

runTests().catch(e => { console.error('异常:', e); process.exit(1); });