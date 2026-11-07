package com.phoneagent.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

/**
 * 输入面：Agent 页与 Coding 页共用的唯一面板。
 *
 * 两页只允许在「占位文案」和「右侧动作」上不同——容器、圆角、内边距、行高、
 * 聚焦反馈都只在这里定义一次。此前两页各写一套，间距与高度慢慢漂移，
 * 切换页面时输入区会上下跳，这类不一致都源于"复制一份再各改一遍"。
 *
 * 结构固定为：可选的活动区（排队条 / 澄清选项 / 运行提示）→
 * 输入行（左入口 + 输入框 + 右侧动作）→ 可选的开关行。
 * 除这一层圆角面板外不再套内层边框或阴影，层级靠间距与字重区分。
 */
@Composable
fun ComposerPanel(
    modifier: Modifier = Modifier,
    /** 输入框是否持有焦点：唯一的聚焦反馈是左右留白轻轻变宽，不做发光也不描边 */
    focused: Boolean = false,
    /** 输入行之上：排队条 / 澄清选项 / 运行提示 */
    above: (@Composable () -> Unit)? = null,
    /** 输入行左入口（附件等）；不传则输入框直接贴左 */
    leading: (@Composable () -> Unit)? = null,
    /** 输入框本体；传入的 modifier 已带 weight 与 40dp 最小高度 */
    field: @Composable (Modifier) -> Unit,
    /** 输入行右侧动作（发送 / 停止 / 取消规划），按重要性从左到右 */
    actions: @Composable RowScope.() -> Unit,
    /** 输入行之下：审核 AI / 先规划执行计划 */
    below: (@Composable () -> Unit)? = null,
) {
    val fieldPad by animateDpAsState(
        targetValue = if (focused) AppSpacing.Lg else AppSpacing.Md,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "composer-pad",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(AppTheme.colors.surfaceRaised),
    ) {
        if (above != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // 两枚转盘卡的左右边缘与输入行、右侧动作键对齐
                    .padding(start = fieldPad, end = AppSpacing.Xs, top = AppSpacing.Xs, bottom = AppSpacing.Xs),
            ) {
                above()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (leading == null) fieldPad else AppSpacing.Xs,
                    end = AppSpacing.Xs,
                    // 40dp 动作键 + 上下各 4dp = 单行 48dp，两页一致
                    top = AppSpacing.Xs,
                    bottom = AppSpacing.Xs,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
        ) {
            if (leading != null) {
                leading()
            }
            field(
                Modifier
                    .weight(1f)
                    .heightIn(min = ComposerActionSize),
            )
            actions()
        }

        if (below != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = fieldPad, end = AppSpacing.Sm, bottom = AppSpacing.Xs),
            ) {
                below()
            }
        }
    }
}

/**
 * 共用输入框：占位文案、光标色、行高、焦点回调只在这里定义。
 *
 * 两页的文字位置必须落在同一条竖线上，所以内边距交给 [ComposerPanel]，
 * 这里不再自己加水平留白；语音手势由 Agent 页在外面包一层，Coding 页直接使用。
 */
@Composable
fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    maxLines: Int = 5,
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val colors = AppTheme.colors
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceRaised,
                textAlign = TextAlign.Start,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(colors.brand),
            maxLines = maxLines,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { onFocusedChange(it.isFocused) },
        )
    }
}
