package com.phoneagent.ui.workspace.FileListScreen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.EmptyHint

/**
 * 文件列表页：独立全屏页面展示工作区全部文件。
 * 点击文件进入 [FileEditorScreen] 预览编辑（正文 + AI 聊天改写）。
 */
@Composable
fun FileListScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onOpenEditor: (String) -> Unit = {},
) {
    val files by vm.workFiles.collectAsState()

    LaunchedEffect(Unit) { vm.workRefreshFiles() }

    Column(modifier = modifier.fillMaxSize()) {
        if (files.isEmpty()) {
            EmptyHint(
                icon = Icons.Rounded.FolderOpen,
                title = "暂无文件",
                desc = "让 AI 生成一个文档，它会出现在这里。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                items(files, key = { it.name }) { f ->
                    FileRow(
                        file = f,
                        onClick = { onOpenEditor(f.name) },
                        onShow = { onOpenEditor(f.name) },
                        onDelete = { vm.workDeleteFile(f.name) },
                    )
                }
            }
        }
    }
}
