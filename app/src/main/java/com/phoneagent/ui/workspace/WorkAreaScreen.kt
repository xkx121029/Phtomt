package com.phoneagent.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.components.liquidGlass
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.contentSpringSpec
import com.phoneagent.workspace.WorkDisplay
import com.phoneagent.workspace.WorkFile
import com.phoneagent.workspace.WorkLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区：给 AI 一个可写文件的目录，生成 Markdown/纯文本文档。
 * 用户可实时跟随 AI 操作（流式内容预览 + 操作日志）。
 * 文件列表独立为 [FileListScreen]（点入口卡片进入），文件预览编辑为 [FileEditorScreen]。
 *
 * 布局层级：Header → 生成卡片 → 实时预览 → 文件入口 → 操作日志。
 */
@Composable
fun WorkAreaScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onOpenFileList: () -> Unit = {},
    onOpenEditor: (String) -> Unit = {},
) {
    val files by vm.workFiles.collectAsState()
    val generating by vm.workGenerating.collectAsState()
    val preview by vm.workPreview.collectAsState()
    val logs by vm.workLogs.collectAsState()
    val error by vm.workError.collectAsState()
    val display by vm.workDisplay.collectAsState()

    var task by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    val buzz = rememberHapticClick()

    LaunchedEffect(Unit) { vm.workRefreshFiles() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- 页头 ----
        AppTopBar(
            title = "工作区",
            subtitle = "让 AI 编写文档，实时跟随生成过程",
        )

        Spacer(Modifier.height(8.dp))

        // ---- 生成输入区 ----
        GenerateCard(
            task = task,
            fileName = fileName,
            generating = generating,
            error = error,
            onTask = { task = it },
            onFileName = { fileName = it },
            onGenerate = { buzz(); vm.workGenerate(task, fileName) },
            onStop = { vm.workStop() },
        )

        // ---- 实时预览区（跟随 AI 生成） ----
        if (preview.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            PreviewCard(vm, preview, generating)
        }

        Spacer(Modifier.height(20.dp))

        // ---- 文件入口卡片（独立文件列表页） ----
        FilesEntryCard(files, onClick = onOpenFileList)

        // ---- 操作日志 ----
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "操作日志 · ${logs.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (logs.isNotEmpty()) {
                Text(
                    "清空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.workClearLogs() }
                        .padding(6.dp),
                )
            }
        }
        if (logs.isEmpty()) {
            EmptyHint(
                icon = Icons.Rounded.History,
                title = "暂无操作记录",
                desc = "AI 生成文档时的每一步操作都会记录在这里。",
            )
        } else {
            LogStream(logs)
        }

        Spacer(Modifier.height(28.dp))
    }

    // AI 编辑完文件后，直接展示内容给用户
    val cur = display
    if (cur != null) {
        WorkDisplayPanel(cur, onDismiss = { vm.workDismissDisplay() })
    }
}

/** 文件入口卡片：显示文件数量与最新文件，点击进入独立文件列表页 */
@Composable
private fun FilesEntryCard(files: List<WorkFile>, onClick: () -> Unit) {
    val latest = files.maxByOrNull { it.modifiedAt }
    Surface(
        shape = RoundedCornerShape(AppRadii.Bubble),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .animateListItem(0)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(AppRadii.Inline)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "已生成文件",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (latest == null) "暂无文件，点击进入"
                    else "${files.size} 个文件 · 最新：${latest.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 生成卡片：描述文档 + 可选文件名 + 生成/停止按钮 */
@Composable
private fun GenerateCard(
    task: String,
    fileName: String,
    generating: Boolean,
    error: String,
    onTask: (String) -> Unit,
    onFileName: (String) -> Unit,
    onGenerate: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(alpha = 0.55f, cornerRadius = AppRadii.Card)
            .animateListItem(1),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(AppRadii.Inline),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "新建文档",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (generating) "AI 正在编写中…" else "描述你想让 AI 写的内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = task,
                onValueChange = onTask,
                label = { Text("想让 AI 写什么？") },
                placeholder = { Text("例如：写一份健身计划，包含每周训练安排") },
                shape = RoundedCornerShape(AppRadii.Tile),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp),
                minLines = 3,
                enabled = !generating,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = fileName,
                onValueChange = onFileName,
                label = { Text("文件名（可选）") },
                placeholder = { Text("如 健身计划.md，留空由 AI 决定") },
                shape = RoundedCornerShape(AppRadii.Tile),
                singleLine = true,
                enabled = !generating,
                modifier = Modifier.fillMaxWidth(),
            )

            if (error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(14.dp))
            if (!generating) {
                Button(
                    onClick = onGenerate,
                    enabled = task.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(AppRadii.Tile),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始生成")
                }
            } else {
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(),
                    shape = RoundedCornerShape(AppRadii.Tile),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("停止生成")
                }
            }
        }
    }
}

/** 空状态提示 */
@Composable
fun EmptyHint(icon: ImageVector, title: String, desc: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(AppRadii.Tile)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PreviewCard(vm: MainViewModel, content: String, generating: Boolean) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(alpha = 0.6f, cornerRadius = AppRadii.Card)
            .animateListItem(2),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = generating,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在生成…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                val active = com.phoneagent.ui.workspace.rememberActiveFile(vm)
                if (active != null) {
                    Text(
                        active,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                content,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(AppRadii.Tile),
                    )
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun rememberActiveFile(vm: MainViewModel): String? {
    val active by vm.workActiveFile.collectAsState()
    return active
}

@Composable
fun FileRow(
    file: WorkFile,
    onClick: () -> Unit,
    onShow: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AppRadii.Inline),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${formatSize(file.sizeBytes)} · ${formatTime(file.modifiedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PressableScale(onClick = onShow) {
                Icon(
                    Icons.Rounded.OpenInFull,
                    contentDescription = "查看",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
            PressableScale(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LogStream(logs: List<WorkLog>) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(modifier = Modifier.fillMaxWidth()) {
        logs.reversed().forEachIndexed { i, log ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateListItem(i)
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    fmt.format(Date(log.time)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.width(64.dp),
                )
                Text(
                    log.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}

private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - millis < 24 * 3600 * 1000L)
        SimpleDateFormat("HH:mm", Locale.getDefault())
    else
        SimpleDateFormat("MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}

/**
 * AI 编辑完文件后向用户展示内容的浮层面板。
 * 覆盖在工作区页面上，白色液态玻璃 + 滚动正文 + 复制/关闭。
 */
@Composable
private fun WorkDisplayPanel(display: WorkDisplay, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 面板入场动画：淡入 + 弹簧轻微缩放（尊重“减少动画”时仅淡入）
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val showAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(DurationNormal, easing = EaseOut),
        label = "panel-alpha",
    )
    val showScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.94f,
        animationSpec = contentSpringSpec(),
        label = "panel-scale",
    )

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f * showAlpha))
            .clickable(enabled = true, onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
    ) {
        Surface(
            shape = RoundedCornerShape(AppRadii.Card),
            color = Color.Transparent,
            modifier = Modifier
                .alpha(showAlpha)
                .graphicsLayer { scaleX = showScale; scaleY = showScale }
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 20.dp)
                .liquidGlass(alpha = 0.95f, cornerRadius = AppRadii.Card),
        ) {
            Column(modifier = Modifier.heightIn(max = 520.dp)) {
                // 头部：文件名 + 关闭
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        display.fileName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
                // 正文
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        display.content.ifBlank { "（空文档）" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                // 底部操作
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = android.content.ClipData.newPlainText(display.fileName, display.content)
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(clip)
                        },
                        shape = RoundedCornerShape(AppRadii.Inline),
                    ) {
                        Text("复制全文")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(AppRadii.Inline),
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}
