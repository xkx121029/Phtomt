# 虚拟副屏（overlay_display_devices）设计与实现对照

本文按**现有代码**描述副屏模式的实际实现，不是愿景稿。凡需求描述与代码不一致处，以代码为准，并在文中用「⚠ 差异」标出。

一句话实现：用 Shizuku 的 shell 通道写 `settings put global overlay_display_devices 1080x1920/160`，让系统挂一块模拟副屏；任务在副屏上通过 `am start --display` 起应用、`input -d` 注入触控；观察以副屏无障碍元素树为主；结束或失败时 `settings delete` 回收。

---

## 0. 入口清单

| 环节 | 入口文件 | 函数 / 位置 |
| --- | --- | --- |
| 开关持久化 | `app/src/main/java/com/phoneagent/data/prefs/AppSettings.kt` | `Settings.virtualDisplayEnabled`、`Keys.VIRTUAL_DISPLAY_ENABLED`（默认 `false`） |
| 能力声明（给模型） | `app/src/main/java/com/phoneagent/domain/model/CapabilitySpec.kt` | `IntentType.VIRTUAL_DISPLAY` 的 `CapabilitySpec`，`category = "自由模式"` |
| 能力探测 | `app/src/main/java/com/phoneagent/device/capability/DeviceCapabilityReader.kt` | `virtualDisplaySupported = shizukuReady()` |
| 任务开始时创建 | `app/src/main/java/com/phoneagent/engine/AgentEngine.kt` | `prepareVirtualDisplay()`（约 L2783） |
| 创建 / 起应用 / 观察 / 执行 / 销毁的底层 | `app/src/main/java/com/phoneagent/device/screen/VirtualDisplayAgent.kt` | `ensure()` / `launch()` / `screenshot()` / `tap()` 等 / `destroy()` |
| 副屏观察 | `app/src/main/java/com/phoneagent/engine/AgentEngine.kt` | `observeVirtualDisplay()`（约 L2845）、`degradeVirtualDisplay()`（约 L2893） |
| 副屏元素树 | `app/src/main/java/com/phoneagent/device/a11y/AgentAccessibilityService.kt` | `captureScreenOnDisplay(displayId)`（约 L180） |
| 副屏动作分发 | `app/src/main/java/com/phoneagent/engine/execution/ExecutionCore.kt` | `executeOnVirtualDisplay()`（约 L456） |
| 模型直调副屏 | `app/src/main/java/com/phoneagent/device/ops/DeviceOpsChannel.kt` | `virtualDisplayOp()`（约 L1205） |
| 销毁 | `AgentEngine.kt` | `releaseVirtualDisplay()`（约 L2803），由 `finish()` 内 `scope.launch { ... }`（约 L817）调用 |
| 开机残留清理 | `app/src/main/java/com/phoneagent/PhoneAgentApp.kt` | `VirtualDisplayAgent.cleanupGhost(shizuku)`（约 L44） |
| 状态展示 | `VirtualDisplayAgent.kt` / `app/src/main/java/com/phoneagent/ui/settings/SettingsAgent.kt` | `VirtualDisplayStatus.present()` / `VirtualDisplayStatusBlock`（约 L300） |
| shell 执行（含超时） | `provider/src/main/java/com/phoneagent/provider/shell/ShizukuManager.kt` | `executeShell()`（L119）、`waitForExit()`（约 L192） |

关键常量（`VirtualDisplayAgent` 伴生对象）：

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `OVERLAY_SPEC` | `1080x1920/160` | 写进 `overlay_display_devices` 的规格 |
| `WIDTH` / `HEIGHT` | `1080` / `1920` | 坐标系基准 |
| `CREATE_TIMEOUT_MS` | `8000` | 等系统挂出新 displayId |
| `WRITE_VERIFY_TIMEOUT_MS` | `5000` | 写入后回读确认窗口 |
| `TEARDOWN_TIMEOUT_MS` | `3000` | 等旧副屏被系统回收 |
| `POLL_INTERVAL_MS` | `300` | 轮询间隔 |
| `SHOT_PATH` | `/data/local/tmp/phoneagent_vd_shot.png` | 截图暂存（shell 可读） |

---

## 1. 五段链路

### 1.1 创建（含残留清理）

**入口**：`AgentEngine.prepareVirtualDisplay()` → `VirtualDisplayAgent.ensure()`。

**前置判定**

1. 设置项 `virtualDisplayEnabled == false` → 直接返回 `false`，不进入副屏。
2. `virtualDisplay` 代理为 `null`（`shizukuManager == null`）→ 日志「副屏模式需要 Shizuku 通道，当前不可用，已降级主屏模式」。
3. `ensure()` 内再判 `shizuku.isAvailable()`，不可用则发布 `VirtualDisplayStatus.ShizukuInactive` 并返回失败。

**实际下发**

1. `ensure()` 起手是幂等的：`displayId` 非空直接返回 `display=N`。
2. 清残留：`settings delete global overlay_display_devices`（`clearOverlaySetting()`，失败被 `runCatching` 吞掉，因为后面还有真正的判定）。
3. 等旧屏回收：轮询 `dumpsys display displays`，直到解析出的 displayId 除 `0` 外为空；最多等 `TEARDOWN_TIMEOUT_MS`，超时继续（创建阶段另有超时兜底）。
4. 记录写入前的 displayId 集合 `before`。
5. 写入：`settings put global overlay_display_devices 1080x1920/160`。
6. **写后回读**：轮询 `settings get global overlay_display_devices`，最多 `WRITE_VERIFY_TIMEOUT_MS`；只有读到字符串等于 `1080x1920/160` 才算写入生效。`settings put` 的命令回执**不作为**判据。
7. 等屏出现：轮询 `dumpsys display displays`，取 `before` 之外、非 `0` 的 displayId，最多 `CREATE_TIMEOUT_MS`。

**为什么命令回执不可信**：Shizuku 侧 `exitValue()` 在进程未退出时经 Binder 会把 `IllegalThreadStateException` 还原成 `RuntimeException("process hasn't exited")`，而 `Process.waitFor(timeout)` 只捕获前者，首次轮询就会把「仍在运行」当失败抛出；同时 `settings put` 的子进程继承了 stdout 管道，`readText()` 可能先于父进程退出返回 EOF。两者叠加会得到「命令报错但设置其实已生效」。因此 `ShizukuManager` 改为自轮询 `waitForExit()`（`SHELL_TIMEOUT_MS = 30_000`，50ms 间隔），`VirtualDisplayAgent` 改为回读为准。

**失败判定与走向**

| 条件 | 状态 | 用户可见文案来源 | 后续 |
| --- | --- | --- | --- |
| Shizuku 不可用 | `ShizukuInactive` | 设置页「未激活 Shizuku」 | `Outcome.Fail` → 引擎降级主屏，任务继续 |
| 回读不是目标值，且命令超时 / `hasn't exited` | `Failed` | 「写入模拟显示设置失败：命令超时未返回，且回读未看到目标值（回读「…」）」 | 降级主屏 |
| 回读不是目标值，命令已正常返回 | `Failed` | 「写入模拟显示设置失败：命令已返回但设置未生效（回读「…」）」 | 降级主屏 |
| 回读成功但命令回执为失败 | `Active` | 不报错；诊断里记「写入命令未返回但已生效」 | 继续 |
| 写入生效但 8s 内没有新 displayId | `Unsupported` | 「模拟副屏创建超时（设备可能不支持模拟辅助显示）」 | 降级主屏 |

失败时 `lastDiagnostic` 会写入「原因 + 完整命令 + 命令回执」三行，供设置页「查看诊断」展开。

**其它事实**

- `ensure()` 全程持 `Mutex`，与 `destroy()` 串行，避免并发创建/销毁打架。
- App 启动时 `cleanupGhost()` 会无条件清一次设置项与截图临时文件（仅 Shizuku 可用时），用于收拾上个进程被强杀留下的「第二块黑屏」。
- 创建成功后 `displayId` 是类级 `@Volatile` 字段，供观察、执行、状态展示共用。

### 1.2 起应用

**入口**：`ExecutionCore.executeOnVirtualDisplay()` 的 `ActionType.LAUNCH` 分支 → `VirtualDisplayAgent.launch(pkg)`；模型直调走 `DeviceOpsChannel.virtualDisplayOp("launch")`。

**实际下发**

1. 入参支持包名或 `包名/Activity` 全名；两侧 `trim` 并去掉包裹引号，空值返回「应用包名为空」。
2. 已含 `/`：直接 `am start --display <id> -n <包名/Activity>`。
3. 只给包名：先 `cmd package resolve-activity --brief <包名>`，取输出中最后一行包含 `/` 的内容，再 `am start --display <id> -n <解析结果>`。
4. 解析不到入口（少数 ROM 行为差异）：退回 `monkey -p <包名> --display <id> -c android.intent.category.LAUNCHER 1`。

> `am start` 没有 `-p` 选项，直接传包名会解析失败，所以必须先解析 Activity；这条写在代码注释里，属于已验证事实。

**判定与走向**

- 前半段判据是 shell 结果（退出码 0 视为成功，stderr 作为失败原因）。
- **落点回读**：命令成功后再等 `LAUNCH_SETTLE_MS`（1200ms），然后轮询任务栈归属，最长 `LAUNCH_VERIFY_TIMEOUT_MS`（3000ms，间隔 300ms）：
  - 主路径 `am stack list`：按 `RootTask` 分块，块内 `taskId=…: 包名/` 即把该块的 `displayId` 记为该包所在屏（兼容旧版 `Stack id=`）；
  - 退路 `dumpsys activity activities`：按 `displayId=N` 分段，段内 `Task{… I=包名/…}` 归属该屏；
  - 见到副屏 id → 成功；只在别的屏出现、或窗口内始终读不到任务栈 → 失败，且 `landingMismatch = true`。
- 成功后回报「已在副屏启动应用: pkg」。
- 失败返回「副屏启动应用失败: <原因>」；若是 `landingMismatch`，追加「，已降级主屏」，并调用引擎钩子 `onVirtualDisplayUnusable` 把本任务降级，后续动作不再打在错的屏上。

> 为什么必须回读：应用已有主屏任务时，部分系统会把那个任务直接带到前台，`am start --display` 仍是退出码 0。只判退出码就分辨不出「起在主屏」。

⚠ 差异（已知、未做）：没有确认包名是否真实存在（拼错包名时表现为 `resolve-activity` 失败 → monkey 失败），也没有校验目标 Activity 是否为用户真正想要的那个。

### 1.3 观察

**入口**：`AgentEngine.observeVirtualDisplay()`。

**顺序与判定**

1. `displayId` 丢失或代理为 `null` → `degradeVirtualDisplay("副屏代理或 displayId 丢失（创建阶段异常）")`。
2. **树优先**：无障碍服务在线时调 `AgentAccessibilityService.captureScreenOnDisplay(displayId)`，读该 display 的窗口元素树；读取异常被 `tryOrNull` 收掉，统计写入 `lastTreeStats`。
3. 树非空：再尝试 `vd.screenshot()`。
   - 截图失败：只记 INFO「副屏截图不可用（…），本轮按无障碍树观察」，继续用树；快照 `hasScreenshot = false`。
   - 截图成功：`hasScreenshot = true`。
   - 宽高缺失时用 `VirtualDisplayAgent.WIDTH/HEIGHT` 兜底。
4. 树为空或读不到：走第二条路 `vd.screenshot()`。成功则返回 `missingAccessibility = true`、宽高 1080x1920、`hasScreenshot = true` 的快照。
5. 树与截图都不可用：`degradeVirtualDisplay("副屏无障碍树不可用（统计），副屏截图也不可用（原因）")`。

**截图实现**：`screencap -d <id> -p /data/local/tmp/phoneagent_vd_shot.png`，成功后再 `base64 <path> && rm -f <path>` 走文本通道回传，App 侧 Base64 解码。失败原因写入 `lastScreenshotError`。

**降级走向**：`degradeVirtualDisplay()` → `abandonVirtualDisplay(reason)` → `destroy(reason)` 发布 `Degraded`，写一条 WARN 日志、`pushFloating("副屏不可用，已降级主屏", "OBSERVING")`，然后回到主屏 `observe()`。降级是明示的，设置页、日志与胶囊都能看到同一句原因。

### 1.4 执行

**入口**：`ExecutionCore.executeOnVirtualDisplay()`。触发条件：`virtualDisplay()?.isReady == true`，即副屏已创建；动作不经过无障碍服务。

**坐标来源**：与主屏同一口径 `IntentResolver.resolveAction()`，支持 `target{method:"coordinate", value:"比例x,比例y"}` 与 `x/y` 字段。

**实际下发**

| 动作 | 命令 |
| --- | --- |
| `click` / `tap` | `input -d <id> tap <x> <y>`（缺坐标直接失败并提示用 coordinate） |
| `long_click` / `long_press` | `input -d <id> swipe <x> <y> <x> <y> <duration>`（默认真 800ms） |
| `swipe` | `input -d <id> swipe <x1> <y1> <x2> <y2> <duration>`（默认 400ms） |
| `swipe_up/down/left/right` | 同上，按方向生成端点 |
| `scroll` / `scroll_to` | 近似实现：半屏等距滑动一次（0.7H→0.3H 或反向） |
| `type` | `input -d <id> text "<text>"`，对 `\`、`"`、`$` 转义 |
| `key` BACK/HOME/RECENT/ENTER | `keyevent 4 / 3 / 187 / 66` |
| `back` / `home` / `recents` | 同 keyevent |
| `launch` | 见 1.2 |
| `wait` | `delay(timeoutMs ?: durationMs ?: 1000)` |
| 其它 | 失败：「副屏模式暂不支持动作 …（可用：click/long_click/swipe/swipe_*/scroll/type/key/back/home/recents/launch/wait）」 |

**判定与走向**

- 判据只有「shell 是否接受命令」，没有任何控件级确认（没有点击后状态校验、没有元素存在性复核）。
- 失败直接把失败文案回给模型（「副屏执行失败：…」），由模型换路；不自动切主屏，不自动重试。
- 文字输入只回报位数（`已在副屏输入 N 位`），正文不进轨迹，避免把敏感内容写进日志。

### 1.5 销毁

**入口**：`AgentEngine.releaseVirtualDisplay()`（任务结束/停止，由 `finish()` 调用）→ `VirtualDisplayAgent.destroy(degradedReason = null)`；运行时降级走 `destroy(degradedReason)`；另有 App 启动时的 `cleanupGhost()`。

**实际下发**

1. `displayId = null`，并记下销毁前的状态 `previous`。
2. `settings delete global overlay_display_devices`。
3. **回读确认清空**：轮询 `settings get global overlay_display_devices`，读到 `null`/空才算清空；窗口为 `TEARDOWN_TIMEOUT_MS + WRITE_VERIFY_TIMEOUT_MS`（8s）。
4. 删截图临时文件：`rm -f /data/local/tmp/phoneagent_vd_shot.png`。

**失败判定与走向**

| 条件 | 结果 |
| --- | --- |
| 回读清空，且 `degradedReason == null` | 原来处于 `Active` → 状态回 `Idle`；否则保留原状态 |
| 回读清空，且 `degradedReason != null` | 发布 `Degraded(reason, atMillis)`（不回 Idle） |
| 未清空且 `wasActive`，普通收尾 | `Failed("关闭副屏失败：设置项未清空（回读「…」）")`，诊断里带命令与回执 |
| 未清空且 `wasActive`，运行时降级 | 仍发布 `Degraded(reason)`；关闭异常写进诊断，不覆盖降级原因 |
| 未清空但原本不是 `Active` | 不改状态（保留创建期的失败原因，避免把诊断擦掉） |
| 删除命令未返回但回读已清空 | 视为成功（与写入同口径） |

状态迁移收口在纯函数 `statusAfterDestroy(previous, degradedReason, atMillis)`：降级原因非空就一定是 `Degraded`，只有「正常收尾 + 原来 Active」才回 `Idle`。用户手动关开关时由 `VirtualDisplayAgent.clearStatus()` 回到 `Idle` 并清空诊断——这是唯一的「清掉降级原因」入口。

---

## 2. 为什么观察必须走元素树

### 2.1 实测事实

在 `emulator-5560`（Android 16 / API 36）上，副屏 `displayId=2` 存在时执行：

```
$ adb shell screencap -d 2 -p /data/local/tmp/vd.png
Display Id '2' is not valid
```

命令非零退出，stderr 即上面这句。经 `ShizukuManager.executeShell()` 后它成为 `ShellResult.Failure.reason`，最终落为：

```
lastScreenshotError = "screencap -d 2 失败：Display Id '2' is not valid"
```

代码没有硬编码这个字符串，只是把 stderr 原样透传；换机型/换版本时文案可能不同，但「`-d` 指向模拟副屏不被接受」这一行为一致。

### 2.2 为什么不能回退主屏截图

1. **坐标系不同**：副屏固定 1080x1920，主屏是物理分辨率。模型拿到主屏画面后会按主屏布局输出 0~1 比例坐标，落到副屏上位置全错——点击命中错误控件，甚至误触不可逆按钮。
2. **判断依据被替换**：模型看到的是主屏内容，却以为那是副屏当前页面，会把「主屏上恰好是目标页」当成「副屏任务完成」，属于假运行；后续每一步都建立在错误事实上。
3. **污染证据链**：截图会进本地轨迹、进入下一轮提示词，也会被验收当作「副屏画面」。一旦允许替代，事后无法区分「副屏真的完成了」和「主屏看起来完成了」。

所以代码在 `AgentEngine.captureScreenshot()` 里写死：副屏模式下只调 `virtualDisplay.screenshot()`，失败就 `log(WARN)` 并返回 `null`，**不落到** `ScreenCapture.capture()`（主屏截图）。`observeVirtualDisplay()` 同样从不调用主屏截图接口。

### 2.3 树能补上的部分

无障碍树是跨 display 读窗口拿到的，不经过 `screencap`，因此副屏截图不可用时它仍是唯一可用观察通道。代价是：没有像素、没有颜色/图形信息，只能看到控件的文本、描述、类名与 bounds。

---

## 3. 前提与天花板

**这些不是待修的 bug，是当前 API 与所选实现路径的边界。**

1. **必须 Shizuku**。实现是「shell 写系统设置 + shell 注入输入」，没有调用原生 `DisplayManager.createVirtualDisplay()`。`DeviceCapabilityReader` 直接把 `virtualDisplaySupported` 定义为 `shizukuReady()`；Shizuku 不在线时连设置都写不进去。
2. **`canHostTasks = false`**：⚠ 差异——**代码里没有 `canHostTasks` 这个字段/常量**。实际的三道闸门是：
   - 设置开关 `virtualDisplayEnabled`（默认关）；
   - 能力声明只在「自由模式」分类下注册（`CapabilitySpec` 的 `category = "自由模式"`），保守/均衡模式不暴露该能力；
   - 运行时 `prepareVirtualDisplay()` 要求 `ShizukuManager` 实例存在且 `isAvailable()`。
   如果后续需要「某能力不能承载任务」这种语义，现在的代码里没有对应表达，只能靠上述三点间接实现。
3. **树稀疏页面判断力下降**。WebView、Canvas/游戏、Flutter 等自绘界面，元素树可能只有外壳甚至为空；叠加 `screencap -d` 不可用，这类页面在副屏上等于「看不见」——结果就是 `degradeVirtualDisplay` 切回主屏，而不是猜着点。
4. **固定 1080x1920 / 160dpi**。与主屏分辨率无关，横屏应用、异形屏、平板布局会被拉伸或重排；坐标一律按副屏逻辑尺寸换算。
5. **无控件级确认**。执行只看 shell 是否接受，没有「点了之后页面是否变化」的校验；模型自己负责通过下一轮观察纠错。
6. **系统版本与厂商策略**。`input -d` / `screencap -d` 需 Android 10+，`am start --display` 需 Android 8+（代码注释口径）；部分厂商关闭「模拟辅助显示设备」后，写入设置也不会挂出副屏，表现为创建超时。
7. **模拟副屏不是镜像**。主屏与副屏是两个独立显示，应用状态跨屏共享，但画面不互通；`virtual_display present` 是通过在主屏重新 `monkey` 拉起同一应用来「把结果给用户看」，不是投屏。

---

## 4. 验收标准

### 4.1 算成功的状态

1. 设置页状态行为「已启用 display=N」（`VirtualDisplayStatus.Active`）。
2. 任务动作确实落在副屏：`input -d N` / `am start --display N` 有成功回执，且任务在副屏上完成目标（不是主屏上完成）。
3. 主屏全程可继续使用：任务运行期间用户前台应用不被顶掉，主屏无异常弹窗。
4. 胶囊（悬浮窗）正常：阶段推进正常，不出现「副屏不可用」的降级提示；若出现降级，必须同时有明确的 WARN 文案，而不是静默换屏。
5. 关闭后无残留：
   - `settings get global overlay_display_devices` 返回 `null`；
   - `dumpsys display` 中不再有额外的 `mDisplayId`；
   - `/data/local/tmp/phoneagent_vd_shot.png` 不存在。

### 4.2 各失败态在设置页的显示

判定函数是 `VirtualDisplayStatus.present(shizukuReady)`，判定顺序为：`Active` → `Degraded` → `!shizukuReady` → `Unsupported` → `Failed` → `Idle`。

| 内部状态 | 标题 | 明细 | 是否标红 |
| --- | --- | --- | --- |
| `Active(displayId)` | `已启用 display=N` | `任务正在副屏上运行，不占用当前屏幕` | 否 |
| `Degraded(reason, atMillis)` | `已降级到主屏` | `原因：reason（HH:mm）· 本轮任务已在主屏继续` | 是 |
| 任意状态但 Shizuku 掉线 | `未激活 Shizuku` | `副屏的创建与驱动都走内置 Shizuku 的 shell 通道：请先在「权限」页启动并授权` | 是 |
| `Unsupported(reason)` | `系统不支持` | `reason`（创建超时文案） | 是 |
| `Failed(reason)` | `创建失败` | `reason`（写入失败 / 关闭失败文案） | 是 |
| `Idle` | `已就绪` | `Shizuku 通道在线，任务开始时会创建副屏；若创建失败会在这里给出原因` | 否 |

失败态会多出一个「查看诊断 / 收起诊断」按钮，展开后显示 `VirtualDisplayAgent.lastDiagnostic`（为空时退回明细文本）。

> 判定顺序：`Active` → `Degraded` → `!shizukuReady` → `Unsupported` → `Failed` → `Idle`。`Degraded` 特意排在 Shizuku 掉线之前——降级常常就是 Shizuku 掉线导致的，标题必须指向用户真正要看的降级事实。`Degraded` 会一直保留，直到下一次成功创建副屏（`ensure()` 发布 `Active`）或用户手动关闭开关（`clearStatus()`）。

### 4.3 已存在的差异（不作为验收通过）

1. **诊断文本是静态字段**：`lastDiagnostic` 只有一次写入，后续成功创建会清空为 `""`；失败态下若又发生别的操作，展开项可能显示与标题不匹配的旧内容。
2. **无 `canHostTasks`**：见 3.2，代码里没有该开关，无法从能力层禁止某类任务上副屏。
3. **落点校验只认任务栈归属**：`am stack list` / `dumpsys activity activities` 都读不到时按失败处理（宁可降级也不假运行）；个别 ROM 的 dump 格式差异会导致误判为失败。

---

## 5. 明确不做的部分

以下看起来「顺手就能加」，但当前**刻意不做**，改动前请先读理由：

1. **不做副屏像素级截图的替代通道**（MediaProjection 镜像副屏 / SurfaceControl 截屏）。
   理由：镜像需要前台服务与额外的屏幕捕获授权弹窗，且镜像出来的画面没有「这是哪块屏」的自证，容易再次滑向「拿主屏冒充副屏」；当前选择是「拿不到就明说，只用树」。
2. **不用主屏截图代替副屏截图**。理由见第 2 节，这是假运行。
3. **不做副屏上的像素级定位**（模板匹配 / OCR / 视觉找控件）。理由：树缺失时宁可失败降级，也不引入一套无法解释的猜测坐标；误点不可逆按钮的代价高于任务失败。
4. **不自动安装 Shizuku、不引导 root**。理由：属于用户设备安全边界，应用内只做状态提示与跳转，不代替用户决策。
5. **不做多副屏、自定义分辨率/DPI**。理由：`OVERLAY_SPEC` 是单一固定值；多块模拟屏会让「新出现的 displayId」产生歧义，`ensure()` 的 diff 逻辑会失效。
6. **不做跨屏镜像/投屏**。`present` 的语义是「在主屏重新拉起同一应用」，不是把副屏画面搬过去；应用状态跨 display 共享，但画面与焦点不共享。
7. **不做失败自动重试**。一次创建/写入失败就降级主屏，避免在厂商禁用模拟显示的设备上反复写入设置、反复弹系统提示。
8. **不做副屏常驻**。副屏跟随任务生命周期，任务结束即删除；进程被强杀留下的残留由下次启动的 `cleanupGhost()` 收拾，而不是常驻一个守护进程。

---

## 6. 文件与目录结构

```
app/src/main/java/com/phoneagent/
├── device/screen/VirtualDisplayAgent.kt        # 创建/起应用/输入/截图/销毁 + 四态状态机
├── device/a11y/AgentAccessibilityService.kt    # captureScreenOnDisplay：跨 display 读元素树
├── device/ops/DeviceOpsChannel.kt              # virtual_display op 分发（模型直调）
├── device/capability/DeviceCapabilityReader.kt # virtualDisplaySupported = shizukuReady()
├── device/capability/DeviceCapability.kt       # VIRTUAL_DISPLAY 能力码与提示文案
├── domain/model/CapabilitySpec.kt              # 「副屏操作」能力声明（自由模式）
├── engine/AgentEngine.kt                       # prepare / observe / degrade / release
├── engine/execution/ExecutionCore.kt           # executeOnVirtualDisplay：动作 → input -d
├── engine/loop/ObservationPhase.kt             # 副屏模式不因无障碍掉线中断
├── engine/prompt/PromptBodies.kt               # 工具表里的 virtual_display 说明
├── data/prefs/AppSettings.kt                   # virtualDisplayEnabled 开关
├── ui/settings/SettingsAgent.kt                # 开关 + 状态块 + 诊断展开
└── PhoneAgentApp.kt                            # 启动时 cleanupGhost

provider/src/main/java/com/phoneagent/provider/shell/ShizukuManager.kt
                                                # shell 通道、stderr 收集、waitForExit
```

文档目录（`docs/`）当前结构：

```
docs/
├── virtual-display-design.md        # 本文
├── assistant-role-contract.md
├── brand-vi.md
├── build-modules.md
├── eta-ai-tools-and-prompts-comparison.md
├── eta-code-level.md
├── eta-implementation-review.md
├── nightly-optimization-2026-11-06.md
├── release-pipeline.md
├── task-notice-contract.md
├── ui-refresh-plan.md
├── hpa-trainer/
├── refactor/
└── ui-shots/
```

---

## 7. 已知风险（只记录，本轮不改）

- **`readText()` 没有超时**：`ShizukuManager.executeShell()` 仍是「先 `reader.readText()` 读完 stdout，再用 `waitForExit()` 轮询退出」。如果子进程或它派生的孙进程长期持有 stdout 不关闭，读线程会一直阻塞，`SHELL_TIMEOUT_MS = 30_000` 只在拿到 EOF 之后才生效。
  这类命令在副屏链路里已经改成「以回读为准」，但阻塞本身没消除。修复需要单独一轮：给读线程加超时/可取消，并补「stdout 不关闭」的用例；本轮不动，避免和已验证的回读路径混在一起。
