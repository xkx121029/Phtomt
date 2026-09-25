package com.phoneagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationInstant
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.DurationPulse
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.SpringConfigs
import com.phoneagent.ui.theme.Warning
import com.phoneagent.ui.theme.contentSpringSpec
import com.phoneagent.ui.theme.motionSettings
import com.phoneagent.ui.theme.staggerDelayMs
import kotlinx.coroutines.delay
import com.phoneagent.ui.icons.AppIcons
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 底部悬浮导航栏给页面留出的净空高度。
 *
 * 导航栏是浮在内容之上的圆角条（不再由 Scaffold 整段预留底部空间），
 * 因此各页要自己把底部元素/滚动内容垫高这么多，否则最后一条会被压在条下面。
 * 导航栏隐藏时（全屏二级页、键盘弹出）由宿主提供 0，页面按原样铺满。
 */
val LocalBottomNavClearance = staticCompositionLocalOf { 0.dp }

/**
 * 全局 Snackbar：宿主（MainActivity）注入，页面内直接取用。
 *
 * 项目铁律是不使用系统 Toast——它的字体、圆角、位置全由系统决定，
 * 和页面里其余反馈不是一套语言。轻量反馈统一走「页面内浮条」。
 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarState?> { null }

/** 浮层遮罩：抽屉与内嵌浮层共用同一层压暗底，避免每个浮层自己调一个黑度 */
val OverlayScrim: Color
    @Composable get() = MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f)

/**
 * Button press feedback — scale(0.97) on press with critically-damped spring.
 *
 * Design principles (emilkowalski/skills):
 * 1. "Buttons must feel responsive — add transform: scale(0.97) on :active.
 *    This gives instant feedback, making the UI feel like it is truly listening."
 * 2. "Respond on pointer-down, not on release." — haptic feedback fires
 *    the instant the button is pressed, not on click/release.
 * 3. No bounce on button press — bounce is reserved exclusively for
 *    momentum-driven gestures (flick, drag release).
 */@Composable
fun PressableScale(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onPress: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Fire haptic on press-down, not on release
    LaunchedEffect(pressed) {
        if (pressed) onPress()
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = SpringConfigs.ButtonDampingRatio,
            stiffness = SpringConfigs.ButtonStiffness,
        ),
        label = "press",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        content = { content() },
    )
}

/**
 * 列表项入场动画修饰符：淡入 + 上移 + 轻微缩放。
 *
 * 配合 [staggerDelayMs] 按 index 逐项延迟，形成“级联”入场效果。
 * 弹簧带轻微回弹（内容入场），符合 skills 规范——弹簧仅用于入场这类
 * 离散状态切换。系统开启“减少动画”时自动退化为短促透明度渐显。
 *
 * 出现方向全站统一为**由下向上**（位移 24dp → 0，不做缩放）：
 * 同一屏里混用横向滑入、缩放、纵向位移会让界面显得散，方向一致才是"协调"。
 *
 * @param index 列表中的序号，决定入场延迟
 * @param visible 是否播放（false 时原样返回）
 */
@Composable
fun Modifier.animateListItem(
    index: Int,
    visible: Boolean = true,
): Modifier {
    if (!visible) return this

    val settings = motionSettings()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val delayMs = staggerDelayMs(index)
        if (delayMs > 0) delay(delayMs.toLong())
        entered = true
    }

    // 减少动画时：纯透明度渐显，不做位移
    val spec = if (settings.reduceMotion) {
        tween<Float>(durationMillis = DurationNormal)
    } else {
        contentSpringSpec<Float>()
    }

    val itemAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spec,
        label = "list-item-alpha",
    )
    val itemOffsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 24f,
        animationSpec = spec,
        label = "list-item-offset",
    )
    // 出现方向全站统一为"由下向上"：不做缩放，避免纵向位移与缩放松缩两种观感互相打架
    return this.graphicsLayer {
        alpha = itemAlpha
        translationY = itemOffsetY
    }
}

/**
 * Section header with restrained typography.
 *
 * Design principle: "Typography is size-specific — never one tracking value
 * for all sizes. Large text wants negative tracking; small text wants
 * slightly positive tracking for legibility."
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 20.dp, bottom = 10.dp),
    )
}

// ========== 统一卡片体系 ==========
//
// 圆角语言（Material 3 Expressive 质感，全项目统一）：
// - 分组大卡 (AppCard / AppSectionCard)  → AppRadii.Card (24dp)
// - 条目/状态卡 (AppItemCard)            → AppRadii.Item (20dp)
// - 小芯片 / 小容器                      → AppRadii.Tile (14dp)

/** 卡片容器默认圆角（分组大卡） */
val AppCardRadius = AppRadii.Card

/** 统一分组卡：surface 底 + 细边框，深/浅主题均保持清晰层次 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = AppCardRadius,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/** 统一条目卡：用于状态卡、权限条目、快捷入口等单行内容 */
@Composable
fun AppItemCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AppRadii.Item)
    if (onClick != null) {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        ) { androidx.compose.foundation.layout.Row(content = content) }
    } else {
        androidx.compose.material3.Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        ) { androidx.compose.foundation.layout.Row(content = content) }
    }
}

/** 图标瓦片：统一的圆角图标底（品牌区 / 列表条目图标共用） */
@Composable
fun AppIconTile(
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    tileSize: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    cornerRadius: Dp = AppRadii.Tile,
) {
    Box(
        modifier = modifier.size(tileSize),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = background,
            modifier = Modifier.matchParentSize(),
        ) {}
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 状态小徽章：胶囊形状态指示（完成/未完成/数量等） */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    containerAlpha: Float = 0.14f,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Chip),
        color = color.copy(alpha = containerAlpha),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ========== 统一页头 ==========

/**
 * 统一页头组件
 *
 * Material 3 Expressive 风格的页面标题区，所有主页面和二级页使用。
 * - 主标题 + 可选副标题
 * - 左侧可选 leading 图标
 * - 右侧可选 trailing 操作（图标按钮等）
 *
 * 默认左右留白 20dp（与各页 20dp 的内容留白对齐）。当页头本身带着一层
 * 有外边距的背板时（如 Agent 页的浮动玻璃顶栏），用 [contentPadding] 把
 * 背板外边距减掉，标题才会与其他页面落在同一条竖直线上。
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(12.dp))
        } else if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AppRadii.Tile))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (trailingContent != null) {
            trailingContent()
        }
    }
}

// ========== 骨架屏加载 ==========

/**
 * 骨架屏加载效果
 *
 * 使用闪烁动画模拟内容占位符的微光效果。
 * 符合 Material 3 Expressive 的加载体验标准。
 */
@Composable
fun Modifier.skeleton(
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
): Modifier {
    if (!enabled) return this
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DurationPulse, easing = EaseOut),
        ),
    )
    return this
        .clip(RoundedCornerShape(AppRadii.Inline))
        .background(color.copy(alpha = alpha))
}

/** 骨架屏卡片：带标题和内容行的骨架占位 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .fillMaxWidth(0.6f)
                .skeleton(),
        )
        Spacer(Modifier.height(12.dp))
        repeat(lines) { idx ->
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .fillMaxWidth(if (idx == lines - 1) 0.6f else 1f)
                    .skeleton(),
            )
            if (idx < lines - 1) Spacer(Modifier.height(8.dp))
        }
    }
}

// ========== Snackbar 反馈 ==========

/**
 * Snackbar 类型枚举
 */
enum class SnackbarType { SUCCESS, WARNING, ERROR, INFO }

/** 全局 Snackbar 状态管理器 */
class SnackbarState {
    var current by mutableStateOf<SnackbarData?>(null)
        private set

    fun show(
        message: String,
        type: SnackbarType = SnackbarType.INFO,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        current = SnackbarData(message, type, actionLabel, onAction)
    }

    fun dismiss() { current = null }
}

data class SnackbarData(
    val message: String,
    val type: SnackbarType,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** 应用级 Snackbar 组件 */
@Composable
fun AppSnackbar(
    state: SnackbarState,
    modifier: Modifier = Modifier,
) {
    val data = state.current
    AnimatedVisibility(
        visible = data != null,
        enter = fadeIn(tween(DurationNormal, easing = EaseOut)) +
            slideInVertically(tween(DurationNormal, easing = EaseOut)) { it / 2 },
        exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
            slideOutVertically(tween(DurationFast, easing = EaseOut)) { -it / 2 },
        modifier = modifier,
    ) {
        data?.let { snack ->
            val bgColor = when (snack.type) {
                SnackbarType.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                SnackbarType.WARNING -> Warning.copy(alpha = 0.15f)
                SnackbarType.ERROR -> MaterialTheme.colorScheme.errorContainer
                SnackbarType.INFO -> MaterialTheme.colorScheme.surfaceVariant
            }
            val textColor = when (snack.type) {
                SnackbarType.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                SnackbarType.WARNING -> Warning
                SnackbarType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                SnackbarType.INFO -> MaterialTheme.colorScheme.onSurface
            }

            Surface(
                shape = RoundedCornerShape(AppRadii.Card),
                color = bgColor,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = snack.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = Modifier.weight(1f),
                    )
                    if (snack.actionLabel != null && snack.onAction != null) {
                        TextButton(onClick = { snack.onAction(); state.dismiss() }) {
                            Text(
                                text = snack.actionLabel,
                                color = textColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        PressableScale(onClick = { state.dismiss() }) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "关闭",
                                tint = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 内嵌浮层：铺满父容器的一层压暗底 + 居中面板。
 *
 * 项目铁律是不使用系统 Dialog/AlertDialog：系统弹窗自带另一套字体、圆角与
 * 窗口动画，一出现就把页面语言切断。需要「必须回应一次」的浮层一律用它，
 * 长在页面内部——跟着页面走（切页/返回自然消失），不另开窗口。
 *
 * 用法：把宿主页面的根节点包成 Box，再把浮层作为 Box 的最后一个子项。
 *
 * @param fillHeight 面板是否撑满可用高度。内容里有 `weight(...)` 的长列表
 *   （如模型选择弹层）必须打开：面板高度不定的情况下权重拿不到可分配空间。
 *   短内容保持默认的「随内容收紧」，浮层才不会变成一块空板。
 */
@Composable
fun InlineOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 460.dp,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = motionSettings().reduceMotion
    // 与抽屉同一套做法：首帧就落位的话入场整段看不到，所以用 MutableTransitionState 起手
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { state.targetState = true }

    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(DurationNormal, easing = EaseOut)) +
            slideInVertically(tween(DurationNormal, easing = EaseOut)) { full ->
                if (reduceMotion) 0 else full / 12
            },
        exit = fadeOut(tween(DurationInstant, easing = EaseOut)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OverlayScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(AppRadii.Overlay),
                color = AppTheme.colors.surfaceBase,
                // 面板自身吃掉点击，避免点内容穿透到遮罩上被当成"点了外面"
                modifier = Modifier
                    .padding(AppSpacing.Lg)
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
                    .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                    content = content,
                )
            }
        }
    }
}

/** 空状态提示 */
@Composable
fun EmptyHint(icon: ImageVector, title: String, desc: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(AppRadii.Tile)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(AppSpacing.Md))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 统计瓦片：标题 + 数值（可选单位）+ 强调色 */
@Composable
fun StatTile(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    unit: String = "",
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Lg)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    ),
                )
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(AppSpacing.Xs))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AppSpacing.Xs),
                    )
                }
            }
        }
    }
}

/** 分区卡片：标题 + 计数徽章 + 可选清空操作 + 内容 */
@Composable
fun SectionCard(
    title: String,
    count: Int,
    countColor: Color,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth().padding(vertical = AppSpacing.Sm),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelMedium.copy(color = countColor),
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(countColor.copy(alpha = 0.15f))
                        .padding(horizontal = AppSpacing.Sm, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                if (count > 0) {
                    TextButton(onClick = onClear) {
                        Icon(AppIcons.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(AppSpacing.Xs))
                        Text("清空")
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.Sm))
            content()
        }
    }
}