package com.phoneagent.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.SpringSpec

/**
 * Motion tokens based on emilkowalski/skills design engineering principles.
 *
 * Philosophy:
 * - Ease-out for entrances (starts fast, feels responsive)
 * - Ease-in-out for on-screen movement (natural acceleration/deceleration)
 * - Never use ease-in for UI — it starts slow and feels sluggish
 * - UI animations should stay under 300ms
 * - No bounce on button presses (bounce = momentum-driven gestures only)
 */

// --- Easing Tokens ---

/** Strong ease-out for UI interactions (cubic-bezier(0.23, 1, 0.32, 1)) */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** Strong ease-in-out for on-screen movement (cubic-bezier(0.77, 0, 0.175, 1)) */
val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/** iOS-like drawer curve (cubic-bezier(0.32, 0.72, 0, 1)) */
val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** Linear-out-slow-in: content leaving screen */
val EaseOutSlowIn = LinearOutSlowInEasing

/** Fast-out-linear-in: elements entering */
val EaseFastOut = FastOutLinearInEasing

// --- Duration Tokens (milliseconds) ---

/** Button press feedback — instant feel */
const val DurationInstant = 100

/** Small UI elements (tooltips, badges, icon transitions) */
const val DurationFast = 160

/** Standard UI (dropdowns, selects, card transitions) */
const val DurationNormal = 200

/** Large UI (modals, drawers, screen transitions) — never exceed 300ms for responsiveness */
const val DurationSlow = 280

/** Emphasis (celebrations, onboarding) — can be longer, but still restrained */
const val DurationEmphasis = 400

/**
 * 循环动效周期（毫秒）：状态点脉动、骨架微光、运行指示共用一个节拍。
 *
 * 循环动画不是"过渡"，不套用 100/160/200/280 那套一次性时长；单独给一个慢拍，
 * 全站所有"呼吸"才是同一口气（原先 800/900 两三种节拍混用，并排出现时会互相打架）。
 */
const val DurationPulse = 900

// --- Animation Decision Framework ---
//
// 1. Should this animate at all?
//    - 100+ times/day (keyboard, shortcuts) → NO animation
//    - Tens of times/day (hover, list nav) → remove or drastically reduce
//    - Occasional (modals, drawers, toasts) → standard animation
//    - Rare/first-time (onboarding, celebrations) → add delight
//
// 2. What is the purpose?
//    - Spatial consistency: toast enters/exits same direction
//    - State indication: morphing feedback shows state change
//    - Feedback: button scales down on press
//    - Preventing jarring changes
//
// 3. Which easing?
//    - Entering → EaseOut
//    - Moving/morphing → EaseInOut
//    - Hover/color change → EaseOut
//    - Constant motion (marquee) → Linear
//
// 4. How fast?
//    - Button press: DurationInstant (100ms)
//    - Tooltips: DurationFast (160ms)
//    - Dropdowns: DurationNormal (200ms)
//    - Modals: DurationSlow (280ms)

/**
 * Screen transition spec — symmetric paths with proper easing.
 * Principle: "If something disappears one way, we expect it to emerge from where it came."
 */
object ScreenTransitions {
    const val Duration = DurationSlow
    val Easing: Easing = EaseOut
}

/**
 * Spring constants for different interaction types.
 * Bounce is reserved ONLY for momentum-driven gestures (flick, drag release).
 * Button presses use critically-damped springs for instant feel.
 */
object SpringConfigs {
    /** Button press — critically damped, no bounce */
    const val ButtonStiffness = 400f
    const val ButtonDampingRatio = 1.0f

    /** Gesture with momentum — subtle bounce */
    const val GestureStiffness = 250f
    const val GestureDampingRatio = 0.85f

    /** Content movement — smooth settling */
    const val ContentStiffness = 300f
    const val ContentDampingRatio = 0.9f
}

/** 列表交错动画：相邻元素入场的延迟间隔（毫秒） */
const val StaggerIntervalMs = 45

/**
 * 列表项入场动画的弹簧规格。
 * 轻微回弹（阻尼 0.82）+ 适度刚度，比 tween 更有“响应感”，
 * 且符合 skills 规范：弹簧仅用于入场这类离散状态切换，按钮按压不用。
 */
fun <T> contentSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = 0.82f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * 计算列表第 [index] 项的入场延迟。
 * 使用 index * StaggerIntervalMs，形成逐项错开的“级联”入场效果。
 */
fun staggerDelayMs(index: Int): Int = index * StaggerIntervalMs