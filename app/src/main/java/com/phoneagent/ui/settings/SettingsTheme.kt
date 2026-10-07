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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppCardBorder
import com.phoneagent.ui.components.AppCardContainer
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii

/**
 * 主题页：选择整体视觉风格。
 *
 * 目前只有一条轴——**框线**：标准保留卡片 / 面板 / 输入面的发丝描边，
 * 无框线则把这些装饰性描边全部去掉，层次只靠底色差拉开。
 * 选中即时落盘并即时生效：开关本身走 `PhoneAgentTheme(bordersEnabled = ...)`，
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
