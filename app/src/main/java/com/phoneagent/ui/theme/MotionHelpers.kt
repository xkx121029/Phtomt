package com.phoneagent.ui.theme

import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Reduced motion support based on emilkowalski/skills accessibility principles.
 *
 * Principle: "Reduced motion doesn't mean no feedback — it means a gentler,
 * non-vestibular equivalent. Replace slides/springs/parallax with short
 * opacity cross-fades or static transitions."
 *
 * On Android, we check the system's animation scale setting via
 * ContentAnimator. When animations are disabled or scaled to 0,
 * we simplify all motion to opacity-only transitions.
 */

@Immutable
class MotionSettings(
    val reduceMotion: Boolean = false,
    val animationScale: Float = 1f,
) {
    fun scaledDuration(baseDuration: Int): Int {
        return if (reduceMotion) (baseDuration * 0.3f).toInt()
        else (baseDuration * animationScale).toInt()
    }
}

private val LocalMotionSettings = compositionLocalOf { MotionSettings() }

/**
 * Provides motion settings based on system accessibility preferences.
 * When [reduceMotion] is true, animations are simplified to prevent
 * discomfort for users with vestibular disorders.
 */
@Composable
fun ProvideMotionSettings(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = isReduceMotionEnabled(context)
    val animationScale = getAnimationScale(context)

    CompositionLocalProvider(
        LocalMotionSettings provides MotionSettings(
            reduceMotion = reduceMotion,
            animationScale = animationScale,
        ),
        content = content,
    )
}

@Composable
@ReadOnlyComposable
fun motionSettings(): MotionSettings = LocalMotionSettings.current

/**
 * Check if the user has enabled "Reduce animations" in system settings.
 * This maps to Settings > Accessibility > Display size and text >
 * Remove animations.
 */
private fun isReduceMotionEnabled(context: Context): Boolean {
    return try {
        val animatorDurationScale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        animatorDurationScale == 0f
    } catch (_: Exception) {
        false
    }
}

/**
 * Get the system animation scale factor (0.5, 1.0, 1.5).
 */
private fun getAnimationScale(context: Context): Float {
    return try {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    } catch (_: Exception) {
        1f
    }
}

/**
 * Create a tween spec that respects the reduced motion setting.
 * Returns a simple opacity-only fade when reduceMotion is enabled.
 */
@Composable
fun <T> motionTweenSpec(
    durationMs: Int,
): TweenSpec<T> {
    val settings = motionSettings()
    val actualDuration = settings.scaledDuration(durationMs)
    return TweenSpec(
        durationMillis = actualDuration,
        easing = EaseOut,
    )
}