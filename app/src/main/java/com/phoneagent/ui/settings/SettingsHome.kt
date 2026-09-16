package com.phoneagent.ui.settings.SettingsHome

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppRadii

/** 设置主页（一级导航） */
@Composable
internal fun SettingsHome(st: SettingsState, onOpen: (SettingsPage) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "按模块分层管理 AI 服务与运行行为",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        GroupCard {
            SettingsEntry(
                icon = Icons.Filled.SmartToy,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                title = "AI 模型配置",
                subtitle = "主模型 / 视觉 / 思考链路",
                summary = st.model,
                onClick = { onOpen(SettingsPage.AI_MODELS) },
            )
            GroupDivider()
            SettingsEntry(
                icon = Icons.Filled.PlayArrow,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBackground = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f),
                title = "Agent 运行",
                subtitle = "步数限制与提示词语言",
                summary = if (st.maxSteps > 0) "最多 ${st.maxSteps} 步" else "不设限",
                onClick = { onOpen(SettingsPage.AGENT) },
            )
            GroupDivider()
            SettingsEntry(
                icon = Icons.Filled.Close,
                iconTint = MaterialTheme.colorScheme.error,
                iconBackground = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                title = "跳广告",
                subtitle = "自动跳过开屏与弹窗广告",
                summary = if (st.autoSkipAds) "已开启" else "已关闭",
                onClick = { onOpen(SettingsPage.AD_SKIP) },
            )
            GroupDivider()
            SettingsEntry(
                icon = Icons.Filled.Star,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBackground = MaterialTheme.colorScheme.secondary.copy(alpha = 0.13f),
                title = "视觉效果",
                subtitle = "屏幕边缘光效标定",
                summary = if (st.edgeLightingEnabled) "已开启" else "已关闭",
                onClick = { onOpen(SettingsPage.VISUAL) },
            )
            GroupDivider()
            SettingsEntry(
                icon = Icons.Filled.Search,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                title = "长线任务",
                subtitle = "执行策略 / 断点续传 / 任务模板",
                summary = "任务管理",
                onClick = { onOpen(SettingsPage.LONG_RUN) },
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}