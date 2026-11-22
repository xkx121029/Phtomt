// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber", "TooManyFunctions")
package com.phoneagent.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** 扩展函数里没有 `this@FloatingWindowService` 这个标签，嵌套 lambda 中靠 `self` 取回宿主服务 */
private val FloatingWindowService.self: FloatingWindowService get() = this

internal fun FloatingWindowService.buildPanel(): LinearLayout {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
    }
    // 卡片底色：玄青 85% 不透明度的半透明底 + 发丝描边。
    // **没有背景模糊**——卡片曾经配过窗口的 blurBehindRadius，但那个 API 糊的是整块屏幕，
    // 不是卡片这 300dp 见方（见类注释）；悬浮窗拿不到"只糊窗口范围"的 setBackgroundBlurRadius。
    // 留着半透明是因为它仍有层次：透出来的底图是清晰的原样，不是磨砂。
    panel.background = FloatingUi.capsule(self,
        FloatingUi.RADIUS_CARD,
        FloatingUi.BRAND_GLASS,
        FloatingUi.GLASS_EDGE,
        dp(1),
    )
    panel.setPadding(FloatingUi.PAD_L, FloatingUi.PAD_S, FloatingUi.PAD_L, FloatingUi.PAD_S)

    // 跑马灯已拆成独立的底部窗口（见 showMarquee），卡片里不再有色带：
    // 卡片只有 300dp 宽，跑马灯挂在里面会跟着卡片一起被拖走

    val hideChip = buildHeaderRow(panel)
    buildStatusRow(panel, hideChip)
    buildProgressRow(panel)
    buildThinkingBlock(panel)
    buildInteractPanel()
    buildInteractInputs()
    return panel
}

@Suppress("LongMethod")
internal fun FloatingWindowService.buildHeaderRow(panel: LinearLayout): TextView {
    // 头部：状态呼吸点 + 任务标题 + 关闭（仅头部可拖动；轻点头部也可切换详情）。
    // 三个操作都保留，只重新分层：「详情 / 隐藏」下移到状态行右侧，
    // 标题因此独占整行，不会被三枚按钮挤成一截。
    header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(FloatingUi.PAD_L, FloatingUi.PAD, FloatingUi.PAD_S, 0)
        setOnTouchListener { _, event ->
            onTouchDrag(event)
            true
        }
    }
    // 状态呼吸点：阶段色 + 呼吸脉冲，让任务状态“有生命”
    dot = View(this).apply {
        setBackgroundResource(0)
        val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(FloatingUi.phaseColor("PENDING")) }
        background = d
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
    }
    // 任务标题：单行省略，长任务名不会把右侧的出口按钮顶出卡片
    taskTitle = TextView(this).apply {
        text = "Happy Agent"
        textSize = 14f
        setTextColor(FloatingUi.ON_BRAND)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(FloatingUi.PAD, 0, FloatingUi.PAD, 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val close = buildCloseChip()
    // 头部三个可点控件（关闭 / 详情 / 隐藏）：按下当帧缩放
    bindPress(close)
    // 详情开关：折叠态只显示三行，AI 的发送/返回/审核内容按需展开
    expandChip = TextView(this).apply {
        text = "详情"
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(FloatingUi.ON_BRAND_SECONDARY)
        background = FloatingUi.capsule(self, FloatingUi.RADIUS_CHIP,
            FloatingUi.ON_BRAND_STATE_WEAK)
        setPadding(FloatingUi.PAD, 0, FloatingUi.PAD, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(26),
        ).apply { marginStart = dp(6) }
        setOnClickListener { toggleThinking() }
    }
    expandChip?.let { bindPress(it) }
    header?.addView(dot)
    header?.addView(taskTitle)
    // 隐藏按钮：收起整套面板、只留一个悬浮球，同时把任务搁置（引擎在下一轮开始前挂起）；
    // 用户点悬浮球即可原样唤出并继续执行
    val hideChip = TextView(this).apply {
        text = "隐藏"
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(FloatingUi.ON_BRAND_SECONDARY)
        background = FloatingUi.capsule(self, FloatingUi.RADIUS_CHIP,
            FloatingUi.ON_BRAND_STATE_WEAK)
        setPadding(FloatingUi.PAD, 0, FloatingUi.PAD, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(26),
        ).apply { marginStart = dp(6) }
        setOnClickListener {
            hideForUser()
            FloatingWindowService.onInteraction?.invoke("hide", "")
        }
    }
    bindPress(hideChip)
    header?.addView(close)
    panel.addView(header)
    return hideChip
}

    // 关闭按钮：圆形胶囊，按压有反馈。
    // 两段式确认：第一次点进入待确认态（文案变「再点确认停止」，2.5 秒后自动复位），
    // 再次点击才真正回调 close 终止任务——一次误触不应杀掉正在执行的任务
internal fun FloatingWindowService.buildCloseChip(): TextView {
    return TextView(this).apply {
        text = "✕"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(FloatingUi.ON_BRAND_SECONDARY)
        background = FloatingUi.capsule(self, FloatingUi.RADIUS_CHIP,
            FloatingUi.ON_BRAND_STATE_WEAK)
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        var closeArmed = false
        setOnClickListener {
            if (!closeArmed) {
                // 第一段：进入待确认态（文案变长，宽度改为随内容自适应）
                closeArmed = true
                text = FloatingWindowService.CLOSE_CONFIRM_LABEL
                textSize = 10f
                setPadding(FloatingUi.PAD_S, 0, FloatingUi.PAD_S, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(26),
                )
                postDelayed({
                    // 超时未确认：自动复位回「✕」，下次点击仍需两段确认
                    if (closeArmed) {
                        closeArmed = false
                        text = "✕"
                        textSize = 12f
                        setPadding(0, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                    }
                }, FloatingWindowService.CLOSE_CONFIRM_RESET_MS)
            } else {
                // 第二段：用户已确认，才回调 close 并关闭悬浮窗
                FloatingWindowService.onInteraction?.invoke("close", "")
                stopSelf(); removeWindow()
            }
        }
    }
}

internal fun FloatingWindowService.buildStatusRow(panel: LinearLayout, hideChip: TextView) {
    // 状态行：阶段徽章 + 步骤文字 + 次要操作（详情 / 隐藏）。
    // 完成态整行让位给打勾面板，所以它单独存一份引用。
    statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(FloatingUi.PAD_L, dp(2), FloatingUi.PAD, FloatingUi.PAD_S)
    }
    phaseChip = TextView(this).apply {
        text = "待命"
        textSize = 10f
        // 主题色底上用实心阶段色胶囊 + 白字：半透明的淡色底在深色卡片上读不出层次
        setTextColor(FloatingUi.ON_BRAND)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = FloatingUi.capsule(self, 
            FloatingUi.RADIUS_CHIP / 2,
            FloatingUi.phaseColor("PENDING"),
        )
        setPadding(FloatingUi.PAD, dp(3), FloatingUi.PAD, dp(3))
    }
    stepText = TextView(this).apply {
        text = "等待任务..."
        textSize = 12f
        setTextColor(FloatingUi.ON_BRAND_SECONDARY)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(FloatingUi.PAD, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    statusRow?.addView(phaseChip)
    statusRow?.addView(stepText)
    statusRow?.addView(expandChip)
    statusRow?.addView(hideChip)
    statusRow?.let { panel.addView(it) }
}

internal fun FloatingWindowService.buildProgressRow(panel: LinearLayout) {
    // 进度条：用户反馈无用，始终保持隐藏（不占悬浮窗空间）
    progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
    }
    panel.addView(progressBar)
}

@Suppress("LongMethod")
internal fun FloatingWindowService.buildThinkingBlock(panel: LinearLayout) {
    // AI 详情面板：圆角内嵌卡片，按 发送/返回/审核 分栏
    // 默认 GONE（折叠）：只在用户点「详情」或点头部时占位，避免窗口常驻撑高
    thinkingPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L)
        themed { p -> background = FloatingUi.capsule(self, FloatingUi.RADIUS_PANEL,
            p.panel) }
    }
    // 内容可滚动，限制高度避免悬浮窗过大（折叠时整块不占位）
    thinkingScroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(FloatingUi.THINKING_H))
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        setPadding(0, FloatingUi.PAD, 0, 0)
        clipToPadding = false
        clipToOutline = false
    }
    val thinkCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sentLabel = TextView(this).apply {
        text = "发送给 AI"
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, 0)
        themed { p -> setTextColor(p.textTertiary) }
    }
    thinkCol.addView(sentLabel)
    thinkingSentText = TextView(this).apply {
        text = ""
        textSize = 11f
        setPadding(0, dp(3), 0, dp(2))
        themed { p -> setTextColor(p.textSecondary) }
    }
    thinkCol.addView(thinkingSentText)
    val retLabel = TextView(this).apply {
        text = "AI 返回"
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, 0)
        themed { p -> setTextColor(p.textTertiary) }
    }
    thinkCol.addView(retLabel)
    thinkingReturnText = TextView(this).apply {
        text = ""
        textSize = 11f
        setPadding(0, dp(3), 0, dp(2))
        themed { p -> setTextColor(p.textPrimary) }
    }
    thinkCol.addView(thinkingReturnText)
    val reviewLabel = TextView(this).apply {
        text = "审核结论"
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, 0)
        themed { p -> setTextColor(p.brand) }
    }
    thinkCol.addView(reviewLabel)
    reviewText = TextView(this).apply {
        text = ""
        textSize = 11f
        setPadding(0, dp(3), 0, dp(4))
        themed { p -> setTextColor(p.brand) }
    }
    thinkCol.addView(reviewText)
    thinkingScroll?.addView(thinkCol)
    thinkingPanel?.addView(thinkingScroll)
    panel.addView(thinkingPanel)
}

internal fun FloatingWindowService.buildInteractPanel() {
    // 交互区域（批准/澄清/指导，默认隐藏）：暖纸白实色卡 + 发丝描边。
    // 圆角取第一层 RADIUS_CARD：它是这扇窗口最外层的表面，与 App 侧同名的协助浮层
    // （AgentAssistSheet，容器同为 Card 圆角）对齐；内层的选项行/输入框再降一档到 RADIUS_TILE。
    interactPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(FloatingUi.PAD_XL), dp(FloatingUi.PAD_XL), dp(FloatingUi.PAD_XL), dp(FloatingUi.PAD_XL))
        themed { p ->
            background = FloatingUi.capsule(self, 
                FloatingUi.RADIUS_CARD,
                p.panel,
                p.panelEdge,
                dp(1),
            )
        }
    }
    // 帮助徽章 + 标题 + 收起
    val interactHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val helpBadge = TextView(this).apply {
        text = "助手"
        textSize = 10f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = FloatingUi.capsule(self, FloatingUi.RADIUS_PILL, FloatingUi.ACCENT_WARM)
        setPadding(dp(FloatingUi.PAD), dp(3), dp(FloatingUi.PAD), dp(3))
    }
    interactTitle = TextView(this).apply {
        text = "需要确认"
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(FloatingUi.PAD), 0, 0, 0)
        // 占满中间：把「收起」顶到最右侧，标题多长都不会把它挤走
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        themed { p -> setTextColor(p.textPrimary) }
    }
    // 「收起」：让出屏幕。收起后面板整块 GONE，只留右下角一枚入口胶囊，
    // 用户可以先去动手处理屏幕上的事，处理完再点胶囊回来回答（问题不会丢）
    val collapseChip = TextView(this).apply {
        text = "收起"
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26))
        setOnClickListener { setInteractCollapsed(true) }
        themed { p ->
            setTextColor(p.brand)
            background = FloatingUi.capsule(self, 
                FloatingUi.RADIUS_PILL,
                p.panelSunken,
                p.panelEdge,
                dp(1),
            )
        }
    }
    interactHeader.addView(helpBadge)
    interactHeader.addView(interactTitle)
    interactHeader.addView(collapseChip)
    interactPanel?.addView(interactHeader)
}

internal fun FloatingWindowService.buildInteractInputs() {
    // 内容可滚动（长文本）
    //
    // 这里**不能**用「高度 0 + weight 1」那种"占满剩余空间"的写法：选项卡窗口是
    // WRAP_CONTENT，LinearLayout 在 AT_MOST 下会把「沿高度方向的剩余空间」全分给权重子视图，
    // 而剩余空间就是整块屏幕 —— 面板于是被撑到整屏高，整个窗口变成一张盖住全屏的
    // 透明可触摸层：屏幕上千点什么都落到这层上（用户侧表现就是"整个手机都点不动"，
    // 而底部跑马灯是另一个窗口，照旧在滚）。
    // 改成「限高内滚」：短文案贴合内容高度（不再留一片固定 140dp 的空白），超长才内滚。
    val contentScroll = MaxHeightScrollView(this, dp(140)).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        isVerticalScrollBarEnabled = false
    }
    interactContent = TextView(this).apply {
        text = ""
        textSize = 12f
        setPadding(0, dp(8), 0, dp(2))
        themed { p -> setTextColor(p.textPrimary) }
    }
    contentScroll.addView(interactContent)
    interactPanel?.addView(contentScroll)
    // 选项行 / 出口行的容器（按交互类型重建）
    interactRows = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    interactPanel?.addView(interactRows)
    // 指导输入框
    hintInput = EditText(this).apply {
        textSize = 12f
        setHint("告诉 AI 该怎么做（或留空）")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
        maxLines = 3
        setBackgroundResource(0)
        setPadding(dp(FloatingUi.PAD_L), dp(10), dp(FloatingUi.PAD_L), dp(10))
        themed { p ->
            setTextColor(p.textPrimary)
            setHintTextColor(p.textTertiary)
            background = FloatingUi.capsule(self, 
                FloatingUi.RADIUS_TILE,
                p.panelSunken,
                p.panelEdge,
                dp(1),
            )
        }
    }
    interactPanel?.addView(hintInput)
    interactActions = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    interactPanel?.addView(interactActions)
    // 交互面板不再挂进顶部窗口：它由底部选项卡承载（见 showSheetWindow），
    // 顶部只保留任务状态与跑马灯，两边职责不重叠
}


// ==================== 对外更新 ====================

/**
 * 更新悬浮窗进度状态。
 * @param status 状态文本
 * @param task 任务名称
 * @param reasoning AI 推理/思考内容
 * @param step 当前步骤
 * @param total 总步骤
 * @param phase 阶段（OBSERVING/THINKING/ACTING/DONE/ERROR）
 */
@Suppress("LongParameterList")
internal fun FloatingWindowService.updateStatus(
    status: String,
    task: String,
    reasoning: String,
    step: Int,
    total: Int,
    phase: String,
) {
    handler.post {
        // 新任务开始时自动恢复常规面板（清除上一个任务的完成态残留）
        resetPanel()
        // 记录最近一次任务名：悬浮窗销毁时状态总线要拿它做 Widget 的空闲摘要
        currentTask = task
        // 跑马灯底色随阶段变化（跟随状态）或保持用户自定义配色
        currentPhase = phase
        marquee?.let { applyMarqueePalette(it) }
        // 记录原始入参 + 按显示设置落到两处（跑马灯 / 卡片状态行），避免同一条描述画两遍
        lastStatusText = status
        lastReasoningText = reasoning
        lastStep = step
        lastTotal = total
        applyStepTextPlacement()
        taskTitle?.text = task
        // 阶段徽章：实心阶段色胶囊 + 白字（卡片是实色主题色，徽章也必须实色才压得住）
        val ph = FloatingUi.phaseColor(phase)
        phaseChip?.text = phaseLabel(phase)
        phaseChip?.setTextColor(FloatingUi.ON_BRAND)
        phaseChip?.background = FloatingUi.capsule(self, FloatingUi.RADIUS_CHIP / 2, ph)
        // 进度条已隐藏（用户反馈无用），仅显示步骤文字
        dot?.setBackgroundColor(dotColor(phase))
        startDotPulse()
        // 实时更新通知
        updateNotification(status, task, step)
        // 用户收起面板期间：状态照常更新到各控件上，但面板不能自己冒回来
        applyUserHidden()
    }
}

/** 阶段枚举 → 友好中文标签 */
internal fun FloatingWindowService.phaseLabel(phase: String): String = when (phase) {
    "OBSERVING" -> "观察中"
    "THINKING" -> "思考中"
    "ACTING" -> "执行中"
    "DONE" -> "已完成"
    "ERROR" -> "出错"
    else -> "待命"
}

/** 清空思考显示区：流式请求中断自动重试前调用，避免重放内容与旧文本重复累积 */
internal fun FloatingWindowService.resetThinking() {
    handler.post {
        clearThinkingReturn()
        thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
    }
}

/** 清空流式返回区：TextView 与累积缓冲必须一起清，否则下一轮会把旧内容再接上 */
internal fun FloatingWindowService.clearThinkingReturn() {
    thinkingBuffer.setLength(0)
    thinkingReturnText?.text = ""
}

/**
 * 实时更新 AI 详情面板：显示发送给 AI 的内容与流式返回的内容，并同步更新通知。
 *
 * 折叠状态下**只累积内容、不显示面板** —— 详情区有 112dp 高，若每来一段流式文本
 * 就自动弹出来，窗口会在任务执行中反复变高变大，正是"遮挡屏幕"的来源。
 * 用户点开「详情」后内容即刻可见，因为文本一直在后台累积。
 *
 * @param sent 发送给 AI 的文本（首次传入；后续传 null 保持已显示内容）
 * @param delta 流式返回的增量文本（null 表示不更新返回区）
 */
internal fun FloatingWindowService.updateThinking(sent: String? = null, delta: String? = null) {
    handler.post {
        val hasSent = !sent.isNullOrBlank()
        val hasDelta = !delta.isNullOrBlank()
        if (!hasSent && !hasDelta) return@post
        if (hasSent && sent != lastSentShown) {
            lastSentShown = sent
            thinkingSentText?.text = sent!!.take(600) + if (sent!!.length > 600) "…" else ""
        }
        if (hasDelta) {
            // 限制展示长度，避免内容无限增长（完整内容由 AI 客户端保留用于解析）；
            // 返回区是流式追加，用户要看的是最新内容，超限时保留**尾部**。
            // 用 StringBuilder 累积：逐增量做字符串拼接是 O(n²) 的拷贝量
            thinkingBuffer.append(delta)
            if (thinkingBuffer.length > FloatingWindowService.THINKING_DISPLAY_MAX) {
                thinkingBuffer.delete(0, thinkingBuffer.length - FloatingWindowService.THINKING_DISPLAY_MAX)
            }
            thinkingReturnText?.text = thinkingBuffer.toString()
            // 只有展开时才需要滚动到底：折叠状态下滚动位置没人看，做了也是白做
            if (thinkingExpanded) {
                thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
            }
            notifyThinkingThrottled()
        }
    }
}

/** 同步 AI 思考到通知栏（节流）：流式增量每秒数次，逐条 notify 是跨进程调用，代价高 */
internal fun FloatingWindowService.notifyThinkingThrottled() {
    val now = SystemClock.uptimeMillis()
    if (now - lastThinkNotifyAt < FloatingWindowService.NOTIFY_THROTTLE_MS) return
    lastThinkNotifyAt = now
    val task = taskTitle?.text?.toString() ?: "Happy Agent"
    updateNotification("AI 思考中", task, 0, thinkingReturnText?.text?.toString().orEmpty())
}

/**
 * 状态点呼吸脉冲：透明度在 0.45~1 间往复，让任务执行状态更“有生命”。
 * 遵循“常驻/循环动效用线性往复”的规范，仅在任务运行期间启用。
 */
internal fun FloatingWindowService.startDotPulse() {
    if (dotPulseAnimator?.isRunning == true) return
    dotPulseAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
        // 循环动效统一走 OverlayMotion.PulseMs：与骨架/运行指示同一个节拍
        duration = OverlayMotion.PulseMs.toLong()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = OverlayMotion.EaseOut
        addUpdateListener { dot?.alpha = it.animatedValue as Float }
        start()
    }
}

internal fun FloatingWindowService.stopDotPulse() {
    dotPulseAnimator?.cancel()
    dotPulseAnimator = null
    dot?.alpha = 1f
}

/** 审核结果行实时更新：在思考面板内展示审核者的 pass/拒绝原因/采用的修正 */
internal fun FloatingWindowService.updateReview(text: String?) {
    handler.post {
        reviewText?.text = text ?: ""
        updateNotification("AI 思考中", taskTitle?.text?.toString() ?: "Happy Agent", 0,
            thinkingReturnText?.text?.toString().orEmpty())
    }
}

/**
 * 恢复常规面板视图：隐藏完成面板，重新显示步骤/进度/头部。
 * 用于新任务开始时清除上一个任务完成态的残留。
 */
internal fun FloatingWindowService.resetPanel() {
    // 上一次收尾的交接可能还挂在 800ms 停留里：新任务开始必须取消，
    // 否则它会在新任务跑到一半时把面板和跑马灯一起淡掉
    handler.removeCallbacks(doneHandoff)
    pendingDoneOutcome = null
    // 新任务开始时把上一次的完成卡片收起来（它可能还在 8s 自动关闭倒计时里）。
    // 只隐藏不销毁：窗口在任务开始时已预热，留着下次完成态直接复用
    hideDoneCardNow()
    // 完成态会把顶部任务卡整块让位给完成卡片；新任务必须把它放回来
    root?.visibility = View.VISIBLE
    root?.alpha = 1f
    marquee?.alpha = 1f
    dot?.visibility = View.VISIBLE
    setMarqueeVisible(true)
    statusRow?.visibility = View.VISIBLE
    progressBar?.visibility = View.GONE
    interactPanel?.visibility = View.GONE
    // 详情区按用户的展开状态恢复：折叠时保持 GONE，否则窗口会在每步更新后突然变高
    thinkingPanel?.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
    thinkingSentText?.text = ""
    clearThinkingReturn()
    reviewText?.text = ""
    lastSentShown = ""
    lastThinkNotifyAt = 0L
    header?.visibility = View.VISIBLE
}

/** 切换 AI 详情区的展开/收起（点头部或点「详情」按钮） */
internal fun FloatingWindowService.toggleThinking() {
    thinkingExpanded = !thinkingExpanded
    thinkingPanel?.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
    expandChip?.text = if (thinkingExpanded) "收起" else "详情"
    if (thinkingExpanded) {
        thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
    }
}

/** 复位为折叠态（新任务/任务结束时调用，避免上一个任务的展开状态带过来） */
internal fun FloatingWindowService.collapseThinking() {
    thinkingExpanded = false
    thinkingPanel?.visibility = View.GONE
    expandChip?.text = "详情"
}

internal fun FloatingWindowService.dotColor(phase: String): Int = FloatingUi.phaseColor(phase)
