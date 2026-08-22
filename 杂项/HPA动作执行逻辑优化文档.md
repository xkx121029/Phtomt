这个思路是对的，而且是从根上解决问题——让 AI 做它擅长的事（判断"该干什么"），把 AI 不擅长的事（"怎么干"）全部下放给端侧。下面是完整的设计文档。

---

# Phantom 意图-转译架构设计文档

**版本**：v2.1
**日期**：2026-08-22
**核心变更**：AI 不再输出可执行命令，只输出"意图"。端侧根据当前授权模式把意图转译为具体命令。

---

## 一、现在的问题

v2.0 的设计里，AI 承担了三件事：

1. **判断该做什么**（决策——AI 擅长的）
2. **判断用什么通道做**（无障碍还是 Shizuku——AI 不擅长，也不该它管）
3. **生成对应通道的命令格式**（`input tap x y` vs `performAction`——AI 很容易错）

第 2、3 件事是纯粹的负担。它导致：

- Prompt 里塞满了"寻址策略""通道选择""动作格式"这些技术规则
- AI 需要理解 Shizuku 和无障碍的区别，但它其实不该关心
- 用户切换授权模式（关掉 Shizuku）时，AI 的输出可能失效
- 同一个动作，AI 在不同通道下要写不同的格式，出错率高

**根因：把"执行细节"泄露到了"决策层"。**

---

## 二、核心思想：意图与执行分离

```
改造前（AI 输出可执行命令）：

  AI 决策 ──► 判断通道 ──► 生成命令 ──► 端侧直接执行
   "该点搜索框"  无障碍还是Shizuku?  "input tap 540 120"   照做
                 (AI不该管这个)     (AI容易写错)


改造后（AI 只输出意图）：

  AI 决策 ──► 输出意图 ──► 端侧转译 ──► 执行
   "该点搜索框"   "tap 搜索框"   根据授权模式选通道    无障碍: performAction
                                定位目标+算坐标       Shizuku: input tap
                                                     只读: 提示用户
```

一句话：**AI 说"做什么"，端侧决定"怎么做"。**

---

## 三、意图 DSL——AI 的新输出格式

### 3.1 九种意图

| 意图 | 含义 | 必带字段 | 可选字段 |
|------|------|----------|----------|
| `open_app` | 打开应用 | `app`（应用名，如"美团"） | — |
| `tap` | 点击 | `target` | — |
| `input` | 输入文字 | `target` + `text` | — |
| `swipe` | 滑动 | `direction` | — |
| `press` | 按键 | `key` | — |
| `wait` | 等待 | `wait_ms` | — |
| `scroll_to` | 滚动查找 | `target` | — |
| `finish` | 任务完成 | `summary` | — |
| `give_up` | 放弃 | `reason` | — |

### 3.2 目标定位（target）

AI 描述"对什么操作"，用三种方式之一：

```json
{
  "by": "id",          // 方式一：控件 id（页面数据里有，最精确）
  "value": "node_search"
}

{
  "by": "text",        // 方式二：控件文字（精确匹配）
  "value": "搜索"
}

{
  "by": "hint",        // 方式三：语义描述（截图模式或文字匹配不到时）
  "value": "页面右上角的搜索图标"
}
```

**关键规则：AI 永不输出坐标。** 坐标是端侧定位到目标后自己算出来的。AI 只需要说清"要点哪个控件"，不需要知道它在屏幕的哪个像素位置。

### 3.3 完整示例

```json
{
  "intent": "open_app",
  "app": "美团",
  "reasoning": "打开美团开始点餐",
  "expected": "美团首页",
  "confidence": 0.95
}
```

```json
{
  "intent": "tap",
  "target": {"by": "id", "value": "node_search"},
  "reasoning": "点击搜索框",
  "expected": "搜索框获得焦点",
  "confidence": 0.95
}
```

```json
{
  "intent": "input",
  "target": {"by": "id", "value": "node_search"},
  "text": "黄焖鸡米饭",
  "reasoning": "输入搜索关键词",
  "expected": "搜索框显示文字",
  "confidence": 0.95
}
```

```json
{
  "intent": "tap",
  "target": {"by": "text", "value": "搜索"},
  "reasoning": "点击搜索按钮",
  "expected": "显示搜索结果",
  "confidence": 0.95,
  "needs_confirmation": false
}
```

---

## 四、转译层——端侧新增的核心模块

### 4.1 模块位置

```
core/execution/
├── IntentTranslator.kt          # 新增：意图 → 命令转译
├── IntentResolver.kt            # 新增：目标定位（把 target 变成实际控件/坐标）
├── ActionRouter.kt              # 保留：但改为接收"转译后的命令"而非"动作"
├── ShizukuActionExecutor.kt     # 保留：命令执行器
└── AccessibilityActionExecutor.kt # 保留：命令执行器
```

### 4.2 转译流程

```
AI 输出意图（Intent）
      │
      ▼
┌─────────────────────┐
│  IntentTranslator    │
│                     │
│  1. 读取当前授权模式  │
│  2. 解析意图类型      │
│  3. 定位目标（如需要）│
│  4. 生成通道命令      │
│  5. 交给执行器        │
└─────────┬───────────┘
          │
          ▼
    CommandExecutor（具体通道）
```

```kotlin
class IntentTranslator(
    private val capabilityManager: CapabilityManager,  // 当前授权模式
    private val intentResolver: IntentResolver,        // 目标定位
    private val shizukuExecutor: ShizukuActionExecutor,
    private val accessibilityExecutor: AccessibilityActionExecutor
) {
    suspend fun translateAndExecute(intent: AgentIntent): ExecutionResult {
        // 1. 当前授权模式
        val mode = capabilityManager.currentMode()  // SHIZUKU / ACCESSIBILITY / READONLY

        // 2. 如果是需要定位目标的意图，先定位
        val resolvedTarget = when (intent.intent) {
            "tap", "input", "scroll_to" -> intentResolver.resolve(intent.target, mode)
            else -> null
        }

        // 3. 定位失败 → 直接返回失败，不执行
        if (intent.target != null && resolvedTarget == null) {
            return ExecutionResult(false, "目标定位失败: ${intent.target.by}=${intent.target.value}", 0)
        }

        // 4. 按模式转译并执行
        return when (mode) {
            CapabilityManager.Mode.SHIZUKU ->
                shizukuExecutor.execute(translateToShizuku(intent, resolvedTarget))
            CapabilityManager.Mode.ACCESSIBILITY ->
                accessibilityExecutor.execute(translateToAccessibility(intent, resolvedTarget))
            CapabilityManager.Mode.READONLY ->
                ExecutionResult(false, "当前为只读模式，无法自动执行", 0)
        }
    }
}
```

---

## 五、各授权模式的转译矩阵

同一意图在不同模式下转译成不同命令。AI 对此完全无感知。

### 5.1 Shizuku 模式

| 意图 | 转译命令 | 定位方式 |
|------|----------|----------|
| `open_app` | `monkey -p <包名> -c android.intent.category.LAUNCHER 1` | 应用名 → 包名（端侧查已安装应用列表） |
| `tap` (by_id) | `input tap <x> <y>` | 控件树查 id → 取节点中心坐标 |
| `tap` (by_text) | `input tap <x> <y>` | 控件树匹配文字 → 取节点中心坐标 |
| `tap` (by_hint) | `input tap <x> <y>` | 截图 + 云端视觉定位 → 返回坐标 |
| `input` | `cmd clipboard set "<text>"` + `input keyevent 279` | 先聚焦目标输入框 |
| `swipe` | `input swipe <x1> <y1> <x2> <y2> 300` | 屏幕中心点按方向算坐标 |
| `press` (back) | `input keyevent 4` | — |
| `press` (home) | `input keyevent 3` | — |
| `press` (enter) | `input keyevent 66` | — |
| `wait` | `sleep <ms>` 或协程 delay | — |
| `scroll_to` | 循环：`input swipe` + 读屏，直到找到目标 | 每次滑动后检查文字 |

### 5.2 无障碍模式

| 意图 | 转译命令 | 定位方式 |
|------|----------|----------|
| `open_app` | `startActivity(intent)` | 应用名 → 包名 → Intent |
| `tap` (by_id) | `performAction(ACTION_CLICK)` | 控件树查 id → 直接对节点操作 |
| `tap` (by_text) | 模糊匹配节点 → `performAction(ACTION_CLICK)` | 遍历控件树匹配文字 |
| `tap` (by_hint) | `dispatchGesture(坐标)` | 截图 + 云端视觉定位 → 坐标 → 手势 |
| `input` | 聚焦节点 → `ACTION_SET_TEXT` | 查 id 或文字找到输入框节点 |
| `swipe` | `dispatchGesture(Path)` | 按方向生成手势路径 |
| `press` (back) | `performGlobalAction(GLOBAL_ACTION_BACK)` | — |
| `press` (home) | `performGlobalAction(GLOBAL_ACTION_HOME)` | — |
| `wait` | 协程 delay | — |
| `scroll_to` | 循环：dispatchGesture + 读屏 | 每次滑动后检查文字 |

### 5.3 只读模式（无障碍 + Shizuku 都不可用）

| 意图 | 处理 |
|------|------|
| 所有需要执行的动作 | 不执行。悬浮窗显示："AI 建议：{intent 的中文描述}"，由用户手动操作 |

只读模式下 AI 依然在工作（分析页面、给出建议），只是手变成了用户自己。

---

## 六、目标定位策略（IntentResolver）

这是转译层的核心难点：把 AI 的"文字描述目标"变成"屏幕上的具体控件/坐标"。

### 6.1 三级定位

```
第一级：by_id → 控件树精确查找
    → 找到：直接用节点
    → 找不到：降级到第二级

第二级：by_text → 控件树文字匹配（精确 → 包含）
    → 找到：直接用节点
    → 找不到：降级到第三级

第三级：by_hint / 前两级都失败 → 视觉定位
    → 截图 + 云端视觉模型 → 返回坐标
    → 只有 Shizuku 或无障碍手势可用时才有意义
```

### 6.2 实现

```kotlin
class IntentResolver(
    private val screenReader: ScreenReader,
    private val cloudAgent: CloudAgent
) {
    data class ResolvedTarget(
        val nodeId: String?,       // 无障碍模式下用
        val nodeBounds: List<Float>?, // 节点 bounds（比例坐标）
        val pixelX: Int?,          // 最终像素坐标（Shizuku 模式用）
        val pixelY: Int?
    )

    suspend fun resolve(
        target: AgentIntentTarget,
        mode: CapabilityManager.Mode
    ): ResolvedTarget? {
        // 1. 读当前页面
        val page = screenReader.captureCurrentPage()

        // 第一级：by_id
        if (target.by == "id") {
            val node = findNodeById(page, target.value)
            if (node != null) return buildResolvedTarget(node, mode)
        }

        // 第二级：by_text（by=id 找不到时也走这里）
        if (target.by == "text" || target.by == "id") {
            val node = findNodeByText(page, target.value)
            if (node != null) return buildResolvedTarget(node, mode)
        }

        // 第三级：视觉定位（截图 + 云端）
        if (mode != CapabilityManager.Mode.READONLY) {
            val coordinate = cloudAgent.visualLocate(
                screenshot = screenReader.captureScreenshot(),
                targetDescription = target.value,
                pageContext = page.page.contextHint
            )
            if (coordinate != null) {
                return ResolvedTarget(
                    nodeId = null,
                    pixelX = coordinate.x,
                    pixelY = coordinate.y
                )
            }
        }

        // 全部失败
        return null
    }

    private fun buildResolvedTarget(node: ElementNode, mode: CapabilityManager.Mode): ResolvedTarget {
        return if (mode == CapabilityManager.Mode.ACCESSIBILITY) {
            // 无障碍模式：直接用节点 id
            ResolvedTarget(nodeId = node.id, nodeBounds = node.boundsRatio, pixelX = null, pixelY = null)
        } else {
            // Shizuku 模式：算中心点像素坐标
            val centerX = (node.boundsRatio[0] + node.boundsRatio[2]) / 2f
            val centerY = (node.boundsRatio[1] + node.boundsRatio[3]) / 2f
            ResolvedTarget(
                nodeId = node.id,
                nodeBounds = node.boundsRatio,
                pixelX = (centerX * screenWidth).toInt(),
                pixelY = (centerY * screenHeight).toInt()
            )
        }
    }
}
```

### 6.3 应用名 → 包名的映射

AI 输出 `app: "美团"`，端侧负责转成包名：

```kotlin
class AppNameResolver(context: Context) {
    // 端侧维护一个常用应用映射表 + 动态查询已安装应用
    private val knownApps = mapOf(
        "美团" to "com.sankuai.meituan",
        "微信" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "饿了么" to "me.ele",
        "设置" to "com.android.settings"
    )

    fun resolve(appName: String): String? {
        // 1. 先查映射表
        knownApps[appName]?.let { return it }

        // 2. 再查已安装应用列表（按应用名匹配）
        return queryInstalledApps().firstOrNull { it.appName == appName }?.packageName
    }
}
```

---

## 七、失败处理与闭环

意图转译失败（定位不到目标）时，不能静默吞掉。

```
转译/执行失败
    │
    ▼
返回 ExecutionResult(success=false, detail="目标定位失败: by=text, value=搜索")
    │
    ▼
TaskOrchestrator 记录失败
    │
    ├── 第 1 次失败 → 重新读屏 → 让 AI 重新决策（页面可能变了）
    ├── 第 2 次失败 → 重新读屏 → 让 AI 换一个目标描述
    └── 第 3 次失败 → 进入异常协作面板（用户接管/指导/重试）
```

AI 收到"定位失败"的反馈后，可以换一种描述方式（比如从 by_text 换成 by_hint，或者换一个目标）。

---

## 八、截图模式的特殊处理

截图模式下没有控件树，AI 输出的 `by_id` 和 `by_text` 都无法端侧定位。处理方式：

```
截图模式 + AI 输出 tap(by_text="搜索")
    │
    ▼
端侧无法定位
    │
    ▼
转译层调用"视觉定位"接口：
    发送：截图 + "搜索"这个文字目标
    ↓
    云端视觉模型返回："搜索按钮在 (540, 120)"
    │
    ▼
Shizuku input tap 540 120
```

**注意**：视觉定位和"AI 决策"是两次不同的调用。决策时 AI 只输出意图（不用看截图定位），定位时视觉模型只负责找目标（不做决策）。职责分离后，两个 Prompt 都能大幅简化。

---

## 九、简化后的 Prompt

AI 的 Prompt 大幅瘦身。以下是核心变化：

### 9.1 删掉的内容

- ❌ 寻址策略（id/label/coordinate 的规则）—— AI 不再管坐标
- ❌ 执行通道说明（Shizuku / 无障碍）—— AI 完全不知道这些
- ❌ 坐标格式说明（比例坐标、像素坐标）—— AI 不输出坐标
- ❌ 十种动作的详细命令格式——变成九种意图的简单格式

### 9.2 新增的内容

- ✅ 九种意图的定义（简单格式）
- ✅ 目标描述规则（by_id / by_text / by_hint）

### 9.3 简化后的系统 Prompt 核心段落

```
你是 Phantom，一个 Android 手机操控 Agent。
输出纯 JSON，首字符 {，末字符 }。

# 你输出的是"意图"，不是命令

你只需要决定"做什么"和"对什么做"，不需要关心"怎么做"。
具体执行方式由手机端根据当前权限自动处理。

# 九种意图

1. open_app  — 打开应用。格式: {"intent":"open_app","app":"应用名"}
2. tap       — 点击。格式: {"intent":"tap","target":{...}}
3. input     — 输入文字。格式: {"intent":"input","target":{...},"text":"内容"}
4. swipe     — 滑动。格式: {"intent":"swipe","direction":"up|down|left|right"}
5. press     — 按键。格式: {"intent":"press","key":"back|home|enter"}
6. wait      — 等待。格式: {"intent":"wait","wait_ms":2500}
7. scroll_to — 滚动查找。格式: {"intent":"scroll_to","target":{...}}
8. finish    — 任务完成。格式: {"intent":"finish","summary":"结果"}
9. give_up   — 放弃。格式: {"intent":"give_up","reason":"原因"}

# 目标描述（tap/input/scroll_to 需要）

三种方式，按优先级选择：

1. by_id：页面数据里目标控件有 id → {"by":"id","value":"控件id"}
2. by_text：控件上有明确文字 → {"by":"text","value":"文字"}
3. by_hint：没有 id 也没有文字，或前两种可能找不到 → {"by":"hint","value":"语义描述，如：右上角的搜索图标"}

你永远不需要输出坐标。坐标由手机端定位后自动计算。

# 页面数据

（保持不变——你还是需要看页面来决定"该点什么"）

# 决策原则

（保持不变）

只输出 JSON。
```

---

## 十、改动影响清单

| 模块 | 改动 |
|------|------|
| 数据模型 | 新增 `AgentIntent`、`AgentIntentTarget`；`AgentAction` 改为内部使用或废弃 |
| 执行层 | 新增 `IntentTranslator`、`IntentResolver`、`AppNameResolver`；`ActionRouter` 改为接收转译后的命令 |
| 云端接口 | 新增"视觉定位"接口（截图 + 文字目标 → 坐标） |
| Prompt | 全部重写为意图格式；删除寻址策略、通道选择、坐标相关规则 |
| 验证层 | 不变（还是靠页面指纹验证动作是否生效） |
| 端侧决策引擎 | 不变（弹窗/加载/异常分类仍走端侧，只是它的本地动作也改成意图格式） |
| 异常协作 | 不变（接管/指导/重试逻辑与意图格式无关） |
| 任务录制/回放 | 受益——录制的就是意图序列，回放时按当前模式重新转译，天然跨设备 |
| 回归测试 | 全部测试用例的期望输出从"命令"改为"意图" |

---

## 十一、ToDo 清单

- [ ] **1.** 定义 `AgentIntent` 和 `AgentIntentTarget` 数据模型
- [ ] **2.** 实现 `CapabilityManager`（检测并缓存当前授权模式：SHIZUKU / ACCESSIBILITY / READONLY）
- [ ] **3.** 实现 `IntentResolver`（三级定位：id → text → 视觉定位）
- [ ] **4.** 实现 `AppNameResolver`（应用名 → 包名映射 + 动态查询）
- [ ] **5.** 实现 `IntentTranslator`（意图 → 命令转译主逻辑）
- [ ] **6.** 改造 `ShizukuActionExecutor`（接收转译后的命令，命令构造逻辑从 AI 移到这里）
- [ ] **7.** 改造 `AccessibilityActionExecutor`（同上）
- [ ] **8.** 改造 `ActionRouter`（改为接收 `ResolvedTarget` + 意图，不再接收 AI 的命令）
- [ ] **9.** 实现云端"视觉定位"接口（截图 + 文字目标 → 坐标，独立于决策 Prompt）
- [ ] **10.** 重写全部 Prompt 为意图格式（系统/决策/规划/验证/重规划/用户指导/接管恢复）
- [ ] **11.** 端侧决策引擎的本地动作改为意图格式输出
- [ ] **12.** 更新全部回归测试用例（期望输出从命令改为意图）
- [ ] **13.** 单元测试：IntentTranslator 在三种模式下的转译正确性
- [ ] **14.** 单元测试：IntentResolver 三级定位的降级逻辑
- [ ] **15.** 端到端验证：同一意图在 Shizuku 模式和无障碍模式下都能正确执行

---

**文档版本**：v2.1
**创建日期**：2026-08-22

这个重构的核心收益是三句话：**AI 的 Prompt 砍掉三分之一的技术规则，出错的面积同步缩小；授权模式的变化（开关 Shizuku）对 AI 完全透明；同一份意图可以被无障碍、Shizuku、只读三种模式各自转译，天然支持降级和跨设备回放。**

*内容由 AI 生成仅供参考*