package com.phoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.coding.agent.CodingToolNames
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.ActionType
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorder

/**
 * 工具执行卡片的原子层：Agent 页与编码页共用。
 *
 * 卡片外壳（[SkillCard]）负责容器与展开态；这里只负责"工具"本身怎么被认出来、
 * 怎么显示状态、怎么排成一排。两页不再各写一套图标映射与状态色。
 */

/** 一次工具执行在卡片上只有三种状态，两页共用这一口径 */
enum class ToolRunState { Running, Succeeded, Failed }

/** 协议层只有 `Boolean?`（null = 还在跑），在 UI 边界收口成三态 */
fun toolRunState(ok: Boolean?): ToolRunState = when (ok) {
    null -> ToolRunState.Running
    true -> ToolRunState.Succeeded
    false -> ToolRunState.Failed
}

/** 状态的三件套：图标、颜色、默认文案（个别页面可覆盖文案，但颜色与图标只有这一份） */
data class ToolStatusStyle(
    val icon: ImageVector,
    val tint: Color,
    val label: String,
)

/**
 * 工具状态的统一样式。
 * Agent 页把"未生效"单独说清楚时只覆盖文案，颜色仍与编码页一致。
 */
@Composable
fun toolStatusStyle(
    state: ToolRunState,
    runningLabel: String = "运行中…",
    succeededLabel: String = "完成",
    failedLabel: String = "失败",
): ToolStatusStyle {
    val colors = AppTheme.colors
    return when (state) {
        ToolRunState.Running ->
            ToolStatusStyle(AppIcons.Loading, MaterialTheme.colorScheme.primary, runningLabel)

        ToolRunState.Succeeded -> ToolStatusStyle(AppIcons.CheckCircle, colors.success, succeededLabel)
        ToolRunState.Failed -> ToolStatusStyle(AppIcons.Cancel, colors.error, failedLabel)
    }
}

/** 一个工具在卡片上的身份：图标 + 中文名。两页的卡片标题、图标、图标连排都从这里取 */
data class ToolIdentity(
    val icon: ImageVector,
    val label: String,
    /** 参数/输出是否按等宽正文排版（命令类工具有此标记，避免页面自己再列一遍工具名） */
    val monospace: Boolean = false,
)

/**
 * Agent 侧工具（按动作类型识别）。
 *
 * 图标一律线性描边、同一尺寸；名称复用 [HumanTranslator] 的动作人话，
 * 避免同一动作在卡片标题与步骤行里叫两个名字。
 */
fun agentToolIdentity(type: String): ToolIdentity = ToolIdentity(
    icon = when (type) {
        ActionType.CLICK, ActionType.TAP -> AppIcons.Tap
        ActionType.LONG_CLICK, ActionType.LONG_PRESS -> AppIcons.LongPress
        ActionType.TYPE_TEXT -> AppIcons.Keyboard
        ActionType.KEY -> AppIcons.EnterKey
        ActionType.SCROLL, ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
        ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT,
        -> AppIcons.ScrollVertical

        ActionType.SCROLL_TO -> AppIcons.ScrollSearch
        ActionType.WAIT -> AppIcons.Loading
        ActionType.LAUNCH -> AppIcons.Launch
        ActionType.LAUNCH_ASSISTANT -> AppIcons.Launch
        ActionType.OPEN -> AppIcons.Globe
        ActionType.BACK -> AppIcons.ArrowBack
        ActionType.HOME -> AppIcons.Home
        ActionType.RECENTS -> AppIcons.ListTodo
        ActionType.REFRESH -> AppIcons.Refresh
        ActionType.SHELL -> AppIcons.Terminal
        ActionType.WRITE_DOC -> AppIcons.Description
        ActionType.REMEMBER -> AppIcons.Remember
        ActionType.DEVICE_QUERY -> AppIcons.Memory
        ActionType.MCP_CALL -> AppIcons.Bolt
        ActionType.TASK_DONE, ActionType.TASK_COMPLETE -> AppIcons.CheckCircle
        ActionType.ABORT -> AppIcons.Cancel
        else -> AppIcons.SmartToy
    },
    label = if (HumanTranslator.knowsAction(type)) HumanTranslator.actionVerb(type) else "未知操作",
)

/**
 * 编码侧工具（按协议名识别）。
 * 协议名不外露，只用来选图标与中文名；未知工具不硬编名字，如实说"其他工具"。
 */
fun codingToolIdentity(name: String?): ToolIdentity = ToolIdentity(
    icon = when (name) {
        "read_file", "read_files", "write_file", "edit_file", "apply_patch" -> AppIcons.Description
        "web_fetch", "fetch" -> AppIcons.Globe
        "tree", "list_dir", "list_files", "glob", "grep", "search_files",
        "todo_write", "todo_read",
        -> AppIcons.ListTodo

        "git_read", "git_write" -> AppIcons.History
        else -> AppIcons.Terminal
    },
    label = CodingToolNames.label(name),
    // 命令类工具的参数与输出必须等宽照排（Markdown 会把 * 与 - 当格式吃掉）
    monospace = name in CommandTools,
)

/** 命令类工具：参数与回传输出按等宽排版 */
private val CommandTools = setOf("run_command", "bash", "shell")

/**
 * 工具图标方块：卡片左侧识别位。
 * 尺寸与底色只有这一份，Agent 页图标连排与编码页单工具卡才对得上。
 */
@Composable
fun ToolIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    boxSize: Dp = 28.dp,
    iconSize: Dp = 15.dp,
    background: Color? = null,
) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(RoundedCornerShape(AppRadii.Chip))
            .background(background ?: colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 卡片右侧状态位：状态图标 + 中文状态，耗时之类的补充信息附在 [detail] 上 */
@Composable
fun ToolStatusTrail(
    style: ToolStatusStyle,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = style.icon,
            contentDescription = style.label,
            tint = style.tint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        Text(
            text = if (detail.isNullOrBlank()) style.label else "${style.label} · $detail",
            style = MaterialTheme.typography.labelSmall,
            color = style.tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 图标连排里的一个工具：失败的那一格用错误底色，和展开后的步骤行对得上 */
data class ToolChip(
    val icon: ImageVector,
    val label: String,
    val failed: Boolean = false,
)

/** 图标条最多连排几个，超出的用 +N 交代，避免长任务把这一行撑爆 */
private const val MaxChips = 4

/** 单个工具方块边长 / 相邻两个的横向前进量（小于边长 → 连排而非平铺） */
private val ChipSize = 26.dp
private val ChipAdvance = 19.dp

/**
 * 图标连排：几个图标连在一起，就是调用了几个工具。
 * Agent 页工具链折叠行用它交代"调用了哪些工具"，编码页需要并排展示时也能直接复用。
 */
@Composable
fun ToolIconStrip(
    chips: List<ToolChip>,
    modifier: Modifier = Modifier,
    maxChips: Int = MaxChips,
) {
    if (chips.isEmpty()) return
    val colors = AppTheme.colors
    val shown = chips.take(maxChips)
    val extra = chips.size - shown.size
    val shape = RoundedCornerShape(AppRadii.Chip)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(ChipAdvance * (shown.size - 1) + ChipSize)
                .height(ChipSize),
        ) {
            shown.forEachIndexed { index, chip ->
                Box(
                    modifier = Modifier
                        .offset(x = ChipAdvance * index)
                        .size(ChipSize)
                        .clip(shape)
                        .background(if (chip.failed) colors.errorContainer else colors.surfaceSunken)
                        .appBorder(
                            1.dp,
                            if (chip.failed) colors.error.copy(alpha = 0.45f) else colors.outlineSoft,
                            shape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = chip.label,
                        tint = if (chip.failed) colors.error else colors.brand,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (extra > 0) {
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = "+$extra",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 执行证据块：Agent 页的"命令/输出/原始数据"与编码页的"参数/执行结果"
// 此前各写一套容器（圆角、底色、内边距、标题字重各自硬编），这里收成同一份。
// ---------------------------------------------------------------------------

/** 证据块的容器：下沉底色 + Tile 圆角 + 统一内边距 */
@Composable
fun ToolEvidenceBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.surfaceSunken)
            .padding(AppSpacing.Md),
        content = content,
    )
}

/** 证据块的小标题：等宽/普通正文共用这一层字重与颜色 */
@Composable
fun ToolEvidenceTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = AppTheme.colors.onSurfaceRaised,
    )
}

/** 证据块的正文：默认纯文本；[monospace] 用于命令/输出原文 */
@Composable
fun ToolEvidenceBody(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodySmall
        },
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 一组「小标题 + 正文」，多段证据（执行的命令 / 输出）直接按顺序拼进同一个容器 */
@Composable
fun ToolEvidenceSection(
    title: String,
    body: String,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    ToolEvidenceTitle(title)
    Spacer(Modifier.height(AppSpacing.Xs))
    ToolEvidenceBody(body, monospace = monospace, maxLines = maxLines)
}

/** 耗时的统一口径：只有跑完（`null` / 0 视为未完成）才给数字，运行中的跳数读起来是噪声 */
fun toolTimingText(durationMs: Long?): String? =
    durationMs?.takeIf { it > 0 }?.let { formatDuration(it) }

/** 卡片正文里的耗时脚注：Agent 页与编码页共用字号与弱化色 */
@Composable
fun ToolTimingLabel(timing: String, modifier: Modifier = Modifier) {
    Text(
        text = "耗时 $timing",
        style = MaterialTheme.typography.labelSmall,
        color = AppTheme.colors.onSurfaceRaised,
        modifier = modifier,
    )
}
