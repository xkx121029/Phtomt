package com.phoneagent.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.model.AgentState
import com.phoneagent.ui.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun AgentScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val agent by vm.agentState.collectAsState()
    val queue by vm.taskQueue.collectAsState()
    val needsUser by vm.needsUser.collectAsState()
    val overlayGranted by vm.overlayGranted.collectAsState()
    val planPhase by vm.planPhase.collectAsState()
    val planStream by vm.planStream.collectAsState()
    var task by rememberSaveable { mutableStateOf("") }
    val buzz = com.phoneagent.ui.components.rememberHapticClick()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Agent", style = MaterialTheme.typography.headlineMedium)
        Text(
            "描述任务，AI 将逐步接管手机执行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        AgentRunCard(agent, vm)

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            label = { Text("你想让 AI 完成什么？") },
            placeholder = { Text("例如：打开设置，把字体调大") },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            minLines = 3,
            enabled = !agent.isRunning,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (!agent.isRunning) {
                Button(
                    onClick = { buzz(); vm.startPlanning(task) },
                    enabled = task.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("开始")
                }
            } else {
                OutlinedButton(
                    onClick = { vm.stopAgent() },
                    colors = ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        PlanPanel(vm, planPhase, agent.isRunning, planStream)

        if (!overlayGranted) {
            Spacer(Modifier.height(16.dp))
            OverlayPermissionBanner(onGrant = { vm.openOverlaySettings(context) })
        }

        if (needsUser) {
            Spacer(Modifier.height(16.dp))
            CollaborationCard(vm)
        }

        if (queue.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TaskQueueCard(queue)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun rememberTranslated(text: String, vm: MainViewModel): String {
    var translated by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if (text.isBlank() || isMostlyChinese(text)) {
            translated = null
        } else {
            delay(600) // 流式输出时防止频繁触发，等待停顿后翻译
            translated = vm.translate(text)
        }
    }
    return when {
        text.isBlank() -> text
        isMostlyChinese(text) -> text
        else -> translated ?: text
    }
}

private fun isMostlyChinese(text: String): Boolean {
    val cjk = text.count { it.code in 0x4E00..0x9FFF }
    return text.isNotEmpty() && cjk.toFloat() / text.length > 0.3f
}

@Composable
private fun PlanPanel(
    vm: MainViewModel,
    phase: com.phoneagent.agent.PlanPhase,
    isRunning: Boolean,
    streamText: String = "",
) {
    var manualAnswer by rememberSaveable { mutableStateOf("") }
    val pBuzz = com.phoneagent.ui.components.rememberHapticClick()
    when (phase) {
        is com.phoneagent.agent.PlanPhase.Planning -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("AI 正在思考并规划...", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (streamText.isNotBlank()) {
                        val stream = rememberTranslated(streamText, vm)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stream,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        )
                    }
                }
            }
        }
        is com.phoneagent.agent.PlanPhase.Clarifying -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("需要向你确认", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(8.dp))
                    val q = rememberTranslated(phase.clarification.question, vm)
                    Text(q, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(12.dp))
                    phase.clarification.options.forEach { opt ->
                        val optLabel = rememberTranslated(opt.label, vm)
                        val optDesc = rememberTranslated(opt.description, vm)
                        Surface(
                            onClick = { vm.answerClarification(opt) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(optLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (opt.description.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(optDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualAnswer,
                        onValueChange = { manualAnswer = it },
                        label = { Text("✏️ 我想自己说") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            vm.answerClarification(com.phoneagent.model.ClarificationOption(id = "manual", label = manualAnswer))
                            manualAnswer = ""
                        },
                        enabled = manualAnswer.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("提交我的回答") }
                }
            }
        }
        is com.phoneagent.agent.PlanPhase.AwaitingApproval -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("执行计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("预计 ${phase.plan.estimatedTimeSeconds}s · 置信度 ${(phase.plan.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    phase.plan.steps.forEachIndexed { i, s ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            val desc = rememberTranslated(s.description, vm)
                            Text(desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { pBuzz(); vm.cancelPlanning() }, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(
                            onClick = { pBuzz(); vm.approvePlan() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                        ) { Text("批准并开始") }
                    }
                }
            }
        }
        is com.phoneagent.agent.PlanPhase.Approved -> {
            if (isRunning) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("计划已批准，正在执行...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        is com.phoneagent.agent.PlanPhase.Error -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("规划失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(6.dp))
                    val errMsg = rememberTranslated(phase.message, vm)
                    Text(errMsg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        is com.phoneagent.agent.PlanPhase.Idle -> {}
    }
}

@Composable
private fun OverlayPermissionBanner(onGrant: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                "开启悬浮窗权限可实时显示执行进度跑马灯",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("去授权")
            }
        }
    }
}

@Composable
private fun TaskQueueCard(queue: List<String>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("待执行队列（${queue.size}）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            queue.forEachIndexed { i, t ->
                Text(
                    "${i + 1}. $t",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CollaborationCard(vm: MainViewModel) {
    var hint by rememberSaveable { mutableStateOf("") }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("需要你的协助", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Text(
                "Agent 已暂停，请手动接管处理，或告诉我该怎么做。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = hint,
                onValueChange = { hint = it },
                label = { Text("指导 AI（可留空表示已手动处理好）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.dismissUser() }, modifier = Modifier.weight(1f)) {
                    Text("已手动处理")
                }
                Button(
                    onClick = { vm.provideUserHint(hint) },
                    enabled = hint.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("指导 AI")
                }
            }
        }
    }
}

@Composable
private fun AgentRunCard(state: AgentState, vm: MainViewModel) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.isRunning -> CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.phase == AgentState.Phase.DONE -> Icon(Icons.Rounded.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    state.phase == AgentState.Phase.ERROR -> Icon(Icons.Rounded.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Text("步骤 ${state.stepCount}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            val msg = rememberTranslated(state.message, vm)
            Text(
                msg.ifBlank { "等待任务下发…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}