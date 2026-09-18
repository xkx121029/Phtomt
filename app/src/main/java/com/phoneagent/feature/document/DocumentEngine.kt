package com.phoneagent.feature.document

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 文档结果引擎：AI 产出的文档正文落盘到应用私有目录，并把结果推给 Agent 页预览。
 *
 * 只做两件事：写入文件、把最近一次结果暴露成状态流。
 * 文件列表 / 编辑器那套工作区界面已移除，文档结果统一在 Agent 页任务流里展示。
 */
class DocumentEngine(private val context: Context) {

    /** 文档根目录（应用专属外部存储，无需运行时权限，用户可经文件管理器访问） */
    private val rootDir: File
        get() = File(context.getExternalFilesDir(null), "documents").apply { mkdirs() }

    /** 最近一次生成的文档结果（null = 当前无可预览内容） */
    private val _result = MutableStateFlow<DocResult?>(null)
    val result: StateFlow<DocResult?> get() = _result.asStateFlow()

    /** 最近一次错误信息 */
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> get() = _error.asStateFlow()

    /**
     * 写入文档（内容由调用方提供，如 Agent 决策产出），并把结果推给界面预览。
     * @param content 文档正文（Markdown/纯文本）
     * @param fileName 目标文件名（可带 .md）；留空则按时间戳命名
     * @return 实际写入的文件名；失败返回空串
     */
    fun writeDocument(content: String, fileName: String = ""): String {
        if (content.isBlank()) {
            _error.value = "文档内容为空"
            return ""
        }
        return runCatching {
            val resolved = sanitizeName(fileName.ifBlank { "文档_${System.currentTimeMillis()}.md" })
            File(rootDir, resolved).writeText(content)
            _error.value = ""
            _result.value = DocResult(
                fileName = resolved,
                content = content,
                time = System.currentTimeMillis(),
            )
            resolved
        }.getOrElse {
            _error.value = it.message ?: "写入失败"
            ""
        }
    }

    /** 关闭预览（仅清界面状态，磁盘文件保留） */
    fun dismiss() {
        _result.value = null
    }

    private fun sanitizeName(name: String): String {
        var n = name.trim()
        if (n.isBlank()) n = "document.md"
        // 无后缀时按文档补 .md
        if (!n.endsWith(".md") && !n.endsWith(".txt") && !n.contains(".")) n += ".md"
        return n.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }
}

/** AI 生成的文档结果（Agent 页预览用） */
data class DocResult(
    val fileName: String,
    val content: String,
    val time: Long,
)