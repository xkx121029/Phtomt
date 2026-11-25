package com.phoneagent.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.launch

/**
 * 全局按压反馈：`ACTION_DOWN` 当帧给一层平涂色块，不等 ripple 的扩散动画。
 *
 * 为什么不用系统 ripple：ripple 的可见浓度是随动画逐帧涨上去的，手指刚落下那一帧几乎看不出变化，
 * 快点击（< 200ms）时整段反馈被截掉大半，观感是"点了没反应"。这里改成按下即铺满整块区域、
 * 抬手即撤掉，两帧之间没有任何插值——反馈与手指同步，而不是与动画同步。
 *
 * 通过 `LocalIndication` 注入（见 [ProvideMotionSettings]），所有未显式指定 indication 的
 * `.clickable` / `.toggleable` / M3 列表行都会自动生效；自带 `PressableScale` 缩放的控件
 * 仍走自己的 `indication = null`，不会被叠两层。
 */
internal object InstantPressIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        InstantPressNode(interactionSource)

    // 单例：同一个 indication 实例在重组之间保持稳定，ClickableNode 不必反复重建
    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = "InstantPressIndication".hashCode()
}

private class InstantPressNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    // 快照状态：draw 里读它，值一变就只重绘这一个节点，不触发重组、也不走动画时钟
    private var pressed by mutableStateOf(false)

    // 内容色在挂载时取一次：主题切换会重建 Activity，不存在"按着不放恰好换主题"的场景
    private var tintColor: Color = Color.Gray

    override fun onAttach() {
        tintColor = currentValueOf(LocalContentColor)
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    else -> Unit
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) {
            drawRect(color = tintColor.copy(alpha = PressTintAlpha))
        }
    }
}
