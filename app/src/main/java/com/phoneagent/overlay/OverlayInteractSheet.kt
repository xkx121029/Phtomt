// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 扩展函数里没有 `this@FloatingWindowService` 这个标签，嵌套 lambda 中靠 `self` 取回宿主服务 */
private val FloatingWindowService.self: FloatingWindowService get() = this

/**
 * 创建底部选项卡窗口（常驻、初始不可见）。
 *
 * 与顶部窗口相反，这里**不清空 fitInsetsTypes**：默认避开系统栏，
 * gravity=BOTTOM 时面板正好落在导航栏上方，不会被导航栏压住。
 * 窗口可触摸、可聚焦（需要点选项与输入文字），但 GONE 时不会干扰任何操作。
 */
internal fun FloatingWindowService.showSheetWindow() {
    if (sheetRoot != null) return
    val wm = windowManager ?: return
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
    }
    // 交互面板在此挂载（它已在 buildPanel 中构建完成，但未加入顶部窗口）。
    //
    // 左右留白与底边留白放在**面板自己的外边距**上，而不是容器的内边距上：
    // 容器是窗口的根视图，它的内边距在面板隐藏时仍会把窗口撑出一段高度，
    // 零内容却依然可触摸 —— 那一条透明窗口会持续吃掉屏幕底部的操作。
    // 面板隐藏时容器高度为 0，窗口才真正不占地方。
    interactPanel?.let {
        container.addView(
            it,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(FloatingUi.PAD_XL)
                marginEnd = dp(FloatingUi.PAD_XL)
                bottomMargin = dp(FloatingUi.PAD)
            },
        )
    }
    // 收起后的入口胶囊：右下角一枚小胶囊。它是容器里唯一占位的东西 ——
    // LinearLayout 不计 GONE 子视图（连外边距都不计），所以收起时窗口高度正好等于胶囊高，
    // 屏幕其余位置的触摸全部落到底下的 App 上（窗口是 FLAG_NOT_TOUCH_MODAL）。
    collapsedPill = TextView(this).apply {
        text = "需要你回答 · 点开"
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(8), dp(12), dp(8))
        visibility = View.GONE
        setOnClickListener { setInteractCollapsed(false) }
        // 与面板里的主操作同一支主色：它按下去就是"继续回答"，属于同一条动线
        themed { p ->
            setTextColor(p.onBrand)
            background = FloatingUi.capsule(self, FloatingUi.RADIUS_PILL, p.brand)
        }
    }
    collapsedPill?.let { pill ->
        bindPress(pill)
        container.addView(
            pill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                marginEnd = dp(FloatingUi.PAD_XL)
                bottomMargin = dp(FloatingUi.PAD)
            },
        )
    }
    val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        // 输入框获得焦点时让窗口随软键盘上移，避免面板被键盘盖住
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    try {
        wm.addView(container, lp)
        sheetRoot = container
        sheetParams = lp
    } catch (_: Exception) {
        // 无悬浮窗权限时静默失败，任务照常执行
    }
}

/** 从底边滑入选项卡 */
internal fun FloatingWindowService.showSheet() {
    val container = sheetRoot ?: return
    if (container.visibility == View.VISIBLE) return
    container.visibility = View.VISIBLE
    container.post {
        val h = container.height.coerceAtLeast(dp(1)).toFloat()
        // 入场：自下而上（从面板自身高度走进位），曲线与时长走令牌
        container.animate().cancel()
        container.alpha = 1f
        container.translationY = h
        container.animate()
            .translationY(0f)
            .setDuration(OverlayMotion.EnterMs.toLong())
            .setInterpolator(OverlayMotion.EaseOut)
            .start()
    }
}

/** 滑出并隐藏选项卡：向上淡出（与入场方向相反，读起来是"让位"而不是"被抽走"） */
internal fun FloatingWindowService.hideSheet() {
    val container = sheetRoot ?: return
    if (container.visibility != View.VISIBLE) return
    OverlayMotion.exit(container) {
        container.visibility = View.GONE
        // 复位入场/退场留下的透明度与位移，下一次 showSheet 才是干净的自下而上
        container.alpha = 1f
        container.translationY = 0f
        // 面板本身也要收起：容器是窗口根视图，即使它自己 GONE，窗口根仍会被测量，
        // 面板留在 VISIBLE 会继续把窗口撑出同样高度 —— 一层看不见却可触摸的窗口
        // 会持续吃掉这块屏幕区域的操作。
        interactPanel?.visibility = View.GONE
        // 收起胶囊同理：它是答疑面板的入口，问题已经收起（答复完毕/任务结束）时不该留在屏幕上
        collapsedPill?.visibility = View.GONE
        interactCollapsed = false
        // 窗口尺寸复原为整宽：下一次交互滑进来时是完整的选项卡，而不是一条贴着右边缘的窄窗
        applySheetCollapsedWindow(false)
        // 丢掉"上次问了什么"的记录：留着它，系统切深浅色时旧问题会被重新摆回屏幕上
        lastInteractType = null
        lastInteractTitle = null
        lastInteractContent = null
        lastInteractOptions = null
    }
}

/**
 * 显示交互面板（批准/澄清/指导），供用户在悬浮窗内直接操作。
 * @param type approve=批准计划 / clarify=歧义澄清 / guide=需要指导 / null=隐藏
 * @param title 交互标题
 * @param content 交互内容
 * @param options 澄清选项文本列表（type=clarify 时有效）
 */
internal fun FloatingWindowService.showInteraction(type: String?, title: String?, content: String?, options: List<String>? = null) {
    handler.post {
        if (type == null) {
            hideSheet()
            hideKeyboard()
            return@post
        }
        // 记下入参：系统切深浅色时要用同一份入参把动态行重绘一遍
        lastInteractType = type
        lastInteractTitle = title
        lastInteractContent = content
        lastInteractOptions = options
        // 所有交互（批准 / 保存模板 / 澄清 / 指导）都渲染在同一个 interactPanel 里，
        // 而 interactPanel 挂在底部选项卡窗口上，顶部窗口只保留状态与跑马灯。
        // 所以这里必须做三件事，缺一样用户就操作不了：
        //   1) 面板置 VISIBLE —— 它的初值/重置值都是 GONE，只让它滑出容器是看不见的；
        //   2) 按类型重建按钮；
        //   3) showSheet() 让选项卡容器滑出来 —— 容器默认 GONE，
        //      之前 approve / savetemplate 走的是"顶部渲染"分支（面板早已不挂在顶部窗口），
        //      面板永远不可见，于是需要批准时用户点什么都没反应、任务一直挂着。
        interactRows?.removeAllViews()
        hintInput?.visibility = View.GONE
        hintInput?.setText("")
        interactActions?.removeAllViews()
        interactTitle?.text = title ?: "需要确认"
        interactContent?.text = content ?: ""
        // 每次新交互都从展开态开始：收起是"我现在要腾出手"，不该被下一个问题继承
        setInteractCollapsed(false)

        when (type) {
            "approve" -> {
                // 批准/取消
                addActionRow(
                    interactRows,
                    secondaryLabel = "取消",
                    secondaryClick = { FloatingWindowService.onInteraction?.invoke("cancel", "") },
                    primaryLabel = "批准并开始",
                    primaryClick = { FloatingWindowService.onInteraction?.invoke("approve", "") },
                )
            }
            "savetemplate" -> {
                // 任务完成：是否把执行步骤保存为模板（用户主动确认才入库）
                addActionRow(
                    interactRows,
                    secondaryLabel = "不保存",
                    secondaryClick = { FloatingWindowService.onInteraction?.invoke("save_template", "no") },
                    primaryLabel = "保存为模板",
                    primaryClick = { FloatingWindowService.onInteraction?.invoke("save_template", "yes") },
                )
            }
            "clarify" -> {
                // 选项行：一行一个、点一下即答，与 App 侧协助浮层的选项行同构
                options?.forEach { opt ->
                    addOptionRow(interactRows, opt) { FloatingWindowService.onInteraction?.invoke("clarify", opt) }
                }
                addOptionRow(interactRows, "✏️ 我想自己说", highlight = true) {
                    showHintInput()
                }
            }
            "guide" -> {
                showHintInput()
            }
            "shellconfirm" -> {
                // 自由模式：AI 自写命令的首次确认。批准后本任务内不再问；
                // 拒绝只针对本任务，不改动 Agent 页那条动作模式切换条的档位。
                addActionRow(
                    interactRows,
                    secondaryLabel = "拒绝",
                    secondaryClick = { FloatingWindowService.onInteraction?.invoke("shell_approve", "no") },
                    primaryLabel = "批准执行",
                    primaryClick = { FloatingWindowService.onInteraction?.invoke("shell_approve", "yes") },
                )
            }
        }
        showSheet()
    }
}

internal fun FloatingWindowService.showHintInput() {
    // 输入前切换窗口为可聚焦输入模式，保证软键盘能弹出
    setInputMode(true)
    hintInput?.requestFocus()
    hintInput?.visibility = View.VISIBLE
    interactActions?.removeAllViews()
    addActionRow(
        interactActions,
        secondaryLabel = "已手动处理",
        secondaryClick = { FloatingWindowService.onInteraction?.invoke("dismiss", "") },
        primaryLabel = "指导 AI",
        primaryClick = {
            val text = hintInput?.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                // 留空点击「指导 AI」等价「已手动处理」，避免空串被 provideUserHint 吞掉导致静默挂起
                FloatingWindowService.onInteraction?.invoke("dismiss", "")
            } else {
                FloatingWindowService.onInteraction?.invoke("hint", text)
            }
            hintInput?.setText("")
        },
    )
    // 延迟弹出软键盘，等待窗口布局完成后再唤起输入法
    hintInput?.postDelayed({
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(hintInput, InputMethodManager.SHOW_IMPLICIT)
    }, 200)
}

/** 切换悬浮窗输入模式：输入时窗口移除 NOT_FOCUSABLE 并加 FLAG_ALT_FOCUSABLE_IM 以弹出软键盘，
 *  输入结束恢复 NOT_FOCUSABLE，保证窗口始终可拖动且不抢占系统焦点 */
internal fun FloatingWindowService.setInputMode(enabled: Boolean) {
    // 输入框现在在底部选项卡里，焦点模式切换的是选项卡窗口；
    // 顶部窗口始终保持 NOT_FOCUSABLE（不抢输入焦点，也不影响拖动）
    val p = sheetParams ?: return
    val rootView = sheetRoot ?: return
    p.flags = if (enabled) {
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    } else {
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    runCatching { windowManager?.updateViewLayout(rootView, p) }
}

/**
 * 重绘答疑里的动态行（选项行 / 出口行）。
 *
 * 这些视图每次交互现建，登记不到静态表里，只能按上次的入参重新渲染一遍。
 * 重来之前先把"正在输入"这个状态和用户已经打好的字记下来，之后原样还回去 ——
 * 切主题不该让用户丢掉半句已经写完的回答。
 */
internal fun FloatingWindowService.rerenderInteractRows() {
    val type = lastInteractType ?: return
    // 收起的面板不重绘：它的可见内容只有一枚主色胶囊，本身就不随主题变
    if (interactCollapsed) return
    val draft = hintInput?.text?.toString().orEmpty()
    val inputOpen = hintInput?.visibility == View.VISIBLE
    // 重绘走 showInteraction 自己的队列（内部会 post 到主线程）；输入状态用一个后入队的
    // post 补回来 —— 同一个 Handler 是 FIFO，后入队的一定跑在重绘之后。
    showInteraction(type, lastInteractTitle, lastInteractContent, lastInteractOptions)
    if (!inputOpen) return
    handler.post {
        showHintInput()
        hintInput?.setText(draft)
        hintInput?.setSelection(draft.length)
    }
}

/**
 * 收起/展开答疑面板。
 *
 * 收起 = 把面板整块让出屏幕，只留右下角一枚入口胶囊（[collapsedPill]）；
 * 同时收起软键盘并恢复窗口的不可聚焦模式 —— 留着可聚焦的窗口会让底下 App 的输入法行为异常。
 * 问题本身不受影响：AI 仍在等这个答案，点胶囊即可原样展开继续回答。
 */
internal fun FloatingWindowService.setInteractCollapsed(collapsed: Boolean) {
    interactCollapsed = collapsed
    if (collapsed) hideKeyboard()
    interactPanel?.visibility = if (collapsed) View.GONE else View.VISIBLE
    collapsedPill?.visibility = if (collapsed) View.VISIBLE else View.GONE
    applySheetCollapsedWindow(collapsed)
}

/**
 * 收起时把窗口本身也收窄到胶囊大小。
 *
 * 只把面板置 GONE 还不够：窗口是 MATCH_PARENT 宽，收起来之后仍然是一条全宽、几十 dp 高、
 * 什么都看不见却能触摸的窗口，落在其中的操作全被它吃掉。收窄到 WRAP_CONTENT + 右对齐后，
 * 屏幕上只剩胶囊那一小块归悬浮窗，其余位置照常传给底下的 App。
 */
internal fun FloatingWindowService.applySheetCollapsedWindow(collapsed: Boolean) {
    val p = sheetParams ?: return
    val root = sheetRoot ?: return
    p.width = if (collapsed) {
        WindowManager.LayoutParams.WRAP_CONTENT
    } else {
        WindowManager.LayoutParams.MATCH_PARENT
    }
    p.gravity = if (collapsed) {
        Gravity.BOTTOM or Gravity.END
    } else {
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }
    runCatching { windowManager?.updateViewLayout(root, p) }
}

/**
 * 胶囊按钮：主操作 = 主题色实底白字，次要 = 下沉实底 + 发丝描边。
 * 两种态都是不透明表面（不再用半透明叠层做层次），悬浮在别的 App 之上也不会透出底图。
 */
internal fun FloatingWindowService.actionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(if (primary) palette.onBrand else palette.textPrimary)
        background = if (primary) {
            FloatingUi.capsule(self, FloatingUi.RADIUS_PILL, palette.brand)
        } else {
            FloatingUi.capsule(self, 
                FloatingUi.RADIUS_PILL,
                palette.panelSunken,
                palette.panelEdge,
                dp(1),
            )
        }
        setPadding(dp(FloatingUi.PAD_XL), 0, dp(FloatingUi.PAD_XL), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        setOnClickListener { onClick() }
    }.also { bindPress(it) }

/**
 * 出口行：次要在左、主操作在右（与 App 侧协助浮层的出口行同构）。
 * 两个按钮都靠内容定宽，中间用一条权重空隙把主操作推到最右 —— 主操作的落点固定，
 * 不会因为次要按钮文案长短而左右横跳。
 */
internal fun FloatingWindowService.addActionRow(
    container: LinearLayout?,
    secondaryLabel: String?,
    secondaryClick: (() -> Unit)?,
    primaryLabel: String,
    primaryClick: () -> Unit,
) {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(FloatingUi.PAD_L) }
    }
    if (secondaryLabel != null && secondaryClick != null) {
        row.addView(actionButton(secondaryLabel, false, secondaryClick))
    }
    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    row.addView(actionButton(primaryLabel, true, primaryClick))
    container?.addView(row)
}

/**
 * 选项行：一行一个、点一下即答（澄清候选答案）。
 * 圆角取第三层 RADIUS_TILE、底色取下沉实色 —— 与输入框同一档，读起来是"能点的行"而不是按钮。
 * [highlight] 用于「我想自己说」这种不是答案、而是"我要打字"的入口。
 */
internal fun FloatingWindowService.addOptionRow(container: LinearLayout?, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    val row = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(if (highlight) palette.brand else palette.textPrimary)
        background = FloatingUi.capsule(self, 
            FloatingUi.RADIUS_TILE,
            palette.panelSunken,
            palette.panelEdge,
            dp(1),
        )
        setPadding(dp(FloatingUi.PAD_L), dp(10), dp(FloatingUi.PAD_L), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(FloatingUi.PAD) }
        setOnClickListener { onClick() }
    }
    bindPress(row)
    container?.addView(row)
}

internal fun FloatingWindowService.hideKeyboard() {
    // 隐藏软键盘并恢复窗口原有的不可聚焦模式（可拖动、不抢占系统焦点）
    setInputMode(false)
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val focused = hintInput
    if (focused != null) imm?.hideSoftInputFromWindow(focused.windowToken, 0)
}
