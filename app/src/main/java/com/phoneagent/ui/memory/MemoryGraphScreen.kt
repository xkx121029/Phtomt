package com.phoneagent.ui.memory

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderInnerPad
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.skeleton
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.MemoryAnomaly
import com.phoneagent.ui.theme.MemoryAnomalySoft
import com.phoneagent.ui.theme.MemoryProfile
import com.phoneagent.ui.theme.MemoryProfileSoft
import com.phoneagent.ui.theme.MemoryRoot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.phoneagent.ui.icons.AppIcons

/**
 * AI 记忆图谱：
 * 把积累的记忆（异常经验 + 用户画像）以图谱形式展示。
 * 中心为根节点，外围为分类节点，最外层为具体记忆条目，节点间用连线连接。
 */
@Composable
fun MemoryGraphScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val anomalies by vm.memoryAnomalies.collectAsState()
    val profiles by vm.memoryProfile.collectAsState()
    val taskMemories by vm.memoryTaskMemories.collectAsState()
    val loading by vm.memoryLoading.collectAsState()

    LaunchedEffect(Unit) { vm.refreshMemory() }

    GlassHeaderScaffold(
        modifier = modifier,
        header = {
            // 标题 + 操作
            AppTopBar(
                title = "记忆图谱",
                subtitle = "任务记忆、异常经验与用户画像",
                leadingIcon = AppIcons.Memory,
                trailingContent = {
                    PressableScale(onClick = { vm.refreshMemory() }) {
                        Icon(
                            AppIcons.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp).size(20.dp),
                        )
                    }
                },
                contentPadding = PaddingValues(horizontal = GlassHeaderInnerPad, vertical = 8.dp),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 顶部净空垫在滚动容器内部：图谱与明细滚动时会从玻璃页眉下穿过，
                // 页眉才有真实内容可模糊；垫在外面就只是把内容整体压低
                .padding(top = pad.calculateTopPadding()),
        ) {
            if (loading) {
                // 骨架屏加载：标题 + 图谱占位 + 统计占位
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .fillMaxWidth(0.5f)
                            .skeleton(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(AppRadii.Card))
                            .skeleton(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(AppRadii.Item))
                                .skeleton(),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(AppRadii.Item))
                                .skeleton(),
                        )
                    }
                }
                return@Column
            }

            if (anomalies.isEmpty() && profiles.isEmpty() && taskMemories.isEmpty()) {
                EmptyMemoryCard(onRefresh = { vm.refreshMemory() })
                return@Column
            }

            // 图谱画布
            val graphData = remember(anomalies, profiles) { buildGraphNodes(anomalies, profiles) }
            GraphCanvas(nodes = graphData)

            Spacer(Modifier.height(16.dp))

            // 统计概览
            StatsRow(taskMemories.size, anomalies.size, profiles.size)

            Spacer(Modifier.height(16.dp))

            // 明细列表：任务记忆最前（与当前任务最相关），随后是异常经验、用户画像
            TaskMemoryList(
                items = taskMemories,
                onDelete = { vm.deleteTaskMemory(it) },
                onClear = { vm.clearTaskMemories() },
            )
            AnomalyList(anomalies, onClear = { vm.clearAnomalyMemory() })
            ProfileList(profiles, onClear = { vm.clearProfileMemory() })

            Spacer(Modifier.height(28.dp))
        }
    }
}