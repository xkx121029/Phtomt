package com.phoneagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppCardBorder
import com.phoneagent.ui.components.AppCardContainer
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.appBorderStroke

// ========== 设置页设计语言（Material 3 Expressive） ==========
//
// 与旧版「每行一个彩色圆角图标块 + 中性大卡」的通用列表拉开距离，这里立三条规矩：
// 1. 一个分区只有一个色相（[SettingsTone]），色相负责"认组"，组内条目共用一色；
// 2. 分组容器带极淡的强调色底 + 眉条，块与块之间靠色相区分，不靠一堆描边；
// 3. 排版放大：页头用 headline，条目图标进圆形底盘，留白更松。
// 全项目只此一处定义，各设置页一律引用，杜绝各页各写一套。

/** 设置页统一横向留白，与全站页面栅格一致 */
internal val SettingsScreenPad = 20.dp

/** 分区容器圆角：比通用卡片（24dp）再大一档，读起来更"整块" */
private val SectionShape = RoundedCornerShape(28.dp)

/**
 * 分区色调（语义分组色）。
 *
 * 四系对应色板里的品牌 / 冷辅 / 暖辅 / 中性——不是随手挑的彩虹。
 * 分组与色调的绑定是固定的：AI 接入=品牌、运行行为=冷、设备接入=暖、
 * 外观复用品牌、系统走中性；用户在整页里靠"颜色→类别"形成肌肉记忆。
 */
internal enum class SettingsTone { Brand, Cool, Warm, Neutral }

/** 取出色调对应的强调色 */
@Composable
internal fun SettingsTone.accent(): Color = when (this) {
    SettingsTone.Brand -> MaterialTheme.colorScheme.primary
    SettingsTone.Cool -> MaterialTheme.colorScheme.tertiary
    SettingsTone.Warm -> MaterialTheme.colorScheme.secondary
    SettingsTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 分区容器：置顶「眉条 + 标题 + 说明」页眉，下面是同一色系的内容。
 *
 * 底色是强调色按极低透明度掺进页面底，而不是完整 container 色——
 * 完整 container 是一整块饱和面，连排五个会把页面变成色卡；
 * 掺淡之后一眼能看出"这块属于同类"，又保持工具界面的克制。
 */
@Composable
internal fun SettingsSection(
    title: String,
    subtitle: String? = null,
    tone: SettingsTone = SettingsTone.Brand,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = tone.accent()
    Surface(
        shape = SectionShape,
        color = accent.copy(alpha = 0.06f),
        border = appBorderStroke(1.dp, accent.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = if (subtitle != null) 10.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
            ) {
                // 眉条：一小段竖条，把分组的色相钉在标题旁边
                Box(
                    Modifier
                        .size(width = 4.dp, height = 20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Column(Modifier.fillMaxWidth()) { content() }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 中性容器（无分区标题时用）：底色走通用卡片面，与分区容器区分开 */
@Composable
internal fun SettingsPlainCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AppCardContainer,
        border = appBorderStroke(1.dp, AppCardBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 分区内条目之间的细分割线：起始缩进对齐文字（图标底盘 44 + 间距 14 + 左 16） */
@Composable
internal fun SettingsDivider(inset: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 74.dp else 16.dp, end = 16.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

/**
 * 导航条目：圆形图标底盘 + 标题 + 副标题 + 可选摘要 + 箭头。
 *
 * 图标底盘由旧版的"圆角方块"改为"正圆"：圆在这里是**动作入口**的记号，
 * 与设置页里其他方块形控件（输入框、卡片）在形状语义上分开。
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tone: SettingsTone,
    summary: String? = null,
    onClick: () -> Unit,
) {
    val buzz = rememberHapticClick()
    val accent = tone.accent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 118.dp),
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 二级页页头：圆形返回键 + 大字标题 + 可选说明。
 *
 * 标题用 headlineMedium（28sp）而不是旧的 titleLarge：设置是"进到某一类里做事"，
 * 大标题给出明确的落点感，也让二级页与主页页头共用同一种typography体质。
 */
@Composable
internal fun SettingsPageHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    val buzz = rememberHapticClick()
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable {
                    buzz()
                    onBack()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.ArrowBack, contentDescription = "返回", modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 开关行：标题 + 可选说明 + 右侧开关，沿用全站圆角语言 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** 带标签的表单字段 */
@Composable
internal fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

/**
 * 选择芯片：设置页所有「单选胶囊」的统一语言（提示词语言 / 更新渠道等）。
 *
 * 选中 = 主色 14% 底 + 1.5dp 主色描边 + 主色文字；未选中 = 通用卡片面 + 发丝描边。
 * 与主题页 ThemeOption / PaletteOption 的选择语言同源——此前提示词语言与更新渠道
 * 各写一套（实底 primaryContainer vs 主色描边），选中观感在两页之间对不上，故收口于此。
 */
@Composable
internal fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buzz = rememberHapticClick()
    Surface(
        shape = RoundedCornerShape(AppRadii.Tile),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else AppCardContainer,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                 else appBorderStroke(1.dp, AppCardBorder),
        modifier = modifier.clickable {
            buzz()
            onClick()
        },
    ) {
        Box(
            modifier = Modifier.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}