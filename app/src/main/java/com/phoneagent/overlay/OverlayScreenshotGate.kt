// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.os.SystemClock
import android.view.View

/**
 * 截图时隐藏悬浮窗 / 截图后恢复，避免悬浮窗出现在 AI 读屏画面中。
 *
 * 返回「本次调用确实隐藏了内容」：进入 handler.post **前**记录各窗口当前可见性，
 * 只有原本可见的窗口才算本次隐藏（无实例或本来就全不可见时返回 false），
 * 调用方据此决定是否需要恢复，避免对「没藏东西」的调用做无谓的恢复动作。
 * 恢复分支会尊重用户手动隐藏状态（userHidden）：用户主动收起的面板不因截图恢复而弹回。
 */
// 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
@Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition")
internal fun setOverlayVisible(visible: Boolean): Boolean {
    // 条件由多个协议字段组合而成，抽成具名布尔量会分散逻辑，此处按规则豁免
    @Suppress("ComplexCondition")
    val svc = FloatingWindowService.instance ?: return false
    // 预读各窗口可见性用于同步返回值（post 内仍会按当前可见性权威记录再落位）
    if (!visible) {
        val rootShown = svc.root?.visibility == View.VISIBLE
        val marqueeShown = svc.isMarqueeVisible()
        val miniShown = svc.miniRoot?.visibility == View.VISIBLE
        val sheetShown = svc.sheetRoot?.visibility == View.VISIBLE
        val doneShown = svc.doneRoot?.visibility == View.VISIBLE
        val noticeShown = svc.noticeRoot?.visibility == View.VISIBLE
        if (!rootShown && !marqueeShown && !miniShown && !sheetShown && !doneShown && !noticeShown) {
            return false
        }
        // 条件由多个协议字段组合而成，抽成具名布尔量会分散逻辑，此处按规则豁免
        @Suppress("ComplexCondition")
        val hideStartedAt = SystemClock.uptimeMillis()
        svc.handler.post {
            svc.root?.visibility = View.GONE
            // 跑马灯是两层独立窗口（顶部光带 + 底部胶囊），截图时同样要藏起来；
            // 先记下本来的可见状态，截完按原样恢复（任务完成后跑马灯本来是隐藏的）
            svc.barVisibleBeforeHide = svc.isMarqueeVisible()
            svc.setMarqueeVisible(false)
            // 悬浮球同理：截图时藏起来（它是个不透明的圆形按钮，被截进画面会干扰 AI 读屏）
            svc.miniVisibleBeforeHide = svc.miniRoot?.visibility == View.VISIBLE
            svc.miniRoot?.visibility = View.GONE
            // 底部选项卡是独立窗口，截图时同样要藏起来，否则会被截进画面；
            // 截图前先记下它本来是否可见，截完按原样恢复
            svc.sheetVisibleBeforeHide = svc.sheetRoot?.visibility == View.VISIBLE
            svc.sheetRoot?.visibility = View.GONE
            // 完成卡片也是独立窗口：截图时藏起来，截完按原样恢复
            svc.doneVisibleBeforeHide = svc.doneRoot?.visibility == View.VISIBLE
            svc.doneRoot?.visibility = View.GONE
            // 降级提示细条同理：截图时不能截进画面，先停计时再藏，截完按原样恢复并重新计时
            svc.captureHidden = true
            svc.noticeVisibleBeforeHide = svc.noticeShowing
            svc.handler.removeCallbacks(svc.noticeAutoHide)
            svc.noticeRoot?.visibility = View.GONE
            // 隐藏是"落位"而不是"绘制"：GONE 的视图不会再有 onPreDraw，
            // 因此这里记「调用 → 落位」的代码耗时，不含任何帧等待
            OverlayPerf.code("screenshot-hide-code", hideStartedAt)
        }
        return true
    }
    val restoreStartedAt = SystemClock.uptimeMillis()
    svc.handler.post {
        // 用户主动收起（userHidden）时面板保持隐藏：截图恢复不能推翻用户的收起操作
        svc.root?.visibility = if (svc.userHidden) View.GONE else View.VISIBLE
        if (svc.barVisibleBeforeHide) {
            svc.setMarqueeVisible(true)
            svc.barVisibleBeforeHide = false
        }
        if (svc.miniVisibleBeforeHide) {
            svc.miniRoot?.visibility = View.VISIBLE
            svc.miniVisibleBeforeHide = false
        }
        if (svc.sheetVisibleBeforeHide) {
            svc.sheetRoot?.visibility = View.VISIBLE
            svc.sheetVisibleBeforeHide = false
        }
        if (svc.doneVisibleBeforeHide) {
            svc.doneRoot?.visibility = View.VISIBLE
            svc.doneVisibleBeforeHide = false
        }
        // 提示细条：截图期间到达的提示已被压住（只更新内容、不弹窗），这里一并恢复并重新计时
        svc.captureHidden = false
        if (svc.noticeVisibleBeforeHide || svc.noticeShowing) {
            svc.noticeVisibleBeforeHide = false
            svc.noticeRoot?.takeIf { !svc.userHidden }?.let { n ->
                n.visibility = View.VISIBLE
                OverlayMotion.reset(n)
                svc.noticeShowing = true
                svc.scheduleNoticeHide()
            }
        }
        // 恢复分两段：先记"代码落位"（不可见窗口重新置为 VISIBLE），
        // 再等首个真正绘制帧 —— 后者含软件渲染的合成等待，不能当作代码成本
        val restoreCode = SystemClock.uptimeMillis() - restoreStartedAt
        OverlayPerf.log("screenshot-restore-code", "${restoreCode}ms")
        val restoreTarget = svc.doneRoot?.takeIf { it.visibility == View.VISIBLE }
            ?: svc.root?.takeIf { it.visibility == View.VISIBLE }
            ?: svc.marquee?.takeIf { it.visibility == View.VISIBLE }
        if (restoreTarget != null) {
            OverlayPerf.firstFrame("screenshot-restore-first-frame", restoreTarget, restoreStartedAt,
                restoreCode)
        }
    }
    return true
}
