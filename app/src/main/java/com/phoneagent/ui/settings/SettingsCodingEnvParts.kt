package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phoneagent.coding.env.CodingEnvClient
import com.phoneagent.env.EnvApi
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [SettingsCodingEnv] 的展示零件：步骤列表、日志框、安装清单与几个文案换算 */

@Composable
internal fun StepList(steps: List<CodingEnvClient.Step>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        steps.forEach { step ->
            val tint = when (step.state) {
                EnvApi.STEP_OK -> Success
                EnvApi.STEP_FAIL -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            val icon = when (step.state) {
                EnvApi.STEP_OK -> AppIcons.CheckCircle
                EnvApi.STEP_FAIL -> AppIcons.ErrorOutline
                else -> AppIcons.Loading
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    step.title,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(stepLabel(step.state), style = MaterialTheme.typography.bodySmall, color = tint)
            }
        }
    }
}

/** 只显示最后几行：构建日志动辄上万行，滚动区域里塞全量没有意义 */
@Composable
internal fun LogView(lines: List<String>) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Chip))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(AppSpacing.Md),
    ) {
        Text(
            lines.takeLast(8).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EnvStateRows(state: Map<String, String>) {
    val rows = listOf(
        "Linux" to state[EnvApi.STATE_LINUX],
        "JDK" to state[EnvApi.STATE_JDK],
        "Gradle" to state[EnvApi.STATE_GRADLE],
        "SDK" to state[EnvApi.STATE_SDK],
        "aapt2" to state[EnvApi.STATE_AAPT2],
        "安装时间" to state[EnvApi.STATE_INSTALLED_AT]?.let(::formatInstalledAt),
    )
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        rows.forEach { (label, value) ->
            if (value != null) SettingsStatusRow(label, value)
        }
    }
}

internal fun installedText(ui: CodingEnvClient.UiState): String = when {
    ui.installedVersion == null -> "未安装"
    ui.needsInstall -> "有新版本（当前 v${ui.installedVersion}，随包 v${ui.bundledVersion ?: "未知"}）"
    else -> "已安装 v${ui.installedVersion}"
}

internal fun gradleSourceLabel(source: String): String = when (source) {
    "wrapper" -> "工程自带 wrapper"
    "agp" -> "按 AGP 配套表"
    else -> "默认值"
}

internal fun stepLabel(state: String): String = when (state) {
    EnvApi.STEP_OK -> "完成"
    EnvApi.STEP_FAIL -> "失败"
    else -> "进行中"
}

private fun formatInstalledAt(raw: String): String = runCatching {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    fmt.format(Date(raw.toLong()))
}.getOrDefault(raw)