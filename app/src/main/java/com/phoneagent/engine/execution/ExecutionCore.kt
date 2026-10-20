package com.phoneagent.engine.execution

import com.phoneagent.core.ai.GlmDefaults
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.device.shell.AdbBootstrapTransport
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.device.shell.TermuxBridge
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.domain.rules.ShellCommands
import com.phoneagent.domain.rules.ShellCommands.Motion
import com.phoneagent.engine.VisionConfig
import com.phoneagent.engine.context.ContextInjector
import com.phoneagent.engine.isSuccess
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.engine.perception.effectiveLabel
import com.phoneagent.engine.runtime.EngineLimits.RELOCATE_BEFORE_TYPES
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 引擎执行核心（重构批次 7b）：把 AI 决策出的动作落到端侧——
 * 带验证执行（executeWithVerify 及其异常安全包装）、文档写入、shell 动作（真实通道/无障碍翻译）、
 * 无障碍端点直调、shell 通道选择与执行、光标回放、按键与滑动端点。
 * 从 AgentEngine 原样迁入，行为不变。
 *
 * 注意：目标定位与坐标换算已收口到 [IntentResolver]（resolveAction / relocateOnLatest / nodeSelectorOf），
 * 本类不做任何重复定位逻辑。
 */
internal class ExecutionCore(
    private val appContext: android.content.Context,
    private val settings: AppSettings,
    private val shizukuManager: ShizukuManager?,
    private val adbTransport: AdbBootstrapTransport?,
    private val termuxBridge: TermuxBridge?,
    private val documentEngine: com.phoneagent.feature.document.DocumentEngine?,
    private val intentResolver: IntentResolver,
    private val appNameResolver: AppNameResolver,
    private val store: EngineStore,
    private val ui: UiBridge,
    private val ctx: ContextInjector,
    /** 引擎钩子：执行耗时埋点（TelemetryRecorder.recordExecMs） */
    private val recordExecMs: (Long) -> Unit,
    /** 引擎钩子：重新观察当前页面（AgentEngine.observe，capture_tree 端点用） */
    private val observe: suspend () -> ScreenSnapshot,
) {
    suspend fun executeWithVerify(action: AgentAction, snapshot: ScreenSnapshot): com.phoneagent.engine.execution.VerifyResult {
        val execT0 = System.nanoTime()
        // 规范化文档动作词汇
        val type = ActionType.ALIAS[action.type] ?: action.type

        // 文档写入动作：不依赖屏幕/无障碍，直接把内容落盘（结果在 Agent 页预览）
        if (type == ActionType.WRITE_DOC) {
            return executeWriteDoc(action).also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        // SHELL 动作优先独立处理：Shizuku 可用时无需无障碍服务即可执行
        if (type == ActionType.SHELL) {
            return executeShellAction(action).also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        // 无障碍端点直调（自由模式专属）：绕过语义策略，按 AI 指定的端点与参数直接落到底层接口
        if (type == ActionType.A11Y_CALL) {
            return executeA11yEndpoint(action, snapshot).also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        // 幂等保护（v2.2 5.4）：副作用意图（提交/发送/下单/支付/删除）已由页面证明完成 → 跳过，防重复副作用与误触
        if (EngineRules.isFinalSubmit(action, type) && EngineRules.idempotencyDone(snapshot)) {
            return com.phoneagent.engine.execution.VerifyResult(true, "检测到页面已含完成证据（如「提交成功」），跳过重复副作用操作", "", "").also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        val service = AgentAccessibilityService.instance ?: return com.phoneagent.engine.execution.VerifyResult(false, "无障碍服务不可用", "", "").also {
            recordExecMs((System.nanoTime() - execT0) / 1_000_000)
        }
        val executor = ActionExecutor(service)
        val verifier = VerifiedClickExecutor()

        // 解析动作目标：定位口径的唯一入口在 IntentResolver（原引擎内的 resolveTarget/resolvePoint 已并入）
        val resolved = intentResolver.resolveAction(action, snapshot)
        var target = resolved.element
        var x = resolved.x
        var y = resolved.y
        // 执行前重定位（与 ClickRunner 同一纪律）：输入/滚动这些动作此前直接拿"观察那一刻"的元素坐标，
        // 中间隔着一次 AI 往返（可能数秒），键盘顶起页面、列表滚动之后坐标就指到别处了。
        // 重定位只按 viewId/文字 + 最近距离（[IntentResolver.relocateOnLatest] 刻意不用遍历序号，跨快照不稳定）；
        // 重定位不到就沿用原坐标，不改变既有行为。
        // 指纹基准同步换成"此刻"的页面：否则这期间页面自身的漂移会被当成"动作已生效"。
        var baseline = snapshot
        if (type in RELOCATE_BEFORE_TYPES) {
            val fresh = runCatching { service.captureScreen() }.getOrNull()?.takeUnless { it.missingAccessibility }
            if (fresh != null && fresh.packageName == snapshot.packageName) {
                baseline = fresh
                intentResolver.relocateOnLatest(resolved.element, snapshot, fresh)?.let { relocated ->
                    target = relocated
                    x = relocated.centerX
                    y = relocated.centerY
                    ui.log(
                        AgentLog.Level.INFO,
                        "执行前重定位：${relocated.effectiveLabel() ?: relocated.className} " +
                            "bounds=(${relocated.left},${relocated.top})-(${relocated.right},${relocated.bottom})" +
                            "（快照坐标为 (${resolved.x}, ${resolved.y})）",
                    )
                }
            }
        }

        val result = when (type) {
            ActionType.CLICK, ActionType.TAP ->
                // 点击走专门的流水线：活节点直点 → 手势点最新位置 → 快照坐标 → 滚动查找，
                // 每步分层确认（控件自身状态 → 整页指纹），失败原因里带前台应用与缺失控件
                ClickRunner(service, executor, intentResolver) { msg -> ui.log(AgentLog.Level.INFO, msg) }
                    .run(action, snapshot, longClick = false)
            ActionType.LONG_CLICK, ActionType.LONG_PRESS ->
                ClickRunner(service, executor, intentResolver) { msg -> ui.log(AgentLog.Level.INFO, msg) }
                    .run(action, snapshot, longClick = true)
            ActionType.SWIPE -> {
                // swipe 常为纯手势（无 target），缺少坐标时按方向给起点：
                // 横向仍以屏幕中心为起点；竖向起点下移到屏幕高度的 SWIPE_START_Y_RATIO 倍，
                // 避开顶部 1/3 的播放器/悬浮按钮热点区，降低误触中心热点控件的风险。
                // 位移量仍保持「默认整屏」不变（效率优先）。
                val horizontal = action.direction == "left" || action.direction == "right"
                val px = x ?: (ctx.screenWidth() / 2)
                val py = y ?: if (horizontal) (ctx.screenHeight() / 2) else verticalSwipeStartY()
                val dist = action.distancePx
                    ?: if (horizontal) ctx.screenWidth() else ctx.screenHeight()
                val (ex, ey) = swipeEndpoints(px, py, action.direction, dist)
                verifier.executeAndVerify(baseline) { executor.swipe(px, py, ex, ey, action.durationMs ?: 400).isSuccess() }
            }
            ActionType.SWIPE_UP -> {
                val px = x ?: (ctx.screenWidth() / 2)
                val py = y ?: verticalSwipeStartY()
                verifier.executeAndVerify(snapshot) { executor.swipe(px, py, px, (py - ctx.screenHeight()).coerceIn(0, ctx.screenHeight() - 1)).isSuccess() }
            }
            ActionType.SWIPE_DOWN -> {
                val px = x ?: (ctx.screenWidth() / 2)
                val py = y ?: verticalSwipeStartY()
                verifier.executeAndVerify(snapshot) { executor.swipe(px, py, px, (py + ctx.screenHeight()).coerceIn(0, ctx.screenHeight() - 1)).isSuccess() }
            }
            ActionType.SWIPE_LEFT -> {
                val px = x ?: (ctx.screenWidth() / 2)
                val py = y ?: (ctx.screenHeight() / 2)
                verifier.executeAndVerify(snapshot) { executor.swipe(px, py, (px - ctx.screenWidth()).coerceIn(0, ctx.screenWidth() - 1), py).isSuccess() }
            }
            ActionType.SWIPE_RIGHT -> {
                val px = x ?: (ctx.screenWidth() / 2)
                val py = y ?: (ctx.screenHeight() / 2)
                verifier.executeAndVerify(snapshot) { executor.swipe(px, py, (px + ctx.screenWidth()).coerceIn(0, ctx.screenWidth() - 1), py).isSuccess() }
            }
            ActionType.SCROLL, ActionType.SCROLL_TO -> verifier.executeAndVerify(baseline, target) { executor.scroll(target, action.direction ?: action.text ?: "up").isSuccess() }
            ActionType.TYPE_TEXT -> {
                if ((x == null || y == null) && target == null) com.phoneagent.engine.execution.VerifyResult(false, "当前页面(${snapshot.packageName ?: "未知应用"})没有可输入控件(${action.target?.value ?: "坐标"})：目标应用若未打开，先 launch 到该应用，禁止在页面外凭空输入", "", "")
                else verifier.executeAndVerify(baseline, target) { executor.typeText(action.text ?: "", target, x, y).isSuccess() }
            }
            ActionType.KEY -> handleKey(executor, action.keycode ?: "BACK")
            ActionType.LAUNCH -> {
                val targetPkg = action.packageName
                if (targetPkg.isNullOrBlank()) return com.phoneagent.engine.execution.VerifyResult(false, "launch 缺少包名", "", "").also {
                    recordExecMs((System.nanoTime() - execT0) / 1_000_000)
                }
                val launched = executor.launchApp(targetPkg).isSuccess()
                if (!launched) return com.phoneagent.engine.execution.VerifyResult(false, "启动应用失败: $targetPkg", "", "").also {
                    recordExecMs((System.nanoTime() - execT0) / 1_000_000)
                }
                // launch 动作不依赖包名检测（无障碍服务在某些版本上无法正确报告前台切换），
                // 只要启动指令发出就视为成功，让 AI 在下一轮观察新页面。
                // 等待改为自适应：启动后轮询页面，目标应用页面（包名出现或页面指纹变化）一到位立即继续，
                // 最长仍等 LAUNCH_SETTLE_MS（800ms）——启动快的场景不再干等，慢的保持原行为。
                awaitLaunched(service, targetPkg, snapshot)
                com.phoneagent.engine.execution.VerifyResult(true, "已启动应用: $targetPkg", snapshot.packageName ?: "", targetPkg)
            }
            ActionType.LAUNCH_ASSISTANT -> {
                // 唤起系统语音助手：description 原样透传（AI 能区分"已传话 / 已填入 / 仅拉起"）；
                // 仅"本机探测不到语音助手"才是 Failure，走既有连续失败介入机制
                val r = executor.launchAssistant(action.text)
                if (r is com.phoneagent.device.a11y.ActionExecutor.Result.Failure) {
                    // 无障碍多入口拉起全部失败 → shell 兜底：am start 不受后台启动限制，
                    // 在不少机型上是无障碍 startActivity 被静默拦截时唯一能唤醒的通道。
                    // 唤醒成功后回到无障碍传话（传话只能靠无障碍，与 AssistantStrategy 的双通道分工一致）
                    val shellWoke = shellWakeAssistant(executor)
                    if (!shellWoke) {
                        com.phoneagent.engine.execution.VerifyResult(
                            false,
                            "${r.reason}；shell 兜底（am start）也未能唤醒语音助手。" +
                                "请检查设备的默认语音助手设置，或改用其他方式完成任务",
                            "", "",
                        )
                    } else {
                        delay(600)
                        val desc = enterSendIfFillOnly(executor.handoffToAssistant(action.text))
                        delay(800)
                        com.phoneagent.engine.execution.VerifyResult(
                            true, "经 shell 兜底唤醒语音助手；$desc", snapshot.packageName ?: "", "",
                        )
                    }
                } else {
                    val desc = enterSendIfFillOnly((r as com.phoneagent.device.a11y.ActionExecutor.Result.Success).description)
                    delay(800)
                    com.phoneagent.engine.execution.VerifyResult(true, desc, snapshot.packageName ?: "", "")
                }
            }
            ActionType.READ_NOTIFS -> {
                // 读取通知：数据来自通知监听缓存（纯本地）。监听未授权时明确告知如何开启
                val text = com.phoneagent.feature.notify.NotificationFeed.readText()
                if (text == null) {
                    com.phoneagent.engine.execution.VerifyResult(
                        false,
                        "通知监听未开启，读不到任何通知。请引导用户到系统设置开启本应用的通知使用权后重试",
                        snapshot.packageName ?: "", "",
                    )
                } else {
                    com.phoneagent.engine.execution.VerifyResult(true, text, snapshot.packageName ?: "", "")
                }
            }
            ActionType.CLEAR_NOTIFS -> {
                // 清理通知：ongoing（常驻）不动；返回成功条数，-1 表示监听未开启
                val cleared = com.phoneagent.feature.notify.NotificationFeed.cancelAll()
                when {
                    cleared < 0 -> com.phoneagent.engine.execution.VerifyResult(
                        false,
                        "通知监听未开启，无法清理通知。请引导用户到系统设置开启本应用的通知使用权后重试",
                        snapshot.packageName ?: "", "",
                    )
                    else -> com.phoneagent.engine.execution.VerifyResult(
                        true, "已清理 $cleared 条通知（常驻通知保留）", snapshot.packageName ?: "", "",
                    )
                }
            }
            ActionType.OPEN -> executeNoVerify(executor) {
                // 优先直接深链 uri；否则按软件页面直达索引(app+page)解析直达方式
                val uri = action.uri
                if (!uri.isNullOrBlank()) {
                    // uri 非空时 app 字段的语义 = "用哪个应用打开"（应用名/包名；解析不出则交回系统默认）
                    executor.openUri(uri, action.app?.let { appNameResolver.resolve(it) }).isSuccess()
                } else {
                    val entry = com.phoneagent.domain.model.AppPageIndex.resolve(action.app, action.page)
                    when {
                        entry?.uri != null -> executor.openUri(entry.uri)
                        entry?.intentAction != null -> executor.openSettingsAction(entry.intentAction)
                        entry?.packageName != null -> executor.launchApp(entry.packageName)
                        else -> com.phoneagent.device.a11y.ActionExecutor.Result.Failure(
                            "未在软件页面索引中找到 ${action.app ?: "未知软件"} 页面${action.page?.toString() ?: "（未指定页码）"}",
                        )
                    }.isSuccess()
                }
            }
            ActionType.BACK -> executeNoVerify(executor) { executor.back().isSuccess() }
            ActionType.HOME -> executeNoVerify(executor) { executor.home().isSuccess() }
            ActionType.RECENTS -> executeNoVerify(executor) { executor.recents().isSuccess() }
            ActionType.WAIT -> { delay(action.timeoutMs ?: action.durationMs ?: 1000); com.phoneagent.engine.execution.VerifyResult(true, "等待完成", "", "") }
            ActionType.REFRESH -> com.phoneagent.engine.execution.VerifyResult(
                false,
                VerifyRules.NO_A11Y_REFRESH + "：请改用 refresh 意图点击页面刷新按钮，或下滑手势",
                "",
                "",
            )
            else -> com.phoneagent.engine.execution.VerifyResult(false, "未知动作", "", "")
        }
        recordExecMs((System.nanoTime() - execT0) / 1_000_000)
        return result
    }

    /**
     * executeWithVerify 的异常安全包装。
     *
     * 执行链路要经无障碍服务跨进程调用、Shizuku/Termux IPC 与 shell 解析，服务被系统回收或
     * 通道断开时会直接抛异常。异常若穿透主循环会连带终止整个任务，所以这里统一降级为「失败验证结果」，
     * 复用既有的「连续 3 次失败 → 请求用户介入」机制，把问题交回用户而不是无声崩掉。
     */
    suspend fun safeExecute(action: AgentAction, snapshot: ScreenSnapshot): com.phoneagent.engine.execution.VerifyResult {
        return try {
            executeWithVerify(action, snapshot)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ui.log(AgentLog.Level.WARN, "动作执行异常：${e.message ?: e.javaClass.simpleName}")
            com.phoneagent.engine.execution.VerifyResult(
                false, "执行异常：${e.message ?: e.javaClass.simpleName}（无障碍服务可能已断开）", "", "",
            )
        }
    }

    /**
     * 执行文档写入动作：把 AI 产出的正文落盘，结果随后在 Agent 页任务流里预览。
     * @return 写成功的 VerifyResult；文档通道未启用时返回失败
     */
    private suspend fun executeWriteDoc(action: AgentAction): com.phoneagent.engine.execution.VerifyResult {
        val engine = documentEngine ?: return com.phoneagent.engine.execution.VerifyResult(false, "文档通道未启用", "", "")
        val content = action.text ?: return com.phoneagent.engine.execution.VerifyResult(false, "文档内容为空", "", "")
        val fileName = action.summary ?: ""
        // 直接落盘（内容已由 AI 决策产出，无需再次生成）
        val written = engine.writeDocument(content, fileName)
        return if (written.isBlank()) {
            com.phoneagent.engine.execution.VerifyResult(false, engine.error.value.ifBlank { "文档写入失败" }, "", "")
        } else {
            com.phoneagent.engine.execution.VerifyResult(true, "文档已生成，可在 Agent 页查看：$written", "", "")
        }
    }

    /**
     * 执行 shell 动作。
     * - 真实 shell 通道可用（Shizuku 或无线 ADB 已连接）：直接执行原 Shizuku 命令集
     *   （ShellCommands 解析），查询类命令输出回传 AI。
     * - 无真实 shell 通道：禁止执行 shell，把 AI 输出的友好命令翻译为等价的无障碍动作执行，
     *   保证任务在无 Shizuku/ADB 权限时仍能推进，且绝不真正调用 shell。
     */
    suspend fun executeShellAction(action: AgentAction): com.phoneagent.engine.execution.VerifyResult {
        val cmd = action.command ?: return com.phoneagent.engine.execution.VerifyResult(false, "shell 命令为空", "", "")
        // Termux 工具链命令（curl / python / jq 等）：adb shell 里没有这些工具，
        // 按命令名判定直接交给 Termux，不受 executionChannel 偏好影响
        if (ShellRules.isTermuxToolCommand(cmd)) {
            val bridge = termuxBridge
            if (bridge == null || !bridge.isAvailable()) {
                return com.phoneagent.engine.execution.VerifyResult(
                    false, "该命令需要 Termux 通道（curl/python 等工具），但 Termux 未安装或未授权", "", "",
                )
            }
            val resolved = ShellCommands.resolve(cmd, ctx.screenWidth(), ctx.screenHeight())
                ?: if (action.aiAuthored) cmd
                else return com.phoneagent.engine.execution.VerifyResult(false, "命令无法解析: $cmd", "", "")
            triggerCursorForShell(resolved)
            return finishShellResult(bridge.executeShell(resolved), action.uri)
        }
        // 无真实 shell 通道：硬性禁止 shell，翻译为无障碍动作
        if (!shellChannelAvailable()) {
            return executeShellViaAccessibility(cmd)
        }
        val resolved = ShellCommands.resolve(cmd, ctx.screenWidth(), ctx.screenHeight())
            // 自由模式：命令是 AI 亲自写的，端侧认不出来也必须原样交下去——
            // 友好命令表只是端侧词汇，不是权限边界，在这里把它当白名单会把自由模式变成均衡模式
            ?: if (action.aiAuthored) cmd
            else return com.phoneagent.engine.execution.VerifyResult(false, "未知 shell 命令: $cmd", "", "")
        triggerCursorForShell(resolved)
        return runRealShell(resolved, action.uri)
    }

    /**
     * 无障碍端点直调（自由模式专属）。
     *
     * 绕开语义策略，按 AI 指定的端点名与参数直接落到 [ActionExecutor] / 无障碍服务的底层接口。
     * 端点白名单与必填参数由 ActionPolicy.a11yEndpoints 唯一定义（转译层已校验端点名与必填项），
     * 这里只把字符串参数翻成对应调用，并把**真实结果**如实回报给 AI——感知类端点绝不能假装成功，
     * 否则 AI 会以为"已经看过了"，据此编造页面内容。
     */
    suspend fun executeA11yEndpoint(
        action: AgentAction,
        snapshot: ScreenSnapshot,
    ): com.phoneagent.engine.execution.VerifyResult {
        val name = action.endpoint?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) {
            return com.phoneagent.engine.execution.VerifyResult(false, "a11y 调用缺少 endpoint", "", "")
        }
        if (ActionPolicy.endpointOf(name) == null) {
            return com.phoneagent.engine.execution.VerifyResult(
                false,
                "未知无障碍端点: $name。可用端点：${ActionPolicy.a11yEndpoints.joinToString("/") { it.name }}",
                "", "",
            )
        }
        val args = action.args.orEmpty()
        val fail = { reason: String -> com.phoneagent.engine.execution.VerifyResult(false, reason, "", "") }
        val ok = { msg: String -> com.phoneagent.engine.execution.VerifyResult(true, msg, "", "") }
        fun intArg(key: String): Int? = args[key]?.trim()?.toIntOrNull()
        fun strArg(key: String): String? = args[key]?.trim()?.takeIf { it.isNotBlank() }
        /** 按文字/语义 id 在快照元素树里找控件（坐标兜底与滚动定位共用） */
        fun elementOf(raw: String) = snapshot.elements.firstOrNull {
            it.semanticId == raw || (!it.text.isNullOrBlank() && it.text.contains(raw))
        }

        // 感知类端点不经过无障碍动作通道，先单独处理
        when (name) {
            "can_screenshot" -> {
                val service = AgentAccessibilityService.instance ?: return fail("无障碍服务不可用，无法查询截图能力")
                val yes = service.canScreenshot()
                return ok(if (yes) "截图能力：可用" else "截图能力：不可用（需 Android 11+ 且无障碍服务已声明截图能力）")
            }
            "screenshot" -> {
                val service = AgentAccessibilityService.instance ?: return fail("无障碍服务不可用，无法截图")
                if (!service.canScreenshot()) return fail("本机当前不具备截图能力，无法截图")
                val bmp = service.takeScreenshotBitmap() ?: return fail("截图失败或超时")
                val size = "${bmp.width}×${bmp.height}"
                bmp.recycle()
                return ok("已截取当前屏幕位图（$size）。位图本身不在你的上下文里：要看页面内容请读元素树，网页内容请用 browse_read。")
            }
            "capture_tree" -> {
                val fresh = observe()
                return ok("已重新抓取当前页面元素树：${fresh.packageName ?: "未知应用"}，共 ${fresh.elements.size} 个控件。")
            }
        }

        val service = AgentAccessibilityService.instance ?: return fail("无障碍服务不可用")
        val executor = ActionExecutor(service)
        fun wrap(r: ActionExecutor.Result): com.phoneagent.engine.execution.VerifyResult = when (r) {
            is ActionExecutor.Result.Success -> ok(r.description.ifBlank { "端点 $name 执行成功" })
            is ActionExecutor.Result.Failure -> fail(r.reason)
        }

        return when (name) {
            "click" -> wrap(executor.click(
                intArg("x") ?: return fail("click 缺少参数 x"),
                intArg("y") ?: return fail("click 缺少参数 y"),
            ))
            "long_click" -> wrap(executor.longClick(
                intArg("x") ?: return fail("long_click 缺少参数 x"),
                intArg("y") ?: return fail("long_click 缺少参数 y"),
                // 端点直调同样透传上层指定的按压时长（无则默认），与 long_press 动作口径一致
                action.durationMs,
            ))
            "click_node" -> {
                val raw = strArg("target") ?: return fail("click_node 缺少参数 target")
                // 「id:资源名」走 viewId 精确匹配；否则按文字匹配，并把快照中心点作为同字控件并列时的兜底线索
                val selector = if (raw.startsWith("id:", ignoreCase = true)) {
                    NodeSelector(viewId = raw.substringAfter(':').trim())
                } else {
                    NodeSelector(label = raw, centerHint = elementOf(raw)?.let { it.x to it.y })
                }
                val long = args["long_click"]?.trim()?.let { it.equals("true", true) || it == "1" } == true
                wrap(executor.clickNode(selector, longClick = long))
            }
            "swipe" -> wrap(executor.swipe(
                intArg("x1") ?: return fail("swipe 缺少参数 x1"),
                intArg("y1") ?: return fail("swipe 缺少参数 y1"),
                intArg("x2") ?: return fail("swipe 缺少参数 x2"),
                intArg("y2") ?: return fail("swipe 缺少参数 y2"),
                (intArg("duration_ms") ?: 400).toLong(),
            ))
            "swipe_direction" -> {
                val direction = strArg("direction") ?: return fail("swipe_direction 缺少参数 direction")
                val (ex, ey) = swipeEndpoints(ctx.screenWidth() / 2, ctx.screenHeight() / 2, direction, intArg("distance_px"))
                wrap(executor.swipe(ctx.screenWidth() / 2, ctx.screenHeight() / 2, ex, ey, 400))
            }
            "scroll" -> {
                val direction = strArg("direction") ?: return fail("scroll 缺少参数 direction")
                wrap(executor.scroll(strArg("target")?.let { elementOf(it) }, direction))
            }
            "scroll_container" -> wrap(executor.scrollContainer(strArg("direction") ?: return fail("scroll_container 缺少参数 direction")))
            "global_action" -> wrap(executor.globalAction(intArg("action") ?: return fail("global_action 缺少参数 action")))
            "back" -> wrap(executor.back())
            "home" -> wrap(executor.home())
            "recents" -> wrap(executor.recents())
            "launch_app" -> wrap(executor.launchApp(strArg("package") ?: return fail("launch_app 缺少参数 package")))
            "open_uri" -> wrap(executor.openUri(
                strArg("uri") ?: return fail("open_uri 缺少参数 uri"),
                strArg("package"),
            ))
            "open_settings_action" -> wrap(executor.openSettingsAction(strArg("action") ?: return fail("open_settings_action 缺少参数 action")))
            "type_text" -> {
                val text = args["text"]?.takeIf { it.isNotEmpty() } ?: return fail("type_text 缺少参数 text")
                val elem = strArg("target")?.let { elementOf(it) }
                wrap(executor.typeText(text, elem, elem?.x, elem?.y))
            }
            else -> fail("端点 $name 未实现")
        }
    }

    /**
     * shell 通道也要让用户看到光标：从解析后的完整命令里反查"这一下动了什么"，
     * 按形态回放——点击飞向落点、长按停留呼吸、滑动沿轨迹推进。
     *
     * 三种形态必须分开认：只认"点"的话，长按会被画成点击、滑动干脆没有光标，
     * 用户看到的就是"列表自己跳了一下"。按键、文本、raw 透传等非触摸命令静默跳过。
     */
    private suspend fun triggerCursorForShell(resolved: String) {
        when (val motion = ShellCommands.parseMotion(resolved)) {
            is Motion.Tap ->
                com.phoneagent.overlay.CursorOverlayService.point(motion.x, motion.y)
            is Motion.LongPress ->
                com.phoneagent.overlay.CursorOverlayService.longPress(motion.x, motion.y, motion.holdMs)
            is Motion.Swipe ->
                com.phoneagent.overlay.CursorOverlayService.swipe(
                    motion.x1, motion.y1, motion.x2, motion.y2, motion.durationMs,
                )
            null -> Unit
        }
    }

    /** 是否具备真实 shell 通道：按执行通道偏好判定（AUTO=Shizuku→ADB引导会话→Termux | SHIZUKU=仅Shizuku | TERMUX=仅Termux；旧"ADB"偏好归一 AUTO） */
    suspend fun shellChannelAvailable(): Boolean =
        // 通道判据与真实执行共用同一份（ShellRules.pickChannel），避免"这里说可用、那里走不通"
        ShellRules.pickChannel(
            settings.settings.first().executionChannel,
            adbTransport?.isConnected() == true,
            shizukuManager?.isAvailable() == true,
            termuxBridge?.isAvailable() == true,
        ) != ShellRules.Channel.NONE

    /** 经 ADB 引导会话执行 shell（内置 Shizuku server 拉起前后均可执行）：把字符串结果桥接成 Shizuku 的结果类型，供两条执行链共用 */
    private suspend fun runAdbShell(cmd: String): ShizukuManager.ShellResult {
        val out = adbTransport?.executeShell(cmd)
        return if (out == null) ShizukuManager.ShellResult.Failure("Shizuku 引导会话执行 shell 失败")
        else ShizukuManager.ShellResult.Success(output = out)
    }

    /**
     * 按执行通道偏好选择真实 shell 通道执行命令并回传输出。
     * AUTO 顺序：Shizuku binder → ADB 引导会话（内置 Shizuku 的启动机制）→ Termux。
     * 注意 Termux 是**普通应用权限**的 Linux 环境，只适合 curl/python/文本处理等工具链命令，
     * 系统命令（am/pm/settings）会失败——该边界在提示词里对 AI 显式声明。
     */
    suspend fun runRealShell(
        resolved: String,
        baseUrl: String? = null,
    ): com.phoneagent.engine.execution.VerifyResult {
        val preference = settings.settings.first().executionChannel
        val channel = ShellRules.pickChannel(
            preference,
            adbTransport?.isConnected() == true,
            shizukuManager?.isAvailable() == true,
            termuxBridge?.isAvailable() == true,
        )
        val result: ShizukuManager.ShellResult = when (channel) {
            ShellRules.Channel.ADB -> runAdbShell(resolved)
            // 通道状态与实际调用之间可能发生 binder death / 桥断开，用安全调用兜住竞态窗口，
            // 避免 !! 在同一窗口内抛 NPE 穿透主循环（NONE 已另有分支，但状态可能在两次读取间变化）
            ShellRules.Channel.SHIZUKU -> shizukuManager?.executeShell(resolved)
                ?: ShizukuManager.ShellResult.Failure("Shizuku 通道当前不可用")
            ShellRules.Channel.TERMUX -> termuxBridge?.executeShell(resolved)
                ?: ShizukuManager.ShellResult.Failure("Termux 通道当前不可用")
            ShellRules.Channel.NONE -> return com.phoneagent.engine.execution.VerifyResult(
                false, ShellRules.noChannelReason(preference), "", "",
            )
        }
        return finishShellResult(result, baseUrl)
    }

    /**
     * 按执行通道偏好直接执行一条端侧 shell 并返回原始结果（**不写进 AI 上下文**）。
     *
     * 与 runRealShell 共用同一套通道选择，但产物不进「上一步 shell 输出」，
     * 因此适合任务开始/结束时由端侧自己发起的系统命令（如隐藏状态栏）——
     * 这类命令的返回值对 AI 没有意义，不该占用决策上下文。
     * AUTO 顺序：Shizuku binder → ADB 引导会话 → Termux。
     */
    suspend fun execShellViaChannel(cmd: String): ShizukuManager.ShellResult {
        val preference = settings.settings.first().executionChannel
        val channel = ShellRules.pickChannel(
            preference,
            adbTransport?.isConnected() == true,
            shizukuManager?.isAvailable() == true,
            termuxBridge?.isAvailable() == true,
        )
        return when (channel) {
            ShellRules.Channel.ADB -> runAdbShell(cmd)
            // 同 runRealShell：通道可能在 pickChannel 与实际调用之间失效，安全调用兜住竞态
            ShellRules.Channel.SHIZUKU -> shizukuManager?.executeShell(cmd)
                ?: ShizukuManager.ShellResult.Failure("Shizuku 通道当前不可用")
            ShellRules.Channel.TERMUX -> termuxBridge?.executeShell(cmd)
                ?: ShizukuManager.ShellResult.Failure("Termux 通道当前不可用")
            ShellRules.Channel.NONE -> ShizukuManager.ShellResult.Failure(
                ShellRules.noChannelReasonShort(preference),
            )
        }
    }

    /**
     * shell 兜底唤醒语音助手：无障碍 startActivity 被系统静默拦截时，
     * shell 通道不受后台启动限制。三级尝试：语音指令 am start → 默认助理 am start →
     * 长按电源键（ROM 设置了"长按电源键唤醒语音助手"时直达；未设置会弹电源菜单，
     * 因此该级必须验证到助手窗口才算成功）。最后用无障碍窗口验证
     * （探测不到包名时视为尽力而为的成功，与「仅拉起」语义一致）。
     */
    private suspend fun shellWakeAssistant(executor: ActionExecutor): Boolean {
        val probePkg = runCatching {
            com.phoneagent.device.a11y.VoiceAssistantDetector(appContext).probe()?.packageName
        }.getOrNull()
        // requireWindow：该入口拉起后必须验证到助手窗口才算成功。
        // am start 失败多半是窗口没来但指令本身被接受，沿用尽力而为语义；
        // 长按电源键在未配置"唤醒助手"的 ROM 上会弹电源菜单——必须验证窗口，否则会误报成功
        val attempts = listOf(
            ShellAttempt("am start -a android.intent.action.VOICE_COMMAND", requireWindow = false),
            ShellAttempt("am start -a android.intent.action.ASSIST", requireWindow = false),
            // KEYCODE_POWER=26 长按：ROM 设置了"长按电源键唤醒语音助手"时直达助手
            ShellAttempt("input keyevent --longpress 26", requireWindow = true),
        )
        var anyAccepted = false
        for (attempt in attempts) {
            val out = runCatching { execShellViaChannel(attempt.cmd) }.getOrNull()
            val accepted = when (out) {
                is ShizukuManager.ShellResult.Success ->
                    !out.output.contains("error", ignoreCase = true) &&
                        !out.output.contains("exception", ignoreCase = true)
                else -> false
            }
            if (accepted) {
                // 拉起指令已被 shell 接受：稍候再验证窗口，冷启动的厂商助手需要时间
                delay(1200)
                if (executor.assistantVisible(probePkg)) return true
                if (!attempt.requireWindow) anyAccepted = true
            }
        }
        // 指令被接受但窗口验证不到（探测包名与实际助手不一致等）：仍按尽力而为放行传话
        return anyAccepted
    }

    /** shell 唤醒尝试：一条命令 + 是否强制要求验证到助手窗口 */
    private data class ShellAttempt(val cmd: String, val requireWindow: Boolean)

    /**
     * 传话已填入文字但没点到发送时，用 shell 回车键补发（KEYCODE_ENTER=66）。
     * 厂商助手的发送键常是输入法上的回车/或图标无文字描述，无障碍找不到时，
     * 回车是最通用的发送兜底；指令未被 shell 接受时保留原说明让 AI/用户接手。
     */
    private suspend fun enterSendIfFillOnly(desc: String): String {
        if (!desc.contains("未找到发送按钮")) return desc
        val accepted = runCatching { execShellViaChannel("input keyevent 66") }.getOrNull()
            .let { it is ShizukuManager.ShellResult.Success }
        return if (accepted) "$desc（已补按回车尝试发送）" else desc
    }

    /** shell 结果收口：捕获输出供 AI 决策复用，并转成执行层可验证结果 */
    private suspend fun finishShellResult(
        result: ShizukuManager.ShellResult,
        baseUrl: String? = null,
    ): com.phoneagent.engine.execution.VerifyResult = when (result) {
        is ShizukuManager.ShellResult.Success -> {
            // 捕获输出：查询类命令回传 AI，指令类命令忽略
            store.lastShellOutput = ShellRules.renderShellOutput(result.output.trim(), baseUrl)
            store.pendingShellOutput = store.lastShellOutput
            delay(300)
            com.phoneagent.engine.execution.VerifyResult(true, "shell 执行成功", "", "")
        }
        is ShizukuManager.ShellResult.Failure -> {
            store.lastShellOutput = ""
            // 失败原因同样回显给用户：不然页面上只看到"未生效"，不知道为什么
            store.pendingShellOutput = result.reason
            com.phoneagent.engine.execution.VerifyResult(false, result.reason, "", "")
        }
    }

    /**
     * Shizuku 不可用时：禁止执行 shell。将 AI 输出的友好命令（tap/lp/sw/back/key/text/launch 等）
     * 翻译为等价的无障碍动作执行；无法翻译的命令返回失败，促使 AI 改用无障碍动作重决策。
     */
    private suspend fun executeShellViaAccessibility(cmd: String): com.phoneagent.engine.execution.VerifyResult {
        val service = AgentAccessibilityService.instance
            ?: return com.phoneagent.engine.execution.VerifyResult(false, "Shizuku 不可用且无障碍服务不可用", "", "")
        val executor = ActionExecutor(service)
        val w = ctx.screenWidth()
        val h = ctx.screenHeight()
        val fail = { reason: String -> com.phoneagent.engine.execution.VerifyResult(false, reason, "", "") }

        val parsed = ShellCommands.parse(cmd)
            ?: return fail("Shizuku 不可用，命令无法转为无障碍动作: $cmd")
        val (name, args) = parsed

        // 结果包装：成功提示已用无障碍代替 shell
        fun wrap(r: ActionExecutor.Result): com.phoneagent.engine.execution.VerifyResult = when (r) {
            is ActionExecutor.Result.Success -> com.phoneagent.engine.execution.VerifyResult(true, "已用无障碍代替 shell 执行: $cmd", "", "")
            is ActionExecutor.Result.Failure -> com.phoneagent.engine.execution.VerifyResult(false, r.reason, "", "")
        }

        // 坐标类动作统一处理
        suspend fun coordOp(block: suspend (Int, Int) -> ActionExecutor.Result): com.phoneagent.engine.execution.VerifyResult {
            val (x, y) = ShellCommands.coord(args, w, h) ?: return fail("Shizuku 不可用，坐标无效: $cmd")
            return wrap(block(x, y))
        }

        return when (name) {
            "tap" -> coordOp { x, y -> executor.click(x, y) }
            "lp", "long_press" -> coordOp { x, y -> executor.longClick(x, y) }
            "dt", "double_tap" -> coordOp { x, y ->
                val first = executor.click(x, y)
                if (first is ActionExecutor.Result.Success) {
                    // 两次点击之间插入间隔：零间隔会被系统识别成连点而非双击，间隔之后才稳定判为双击
                    delay(DOUBLE_TAP_INTERVAL_MS)
                    executor.click(x, y)
                } else {
                    first
                }
            }
            "su", "swipe_up" -> coordOp { x, y -> executor.swipe(x, y, x, (y - h * 0.25f).toInt().coerceAtLeast(0), 400) }
            "sd", "swipe_down" -> coordOp { x, y -> executor.swipe(x, y, x, (y + h * 0.25f).toInt().coerceAtMost(h), 400) }
            "sl", "swipe_left" -> coordOp { x, y -> executor.swipe(x, y, (x - w * 0.25f).toInt().coerceAtLeast(0), y, 400) }
            "sr", "swipe_right" -> coordOp { x, y -> executor.swipe(x, y, (x + w * 0.25f).toInt().coerceAtMost(h), y, 400) }
            "sw", "swipe" -> {
                val c = ShellCommands.coords(args, w, h)
                if (c == null) fail("Shizuku 不可用，滑动参数无效: $cmd")
                else wrap(executor.swipe(c[0], c[1], c[2], c[3], 400))
            }
            "back" -> wrap(executor.back())
            "home" -> wrap(executor.home())
            "recents" -> wrap(executor.recents())
            "key" -> when (args.trim().uppercase()) {
                "BACK" -> wrap(executor.back())
                "HOME" -> wrap(executor.home())
                "RECENTS", "RECENT", "APP_SWITCH" -> wrap(executor.recents())
                else -> fail("Shizuku 不可用，按键 $args 无法通过无障碍执行: $cmd")
            }
            "text", "type" -> {
                val t = args.trim().removeSurrounding("\"").removeSurrounding("'")
                if (t.isEmpty()) fail("Shizuku 不可用，输入内容为空: $cmd")
                else wrap(executor.typeText(t, null))
            }
            "launch" -> {
                val pkg = args.trim()
                if (pkg.isEmpty()) fail("Shizuku 不可用，缺少包名: $cmd")
                else wrap(executor.launchApp(pkg))
            }
            "am" -> {
                val intent = args.trim()
                val pkg = intent.substringBefore('/').substringBefore(':').trim()
                if (pkg.isEmpty()) fail("Shizuku 不可用，无法解析 Activity 包名: $cmd")
                else wrap(executor.launchApp(pkg))
            }
            // 裸包名（如 com.tencent.mm）→ 视为实际动作 launch，自动补全并启动应用
            else -> {
                if (cmd.contains('.') && !cmd.contains(" "))
                    wrap(executor.launchApp(cmd))
                else fail("Shizuku 不可用，命令无法通过无障碍执行: $cmd")
            }
        }
    }

    /** 计算滑动终点（纯逻辑，见 EngineRules.swipeEndpoints） */
    fun swipeEndpoints(x: Int, y: Int, direction: String?, distanceArg: Int?): Pair<Int, Int> =
        EngineRules.swipeEndpoints(x, y, direction, distanceArg, ctx.screenWidth(), ctx.screenHeight())

    /**
     * 竖向滑动起点的默认 y：屏幕高度的 [SWIPE_START_Y_RATIO] 倍。
     * 不取几何中心，是为了避开顶部 1/3 的播放器/悬浮按钮等中心热点区，降低误触。
     */
    private fun verticalSwipeStartY(): Int = (ctx.screenHeight() * SWIPE_START_Y_RATIO).toInt()

    /**
     * 启动应用后等待目标页面出现（自适应，最长 [LAUNCH_SETTLE_MS]）。
     * 复用感知层的 captureScreen 与 PageFingerprint：目标应用包名出现、或页面指纹相对启动前发生变化，
     * 即视为页面已到位、立即返回；超时仍未变化也照常返回，保持原「启动即成功」的语义不变。
     */
    private suspend fun awaitLaunched(
        service: AgentAccessibilityService,
        targetPkg: String,
        before: ScreenSnapshot,
    ) {
        val beforeFp = PageFingerprint.computeMeaningful(before)
        var waited = 0L
        while (waited < LAUNCH_SETTLE_MS) {
            delay(LAUNCH_POLL_MS)
            waited += LAUNCH_POLL_MS
            val now = runCatching { service.captureScreen() }.getOrNull() ?: continue
            if (now.missingAccessibility) continue
            if (now.packageName == targetPkg || PageFingerprint.computeMeaningful(now) != beforeFp) return
        }
    }

    private suspend fun handleKey(executor: ActionExecutor, keycode: String): com.phoneagent.engine.execution.VerifyResult {
        return when (keycode.uppercase()) {
            "BACK" -> executeNoVerify(executor) { executor.back().isSuccess() }
            "HOME" -> executeNoVerify(executor) { executor.home().isSuccess() }
            "RECENT", "RECENTS" -> executeNoVerify(executor) { executor.recents().isSuccess() }
            "ENTER" -> com.phoneagent.engine.execution.VerifyResult(
                false,
                "无障碍通道无法直接发送回车键：请改用点击页面上的发送/搜索按钮；若内置 Shizuku 可用可执行 input keyevent 66",
                "",
                "",
            )
            else -> com.phoneagent.engine.execution.VerifyResult(false, "未知按键 $keycode", "", "")
        }
    }

    private suspend fun executeNoVerify(executor: ActionExecutor, block: suspend () -> Boolean): com.phoneagent.engine.execution.VerifyResult {
        val ok = block()
        delay(500)
        return com.phoneagent.engine.execution.VerifyResult(ok, if (ok) "已执行" else "执行失败", "", "")
    }

    private companion object {
        /** 竖向滑动起点默认 y 占屏幕高度的比例：避开顶部 1/3 的播放器/悬浮按钮热点区 */
        const val SWIPE_START_Y_RATIO = 0.62f

        /** 双击两次点击之间的间隔（毫秒）：零间隔会被系统当成连点，间隔之后才稳定判为双击 */
        const val DOUBLE_TAP_INTERVAL_MS = 120L

        /** launch 后等待目标页面出现的最长时间（毫秒）：等于原固定等待，保证慢启动场景行为不变 */
        const val LAUNCH_SETTLE_MS = 800L

        /** launch 后轮询页面是否变化的间隔（毫秒） */
        const val LAUNCH_POLL_MS = 100L
    }
}
