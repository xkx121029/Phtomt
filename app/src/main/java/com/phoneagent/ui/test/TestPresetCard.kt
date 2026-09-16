package com.phoneagent.ui.test.TestPresetCard

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
internal fun PresetCard(preset: TestPreset, running: Boolean, lang: PromptLang, enabled: Boolean, onRunTest: () -> Unit, onUseAsTask: () -> Unit) {
    val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()
    val commitHaptic = com.phoneagent.ui.components.rememberHapticCommit()
    val groupColor = when (preset.group) {
        TestGroup.REAL -> TestReal
        TestGroup.FORMAT -> TestFormat
        TestGroup.TARGETING -> TestTargeting
        TestGroup.DECISION -> TestDecision
        TestGroup.MERGE -> TestMerge
        TestGroup.REGRESSION -> TestRegression
    }
    PressableScale(
        enabled = enabled,
        onPress = { if (!running) pressHaptic() },
        onClick = { if (!running) onRunTest() },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            shape = RoundedCornerShape(AppRadii.Item),
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