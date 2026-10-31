package com.phoneagent.ui.coding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.coding.agent.CodingWorkspaces.Entry
import com.phoneagent.coding.agent.CodingWorkspaces.SessionMeta
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.text.TimeFormats
import com.phoneagent.ui.components.InlineOverlay
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.ApprovalModeSegment
import com.phoneagent.ui.settings.approvalModeHint
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 工作区面板：列出已登记的工作区，可切、可新建、可从设备目录添加。
 * 用 [InlineOverlay] 而不是系统 Dialog（项目铁律）。
 */
@Composable
internal fun WorkspacePanel(
    workspaces: List<Entry>,
    activeId: String,
    onSelect: (Entry) -> Unit,
    onCreate: (String) -> Unit,
    onPickExternal: () -> Unit,
    onDelete: (Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()

    InlineOverlay(onDismiss = onDismiss, fillHeight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "工作区",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    "每个工作区有独立目录、独立会话与独立记忆",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(AppIcons.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            contentPadding = PaddingValues(vertical = AppSpacing.Xs),
        ) {
            items(workspaces, key = { it.id }) { entry ->
                val isActive = entry.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(if (isActive) colors.surfaceRaised else Color.Transparent)
                        .clickable { buzz(); onSelect(entry) }
                        .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isActive) AppIcons.CheckCircle else AppIcons.FolderOpen,
                        contentDescription = null,
                        tint = if (isActive) colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (entry.id != "w_default") {
                        IconButton(onClick = { buzz(); onDelete(entry) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                AppIcons.Delete,
                                contentDescription = "移除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("新工作区名称", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    buzz()
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        onCreate(trimmed)
                        name = ""
                    }
                },
                contentPadding = PaddingValues(horizontal = AppSpacing.Md),
            ) { Text("新建") }
        }

        TextButton(onClick = { buzz(); onPickExternal() }, modifier = Modifier.fillMaxWidth()) {
            Icon(AppIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(AppSpacing.Xs))
            Text("从设备选择目录", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 模型与权限面板：编码页里直接换主模型、换审批档位，不必跳设置页。
 *
 * 审批档位会热更新到宿主里的 ApprovalPolicy（立即生效）；模型走宿主重启，
 * 运行中切换时面板明确告诉用户「本轮结束后生效」，不打断正在跑的这一轮。
 */
@Composable
internal fun ModelPermissionPanel(
    model: String,
    models: List<CatalogModel>,
    endpointLabelOf: (CatalogModel) -> String,
    approvalMode: String,
    busy: Boolean,
    onSelectApproval: (String) -> Unit,
    onPickModel: (CatalogModel) -> Unit,
    onPickManual: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val buzz = rememberHapticClick()
    var manual by remember { mutableStateOf("") }

    InlineOverlay(onDismiss = onDismiss, fillHeight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "模型与权限",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    if (busy) "运行中：切换模型会在本轮结束后生效，审批档位立即生效"
                    else "审批档位立即生效，模型切换会重启编码宿主",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(AppIcons.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
            }
        }

        Text("审批档位", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ApprovalModeSegment(current = approvalMode, onSelect = { buzz(); onSelectApproval(it) })
        Text(
            approvalModeHint(approvalMode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(AppSpacing.Xs))
        Text("模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
            contentPadding = PaddingValues(vertical = AppSpacing.Xs),
        ) {
            if (models.isEmpty()) {
                item {
                    Text(
                        "模型库为空：先在「设置 → 云端模型」里获取模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(models, key = { "${it.endpointId}/${it.name}" }) { m ->
                val selected = m.name == model
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(if (selected) AppTheme.colors.surfaceRaised else Color.Transparent)
                        .clickable { buzz(); onPickModel(m) }
                        .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) AppIcons.CheckCircle else AppIcons.SmartToy,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            endpointLabelOf(m),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                singleLine = true,
                placeholder = { Text("手填模型名", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    buzz()
                    val trimmed = manual.trim()
                    if (trimmed.isNotEmpty()) {
                        onPickManual(trimmed)
                        manual = ""
                    }
                },
                enabled = manual.isNotBlank(),
                contentPadding = PaddingValues(horizontal = AppSpacing.Md),
            ) { Text("使用") }
        }

        TextButton(onClick = { buzz(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
            Text("关闭", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 会话面板：同一工作区里的多个会话，支持列表 / 图谱两种视图。
 *
 * 图谱把「一个工作区」画成中心节点、会话画成环绕节点，一眼能看出会话都属于当前工作区；
 * 列表视图同步给出标题、时间与消息数，长按/垃圾桶可删。
 */
@Composable
internal fun SessionPanel(
    workspaceName: String,
    sessions: List<SessionMeta>,
    activeId: String?,
    onSelect: (SessionMeta) -> Unit,
    onNew: () -> Unit,
    onDelete: (SessionMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    var showGraph by remember { mutableStateOf(sessions.size > 0) }
    val buzz = rememberHapticClick()

    InlineOverlay(onDismiss = onDismiss, fillHeight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "会话",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    workspaceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { showGraph = !showGraph }) {
                Text(if (showGraph) "列表" else "图谱", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDismiss) {
                Icon(AppIcons.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
            }
        }

        if (sessions.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "这个工作区还没有会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (showGraph) {
            SessionGraph(
                sessions = sessions,
                activeId = activeId,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().height(300.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            ) {
                items(sessions, key = { it.id }) { s ->
                    val isActive = s.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadii.Chip))
                            .background(if (isActive) AppTheme.colors.surfaceRaised else Color.Transparent)
                            .clickable { buzz(); onSelect(s) }
                            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.Code,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(AppSpacing.Sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatTime(s.updatedAt)} · ${s.count} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { buzz(); onDelete(s) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                AppIcons.Delete,
                                contentDescription = "删除会话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            OutlinedButton(
                onClick = { buzz(); onNew() },
                modifier = Modifier.weight(1f),
            ) { Text("新会话") }
        }
    }
}

/** 会话图谱：中心 = 当前工作区，周围 = 各会话；点节点即切会话 */
@Composable
private fun SessionGraph(
    sessions: List<SessionMeta>,
    activeId: String?,
    onSelect: (SessionMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val measurer = rememberTextMeasurer()
    // 主题色必须在 Canvas 之前取出：Canvas 的绘制 lambda 不是 @Composable 作用域
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val positions = remember(sessions, activeId) {
        val count = sessions.size
        sessions.mapIndexed { i, s ->
            val angle = -Math.PI / 2 + 2 * Math.PI * i / count
            val ring = if (count <= 6) 0.33f else if (i % 2 == 0) 0.30f else 0.40f
            Offset(
                (0.5f + ring * cos(angle)).toFloat(),
                (0.5f + ring * sin(angle) * 0.85f).toFloat(),
            ) to s
        }
    }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(colors.surfaceRaised.copy(alpha = 0.5f))
            .pointerInput(sessions) {
                detectTapGestures { tap ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val hit = positions.firstOrNull { (pos, _) ->
                        val dx = tap.x - pos.x * w
                        val dy = tap.y - pos.y * h
                        dx * dx + dy * dy <= (36.dp.toPx()) * (36.dp.toPx())
                    }?.second
                    if (hit != null) {
                        onSelect(hit)
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2, h / 2)
        val nodeR = 9.dp.toPx()
        val ringR = 26.dp.toPx()

        // 中心 → 会话连线
        positions.forEach { (pos, _) ->
            drawLine(
                color = colors.outlineSoft.copy(alpha = 0.45f),
                start = center,
                end = Offset(pos.x * w, pos.y * h),
                strokeWidth = 1.5f,
            )
        }
        // 中心工作区节点
        drawCircle(color = colors.surfaceBase, radius = ringR, center = center)
        drawCircle(color = primary, radius = ringR, center = center, style = Stroke(2f))
        val centerLabel = measurer.measure(
            "工作区",
            TextStyle(color = onSurface, fontSize = 10.sp, fontFamily = FontFamily.SansSerif),
        )
        drawText(
            textLayoutResult = centerLabel,
            topLeft = Offset(center.x - centerLabel.size.width / 2f, center.y - centerLabel.size.height / 2f),
        )

        positions.forEach { (pos, s) ->
            val p = Offset(pos.x * w, pos.y * h)
            val isActive = s.id == activeId
            drawCircle(
                color = if (isActive) primary else onSurfaceVariant.copy(alpha = 0.5f),
                radius = nodeR,
                center = p,
            )
            if (isActive) {
                drawCircle(
                    color = primary.copy(alpha = 0.22f),
                    radius = nodeR * 1.9f,
                    center = p,
                )
            }
            val label = measurer.measure(
                s.title.take(9),
                TextStyle(color = onSurface, fontSize = 9.sp, fontFamily = FontFamily.SansSerif),
            )
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    (p.x - label.size.width / 2f).coerceIn(2f, (w - label.size.width - 2f).coerceAtLeast(2f)),
                    p.y + nodeR + 3f,
                ),
            )
        }
        // 右上角提示：点节点切换
        val hint = measurer.measure(
            "点节点切换会话",
            TextStyle(color = onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.SansSerif),
        )
        drawText(textLayoutResult = hint, topLeft = Offset(8.dp.toPx(), 8.dp.toPx()))
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val pattern = if (now - ts < 86_400_000L) "HH:mm" else "MM-dd HH:mm"
    return runCatching { TimeFormats.format(pattern, ts) }.getOrDefault("")
}
