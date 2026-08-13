package com.phoneagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settingsFlow.collectAsState()

    var baseUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var hasVision by remember { mutableStateOf(settings.hasVision) }
    var maxSteps by remember { mutableIntStateOf(settings.maxSteps) }
    var attachScreenshot by remember { mutableStateOf(settings.attachScreenshot) }
    var promptLanguage by remember { mutableStateOf(settings.promptLanguage) }
    var visionBaseUrl by remember { mutableStateOf(settings.visionBaseUrl) }
    var visionModel by remember { mutableStateOf(settings.visionModel) }
    var visionApiKey by remember { mutableStateOf(settings.visionApiKey) }
    var reasonBaseUrl by remember { mutableStateOf(settings.reasonBaseUrl) }
    var reasonModel by remember { mutableStateOf(settings.reasonModel) }
    var reasonApiKey by remember { mutableStateOf(settings.reasonApiKey) }
    var visionEnabled by remember { mutableStateOf(settings.visionEnabled) }
    var enableChain by remember { mutableStateOf(settings.enableChain) }
    var chainOrder by remember { mutableStateOf(settings.chainOrder) }

    // 设置持久化后同步到本地表单
    LaunchedEffect(settings) {
        baseUrl = settings.apiBaseUrl
        apiKey = settings.apiKey
        model = settings.model
        hasVision = settings.hasVision
        maxSteps = settings.maxSteps
        attachScreenshot = settings.attachScreenshot
        promptLanguage = settings.promptLanguage
        visionBaseUrl = settings.visionBaseUrl
        visionModel = settings.visionModel
        visionApiKey = settings.visionApiKey
        reasonBaseUrl = settings.reasonBaseUrl
        reasonModel = settings.reasonModel
        reasonApiKey = settings.reasonApiKey
        visionEnabled = settings.visionEnabled
        enableChain = settings.enableChain
        chainOrder = settings.chainOrder
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text(
            "配置 AI 服务，支持任意 OpenAI 兼容 API",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("AI 服务")
        LabeledField("API 地址") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                singleLine = true,
                supportingText = { Text("默认智谱开放平台，可替换为任意兼容端点") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        LabeledField("API Key") {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                singleLine = true,
                supportingText = { Text("以 Bearer 形式发送，仅保存在本机") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        LabeledField("模型") {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                singleLine = true,
                supportingText = { Text("默认 glm-4.7-flash（免费）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        ToggleRow("启用视觉理解", "结合截图理解图片、图表等元素树无法表达的内容", hasVision) { hasVision = it }
        Spacer(Modifier.height(12.dp))
        ToggleRow("附送屏幕截图", "每轮观察时附带当前屏幕截图辅助决策", attachScreenshot) { attachScreenshot = it }

        SectionHeader("模型链路")
        Text(
            "以图形化卡片管理模型链路，长按拖动重新排序。默认单主模型 + 视觉模型；链路聚合为可选增强，默认关闭。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            "链路聚合（增强）",
            "开启后思考模型参与规划/重规划等复杂任务",
            enableChain,
        ) { enableChain = it }
        Spacer(Modifier.height(12.dp))

        // 可拖动模型链路：main / vision / reason
        val chainItems = remember { mutableStateListOf<String>(*chainOrder.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("main", "vision", "reason")) }
        ReorderableColumn(items = chainItems) { key, dragHandle ->
            when (key) {
                "main" -> ModelCard(
                    dragHandle = dragHandle,
                    title = "主模型",
                    subtitle = "每步执行决策",
                    showToggle = false, enabled = true, onEnabled = {},
                    model = model, onModel = { model = it },
                    baseUrl = baseUrl, onBaseUrl = { baseUrl = it },
                    apiKey = apiKey, onApiKey = { apiKey = it },
                )
                "vision" -> ModelCard(
                    dragHandle = dragHandle,
                    title = "视觉模型",
                    subtitle = "截图描述 + 定位点击坐标",
                    showToggle = true, enabled = visionEnabled, onEnabled = { visionEnabled = it },
                    model = visionModel, onModel = { visionModel = it },
                    baseUrl = visionBaseUrl, onBaseUrl = { visionBaseUrl = it },
                    apiKey = visionApiKey, onApiKey = { visionApiKey = it },
                )
                "reason" -> if (enableChain) ModelCard(
                    dragHandle = dragHandle,
                    title = "思考模型",
                    subtitle = "规划 / 重规划（深度思考）",
                    showToggle = true, enabled = true, onEnabled = {},
                    model = reasonModel, onModel = { reasonModel = it },
                    baseUrl = reasonBaseUrl, onBaseUrl = { reasonBaseUrl = it },
                    apiKey = reasonApiKey, onApiKey = { reasonApiKey = it },
                )
            }
        }
        // 保持 chainOrder 与拖动结果同步
        LaunchedEffect(chainItems.size, chainItems.toList()) {
            chainOrder = chainItems.toList()
        }

        SectionHeader("运行参数")
        // 最大步数：关闭时 maxSteps=0 表示不设限
        ToggleRow("限制最大步数", if (maxSteps > 0) "运行 $maxSteps 步后自动停止" else "不设限，直到任务完成或手动停止", maxSteps > 0) { enabled ->
            // 开启时给一个合理的默认值，否则 maxSteps 保持 0 会一直显示"不设限"
            maxSteps = if (enabled) (if (maxSteps > 0) maxSteps else 20) else 0
        }
        if (maxSteps > 0) {
            LabeledField("最大步数  $maxSteps") {
                Slider(value = maxSteps.toFloat(), onValueChange = { maxSteps = it.toInt() }, valueRange = 5f..60f, steps = 10)
            }
        }

        SectionHeader("提示词语言")
        Text(
            "选择发送给 AI 的提示词语言（系统/决策/验证等 8 组 Prompt）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("CN" to "中文", "EN" to "English").forEach { (code, label) ->
                val selected = promptLanguage == code
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { promptLanguage = code },
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        // 保存前先测试主模型连接，通过才保存；失败则不保存并提示原因
        var testing by remember { mutableStateOf(false) }
        var testError by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val buzz = com.phoneagent.ui.components.rememberHapticClick()
        Button(
            onClick = {
                buzz()
                testError = null
                scope.launch {
                    testing = true
                    try {
                        val r = vm.testConnection(baseUrl, apiKey, model)
                        testing = false
                        if (r.isSuccess) {
                            vm.saveSettings(
                                AppSettings.Settings(
                                    apiBaseUrl = baseUrl,
                                    apiKey = apiKey,
                                    model = model,
                                    hasVision = hasVision,
                                    maxSteps = maxSteps,
                                    attachScreenshot = attachScreenshot,
                                    promptLanguage = promptLanguage,
                                    visionBaseUrl = visionBaseUrl,
                                    visionModel = visionModel,
                                    visionApiKey = visionApiKey,
                                    reasonBaseUrl = reasonBaseUrl,
                                    reasonModel = reasonModel,
                                    reasonApiKey = reasonApiKey,
                                    visionEnabled = visionEnabled,
                                    enableChain = enableChain,
                                    chainOrder = chainOrder,
                                ),
                            )
                        } else {
                            testError = "模型连接失败，未保存：${r.exceptionOrNull()?.message ?: "未知错误"}"
                        }
                    } catch (e: Exception) {
                        testing = false
                        testError = "模型连接失败，未保存：${e.message}"
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "正在测试连接…" else "保存设置")
        }
        if (testing) {
            Text(
                "正在用当前配置调用模型验证连通性…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
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

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/**
 * 可拖动排序的模型链路列表：长按拖动把手即可上下交换顺序。
 */
@Composable
private fun <T> ReorderableColumn(
    items: SnapshotStateList<T>,
    itemThreshold: androidx.compose.ui.unit.Dp = 56.dp,
    itemContent: @Composable (item: T, dragHandle: @Composable () -> Unit) -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { itemThreshold.toPx() }
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            var dragAcc by remember(item) { mutableStateOf(0f) }
            val handle: @Composable () -> Unit = {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .pointerInput(item) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragAcc = 0f },
                                onDragEnd = { dragAcc = 0f },
                                onDragCancel = { dragAcc = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragAcc += amount.y
                                    val cur = items.indexOf(item)
                                    if (dragAcc > thresholdPx && cur < items.lastIndex) {
                                        items.removeAt(cur)
                                        items.add(cur + 1, item)
                                        dragAcc = 0f
                                    } else if (dragAcc < -thresholdPx && cur > 0) {
                                        items.removeAt(cur)
                                        items.add(cur - 1, item)
                                        dragAcc = 0f
                                    }
                                },
                            )
                        },
                )
            }
            itemContent(item, handle)
        }
    }
}

/**
 * 单个模型配置卡片：图形化呈现模型角色与字段，支持启用开关与拖动排序。
 */
@Composable
private fun ModelCard(
    dragHandle: @Composable () -> Unit,
    title: String,
    subtitle: String,
    showToggle: Boolean,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle()
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showToggle && enabled) Text("已启用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (showToggle) {
                ToggleRow(if (enabled) "启用 $title" else "禁用 $title", "", enabled) { onEnabled(it) }
                Spacer(Modifier.height(8.dp))
            }
            LabeledField("模型") {
                OutlinedTextField(value = model, onValueChange = onModel, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            LabeledField("API 地址") {
                OutlinedTextField(value = baseUrl, onValueChange = onBaseUrl, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            LabeledField("API Key") {
                OutlinedTextField(value = apiKey, onValueChange = onApiKey, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}