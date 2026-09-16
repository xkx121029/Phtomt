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
import com.phoneagent.domain.model.AgentState
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
import com.phoneagent.device.screen.ScreenSharingService
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.theme.emptyStateIconColor
import com.phoneagent.ui.theme.emptyStateTextColor
import com.phoneagent.ui.theme.phoneCameraHoleColor
import com.phoneagent.ui.theme.phoneShellBorderColor
import com.phoneagent.ui.theme.phoneShellColor
import com.phoneagent.ui.theme.runningIndicatorColor

@Composable
internal fun PlanPanel(
    vm: MainViewModel,
    phase: com.phoneagent.engine.PlanPhase,
    isRunning: Boolean,
    streamText: String = "",
) {
    var manualAnswer by rememberSaveable { mutableStateOf("") }
    val pBuzz = com.phoneagent.ui.components.rememberHapticClick()
    val reduceMotion = motionSettings().reduceMotion
    // 规划阶段切换动画：淡入 + 轻微纵向位移（减少动画时仅淡入）
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(DurationFast, easing = EaseOut)) togetherWith fadeOut(tween(DurationFast, easing = EaseOut))
            } else {
                val dy = 14
                (fadeIn(tween(DurationFast, easing = EaseOut)) + slideInVertically(tween(DurationFast, easing = EaseOut)) { it / dy }) togetherWith
                    (fadeOut(tween(DurationFast, easing = EaseOut)) + slideOutVertically(tween(DurationFast, easing = EaseOut)) { -it / dy })
            }
        },
        label = "plan-phase",
    ) { p ->
        when (p) {
        is com.phoneagent.engine.PlanPhase.Planning -> {
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
        is com.phoneagent.engine.PlanPhase.Clarifying -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("需要向你确认", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(8.dp))
                    val q = rememberTranslated(p.clarification.question, vm)
                    Text(q, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(12.dp))
                    p.clarification.options.forEach { opt ->
                        val optLabel = rememberTranslated(opt.label, vm)
                        val optDesc = rememberTranslated(opt.description, vm)
                        Surface(
                            onClick = { vm.answerClarification(opt) },
                            shape = RoundedCornerShape(AppRadii.Tile),
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
                            vm.answerClarification(com.phoneagent.domain.model.ClarificationOption(id = "manual", label = manualAnswer))
                            manualAnswer = ""
                        },
                        enabled = manualAnswer.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("提交我的回答") }
                }
            }
        }
        is com.phoneagent.engine.PlanPhase.AwaitingApproval -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("执行计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("预计 ${p.plan.estimatedTimeSeconds}s · 置信度 ${(p.plan.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    p.plan.steps.forEachIndexed { i, s ->
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
        is com.phoneagent.engine.PlanPhase.Approved -> {
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
        is com.phoneagent.engine.PlanPhase.Error -> {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("规划失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(6.dp))
                    val errMsg = rememberTranslated(p.message, vm)
                    Text(errMsg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        is com.phoneagent.engine.PlanPhase.Idle -> {}
        }
    }
}