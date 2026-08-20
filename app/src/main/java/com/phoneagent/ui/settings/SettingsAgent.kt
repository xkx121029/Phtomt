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