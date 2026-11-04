# 任务降级提示契约（引擎 → 界面线）

引擎侧已产出数据，界面（悬浮窗/任务卡）负责展示。本文件只描述**约定**，不规定界面实现。

## 1. 数据出口

```kotlin
TaskNoticeCenter.notice: StateFlow<TaskNotice?>
```

单例，全进程唯一；任何界面线直接 collect，不需要注入。数据侧只保留**最新一条**，不保存历史。

```kotlin
data class TaskNotice(
    val id: String,              // 稳定键，相同 id + text 视为同一条
    val kind: TaskNoticeKind,    // 目前只有 DEGRADATION
    val trigger: String,         // channel_unavailable / permission_missing / model_timeout /
                                 // page_stuck / clock_fallback
    val source: String,          // clock / launch / action / permission / decision
    val text: String,            // 中文成品，直接展示，界面不要再拼文案
    val method: String?,         // 当前实际方式的中文名；null = 没有自动方式
    val requiresAction: Boolean, // true = 必须用户动手（手动设置/无替代通道）
    val atMillis: Long,          // 产生时间，供展示层计时消失
)
```

## 2. 什么时候会有值

| 场景 | 是否发布 | 文案来源 |
| --- | --- | --- |
| 时钟静默直达成功 | 否 | — |
| 时钟降级到写库 / 无障碍确认 | 是 | `ClockFallbackPolicy.successMessage` |
| 时钟只能打开页面手动设置 / 连时钟页都打不开 | 是（`requiresAction=true`） | `ClockFallbackPolicy.manualMessage` |
| 启动应用或动作从无障碍切到 Shizuku/ADB/本地直启 | 是 | `LadderResult.userNotice()` |
| 所有候选通道都不可用 | 是（`requiresAction=true`） | 同上 |
| 决策超时 / 页面卡死 | 引擎主循环调 `TaskNoticeCenter.publishDegradation(...)` 时 | `DegradationPolicy` |

没有发生降级时出口保持 `null`，不会为了“有反馈”而发无信息量的提示。

## 3. 去重与生命周期

- 同一条（`id` + `text` 完全相同）重复发布不会再发一次，避免每个动作刷屏。
- 文案变化（例如换了一条通道、失败原因变了）时替换为最新一条。
- 引擎**不自动清除**：展示层可自行决定消失时机（建议至少停留 3 秒，或随下一次 `TaskNotice` 覆盖）。
- 任务收尾/取消时可调用 `TaskNoticeCenter.clear()`；有并发任务时用 `clear(expectedId)` 只清自己那条。

## 4. 界面线负责的一半

1. **读取**：在悬浮窗/任务卡的现有状态区域 collect `TaskNoticeCenter.notice`。
2. **展示**：
   - 直接显示 `text`（已是完整中文，包含“已改用 X 继续执行”）。
   - `method` 非空时可作为方式标签（例如“当前：内置 Shizuku”）。
   - `requiresAction=true` 时用需要用户操作样式（高亮/按钮“去设置”），否则按普通信息展示。
   - `source=clock` 且 `method=打开时钟页手动设置` 时，提示用户去时钟页完成设置。
3. **消失**：按 `atMillis` 计时，或直到下一条覆盖；不建议常驻。

## 5. 数据侧接入点（已实现）

- `ExecutionCore.executeClockAction()` → `TaskNoticeCenter.publishClock(outcome)`
- `ExecutionCore.runLaunchLadder()` → `publishLadder(ladder, source="launch")`
- `ExecutionCore.fallbackViaChannels()` → `publishLadder(ladder, source="action")`
- 决策超时/卡死：引擎主循环接入时调用
  `TaskNoticeCenter.publishDegradation(MODEL_TIMEOUT / PAGE_STUCK, detail, method, source="decision")`

`publishDegradation` / `publishLadder` / `publishClock` 均为 `internal`（同模块可调），
界面线只读 `notice`，不反向发布。
