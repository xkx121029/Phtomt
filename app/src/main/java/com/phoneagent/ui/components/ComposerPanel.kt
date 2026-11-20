package com.phoneagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.appBorder

/**
 * 输入框自身的大圆角：单行 48dp 时读作完整胶囊，多行展开后退成圆角矩形。
 * 取"半行高"而不是另一档圆角令牌，是为了让单行态真的成为一枚胶囊。
 */
private val ComposerPillRadius = 24.dp

/**
 * 输入框相对面板边缘的退让量，四边同值。
 *
 * 输入框是面板里挖出来的一个槽，不是贴边的一条空白：退让 4dp 之后，
 * 描边与焦点环才看得见，面板也才不会读成"一个文本框加了外框"。
 */
private val ComposerPillInset = AppSpacing.Xs

/**
 * 输入面：Agent 页与 Coding 页共用的唯一面板。
 *
 * 两页只允许在「占位文案」和「右侧动作」上不同——容器、圆角、内边距、行高、
 * 聚焦反馈都只在这里定义一次。此前两页各写一套，间距与高度慢慢漂移，
 * 切换页面时输入区会上下跳，这类不一致都源于"复制一份再各改一遍"。
 *
 * 结构固定为：可选的活动区（转盘 / 排队条 / 澄清选项）→ 输入框 → 可选的辅助行。
 *
 * 输入框本身是一块**下沉底的胶囊**，右侧动作长在胶囊里面，而不是在胶囊外面另起一块：
 * 视线落点只有一个，"我现在在哪儿打字"和"按哪个键发出去"连成一体。
 * 主操作（发送 / 停止）与输入框同底对齐，多行输入时贴住最后一行。
 */
@Composable
fun ComposerPanel(
    modifier: Modifier = Modifier,
    /** 输入框是否持有焦点：唯一的聚焦反馈是描边提亮成品牌色，不做发光也不改变尺寸 */
    focused: Boolean = false,
    /** 输入框之上：转盘 / 排队条 / 澄清选项 */
    above: (@Composable () -> Unit)? = null,
    /** 输入框左入口（附件等）；不传则文字直接贴着胶囊左边 */
    leading: (@Composable () -> Unit)? = null,
    /** 输入框本体；传入的 modifier 已带 weight 与 40dp 最小高度 */
    field: @Composable (Modifier) -> Unit,
    /** 输入框右侧动作（发送 / 停止 / 排队），长在胶囊内部 */
    actions: @Composable RowScope.() -> Unit,
    /** 输入框之下：两枚同级开关，或规划期 / 指导期的旁路出口 */
    below: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val pillShape = RoundedCornerShape(ComposerPillRadius)
    val ringColor by animateColorAsState(
        targetValue = if (focused) colors.brand.copy(alpha = 0.7f) else colors.outlineSoft,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "composer-ring",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.metrics.cardRadius))
            .background(colors.surfaceRaised),
    ) {
        if (above != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(ComposerPillInset),
            ) {
                above()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ComposerPillInset)
                .clip(pillShape)
                .background(colors.surfaceSunken)
                .appBorder(1.dp, ringColor, pillShape)
                .padding(
                    // 无左入口时文字自己让出 16dp；有左入口时由入口占掉这段，文字紧跟其后
                    start = if (leading == null) AppSpacing.Lg else ComposerPillInset,
                    end = ComposerPillInset,
                    top = ComposerPillInset,
                    bottom = ComposerPillInset,
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
                    .padding(ComposerPillInset),
            ) {
                below()
            }
        } else {
            // 没有辅助行时也要让出下沿：胶囊不能贴着面板底边
            Spacer(Modifier.height(ComposerPillInset))
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
                maxLines = 1,
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
                // 空框时把占位文案交给读屏：否则只念一句"编辑框"，读不出这块是做什么用的。
                // 已有内容时不叠加描述，让 TalkBack 直接念用户自己打的字
                .semantics { if (value.isEmpty()) contentDescription = placeholder }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { onFocusedChange(it.isFocused) },
        )
    }
}
