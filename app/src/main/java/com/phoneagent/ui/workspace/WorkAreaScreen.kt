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
import com.phoneagent.ui.components.EmptyHint
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.components.liquidGlass
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.contentSpringSpec
import com.phoneagent.feature.workspace.WorkDisplay
import com.phoneagent.feature.workspace.WorkFile
import com.phoneagent.feature.workspace.WorkLog
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