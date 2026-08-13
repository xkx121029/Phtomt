package com.phoneagent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Returns a haptic feedback function for use on press-down, not on click.
 *
 * Design principle (emilkowalski/skills):
 * "Respond on pointer-down, not on release. Feedback must be continuous
 * during the interaction, not just at the end."
 *
 * Use [rememberHapticPress] for instant press feedback on buttons/cards.
 * Use [rememberHapticCommit] for confirmation feedback on successful actions.
 */
@Composable
fun rememberHapticPress(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

@Composable
fun rememberHapticCommit(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

/**
 * @deprecated Use [rememberHapticPress] for press-down feedback or
 * [rememberHapticCommit] for confirmation feedback.
 * Kept for backward compatibility — will trigger on release.
 */
@Composable
fun rememberHapticClick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}