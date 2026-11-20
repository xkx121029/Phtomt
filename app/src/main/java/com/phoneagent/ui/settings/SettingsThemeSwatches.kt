package com.phoneagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppCardBorder
import com.phoneagent.ui.components.AppCardContainer
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DarkAppColors
import com.phoneagent.ui.theme.GraphiteDarkAppColors
import com.phoneagent.ui.theme.GraphiteLightAppColors
import com.phoneagent.ui.theme.LightAppColors
import com.phoneagent.ui.theme.ThemePalette
import com.phoneagent.ui.theme.ThemeSkin
import com.phoneagent.ui.theme.UiStyle
import com.phoneagent.ui.theme.appBorderStroke

/**
 * 主题页的预览小块与选项行。
 *
 * 与 [SettingsTheme] 分文件，是因为主题页本身已经有皮肤 / 风格 / 描边 / 配色 / 磨砂五段结构；
 * 预览块只负责"把选项画出来"，和设置逻辑无关，混在一个文件里会让两边都难读，
 * 也会把单文件函数数推过静态检查的门槛。
 */

/** 石墨样片里的发丝分隔条数：三条线足以读出"高密度分隔"的排版语言 */
private const val SWATCH_HAIRLINES = 3

/** 无框线样片中间那条短横的宽度占比：留出左右呼吸，读作"内容块"而非"满行线" */
private const val SWATCH_BAR_WIDTH = 0.5f

/**
 * 可选项行（主题 / 皮肤 / 风格 / 配色共用）：左侧自定义预览块，右侧标题与说明，选中时主色描边 + 勾选。
 * 各选项原本各写一份同构布局，只有预览块不同；差异通过 [swatch] 传入。
 */
@Suppress("FunctionNaming") // Compose 约定：可组合函数首字母大写
@Composable
internal fun SelectableOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    swatch: @Composable () -> Unit,
) {
    val buzz = rememberHapticClick()
    Surface(
        shape = RoundedCornerShape(AppRadii.Tile),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else AppCardContainer,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, AppCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { buzz(); onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            swatch()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(AppSpacing.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    AppIcons.CheckCircle,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 主题选项行。左侧一个小预览块直接演示"有框线 / 无框线"的差别，
 * 比只写文字更好判断；选中态用主色描边 + 主色文字，沿用设置页既有的选择语言。
 */
@Composable
internal fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    showBorder: Boolean,
    onClick: () -> Unit,
) = SelectableOption(title, subtitle, selected, onClick) { ThemeSwatch(showBorder = showBorder) }

/**
 * 配色选项行：左侧色板预览（底色 / 抬升面 / 主色 / 冷辅色四点），右侧名称与说明。
 * 预览色取配色自身按当前深浅模式的那一份——所见即所选，不借当前主题的颜色。
 */
@Composable
internal fun PaletteOption(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
) = SelectableOption(palette.label, palette.subtitle, selected, onClick) { PaletteSwatch(palette) }

/**
 * 皮肤预览小块：经典 = 纸底上浮起的大圆角卡片；石墨 = 白卡压在灰底上、配靛蓝点与发丝线。
 * 两块刻意用**各自的真实色板**绘制（不借当前主题色），保证预览与切换后的观感一致。
 */
@Composable
internal fun SkinSwatch(skin: ThemeSkin) {
    val dark = isSystemInDarkTheme()
    val c = when (skin) {
        ThemeSkin.LEGACY -> if (dark) DarkAppColors else LightAppColors
        ThemeSkin.GRAPHITE -> if (dark) GraphiteDarkAppColors else GraphiteLightAppColors
    }
    Surface(
        shape = RoundedCornerShape(if (skin.isGraphite) AppRadii.Chip else AppRadii.Inline),
        color = c.surfaceBase,
        border = BorderStroke(1.dp, c.outlineSoft),
        modifier = Modifier.size(44.dp),
    ) {
        if (skin.isGraphite) GraphiteSwatchBody(c.surfaceRaised, c.outlineSoft, c.brand)
        else LegacySwatchBody(c.surfaceRaised, c.brand)
    }
}

/** 石墨样片内容：亮面卡片 + 发丝分隔 + 靛蓝方点，表达"卡片比页面亮"的反转高度 */
@Composable
private fun GraphiteSwatchBody(raised: Color, outline: Color, brand: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 白卡压在灰底上：新主题的高度关系是"卡片比页面亮"。
        // 深色下卡的明度只比页面高一点点，仅靠底色差在小尺寸样片里会读成一块空黑，
        // 因此补一根发丝边把"卡"的轮廓钉出来。
        Box(
            Modifier
                .fillMaxWidth()
                .height(13.dp)
                .background(raised, RoundedCornerShape(2.dp))
                .border(1.dp, outline.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
        )
        repeat(SWATCH_HAIRLINES) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(outline),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(brand, RoundedCornerShape(2.dp)),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(outline, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** 经典样片内容：纸感底色上浮起一块大圆角面，中心一枚品牌圆点 */
@Composable
private fun LegacySwatchBody(raised: Color, brand: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .background(raised, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(brand, CircleShape),
        )
    }
}

/** 预览小块：经典 = 一张浮起的小圆角卡；工作台 = 平铺的发丝线 */
@Composable
internal fun StyleSwatch(style: UiStyle) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Inline),
        color = AppCardContainer,
        border = appBorderStroke(1.dp, AppCardBorder),
        modifier = Modifier.size(44.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
        ) {
            when (style) {
                UiStyle.CLASSIC -> {
                    Surface(
                        shape = RoundedCornerShape(AppRadii.Chip),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {}
                }

                UiStyle.WORKBENCH -> {
                    repeat(SWATCH_HAIRLINES) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
                        )
                    }
                }
            }
        }
    }
}

/** 预览小块：内容块恒定，只有描边跟着"标准 / 无框线"变 */
@Composable
private fun ThemeSwatch(showBorder: Boolean) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Inline),
        color = AppCardContainer,
        border = if (showBorder) BorderStroke(1.dp, AppCardBorder) else null,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth(SWATCH_BAR_WIDTH)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/** 色板预览：一枚小卡上并排四色圆点（抬升面 / 底色 / 主色 / 冷辅色），深浅跟当前系统模式走 */
@Composable
private fun PaletteSwatch(palette: ThemePalette) {
    val c = if (isSystemInDarkTheme()) palette.dark else palette.light
    Surface(
        shape = RoundedCornerShape(AppRadii.Inline),
        color = AppCardContainer,
        border = BorderStroke(1.dp, AppCardBorder),
        modifier = Modifier.size(44.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(c.surfaceRaised, c.surfaceBase, c.brand, c.accentCool).forEach { color ->
                Box(
                    Modifier
                        .padding(horizontal = AppSpacing.Xxs)
                        .size(12.dp)
                        .background(color, CircleShape),
                )
            }
        }
    }
}
