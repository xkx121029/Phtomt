package com.phoneagent.ui.settings

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.task.ExecutionStrategy
import com.phoneagent.ui.MainViewModel
import kotlinx.coroutines.launch

/** 长线任务设置页：执行策略热切换 + 断点续传 + 任务模板库 */
@Composable
internal fun SettingsLongRun(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var strategy by remember { mutableStateOf(ExecutionStrategy.AUTO) }
    var checkpoint: com.phoneagent.task.Checkpoint? by remember { mutableStateOf(null) }
    var templates by remember { mutableStateOf(emptyList<com.phoneagent.task.TaskTemplate>()) }

    // 进入页面时加载当前状态
    LaunchedEffect(Unit) {
        strategy = runCatching { vm.currentStrategy() }.getOrDefault(ExecutionStrategy.AUTO)
        checkpoint = runCatching { vm.lastCheckpoint() }.getOrNull()
        templates = runCatching { vm.loadTemplates(context) }.getOrDefault(emptyList())
    }

    fun refresh() {
        scope.launch {
            checkpoint = runCatching { vm.lastCheckpoint() }.getOrNull()
            templates = runCatching { vm.loadTemplates(context) }.getOrDefault(emptyList())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SettingsTopBar("长线任务", onBack = onBack)
        Spacer(Modifier.height(16.dp))

        // ===== 执行策略热切换 =====
        Text("执行策略", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val options = listOf(
            ExecutionStrategy.AUTO to "智能",
            ExecutionStrategy.SCRIPT to "脚本",
            ExecutionStrategy.REACT to "逐步",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = strategy == key,
                    onClick = {
                        strategy = key
                        vm.setExecutionStrategy(key)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    label = {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "智能=模板命中按脚本、否则逐步；脚本=复用已生成计划自主推进；逐步=每步云端决策（最稳）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // ===== 断点续传 =====
        Text("断点续传", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        GroupCard {
            val ck = checkpoint
            if (ck != null) {
                Text(
                    "上次任务：${ck.task}\n已完成 ${ck.completedSteps} 步" +
                        (if (ck.totalPlannedSteps > 0) "（共 ${ck.totalPlannedSteps} 步）" else "") +
                        "\n保存于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ck.updatedAt))}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                OutlinedButton(
                    onClick = { vm.resumeFromCheckpoint() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("续传此任务")
                }
            } else {
                Text(
                    "暂无已保存的任务检查点。任务执行中会自动保存进度。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ===== 任务模板库 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("任务模板库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh() }) { Text("刷新") }
        }
        Spacer(Modifier.height(8.dp))
        GroupCard {
            if (templates.isEmpty()) {
                Text(
                    "暂无模板。任务执行成功后会自动学习入库，下次相同目标零规划复用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                templates.forEachIndexed { i, t ->
                    if (i > 0) GroupDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(t.goal, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${t.plan.steps.size} 步 · 执行 ${t.executionCount} 次 · 成功 ${t.successCount} 次" +
                                if (t.failedStreak >= 3) " · 已失效（自动改走云端）" else
                                if (t.failedStreak > 0) " · 连续失败 ${t.failedStreak} 次" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t.failedStreak >= 3) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.deleteTemplate(context, t.id); refresh() }) { Text("删除") }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}