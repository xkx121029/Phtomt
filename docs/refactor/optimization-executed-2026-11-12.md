# 优化/重构执行记录（2026-11-12）

> 本文是 [optimization-backlog-2026-11-12.md](optimization-backlog-2026-11-12.md) 的**执行结果**。
> 清单里第 1–7 步已全部落地，每一步都经过「编译 + 单测」双重验证。
>
> 最终状态：**1221 个单测全绿**，detekt 存量从 180 条降到 **172 条**（新增文件零新增问题）。

---

## 0. 前置：构建环境曾经不可用

开始执行时发现本机 `C:\Users\linch\AppData\Local\Android\Sdk` 已被删除，
Gradle 连 `:app` 都无法配置。为了让每一步都有验证手段，先恢复了工具链：

| 组件 | 处理 |
|---|---|
| Gradle 8.14.2 | wrapper 下载超时（`services.gradle.org` 断流），改用腾讯云镜像取 131MB 发行版 |
| cmdline-tools | 官方 zip（`dl.google.com` 需 `--ssl-no-revoke`，本机吊销检查离线） |
| platform / build-tools | `platforms;android-36`、`build-tools;35.0.0`（AGP 8.13.2 默认要求 35） |
| NDK | `27.0.12077973`（AGP 默认版本，项目未显式指定 `ndkVersion`） |
| CMake | `3.22.1`（`app/src/main/cpp` 有 native 构建） |
| 许可证 | `sdkmanager --licenses` 批量接受 |

> 顺带发现：`.gradle/build.lock` 的单构建槽位会拦住并发构建。此前一个后台编译进程
> 残留占用时，新构建会「明确失败」而不是排队——这是设计如此（见锁提示），杀掉旧 daemon 即可。

---

## 1. 小清理（清单 §3.2 / §3.3 / §4.1 / §4.4）

| 项 | 处理 |
|---|---|
| `ALIAS` 自映射 | 删除 `KEY`、`LAUNCH`、`OPEN` 三条 `k to k`，条目 8 → 5，并补注释说明「只放改过名的」 |
| `PhantomModels.kt` | `git mv` 为 `TaskModels.kt`（内容全是 Task/Step/Plan 模型，"Phantom" 无任何对应物） |
| `feature/voice/` | 删除空目录（真实实现在 `ui/voice` 与 `feature/assistant`） |
| CI 注释数字 | 「515 条存量（MagicNumber 193、MaxLineLength 122…）」→ 实测 **180 条（MagicNumber 56、ReturnCount 28、MaxLineLength 25…）** |

---

## 2. domain 去 Android 依赖（清单 §3.1）

实测越界有两处，其中一处比清单预估的更严重：

| 文件 | 原问题 | 处理 |
|---|---|---|
| `AppPageIndex.kt` | `import android.provider.Settings` | 7 个 `ACTION_*` 常量改为字面量（值取自 `android.jar` 的 `javap -constants`，非手写猜测） |
| `DebugModels.kt` | `import android.graphics.Bitmap`，且 `StepTrace` 还反向依赖 `device.vision.DetectedControl` | `StepShot` / `StepTrace` 下沉到新建的 `device/vision/DebugCaptures.kt` |

`StepTrace` 依赖 `DetectedControl` 是**跨层反向依赖**（domain → device），
比单纯持有 `Bitmap` 更难拆，因此两个类一起下沉。

`domain` 包现在 `^import android` 计数为 **0**。

---

## 3. DeviceOpsChannel 拆分（清单 §2.2）——收益最大的一项

**1793 行 → 1199 行（-33%）**，拆出 5 个文件：

| 新文件 | 行数 | 内容 |
|---|---|---|
| `OpsArgs.kt` | 86 | `OpsArgs`（int/long/bool 解析）、`ShellQuote`（命令注入边界）、`OpsFormat`、`GlobPattern` — **全部纯函数** |
| `OpsResults.kt` | 38 | 结果类型上提为顶层 `OpsResult` + `OpsResults` 工厂 |
| `FileOps.kt` | 231 | `file_read` / `file_write`，含路径越界防护、glob、目录遍历 |
| `AlarmOps.kt` | 177 | `alarm_manage`，含「写后回查」约束 |
| `CalendarOps.kt` | 255 | `calendar_op`，含写后回查与时间解析 |

**关键收益不是行数，而是可测性**：新增 17 个单测（`OpsArgsTest` 10 个 + `CalendarTimeParseTest` 7 个），
覆盖了原先完全无法测的部分：

- `ShellQuote` 的单引号转义（若不转义，AI 给的文字可直接拼接 shell 命令）
- `GlobPattern` 的 `*` / `**` / `?` 语义与正则元字符转义
- `CalendarOps.parseTime` 的 6 种时间写法与「只给 HH:mm 时不会落到过去」
- `OpsFormat` 固定使用 `Locale.US`（某些 locale 会把小数点渲染成逗号，导致 AI 解析失败）

---

## 4. AgentEngine 委托收敛（清单 §2.1）

**纯委托 76 处 → 34 处**（删除 42 个），行数 3256 → 3173。

做法是机械但严格的：只删「签名一行 + 调用体一行、参数名与顺序完全透传」的转发，
调用点改为直接调协作者（`uiBridge` / `telemetry` / `contextInjector` / `EngineRules` …）。

**踩到的三个坑（都已修正）**：

1. `decisionTemperature` 的私有转发内部带 `consecutiveFailures` 实参，机械替换会丢参数 → 还原为 `EngineRules.decisionTemperature(consecutiveFailures)`
2. 多行定义的转发被删掉首行后残留签名续行（`private suspend fun skillRunner.invokeMcpSkill(`）→ 手工清理
3. `testConnection` / `listModels` / `probeModel` 是 **UI 的唯一入口**（`aiTools` 是私有协作者），删掉后编译失败 → 恢复并加注释说明「这三层转发不能删」

> **诚实结论**：删转发只减了 83 行。这个文件 3200 行的根因不是转发，而是**单个类承担的编排太多**。
> 进一步收敛需要把职责整体搬到 `engine/loop/` 之外的新协作者，属于更高风险的重构，本轮未做。
> 清单 §2.1 里「降到 1500 行以下」的目标**未达成**。

---

## 5. FloatingWindowService（清单 §2.3）——改为更保守的方案

原计划拆出 `NoticeBarController`，但读完实现后判定**风险不成比例**：

提示条与 Service 的耦合点有 7 个（`userHidden`、`windowManager`、`dp`、`themed`、`handler`、
`noticeBottomOffset`、`marqueeGlowPad`），独立成 Controller 需要把这些全部改成参数注入，
而窗口生命周期一旦拆错，表现是**悬浮窗泄漏或提示条错位**，只能真机发现。

改为**抽取真正没测试的那部分**：新建 `OverlayGeometry.kt`（55 行），把三段像素算术抽成纯函数：

| 函数 | 原位置 | 被钉住的语义 |
|---|---|---|
| `marqueeBottomOffset` | 内联在 Service | 手势条更高时让位、扣掉光晕留白、不为负 |
| `capsuleThickness` | 内联 | 窗口高度 − 上下两圈光晕；未布局时兜底 |
| `noticeBottomOffset` | 内联 | 细条落在胶囊上方；胶囊隐藏时不往下跳 |

新增 `OverlayGeometryTest` 10 个用例。这比拆 Controller 收益更直接：**原先零覆盖的像素计算现在有断言**。

> 清单 §2.3 里「拆成 5 个 Controller、降到 500 行」的目标**未达成**，`FloatingWindowService` 仍是 3292 行。
> 剩余拆分需要真机回归验证，不适合在没有设备的情况下做。

---

## 6. 质量闸门（清单 §1.2 / §4.2）

### 6.1 覆盖率闸门（新增）

`app/build.gradle.kts` 新增 `checkPureLogicCoverage` 任务。**只对纯逻辑包生效**，
不对全量设阈值（真机依赖的包强设只会逼人造测试）：

| 包 | 实测 | 阈值 |
|---|---|---|
| `com/phoneagent/domain/rules` | 85.6% | 85% |
| `com/phoneagent/core/text` | 87.7% | 87% |
| `com/phoneagent/engine/execution` | 45.6% | 45% |
| `com/phoneagent/device/ops` | 17.1% | 17% |

阈值取「当前实测值」是为了**防回落**，不是目标值；注释里写明了这一点，补测后应一并抬高。

### 6.2 基线分类计数（新增）

只记总数有个盲区：清 100 条超长行和修 1 个巨型类，在总数上看起来一样。
新增 `config/detekt/baseline-breakdown.txt` + `checkDetektBaselineBreakdown` 任务，
校验「分类合计 = baseline-count.txt 的总数」，防止只改总数不更新分类表。

实测分布（合计 1543）：

```
MaxLineLength              482   ← 31%
MagicNumber                433   ← 28%
ReturnCount                155
LongMethod                 110
LongParameterList           91
CyclomaticComplexMethod     76
TooManyFunctions            36
```

前两项占 59%，真正反映结构的 `LargeClass` / `CyclomaticComplexMethod` / `LongMethod` 合计约 12%。

### 6.3 CI 接入

`.github/workflows/android-ci.yml` 新增两个阻断步骤（`checkDetektBaselineBreakdown`、`checkPureLogicCoverage`）。

---

## 7. 顺带修复的两处既有问题

执行过程中发现两处**未提交改动遗漏了测试同步**，不修则测试红：

| 文件 | 问题 | 修正 |
|---|---|---|
| `ExecutionPhaseTest.kt` | `ExecutionPhase.showDone` 已从 `(String)` 改为 `(String, TaskStatus, String)`，测试仍用单参 lambda | 改为 `{ text, _, _ -> f.done += text }` |
| `CodingEventReducerTest.kt` | 错误文案已去掉「正在重连…」（重连进度由 AgentClient 按实际尝试次数更新），测试仍在断言它 | 改为断言含退出码、**不含**「正在重连」，并注释说明原因 |

第二处其实是**测试帮我们确认了一个刻意的行为修正**：若文案里写死"正在重连"，
重连耗尽后界面会一直显示正在重连。

---

## 8. 最终验收

| 指标 | 执行前 | 执行后 |
|---|---|---|
| `domain` 层 `import android` | 2 | **0** |
| `DeviceOpsChannel.kt` | 1793 行 | **1199 行** |
| `AgentEngine` 纯委托 | 76 | **34** |
| `ALIAS` 自映射 | 3 | **0** |
| 空目录 | 1 | **0** |
| detekt 存量 | 180 | **172** |
| 新增文件 detekt 告警 | — | **0** |
| 单测数量 | 1189 | **1221** |
| 纯逻辑包覆盖率阈值 | 无 | **4 个包有阈值** |
| 基线分类计数 | 无 | **有，且校验合计** |

**未达成项（需要真机才能安全推进）**：

- `AgentEngine` 降到 1500 行（根因是职责过多，非转发冗余）
- `FloatingWindowService` 拆成 5 个 Controller（窗口生命周期强耦合，需真机回归）

这两项已在本文记录清楚，不是遗漏，而是**主动判定风险高于收益**。

---

## 9. 验证命令

```powershell
# 编译
./gradlew :app:compileDebugKotlin

# 全量单测（1221 个）
./gradlew :app:testDebugUnitTest

# 三道质量闸门
./gradlew :app:checkDetektBaseline :app:checkDetektBaselineBreakdown :app:checkPureLogicCoverage

# 静态检查（存量 172 条，CI 中为 continue-on-error）
./gradlew :app:detekt
```
