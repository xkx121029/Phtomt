package com.phoneagent.ui.components

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 液态玻璃效果组件
 *
 * 视觉层次:
 * 1. 半透明底色 (tint * alpha)
 * 2. 顶部折射高光 (模拟光线穿过玻璃的折射)
 * 3. 对角高光 (增强液态反射感)
 * 4. 底部阴影 (增强深度)
 * 5. 色散边缘 (R/B 通道微偏移，模拟色散)
 * 6. 细边框 (强化边界)
 *
 * 注意: 由于 Compose 原生 API 限制，此实现使用渐变近似液态玻璃效果。
 * 真正的背景模糊需要 RenderEffect (API 31+) 或 AGSL Shader (API 33+)。
 */

private object GlassDefaults {
    const val Alpha = 0.82f
    val CornerRadius = 28.dp
    val BorderWidth = 1.dp
    const val HighlightIntensity = 0.18f
}

/**
 * 液态玻璃 Modifier 扩展
 * 应用半透明、折射高光和色散边缘效果
 *
 * @param alpha 玻璃不透明度 (0.0-1.0)
 * @param cornerRadius 圆角半径
 * @param tint 玻璃底色
 * @param enabled 是否启用效果
 */
@Composable
fun Modifier.liquidGlass(
    alpha: Float = GlassDefaults.Alpha,
    cornerRadius: Dp = GlassDefaults.CornerRadius,
    tint: Color = MaterialTheme.colorScheme.surface,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this

    return this
        .liquidGlassCore(
            alpha = alpha,
            cornerRadius = cornerRadius,
            tint = tint,
        )
}

/**
 * 液态玻璃核心实现
 */
@Composable
private fun Modifier.liquidGlassCore(
    alpha: Float,
    cornerRadius: Dp,
    tint: Color,
): Modifier {
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .drawWithCache {
            val width = size.width
            val height = size.height
            val minEdge = min(width, height)

            // 高光区域大小 (基于容器尺寸的百分比)
            val highlightWidth = width * 0.12f
            val highlightHeight = height * 0.12f

            // 色散边缘宽度
            val dispersionWidth = minEdge * 0.015f

            onDrawWithContent {
                // 1. 半透明底色
                drawRect(color = tint.copy(alpha = alpha))

                // 2. 顶部折射高光 (模拟光线穿过玻璃的折射)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = GlassDefaults.HighlightIntensity),
                            Color.White.copy(alpha = GlassDefaults.HighlightIntensity * 0.5f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = highlightHeight
                    ),
                    size = Size(width, highlightHeight),
                    topLeft = Offset.Zero
                )

                // 3. 左上对角高光 - 增强液态反射感
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        start = Offset.Zero,
                        end = Offset(highlightWidth * 2, highlightHeight * 2)
                    ),
                    size = Size(highlightWidth * 2, highlightHeight * 2),
                    topLeft = Offset.Zero
                )

                // 4. 底部阴影渐变 (增强深度感)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            tint.copy(alpha = 0.08f)
                        ),
                        startY = height - highlightHeight,
                        endY = height
                    ),
                    size = Size(width, highlightHeight),
                    topLeft = Offset(0f, height - highlightHeight)
                )

                // 5. 色散边缘效果 (模拟 R/B 通道分离)
                if (dispersionWidth > 0f) {
                    // 左侧边缘 - R 通道偏移
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Red.copy(alpha = 0.2f),
                                Color.Red.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = dispersionWidth
                        ),
                        size = Size(dispersionWidth, height),
                        topLeft = Offset.Zero
                    )
                    // 右侧边缘 - B 通道偏移
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Blue.copy(alpha = 0.08f),
                                Color.Blue.copy(alpha = 0.2f)
                            ),
                            startX = width - dispersionWidth,
                            endX = width
                        ),
                        size = Size(dispersionWidth, height),
                        topLeft = Offset(width - dispersionWidth, 0f)
                    )
                    // 顶部边缘 - G 通道微偏移
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Green.copy(alpha = 0.12f),
                                Color.Green.copy(alpha = 0.04f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = dispersionWidth
                        ),
                        size = Size(width, dispersionWidth),
                        topLeft = Offset.Zero
                    )
                }

                // 绘制实际内容
                drawContent()
            }
        }
        .border(
            width = GlassDefaults.BorderWidth,
            brush = Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
            ),
            shape = RoundedCornerShape(cornerRadius)
        )
}

/**
 * 液态玻璃卡片 - 快捷包装组件
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    alpha: Float = GlassDefaults.Alpha,
    cornerRadius: Dp = GlassDefaults.CornerRadius,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier.liquidGlass(
            alpha = alpha,
            cornerRadius = cornerRadius,
        ),
        shape = RoundedCornerShape(cornerRadius),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
    ) {
        content()
    }
}

/**
 * 液态玻璃表面 - 用于导航栏、对话框等
 */
@Composable
fun Modifier.liquidGlassSurface(
    alpha: Float = GlassDefaults.Alpha,
    cornerRadius: Dp = 0.dp,
): Modifier {
    return this.liquidGlass(
        alpha = alpha,
        cornerRadius = cornerRadius,
    )
}