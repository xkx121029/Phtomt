package com.phoneagent.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.PhoneAgentTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音输入交互 Demo：完整跑通「长按录音 → 音量气泡 → 上滑预览/取消 → 松手分发」。
 *
 * 引擎可切换：模拟音量（调 UI 用）/ 系统 SpeechRecognizer（真机验证用）。
 * 组件与 ASR 的接法示例都在这个文件里 —— 上层只消费 engine.finalText 流与结果回调。
 */
class VoiceDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    VoiceDemoScreen()
                }
            }
        }
    }
}

@Composable
private fun VoiceDemoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 录音权限：未授予时提前引导；录音中权限被撤销走组件的静默丢弃路径
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted = it }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 引擎选择：0=模拟音量 1=系统识别；录音中不允许切换
    var engineChoice by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    val engine = remember(engineChoice) {
        if (engineChoice == 0) FakeVoiceEngine() else SpeechRecognizerEngine(context)
    }
    DisposableEffect(engine) {
        onDispose { (engine as? SpeechRecognizerEngine)?.destroy() }
    }

    var draft by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf("帮我看看今天的日程") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .imePadding(),
    ) {
        // 顶栏：引擎切换
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("语音输入 Demo", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            EngineChip("模拟音量", engineChoice == 0, !recording) { engineChoice = 0 }
            EngineChip("系统识别", engineChoice == 1, !recording) { engineChoice = 1 }
        }

        // 消息区
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            messages.forEach { msg ->
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(AppRadii.Bubble))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                ) {
                    Text(msg, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        // 底部语音输入栏
        VoiceRecordField(
            engine = engine,
            value = draft,
            onValueChange = { draft = it },
            onSend = { text ->
                messages.add(text)
                draft = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
            onStartRecord = { recording = true },
            onCancelRecord = {
                recording = false
                Toast.makeText(context, "已取消发送", Toast.LENGTH_SHORT).show()
            },
            onRecordTooShort = {
                recording = false
                Toast.makeText(context, "说话时间太短", Toast.LENGTH_SHORT).show()
            },
            onStartFailed = {
                Toast.makeText(context, "无法启动录音，请检查权限与系统识别服务", Toast.LENGTH_SHORT).show()
                if (!permissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onFinishRecord = { _, _, mode ->
                recording = false
                scope.launch {
                    // 松手浮层已消失；识别文本与错误谁先到谁生效（超时 8s 兜底）
                    val outcome = withTimeoutOrNull(8_000) {
                        combine(engine.finalText, engine.error) { t, e -> t to e }
                            .first { (t, e) -> !t.isNullOrEmpty() || !e.isNullOrEmpty() }
                    }
                    val text = outcome?.first
                    val err = outcome?.second
                    when {
                        !text.isNullOrEmpty() && mode == VoiceFinishMode.SEND -> messages.add(text)
                        !text.isNullOrEmpty() -> draft = (draft + " " + text).trim()
                        !err.isNullOrEmpty() ->
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        else ->
                            Toast.makeText(context, "未识别到内容", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun EngineChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    PressableScale(onClick = onClick, enabled = enabled) {
        Box(
            Modifier
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
