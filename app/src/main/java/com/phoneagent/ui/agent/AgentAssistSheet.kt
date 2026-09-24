package com.phoneagent.ui.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/**
 * 底部协助浮层：AI 在执行或规划中需要用户介入时，从页面下方浮入。
 *
 * 为什么不沿用任务流卡片：
 * 任务流记录的是"已经发生的事"，而"请你选一个 / 说一句"属于**当前待办**。
 * 混在列表里会随滚动跑掉，也会和 Z 轴更高的浮层抢注意力。
 * 浮层固定在输入区上方、用完即走；任务流里仍留一条轻量记录说明发生了什么。
 *
 * 两种场景共用同一副骨架，只是出口不同：
 * - **答疑**（计划有歧义）：问题 + 选项（点一下即答）+ 自由输入；
 * - **协助**（敏感页只读 / 动作连续未生效）：原因 + 「已手动处理」+ 指导输入。
 *
 * 视觉遵循项目三层圆角口径：浮层容器 = Card，输入框与选项 = Tile，出口按钮 = Chip。
 */
@Composable
internal fun AgentAssistSheet(
    vm: MainViewModel,
    title: String,
    message: String,
    options: List<ClarificationOption>,
    allowManualHandle: Boolean,
    /** 是否给自由输入框：自写命令确认只有「批准 / 拒绝」两个出口，打字无处可去，故隐藏输入与提交 */
    allowFreeText: Boolean = true,
    draft: String,
    onDraftChange: (String) -> Unit,
    onPickOption: (ClarificationOption) -> Unit,
    onSubmitText: (String) -> Unit,
    onManualHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val question = rememberTranslated(message, vm)
    val canSubmit = draft.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 高度随文案换行 / 内容增减平滑变化，避免输入时面板"跳一下"
            .animateContentSize()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Card))
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (question.isNotBlank()) {
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 选项：点一下就走，不必再按提交
        if (options.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.Md))
            options.forEach { option ->
                val label = rememberTranslated(option.label, vm)
                val desc = rememberTranslated(option.description, vm)
                PressableScale(
                    onPress = buzz,
                    onClick = { onPickOption(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.Xs)
                        .clip(RoundedCornerShape(AppRadii.Tile))
                        .background(colors.surfaceSunken)
                        .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Tile))
                        .padding(AppSpacing.Md),
                ) {
                    Column {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (desc.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceRaised,
                            )
                        }
                    }
                }
            }
        }

        // 自由输入：选项覆盖不到的情况，用户可以直接说
        if (allowFreeText) {
            Spacer(Modifier.height(AppSpacing.Md))
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Tile))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Tile))
                    .padding(AppSpacing.Md),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            text = if (options.isEmpty()) "直接告诉我该怎么做" else "或者自己说一个答案",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceRaised,
                        )
                    }
                    inner()
                },
            )
        }

        // 出口：协助场景给「已手动处理」，两种场景都可用输入提交
        if (allowManualHandle || allowFreeText) {
            Spacer(Modifier.height(AppSpacing.Md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (allowManualHandle) {
                    PressableScale(
                        onPress = buzz,
                        onClick = onManualHandled,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadii.Chip))
                            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Chip))
                            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                    ) {
                        Text(
                            text = "已手动处理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                PressableScale(
                    enabled = canSubmit,
                    onPress = buzz,
                    onClick = { if (canSubmit) onSubmitText(draft) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(if (canSubmit) colors.brand else colors.surfaceSunken)
                        .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                ) {
                    Text(
                        text = if (options.isEmpty()) "告诉 AI" else "提交",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (canSubmit) colors.onBrand else colors.onSurfaceRaised,
                    )
                }
            }
        }
    }
}