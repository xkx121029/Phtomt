package com.phoneagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.appBorder

/**
 * 技能卡：Agent 页与编码页共用的唯一卡片外壳。
 *
 * 此前两页各写一套（Agent 用 Item 圆角 + 描边，编码用 Chip 圆角 + 无描边），
 * 同一个「调用了什么工具」在两张页面上长得像两个产品。这里把容器、内边距、
 * 标题/副标题层级、状态尾注、展开箭头收成一份，两页只负责填内容。
 *
 * @param leading 左侧识别位：工具图标或工具图标连排
 * @param trailing 右侧状态位：状态徽章 / 勾选 / 数量
 * @param expandable 是否能展开看完整执行细节
 * @param content 展开后的内容（调用方自己给内边距）
 */
@Composable
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
fun SkillCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    content: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val shape = RoundedCornerShape(AppRadii.Item)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, shape),
    ) {
        val header: @Composable () -> Unit = {
            SkillCardHeader(
                title = title,
                subtitle = subtitle,
                leading = leading,
                trailing = trailing,
                expandable = expandable,
                expanded = expanded,
            )
        }
        if (expandable) {
            PressableScale(
                modifier = Modifier.fillMaxWidth(),
                onPress = buzz,
                onClick = onToggleExpand,
            ) { header() }
        } else {
            header()
        }

        if (content != null) {
            // 可展开卡片：内容随展开态进出；不可展开卡片（如审批卡）：内容常显，
            // 但仍套同一层分隔线与容器，保证两页所有卡片的版面语言一致。
            if (expandable) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                        expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                        shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
                ) {
                    SkillCardBody(content = content)
                }
            } else {
                SkillCardBody(content = content)
            }
        }
    }
}

/** 卡片正文区：一条淡分隔线 + 调用方内容（内边距由调用方给） */
@Composable
private fun SkillCardBody(content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineSoft.copy(alpha = 0.6f)),
        )
        content()
    }
}

/** 卡片头：两页共用一套层级（标题 body 中粗、副标题最弱、状态在右） */
@Composable
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
private fun SkillCardHeader(
    title: String,
    subtitle: String,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    expandable: Boolean,
    expanded: Boolean,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(AppSpacing.Md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                    maxLines = if (expandable) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(AppSpacing.Xs))
            trailing()
        }
        if (expandable) {
            Spacer(Modifier.width(AppSpacing.Xs))
            Icon(
                imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = if (expanded) "收起执行细节" else "展开执行细节",
                tint = colors.onSurfaceRaised,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 展开区里的完整执行内容：下沉底色 + 等宽，命令/输出/参数原文一字不改 */
@Composable
fun SkillDetailBlock(
    title: String,
    body: String,
    monospace: Boolean = false,
) {
    // 容器、标题字重、正文弱化色都在 ToolAtoms：与 Agent 页的命令/输出块同一份排版
    ToolEvidenceBlock {
        ToolEvidenceTitle(title)
        Spacer(Modifier.height(AppSpacing.Xs))
        if (monospace) {
            // 命令/输出原文一字不改：等宽照排，交给 Markdown 解析会把 * 与 - 当格式吃掉
            ToolEvidenceBody(
                text = body.ifBlank { "（这一步没有留下执行内容）" },
                monospace = true,
            )
        } else {
            // AI 回返的正文按 Markdown 排：工具参数/子任务说明里常带列表、代码块与小标题
            MarkdownPreview(
                content = body.ifBlank { "（这一步没有留下执行内容）" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
