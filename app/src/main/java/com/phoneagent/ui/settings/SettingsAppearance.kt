package com.phoneagent.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.ScreenTransitions
import com.phoneagent.ui.theme.motionSettings

/** 「外观与导航」页内的三个分区：主题（配色/框线/磨砂）、运行时效果、底部导航 */
internal enum class AppearanceSection(val label: String) {
    THEME("主题"),
    EFFECTS("效果"),
    NAV("导航"),
}

/**
 * 外观与导航：把原先三个同级入口（主题 / 界面效果 / 选项卡与工作台）收成一页。
 *
 * 三处改的都是「看起来什么样、导航怎么排」，彼此要在同一个上下文里对照着调，
 * 分三个页会让用户来回跳。这里用分段控件切换分区，而不是把三段竖向拼成一张长页：
 * 竖向拼接会同时挂载三套实时预览与标定逻辑，页面又长又互相抢刷新。
 *
 * 再次进入时停留在上次的分区（rememberSaveable），因为调外观往往是连着改几处。
 */
@Composable
internal fun SettingsAppearance(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(AppearanceSection.THEME) }
    val sectionMs = motionSettings().scaledDuration(ScreenTransitions.Duration)
    val sections = AppearanceSection.values()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = SettingsScreenPad)) {
            SettingsPageHeader("外观与导航", onBack, subtitle = "配色、运行时效果与底部导航，改完立即生效")
            Spacer(Modifier.height(AppSpacing.Md))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sections.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = section == item,
                        onClick = { section = item },
                        shape = SegmentedButtonDefaults.itemShape(index, sections.size),
                    ) {
                        Text(item.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.Sm))
        }
        // 分区切换沿用全局动效语言：只做自下而上的短距升起 + 淡入，不做水平滑动与缩放
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = section,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val fade = tween<Float>(sectionMs, easing = ScreenTransitions.Easing)
                    val slide = tween<IntOffset>(sectionMs, easing = ScreenTransitions.Easing)
                    (fadeIn(fade) + slideInVertically(slide) { full -> full / 16 })
                        .togetherWith(fadeOut(fade))
                },
                label = "appearance-section",
            ) { target ->
                when (target) {
                    AppearanceSection.THEME -> SettingsTheme(st, save, onBack, embedded = true)
                    AppearanceSection.EFFECTS -> SettingsVisual(st, save, onBack, embedded = true)
                    AppearanceSection.NAV -> SettingsTabs(st, save, onBack, embedded = true)
                }
            }
        }
    }
}