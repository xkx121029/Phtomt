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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneagent.device.vision.NativeVisionEngine
import com.phoneagent.device.vision.OnDeviceVision
import com.phoneagent.device.vision.VisionModelDownloader
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import kotlinx.coroutines.launch

/**
 * 端侧视觉页：3B 视觉模型（Qwen2.5-VL）已整体并入主程序进程内运行，
 * 模型的一键下载（断点续传）、加载与启停都在这一页完成，不再依赖外部 APK。
 */
@Composable
internal fun SettingsOnDevice(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val buzz = rememberHapticClick()
    val scope = rememberCoroutineScope()

    // 模型状态：原生推理库 / 模型文件 / 内存加载
    var nativeOk by remember { mutableStateOf(false) }
    var modelPresent by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var fileStatus by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    // 下载进度与操作提示
    var downloading by remember { mutableStateOf(false) }
    var progressBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(1L) }
    var currentFile by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun refreshStatus() {
        nativeOk = OnDeviceVision.nativeAvailable
        modelPresent = OnDeviceVision.isModelPresent(ctx)
        loaded = OnDeviceVision.modelLoaded
        fileStatus = VisionModelDownloader.localStatus(ctx)
    }

    LaunchedEffect(Unit) { refreshStatus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        SettingsTopBar("端侧视觉", onBack)
        Spacer(Modifier.height(16.dp))

        // ========== A 模型状态 ==========
        GroupCard {
            GroupHeader("模型状态", "Qwen2.5-VL-3B · llama.cpp 端侧推理，识别在本机完成，截图不出设备")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                StatusRow("原生推理库", if (nativeOk) "可用" else "不可用（需 arm64 设备）")
                Spacer(Modifier.height(8.dp))
                StatusRow("模型文件", if (modelPresent) "已下载" else "未下载")
                // 两个模型文件的本地体积：未下载时显示 0 B，下载中断时能看到已落盘的部分
                StatusRow("主权重", formatBytes(fileStatus[NativeVisionEngine.TEXT_MODEL_FILE] ?: 0L))
                StatusRow("视觉投影", formatBytes(fileStatus[NativeVisionEngine.MMPROJ_FILE] ?: 0L))
                Spacer(Modifier.height(8.dp))
                StatusRow("内存加载", if (loaded) "已加载" else "未加载")
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== B 下载与加载 ==========
        GroupCard {
            GroupHeader("下载与加载", "模型合计约 3.1GB，下载支持断点续传，中断后可继续")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (downloading) {
                    Text(
                        "正在下载：$currentFile · ${formatBytes(progressBytes)} / ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (progressBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            buzz()
                            VisionModelDownloader.cancelled = true
                            hint = "已请求取消，已下载部分会保留，下次继续"
                        },
                        enabled = !VisionModelDownloader.cancelled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("取消下载") }
                } else {
                    Button(
                        onClick = {
                            buzz()
                            scope.launch {
                                downloading = true
                                hint = "开始下载…"
                                try {
                                    VisionModelDownloader.downloadAll(ctx) { done, total, file ->
                                        progressBytes = done
                                        totalBytes = total
                                        currentFile = file
                                    }
                                    refreshStatus()
                                    if (OnDeviceVision.isModelPresent(ctx)) {
                                        hint = "下载完成，正在加载模型…"
                                        val ok = OnDeviceVision.loadModel(ctx)
                                        refreshStatus()
                                        hint = if (ok) "模型已下载并加载完成，可打开「启用端侧视觉」" else "下载完成，但模型加载失败（可重试加载）"
                                    } else {
                                        hint = "下载未完成：已下载部分已保留，可再次点击继续"
                                    }
                                } catch (e: Exception) {
                                    hint = "下载失败：${e.message ?: "未知错误"}"
                                }
                                downloading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (modelPresent) "重新下载模型（约 3.1GB）" else "一键下载模型（约 3.1GB）") }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        buzz()
                        scope.launch {
                            hint = "正在加载模型…"
                            val ok = OnDeviceVision.loadModel(ctx)
                            refreshStatus()
                            hint = if (ok) "模型加载成功" else "模型加载失败：${NativeVisionEngine.lastRuntimeText.takeIf { it.isNotBlank() } ?: "未知原因"}"
                        }
                    },
                    enabled = modelPresent && !loaded && !downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (loaded) "模型已加载" else "加载模型") }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        buzz()
                        scope.launch {
                            testing = true
                            hint = "识别测试中（端侧推理需要一段时间）…"
                            // 空白图也会走完整推理链：3B 输出控件数组或回退 OCR，返回即证明链路可用
                            val bmp = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
                            val controls = OnDeviceVision.detectControls(ctx, bmp)
                            hint = "识别完成：返回 ${controls.size} 个控件（来源 ${controls.firstOrNull()?.source ?: "无"}），链路可用"
                            testing = false
                        }
                    },
                    enabled = loaded && !testing && !downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (testing) "识别测试中…" else "识别测试") }

                hint?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== C 运行开关 ==========
        GroupCard {
            GroupHeader("运行开关")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow(
                    "启用端侧视觉",
                    "用端侧 3B 模型框选控件，优先于云端与本地 OCR；不可用时自动回落",
                    st.enableExternalVision,
                ) {
                    st.enableExternalVision = it
                    save()
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "混合路由",
                    "简单任务（元素树可读）走端侧 3B 省额度；复杂任务（如游戏/WebView）直接走云端视觉",
                    st.smartVisionRoute,
                ) {
                    st.smartVisionRoute = it
                    save()
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 状态行：左侧名称，右侧取值 */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 字节数转可读文本（模型文件体积展示用） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
