# Phantom v2.2.1 调试与反馈系统文档

**版本**：v2.2.1
**日期**：2026-08-22
**基于**：v2.2 完整架构文档
**增量内容**：任务过程可视化 + 原始/翻译双语展示 + 诊断报告 + 主动反馈

---

## 一、v2.2.1 定位

v2.2 解决了"AI 怎么干活"（意图转译、脚本执行、分层规划、模板库、策略热切换）。v2.2.1 解决下一个问题：**用户和开发者怎么看懂 AI 在干什么。**

核心矛盾：系统里已经产生了海量数据（意图 JSON、执行日志、截图、Prompt、检查点），但普通用户看不懂技术术语，开发者又需要原始信息定位问题。

v2.2.1 的答案就一句话：**每个数据点同时保留"人话翻译"和"原始信息"，两者并存、可对照、可切换。**

---

## 二、设计原则

| 原则 | 说明 |
|------|------|
| 原始是真相源 | 翻译可能失真，原始信息永远保留，一个字节不丢 |
| 翻译是理解入口 | 默认先看人话，看不懂再展开原始 |
| 两者一一对应 | 每段翻译都能追溯到原始数据 |
| 分层展示 | 普通用户看人话，开发者看原始，可随时切换 |

信息分层：

| 层级 | 用户 | 内容 |
|------|------|------|
| L0 悬浮窗/通知栏 | 所有用户 | 极简进度 |
| L1 调试页-人话模式 | 普通用户 | 翻译后的执行过程 |
| L2 调试页-原始模式 | 开发者 | 完整技术数据 |
| L3 诊断报告导出 | 开发者/反馈 | 人话 + 原始分区 |

---

## 三、三种并存展示模式

### 3.1 模式 A：上下折叠（默认）

人话可见，原始折叠在下方，点开即看。

```
┌─────────────────────────────────────┐
│ 👆 AI 决定：点击"第一个店铺"          │
│     理由：评分最高，符合"选最好的"     │
│     把握：94%                        │
│                                     │
│  ▾ 查看原始信息                      │
│  ┌─────────────────────────────┐   │
│  │ {"intent":"tap",            │   │
│  │  "target":{"by":"hint",     │   │
│  │  "value":"第一个店铺卡片"},   │   │
│  │  "confidence":0.94}         │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

**适用**：AI 返回、发送给 AI 的请求、单条执行日志。

### 3.2 模式 B：并排对照（双栏）

```
┌──────────────────────┬──────────────────────┐
│  人话                 │  原始信息             │
├──────────────────────┼──────────────────────┤
│ AI 看到：搜索页        │ page_type: search_page│
│ 屏幕有 47 个控件       │ elements: 47         │
│ 结果：✅ 成功          │ fingerprint: a3f→b92 │
└──────────────────────┴──────────────────────┘
```

**适用**：能力状态、实时感知、页面变化对比。

### 3.3 模式 C：全局标签页切换

```
┌─────────────────────────────────────┐
│  运行状态      [人话▾]               │
│                ├─ 人话模式（默认）    │
│                ├─ 原始模式           │
│                └─ 对照模式（并排）    │
└─────────────────────────────────────┘
```

---

## 四、任务过程可视化

调试页新增"任务时间线"作为主视图，把技术数据翻译成叙事。

### 4.1 任务时间线

```
🎯 帮我点早餐            ⏱ 总共 45 秒
│
├─ ✅ 第1步 打开美团              （2.1秒）
│      AI 打开了美团 App
│
├─ ✅ 第2步 点击搜索框            （1.8秒）
│      AI 在页面顶部找到了搜索框
│
├─ ⚠️ 第3步 输入"早餐"           （8.5秒）★ 这里遇到了问题
│      AI 尝试输入，第一次没找到输入框
│      AI 自动换了个方法，重试成功
│      [展开看 AI 是怎么解决的]
│
├─ ✅ 第4步 点击搜索              （1.5秒）
│
└─ ✅ 第5步 选择评分最高的店      （3.2秒）
       任务完成 🎉
```

节点状态配色：绿✅成功 / 红❌失败 / 黄⚠️遇到问题但解决 / 灰⏳未执行 / 蓝🔄进行中。

### 4.2 步骤卡片（四段式叙事）

每步归并成一张卡片，固定四段：**看到 → 决定 → 做了 → 结果**。

```
┌─────────────────────────────────────┐
│ 第 3 步 · 点击"第一个店铺"           │
│                                     │
│ ✅ 已完成 · 用时 1.2 秒              │
│                                     │
│ AI 看到了：搜索结果页（3 家店）       │
│ AI 决定了：点击"老王家黄焖鸡"        │
│ AI 做了：点击屏幕 (540, 820)         │
│ 结果：页面跳转到店铺详情 ✅           │
│                                     │
│ [看 AI 的原始对话] [看这一步截图]     │
└─────────────────────────────────────┘
```

### 4.3 AI 第一人称思考实时翻译

执行中把 `reasoning` + `expected` + 结果组合成第一人称：

```
💭 AI 正在想：
"我看到搜索框了，先点它才能输入文字。"

💭 AI 遇到了问题：
"奇怪，输入框好像没反应。我换个方式试试。"
```

### 4.4 截图标注

在截图上用高亮标出 AI 执行的位置：

```
┌─────────────────────────────────────┐
│  [截图]                             │
│    ⭕ ← 高亮圆圈 + "AI 点击了这里"    │
│  步骤 3/5 · 结果 ✅ 成功             │
│  [执行前] [执行后] 对比              │
└─────────────────────────────────────┘
```

标注配色：绿=成功 / 红=失败 / 黄=重试。原始无标注截图保留可切换。

### 4.5 页面变化对比

```
步骤 3 的页面变化
[执行前截图] → [执行后截图]（可拖动对比）
AI 预期：搜索框获得焦点，键盘弹出
实际结果：✅ 键盘弹出了，AI 判断正确
```

### 4.6 置信度可视化

```
第1步 打开美团      ██████████ 95% 很确定
第2步 点击搜索框    ████████░░ 82% 较确定
第3步 输入文字      ██████░░░░ 61% 不太确定 ← 果然出问题了
```

低置信度步骤自动高亮，用户能预判哪步容易出问题。

### 4.7 执行摘要卡片（任务完成后）

```
┌─────────────────────────────────────┐
│ 🎉 任务完成                         │
│ 帮你点好了早餐                      │
│ 老王家黄焖鸡米饭 · 大份 · 微辣      │
│ 预计 25 分钟送达                    │
│                                     │
│ ── 这次执行 ──                      │
│ 5 步全部完成，1 步自动重试           │
│ 总共 45 秒，AI 问了你 0 次          │
│ 省了 3 次云端调用（用了你的常点）    │
│ [看完整过程] [再点一次]              │
└─────────────────────────────────────┘
```

### 4.8 异常可读化

技术错误映射成人话 + 建议动作：

```
⚠️ AI 卡住了
卡在哪：第 3 步，想输入"早餐"
问题：AI 在页面上找不到输入框

AI 已经试过：
  1. 按文字找输入框 → 没找到
  2. 截图识别 → 没找到

你可以：
  👆 帮 AI 点一下输入框
  💬 告诉 AI 输入框在哪
  🔄 让 AI 再试一次
```

---

## 五、翻译映射实现

### 5.1 HumanTranslator（端侧硬编码映射）

人话翻译不靠 AI 生成（避免额外云端调用和不确定性），端侧维护映射表：

```kotlin
object HumanTranslator {
    // intent → 人话动词
    private val intentMap = mapOf(
        "open_app" to "打开",
        "tap" to "点击",
        "input" to "输入",
        "swipe" to "滑动",
        "press" to "按下",
        "wait" to "等待",
        "scroll_to" to "滚动查找",
        "finish" to "完成任务",
        "give_up" to "放弃任务"
    )

    // 异常技术描述 → 人话
    private val errorMap = mapOf(
        "目标定位失败" to "AI 在页面上找不到要操作的东西",
        "视觉定位超时" to "AI 截图识别时网络或服务超时了",
        "页面无变化" to "AI 点了但页面没反应",
        "云端响应超时" to "AI 大脑（云端）暂时联系不上",
        "执行器不可用" to "当前无法自动操作手机（权限问题）"
    )

    // page_type → 人话
    private val pageTypeMap = mapOf(
        "search_page" to "搜索页",
        "search_result_list" to "搜索结果列表",
        "product_detail" to "商品详情页",
        "checkout" to "结算页",
        "dialog_overlay" to "弹窗",
        "loading" to "加载中页面",
        "generic" to "普通页面"
    )

    fun translateIntent(intent: AgentIntent): String {
        val verb = intentMap[intent.intent] ?: intent.intent
        val targetDesc = when (intent.target?.by) {
            "id" -> "控件 ${intent.target.value}"
            "text" -> "文字为「${intent.target.value}」的控件"
            "hint" -> intent.target.value
            else -> ""
        }
        return "$verb $targetDesc"
    }

    fun translateError(technical: String): String {
        errorMap.forEach { (key, human) ->
            if (technical.contains(key)) return human
        }
        return technical  // 无映射则原样返回
    }
}
```

### 5.2 DisplayItem（原始永不丢弃）

```kotlin
data class DisplayItem(
    val humanText: String,          // 翻译后人话（默认展示）
    val rawData: Any?,              // 原始对象（完整保留）
    val rawText: String?,           // 原始序列化文本（展示和导出用）
    val displayMode: DisplayMode    // COLLAPSED / SIDE_BY_SIDE / TAB
)
```

**原则**：翻译是"覆盖在真相之上的一层解释"，不替代真相。原始 JSON 永远在折叠层里，一个字段不少。

---

## 六、调试页完整结构

开发者模式七个面板，每个面板都应用"人话 + 原始"并存：

### 面板一：能力状态
- 无障碍服务（启用状态、存活时长、自愈次数）
- Shizuku（连接状态、版本、延迟）
- 截图权限、当前执行通道、降级链

### 面板二：实时感知
- 最近读屏摘要（人话）+ 完整控件树（原始，可展开）
- page_id / page_type / source / fingerprint

### 面板三：执行日志
- 步骤卡片（人话四段式）+ 完整日志（原始）
- 每条含：意图原文、定位结果、转译命令、执行耗时、验证结果、指纹变化

### 面板四：云端交互
- 每次调用的 Prompt 类型、temperature、token、延迟（人话摘要）
- 完整 Prompt 和完整响应（原始，可查看、可导出）

### 面板五：任务与检查点
- 任务规模、执行策略、步骤/阶段进度
- 检查点保存时间、恢复可用性、工作记忆键值对

### 面板六：记忆与模板
- 模板匹配结果、相似度、健康状态、节省的调用次数
- 异常记忆命中、用户画像注入情况

### 面板七：系统日志
- 流式日志 + 过滤（模块/级别/关键字）
- 搜索同时匹配人话和原始文本

---

## 七、诊断报告导出

```kotlin
data class DiagnosticReport(
    val reportTime: Long,
    val appVersion: String,
    val deviceInfo: DeviceInfo,
    val capabilitySnapshot: CapabilitySnapshot,
    val currentTask: TaskSnapshot?,
    val recentExecutionLogs: List<ExecutionLog>,
    val recentCloudCalls: List<CloudCallLog>,
    val recentSystemLogs: List<String>,
    val memoryStats: MemoryStats,
    val exceptionHistory: List<ExceptionRecord>
)
```

报告分两区：

- **人话摘要区**（用户看）：任务执行过程叙事、遇到的问题、解决方式
- **原始数据区**（开发者看）：完整 JSON、日志、堆栈

隐私处理：导出前自动脱敏（手机号/密码/身份证），默认不含完整页面截图和控件文字，仅含控件 id 和类型。开发者可开启"包含完整页面数据"。

---

## 八、主动反馈

调试页是被动的，还需一层主动反馈：

| 场景 | 主动反馈形式 |
|------|-------------|
| 无障碍服务被杀 | 悬浮窗变灰 + 通知"已断开，点击恢复" |
| 云端连续超时 | 悬浮窗提示"网络异常，已暂停任务" |
| 任务 5 分钟无进展 | 通知 + 异常协作面板 |
| 模板连续 3 次失败 | 设置里标记"已失效，建议重新生成" |
| 端侧决策连续 5 次 | 日志标记"可能死循环"，通知用户 |
| 检查点恢复成功 | 悬浮窗"已从断点恢复" |

通知栏和主动反馈**只发人话**，不发技术术语。

---

## 九、实现基础设施

### 9.1 LogCollector（统一日志收口）

```kotlin
object LogCollector {
    private val buffer = ArrayDeque<LogEntry>(MAX = 500)

    data class LogEntry(
        val time: Long,
        val level: String,
        val module: String,
        val message: String
    )

    fun log(level: String, module: String, message: String) {
        synchronized(buffer) {
            if (buffer.size >= 500) buffer.removeFirst()
            buffer.addLast(LogEntry(System.currentTimeMillis(), level, module, message))
        }
        // 同时保留原 Log
    }

    fun recent(module: String? = null, level: String? = null): List<LogEntry>
    fun export(): String
}
```

### 9.2 DebugState（StateFlow 驱动 UI）

```kotlin
object DebugState {
    val capability = MutableStateFlow<CapabilitySnapshot?>(null)
    val currentPage = MutableStateFlow<PageProtocol?>(null)
    val executionLogs = MutableStateFlow<List<ExecutionLog>>(emptyList())
    val cloudCalls = MutableStateFlow<List<CloudCallLog>>(emptyList())
    val taskState = MutableStateFlow<TaskSnapshot?>(null)
    val templateMatch = MutableStateFlow<TemplateMatchResult?>(null)
    val systemLogs = MutableStateFlow<List<LogEntry>>(emptyList())
}
```

调试页不轮询，各面板订阅对应 StateFlow，打开即最新。

---

## 十、ToDo 清单

### 基础设施
- [ ] **1.** 实现 `LogCollector`（环形缓冲 500 条）
- [ ] **2.** 实现 `CloudCallRecorder`（请求/响应快照）
- [ ] **3.** 实现 `DebugState`（各面板 StateFlow）
- [ ] **4.** 各模块关键节点接入 LogCollector 和 DebugState

### 翻译层
- [ ] **5.** 实现 `HumanTranslator`（intent/error/page_type 映射表）
- [ ] **6.** 定义 `DisplayItem`（humanText + rawData + displayMode）
- [ ] **7.** 实现全局三模式切换（人话/原始/对照）

### 调试页 UI
- [ ] **8.** 调试页入口（悬浮球长按 + 设置页）
- [ ] **9.** 用户模式视图（系统健康 + 任务进度 + 最近问题）
- [ ] **10.** 开发者模式七个面板
- [ ] **11.** 模式 A 上下折叠组件
- [ ] **12.** 模式 B 并排对照组件

### 任务过程可视化
- [ ] **13.** 任务时间线（步骤卡片 + 颜色状态 + 可展开节点）
- [ ] **14.** "AI 看到了什么"页面摘要生成
- [ ] **15.** "AI 决定做什么"翻译（intent/reasoning/expected/confidence）
- [ ] **16.** AI 第一人称思考实时组合
- [ ] **17.** 截图标注（记录动作坐标 → 绘制高亮）
- [ ] **18.** 执行前后截图对比（拖动对比）
- [ ] **19.** 置信度可视化（颜色条 + 低置信高亮）
- [ ] **20.** 执行摘要卡片
- [ ] **21.** 异常文案映射 + 建议动作

### 诊断报告
- [ ] **22.** 实现 `DiagnosticReport` 数据模型
- [ ] **23.** 报告分两区导出（人话 + 原始）
- [ ] **24.** 导出自动脱敏

### 主动反馈
- [ ] **25.** 无障碍被杀 / 云端超时 / 任务卡死 / 模板失效的主动通知
- [ ] **26.** 检查点恢复成功提示

### 测试
- [ ] **27.** LogCollector 环形缓冲正确性
- [ ] **28.** 翻译映射表覆盖率（所有 intent/error/page_type 有映射）
- [ ] **29.** 原始信息完整性（展开后 JSON 与原始对象一致）
- [ ] **30.** 搜索同时匹配人话和原始文本
- [ ] **31.** 调试页打开不影响任务执行（性能验证）

---

**文档版本**：v2.2.1
**创建日期**：2026-08-22

v2.2.1 在 v2.2 的任务执行架构之上，补上了"被看见"这一环。核心是两条线：**任务过程可视化**把技术日志翻译成"看到→决定→做了→结果"的叙事，让普通用户第一次真正看懂 AI 在干什么；**原始+翻译并存**保证开发者随时能翻到真相源，定位问题一个字段不丢。翻译负责理解，原始负责真相，两者永远是并存而非替代的关系。

*内容由 AI 生成仅供参考*