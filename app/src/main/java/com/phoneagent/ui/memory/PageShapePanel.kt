package com.phoneagent.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.data.store.PagePathEdge
import com.phoneagent.ui.components.OverlayScrim
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationInstant
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.motionSettings

/**
 * 页面形态下钻面板：底部对齐的嵌入式浮层（项目铁律：不用系统 Dialog）。
 *
 * 内容分两层：
 * - 主层是当前页面的「虚拟画面」（VirtualScreenCanvas 线框重建），
 *   有出边的控件整体高亮，点它即跳到下一页——空间树用"点"来走；
 * - 兜底层是出边路径列表，空间上不好点的（如整页菜单）从这里也能跳。
 *
 * 链式下钻：每次跳转把目标页压栈，顶部标题栏可逐级返回；换入口页时整栈重置。
 *
 * 与 InlineOverlay 同一套入场语言：MutableTransitionState 起手（首帧落位会吞掉入场动画），
 * 从下到上完整滑入——项目动画铁律只允许这一种方向。
 */
@Composable
internal fun PageShapePanel(
    entry: PageMemoryEntry,
    /** 该应用的全部出边（下钻与兜底列表都从这里筛） */
    edges: List<PagePathEdge>,
    /** 指纹 → 页面条目（下钻目标查找；查不到的出边不可跳，仅展示） */
    entryOf: (String) -> PageMemoryEntry?,
    onDismiss: () -> Unit,
) {
    val reduceMotion = motionSettings().reduceMotion
    // 与抽屉/InlineOverlay 同款起手：首帧就落位的话入场整段看不到
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { state.targetState = true }

    // 下钻栈：栈顶是当前展示的页面；换入口页（entry.id 变）时整栈重置
    var stack by remember(entry.id) { mutableStateOf(listOf(entry)) }
    val current = stack.last()
    val currentEdges = edges.filter { it.appPackage == current.appPackage && it.fromFp == current.fingerprint }

    // 有出边且控件标识非空的集合：虚拟画面据此高亮可跳的控件
    val linkedControls = currentEdges
        .filter { it.fromControl.isNotBlank() }
        .map { it.fromControl }
        .toSet()

    // 点控件下钻：semanticId 优先匹配，无 semanticId 的控件退回文字匹配（与写入侧 controlTag 对齐）
    fun drillTo(node: com.phoneagent.data.store.PageShapeNode) {
        val target = currentEdges.firstOrNull { e ->
            (e.fromControl == node.semanticId && node.semanticId.isNotBlank()) ||
                (node.semanticId.isBlank() && e.fromControl == node.label)
        }?.let { entryOf(it.toFp) } ?: return
        stack = stack + target
    }

    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(DurationNormal, easing = EaseOut)) +
            slideInVertically(tween(DurationNormal, easing = EaseOut)) { full ->
                if (reduceMotion) 0 else full
            },
        exit = fadeOut(tween(DurationInstant, easing = EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OverlayScrim)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = AppRadii.Overlay, topEnd = AppRadii.Overlay),
                color = AppTheme.colors.surfaceBase,
                modifier = Modifier
                    .fillMaxWidth()
                    // 面板自身吃掉点击，避免点内容穿透到遮罩上被当成"点了外面"
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                ) {
                    // 拖拽指示条
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(AppRadii.Chip))
                            .background(AppTheme.colors.outlineSoft)
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(12.dp))

                    // 标题栏：返回（下钻后出现）+ 页面名 + 关闭
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (stack.size > 1) {
                            PressableScale(onClick = { stack = stack.dropLast(1) }) {
                                Icon(
                                    AppIcons.ArrowBack,
                                    contentDescription = "返回上一页",
                                    tint = AppTheme.colors.onSurfaceBase,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                current.title.ifBlank { "未命名页面" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${current.appPackage.substringAfterLast('.')} · 来过 ${current.visitCount} 次 · ${current.shapes.size} 个控件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PressableScale(onClick = onDismiss) {
                            Icon(
                                AppIcons.Close,
                                contentDescription = "关闭",
                                tint = AppTheme.colors.onSurfaceBase.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 虚拟画面：点有出边的控件即下钻
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        VirtualScreenCanvas(
                            shapes = current.shapes,
                            linkedControls = linkedControls,
                            onTapControl = if (currentEdges.isEmpty()) null else ::drillTo,
                            modifier = Modifier.height(360.dp),
                        )
                    }

                    // 控件图例：让「线框语言」可自解释
                    Spacer(Modifier.height(10.dp))
                    ShapeLegend()

                    // 出边兜底列表：空间上不好点的入口从文字列表走
                    if (currentEdges.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "可跳转路径",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        currentEdges.forEach { e ->
                            val target = entryOf(e.toFp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .then(
                                        if (target != null) {
                                            Modifier.clip(RoundedCornerShape(AppRadii.Chip))
                                                .clickable { stack = stack + target }
                                                .padding(vertical = 4.dp)
                                        } else {
                                            Modifier.padding(vertical = 4.dp)
                                        },
                                    ),
                            ) {
                                Icon(
                                    AppIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = if (target != null) AppTheme.colors.brand
                                           else AppTheme.colors.outlineSoft,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    buildString {
                                        append(e.actionLabel)
                                        if (e.fromControl.isNotBlank()) append("（id=${e.fromControl}）")
                                        append(" → ")
                                        append(target?.title?.ifBlank { null } ?: "其他页面")
                                        append("（走过 ${e.count} 次）")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 线框语言图例：一行小字说明各形态画法，免去用户猜 */
@Composable
private fun ShapeLegend() {
    val items = listOf(
        "描边角标" to "可点按钮",
        "下划线" to "输入框",
        "叉线" to "图片",
        "胶囊" to "开关",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { (shape, meaning) ->
            Text(
                "$shape=$meaning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
