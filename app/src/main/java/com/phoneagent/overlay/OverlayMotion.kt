package com.phoneagent.overlay

import android.view.View
import android.view.animation.PathInterpolator
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.DurationPulse
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.PressScale

/**
 * 浮层动效令牌：时长、曲线、位移距离的唯一出处。
 *
 * 为什么单独建一份而不是散在各处写 `dp(12)` / `PathInterpolator(0.23f, …)`：
 * 浮层由六七个独立窗口组成（任务卡、跑马灯、底部选项卡、完成卡、悬浮球、边缘光），
 * 每个窗口都自己写一遍入场时长和曲线，结果就是同一次任务里几块面板的节奏各不相同。
 * 收口后改一次全局生效，行为也不再依赖"记得改另一处"。
 *
 * 规则（与项目动效铁律一致）：
 * - 入场只有一种：**自下而上**（位移 + 淡入），禁止水平滑入、禁止缩放弹出；
 * - 退场只有一种：**向上淡出**，禁止向下抽走、禁止左右滑出；
 * - 循环动效（呼吸点 / 边缘光）走 [PulseMs]，与 App 内骨架屏同一个节拍；
 * - 按压反馈**在 ACTION_DOWN 当帧**给出（[pressDown] 直接改 scale，不起动画），
 *   抬手才走 [ReleaseMs] 的回弹。等系统 ripple 会有一次跨帧延迟，手指已经抬起才看到按下态。
 * - 时长只引用 `ui/theme/Motion` 的常量，这里不再出现 160/200/280/400 这类字面值。
 */
object OverlayMotion {

    // ---- 时长（毫秒）----
    /** 入场：普通面板（200ms，与 App 内 Modal 一致） */
    const val EnterMs = DurationNormal

    /** 退场：比入场快一档，离开不需要被看清 */
    const val ExitMs = DurationFast

    /** 强调入场：完成卡片这类"压轴"面板（280ms） */
    const val EmphasisMs = DurationSlow

    /** 循环动效周期（呼吸点 / 边缘光脉冲） */
    const val PulseMs = DurationPulse

    /** 按压回弹时长；按下本身是 0ms（当帧生效） */
    const val ReleaseMs = DurationFast

    // ---- 位移（dp）----
    /** 入场起点：位于目标位置下方 12dp，向上走进位 */
    const val EnterOffsetDp = 12f

    /** 退场终点：向上 8dp，同时淡出 */
    const val ExitOffsetDp = 8f

    // ---- 曲线 ----
    /**
     * 标准减速曲线（M3 emphasized decelerate 的近似）：起步快、收尾缓。
     * 入场与"落位/回弹"都用它——落位必须收得住，否则看起来像被弹簧甩了一下。
     */
    val EaseOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    /** 加速曲线：只用于退场，离开要干脆 */
    val EaseIn = PathInterpolator(0.4f, 0f, 1f, 1f)

    // ---- 按压 ----
    /** 按下缩放比例（与 App 按钮同一档） */
    const val PressedScale = PressScale

    /** 按下当帧直接落位：不起动画，避免"手指按下 → 等一帧 → 才看到反馈" */
    fun pressDown(view: View) {
        view.animate().cancel()
        view.scaleX = PressedScale
        view.scaleY = PressedScale
    }

    /** 抬手回弹：从按压比例回到 1 */
    fun pressUp(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ReleaseMs.toLong())
            .setInterpolator(EaseOut)
            .start()
    }

    /**
     * 入场：自下而上 + 淡入。视图调用前必须已是 VISIBLE（或由调用方负责置为可见）。
     *
     * @param fromBottom 从下方多少 dp 走进位，默认 [EnterOffsetDp]
     */
    @JvmOverloads
    fun enter(view: View, fromBottom: Float = EnterOffsetDp, durationMs: Int = EnterMs) {
        val px = fromBottom * view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = px
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(durationMs.toLong())
            .setInterpolator(EaseOut)
            .start()
    }

    /**
     * 退场：向上淡出，结束后回调 [onEnd]。
     *
     * 刻意不做"向下抽走"：向下退场会被读成"被系统收回了"，而向上淡出是"让位给下一层"。
     */
    @JvmOverloads
    fun exit(view: View, toTop: Float = ExitOffsetDp, durationMs: Int = ExitMs, onEnd: (() -> Unit)? = null) {
        val px = toTop * view.resources.displayMetrics.density
        view.animate().cancel()
        val animator = view.animate()
            .alpha(0f)
            .translationY(-px)
            .setDuration(durationMs.toLong())
            .setInterpolator(EaseIn)
        if (onEnd != null) animator.withEndAction(onEnd)
        animator.start()
    }

    /** 复位入场/退场留下的位移与透明度（复用窗口前调用，避免下一次入场从"半透明+偏移"开始） */
    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }
}
