package com.phoneagent.device.a11y

import java.net.URI
import java.net.URLEncoder

/**
 * 「用系统应用打开链接/文件」的目标归一化与类型推断。
 *
 * 为什么需要它：AI 说"用手机文档软件打开这个 ppt"时，给出的往往是**本地路径**
 * （`/sdcard/Download/x.ppt`）。直接 `ACTION_VIEW` + `file://` 在 API 24+ 会抛
 * `FileUriExposedException`（系统禁止把文件路径暴露给别的应用），任务必失败。
 *
 * 解法是把路径换成系统「外部存储文档提供者」的 `content://`：
 * `content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.ppt`
 * 它由系统自己的 provider 提供，配合 `FLAG_GRANT_READ_URI_PERMISSION` 会把读权限随 Intent
 * 临时授予接收方——我们不需要申请任何存储权限，也不需要自带 FileProvider。
 *
 * 全部是纯函数（不碰 Context / PackageManager），因此可以直接单测。
 */
internal object OpenTarget {

    /** 系统「外部存储」文档提供者（ExternalStorageProvider）的 authority */
    private const val DOC_PROVIDER = "com.android.externalstorage.documents"

    /** 主存储卷名（`/sdcard` 与 `/storage/emulated/0` 都映射到它） */
    private const val PRIMARY_VOLUME = "primary"

    /** 是否是本地文件（绝对路径，或 `file://` 形式） */
    fun isLocalFile(raw: String): Boolean {
        val s = raw.trim()
        return s.startsWith("file://", ignoreCase = true) || (s.startsWith("/") && !s.startsWith("//"))
    }

    /**
     * 归一化打开目标：
     * - 本地文件（`/sdcard/x.ppt`、`/storage/emulated/0/x.ppt`、`file:///…`）→ 文档提供者 `content://`；
     * - 其余（http/https、App 私有 scheme、系统页 action、`content://`）→ 原样返回。
     *
     * 路径不在已知存储卷下（如 `/proc/…`）时原样返回，由调用方按失败回报，不硬凑。
     */
    fun normalize(raw: String): String {
        val s = raw.trim()
        if (!isLocalFile(s)) return s
        val path = if (s.startsWith("file://", ignoreCase = true)) {
            runCatching { URI(s).path }.getOrNull().orEmpty()
        } else {
            s
        }
        val split = splitVolume(path) ?: return s
        val docId = encode("${split.first}:${split.second}")
        return "content://$DOC_PROVIDER/document/$docId"
    }

    /**
     * 把绝对路径拆成 `卷名 to 相对路径`：
     * - `/sdcard/Download/x.ppt` → `primary` / `Download/x.ppt`
     * - `/storage/emulated/0/Download/x.ppt` → `primary` / `Download/x.ppt`
     * - `/storage/1A2B-3C4D/x.ppt` → `1A2B-3C4D` / `x.ppt`（外置 SD 卡）
     */
    private fun splitVolume(path: String): Pair<String, String>? {
        val clean = path.trim().trimStart('/')
        if (clean.isBlank()) return null
        val segs = clean.split('/').filter { it.isNotBlank() }
        return when {
            segs.size >= 2 && segs[0] == "sdcard" -> PRIMARY_VOLUME to segs.drop(1).joinToString("/")
            segs.size >= 4 && segs[0] == "storage" && segs[1] == "emulated" ->
                PRIMARY_VOLUME to segs.drop(3).joinToString("/")
            segs.size >= 3 && segs[0] == "storage" -> segs[1] to segs.drop(2).joinToString("/")
            else -> null
        }
    }

    /** 文档 id 里 `:` 与 `/` 必须百分号转义，否则提供者解析不出卷与路径 */
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * 按扩展名推断 MIME。
     *
     * 为什么要带 MIME：`ACTION_VIEW` 只给 `content://` 而不给类型时，系统常按
     * `content://` 的通用类型去匹配，文档类应用（WPS/系统文档阅读器）可能不在候选里，
     * 于是"没有应用可打开"。给出准确 MIME 才能让对应软件出现在选择列表里。
     *
     * 认不出的扩展名返回 null（不设 type，交系统自行判断），绝不硬编一个错的类型。
     */
    fun mimeOf(raw: String): String? {
        val name = raw.trim().substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIMES[ext]
    }

    private val MIMES: Map<String, String> = mapOf(
        // 文档
        "pdf" to "application/pdf",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "csv" to "text/csv",
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "epub" to "application/epub+zip",
        // 图片
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "heic" to "image/heic",
        // 音视频
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        // 其它
        "apk" to "application/vnd.android.package-archive",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
    )
}