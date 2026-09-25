package com.phoneagent.ui.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.ai.Endpoint
import com.phoneagent.core.ai.GlmDefaults
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.core.ai.ProviderPreset
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.overlay.FloatingUi
import com.phoneagent.ui.components.InlineOverlay
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.icons.AppIcons

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

/**
 * 套用服务商预设：填好 API 地址与主模型，并在该服务商确实提供对应模型时同步视觉/思考模型。
 * 未提供视觉模型的服务商（如 DeepSeek）不动原有视觉配置，保留用户既有的云端视觉或本地 OCR 选择。
 * 同时把该服务商作为一个端点、把三个模型写进模型库 —— 预设本身就是"组合配置"，不该只填三处输入框。
 */
internal fun applyProviderPreset(st: SettingsState, preset: ProviderPreset) {
    st.baseUrl = preset.baseUrl
    st.model = preset.model
    if (preset.visionModel.isNotBlank()) {
        st.visionBaseUrl = preset.baseUrl
        st.visionModel = preset.visionModel
    }
    if (preset.reasonModel.isNotBlank()) {
        st.reasonBaseUrl = preset.baseUrl
        st.reasonModel = preset.reasonModel
    }
    ensureEndpoint(st, preset.baseUrl, st.apiKey)
    listOf(preset.model, preset.visionModel, preset.reasonModel).forEach {
        ensureCatalogModel(st, preset.baseUrl, it)
    }
}

/** 端点按归一化 URL 去重入库（地址为空则不建），返回端点 id 供调用方继续用 */
internal fun ensureEndpoint(st: SettingsState, baseUrl: String, apiKey: String): String? {
    val id = ModelCatalogCodec.endpointId(baseUrl)
    if (id.isBlank()) return null
    if (st.endpoints.none { it.id == id }) {
        st.endpoints.add(Endpoint(id = id, baseUrl = baseUrl.trim(), apiKey = apiKey.trim()))
    }
    return id
}

/** 模型条目按「端点 + 模型名」去重入库；能力徽章留给真实探测填 */
internal fun ensureCatalogModel(st: SettingsState, baseUrl: String, name: String) {
    val id = ModelCatalogCodec.endpointId(baseUrl)
    val trimmed = name.trim()
    if (id.isBlank() || trimmed.isBlank()) return
    if (st.catalog.none { it.endpointId == id && it.name == trimmed }) {
        st.catalog.add(CatalogModel(endpointId = id, name = trimmed))
    }
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
            Icon(AppIcons.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
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
    Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = 14.dp)) {
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
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = AppSpacing.Lg, vertical = 14.dp),
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
            AppIcons.ChevronRight,
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

/** 开关行；副标题为空时不占位 */
@Composable
internal fun ToggleRow(title: String, subtitle: String? = null, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    imageVector = if (isExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
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
                    imageVector = AppIcons.Reorder,
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

/**
 * 预览里的示意文案：真实跑马灯显示的也是这种单行 AI 动作简述。
 *
 * 刻意写得接近真实长度（任务里的简述多在十几字），而不是一句四五个字的短词：
 * 胶囊宽度随文字走，渐变色是横跨整块胶囊铺开的——文案太短，几个颜色就会被压成一团，
 * 预览看上去和实际任务里"铺满半屏"的渐变完全对不上。
 */
private const val marqueePreviewText = "正在打开设置页面并连接调试端口"

/**
 * 跟随状态时的阶段色清单（顺序即任务推进顺序），色值一律取自 FloatingUi，不在这里另抄一套。
 *
 * 只列真正会被用到的这五个阶段色：面板同时只有一种颜色，多列一个兜底色只会让人以为跑马灯是多彩的。
 */
private val marqueePhases = listOf(
    "OBSERVING" to "观察",
    "THINKING" to "思考",
    "ACTING" to "执行",
    "DONE" to "完成",
    "ERROR" to "出错",
)

/**
 * 跑马灯的文字样式：必须与悬浮窗 `MarqueeView` 里的 `TextPaint` 一字不差
 * （14sp、常规字重、零字距、不设行高）。
 *
 * 这里原先用的是 `typography.labelLarge`——它是 14sp/Medium/0.1sp 字距、且带 20sp 行高，
 * 同一个「内边距」值下，预览的胶囊比实际厚 4dp 左右、字也更粗，用户当场就看出了两处不同。
 */
private val marqueePreviewTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    letterSpacing = 0.sp,
    // 不设 lineHeight：留空才会按字体 ascent/descent 量高，与 MarqueeView 的算法同源
)

/**
 * 跑马灯胶囊的等比预览：半高圆角、左右 16dp / 上下 [padV] 内边距、距底边 12dp 留白，
 * 与悬浮窗里的 `MarqueeView` 对齐——预览一旦与实际各写一套，用户看到的和拿到的就不是同一个东西。
 */
@Composable
private fun MarqueeCapsuleMock(background: Brush, padV: Int) {
    // 屏幕底边示意：胶囊浮在底边之上，一眼看出「隆起」的观感与厚度
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(top = 20.dp, bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .padding(horizontal = 16.dp, vertical = padV.dp),
        ) {
            Text(
                marqueePreviewText,
                style = marqueePreviewTextStyle,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * 「跟随状态变色」的预览：胶囊底色示意当前阶段（以最常见的执行中为例），
 * 下方列出五个阶段各自的底色，让用户一眼知道跑马灯会怎么变色。
 */
@Composable
internal fun MarqueePhasePreview(padV: Int) {
    Column {
        MarqueeCapsuleMock(SolidColor(Color(FloatingUi.phaseColor("ACTING"))), padV)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            marqueePhases.forEach { (id, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(FloatingUi.phaseColor(id))),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "跑马灯底色随阶段切换，无需自定义配色",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 跑马灯渐变颜色选择器（自定义配色模式）。
 *
 * 顶部是照实际渲染的预览：底色取用户配色原色、胶囊圆角、内边距、贴底留白都与 `MarqueeView` 对齐。
 */
@Composable
internal fun MarqueeColorPicker(selected: List<Long>, padV: Int, onSelect: (List<Long>) -> Unit) {
    val buzz = rememberHapticClick()
    val presets = listOf(
        0xFF4FA3FF.toInt(), 0xFF9B5CFF.toInt(), 0xFFFF6B9D.toInt(), 0xFF00C2A8.toInt(),
        0xFFFF8A3D.toInt(), 0xFF6BD968.toInt(), 0xFFFFD600.toInt(), 0xFFE74C5C.toInt(),
    )
    Column {
        // 与 MarqueeView.setColors 同一条规则：不足 2 色就是单色实心（不铺渐变），2 色以上才走横向渐变
        val colors = (if (selected.isNotEmpty()) selected else marqueeDefaultColors).map { Color(it.toInt()) }
        val brush = if (colors.size < 2) {
            SolidColor(colors.first())
        } else {
            // 与 MarqueeView.buildBgGradient 同构：首色补到末尾，渐变首尾同色接缝处才不断开
            Brush.horizontalGradient(colors + colors.first())
        }
        MarqueeCapsuleMock(brush, padV)
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                selected.size >= 2 -> "已选 ${selected.size} 色（按选择顺序渐变，最多 6 色）"
                selected.size == 1 -> "已选 1 色（单色实心底色）"
                else -> "当前为默认渐变，可点击下方颜色自定义"
            },
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

// ========== 模型库与职责分配 ==========

/** 端点显示名：去掉协议头，够短且能区分同名服务（如 open.bigmodel.cn/api/paas/v4） */
internal fun endpointHost(baseUrl: String): String =
    baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

/** 能力徽章：识图 / 纯文字 / 工具。null = 未测出，与"不支持"分开显示 */
@Composable
internal fun AbilityBadges(vision: Boolean?, tools: Boolean?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (vision) {
            true -> CapChip("识图 ✓", true)
            false -> CapChip("纯文字", false)
            null -> CapChip("识图 ?", null)
        }
        when (tools) {
            true -> CapChip("工具 ✓", true)
            false -> CapChip("无工具", false)
            null -> CapChip("工具 ?", null)
        }
    }
}

@Composable
private fun CapChip(text: String, state: Boolean?) {
    val bg = when (state) {
        true -> MaterialTheme.colorScheme.primaryContainer
        false -> MaterialTheme.colorScheme.surfaceVariant
        null -> Color.Transparent
    }
    val fg = when (state) {
        true -> MaterialTheme.colorScheme.onPrimaryContainer
        false -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    Surface(
        shape = RoundedCornerShape(AppRadii.Chip),
        color = bg,
        border = if (state == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 端点卡片：一行一个 API 端点。
 * 「获取模型」拉 /models 写入模型库；删除端点会连带清掉它的模型条目（在页面侧级联）。
 */
@Composable
internal fun EndpointCard(
    baseUrl: String,
    apiKey: String,
    modelCount: Int,
    fetching: Boolean,
    hint: String?,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onFetch: () -> Unit,
    onDelete: () -> Unit,
) {
    val buzz = rememberHapticClick()
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            LabeledField("API 地址") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrl,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            LabeledField("API Key") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { buzz(); onFetch() },
                    enabled = !fetching && apiKey.isNotBlank(),
                ) { Text(if (fetching) "获取中…" else "获取模型") }
                Text(
                    "已入库 $modelCount 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(
                    onClick = {
                        buzz()
                        if (confirmDelete) onDelete() else confirmDelete = true
                    },
                ) {
                    Text(
                        if (confirmDelete) "确认删除" else "删除",
                        color = if (confirmDelete) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            hint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 职责行：只表达"这个职责用哪个模型"，地址与 Key 都由所属端点提供（不再重复填）。
 * 点击整行打开模型选择弹层；视觉/思考职责带启用开关。
 */
@Composable
internal fun RoleRow(
    dragHandle: @Composable () -> Unit,
    title: String,
    subtitle: String,
    model: String,
    endpointLabel: String,
    showToggle: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val buzz = rememberHapticClick()
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dragHandle()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { buzz(); onClick() }
                    .padding(vertical = 14.dp, horizontal = 4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    model.ifBlank { "未指定模型" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (model.isBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (endpointLabel.isBlank()) subtitle else "$subtitle · $endpointLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showToggle) {
                Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.padding(end = 12.dp))
            } else {
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = "更换模型",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(20.dp),
                )
            }
        }
    }
}

/**
 * 模型选择弹层：列出模型库里已探测的模型（带能力徽章），也允许直接手填模型名。
 * 内嵌 Dialog（非系统弹窗），样式与技能编辑弹层一致。
 */
@Composable
internal fun ModelPickerDialog(
    title: String,
    models: List<CatalogModel>,
    endpointLabelOf: (CatalogModel) -> String,
    current: String,
    onPickModel: (CatalogModel) -> Unit,
    onPickManual: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val buzz = rememberHapticClick()
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    // 页内浮层，不用系统 Dialog：样式与技能编辑浮层同一套，跟着页面走
    // 列表用 weight 撑开，所以面板要 fillHeight（高度不定时权重拿不到空间）
    InlineOverlay(onDismiss = onDismiss, fillHeight = true) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "选择模型库中的模型，或直接手填模型名（手填的能力徽章需要重新探测）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("筛选模型名") },
            modifier = Modifier.fillMaxWidth(),
        )
        val filtered = models.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            if (filtered.isEmpty()) {
                Text(
                    if (models.isEmpty()) "模型库为空：先在「端点」里点「获取模型」"
                    else "没有匹配的模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            filtered.forEach { m ->
                Surface(
                    shape = RoundedCornerShape(AppRadii.Item),
                    color = if (m.name == current) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { buzz(); onPickModel(m) }
                            .padding(AppSpacing.Md),
                    ) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(AppSpacing.Xs))
                        AbilityBadges(m.vision, m.tools)
                        Spacer(Modifier.height(AppSpacing.Xs))
                        Text(
                            endpointLabelOf(m),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                singleLine = true,
                placeholder = { Text("手填模型名") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { buzz(); if (manual.isNotBlank()) onPickManual(manual.trim()) },
                enabled = manual.isNotBlank(),
            ) { Text("使用") }
        }
        androidx.compose.material3.TextButton(
            onClick = { buzz(); onDismiss() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("关闭") }
    }
}