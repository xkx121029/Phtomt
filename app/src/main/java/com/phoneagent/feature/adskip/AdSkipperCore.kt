package com.phoneagent.feature.adskip.AdSkipperCore

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.effectiveLabel
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内置跳广告引擎：从屏幕元素树中识别广告的跳过/关闭控件，并屏蔽青少年模式弹窗。
 *
 * 独立于 Agent 运行；由无障碍服务在 Agent 空闲时事件驱动触发，避免与 Agent 执行冲突。
 * 内置冷却（同一目标短时间不重复点击）与成功计数，防止误触和死循环。
 *
 * 识别优先级：青少年模式弹窗 > 显式"跳过"按钮 > 纯倒计时角标 > 关闭按钮（右上 → 弹窗底部）。
 * 识别规则刻意收紧（精确匹配 / 位置约束 / 控件类型约束），以降低误触正常界面的灵敏度。
 */
object AdSkipperCore {

    /** 识别到的点击目标 */
    data class Target(
        val x: Int,
        val y: Int,
        val reason: String,
        val fingerprint: String,
    )

    /** 成功跳过广告/关闭弹窗的次数（供 UI 展示） */
    val skippedCount = AtomicInteger(0)

    // —— 识别词库 ——

    /** 青少年模式 / 防沉迷弹窗关键字 */
    private val teenMarks = listOf("青少年模式", "青少年保护", "防沉迷", "未成年人模式")

    /** 青少年模式弹窗的关闭按钮文案 */
    private val teenDismissMarks = listOf("我知道了", "知道了", "暂不开启", "暂不", "放弃", "关闭", "取消", "跳过")

    /** 显式跳过广告按钮核心词（精确匹配，避免误伤"跳过登录/跳过验证"等正常按钮） */
    private val skipExactMarks = listOf("跳过", "跳過", "略过", "跳过广告", "skip", "skip ad")

    /** 弹窗 / 广告关闭按钮文案（不含单字符叉号，单字符叉号另行处理） */
    private val closeMarks = listOf("关闭", "知道了", "我知道了", "暂不开启", "取消", "done", "close")

    /** 单字符叉号：仅当为图片类控件时才视为广告关闭按钮，避免误触输入框清除、标签页关闭等 */
    private val singleCloseChars = setOf("✕", "×", "X", "x")

    /** 纯倒计时角标：必须带 s 后缀且数值较小（如 "5s"/"3s"），仅右上部；纯数字如 "5" 可能是视频/直播计时，不触发 */
    private val countdownRegex = Regex("""^(\d{1,2})s$""")

    // 冷却去重：同一目标在窗口期内不重复点击
    @Volatile
    private var lastFingerprint: String? = null
    @Volatile
    private var lastClickAt = 0L
    private const val COOLDOWN_MS = 2500L

    /**
     * 扫描快照，返回应点击的广告/弹窗目标；未发现时返回 null。
     * 优先级：青少年模式弹窗 > 显式"跳过"按钮 > 纯倒计时角标 > 关闭按钮（右上 → 弹窗底部）。
     */
    fun find(snapshot: ScreenSnapshot, now: Long = System.currentTimeMillis()): Target? {
        val elements = snapshot.elements
        if (elements.isEmpty()) return null

        // 1. 青少年模式 / 防沉迷弹窗：优先识别并关闭
        val isTeenPopup = elements.any { el ->
            val label = el.effectiveLabel() ?: return@any false
            teenMarks.any { label.contains(it) }
        }
        if (isTeenPopup) {
            elements.firstOrNull { el ->
                val label = (el.effectiveLabel() ?: "").trim()
                el.clickable && teenDismissMarks.any { it == label || label.contains(it) }
            }?.let { return makeTarget(it, "关闭青少年模式弹窗") }
        }

        // 2. 显式跳过广告按钮（最高可信度）：必须可点击，且剔除倒计时后缀后与核心词精确匹配
        elements.firstOrNull { el ->
            if (!el.clickable) return@firstOrNull false
            val label = (el.effectiveLabel() ?: "").trim()
            label.length <= 12 && isSkipLabel(label)
        }?.let { return makeTarget(it, "点击跳过广告") }

        // 3. 倒计时角标（弱信号）：仅右上部、带 s 后缀的小倒计时（开屏广告典型形态）
        val w = if (snapshot.screenWidth > 0) snapshot.screenWidth else 1080
        val h = if (snapshot.screenHeight > 0) snapshot.screenHeight else 2400
        elements.firstOrNull { el ->
            val label = (el.effectiveLabel() ?: "").trim()
            val m = countdownRegex.matchEntire(label)
            if (m == null) return@firstOrNull false
            val sec = m.groupValues[1].toIntOrNull() ?: return@firstOrNull false
            sec in 1..30 && el.left >= w * 0.5f && el.top < h * 0.35f
        }?.let { return makeTarget(it, "点击倒计时广告角标") }

        // 4. 关闭按钮：优先右上角，其次弹窗底部
        fun matchClose(el: UiElement): Boolean {
            val label = (el.effectiveLabel() ?: "").trim()
            // 单字符叉号（✕/×/X/x）仅当为图片类控件时才视为广告关闭按钮
            if (label.length == 1 && label in singleCloseChars) {
                return el.className.contains("Image", ignoreCase = true)
            }
            return closeMarks.any { it == label || (it.length > 1 && label.contains(it)) }
        }
        // 4a. 右上角关闭（开屏广告常见）
        elements.firstOrNull { el ->
            el.clickable && matchClose(el) && el.top < h * 0.35f && el.left >= w * 0.5f
        }?.let { return makeTarget(it, "点击右上角关闭按钮") }
        // 4b. 弹窗底部关闭（"知道了"/"关闭"等）
        elements.firstOrNull { el ->
            el.clickable && matchClose(el) && el.top >= h * 0.45f
        }?.let { return makeTarget(it, "点击弹窗关闭按钮") }

        return null
    }

    /** 目标是否处于冷却期（同一目标短时间内不重复点击） */
    fun isCooldown(target: Target, now: Long = System.currentTimeMillis()): Boolean =
        target.fingerprint == lastFingerprint && (now - lastClickAt) < COOLDOWN_MS

    /** 记录一次点击，用于冷却去重 */
    fun recordClick(target: Target, now: Long = System.currentTimeMillis()) {
        lastFingerprint = target.fingerprint
        lastClickAt = now
    }

    /** 判断是否为广告"跳过"按钮文案：剔除尾部倒计时/括号等干扰后与核心词精确匹配 */
    private fun isSkipLabel(raw: String): Boolean {
        val clean = raw
            .replace(Regex("""\s*[\d一二三四五六七八九十]+\s*s?$"""), "")
            .replace(Regex("""[()（）\[\]]"""), "")
            .trim()
        return clean == "跳过" || clean == "跳過" || clean == "略过" ||
            clean == "跳过广告" || clean.equals("skip", true) || clean.equals("skip ad", true)
    }

    private fun makeTarget(el: UiElement, reason: String): Target = Target(
        x = el.centerX,
        y = el.centerY,
        reason = reason,
        fingerprint = "${el.packageName}|${el.text}|${el.contentDescription}|${el.centerX},${el.centerY}",
    )
}
