package com.phoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppTheme

/**
 * 顶栏渐隐遮罩。
 *
 * 解决的问题：固定顶栏与滚动内容之间用一条实线分隔会显得生硬，
 * 内容滚到顶栏下沿时「啪」地消失。
 *
 * 做法：在顶栏下沿叠一层竖直渐变，颜色从页面底色（完全不透明）分五段过渡到透明，
 * 内容滚进来时像是慢慢溶进底色里，而不是被硬切一刀。
 * 五段而非两段，是为了让中段衰减更快（人眼对渐变末端的突变很敏感），
 * 避免出现一条可见的「灰带」。
 *
 * 注意：本组件只画背景，不加任何 pointerInput，不会拦截滚动手势。
 *
 * [alpha] 由调用方按滚动位置驱动：**没有滚动时应当为 0**，
 * 否则遮罩会白白吃掉内容首项的可视区（它只该在内容真的被截断时才存在）。
 *
 * @param height 渐隐高度。太矮（<48dp）会显得像阴影，太高会吞掉可读内容。
 * @param topColor 起始色，默认取当前主题的页面底色，保证与顶栏无缝衔接。
 * @param alpha 整体不透明度（0 = 完全不画）。
 */
@Composable
fun TopFadeScrim(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    topColor: Color = AppTheme.colors.topFadeTop,
    alpha: Float = 1f,
) {
    if (alpha <= 0.01f) return
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to topColor,
                        0.34f to topColor.copy(alpha = 0.92f),
                        0.62f to topColor.copy(alpha = 0.52f),
                        0.82f to topColor.copy(alpha = 0.18f),
                        1.00f to Color.Transparent,
                    )
                )
            )
    )
}