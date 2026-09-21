package com.phoneagent.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.core.ai.ProviderPresets
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import kotlinx.coroutines.launch

/** AI 模型配置页 */
@Composable
internal fun SettingsAiModels(vm: MainViewModel, st: SettingsState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val buzz = rememberHapticClick()
    var extStatus by remember { mutableStateOf<String?>(null) }
    var extTesting by remember { mutableStateOf(false) }
    var presetHint by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        SettingsTopBar("AI 模型配置", onBack)
        Spacer(Modifier.height(16.dp))

        // 主模型
        GroupCard {
            GroupHeader("主模型", "负责每步执行决策的对话模型")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                LabeledField("API 地址") {
                    OutlinedTextField(
                        value = st.baseUrl,
                        onValueChange = { st.baseUrl = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "支持 http://、内网地址与 localhost（如 http://192.168.1.5:8000/v1），不写协议时自动补全",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 快捷预设：点一下自动填好地址与模型，填完仍可手改
                Text("快捷预设", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderPresets.all.forEach { preset ->
                        FilterChip(
                            selected = st.baseUrl.trim().trimEnd('/') == preset.baseUrl,
                            onClick = {
                                buzz()
                                applyProviderPreset(st, preset)
                                presetHint = "已套用「${preset.name}」：${preset.model}"
                            },
                            label = {
                                Text(preset.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            },
                        )
                    }
                }
                presetHint?.let { hint ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LabeledField("API Key") {
                    OutlinedTextField(
                        value = st.apiKey,
                        onValueChange = { st.apiKey = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                LabeledField("模型") {
                    OutlinedTextField(
                        value = st.model,
                        onValueChange = { st.model = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // 功能选项
        GroupCard {
            GroupHeader("功能选项")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow("启用视觉理解", "结合截图理解图片、图表等元素树无法表达的内容", st.hasVision) { st.hasVision = it }
                Spacer(Modifier.height(8.dp))
                Text("视觉读图方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "自动 = 优先云端，失败回退本地 OCR",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val visionModes = listOf("云端" to "CLOUD", "本地OCR" to "LOCAL", "自动" to "AUTO")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    visionModes.forEachIndexed { i, (label, key) ->
                        SegmentedButton(
                            selected = st.visionMode == key,
                            onClick = { st.visionMode = key },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = visionModes.size),
                            label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("外挂视觉 Agent", "用本地视觉 APK（端侧 3B）框选控件，优先于云端与本地 OCR；不可用时自动回落", st.enableExternalVision) { st.enableExternalVision = it }
                Spacer(Modifier.height(6.dp))
                ToggleRow("混合路由", "简单任务（元素树可读）走端侧 3B 省额度；复杂任务（如游戏/WebView）直接走云端视觉", st.smartVisionRoute) { st.smartVisionRoute = it }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            extTesting = true; extStatus = "连接测试中…"
                            // 空白图也会触发完整 IPC：外挂服务收到后返回 OCR/3B 控件结果
                            val bmp = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
                            val controls = com.phoneagent.device.vision.ExternalVisionProvider.detectControls(ctx, bmp, 15_000)
                            val connected = com.phoneagent.device.vision.ExternalVisionProvider.isConnected
                            extStatus = if (connected) {
                                "外挂视觉服务已连接，跨进程识别返回 ${controls.size} 个控件"
                            } else {
                                "外挂视觉不可用：服务未安装或绑定失败（可回首页视觉卡点击\"打开外挂\"检查）"
                            }
                            extTesting = false
                        }
                    },
                    enabled = !extTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (extTesting) "连接测试中…" else "测试外挂连接") }
                extStatus?.let { s ->
                    Spacer(Modifier.height(4.dp))
                    Text(s, style = MaterialTheme.typography.bodySmall,
                        color = if (s.contains("已连接")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("附送屏幕截图", value = st.attachScreenshot) { st.attachScreenshot = it }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // 模型链路
        GroupCard {
            GroupHeader("模型链路", "长按拖动重新排序")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow(
                    "主模型思考",
                    "主模型支持深度思考时，规划/重规划直接由主模型完成",
                    st.mainThinking,
                ) { st.mainThinking = it }
                Spacer(Modifier.height(8.dp))

                ToggleRow(
                    "执行审核（审核者）",
                    "每个动作由审核者 AI 复核是否有页面依据，防止点到不存在的控件",
                    st.enableReview,
                ) { st.enableReview = it }
                Spacer(Modifier.height(8.dp))

                ToggleRow(
                    "链路聚合（增强）",
                    "开启后思考模型参与规划/重规划等复杂任务",
                    st.enableChain,
                ) { st.enableChain = it }
                Spacer(Modifier.height(8.dp))

                val chainItems = remember { mutableStateListOf(*st.chainOrder.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("main", "vision", "reason")) }
                ReorderableColumn(items = chainItems) { key, dragHandle ->
                    when (key) {
                        "main" -> ModelCard(
                            dragHandle = dragHandle,
                            title = "主模型",
                            subtitle = "每步执行决策",
                            showToggle = false, enabled = true, onEnabled = {},
                            model = st.model, onModel = { st.model = it },
                            baseUrl = st.baseUrl, onBaseUrl = { st.baseUrl = it },
                            apiKey = st.apiKey, onApiKey = { st.apiKey = it },
                        )
                        "vision" -> ModelCard(
                            dragHandle = dragHandle,
                            title = "视觉模型",
                            subtitle = "截图描述 + 定位点击坐标",
                            showToggle = true, enabled = st.visionEnabled, onEnabled = { st.visionEnabled = it },
                            model = st.visionModel, onModel = { st.visionModel = it },
                            baseUrl = st.visionBaseUrl, onBaseUrl = { st.visionBaseUrl = it },
                            apiKey = st.visionApiKey, onApiKey = { st.visionApiKey = it },
                        )
                        "reason" -> if (st.enableChain) ModelCard(
                            dragHandle = dragHandle,
                            title = "思考模型",
                            subtitle = "规划 / 重规划（深度思考）",
                            showToggle = true, enabled = true, onEnabled = {},
                            model = st.reasonModel, onModel = { st.reasonModel = it },
                            baseUrl = st.reasonBaseUrl, onBaseUrl = { st.reasonBaseUrl = it },
                            apiKey = st.reasonApiKey, onApiKey = { st.reasonApiKey = it },
                        )
                    }
                }
                LaunchedEffect(chainItems.size, chainItems.toList()) {
                    st.chainOrder = chainItems.toList()
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 测试所有 API ----
        var testingAll by remember { mutableStateOf(false) }
        val apiResults = remember { mutableStateListOf<ApiTestItemResult>() }
        OutlinedButton(
            onClick = {
                buzz()
                apiResults.clear()
                testingAll = true
                scope.launch {
                    buildApiTestTargets(st).forEach { t ->
                        val r = try {
                            vm.testConnection(t.baseUrl, t.apiKey, t.model)
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                        apiResults.add(
                            ApiTestItemResult(
                                label = t.label,
                                ok = r.isSuccess,
                                detail = if (r.isSuccess) "连接正常" else (r.exceptionOrNull()?.message ?: "未知错误"),
                            )
                        )
                    }
                    testingAll = false
                }
            },
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testingAll) "正在测试所有 API…" else "测试所有 API")
        }
        if (testingAll) {
            Text(
                "按 主模型 / 视觉模型 / 思考模型 逐项验证连接…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        apiResults.forEach { item ->
            // 上下排列：错误详情含完整返回正文，需占满整行换行展示，避免被单行布局挤出屏幕
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (item.ok) "✓ " else "✗ ") + item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 保存按钮
        var testing by remember { mutableStateOf(false) }
        var testError by remember { mutableStateOf<String?>(null) }
        Button(
            onClick = {
                buzz()
                testError = null
                scope.launch {
                    testing = true
                    try {
                        val r = vm.testConnection(st.baseUrl, st.apiKey, st.model)
                        testing = false
                        if (r.isSuccess) {
                            vm.saveSettings(st.toSettings())
                        } else {
                            testError = "模型连接失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
                        }
                    } catch (e: Exception) {
                        testing = false
                        testError = "模型连接失败：${e.message}"
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "正在测试连接…" else "保存 AI 配置")
        }
        testError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}