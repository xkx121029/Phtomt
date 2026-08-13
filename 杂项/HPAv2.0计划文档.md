这是一份从零开始的对照文档。每条提示词要想真正跑起来，都对应着一组必须先实现的代码功能。按实现顺序排列，每完成一层，提示词就多一层可用性。

---

# Phantom 提示词实现前提文档

**版本**：v1.0  
**目标**：明确"让提示词真正生效"所需的全部代码功能，按依赖关系排列

---

## 总览：提示词与代码功能的对应关系

| 提示词 | 依赖的代码功能 | 优先级 |
|--------|--------------|--------|
| 系统 Prompt（一） | 全部功能 | P0 |
| 歧义检测+规划（二） | 云端 API 调用、应用列表获取、用户画像读取 | P1 |
| 每步决策（三） | 感知层（读屏+截图）、页面协议构建、页面指纹 | P0 |
| 执行验证（四） | 执行层（点击/滑动/输入）、页面指纹对比 | P0 |
| 异常重规划（五） | 执行历史记录、失败计数 | P1 |
| 用户指导（六） | 异常协作面板 UI、用户输入捕获 | P2 |
| 接管恢复（七） | 接管处理、页面变化监听、恢复引擎 | P2 |
| 批量规划（八） | 多任务队列 | P2 |

---

## 第零层：项目骨架——所有功能的前提

没有这一层，代码根本跑不起来。

### 0.1 工程结构

- [ ] Android 工程创建（Minimum SDK 29, Kotlin）
- [ ] 包结构按模块划分（accessibility / perception / execution / decision / orchestration / memory / network / ui / model / database / security）
- [ ] Hilt 依赖注入框架配置完成
- [ ] Coroutines 协程支持配置完成
- [ ] kotlinx.serialization 序列化库配置完成
- [ ] OkHttp 网络库配置完成
- [ ] Shizuku API 依赖配置完成

### 0.2 数据模型

- [ ] `PageProtocol` — 页面快照数据结构
- [ ] `PageInfo` — 页面元信息（page_id, fingerprint, source, context_hint, page_type）
- [ ] `ElementNode` — 控件节点（id, type, label, bounds_ratio, clickable, scrollable, enabled, priority, highlight, children）
- [ ] `ElementChange` — 增量变更
- [ ] `AgentAction` — 动作指令（action, target, text, direction, keycode, package_name, timeout_ms, reason, summary, reasoning, expected, confidence, page_fingerprint, needs_user_confirmation）
- [ ] `ActionTarget` — 动作目标（method: id/label/coordinate, value）
- [ ] `TaskPlan` — 任务计划（steps, estimated_time_seconds, confidence）
- [ ] `StepRecord` — 步骤记录（step, action, sendResult, verificationResult, pageBeforeFingerprint, pageAfterFingerprint, durationMs, isConfirmed）
- [ ] `TaskContext` — 任务上下文（userTask, currentStep, stepIndex, totalSteps, lastStepResult, consecutiveFailures）
- [ ] `VerifyResult` — 验证结果（success, reason, needsCloudVerify）
- [ ] `RecoveryResult` — 接管恢复结果（stepIndex, confidence, reason）
- [ ] `ExecutionResult` — 执行结果（success, detail, durationMs）

**验收**：所有数据类编译通过，序列化/反序列化测试通过。

---

## 第一层：无障碍服务——感知层的第一步

系统 Prompt 中"页面数据格式"一节描述的 JSON 结构，全部由这一层产出。

### 1.1 无障碍服务配置

- [ ] `res/xml/accessibility_service_config.xml` 创建，所有属性固化
- [ ] `AndroidManifest.xml` 中声明 Service（含 `BIND_ACCESSIBILITY_SERVICE` 权限、intent-filter、meta-data，不含 process 属性）
- [ ] `AccessibilityConfig` 常量类创建（固化通知渠道 ID、通知内容、图标引用）
- [ ] 构建时检查 task 创建（编译时验证 Manifest 和 XML 配置正确性）
- [ ] 通知图标资源创建（24x24 png，包含 fallback 逻辑）

### 1.2 PhantomAccessibilityService

- [ ] `onCreate()` — 服务创建
- [ ] `onServiceConnected()` — 创建通知渠道 + `startForeground()` + 图标 fallback 机制
- [ ] `onAccessibilityEvent()` — 接收窗口变化事件，转发给 ScreenReader
- [ ] `onInterrupt()` — 系统中断回调
- [ ] `onUnbind()` — **返回 true**（允许自动重建）
- [ ] `onRebind()` — 重建后重新 startForeground
- [ ] `onDestroy()` — 清理
- [ ] 通知构建逻辑（含 `resolveIcon()` fallback）

**验收**：真机上无障碍服务存活 > 10 分钟，开关不自动关闭，通知栏持续显示。

---

## 第二层：感知层——产出页面协议

系统 Prompt 中的"页面数据格式"和每步决策 Prompt 中的 `{page_protocol_json}`，全部由这一层产出。

### 2.1 控件树解析器

- [ ] `AccessibilityTreeParser.parse()` — 从 `rootInActiveWindow` 开始遍历控件树
- [ ] 深度截断（MAX_DEPTH = 18）
- [ ] 不可见节点过滤（`isVisibleToUser == false` 且 depth > 0 时跳过）
- [ ] 坐标换算（像素坐标 → 0~1 比例坐标）
- [ ] 无意义节点过滤（无交互 + 无文字 + 无子节点的叶子节点丢弃）
- [ ] `parseFingerprint()` — 快速指纹计算（轻量版本，只取关键控件特征）

### 2.2 端侧页面标注器

- [ ] `PageAnnotator.inferPageType()` — 页面类型推断（search_page / search_result_list / product_detail / checkout / payment_confirm / completion / dialog_overlay / loading / error / generic）
- [ ] `PageAnnotator.generateContextHint()` — 语义描述生成
- [ ] `PageAnnotator.annotate()` — 控件优先级标注（high / medium / low）+ 按优先级排序
- [ ] 倒计时广告检测逻辑（跳过按钮 + 倒计时数字组合 → 在 contextHint 前置 `【⚠️ 疑似倒计时广告】` 标记）

### 2.3 ScreenReader 统一入口

- [ ] `ScreenReader.onPageChanged()` — 接收无障碍事件
- [ ] `ScreenReader.captureCurrentPage()` — 返回完整 `PageProtocol`
- [ ] `ScreenReader.captureQuickFingerprint()` — 快速返回当前页面指纹字符串

### 2.4 截图路径

- [ ] `MediaProjection` 权限申请与授权流程
- [ ] `ScreenshotCapture.captureBitmap()` — 通过 ImageReader 捕获屏幕截图
- [ ] `ScreenshotCompressor.compress()` — 缩放到 1080px 宽 + JPEG quality 75%
- [ ] 多模态请求构造（文本 + base64 图片 JSON 组装）

**验收**：打开任意 App → 调用 `captureCurrentPage()` → 返回的 JSON 能准确描述屏幕上的控件类型、文字和位置。`page_type` 字段正确。

---

## 第三层：页面指纹——执行前验证的前提

系统 Prompt 中的 `page_fingerprint` 字段和执行验证 Prompt 的前提。

### 3.1 PageFingerprint

- [ ] `PageFingerprint.compute()` — 基于可交互控件的 id + type + label + boundsRatio 生成哈希
- [ ] 页面不变 → 指纹不变；页面变化 → 指纹必变

**验收**：同一页面两次读屏 → 指纹一致。切换页面后 → 指纹不同。

---

## 第四层：执行层——让 AI 的动作指令能真正操作手机

系统 Prompt 中所有动作类型（tap / swipe / type / key / wait / launch / scroll_to）需要有对应的执行能力。

### 4.1 Shizuku 执行器

- [ ] `ShizukuActionExecutor.isAvailable` — Shizuku 可用性检测
- [ ] `ShizukuActionExecutor.execute()` — 执行 ADB 命令
- [ ] `input tap x y` — 坐标点击
- [ ] `input swipe x1 y1 x2 y2 duration` — 滑动
- [ ] `input keyevent <code>` — 系统按键（BACK=4, HOME=3, ENTER=66）
- [ ] `monkey -p <package> -c android.intent.category.LAUNCHER 1` — 启动应用
- [ ] `cmd clipboard set "text"` + `input keyevent 279` — 输入文字（剪切板 + 粘贴）

### 4.2 无障碍执行器

- [ ] `AccessibilityActionExecutor.execute()` — 无障碍方式执行动作
- [ ] `performAction(ACTION_CLICK)` — ID 点击
- [ ] `dispatchGesture` — 坐标点击（兜底）
- [ ] `performAction(ACTION_SET_TEXT)` — 输入文字
- [ ] `performGlobalAction(GLOBAL_ACTION_BACK/HOME)` — 系统按键

### 4.3 路由层

- [ ] `ActionRouter.execute()` — Shizuku 优先 → 失败降级无障碍
- [ ] 降级时打印日志记录原因

### 4.4 带验证的点击执行器

- [ ] `VerifiedClickExecutor.click()` — 执行 + 指纹对比
- [ ] 执行前记录指纹 → 执行 → 等待 600ms → 对比指纹
- [ ] 指纹不变 → Shizuku 升级重试
- [ ] 指纹变化 → 返回成功
- [ ] 有意义变化检测（排除状态栏时间刷新等无意义变化）

**验收**：手动构造一个 tap 动作 → 执行 → 对应位置被点击。页面变化能被正确验证。

---

## 第五层：端侧决策引擎——40% 的步骤不消耗云端调用

系统 Prompt 中弹窗/加载/异常/完成四类场景由端侧处理，只有正常页面才发 Prompt 给云端。

### 5.1 LocalDecisionEngine

- [ ] `decide()` — 五分类主逻辑
- [ ] 弹窗检测：dialogPositivePatterns（允许/同意/确定/我知道了/始终允许）+ dialogDismissPatterns（关闭/X/取消/以后再说/跳过）
- [ ] 加载中检测：loadingPatterns（加载中/请稍候）+ 控件数 < 3
- [ ] 异常页检测：errorPatterns（网络异常/加载失败/点击重试）
- [ ] 任务完成检测：completionPatterns（完成/成功/提交成功/支付成功）
- [ ] 本地动作生成（弹窗 → tap 正向按钮或关闭按钮；加载 → wait；异常 → 重试或 BACK；完成 → task_complete）
- [ ] 防死循环：连续 5 次端侧决策 → 强制走云端

**验收**：在美团/设置等 App 中触发弹窗 → 端侧自动关闭。加载页 → 自动等待。错误页 → 自动重试。

---

## 第六层：网络层——把页面数据发出去，把动作指令收回来

所有 Prompt 的实际执行层。

### 6.1 AgnesApiClient

- [ ] HTTP 客户端初始化（OkHttp，超时 5s）
- [ ] 文本请求方法（发送 system prompt + user message，接收 JSON 响应）
- [ ] 多模态请求方法（发送 system prompt + 文本 + base64 图片，接收 JSON 响应）
- [ ] 重试逻辑（失败重试 1 次）
- [ ] 错误处理（超时 → 抛异常；HTTP 错误码 → 抛异常；JSON 解析失败 → 返回 null）

### 6.2 CloudAgent 接口

- [ ] `plan()` — 调用歧义检测+规划 Prompt，返回 `TaskPlan`
- [ ] `decide()` — 调用每步决策 Prompt，返回 `AgentAction`
- [ ] `verify()` — 调用执行验证 Prompt，返回 `VerifyResult`
- [ ] `replan()` — 调用异常重规划 Prompt，返回 `TaskPlan`
- [ ] `decideWithUserHint()` — 调用用户指导 Prompt，返回 `AgentAction`
- [ ] `identifyStepAfterTakeover()` — 调用接管恢复 Prompt，返回 `RecoveryResult`
- [ ] `decideWithScreenshot()` — 调用多模态请求（截图路径），返回 `AgentAction`

### 6.3 页面协议构建器

- [ ] `PageProtocolBuilder.build()` — 将 `PageProtocol` 对象序列化为 JSON 字符串
- [ ] `IncrementalPageDiff.diff()` — 对比前后两次页面快照，生成 diff 版本（mode="diff"）
- [ ] 变化量 > 50% 或 page_id 变化 → 自动回退到 mode="full"

### 6.4 JSON 响应解析器

- [ ] 三步兜底解析：
  - Step 1：直接 `Json.decodeFromString`
  - Step 2：正则从 ` ```json ... ``` ` 代码块中提取
  - Step 3：正则匹配第一个完整 `{ ... }` 对象
  - Step 4：全部失败 → 构造合法 abort 动作（`{"action":"abort","reason":"JSON parse failed","confidence":0}`）

**验收**：发送真实页面数据给 agnes-2.5-flash → 收到合法的 `AgentAction` JSON → 解析成功。

---

## 第七层：任务调度引擎——ReAct 循环

把感知、决策、执行、验证串成完整闭环。

### 7.1 状态机

- [ ] TaskOrchestrator 状态机实现：IDLE → PLANNING → EXECUTING → VERIFYING → COMPLETED / ABORTED / PAUSED / TAKEOVER
- [ ] `startTask()` — 启动新任务
- [ ] `pause()` / `resume()` / `cancel()` — 任务控制

### 7.2 ReAct 执行循环

- [ ] 读屏 → 端侧决策 or 云端决策 → 执行 → 验证 → 下一步 or 重试
- [ ] 端侧决策处理弹窗/加载/异常 → 继续循环
- [ ] 云端决策处理正常页面 → 返回动作 → 执行
- [ ] 验证失败 → 重试（最多 3 次）→ 3 次后进入异常协作
- [ ] 验证成功 → `currentStepIndex++` → 继续下一步
- [ ] 全部步骤完成 → 状态变为 COMPLETED

### 7.3 RobustStepVerifier（三重门验证）

- [ ] 执行后等待 300ms → 读屏 → 判断
- [ ] 再等 300ms → 读屏 → 再判断
- [ ] 再等 300ms → 读屏 → 三取二
- [ ] 有意义变化检测（排除状态栏时间刷新等）
- [ ] wait 动作特殊处理（wait 后指纹不变是正常的）

### 7.4 ExecutionHistory（执行历史）

- [ ] 每步记录 `StepRecord`（含 sendResult, verificationResult, isConfirmed）
- [ ] `isConfirmed` 只在 `verificationResult == "verified_success"` 时为 true
- [ ] 下一步 Prompt 中 `{last_step_result}` 根据 `isConfirmed` 决定：
  - `true` → "✅ 已确认成功: xxx"
  - `false` + sendResult="sent" → "⚠️ 已发送但未确认: xxx"
  - `false` + sendResult="send_failed" → "❌ 未生效: xxx"

### 7.5 动作合并处理

- [ ] 收到 JSON 数组（合并模式）→ 按顺序逐个执行
- [ ] 每个子动作执行后验证 → 某个失败则丢弃剩余 → 重新上报云端

**验收**：Mock 一个固定动作序列 → 验证完整 ReAct 循环跑通 → 每一步都有验证记录 → `isConfirmed` 逻辑正确。

---

## 第八层：用户界面——用户能看到 Agent 在做什么

系统 Prompt 中 `reasoning` 字段的展示、异常协作面板、接管面板、歧义澄清面板都依赖这一层。

### 8.1 悬浮窗

- [ ] FloatingWindowService（前台服务，WindowManager 添加悬浮窗）
- [ ] 悬浮球（待机态：圆形、可拖动、半透明渐变）
- [ ] 执行面板（任务标题 + 当前步骤 + 分段进度条 + 上一步/下一步预览 + 跑马灯 + 暂停/取消按钮）
- [ ] 折叠窄条（步骤 + 进度条，点击展开）
- [ ] AI 决策详情面板（可展开，展示：页面摘要、AI 判断、AI 动作、预期、执行历史、统计）
- [ ] 半屏输入面板（任务下达入口：文字输入 + 语音输入）

### 8.2 跑马灯

- [ ] MarqueeTextView（单行文字，向左匀速滚动，约 40px/s）
- [ ] 支持运行时更新文字（淡出 → 更新 → 淡入）

### 8.3 异常协作面板

- [ ] AnomalyCollaborationView（问题描述 + 四选项：手动接管/告诉 AI/换方式重试/取消）
- [ ] 用户指导输入框 + 发送按钮
- [ ] 接管状态面板（"🤚 手动接管中，完成后请点击'我完成了'"）

### 8.4 歧义澄清面板

- [ ] ClarificationPanelView（卡片式选项列表 + 手动输入框 + 倒计时提示）
- [ ] 5 秒超时自动选择默认项

### 8.5 通知栏

- [ ] TaskNotificationManager（通知渠道创建 + 进度通知 + 内联操作按钮）
- [ ] 通知状态变化（任务开始 / 每步更新 / 异常震动 / 接管中 / 完成 / 取消）

**验收**：从下达任务 → 悬浮窗全程展示进度 → 跑马灯滚动 AI 思考 → 点击面板可展开详情 → 通知栏同步进度。

---

## 第九层：协作用户交互——让用户能介入

用户指导 Prompt（六）和接管恢复 Prompt（七）依赖这一层。

### 9.1 异常触发与协作入口

- [ ] 异常触发逻辑（端侧失败 3 次 / 动作 3 次后页面无变化 / 云端 abort / 云端不可用 3 次）→ 自动弹出异常协作面板

### 9.2 手动接管

- [ ] ManualTakeoverHandler（Agent 进入观察模式：无障碍继续监听但不执行操作）
- [ ] 接管期间页面变化记录（用于恢复时分析用户做了什么）
- [ ] "我完成了"按钮 → 触发接管恢复

### 9.3 接管恢复引擎

- [ ] TakeoverRecoveryEngine（三策略恢复）：
  - 策略 1：page_id 精确匹配（当前 page_id 是否包含某步骤的预期页面特征）
  - 策略 2：控件标签语义相似度匹配
  - 策略 3：推给云端（调用接管恢复 Prompt）
- [ ] 低置信度时弹出候选步骤列表让用户手动确认

### 9.4 用户指导流程

- [ ] 用户输入捕获 → 打包（当前页面 + 用户提示 + 失败历史）→ 发送云端（调用用户指导 Prompt）
- [ ] 执行成功后 → 写入异常记忆

**验收**：主动触发一个异常场景 → 完整走通接管/指导/重试三条路径各一次。

---

## 第十层：记忆系统——让 Agent 越用越聪明

用户画像在歧义检测 Prompt（二）和规划 Prompt 中使用，异常记忆在异常协作流程中使用。

### 10.1 Room 数据库

- [ ] `PhantomDatabase` 创建
- [ ] `anomaly_memory` 表 + DAO（id, pageFingerprint, pageLabels, anomalyType, anomalyDescription, userSolution, resolvedAction, appPackage, hitCount, successCount, createdAt, lastUsedAt）
- [ ] `user_profile` 表 + DAO（key, category, value, confidence, useCount, lastUsedAt）
- [ ] `user_addresses` 表 + DAO（label, fullAddress, appPackage）
- [ ] `task_templates` 表 + DAO（id, name, originalTask, planJson, executionCount, isQuickAction, scheduleCron）

### 10.2 异常经验记忆引擎

- [ ] `AnomalyMemoryEngine.findSolution()` — 精确匹配 + 模糊匹配
- [ ] `AnomalyMemoryEngine.applySolution()` — 命中后直接复用
- [ ] `AnomalyMemoryEngine.saveSolution()` — 用户教过之后自动写入
- [ ] 异常触发时先查记忆 → 命中则直接应用 → 不命中才走协作流程

### 10.3 用户画像引擎

- [ ] `ProfileLearner.learnFromTask()` — 任务成功后自动提取关键选择写入画像
- [ ] 手动"记住这个选择"按钮
- [ ] 云端 Prompt 注入用户偏好子集（只注入与当前任务相关的部分，不全量发送）

### 10.4 任务模板引擎

- [ ] `TaskTemplateEngine.findMatchingTemplate()` — 新指令与已存储模板的 Jaccard 相似度计算
- [ ] 相似度 > 85% → 复用模板计划，跳过 LLM 规划

**验收**：同类异常二次命中率 > 80%。重复任务第二次开始走模板。

---

## 第十一层：多任务队列——批量规划的前提

批量规划 Prompt（八）依赖这一层。

### 11.1 任务队列

- [ ] TaskQueue（已完成 / 执行中 / 等待中 三种状态）
- [ ] 队列 UI（进度展示 + 拖拽调整优先级）
- [ ] 按顺序逐个执行
- [ ] 异常时后续任务自动挂起
- [ ] 队列中追加新任务

### 11.2 批量规划入口

- [ ] 用户指令中"然后""还有""顺便""另外""同时"连接词识别
- [ ] 调用批量规划 Prompt → 返回多个独立计划
- [ ] 逐个加入任务队列

---

## 第十二层：安全与边界——不该做的事绝不做

系统 Prompt 中 `needs_user_confirmation` 字段的执行端依赖。

### 12.1 敏感页面检测

- [ ] `SensitivePageDetector.isSensitive()` — 银行 App / 支付密码页 / 金融确认页识别
- [ ] 敏感页面 → 只读模式（拒绝执行所有动作）

### 12.2 数据脱敏

- [ ] `DataSanitizer.sanitize()` — 手机号/身份证/银行卡号的端侧正则脱敏
- [ ] 在 JSON 序列化之前完成

### 12.3 不可逆操作确认

- [ ] `needs_user_confirmation == true` 的动作 → 执行前弹窗让用户手动确认
- [ ] 支付/删除/发送消息/发布内容 等操作的关键词匹配

---

## 所依赖的总表

| 层级 | 主题 | 任务数 | 对应提示词 |
|------|------|--------|-----------|
| 0 | 项目骨架 | 2 | — |
| 1 | 无障碍服务 | 2 | — |
| 2 | 感知层 | 4 | 系统 Prompt（页面数据格式）、决策 Prompt |
| 3 | 页面指纹 | 1 | 系统 Prompt（page_fingerprint）、验证 Prompt |
| 4 | 执行层 | 4 | 系统 Prompt（全部动作类型）、决策 Prompt |
| 5 | 端侧决策引擎 | 1 | 为决策 Prompt 减负（40% 场景不调用） |
| 6 | 网络层 | 4 | 所有 Prompt 的传输层 |
| 7 | 任务调度引擎 | 5 | 决策/验证/重规划 Prompt 的调用方 |
| 8 | 用户界面 | 5 | 系统 Prompt 中 reasoning 的展示、异常协作入口 |
| 9 | 协作用户交互 | 4 | 用户指导 Prompt、接管恢复 Prompt |
| 10 | 记忆系统 | 4 | 歧义检测 Prompt（用户画像）、异常协作 |
| 11 | 多任务队列 | 2 | 批量规划 Prompt |
| 12 | 安全与边界 | 3 | 系统 Prompt 中 needs_user_confirmation 的端侧执行 |
| **合计** | | **41** | |

---

## 推荐实现顺序

按照这个顺序，每一步做完都可以验证，不会出现"写了代码但测不了"的情况：

```
第零层  →  第一层  →  第二层  →  第三层  →  第四层
(骨架)    (无障碍)   (感知)     (指纹)     (执行)
                              ↘           ↙
                              第六层  →  第五层  →  第七层  →  第八层  →  第九层
                              (网络)    (决策引擎)  (调度)     (UI)      (协作)
                                                                       ↓
                                                              第十层  →  第十一层  →  第十二层
                                                              (记忆)    (队列)     (安全)
```

**里程碑 1**（第零层 ~ 第三层完成）：Agent 能正确描述任意 App 的当前屏幕状态。

**里程碑 2**（第四层完成）：Agent 能操控手机——点击、滑动、输入。

**里程碑 3**（第五层 ~ 第七层完成）：Agent 能自动完成一个完整的端到端任务，"没做却以为做了"的问题被根治。

**里程碑 4**（第八层 ~ 第九层完成）：用户能看到 Agent 在做什么，卡住时能介入协助。

**里程碑 5**（第十层 ~ 第十二层完成）：Agent 越用越聪明，安全性达标。

---

**文档版本**：v1.0
**创建日期**：2026-08-12

这份文档的作用是：任何人接手这个项目，只要按顺序逐层实现，就能保证提示词不是空中楼阁——写出来的每一行代码都在为提示词的生效铺路。

*内容由 AI 生成仅供参考*