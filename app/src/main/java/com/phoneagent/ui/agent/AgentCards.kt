package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Switch
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
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.motionSettings
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.phoneagent.screen.ScreenSharingService
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.theme.emptyStateIconColor
import com.phoneagent.ui.theme.emptyStateTextColor
import com.phoneagent.ui.theme.phoneCameraHoleColor
import com.phoneagent.ui.theme.phoneShellBorderColor
import com.phoneagent.ui.theme.phoneShellColor
import com.phoneagent.ui.theme.runningIndicatorColor

/** Agent 页的"审核AI"运行开关：独立审核者复核每个动作是否有页面证据 */
@Composable
internal fun AgentReviewToggle(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("审核AI", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "每个动作由独立审核者复核是否有页面证据，防止点到不存在的控件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
internal fun OverlayPermissionBanner(onGrant: () -> Unit) {
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
internal fun TaskQueueCard(queue: List<String>) {
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
internal fun CollaborationCard(vm: MainViewModel) {
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
internal fun AgentRunCard(state: AgentState, vm: MainViewModel) {
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
            // 内置手机场景：任务运行时实时展示 AI 正在操作的画面，供用户观看
            PhonePreviewArea(active = state.isRunning)
        }
    }
}

@Composable
internal fun PhonePreviewArea(active: Boolean) {
    if (!active) return
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(active) {
        while (true) {
            runCatching { ScreenSharingService.instance?.previewFrame() }
                .getOrNull()?.let { frame = it }
            delay(500)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(AppRadii.Chip))
                    .background(if (active) runningIndicatorColor() else emptyStateIconColor())
            )
            Text(
                if (active) "AI 实时操作预览" else "手机场景预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 内置手机外壳 + 实时画面
        val shellShape = RoundedCornerShape(AppRadii.Overlay)
        Box(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .aspectRatio(9f / 16f)
                .clip(shellShape)
                .background(phoneShellColor())
                .border(2.dp, phoneShellBorderColor(), shellShape),
        ) {
            val shot = frame
            if (shot != null && !shot.isRecycled) {
                Image(
                    bitmap = shot.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(7.dp)
                        .clip(RoundedCornerShape(AppRadii.Bubble)),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        "暂无画面",
                        style = MaterialTheme.typography.titleMedium,
                        color = emptyStateTextColor(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "开启屏幕共享授权后\n可实时观看 AI 操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = emptyStateTextColor(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            // 顶部摄像头点缀（手机特征）
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(AppRadii.Chip))
                    .background(phoneCameraHoleColor(active = frame != null))
            )
        }
    }
}