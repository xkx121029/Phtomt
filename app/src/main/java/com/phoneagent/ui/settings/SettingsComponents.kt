package com.phoneagent.ui.settings.SettingsComponents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.core.ai.GlmDefaults
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii

// ========== 共享数据模型 ==========

internal data class ApiTestItemResult(val label: String, val ok: Boolean, val detail: String)

internal data class ApiTestTarget(val label: String, val baseUrl: String, val apiKey: String, val model: String)

internal fun buildApiTestTargets(st: SettingsState): List<ApiTestTarget> {
    val targets = mutableListOf<ApiTestTarget>()
    if (st.apiKey.isNotBlank()) {
        targets.add(
            ApiTestTarget(
                label = "主模型",
                baseUrl = st.baseUrl.trim().ifBlank { GlmDefaults.BASE_URL },
                apiKey = st.apiKey.trim(),
                model = st.model.trim().ifBlank { GlmDefaults.MODEL },
            )
        )
    }
    if (st.visionEnabled && st.visionModel.isNotBlank()) {
        targets.add(
            ApiTestTarget(
                label = "视觉模型",
                baseUrl = st.visionBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL },
                apiKey = st.visionApiKey.trim().ifBlank { st.apiKey },
                model = st.visionModel.trim(),
            )
        )
    }
    if (!st.mainThinking) {
        if (st.enableChain && st.reasonModel.isNotBlank()) {
            targets.add(
                ApiTestTarget(
                    label = "思考模型",
                    baseUrl = st.reasonBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL },
                    apiKey = st.reasonApiKey.trim().ifBlank { st.apiKey },
                    model = st.reasonModel.trim(),
                )
            )
        }
    }
    return targets
}

// ========== 共享 UI 组件 ==========

/** 二级页面顶部栏：返回按钮 + 标题 */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AppRadii.Inline))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { buzz(); onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 分组卡片：圆角容器 */
@Composable
internal fun GroupCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(AppRadii.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** 分组标题 */
@Composable
internal fun GroupHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分组内细分隔线 */
@Composable
internal fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

/** 设置条目 */
@Composable
internal fun SettingsEntry(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    summary: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(AppRadii.Tile))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 带标签的表单字段 */
@Composable
internal fun LabeledField(label: String, content: @Composable () -> Unit) {
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

/** 开关行 */
@Composable
internal fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
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

/** 可折叠卡片 */
@Composable
internal fun ExpandableCard(
    title: String,
    enabled: Boolean = true,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var internalExpanded by remember { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: internalExpanded

    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        val newState = !isExpanded
                        if (expanded == null) {
                            internalExpanded = newState
                        }
                        onExpandedChange?.invoke(newState)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            AnimatedVisibility(
                visible = isExpanded && enabled,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    content()
                }
            }
        }
    }
}

/** 可拖拽排序列表 */
@Composable
internal fun <T> ReorderableColumn(
    items: SnapshotStateList<T>,
    itemThreshold: Dp = 56.dp,
    itemContent: @Composable (item: T, dragHandle: @Composable () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { itemThreshold.toPx() }
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            var dragAcc by remember(item) { mutableStateOf(0f) }
            val handle: @Composable () -> Unit = {
                Icon(
                    imageVector = Icons.Filled.Reorder,
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

/** 模型配置卡片 */
@Composable
internal fun ModelCard(
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
        shape = RoundedCornerShape(AppRadii.Item),
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

/** 标定滑块 */
@Composable
internal fun CalibrationSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(70.dp),
        )
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$value dp",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(50.dp),
        )
    }
}

/** 跑马灯渐变默认颜色 */
internal val marqueeDefaultColors = listOf(0xFF4FA3FF.toLong(), 0xFF9B5CFF.toLong(), 0xFFFF6B9D.toLong())

/** 跑马灯渐变颜色选择器 */
@Composable
internal fun MarqueeColorPicker(selected: List<Long>, onSelect: (List<Long>) -> Unit) {
    val buzz = rememberHapticClick()
    val presets = listOf(
        0xFF4FA3FF.toInt(), 0xFF9B5CFF.toInt(), 0xFFFF6B9D.toInt(), 0xFF00C2A8.toInt(),
        0xFFFF8A3D.toInt(), 0xFF6BD968.toInt(), 0xFFFFD600.toInt(), 0xFFE74C5C.toInt(),
    )
    Column {
        val previewColors = selected.map { Color(it.toInt()) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(AppRadii.Tile))
                .background(
                    when {
                        previewColors.size >= 2 -> Brush.horizontalGradient(previewColors)
                        previewColors.size == 1 -> Brush.horizontalGradient(listOf(previewColors[0], previewColors[0]))
                        else -> Brush.horizontalGradient(listOf(Color(0xFFCFD4DA), Color(0xFFCFD4DA)))
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected.isEmpty()) {
                Text(
                    "点击下方颜色，按顺序组成渐变（至少 2 色）",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (selected.isNotEmpty()) "已选 ${selected.size} 色（按选择顺序渐变，最多 6 色）"
            else "当前为默认渐变，可点击下方颜色自定义",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        val idx = selected.indexOf(c.toLong())
                        val isSel = idx >= 0
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(AppRadii.Chip))
                                .background(Color(c))
                                .then(
                                    if (isSel) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(AppRadii.Chip))
                                    } else {
                                        Modifier.border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(AppRadii.Chip))
                                    }
                                )
                                .clickable {
                                    buzz()
                                    val list = selected.toMutableList()
                                    if (isSel) {
                                        list.removeAt(idx)
                                    } else {
                                        if (list.size >= 6) list.removeAt(0)
                                        list.add(c.toLong())
                                    }
                                    onSelect(list)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSel) {
                                Text(
                                    "${idx + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.size(44.dp)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(
            onClick = {
                buzz()
                onSelect(marqueeDefaultColors)
            },
        ) {
            Text("重置为默认渐变")
        }
    }
}