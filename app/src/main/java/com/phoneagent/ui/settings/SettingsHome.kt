package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.icons.AppIcons

/**
 * 设置主页（一级导航）。
 * 按「模型 / 运行 / 外观与高级」三组归类，而不是把 5 个入口平铺成一张长卡片——
 * 分组后同类项挨在一起，找东西时先看组再看条目，扫视成本更低。
 */
@Composable
internal fun SettingsHome(st: SettingsState, onOpen: (SettingsPage) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.Lg),
    ) {
        Spacer(Modifier.height(AppSpacing.Sm))
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "按模块分层管理 AI 服务与运行行为",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 模型：接入哪家 AI ----
        SettingsSectionLabel("模型")
        GroupCard {
            SettingsEntry(
                icon = AppIcons.SmartToy,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                title = "AI 模型配置",
                subtitle = "主模型 / 视觉 / 思考链路",
                summary = st.model,
                onClick = { onOpen(SettingsPage.AI_MODELS) },
            )
        }

        // ---- 运行：AI 怎么干活 ----
        SettingsSectionLabel("运行")
        GroupCard {
            SettingsEntry(
                icon = AppIcons.Play,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBackground = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f),
                title = "Agent 运行",
                subtitle = "步数限制、执行通道、提示词语言",
                summary = if (st.maxSteps > 0) "最多 ${st.maxSteps} 步" else "不设限",
                onClick = { onOpen(SettingsPage.AGENT) },
            )
            GroupDivider()
            SettingsEntry(
                icon = AppIcons.Search,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                title = "长线任务",
                subtitle = "执行策略 / 断点续传 / 任务模板",
                summary = "任务管理",
                onClick = { onOpen(SettingsPage.LONG_RUN) },
            )
        }

        // ---- 外观与高级 ----
        SettingsSectionLabel("外观与高级")
        GroupCard {
            SettingsEntry(
                icon = AppIcons.Star,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBackground = MaterialTheme.colorScheme.secondary.copy(alpha = 0.13f),
                title = "视觉效果",
                subtitle = "屏幕边缘光效标定",
                summary = if (st.edgeLightingEnabled) "已开启" else "已关闭",
                onClick = { onOpen(SettingsPage.VISUAL) },
            )
        }
        Spacer(Modifier.height(AppSpacing.Lg))
    }
}

/** 分组小标题：字号小、颜色淡，只负责分隔，不与条目抢注意力 */
@Composable
private fun SettingsSectionLabel(text: String) {
    Spacer(Modifier.height(AppSpacing.Lg))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = AppSpacing.Xs, bottom = AppSpacing.Sm),
    )
}