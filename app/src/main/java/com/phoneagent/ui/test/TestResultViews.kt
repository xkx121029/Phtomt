package com.phoneagent.ui.test

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.phoneagent.ui.theme.AppRadii
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
import com.phoneagent.engine.PromptLang
import com.phoneagent.feature.test.TestGroup
import com.phoneagent.feature.test.TestPreset
import com.phoneagent.feature.test.TestResult
import com.phoneagent.feature.test.TestStatus
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.TestDecision
import com.phoneagent.ui.theme.TestFormat
import com.phoneagent.ui.theme.TestMerge
import com.phoneagent.ui.theme.TestReal
import com.phoneagent.ui.theme.TestRegression
import com.phoneagent.ui.theme.TestTargeting
import com.phoneagent.ui.theme.Warning
import androidx.compose.material.icons.rounded.SendToMobile

@Composable
internal fun ResultSummary(summary: com.phoneagent.feature.test.TestRunSummary, vm: MainViewModel, lang: PromptLang) {
    val done = summary.status == TestStatus.DONE
    val err = summary.status == TestStatus.ERROR
    val allPassed = done && summary.passed == summary.total
    val bgColor = animateColorAsState(
        targetValue = if (err) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else if (allPassed) Success.copy(alpha = 0.12f)
        else if (done) Warning.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = DurationFast, easing = EaseOut),
        label = "bg",
    )
    Card(
        shape = RoundedCornerShape(AppRadii.Item),
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
                        color = if (allPassed) Success else Warning,
                    )
                    Text(
                        if (allPassed) "全部通过 ✓" else "部分未通过",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (allPassed) Success else Warning,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TestResultCard(result: TestResult, onUseAsTask: () -> Unit, modifier: Modifier = Modifier) {
    val passedColor = Success
    val failedColor = MaterialTheme.colorScheme.error
    val borderColor = animateColorAsState(
        targetValue = if (result.passed) passedColor.copy(alpha = 0.25f) else failedColor.copy(alpha = 0.25f),
        animationSpec = tween(durationMillis = DurationFast, easing = EaseOut),
        label = "border",
    )
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(AppRadii.Tile),
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