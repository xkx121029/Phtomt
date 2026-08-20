package com.phoneagent.workspace

import android.content.Context
import com.phoneagent.ai.AiClient
import com.phoneagent.ai.ChatMessageDto
import com.phoneagent.ai.ContentPart
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.agent.PromptLang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区引擎：给 AI 提供一个可写文件的目录，并在其中生成 Markdown / 纯文本文档。
 *
 * 能力：
 * - 文件管理：列出 / 创建 / 读取 / 删除工作区文件（应用私有 external files 目录）
 * - AI 文档生成：流式生成 Markdown 内容，实时写入临时缓冲并最终落盘
 * - 实时跟随：通过 [previewContent] 让用户在界面上实时看到 AI 正在生成的内容
 * - 操作日志：记录 AI 每一步操作（创建/写入/完成/重命名等），形成日志流
 */
class WorkAreaEngine(
    private val context: Context,
    private val settings: AppSettings,
    private val aiClient: AiClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var editJob: Job? = null

    /** 工作区根目录（应用专属外部存储，无需运行时权限，用户可经文件管理器访问） */
    private val rootDir: File
        get() = File(context.getExternalFilesDir(null), "workarea").apply { mkdirs() }

    // ---- 对外状态 ----
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> get() = _isGenerating.asStateFlow()

    /** 当前正在生成 / 最近生成的文件名 */
    private val _activeFile = MutableStateFlow<String?>(null)
    val activeFile: StateFlow<String?> get() = _activeFile.asStateFlow()

    /** 流式预览内容：AI 正在生成或最近完成的文档全文 */
    private val _previewContent = MutableStateFlow("")
    val previewContent: StateFlow<String> get() = _previewContent.asStateFlow()

    /** 文件列表快照 */
    private val _files = MutableStateFlow<List<WorkFile>>(emptyList())
    val files: StateFlow<List<WorkFile>> get() = _files.asStateFlow()

    /** 操作日志流（时间戳 + 文本） */
    private val _logs = MutableStateFlow<List<WorkLog>>(emptyList())
    val logs: StateFlow<List<WorkLog>> get() = _logs.asStateFlow()

    /** 错误信息（最近一次） */
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> get() = _error.asStateFlow()

    /** 待展示给用户的文档内容（AI 编辑完文件后自动触发） */
    private val _display = MutableStateFlow<WorkDisplay?>(null)
    val display: StateFlow<WorkDisplay?> get() = _display.asStateFlow()

    // ---- 文档预览编辑状态（文件预览页 + AI 改写） ----
    /** 当前正在预览/编辑的文件名；null 表示未打开编辑器 */
    private val _editingFile = MutableStateFlow<String?>(null)
    val editingFile: StateFlow<String?> get() = _editingFile.asStateFlow()

    /** 当前编辑文件的最新内容（AI 改写成功后自动更新） */
    private val _editingContent = MutableStateFlow("")
    val editingContent: StateFlow<String> get() = _editingContent.asStateFlow()

    /** AI 是否正在改写文档 */
    private val _editBusy = MutableStateFlow(false)
    val editBusy: StateFlow<Boolean> get() = _editBusy.asStateFlow()

    /** 聊天消息列表：user 指令 + assistant 改写结果 */
    private val _editChat = MutableStateFlow<List<EditChatMessage>>(emptyList())
    val editChat: StateFlow<List<EditChatMessage>> get() = _editChat.asStateFlow()

    /** AI 改写过程中的流式输出（用于聊天气泡实时渲染） */
    private val _editStream = MutableStateFlow("")
    val editStream: StateFlow<String> get() = _editStream.asStateFlow()

    /** 编辑相关错误信息（最近一次） */
    private val _editError = MutableStateFlow("")
    val editError: StateFlow<String> get() = _editError.asStateFlow()

    fun refreshFiles() {
        _files.value = rootDir.listFiles()
            ?.filter { it.isFile }
            ?.map { WorkFile(name = it.name, sizeBytes = it.length(), modifiedAt = it.lastModified()) }
            ?.sortedByDescending { it.modifiedAt }
            ?: emptyList()
    }

    /**
     * 直接写入文档到工作区（不调用 AI 生成，内容由调用方提供，如 Agent 决策产出）。
     * 写入成功后自动触发展示面板，供用户即时查看。
     * @param content 文档正文（Markdown/纯文本）
     * @param fileName 目标文件名（可带 .md）；留空则按时间戳命名
     * @return 实际写入的文件名
     */
    fun writeDocument(content: String, fileName: String = ""): String {
        if (content.isBlank()) { log("写入失败：内容为空"); return "" }
        val resolved = sanitizeName(fileName.ifBlank { "文档_${System.currentTimeMillis()}.md" })
        return runCatching {
            File(rootDir, resolved).writeText(content)
            refreshFiles()
            _activeFile.value = resolved
            _previewContent.value = content
            _display.value = WorkDisplay(fileName = resolved, content = content, time = System.currentTimeMillis())
            log("已写入文档：$resolved（${content.length} 字符）")
            resolved
        }.getOrElse {
            _error.value = it.message ?: "写入失败"
            log("写入失败：${it.message}")
            ""
        }
    }

    fun readFile(name: String): String {
        val f = File(rootDir, name)
        return if (f.exists()) f.readText() else ""
    }

    /** 删除文件（校验文件名，仅限工作区目录内） */
    fun deleteFile(name: String) {
        val f = File(rootDir, name)
        if (f.exists() && f.delete()) {
            log("删除文件：$name")
            refreshFiles()
        }
    }

    /**
     * 让 AI 生成一个文档并写入工作区。
     * @param task 用户描述想要什么文档
     * @param fileName 期望文件名（可带 .md 后缀）；为空则让 AI 决定
     * @param onPreview 可选：流式内容回调（UI 可实时渲染）
     */
    fun generateDocument(task: String, fileName: String = "", onPreview: (String) -> Unit = {}) {
        if (task.isBlank() || _isGenerating.value) return
        _error.value = ""
        _isGenerating.value = true
        _previewContent.value = ""
        refreshFiles()
        job = scope.launch {
            try {
                val settingsVal = settings.settings.first()
                val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
                val prompt = documentPrompt(lang, task, fileName)
                val messages = listOf(
                    ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = prompt))),
                )
                val sb = StringBuilder()
                log("AI 开始生成文档…")

                // 第一阶段：让 AI 给出文件名与内容（流式正文累积）
                val content = aiClient.chatStream(
                    baseUrl = settingsVal.apiBaseUrl,
                    apiKey = settingsVal.apiKey,
                    model = settingsVal.model,
                    messages = messages,
                    temperature = 0.6,
                    onDelta = { delta ->
                        sb.append(delta)
                        _previewContent.value = sb.toString()
                    },
                ).getOrElse { throw it }

                // 第二阶段：内容可能包含 ```markdown 包裹，剥离代码块围栏
                val finalContent = stripFences(content.ifBlank { sb.toString() })
                val resolvedName = resolveFileName(fileName, finalContent, lang)
                withContext(Dispatchers.IO) {
                    File(rootDir, resolvedName).writeText(finalContent)
                }
                _activeFile.value = resolvedName
                _previewContent.value = finalContent
                log("文档已生成：$resolvedName（${finalContent.length} 字符）")
                refreshFiles()
                // AI 编辑完文件后，直接展示给用户（无需用户手动查找）
                _display.value = WorkDisplay(
                    fileName = resolvedName,
                    content = finalContent,
                    time = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "生成失败"
                log("生成失败：${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** 停止当前生成任务 */
    fun stop() {
        job?.cancel()
        job = null
        _isGenerating.value = false
        log("已停止生成")
    }

    /** 手动展示某个文件内容给用户 */
    fun showFile(name: String) {
        val f = File(rootDir, name)
        if (!f.exists()) return
        _display.value = WorkDisplay(
            fileName = name,
            content = f.readText(),
            time = System.currentTimeMillis(),
        )
    }

    /** 关闭当前展示面板 */
    fun dismissDisplay() {
        _display.value = null
    }

    // ==================== 文档预览编辑（AI 改写） ====================

    /** 打开文件预览编辑页：加载全文、清空聊天记录 */
    fun openEditor(name: String) {
        val f = File(rootDir, name)
        if (!f.exists()) return
        editJob?.cancel()
        editJob = null
        _editBusy.value = false
        _editingFile.value = name
        _editingContent.value = f.readText()
        _editChat.value = emptyList()
        _editStream.value = ""
        _editError.value = ""
        log("打开文档：$name")
    }

    /** 关闭文件预览编辑页 */
    fun closeEditor() {
        editJob?.cancel()
        editJob = null
        _editBusy.value = false
        _editingFile.value = null
        _editingContent.value = ""
        _editChat.value = emptyList()
        _editStream.value = ""
        _editError.value = ""
    }

    /**
     * 把当前文档全文 + 用户指令发给 AI，AI 结合指令改写后写回文件。
     * 流式结果通过 [editStream] 实时输出，完成后更新 [editingContent] 并落盘。
     */
    fun editDocument(instruction: String) {
        val name = _editingFile.value ?: return
        if (instruction.isBlank() || _editBusy.value) return
        _editError.value = ""
        val original = _editingContent.value
        if (original.isBlank()) { _editError.value = "文档为空，暂无可修改内容"; return }
        _editChat.value = _editChat.value + EditChatMessage(
            role = "user",
            content = instruction,
            time = System.currentTimeMillis(),
        )
        _editStream.value = ""
        _editBusy.value = true
        editJob = scope.launch {
            try {
                val settingsVal = settings.settings.first()
                val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
                val messages = listOf(
                    ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = editSystemPrompt(lang)))),
                    ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = editPrompt(lang, original, instruction)))),
                )
                log("AI 开始修改文档：$name")
                val sb = StringBuilder()
                val result = aiClient.chatStream(
                    baseUrl = settingsVal.apiBaseUrl,
                    apiKey = settingsVal.apiKey,
                    model = settingsVal.model,
                    messages = messages,
                    temperature = 0.5,
                    onDelta = { delta ->
                        sb.append(delta)
                        _editStream.value = sb.toString()
                    },
                ).getOrElse { throw it }

                val finalContent = stripFences(result.ifBlank { sb.toString() })
                withContext(Dispatchers.IO) {
                    File(rootDir, name).writeText(finalContent)
                }
                _editingContent.value = finalContent
                _editChat.value = _editChat.value + EditChatMessage(
                    role = "assistant",
                    content = finalContent,
                    time = System.currentTimeMillis(),
                )
                _editStream.value = ""
                log("文档已修改：$name（${finalContent.length} 字符）")
                refreshFiles()
            } catch (e: Exception) {
                _editError.value = e.message ?: "修改失败"
                log("修改失败：${e.message}")
            } finally {
                _editBusy.value = false
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ==================== Prompt ====================

    private fun documentPrompt(lang: PromptLang, task: String, fileName: String): String = when (lang) {
        PromptLang.CN -> """
你是文档生成助手。根据用户需求，直接输出一篇完整的 Markdown 文档内容。

# 用户需求
$task

# 输出要求
1. 只输出 Markdown 文档正文，使用规范 Markdown 语法（标题、列表、表格、代码块、引用等）。
2. 不使用 ```markdown 代码块包裹正文，直接输出正文本身。
3. 内容要详实、结构清晰、表达专业。
4. 若用户未指定文件名，请在第一行用 HTML 注释形式给出建议文件名，格式：<!--FILENAME: 建议文件名.md-->，随后输出正文。
${if (fileName.isNotBlank()) "5. 目标文件名：$fileName（无需在内容中再指定文件名）。" else ""}
""".trimIndent()
        PromptLang.EN -> """
You are a document generation assistant. Produce a complete Markdown document based on the user's request.

# User Request
$task

# Output Requirements
1. Output only the Markdown document body, using proper Markdown syntax (headings, lists, tables, code blocks, quotes, etc.).
2. Do NOT wrap the body in ```markdown fences; output the body directly.
3. Content should be thorough, well-structured, and professional.
4. If no filename was specified, give a suggested filename on the first line as an HTML comment: <!--FILENAME: suggested.md-->, followed by the body.
${if (fileName.isNotBlank()) "5. Target filename: $fileName (no need to specify filename in content)." else ""}
""".trimIndent()
    }

    /** 编辑系统提示词：约束 AI 只输出修改后的完整文档 */
    private fun editSystemPrompt(lang: PromptLang): String = when (lang) {
        PromptLang.CN -> "你是专业的文档编辑助手，擅长在保留原文结构与风格的基础上，严格按照用户的修改指令精准改写文档。"
        PromptLang.EN -> "You are a professional document editing assistant. Revise the document precisely according to the user's instructions while preserving its original structure and style."
    }

    /** 编辑用户提示词：当前文档全文 + 修改指令 */
    private fun editPrompt(lang: PromptLang, original: String, instruction: String): String = when (lang) {
        PromptLang.CN -> """
请根据「修改指令」修改下方提供的「文档全文」，然后只输出修改后的完整文档。

# 输出要求
1. 只输出修改后的文档正文本身，不要输出任何解释、说明或前言。
2. 不使用 ```markdown 代码块包裹正文，直接输出正文。
3. 保持 Markdown 语法规范；未涉及的内容尽量原样保留，不要擅自增删。

# 当前文档全文
$original

# 修改指令
$instruction
""".trimIndent()
        PromptLang.EN -> """
Revise the provided "Document Content" below according to the "Revision Instruction", then output ONLY the complete revised document.

# Output Requirements
1. Output only the revised document body itself, with no explanation or preamble.
2. Do NOT wrap the body in ```markdown fences; output the body directly.
3. Keep proper Markdown syntax; leave unrelated parts unchanged as much as possible.

# Document Content
$original

# Revision Instruction
$instruction
""".trimIndent()
    }

    /** 剥离 ```markdown/``` 围栏，提取纯正文 */
    private fun stripFences(content: String): String {
        var c = content.trim()
        if (c.startsWith("```")) {
            val firstNl = c.indexOf("\n")
            if (firstNl > 0) c = c.substring(firstNl + 1)
            if (c.endsWith("```")) c = c.dropLast(3)
            c = c.trim()
        }
        return c
    }

    /** 解析文件名：优先用户指定，其次 AI 建议注释，兜底时间戳 */
    private fun resolveFileName(requested: String, content: String, lang: PromptLang): String {
        if (requested.isNotBlank()) return sanitizeName(requested)
        val comment = Regex("<!--FILENAME:\\s*(.+?)-->").find(content)
        val suggested = comment?.groupValues?.get(1)?.trim()
        if (!suggested.isNullOrBlank()) return sanitizeName(suggested)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (lang == PromptLang.CN) "文档_$stamp.md" else "document_$stamp.md"
    }

    private fun sanitizeName(name: String): String {
        var n = name.trim()
        if (n.isBlank()) n = "document.md"
        // 确保 .md 后缀（若是文档内容）
        if (!n.endsWith(".md") && !n.endsWith(".txt") && !n.contains(".")) n += ".md"
        return n.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }

    private fun log(text: String) {
        _logs.value = _logs.value + WorkLog(System.currentTimeMillis(), text)
        if (_logs.value.size > 200) _logs.value = _logs.value.takeLast(200)
    }
}

/** 工作区文件信息 */
data class WorkFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** 工作区操作日志 */
data class WorkLog(
    val time: Long,
    val text: String,
)

/** 文档编辑聊天消息：user = 用户修改指令，assistant = AI 改写结果 */
data class EditChatMessage(
    val role: String,
    val content: String,
    val time: Long,
)

/** 待展示给用户的文档内容（AI 编辑完文件后自动触发，或手动查看） */
data class WorkDisplay(
    val fileName: String,
    val content: String,
    val time: Long,
)