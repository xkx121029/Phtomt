package com.phoneagent.ui.agent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.motionSettings
import kotlin.math.abs

/** 轨道宽度：与列表项之间留出安全距离，太宽会挤压内容 */
private val RailWidth = 14.dp

/** 可见刻度上限：步数更多时改用「当前步居中的窗口」，避免密排成栅格 */
private const val RailMaxTicks = 8

/** 拖动超过该距离才接管手势，保证未超阈值时列表还能正常滚动 */
private val RailDragThreshold = 16.dp

/**
 * 任务时间线轨道：静态进度指示 + 可选拖动/点击跳转。
 *
 * 作为**单个语义节点**对外（不逐条建节点）；`onSeek == null` 时完全不接管手势，
 * 仅供运行中当进度条用（运行中用户目标是看最新，不允许跳转）。
 */
@Composable
internal fun AgentStepRail(
    totalSteps: Int,
    completed: Int,
    current: Int,
    modifier: Modifier = Modifier,
    onSeek: ((Int) -> Unit)? = null,
) {
    // 一两步的任务不值得再画一条轨道
    if (totalSteps < 2) return

    val colors = AppTheme.colors
    val reduceMotion = motionSettings().reduceMotion

    val windowSize = minOf(totalSteps, RailMaxTicks)
    val windowStart = if (totalSteps <= RailMaxTicks) {
        0
    } else {
        (current - windowSize / 2).coerceIn(0, totalSteps - windowSize)
    }
    val activeIndex by animateFloatAsState(
        targetValue = (current - windowStart).toFloat().coerceIn(0f, (windowSize - 1).toFloat()),
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(DurationNormal, easing = EaseOut)
        },
        label = "rail-active-index",
    )

    val seek = onSeek
    val gesture = if (seek == null) {
        Modifier
    } else {
        Modifier.pointerInput(windowStart, windowSize, totalSteps) {
            val thresholdPx = RailDragThreshold.toPx()
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var engaged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 抬手时统一落点：纯点击也能跳转
                            val offset = ((change.position.y / size.height) * windowSize)
                                .toInt().coerceIn(0, windowSize - 1)
                            seek(windowStart + offset)
                            break
                        }
                        if (!engaged && abs(change.position.y - down.position.y) >= thresholdPx) {
                            engaged = true
                        }
                        if (engaged) {
                            change.consume()
                            val offset = ((change.position.y / size.height) * windowSize)
                                .toInt().coerceIn(0, windowSize - 1)
                            seek(windowStart + offset)
                        }
                    }
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .width(RailWidth)
            .fillMaxHeight()
            .then(gesture)
            .semantics(mergeDescendants = true) {
                contentDescription = "任务进度：第 $current 步，共 $totalSteps 步"
            },
    ) {
        val gap = size.height / windowSize
        val baseWidth = 3.dp.toPx()
        val activeWidth = 4.dp.toPx()
        for (i in 0 until windowSize) {
            val index = windowStart + i
            val isActive = abs(i - activeIndex) < 0.5f
            val centerY = gap * (i + 0.5f)
            val tickHeight = if (isActive) {
                minOf(gap * 0.72f, 22.dp.toPx())
            } else {
                minOf(gap * 0.5f, 12.dp.toPx())
            }
            val tickWidth = if (isActive) activeWidth else baseWidth
            val tickColor = when {
                isActive -> colors.railActive
                index < completed -> colors.railDone
                else -> colors.railIdle
            }
            drawRoundRect(
                color = tickColor,
                topLeft = Offset(size.width / 2f - tickWidth / 2f, centerY - tickHeight / 2f),
                size = Size(tickWidth, tickHeight),
                cornerRadius = CornerRadius(tickWidth / 2f, tickWidth / 2f),
            )
        }
    }
}