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
 * 任务行为页。
 *
 * 收口「AI 怎么干活」这一组配置：动作模式（授权范围）、步数上限、提示词语言与自定义提示词，
 * 以及默认收起的高级项（副屏模式）。
 *
 * 从本页搬走的两项：悬浮窗开关 → 「界面效果」（与跑马灯同页，同一件事只留一个设置点）；
 * 页面记忆 → 「数据与记忆」（记忆类开关与清空动作放在一起）。
 * Agent 页输入栏下方的模式转盘仍保留，它是在跑任务时的快捷切换，与这里的设置写同一个键。
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
            title = "任务行为",
            onBack = onBack,
            subtitle = "动作模式、步数与提示词；高级项默认收起",
        )
        // 与全设置流程同一条骨架节奏：页头后 20 / 分区之间 16 / 结尾 28（不再用 spacedBy，
        // 否则页头与首区之间只有 16，和其他二级页对不上）
        Spacer(Modifier.height(20.dp))

        // ---- 动作模式：决定 AI 能提出哪一类操作（与执行通道正交）----
        val mode = com.phoneagent.engine.execution.ActionMode.fromKey(st.actionMode)
        SettingsSection(
            title = "动作模式",
            subtitle = "给 AI 的授权范围，与「执行通道」叠加生效",
            tone = SettingsTone.Cool,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    com.phoneagent.engine.execution.ActionMode.entries.forEach { item ->
                        SettingsChoiceChip(
                            label = item.label,
                            selected = st.actionMode == item.key,
                            onClick = {
                                st.actionMode = item.key
                                save()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = mode.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == com.phoneagent.engine.execution.ActionMode.FREE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (mode == com.phoneagent.engine.execution.ActionMode.FREE) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "自由模式允许 AI 自写命令并直调无障碍端点，只在你信任当前任务时使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "执行参数", subtitle = "跑多少步就停", tone = SettingsTone.Cool) {
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
        }

        Spacer(Modifier.height(16.dp))

        // ---- 高级项：默认折叠，用到再展开（副屏）----
        SettingsSection(
            title = "高级执行选项",
            subtitle = "特殊场景才需要；保持默认即可正常使用",
            tone = SettingsTone.Neutral,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ExpandableCard(title = "副屏模式", initiallyExpanded = st.virtualDisplayEnabled) {
                    SettingsToggleRow(
                        title = "启用副屏模式",
                        subtitle = "在模拟副屏上跑任务，不抢占当前屏幕；读不到无障碍元素树，决策只能依赖视觉",
                        value = st.virtualDisplayEnabled,
                    ) { enabled ->
                        st.virtualDisplayEnabled = enabled
                        save()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "提示词", subtitle = "发送给模型的语言与自定义系统提示词", tone = SettingsTone.Cool) {
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
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // 自定义系统提示词是少数人才会动的项：默认收起，展开后才显示编辑器
                ExpandableCard(
                    title = "自定义系统提示词",
                    initiallyExpanded = st.systemPrompt.isNotBlank(),
                ) {
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
        }

        Spacer(Modifier.height(28.dp))
    }
}
