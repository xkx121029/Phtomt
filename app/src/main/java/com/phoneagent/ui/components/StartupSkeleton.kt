package com.phoneagent.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** 骨架块透明度区间：够暗让主色看得见，够亮让人看出「在加载」 */
private const val SKELETON_ALPHA_MIN = 0.10f
private const val SKELETON_ALPHA_MAX = 0.22f

/**
 * 启动骨架屏：门控还没放行（引导状态未定）时占位。
 *
 * 为什么不是纯色：纯色 Box 与 `windowBackground` 观感一样，用户看到的就是「点了图标先白一下」。
 * 这里按主页面的版式铺出顶栏、内容块与底部输入框的灰块，并把透明度做成缓慢呼吸，
 * 观感从「白屏」变成「界面已在、内容待填」。
 *
 * 只用 `MaterialTheme.colorScheme` 与半透明叠加：不接 Haze、不读任何数据，
 * 成本只有一次组合 + 一次绘制，且深浅皮肤（legacy / graphite）都自动跟着主题走。
 */
@Composable
fun StartupSkeleton(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val breathe = rememberInfiniteTransition(label = "startup-skeleton")
        .animateFloat(
            initialValue = SKELETON_ALPHA_MIN,
            targetValue = SKELETON_ALPHA_MAX,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "startup-skeleton-alpha",
        )
    // 用前景色压在半透明上：浅底上是深灰块，深底上是浅灰块，两种皮肤都看得见
    val block = scheme.onSurface
    val alpha = breathe.value
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(block, alpha, Modifier.size(40.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(block, alpha, Modifier.fillMaxWidth(0.45f).height(14.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBlock(block, alpha, Modifier.fillMaxWidth(0.30f).height(10.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        // 三行内容块 + 一块卡片：对应主页「问候 / 能力卡 / 快捷入口」的大致版式
        SkeletonBlock(block, alpha, Modifier.fillMaxWidth(0.9f).height(20.dp))
        Spacer(Modifier.height(18.dp))
        SkeletonBlock(block, alpha, Modifier.fillMaxWidth().height(96.dp))
        Spacer(Modifier.height(18.dp))
        SkeletonBlock(block, alpha, Modifier.fillMaxWidth().height(20.dp))
        Spacer(Modifier.weight(1f))
        SkeletonBlock(block, alpha, Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(26.dp))
    }
}

@Composable
private fun SkeletonBlock(
    color: Color,
    alpha: Float,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(modifier.clip(shape).background(color.copy(alpha = alpha)))
}
