package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.GlassSurface
import com.phoneagent.ui.components.GlassSurfaceStyle
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberGlassState
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.ThemePalette
import com.phoneagent.ui.theme.ThemeSkin
import com.phoneagent.ui.theme.UiStyle
import com.phoneagent.ui.theme.glassTintPreview
import dev.chrisbanes.haze.hazeSource

/**
 * 主题页：选择整体视觉风格。
 *
 * 四条轴：**主题皮肤**、界面风格、配色与框线、磨砂浓淡。
 * 皮肤是最高优先级的一层——经典 = 现有主题（配色 / 框线 / 工作台风格全部照旧），
 * 石墨 = 新主题（自带色板、字体、形状、间距与材质），此时配色与框线选项不参与，
 * 页面里会明确说明，而不是让用户点了没反应。
 * 选中即时落盘并即时生效：保存后整棵树重组，不需要重启。
 */
@Composable
internal fun SettingsTheme(
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
    /** 由「外观与导航」内嵌渲染时为 true：标题已由外层给，这里不再重复画一遍 */
    embedded: Boolean = false,
) {
    val activeSkin = ThemeSkin.of(st.skin)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        if (!embedded) {
            SettingsPageHeader("主题", onBack, subtitle = "主题皮肤、界面风格、配色与磨砂浓淡，改完立即生效")
            Spacer(Modifier.height(AppTheme.metrics.pagePad))
        }

        SkinSection(st, activeSkin, save)

        Spacer(Modifier.height(AppSpacing.Lg))

        StyleSection(st, activeSkin, save)

        if (activeSkin.isGraphite) {
            GraphiteSkinNote()
        } else {
            Spacer(Modifier.height(AppSpacing.Lg))
            BorderSection(st, save)
            Spacer(Modifier.height(AppSpacing.Lg))
            PaletteSection(st, save)
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        GlassTintSection(st, save)

        Spacer(Modifier.height(AppSpacing.Xl))
    }
}

/** 主题皮肤：整套视觉语言一起换，选中即落盘 */
@Composable
private fun SkinSection(st: SettingsState, activeSkin: ThemeSkin, save: () -> Unit) {
    SettingsSection(
        title = "主题皮肤",
        subtitle = "整套视觉语言。经典保留现有主题；石墨是全新设计，自带色板与材质",
        tone = SettingsTone.Brand,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            ThemeSkin.entries.forEach { skin ->
                SelectableOption(
                    title = skin.label,
                    subtitle = skin.summary,
                    selected = activeSkin == skin,
                    onClick = {
                        if (activeSkin != skin) {
                            st.skin = skin.id
                            save()
                        }
                    },
                ) { SkinSwatch(skin) }
            }
        }
    }
}

/** 界面风格：经典 / 工作台，只改页面结构与圆角口径 */
@Composable
private fun StyleSection(st: SettingsState, activeSkin: ThemeSkin, save: () -> Unit) {
    SettingsSection(
        title = "界面风格",
        subtitle = if (activeSkin.isGraphite) {
            "石墨主题下仍可切换页面结构：经典是常规页面，工作台把内容收成平铺分区"
        } else {
            "经典是默认主题；工作台把页面收成平铺分区，首屏直接进入工作区"
        },
        tone = SettingsTone.Brand,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            UiStyle.entries.forEach { style ->
                SelectableOption(
                    title = style.label,
                    subtitle = style.summary,
                    selected = st.uiStyle == style.id,
                    onClick = {
                        if (st.uiStyle != style.id) {
                            st.uiStyle = style.id
                            save()
                        }
                    },
                ) { StyleSwatch(style) }
            }
        }
    }
}

/** 石墨下配色与框线不参与：给一句明确说明，而不是留两组点了没反应的选项 */
@Composable
private fun GraphiteSkinNote() {
    Spacer(Modifier.height(AppSpacing.Lg))
    Text(
        "石墨主题使用独立的配色与发丝描边，配色与框线两组选项仅在经典主题生效；" +
            "切回经典即可继续使用现有配色方案与框线开关。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = AppSpacing.Xs),
    )
}

/** 描边方式：只属于经典皮肤 */
@Composable
private fun BorderSection(st: SettingsState, save: () -> Unit) {
    SettingsSection(
        title = "主题风格",
        subtitle = "卡片、面板与输入面的描边方式",
        tone = SettingsTone.Brand,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            ThemeOption(
                title = "标准",
                subtitle = "卡片、面板与输入面保留发丝描边，层次最清晰",
                selected = !st.borderless,
                showBorder = true,
                onClick = {
                    if (st.borderless) {
                        st.borderless = false
                        save()
                    }
                },
            )
            ThemeOption(
                title = "无框线",
                subtitle = "去掉所有装饰性框线，界面更干净，层次由底色差表达",
                selected = st.borderless,
                showBorder = false,
                onClick = {
                    if (!st.borderless) {
                        st.borderless = true
                        save()
                    }
                },
            )
        }
    }
}

/** 配色：整套品牌主色 + 底色，只属于经典皮肤 */
@Composable
private fun PaletteSection(st: SettingsState, save: () -> Unit) {
    SettingsSection(
        title = "配色",
        subtitle = "品牌主色与底色，深浅跟随系统",
        tone = SettingsTone.Brand,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            ThemePalette.entries.forEach { palette ->
                PaletteOption(
                    palette = palette,
                    selected = st.palette == palette.id,
                    onClick = {
                        if (st.palette != palette.id) {
                            st.palette = palette.id
                            save()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 磨砂浓淡：拖动只更新本地编辑态（数值跟手），松手才落盘，避免拖一次写几十次 DataStore。
 * 预览样片取滑杆当前值直接画，所见即所得。
 */
@Composable
private fun GlassTintSection(st: SettingsState, save: () -> Unit) {
    SettingsSection(
        title = "磨砂玻璃",
        subtitle = "玻璃面板的透光度：向左更透，向右更实",
        tone = SettingsTone.Brand,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Lg)) {
            Text(
                "滚过玻璃下方的内容",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Frosted sample · 0123456789",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Sm))
            GlassSamplePreview(glassTintPreview(st.glassTintOffset))
            Spacer(Modifier.height(AppSpacing.Md))
            LabeledField(
                when {
                    st.glassTintOffset == 0 -> "浓淡  默认"
                    st.glassTintOffset < 0 -> "浓淡  更透 ${-st.glassTintOffset}"
                    else -> "浓淡  更实 ${st.glassTintOffset}"
                },
            ) {
                Slider(
                    value = st.glassTintOffset.toFloat(),
                    onValueChange = { st.glassTintOffset = it.toInt() },
                    onValueChangeFinished = { save() },
                    valueRange = -25f..25f,
                    steps = 9,
                )
            }
            Spacer(Modifier.height(AppSpacing.Xs))
        }
    }
}

/** 玻璃样片：上方是"滚过玻璃下的内容"，下方浮一块同材质玻璃板 */
@Composable
private fun GlassSamplePreview(tint: Color) {
    val glass = rememberGlassState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(AppTheme.metrics.tileRadius)),
    ) {
        // 用容器色三段渐变做取样源：容器色本为承载 on*Container 文字而设计，
        // 既透得出色相变化，又把"玻璃上浅字 vs 最亮处"的最坏对比度钉在 AA 以上
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(glass)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ),
                ),
        )
        GlassSurface(
            hazeState = glass,
            style = GlassSurfaceStyle(
                shape = RoundedCornerShape(AppTheme.metrics.tileRadius),
                blurRadius = AppTheme.metrics.glassBlurCompact,
                tintOverride = tint,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md)
                .height(44.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "玻璃页眉预览",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    // onPrimaryContainer 与样片渐变起始色是官方配对，深色下过 AA
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
