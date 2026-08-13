# agnes-2.5-flash 标准化测试结果

**版本**：v1.0
**日期**：2026-08-12
**模型**：agnes-2.5-flash（`https://api.agnes-ai.cn/v1`）
**提示词语言**：English
**测试方式**：电脑端脚本 `test_agnes.ps1` + 软件内「测试」页（预设方案）

---

## 一、测试环境

| 项目 | 值 |
|------|-----|
| Base URL | `https://api.agnes-ai.cn/v1` |
| Endpoint | `POST /v1/chat/completions` |
| 模型 | `agnes-2.5-flash` |
| temperature | 0.1 |
| 消息格式 | OpenAI 兼容 |

---

## 二、实测结果（英文提示词）

用符合《HPS ai标准化测试.md》字段（`action` / `target.method` / `confidence`）的英文提示词逐条调用 agnes，原始输出与判定如下：

### 决策逻辑（组 C）

| 用例 | 场景 | agnes 原始输出 | 判定 |
|------|------|---------------|------|
| C01 | 倒计时广告 | `{"action":"wait","target":{"method":"coordinate","coordinate":[500,500]},"confidence":0.9,"needs_user_confirmation":false}` | ✅ wait，且**未** tap 跳过 |
| C03 | 支付确认 | `{"action":"tap","target":{"method":"id","value":"btn_pay"},"confidence":0.9,"needs_user_confirmation":true}` | ✅ tap + `needs_user_confirmation=true` |

### 寻址方式（组 B）

| 用例 | 场景 | agnes 原始输出 | 判定 |
|------|------|---------------|------|
| B01 | accessibility + 有 id | `{"action":"tap","target":{"method":"id","value":"node_a1"},"confidence":0.95}` | ✅ method=id |
| B04 | screenshot | `{"action":"tap","target":{"method":"coordinate","x":900,"y":900},"confidence":0.85}` | ✅ method=coordinate |

### 动作合并（组 D）

| 用例 | 场景 | agnes 原始输出 | 判定 |
|------|------|---------------|------|
| D01 | 输入+搜索（accessibility） | `[{"action":"type","target":{"method":"id","id":"search_input"},"confidence":0.95},{"action":"tap",...}]` | ✅ 返回数组 |
| D04 | 截图来源 | `[{"action":"type","target":{"method":"coordinate","id":"search_box"}},"wait",500,{"action":"tap",...}]` | ❌ **应单对象，实际返回数组且结构异常** |

### 回归基准（组 E）

| 用例 | 场景 | agnes 原始输出 | 判定 |
|------|------|---------------|------|
| R09 | 任务完成 | `{"action":"task_complete","target":{"method":"id","id":"order_id"},"confidence":1.0}` | ✅ task_complete |

---

## 三、发现的问题

1. **字段命名**：agnes 输出倾向使用 `action` 字段（而非 phantom 的 `type`）。软件内测试引擎已兼容 `action` / `type` 两种字段，但 agent 运行时走 JSON Schema（`type`），使用 agnes 时应以测试引擎的 `action` 提示词为准。
2. **D04 合并规则不遵守**：`source=screenshot` 时 agnes 仍返回了 JSON 数组，且数组内混入裸字符串 `"wait"`、数字 `500`，**结构不合法**。这是最需要强化的点。
3. **整体质量**：倒计时广告、支付确认、寻址方式、任务完成等核心逻辑均正确，说明 agnes 对英文提示词遵循度良好。

---

## 四、针对 agnes 的提示词优化

### 4.1 优化方向

| 问题 | 优化手段 |
|------|---------|
| 字段命名 | 明确强制使用 `action` 字段名，避免 agnes 在 `type`/`action` 间摇摆 |
| D04 数组异常 | 强化「数组内每个元素必须是完整的 action 对象，禁止混入字符串/数字」 |
| D04 截图合并 | 强化「截图来源（source=screenshot）必须输出单个对象，禁止数组」 |
| 首动作跳转 | 强化「首动作会跳转新页面时禁止数组」 |
| JSON 纯净 | 保持「只输出单个 JSON，无 markdown、无解释」 |

### 4.2 优化后的英文 system 提示词（已内置到软件测试引擎）

```text
You are Phantom, an Android automation agent being regression-tested.
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
# Action merging (JSON array)
You may output a JSON ARRAY of at most 2 actions ONLY when BOTH hold:
1. page source is accessibility, AND
2. the first action will NOT navigate away or change page structure.
When you output an array, EVERY element MUST be a complete, valid action JSON object.
NEVER mix plain strings or numbers into the array.
NEVER use an array when source is screenshot.
NEVER use an array when the first action navigates to a new page.
# Output form
Prefer a single JSON object unless merging is required.
```

---

## 五、结论

- agnes-2.5-flash 对英文提示词遵循度整体良好，核心决策（倒计时广告、支付确认、寻址、任务完成）正确。
- 主要短板集中在**动作合并的数组边界**（截图来源、数组结构合法性），已通过强化提示词约束针对性优化。
- 优化后的提示词已集成到软件「测试」页的测试引擎，可随时重新跑各预设方案验证。

**文档版本**：v1.0
**创建日期**：2026-08-12