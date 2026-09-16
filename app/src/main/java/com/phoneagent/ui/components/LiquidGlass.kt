package com.phoneagent.ui.components.LiquidGlass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.motionTweenSpec
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 液态玻璃效果组件
 *
 * 视觉层次（借鉴 canvas-ui Glass 组件的物理光学模型，用渐变近似实现）:
 * 1. 半透明底色 (tint * alpha)
 * 2. 菲涅尔边缘反射（边缘反射强、中心透，模拟真实玻璃的掠射反射）
 * 3. 顶部折射高光 + 左上对角高光 + 静态光斑（模拟镜面 shine）
 * 4. 底部阴影（增强深度）
 * 5. 光谱分离色散（边缘 R/G/B 三通道叠加，模拟棱镜虹彩 / chromatic aberration）
 * 6. 细边框（强化边界）
 *
 * 注意: 由于 Compose 原生 API 限制，此实现用渐变近似液态玻璃效果；
 * 真正的背景模糊/折射需要 RenderEffect (API 31+) 或 AGSL Shader (API 33+)，
 * 且悬浮窗无法读取背后的屏幕内容。
 */

private object GlassDefaults {
    const val Alpha = 0.82f
    val CornerRadius = 28.dp
    val BorderWidth = 1.dp
    const val HighlightIntensity = 0.18f
}

/**
 * 液态玻璃 Modifier 扩展
 * 应用半透明、菲涅尔反射、折射高光和光谱分离色散效果
 *
 * @param alpha 玻璃不透明度 (0.0-1.0)
 * @param cornerRadius 圆角半径
 * @param tint 玻璃底色
 * @param enabled 是否启用效果
 * @param animateEntry 是否播放入场动画（淡入 + 轻微缩放，用于卡片首次出现）
 * @param entryDelayMs 入场动画的延迟毫秒数（列表交错动画使用）
 */
@Composable
fun Modifier.liquidGlass(
    alpha: Float = GlassDefaults.Alpha,
    cornerRadius: Dp = GlassDefaults.CornerRadius,
    tint: Color = MaterialTheme.colorScheme.surface,
    enabled: Boolean = true,
    animateEntry: Boolean = false,
    entryDelayMs: Int = 0,
): Modifier {
    if (!enabled) return this

    val glass = this.liquidGlassCore(
        alpha = alpha,
        cornerRadius = cornerRadius,
        tint = tint,
    )

    if (!animateEntry) return glass

    // 入场动画：淡入 + 轻微缩放（尊重系统“减少动画”设置）
    val spec = motionTweenSpec<Float>(DurationNormal)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (entryDelayMs > 0) delay(entryDelayMs.toLong())
        entered = true
    }
    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spec,
        label = "glass-entry-alpha",
    )
    val entryScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.96f,
        animationSpec = spec,
        label = "glass-entry-scale",
    )
    return glass.graphicsLayer {
        // 使用 this. 显式访问 GraphicsLayerScope 属性，避免与方法参数 alpha 同名遮蔽
        this.alpha = entryAlpha
        scaleX = entryScale
        scaleY = entryScale
    }
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
            // 菲涅尔边缘反射带宽度
            val fresnelW = minEdge * 0.06f
            // 光谱分离色散单通道宽度
            val iriW = minEdge * 0.011f
            // 光斑半径
            val glintR = minEdge * 0.35f

            onDrawWithContent {
                // 1. 半透明底色
                drawRect(color = tint.copy(alpha = alpha))

                // 2. 菲涅尔边缘反射（边缘反射强、中心透，越靠边越亮）
                if (fresnelW > 0f) {
                    // 上缘
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.24f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = fresnelW
                        ),
                        size = Size(width, fresnelW),
                        topLeft = Offset.Zero
                    )
                    // 下缘
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.12f)
                            ),
                            startY = height - fresnelW,
                            endY = height
                        ),
                        size = Size(width, fresnelW),
                        topLeft = Offset(0f, height - fresnelW)
                    )
                    // 左缘
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = fresnelW
                        ),
                        size = Size(fresnelW, height),
                        topLeft = Offset.Zero
                    )
                    // 右缘
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.14f)
                            ),
                            startX = width - fresnelW,
                            endX = width
                        ),
                        size = Size(fresnelW, height),
                        topLeft = Offset(width - fresnelW, 0f)
                    )
                }

                // 3. 顶部折射高光 (模拟光线穿过玻璃的折射)
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

                // 4. 左上对角高光 + 静态光斑（镜面 shine）
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
                // 静态光斑：左上柔光点，强化玻璃光泽
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(width * 0.18f, height * 0.16f),
                        radius = glintR
                    ),
                    radius = glintR,
                    center = Offset(width * 0.18f, height * 0.16f)
                )

                // 5. 底部阴影渐变 (增强深度感)
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

                // 6. 光谱分离色散（R/G/B 三通道叠加，模拟棱镜虹彩）
                if (iriW > 0f) {
                    val w3 = iriW * 3
                    // 左缘：红→绿→蓝（从边缘向内的虹彩）
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFF5A6E).copy(alpha = 0.28f),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = iriW
                        ),
                        size = Size(iriW, height),
                        topLeft = Offset.Zero
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF5AFF8A).copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            startX = iriW,
                            endX = iriW * 2
                        ),
                        size = Size(iriW, height),
                        topLeft = Offset(iriW, 0f)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF5A5AFF).copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            startX = iriW * 2,
                            endX = iriW * 3
                        ),
                        size = Size(iriW, height),
                        topLeft = Offset(iriW * 2, 0f)
                    )
                    // 右缘：蓝→绿→红（从边缘向内的虹彩，与左缘镜像）
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF5A5AFF).copy(alpha = 0.20f)
                            ),
                            startX = width - w3,
                            endX = width - iriW * 2
                        ),
                        size = Size(iriW, height),
                        topLeft = Offset(width - w3, 0f)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF5AFF8A).copy(alpha = 0.12f)
                            ),
                            startX = width - iriW * 2,
                            endX = width - iriW
                        ),
                        size = Size(iriW, height),
                        topLeft = Offset(width - iriW * 2, 0f)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFFF5A6E).copy(alpha = 0.28f)
                            ),
                            startX = width - iriW,
                            endX = width
                        ),
                        size = Size(iriW, height),
                        topLeft = Offset(width - iriW, 0f)
                    )
                    // 顶缘：翠绿微彩
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF5AFF8A).copy(alpha = 0.16f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = iriW
                        ),
                        size = Size(width, iriW),
                        topLeft = Offset.Zero
                    )
                    // 底缘：淡紫微彩
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFC25AFF).copy(alpha = 0.14f)
                            ),
                            startY = height - iriW,
                            endY = height
                        ),
                        size = Size(width, iriW),
                        topLeft = Offset(0f, height - iriW)
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
