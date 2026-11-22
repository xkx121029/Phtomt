// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber", "TooManyFunctions")
package com.phoneagent.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.phoneagent.domain.model.TaskOutcome
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.ui.MainActivity

/** 扩展函数里没有 `this@FloatingWindowService` 这个标签，嵌套 lambda 中靠 `self` 取回宿主服务 */
private val FloatingWindowService.self: FloatingWindowService get() = this

/**
 * 任务收尾：胶囊先切终态色停留约 800ms，再淡出让位给完成卡片。
 *
 * 成败只认 [TaskOutcome.status]，不再从摘要里搜「失败 / 放弃 / 未完成」——
 * 那种猜法会把「已完成登录，但支付失败」判成失败，也会把没有任何关键词的失败判成功。
 *
 * @param outcome 引擎给出的终态、摘要与耗时/步数/通道/失败原因
 */
internal fun FloatingWindowService.showDone(outcome: TaskOutcome) {
    handler.post {
        pendingDoneOutcome = outcome
        // 用户收起面板时任务恰好收尾：先复位收起态（下一个任务开始时面板要能自己回来）
        showFromUser()
        stopDotPulse()
        collapseThinking()
        hideSheet()
        hideKeyboard()
        // 完成卡片在这 800ms 里预热：窗口与文案先建好，交接时只翻可见性 + 淡入，
        // 避免"胶囊已经淡出、卡片还没画出来"的空档
        val t0 = SystemClock.uptimeMillis()
        doneMessage = outcome.summary
        val cardReady = ensureDoneWindow()
        if (cardReady) {
            applyDoneContent(outcome)
            OverlayPerf.code("done-card-build", t0)
        }
        // 终态先落在胶囊与任务卡上：这一段是用户对"任务结束"的唯一即时反馈，
        // 不能被卡片入场吞掉。例外是用户主动收起面板——那属于用户意图，不替他弹回来。
        val phase = terminalPhase(outcome.status)
        currentPhase = phase
        marquee?.let { applyMarqueePalette(it) }
        dot?.setBackgroundColor(dotColor(phase))
        phaseChip?.apply {
            text = outcome.status.label
            setTextColor(FloatingUi.ON_BRAND)
            background = FloatingUi.capsule(
                self,
                FloatingUi.RADIUS_CHIP / 2,
                FloatingUi.phaseColor(phase),
            )
        }
        // 悬浮球先收：它是"唤起面板"的入口，收尾期间留着会被误点
        miniRoot?.visibility = View.GONE
        updateNotification("${outcome.status.label}：${outcome.summary}", "Happy Agent")
        handler.removeCallbacks(doneHandoff)
        if (cardReady) {
            handler.postDelayed(doneHandoff, FloatingWindowService.DONE_HOLD_MS)
        } else {
            // 卡片建不出来就不会有交接：把待交接结果丢掉，否则引擎随后的收窗请求
            // 会被 [deferStopForDoneCard] 一直挂着，前台通知退不掉
            pendingDoneOutcome = null
        }
        // 卡片建不出来（窗口被系统拒绝）时不做交接：胶囊的终态色是唯一还能看完的信息，
        // 不能把它也淡掉，让屏幕上什么都不剩
    }
}

/** 终态 → 胶囊阶段色：中断与未达成走等待琥珀（不是链路故障），失败才用危险红 */
internal fun FloatingWindowService.terminalPhase(status: TaskStatus): String = when (status) {
    TaskStatus.SUCCESS -> "DONE"
    TaskStatus.FAILED -> "ERROR"
    TaskStatus.ABORTED, TaskStatus.NOT_ACHIEVED -> "WAITING"
}

/**
 * 构建任务完成卡片（对标 Eta 的 result card，视觉仍用本项目令牌）。
 *
 * 刻意不照搬 Eta 的两点：
 * - 不用「50% 屏高 + 固定 420dp」双约束：卡片高度随内容自适应，正文最大
 *   [FloatingWindowService.DONE_BODY_MAX_HEIGHT]dp 内部滚动，矮屏 / 大字号下正文不会被压没；
 * - 不从屏幕顶部滑入：进入方向固定自下而上（项目动效铁律）。
 *
 * 层级：拖拽把手 → 状态行（20dp 圆点 + 15sp 半粗标题）→ 可滚动正文（底部渐变遮罩）
 *       → 一行三键（复制 / 完成 / 打开 Agent）。
 */

internal fun FloatingWindowService.buildDoneCard(): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // 左右 16 / 底部 16：与 App 一级内容留白一致；顶部留给拖拽把手
        setPadding(dp(FloatingUi.PAD_XL), 0, dp(FloatingUi.PAD_XL), dp(FloatingUi.PAD_XL))
        // 与任务卡同一档浮起高度：卡片是"贴着屏幕底部的一层"，不是贴在底图上的补丁
        elevation = dp(16).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    dp(FloatingUi.RADIUS_CARD.toInt()).toFloat(),
                )
            }
        }
        themed { p ->
            background = FloatingUi.capsule(
                self,
                FloatingUi.RADIUS_CARD,
                p.panel,
                p.panelEdge,
                dp(1),
            )
        }
    }

    // 1) 拖拽把手 36×4dp：只在这条上响应下滑，避免和正文滚动抢手势
    val handle = View(this).apply {
        themed { p ->
            // 与标题同色系、30% 透明：只做"可以拖"的暗示，不抢视觉
            background = FloatingUi.capsule(
                self,
                FloatingUi.RADIUS_PILL,
                (p.textTertiary and 0x00FFFFFF) or 0x33000000,
            )
        }
        setOnTouchListener { _, ev ->
            onDoneDrag(ev)
            true
        }
    }
    card.addView(
        handle,
        LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(FloatingUi.PAD)
            bottomMargin = dp(FloatingUi.PAD_L)
        },
    )

    buildDoneStatusBlock(card)
    buildDoneBodyBlock(card)
    buildDoneActionsBlock(card)
    return card
}

internal fun FloatingWindowService.buildDoneStatusBlock(card: LinearLayout) {
    // 2) 状态行：20dp 圆点（14% 语义色底 + 语义色勾/叉）+ 15sp 半粗标题
    val statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    doneStatusDot = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
    }
    statusRow.addView(doneStatusDot, LinearLayout.LayoutParams(dp(20), dp(20)))
    doneTitle = TextView(this).apply {
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(10), 0, 0, 0)
        themed { p -> setTextColor(p.textPrimary) }
    }
    statusRow.addView(
        doneTitle,
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    card.addView(statusRow)

    // 2.5) 元信息 + 失败原因：都是"引擎有才显示"的行，缺数据时整行不出现，
    //      不写"耗时未知 / 通道未知"这类噪音
    doneMeta = TextView(this).apply {
        textSize = 11f
        setPadding(dp(30), dp(2), 0, 0)
        themed { p -> setTextColor(p.textTertiary) }
        visibility = View.GONE
    }
    card.addView(doneMeta)
    doneReason = TextView(this).apply {
        textSize = 12f
        setPadding(dp(30), dp(6), 0, 0)
        visibility = View.GONE
    }
    card.addView(doneReason)

}

internal fun FloatingWindowService.buildDoneBodyBlock(card: LinearLayout) {
    // 3) 正文：内容自适应、超长时限高内部滚动；底部渐变遮罩只在真的滚得动时出现
    val bodyFrame = FrameLayout(this)
    doneScroll = MaxHeightScrollView(this, dp(FloatingWindowService.DONE_BODY_MAX_HEIGHT)).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // 滚动期间暂停自动关闭；松手后重新计时（Eta 的 interacting 语义）
        setOnScrollChangeListener { _, _, _, _, _ -> restartDoneAutoClose() }
    }
    doneBody = TextView(this).apply {
        textSize = 13f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(10), 0, dp(12))
        themed { p -> setTextColor(p.textSecondary) }
    }
    doneBody?.let { body -> doneScroll?.addView(body) }
    bodyFrame.addView(
        doneScroll,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    doneFade = DoneFadeView(this).apply { visibility = View.GONE }
    bodyFrame.addView(
        doneFade,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
            gravity = Gravity.BOTTOM
        },
    )
    themed { p -> doneFade?.setFadeColor(p.panel) }
    card.addView(bodyFrame)

}

@Suppress("LongMethod")
internal fun FloatingWindowService.buildDoneActionsBlock(card: LinearLayout) {
    // 4) 一行三键：复制（结果写剪贴板）/ 完成（关卡片）/ 打开 Agent（回到会话看完整过程）
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    doneCopy = TextView(this).apply {
        text = "复制"
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(FloatingUi.PAD_L), dp(10), dp(FloatingUi.PAD_L), dp(10))
        isClickable = true
        setOnClickListener { copyDoneResult() }
        themed { p ->
            setTextColor(p.textSecondary)
            background = FloatingUi.capsule(
                self,
                FloatingUi.RADIUS_PILL,
                p.panelSunken,
                p.panelEdge,
                dp(1),
            )
        }
    }
    actions.addView(
        doneCopy,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(FloatingUi.PAD) },
    )
    val doneBtn = TextView(this).apply {
        text = "完成"
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { closeDoneCard() }
        themed { p -> setTextColor(p.textSecondary) }
    }
    actions.addView(
        doneBtn,
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    val openBtn = TextView(this).apply {
        text = "打开 Agent"
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { openAgentFromDone() }
        themed { p ->
            setTextColor(p.onBrand)
            background = FloatingUi.capsule(
                self,
                FloatingUi.RADIUS_PILL,
                p.brand,
            )
        }
    }
    actions.addView(
        openBtn,
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(FloatingUi.PAD) },
    )
    card.addView(
        actions,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(FloatingUi.PAD_L) },
    )
    bindPressFeedback(doneCopy!!, doneBtn, openBtn)
}


/**
 * 创建并显示完成卡片窗口。
 *
 * 任务开始时预热（见 [showWindow]），完成时只翻可见性——新建一个覆盖层窗口要等完整的
 * measure / layout / 一次合成（实测首帧 1.6s+，窗口已存在时 341ms），这笔开销不能
 * 落在"任务刚结束"的那一刻。
 *
 * 不被系统栏裁切的三条保证：
 * 1. `gravity = BOTTOM` + **不清空** `fitInsetsTypes`：内容区停在导航栏之上，
 *    贴底卡片既不会被手势条压住，也不会被系统栏切掉；左右/底部留白由根容器 padding 负责。
 * 2. 高度 `WRAP_CONTENT`：卡片长多高长多高，永远不需要「先占屏再裁」。
 * 3. 正文限高滚动：长结果在卡片内部滚，卡片本身不会长到屏幕外。
 */
// 分派函数多处早退以保证可读性，此处按规则豁免
@Suppress("ReturnCount")
internal fun FloatingWindowService.ensureDoneWindow(): Boolean {
    if (doneRoot != null) return true
    val wm = windowManager ?: return false
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        // 留白放在根容器的 padding 上：卡片隐藏/移除时窗口高度为 0，不留透明触摸区
        setPadding(
            dp(FloatingUi.PAD_XL),
            0,
            dp(FloatingUi.PAD_XL),
            dp(FloatingUi.PAD),
        )
        visibility = View.GONE
    }
    val card = buildDoneCard()
    container.addView(
        card,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    val added = runCatching { wm.addView(container, lp) }.isSuccess
    if (!added) {
        Log.w("FloatingWindowService", "完成卡片添加失败")
        doneCard = null
        return false
    }
    doneRoot = container
    return true
}

/**
 * 交接：胶囊与任务卡淡出、完成卡片淡入，两件事同一拍开始。
 *
 * 卡片在停留期已建好并写好内容，这里只翻可见性 + 透明度，
 * 所以"胶囊消失"与"卡片出现"之间不会露出空档。
 */
internal fun FloatingWindowService.revealDoneCard(startedAt: Long) {
    val rv = doneRoot ?: return
    rv.visibility = View.VISIBLE
    doneCard?.let { card ->
        card.animate().cancel()
        // 只做透明度：这是让位，不加位移、弹性或缩放
        card.translationY = 0f
        card.alpha = 0f
        card.animate()
            .alpha(1f)
            .setDuration(OverlayMotion.EmphasisMs.toLong())
            .setInterpolator(OverlayMotion.EaseOut)
            .start()
    }
    rv.post {
        val postAt = SystemClock.uptimeMillis()
        OverlayPerf.log("done-card-apply", "${postAt - startedAt}ms")
        // 正文真的超出限高才显示渐变遮罩（短结果下不出现多余的灰带）
        val scroll = doneScroll
        val body = doneBody
        doneFade?.visibility =
            if (scroll != null && body != null && body.height > scroll.height) {
                View.VISIBLE
            } else {
                View.GONE
            }
        OverlayPerf.firstFrame("done-card-first-frame", rv, startedAt, postAt - startedAt)
        restartDoneAutoClose()
    }
}

/** 淡出后隐藏：只用于收尾交接，离开的窗口不参与后续布局 */
internal fun FloatingWindowService.fadeOutAndHide(view: View?, durationMs: Int) {
    if (view == null) return
    view.animate().cancel()
    if (view.visibility != View.VISIBLE) {
        view.alpha = 1f
        return
    }
    view.animate()
        .alpha(0f)
        .setDuration(durationMs.toLong())
        .setInterpolator(OverlayMotion.EaseIn)
        .withEndAction {
            view.visibility = View.GONE
            // 复位透明度：下一次任务复用时从 1 开始，不然会带着上一次的残影
            view.alpha = 1f
        }
        .start()
}



/** 把结果写进卡片：标题/状态色/正文/元信息/失败原因/复制按钮复位 */
@Suppress("CyclomaticComplexMethod")
internal fun FloatingWindowService.applyDoneContent(outcome: TaskOutcome) {
    val success = outcome.status == TaskStatus.SUCCESS
    val tone = when (outcome.status) {
        TaskStatus.SUCCESS -> FloatingUi.phaseColor("DONE")
        TaskStatus.FAILED -> FloatingUi.phaseColor("ERROR")
        // 中断与未达成不是故障：用等待琥珀，避免把"用户停手"画成出错
        TaskStatus.ABORTED, TaskStatus.NOT_ACHIEVED -> FloatingUi.phaseColor("WAITING")
    }
    doneTitle?.text = outcome.status.label
    doneStatusDot?.apply {
        text = when (outcome.status) {
            TaskStatus.SUCCESS -> "✓"
            TaskStatus.FAILED -> "✕"
            TaskStatus.ABORTED, TaskStatus.NOT_ACHIEVED -> "!"
        }
        setTextColor(tone)
        background = FloatingUi.capsule(
            self,
            FloatingUi.RADIUS_PILL,
            // 14% 语义色底：色点只做状态标记，不把整张卡片染成红/绿
            (tone and 0x00FFFFFF) or 0x24000000,
        )
    }
    val raw = outcome.summary.trim()
    // 引擎只给了一行"任务完成"时，补一句可执行的下一步；有细节就原样展示
    doneBody?.text = if (raw.isEmpty() || raw == "任务完成" || raw == "任务已完成") {
        if (success) "任务已结束。打开 Agent 可以查看完整过程与结果。" else outcome.status.label
    } else {
        raw
    }
    // 元信息：引擎给什么显示什么，缺一项就少一项，不拿默认值凑
    val meta = buildDoneMeta(outcome)
    doneMeta?.apply {
        text = meta
        visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
    }
    // 失败原因：摘要本身已经是原因时不再重复一遍
    val reason = outcome.failureReason.trim()
    val showReason = reason.isNotEmpty() && reason != raw
    doneReason?.apply {
        text = if (showReason) reason else ""
        setTextColor(tone)
        visibility = if (showReason) View.VISIBLE else View.GONE
    }
    doneCopy?.text = "复制"
    doneScroll?.scrollTo(0, 0)
}

/** 「耗时 12 秒 · 3 步 · 无障碍」；全空返回空串，卡片直接省略该行 */
internal fun FloatingWindowService.buildDoneMeta(outcome: TaskOutcome): String = buildList {
    if (outcome.elapsedMs > 0) add("耗时 ${formatElapsed(outcome.elapsedMs)}")
    if (outcome.steps > 0) add("${outcome.steps} 步")
    if (outcome.channel.isNotBlank()) add(outcome.channel)
}.joinToString(" · ")

/** 秒级够用：任务是分钟量级，再多一位小数只会让收尾行变吵 */
internal fun FloatingWindowService.formatElapsed(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(1L)
    return if (seconds < 60L) {
        "$seconds 秒"
    } else {
        "${seconds / 60L} 分 ${seconds % 60L} 秒"
    }
}

/** 复制完整结果到剪贴板；1.4s 内按钮显示"已复制"（状态保持，不是动效） */
internal fun FloatingWindowService.copyDoneResult() {
    val text = doneBody?.text?.toString().orEmpty().ifBlank { doneMessage }
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    runCatching { cm?.setPrimaryClip(ClipData.newPlainText("任务结果", text)) }
    doneCopy?.text = "已复制"
    handler.postDelayed({ doneCopy?.text = "复制" }, FloatingWindowService.COPY_FEEDBACK_MS)
    restartDoneAutoClose()
}

/** 「打开 Agent」：回到 App 的会话页看完整过程；随后收起完成卡片 */
internal fun FloatingWindowService.openAgentFromDone() {
    runCatching {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }
    closeDoneCard()
}

/** 下滑关闭（≥96dp 才算关闭，否则回弹）+ 按住/滚动期间暂停自动关闭 */
internal fun FloatingWindowService.onDoneDrag(ev: MotionEvent) {
    val card = doneCard ?: return
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            doneDragStartRawY = ev.rawY
            doneDragOffset = 0f
            pauseDoneAutoClose()
            card.animate().cancel()
        }
        MotionEvent.ACTION_MOVE -> {
            // 只允许向下：向上位移会让卡片跑到状态栏下面，与"不碰系统栏"的约束冲突
            doneDragOffset = (ev.rawY - doneDragStartRawY).coerceAtLeast(0f)
            card.translationY = doneDragOffset
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (doneDragOffset >= dp(FloatingWindowService.DONE_SWIPE_CLOSE_DP)) {
                closeDoneCard()
            } else {
                card.animate()
                    .translationY(0f)
                    .setDuration(OverlayMotion.EnterMs.toLong())
                    .setInterpolator(OverlayMotion.EaseOut)
                    .start()
                restartDoneAutoClose()
            }
        }
    }
}

/**
 * 完成卡片还在屏上、或正等胶囊交接时，接住引擎这一次「收窗」请求。
 *
 * 引擎的停止语义是"这次任务结束了"；卡片还要把结论与失败原因交给用户读。
 * 两者可以错开：窗口的可见时长上限由卡片自己管（[FloatingWindowService.DONE_HOLD_MS] 停留 + [FloatingWindowService.DONE_AUTO_CLOSE_MS]
 * 自动关闭），关闭那一步本来就会 `stopSelf`，服务不会悬着。
 *
 * @return true 表示停止时机已由本服务接管，调用方不要再 `stopService`
 */
internal fun FloatingWindowService.deferStopForDoneCard(): Boolean {
    // 既没有卡片、也没有待交接的结果：这次停止与收尾无关，照常执行
    if (doneRoot == null && pendingDoneOutcome == null) {
        stopPending = false
        return false
    }
    stopPending = true
    return true
}

/** 退场：向上淡出（不复用"下滑抽走"——那会被读成系统回收），动画结束后才移除窗口 */
internal fun FloatingWindowService.closeDoneCard() {
    handler.removeCallbacks(doneAutoClose)
    val card = doneCard
    val rootView = doneRoot
    if (card == null || rootView == null) {
        removeDoneCardNow()
        // 停止请求被挂起过、而卡片已经不在：这里补一次收尾，别让前台通知永远挂着
        if (stopPending) {
            stopPending = false
            stopSelf()
        }
        return
    }
    OverlayMotion.exit(card, durationMs = OverlayMotion.ExitMs) {
        // 退场动画 200ms 内可能有新任务接管（resetPanel 已把卡片摘掉）：
        // 只有"卡片还挂在这个窗口上"时才认为整场任务真正结束并退出服务，
        // 否则只清理旧窗口，别把新任务的悬浮窗一起带走
        if (doneRoot === rootView) {
            removeDoneCardNow()
            stopSelf()
            removeWindow()
        } else {
            runCatching { windowManager?.removeView(rootView) }
        }
    }
}

/** 立即移除完成卡片窗口（不做退场动画）：新任务开始 / 服务销毁时用 */
internal fun FloatingWindowService.removeDoneCardNow() {
    handler.removeCallbacks(doneAutoClose)
    doneRoot?.let { runCatching { windowManager?.removeView(it) } }
    doneRoot = null
    doneCard = null
    doneTitle = null
    doneStatusDot = null
    doneBody = null
    doneMeta = null
    doneReason = null
    pendingDoneOutcome = null
    doneScroll = null
    doneFade = null
    doneCopy = null
    doneInteracting = false
    doneDragOffset = 0f
}

/**
 * 只收起、不销毁：完成卡片窗口留给下一次任务复用（它是任务开始时预热好的）。
 * 8s 自动关闭的计时必须在这里取消，否则下一次任务进行到一半会被上一个倒计时掐掉。
 */
internal fun FloatingWindowService.hideDoneCardNow() {
    handler.removeCallbacks(doneAutoClose)
    doneRoot?.visibility = View.GONE
    doneCard?.translationY = 0f
    doneCard?.alpha = 1f
    doneInteracting = false
    doneDragOffset = 0f
}

/** 静止 8s 自动关；任何按压 / 滚动 / 拖拽都会重置计时 */
internal fun FloatingWindowService.restartDoneAutoClose() {
    handler.removeCallbacks(doneAutoClose)
    doneInteracting = false
    if (doneRoot == null) return
    handler.postDelayed(doneAutoClose, FloatingWindowService.DONE_AUTO_CLOSE_MS)
}

internal fun FloatingWindowService.pauseDoneAutoClose() {
    doneInteracting = true
    handler.removeCallbacks(doneAutoClose)
}

/**
 * 完成卡片三键的按压反馈：ACTION_DOWN 当帧缩到 [OverlayMotion.PressedScale]，
 * 抬手回弹；同时暂停/恢复 auto-close 计时。
 *
 * 为什么不用系统 ripple：ripple 的按下态要等下一帧才显示，手指快按快抬时经常完全看不到；
 * 这里在 DOWN 事件里直接改 scale，当帧就生效。
 */
internal fun FloatingWindowService.bindPressFeedback(vararg views: View) {
    views.forEach { v ->
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pauseDoneAutoClose()
                    OverlayMotion.pressDown(v)
                    // 首个视觉反馈延迟（含帧等待；代码部分是 0ms，缩放是同步落位）
                    OverlayPerf.firstFrame("press-down-paint", v, ev.eventTime, 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    OverlayMotion.pressUp(v)
                    restartDoneAutoClose()
                }
            }
            // 返回 false：不影响 TextView 自身的点击回调
            false
        }
    }
}
