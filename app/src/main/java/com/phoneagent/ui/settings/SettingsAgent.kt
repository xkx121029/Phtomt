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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppRadii

/** Agent 运行页 */
@Composable
internal fun SettingsAgent(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        SettingsTopBar("Agent 运行", onBack)
        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("运行参数", "控制 Agent 的执行方式")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow("限制最大步数", if (st.maxSteps > 0) "运行 ${st.maxSteps} 步后自动停止" else "不设限，直到任务完成或手动停止", st.maxSteps > 0) { enabled ->
                    st.maxSteps = if (enabled) (if (st.maxSteps > 0) st.maxSteps else 20) else 0
                    save()
                }
                if (st.maxSteps > 0) {
                    Spacer(Modifier.height(8.dp))
                    LabeledField("最大步数  ${st.maxSteps}") {
                        Slider(
                            value = st.maxSteps.toFloat(),
                            onValueChange = {
                                st.maxSteps = it.toInt()
                                save()
                            },
                            valueRange = 5f..60f,
                            steps = 10,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    "启用桌面悬浮窗",
                    "关闭后任务进度只在 App 内展示；悬浮窗为可选能力，不影响任务执行",
                    st.floatingWindowEnabled,
                ) { enabled ->
                    st.floatingWindowEnabled = enabled
                    save()
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("执行通道", "shell 执行通路的优先顺序（无线 ADB 为主，Shizuku / Termux 可选）")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val channels = listOf(
                    "AUTO" to "自动",
                    "ADB" to "无线ADB",
                    "SHIZUKU" to "Shizuku",
                    "TERMUX" to "Termux",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    channels.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = st.executionChannel == key,
                            onClick = { st.executionChannel = key; save() },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = channels.size),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (st.executionChannel) {
                        "ADB" -> "仅用无线 ADB；未连接时无真实 shell，走无障碍执行。"
                        "SHIZUKU" -> "仅用 Shizuku；不可用时用无线 ADB 拉起。"
                        "TERMUX" -> "仅用 Termux（普通应用权限）：可跑 curl / python / 文本处理，不能执行系统命令。"
                        else -> "优先无线 ADB，其次 Shizuku，最后 Termux；都不可用则走无障碍执行。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("提示词语言", "选择发送给 AI 的提示词语言（系统/决策/验证等 8 组 Prompt）")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf("CN" to "中文", "EN" to "English").forEach { (code, label) ->
                    val selected = st.promptLanguage == code
                    Surface(
                        shape = RoundedCornerShape(AppRadii.Tile),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                st.promptLanguage = code
                                save()
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
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}