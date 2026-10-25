package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.LocalNavClearance

/**
 * 运行与提示词页。
 *
 * 由旧「Agent 运行」页拆出「执行通道」后的剩余部分——执行通道的优先级开关与
 * 「权限与执行通道」页里的配对界面原本说的是同一件事，现已统一收进后者。
 * 本页只负责：跑多少步、要不要悬浮窗、要不要页面记忆，以及发给模型的提示词。
 */
@Composable
internal fun SettingsAgent(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader(
            title = "运行与提示词",
            onBack = onBack,
            subtitle = "控制 Agent 的执行方式与发给模型的提示词",
        )
        // 与全设置流程同一条骨架节奏：页头后 20 / 分区之间 16 / 结尾 28（不再用 spacedBy，
        // 否则页头与首区之间只有 16，和其他二级页对不上）
        Spacer(Modifier.height(20.dp))

        SettingsSection(title = "运行参数", subtitle = "步数与悬浮窗", tone = SettingsTone.Cool) {
            SettingsToggleRow(
                title = "限制最大步数",
                subtitle = if (st.maxSteps > 0) "运行 ${st.maxSteps} 步后自动停止" else "不设限，直到任务完成或手动停止",
                value = st.maxSteps > 0,
            ) { enabled ->
                st.maxSteps = if (enabled) (if (st.maxSteps > 0) st.maxSteps else 20) else 0
                save()
            }
            if (st.maxSteps > 0) {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    LabeledField("最大步数  ${st.maxSteps}") {
                        Slider(
                            // 拖动只更新本地编辑态（数值即时跟手），松手才落盘，
                            // 避免拖一次滑杆产生几十次 DataStore 写入
                            value = st.maxSteps.toFloat(),
                            onValueChange = { st.maxSteps = it.toInt() },
                            onValueChangeFinished = { save() },
                            valueRange = 5f..60f,
                            steps = 10,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            SettingsToggleRow(
                title = "启用桌面悬浮窗",
                subtitle = "关闭后任务进度只在 App 内展示；悬浮窗为可选能力，不影响任务执行",
                value = st.floatingWindowEnabled,
            ) { enabled ->
                st.floatingWindowEnabled = enabled
                save()
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "页面记忆", subtitle = "任务执行时自动记忆页面热点与跳转路径", tone = SettingsTone.Cool) {
            SettingsToggleRow(
                title = "启用页面记忆",
                subtitle = "执行任务时自动记忆遇到过的页面；屏幕以淡色圆环标记已知控件（不挡触摸），后续任务规划与决策可参考",
                value = st.pageMemoryEnabled,
            ) { enabled ->
                st.pageMemoryEnabled = enabled
                save()
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "副屏模式", subtitle = "任务在模拟副屏上运行，不抢占当前屏幕", tone = SettingsTone.Cool) {
            SettingsToggleRow(
                title = "启用副屏模式",
                subtitle = "通过 Shizuku 挂一块 1080x1920 模拟副屏，任务的应用、点击、输入与截图全部在副屏上进行，" +
                    "你可以继续正常使用主屏。限制：读不到无障碍元素树，决策只能依赖视觉；部分厂商系统会禁用模拟辅助显示",
                value = st.virtualDisplayEnabled,
            ) { enabled ->
                st.virtualDisplayEnabled = enabled
                save()
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "提示词", subtitle = "提示词语言与系统提示词", tone = SettingsTone.Cool) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(
                    "提示词语言",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "选择发送给 AI 的提示词语言（系统/决策/验证等 8 组 Prompt）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf("CN" to "中文", "EN" to "English").forEach { (code, label) ->
                        // 单选胶囊统一走 SettingsChoiceChip（与更新渠道/主题选择同一套语言）
                        SettingsChoiceChip(
                            label = label,
                            selected = st.promptLanguage == code,
                            onClick = {
                                st.promptLanguage = code
                                save()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            SettingsDivider(inset = false)
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(
                    "系统提示词",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "留空 = 使用内置提示词（含执行铁律与动作格式）；填写后用你写的替换它，技能区块仍会追加在末尾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // 文本类改动不逐字落库：设置流会回灌编辑态，长文本（贴一段提示词）会与输入互抢光标
                var draft by remember { mutableStateOf(st.systemPrompt) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("留空 = 使用内置提示词（推荐）") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        when {
                            // 「未保存」优先于「当前生效」：清空输入框但还没保存时，
                            // 生效中的仍是旧值，不能显示成"内置提示词"
                            draft != st.systemPrompt -> "有未保存的修改（${draft.length} 字）"
                            draft.isBlank() -> "当前生效：内置提示词"
                            else -> "当前生效：自定义（${draft.length} 字）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draft.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.isNotBlank()) {
                        TextButton(
                            onClick = {
                                draft = ""
                                st.systemPrompt = ""
                                save()
                            },
                        ) { Text("恢复内置") }
                    }
                    TextButton(
                        onClick = {
                            st.systemPrompt = draft
                            save()
                        },
                        enabled = draft != st.systemPrompt,
                    ) { Text("保存修改") }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}