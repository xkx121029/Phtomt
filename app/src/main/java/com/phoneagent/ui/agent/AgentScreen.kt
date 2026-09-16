package com.phoneagent.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.theme.AppRadii

@Composable
fun AgentScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val agent by vm.agentState.collectAsState()
    val queue by vm.taskQueue.collectAsState()
    val needsUser by vm.needsUser.collectAsState()
    val overlayGranted by vm.overlayGranted.collectAsState()
    val planPhase by vm.planPhase.collectAsState()
    val planStream by vm.planStream.collectAsState()
    val settings by vm.settingsFlow.collectAsState()
    var task by rememberSaveable { mutableStateOf("") }
    val buzz = com.phoneagent.ui.components.rememberHapticClick()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AppTopBar(
            title = "Agent",
            subtitle = "描述任务，AI 将逐步接管手机执行",
        )

        Spacer(Modifier.height(8.dp))
        AgentRunCard(agent, vm)

        Spacer(Modifier.height(12.dp))
        AgentReviewToggle(
            checked = settings.enableReview,
            onToggle = { v ->
                buzz()
                vm.saveSettings(settings.copy(enableReview = v))
            },
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            label = { Text("你想让 AI 完成什么？") },
            placeholder = { Text("例如：打开设置，把字体调大") },
            shape = RoundedCornerShape(AppRadii.Tile),
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