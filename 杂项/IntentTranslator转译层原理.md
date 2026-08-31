# IntentTranslator 转译层原理文档

> 版本：v2.2
> 对应模块：`com.phoneagent.execution.IntentTranslator`
> 关联文件：`AgentIntent.kt` / `AgentAction.kt` / `IntentResolver.kt` / `CapabilityManager.kt` / `ActionExecutor.kt`

---

## 一、设计目标

**核心原则：AI 只描述"做什么"，端侧负责"怎么做"。**

AI 输出意图（Intent），不输出可执行命令；端侧转译器（IntentTranslator）根据当前授权模式、页面快照、视觉定位结果，将意图转译为内部可执行的动作（Action），最终由 ActionExecutor 执行。

```
┌─────────────────────────────────────────────────────────────────────┐
│                        HPA 架构数据流                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   AI 决策                转译层 (Translator)              执行层     │
│  ┌──────────┐        ┌──────────────────────┐         ┌────────┐  │
│  │ AgentIntent│──────▶│ 1. 模式感知 (Mode)      │──Command─▶│ActionExc│ │
│  │ (意图 DSL) │        │ 2. 目标定位 (Resolver) │         │ utor   │  │
│  │            │        │ 3. 包名解析 (Resolver) │         └────────┘  │
│  └──────────┘        │ 4. 通道选择 (Mode)      │                     │
│                      └──────────────────────┘                     │
│                           │                                        │
│                           ▼                                        │
│                      AgentAction                                   │
│                    (内部动作命令)                                    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 二、三层组件职责

### 2.1 AgentIntent — AI 输出的意图 DSL

AI 在 JSON 中输出的意图结构，字段含义：

| 字段 | 说明 | 示例 |
|------|------|------|
| `intent` | 意图类型（见 IntentType 常量） | `"tap"` / `"open_app"` / `"input"` |
| `target` | 操作目标 { by, value } | `{by:"text", value:"发送"}` |
| `app` | 应用名或包名 | `"微信"` / `"com.tencent.mm"` |
| `text` | 输入文字 / 文档内容 | `"你好"` |
| `direction` | 滑动方向 | `"up"` / `"down"` |
| `wait_ms` | 等待时长（毫秒） | `2000` |
| `reasoning` | AI 思考过程 | `"用户要发消息，先打开微信"` |
| `expected` | 执行后预期结果 | `"微信首页出现"` |
| `confidence` | 决策置信度 0~1 | `0.95` |
| `page_fingerprint` | 页面指纹（回传验证） | `"main_page_v3"` |
| `needs_confirmation` | 是否需用户确认 | `false` |

**AI 禁止输出：**
- 像素坐标（x, y）
- shell 命令（如 `input tap 540 1200`）
- 具体执行通道名称

### 2.2 AgentAction — 端侧内部动作命令

转译层输出的动作结构，引擎和 ActionExecutor 直接消费：

| 字段 | 说明 | 来源 |
|------|------|------|
| `type` | 动作类型（ActionType 常量） | 由 intent 类型映射 |
| `x, y` | 目标像素坐标 | IntentResolver 计算 |
| `elementIndex` | 元素树索引 | 命中元素时回填 |
| `target` | 目标描述 {method, value} | 用于执行后定位验证 |
| `packageName` | 应用包名 | AppNameResolver 解析 |
| `command` | shell 命令（仅 SHIZUKU 模式） | 转译层生成 |
| `reason` | 用户可见的执行说明 | 来自 reasoning/reason |
| `reasoning` | AI 思考过程（不展示给用户） | 原样透传 |
| `expected` | 预期结果（用于验证对比） | 原样透传 |
| `pageFingerprint` | 页面指纹 | 原样透传 |

### 2.3 IntentResolver — 目标定位器

三级定位策略，把 AI 的"对什么操作"变成屏幕坐标：

```
                    ┌─ by=id ──→ 按 semanticId/viewId 精确查找
AI 目标描述 ───────┼─ by=text ──→ 按文字包含匹配（精确→模糊）
   {by, value}     │
                    └─ by=hint ──→ 视觉模型给出坐标（decision 阶段已算出）
                         │
                         ▼
                   像素坐标 (x, y)
                   + 命中元素 UiElement
```

**定位优先级：**
1. **id 精确匹配**：`semanticId` 或 `viewId` 后缀匹配
2. **text 包含匹配**：控件文字包含目标字符串（忽略大小写）
3. **视觉坐标**：`by=hint` 时，使用 decision 阶段视觉模型给出的坐标

**保留能力：** `by=coordinate` 是独占规则，允许 AI 在元素树无匹配且视觉定位失败时输出比例坐标（格式 `"0.5,0.2"`）或像素坐标（格式 `"540,1200"`）。

### 2.4 AppNameResolver — 应用名→包名解析器

运行时搜索已安装应用列表，无需预设映射表：

```kotlin
// 匹配优先级：
// 1. 包名直接透传（含 '.' 且非中文）
// 2. 应用标签精确匹配（忽略大小写）
// 3. 应用标签模糊包含匹配
appNameResolver.resolve("微信") → "com.tencent.mm"
appNameResolver.resolve("com.tencent.mm") → "com.tencent.mm"  // 直接返回
```

---

## 三、转译流程详解

### 3.1 主入口 `translate()`

```kotlin
fun translate(
    intent: AgentIntent,
    snapshot: ScreenSnapshot,        // 当前页面元素树 + 屏幕尺寸
    visualCoordinate: Pair<Int, Int>? = null,  // 视觉定位结果
): TranslationResult
```

**步骤：**

```
① 获取当前授权模式
   mode = capabilityManager.currentMode()
   → SHIZUKU（支持 shell 命令）
   → ACCESSIBILITY（无障碍服务）
   → READONLY（仅分析不执行）

② 确定动作基础类型
   baseType = IntentType.TO_ACTION[intent.intent] ?: intent.intent
   → 将意图类型映射到内部 ActionType

③ 构建公共字段 AgentAction
   common = AgentAction(
       type = baseType,
       reasoning = intent.reasoning,
       expected = intent.expected,
       confidence = intent.confidence,
       needsUserConfirmation = intent.needsConfirmation,
       pageFingerprint = intent.pageFingerprint,
   )

④ 按意图类型分发到具体转译函数

⑤ READONLY 过滤：拒绝非等待/文档写入类操作
```

### 3.2 各意图类型的转译规则

#### open_app — 打开应用

```
AI: { intent: "open_app", app: "微信" }
              │
              ▼
     AppNameResolver.resolve("微信")
              │
     ┌────────┴────────┐
     ▼                 ▼
   找到包名           未找到
     │                 │
     ▼                 ▼
 SHIZUKU: shell command    READONLY: Failed
 "launch com.tencent.mm"
     │
 ACCESSIBILITY: AgentAction(
     type="launch",
     packageName="com.tencent.mm"
 )
```

#### tap / long_press / input — 点击类操作

```
AI: { intent: "tap", target: {by:"text", value:"发送"} }
              │
              ▼
     IntentResolver.resolve(target, snapshot, visual)
              │
     ┌────────┼──────────────────────┐
     ▼        ▼                      ▼
  by=id    by=text               by=hint
   │         │                      │
   ▼         ▼                      ▼
 元素树    元素树                 视觉坐标
 精确匹配   包含匹配               (x,y)
   │         │                      │
   └─────────┴──────────────────────┘
                    │
                    ▼
           ResolvedTarget{element, x, y}
                    │
                    ▼
           AgentAction(
               type="tap" / "long_click" / "type"
               x, y, elementIndex, target
           )
```

**目标定位失败处理：** 返回 `TranslationResult.Failed("目标定位失败: by=..., value=...")`，引擎捕获后注入反馈给 AI，要求重试。

#### swipe — 滑动

```
AI: { intent: "swipe", direction: "up" }
              │
              ▼
     AgentAction(
         type="swipe",
         direction="up",
         distancePx=null  // 默认全屏距离
     )
```

方向 → 端点映射（屏幕中心为起点）：
- `up`: `(cx, cy) → (cx, cy-600)`
- `down`: `(cx, cy) → (cx, cy+600)`
- `left`: `(cx, cy) → (cx-600, cy)`
- `right`: `(cx, cy) → (cx+600, cy)`

#### press — 按键

```
AI: { intent: "press", key: "BACK" }
              │
              ▼
     normalizeKey("BACK") → "BACK"
              │
              ▼
     AgentAction(type="key", keycode="BACK")
              │
              ▼
     ActionExecutor.back() / home() / recents()
```

按键归一化映射：
| AI 输出 | 归一化结果 |
|---------|-----------|
| `"BACK"` | `"BACK"` |
| `"HOME"` | `"HOME"` |
| `"ENTER"` / `"OK"` / `"CONFIRM"` | `"ENTER"` |
| `"RECENTS"` / `"RECENT"` / `"APP_SWITCH"` | `"RECENTS"` |

#### scroll_to — 滚动查找

```
AI: { intent: "scroll_to", target: {by:"text", value:"搜索结果"} }
              │
              ▼
     IntentResolver.resolve(target, snapshot, null)  // scroll_to 不依赖坐标
              │
              ▼
     判断滚动方向：
     - value 含 "上方" → 向上滚
     - value 含 "下方" → 向下滚
     - 其他 → 默认向上滚
              │
              ▼
     AgentAction(
         type="scroll",
         text="搜索结果",
         direction="up"
     )
```

**注意：** scroll_to 不要求目标已可见，通过滚动搜索目标区域。

#### wait — 等待

```
AI: { intent: "wait", wait_ms: 2000 }
              │
              ▼
     AgentAction(type="wait", timeoutMs=2000)
```

#### open — 深链直达

```
AI: { intent: "open", uri: "weixin://", app: "微信", page: 1 }
              │
              ▼
     AgentAction(
         type="open",
         uri="weixin://",
         app="微信",
         page=1
     )
```

#### finish / give_up — 任务结束

```
AI: { intent: "finish", summary: "已完成转账" }
              │
              ▼
     AgentAction(type="task_done", summary="已完成转账")
```

---

## 四、授权模式与通道选择

| 模式 | 条件 | 影响范围 |
|------|------|---------|
| `SHIZUKU` | Shizuku 服务可用 | open_app → shell 命令（`launch pkg`）；其余同 ACCESSIBILITY |
| `ACCESSIBILITY` | 无障碍服务已授权 | 全部通过无障碍 API 执行（手势/按键/文本输入） |
| `READONLY` | 上述两个都不可用 | 除 `wait` 和 `write_doc` 外，所有动作被拒绝并返回 Failed |

**转译层对 READONLY 的过滤逻辑：**
```kotlin
if (mode == Mode.READONLY &&
    action.type != ActionType.WAIT &&
    action.type != ActionType.WRITE_DOC
) {
    return TranslationResult.Failed("当前为只读模式，无法自动执行...")
}
```

---

## 五、数据流转完整示例

以"打开微信"任务为例：

```
┌─ AI 输出 ──────────────────────────────────────────────────────┐
│  {                                                           │
│    "intent": "open_app",                                     │
│    "app": "微信",                                             │
│    "reasoning": "用户要发消息，先打开微信",                    │
│    "expected": "微信聊天列表页出现",                          │
│    "confidence": 0.95,                                        │
│    "page_fingerprint": "home_screen_v1"                      │
│  }                                                           │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─ 转译层 ──────────────────────────────────────────────────────┐
│  1. mode = ACCESSIBILITY                                     │
│  2. baseType = IntentType.TO_ACTION["open_app"] = "launch"   │
│  3. pkg = AppNameResolver.resolve("微信") = "com.tencent.mm" │
│  4. → AgentAction(                                           │
│       type="launch",                                         │
│       packageName="com.tencent.mm",                          │
│       reasoning="用户要发消息，先打开微信",                    │
│       expected="微信聊天列表页出现",                          │
│       confidence=0.95                                        │
│     )                                                        │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─ 引擎执行 ────────────────────────────────────────────────────┐
│  executor.launchApp("com.tencent.mm")                         │
│  → PackageManager 查找 LAUNCHER activity                      │
│  → startActivity(intent)                                     │
│  → 验证：checkAppLaunched("com.tencent.mm")                   │
│  → 800ms 后返回 VerifyResult(true, "已启动应用: ...")          │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─ AI 下一轮 ───────────────────────────────────────────────────┐
│  观察新页面 → 发现微信已打开 → 继续决策下一步操作              │
└──────────────────────────────────────────────────────────────┘
```

---

## 六、字段命名规范

### AI 输出字段（JSON 序列化用 @SerialName）

| AI 字段名 | Kotlin 属性 | 说明 |
|-----------|------------|------|
| `intent` | `intent` | 意图类型 |
| `target` | `target` | 目标描述 |
| `app` | `app` | 应用名 |
| `text` | `text` | 输入文字 |
| `direction` | `direction` | 方向 |
| `wait_ms` | `waitMs` | 等待时长 |
| `duration_ms` | `durationMs` | 长按/操作时长 |
| `reasoning` | `reasoning` | AI 思考过程 |
| `expected` | `expected` | 预期结果 |
| `page_fingerprint` | `pageFingerprint` | 页面指纹 |
| `needs_confirmation` | `needsConfirmation` | 是否需确认 |

### 引擎内部字段（AgentAction）

| 字段 | 来源 |
|------|------|
| `type` | IntentType.TO_ACTION 映射 |
| `x, y` | IntentResolver 计算 |
| `elementIndex` | 命中元素索引 |
| `packageName` | AppNameResolver 解析 |
| `command` | SHIZUKU 模式下生成 |
| `reason` | intent.reasoning（用户可见） |
| `reasoning` | intent.reasoning（调试追溯） |
| `expected` | intent.expected（验证对比） |
| `pageFingerprint` | intent.pageFingerprint（验证用） |

---

## 七、意图 → 动作类型映射表

| IntentType | → ActionType | 执行通道 |
|------------|-------------|---------|
| `open_app` | `launch`（无障碍）/ `shell`（Shizuku） | ActionExecutor.launchApp() |
| `open` | `open` | ActionExecutor.openUri() |
| `tap` | `tap` | ActionExecutor.click() |
| `long_press` | `long_click` | ActionExecutor.longClick() |
| `input` | `type` | ActionExecutor.typeText() |
| `swipe` | `swipe` | ActionExecutor.swipe() |
| `press` | `key` | ActionExecutor.back/home/recents() |
| `wait` | `wait` | 协程 delay() |
| `scroll_to` | `scroll` | ActionExecutor.scroll() |
| `write_doc` | `write_doc` | 写入工作区文件 |
| `finish` | `task_done` | 结束任务 |
| `give_up` | `task_done` | 结束任务（标记放弃） |

---

## 八、错误处理策略

| 错误类型 | 转译层行为 | 引擎行为 |
|---------|-----------|---------|
| 未知意图 | `Failed("未知意图：xxx")` | 注入反馈，AI 重试 |
| 目标定位失败 | `Failed("目标定位失败: by=xxx, value=xxx")` | 注入反馈，AI 重新描述目标 |
| 应用名解析失败 | `Failed("无法解析应用「xxx」的包名")` | 注入反馈，提示 AI 使用包名 |
| READONLY 拒绝 | `Failed("当前为只读模式...")` | 告知用户需授权 |
| 执行失败（验证不通过）| 不适用 | 重试（最多 3 次），超过则 AI 重新决策 |
