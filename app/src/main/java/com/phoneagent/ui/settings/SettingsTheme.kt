package com.phoneagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.phoneagent.ui.components.GlassTokens
import com.phoneagent.ui.components.rememberGlassState
import dev.chrisbanes.haze.hazeSource
import com.phoneagent.ui.components.AppCardBorder
import com.phoneagent.ui.components.AppCardContainer
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.ThemePalette
import com.phoneagent.ui.theme.glassTintPreview

/**
 * 主题页：选择整体视觉风格。
 *
 * 目前有三条轴——**配色**、**框线**与**磨砂浓淡**：配色决定整套品牌主色与底色（深浅跟随系统）；
 * 标准保留卡片 / 面板 / 输入面的发丝描边，无框线则把这些装饰性描边全部去掉，层次只靠底色差拉开；
 * 磨砂浓淡控制玻璃页眉/悬浮面板的透光度，负更透、正更实。
 * 选中即时落盘并即时生效：配色与框线走 `PhoneAgentTheme(paletteId / bordersEnabled = ...)`，
 * 保存后整棵树重组，不需要重启。
 */
@Composable
internal fun SettingsTheme(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        SettingsTopBar("主题", onBack)
        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("主题风格", "选择整体视觉风格，改完立即生效")
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // 配色：整套品牌主色 + 底色（深浅跟随系统）。存 ThemePalette.id 字符串，
        // 落盘后 settingsFlow 驱动 PhoneAgentTheme 重组，整棵树立即换色，不用重启
        GroupCard {
            GroupHeader("配色", "品牌主色与底色，深浅跟随系统")
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // 磨砂浓淡：微调玻璃 tint 的透明度（相对色板默认 ±25 个百分点点），
        // 拖动只更新本地编辑态（数值跟手），松手才落盘——落盘后 settingsFlow 驱动
        // PhoneAgentTheme 重组，整棵树的玻璃面立即跟着变，避免拖一次写几十次 DataStore
        GroupCard {
            GroupHeader("磨砂玻璃", "玻璃面板的透光度：向左更透，向右更实")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 实时预览样片：上面是"滚过玻璃下的内容"（彩色渐变 + 文字），
                // 下面浮一块小玻璃板模拟页眉——tint 直接取滑杆的调节值，拖动跟手，
                // 不用等落盘。真正落盘后全局玻璃面走的是同一个 alpha 位移实现点，
                // 预览所见 = 全局所得
                val previewTint = glassTintPreview(st.glassTintOffset)
                val previewGlass = rememberGlassState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(AppRadii.Tile)),
                ) {
                    // 取样源：刻意用高饱和渐变 + 中英文，玻璃透不透一眼可辨
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(previewGlass)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF5FA0C9), Color(0xFF9AC98F), Color(0xFFE4B45C),
                                    ),
                                ),
                            )
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "滚过玻璃下方的内容",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            Text(
                                "Frosted sample · 0123456789",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                    // 玻璃浮板：与真实页眉同款材质（同模糊半径档位 + 高光 + 描边），
                    // 只铺样片下半部、四边留白，模拟"内容滚到玻璃下"的观感
                    GlassSurface(
                        hazeState = previewGlass,
                        tintOverride = previewTint,
                        shape = RoundedCornerShape(AppRadii.Tile),
                        blurRadius = GlassTokens.BlurCompact,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .height(44.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "玻璃页眉预览",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 主题选项行。左侧一个小预览块直接演示"有框线 / 无框线"的差别，
 * 比只写文字更好判断；选中态用主色描边 + 主色文字，沿用设置页既有的选择语言。
 */
@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    showBorder: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Tile),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeSwatch(showBorder = showBorder)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
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
                    .fillMaxWidth(0.5f)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * 配色选项行：左侧色板预览（底色 / 抬升面 / 主色 / 冷辅色四点），右侧名称与说明。
 * 预览色取配色自身按当前深浅模式的那一份——所见即所选，不借当前主题的颜色。
 */
@Composable
private fun PaletteOption(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Tile),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PaletteSwatch(palette)
            Column(Modifier.weight(1f)) {
                Text(
                    palette.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    palette.subtitle,
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

/** 色板预览：一枚小卡上并排四色圆点（抬升面 / 底色 / 主色 / 冷辅色），深浅跟当前系统模式走 */
@Composable
private fun PaletteSwatch(palette: ThemePalette) {
    val c = if (androidx.compose.foundation.isSystemInDarkTheme()) palette.dark else palette.light
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
                        .padding(horizontal = 2.dp)
                        .size(12.dp)
                        .background(color, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
    }
}
