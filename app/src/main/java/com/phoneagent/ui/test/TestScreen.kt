package com.phoneagent.ui.test

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.agent.PromptLang
import com.phoneagent.test.TestGroup
import com.phoneagent.test.TestPreset
import com.phoneagent.test.TestResult
import com.phoneagent.test.TestStatus
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import androidx.compose.material.icons.rounded.SendToMobile

/**
 * AI 测试工作台：集成标准预设测试 + 真实场景测试，支持中英文提示词切换。
 */
@Composable
fun TestScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val config by vm.testConfig.collectAsState()
    val running by vm.testRunning.collectAsState()
    val summary by vm.testSummary.collectAsState()
    val streamText by vm.testStreamText.collectAsState()
    val testLang by vm.testLanguage.collectAsState()
    val testCategory by vm.testCategory.collectAsState()

    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var showConfig by remember { mutableStateOf(false) }
    var showPromptPreview by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        // ===== 标题 + 语言切换 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("AI 测试工作台", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            // 语言切换
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(180.dp)) {
                SegmentedButton(
                    selected = testLang == PromptLang.CN,
                    onClick = { vm.setTestLanguage(PromptLang.CN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !running,
                ) { Text("中文", style = MaterialTheme.typography.labelMedium) }
                SegmentedButton(
                    selected = testLang == PromptLang.EN,
                    onClick = { vm.setTestLanguage(PromptLang.EN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !running,
                ) { Text("English", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "当前：${vm.getLanguageLabel(testLang)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ===== 分类筛选 + 操作按钮 =====
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // 分类筛选
            val categories = listOf(null to "全部", "standard" to "标准测试", "real" to "真实场景")
            for ((cat, label) in categories) {
                val selected = testCategory == cat
                if (selected) {
                    FilledTonalButton(
                        onClick = { vm.setTestCategory(cat) },
                        enabled = !running,
                        modifier = Modifier.height(32.dp),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                } else {
                    OutlinedButton(
                        onClick = { vm.setTestCategory(cat) },
                        enabled = !running,
                        modifier = Modifier.height(32.dp),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Spacer(Modifier.weight(1f))
            // 提示词预览
            FilledTonalButton(
                onClick = { showPromptPreview = !showPromptPreview },
                modifier = Modifier.height(32.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("提示词", style = MaterialTheme.typography.labelSmall)
            }
            // 配置
            FilledTonalButton(
                onClick = { showConfig = !showConfig },
                modifier = Modifier.height(32.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Icon(Icons.Rounded.Report, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("配置", style = MaterialTheme.typography.labelSmall)
            }
        }

        // ===== 提示词预览 =====
        if (showPromptPreview) {
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前系统提示词（${vm.getLanguageLabel(testLang)}）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("仅展示开头", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when (testLang) {
                            PromptLang.CN -> "你是 Phantom，一个 Android 手机操控 Agent。\n\n# 铁律（违反任何一条 = 任务失败）\n1. 回复只能是纯 JSON。首字符 = {，末字符 = }。\n2. 禁止输出 ```json、``` 或任何 Markdown 标记。\n..."
                            PromptLang.EN -> "You are Phantom, an Android device automation agent.\n\n# Iron Rules (violation = task failure)\n1. Response = pure JSON only. First char = {, last char = }.\n2. NEVER output ```json, ```, or any Markdown markers.\n..."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp).heightIn(max = 140.dp),
                    )
                }
            }
        }

        // ===== 模型配置 =====
        if (showConfig) {
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL") }, singleLine = true, enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("Model") }, singleLine = true, enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API Key") }, singleLine = true, enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.updateTestConfig(baseUrl, model, apiKey) },
                        enabled = !running && baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank(),
                    ) { Text("保存配置") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ===== 预设方案列表 =====
        Text("测试方案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "选择方案后自动用 ${vm.getLanguageLabel(testLang)} 运行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // 筛选预设
        val presets = com.phoneagent.test.TestPresets.all.filter { preset ->
            when (testCategory) {
                "standard" -> preset.group != TestGroup.REAL
                "real" -> preset.group == TestGroup.REAL
                else -> true
            }
        }

        if (presets.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text("无匹配的测试方案", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            presets.forEach { preset ->
                PresetCard(
                    preset = preset,
                    running = running,
                    lang = testLang,
                    enabled = !running,
                    onRunTest = { vm.runTest(preset) },
                    onUseAsTask = { vm.startPlanning(preset.cases.first().name) },
                )
            }
        }

        // ===== 实时流式输出 =====
        if (running && streamText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("实时流式输出")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Text("模型正在输出…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        streamText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = 200.dp),
                    )
                }
            }
        }

        // ===== 测试结果 =====
        if (summary.status != TestStatus.IDLE) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("测试结果")
            ResultSummary(summary, vm, testLang)
            summary.results.forEach { result ->
                TestResultCard(
                    result = result,
                    onUseAsTask = { vm.startPlanning(result.case.name) },
                )
            }

            // 重置按钮
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.resetTest() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("清除结果")
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PresetCard(preset: TestPreset, running: Boolean, lang: PromptLang, enabled: Boolean, onRunTest: () -> Unit, onUseAsTask: () -> Unit) {
    val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()
    val commitHaptic = com.phoneagent.ui.components.rememberHapticCommit()
    val groupColor = when (preset.group) {
        TestGroup.REAL -> Color(0xFF7C4DFF)
        TestGroup.FORMAT -> Color(0xFF448AFF)
        TestGroup.TARGETING -> Color(0xFF00BCD4)
        TestGroup.DECISION -> Color(0xFFFF6D00)
        TestGroup.MERGE -> Color(0xFFE91E63)
        TestGroup.REGRESSION -> Color(0xFF2E9E6B)
    }
    PressableScale(
        enabled = enabled,
        onPress = { if (!running) pressHaptic() },
        onClick = { if (!running) onRunTest() },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 分组色标
                        Box(
                            Modifier
                                .size(8.dp)
                                .padding(0.dp),
                        ) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                drawCircle(groupColor)
                            }
                        }
                        Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${preset.cases.size} 例", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 执行任务按钮（仅真实场景 / 回归基准）
                        if (preset.group == TestGroup.REAL || preset.group == TestGroup.REGRESSION) {
                            FilledTonalButton(
                                onClick = { commitHaptic(); onUseAsTask() },
                                modifier = Modifier.height(36.dp),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                            ) {
                                Icon(Icons.Rounded.SendToMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("任务", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        // 运行测试按钮
                        FilledTonalButton(
                            onClick = { commitHaptic(); onRunTest() },
                            modifier = Modifier.height(36.dp),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("测试", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSummary(summary: com.phoneagent.test.TestRunSummary, vm: MainViewModel, lang: PromptLang) {
    val done = summary.status == TestStatus.DONE
    val err = summary.status == TestStatus.ERROR
    val allPassed = done && summary.passed == summary.total
    val bgColor = animateColorAsState(
        targetValue = if (err) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else if (allPassed) Color(0xFF2E9E6B).copy(alpha = 0.12f)
        else if (done) Color(0xFFE8A33D).copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = DurationFast, easing = EaseOut),
        label = "bg",
    )
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor.value),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("方案：${summary.presetName}", style = MaterialTheme.typography.titleMedium)
                // 语言标签
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(vm.getLanguageLabel(lang), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (err) {
                Text("运行出错：${summary.error}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${summary.passed} / ${summary.total}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (allPassed) Color(0xFF2E9E6B) else Color(0xFFE8A33D),
                    )
                    Text(
                        if (allPassed) "全部通过 ✓" else "部分未通过",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (allPassed) Color(0xFF2E9E6B) else Color(0xFFE8A33D),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TestResultCard(result: TestResult, onUseAsTask: () -> Unit) {
    val passedColor = Color(0xFF2E9E6B)
    val failedColor = MaterialTheme.colorScheme.error
    val borderColor = animateColorAsState(
        targetValue = if (result.passed) passedColor.copy(alpha = 0.25f) else failedColor.copy(alpha = 0.25f),
        animationSpec = tween(durationMillis = DurationFast, easing = EaseOut),
        label = "border",
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.passed) passedColor.copy(alpha = 0.08f) else failedColor.copy(alpha = 0.08f),
        ),
        border = BorderStroke(1.dp, borderColor.value),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (result.passed) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                    contentDescription = null,
                    tint = if (result.passed) passedColor else failedColor,
                    modifier = Modifier.size(20.dp),
                )
                Text("${result.case.id} · ${result.case.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 执行任务按钮
                FilledTonalButton(
                    onClick = onUseAsTask,
                    modifier = Modifier.height(28.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Icon(Icons.Rounded.SendToMobile, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("执行", style = MaterialTheme.typography.labelSmall)
                }
                Text("${result.latencyMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.case.checkHint.isNotBlank()) {
                Text(
                    "校验：${result.case.checkHint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (result.actionType != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "动作：${result.actionType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "目标：${result.rawOutput.substringBefore("}").take(60)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (result.errors.isNotEmpty()) {
                result.errors.forEach { e ->
                    Text("✗ $e", style = MaterialTheme.typography.bodySmall, color = failedColor, modifier = Modifier.padding(top = 2.dp))
                }
            } else {
                Text("✓ 通过", style = MaterialTheme.typography.bodySmall, color = passedColor, modifier = Modifier.padding(top = 2.dp))
            }
            if (result.rawOutput.isNotBlank()) {
                Text(
                    result.rawOutput.take(300),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .heightIn(max = 120.dp),
                )
            }
        }
    }
}