package com.phoneagent.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.ui.theme.AppRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 轻量 Markdown 渲染器：把 md 源码渲染为排版好的阅读视图（预览而非编辑）。
 * 支持：标题(#、##…)、粗体(**)、斜体(*)、删除线(~~)、行内代码(`)、链接([文字](网址))、
 * 无序/有序列表、代码块(```)、引用(>)、分隔线(---)、图片(![alt](src))。
 * 图片支持：data:image…base64 内嵌图，以及基于 [baseDir] 解析的相对/绝对本地路径。
 * 解析器为本地确定性实现，不依赖第三方库。
 *
 * [maxLines] 非空时限行并省略（流式回显只贴尾部若干行）；为 null 时行为与不限行完全一致。
 */
@Composable
fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
    baseDir: String? = null,
    maxLines: Int? = null,
) {
    if (content.isBlank()) {
        Text("（空文档）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // 不像 md 的纯文本整段渲染即可：强套解析器既浪费，也会吃掉换行、把正文里的 * 与 - 误当格式
    if (!looksLikeMarkdown(content)) {
        Text(
            text = content.trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val blocks = remember(content, baseDir) { parseBlocks(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdB.Heading -> Text(
                    b.text,
                    fontSize = when (b.level) {
                        1 -> 22.sp
                        2 -> 18.sp
                        3 -> 15.sp
                        else -> 14.sp
                    },
                    fontWeight = if (b.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp,
                    maxLines = maxLines ?: Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                is MdB.Para -> Text(
                    rich(b.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = maxLines ?: Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                is MdB.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    b.items.forEachIndexed { idx, itm ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                if (b.ordered) "${idx + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                rich(itm),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = maxLines ?: Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is MdB.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(AppRadii.Chip))
                        .padding(10.dp),
                ) {
                    Text(
                        b.code.trimEnd(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
                is MdB.Quote -> Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .padding(top = 2.dp, bottom = 2.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rich(b.text),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = maxLines ?: Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdB.Image -> MarkdownImage(b.src, b.alt, baseDir)
                MdB.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            }
        }
    }
}

// ---------------- 是否像 Markdown ----------------

/** 块级标记：行首 `#` / 列表 / 引用 / 围栏 / 分隔线。要求行首，避免正文中间的 `-` 被误判 */
private val MD_BLOCK_MARKERS = listOf(
    Regex("(?m)^#{1,6}(\\s|$)"),
    Regex("(?m)^\\s*([-*+]\\s|\\d+\\.\\s)"),
    Regex("(?m)^\\s*>\\s"),
    Regex("(?m)^\\s*```"),
    Regex("(?m)^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$"),
)

/**
 * 是否"看起来像 Markdown"。
 *
 * 命中任一标记才算，纯文本不强行套块级解析——解析器会把换行拍平、
 * 也会把正文里偶然出现的 `-` / `*` 当列表。流式半截文本同样适用。
 */
fun looksLikeMarkdown(text: String): Boolean {
    if (text.isBlank()) return false
    if (MD_BLOCK_MARKERS.any { it.containsMatchIn(text) }) return true
    if (text.contains("**") || text.contains("~~") || text.contains('`')) return true
    if (text.contains("](") || text.contains("![")) return true
    // 表格行：至少两个竖线才像表，单个竖线在普通句子里太常见
    if (text.count { it == '|' } >= 2) return true
    return false
}

// ---------------- 块结构 ----------------

private sealed interface MdB {
    data class Heading(val level: Int, val text: String) : MdB
    data class Para(val text: String) : MdB
    data class Bullet(val items: List<String>, val ordered: Boolean) : MdB
    data class Code(val code: String) : MdB
    data class Quote(val text: String) : MdB
    data class Image(val src: String, val alt: String) : MdB
    object Rule : MdB
}

/** 把 markdown 文本解析为渲染块列表 */
private fun parseBlocks(text: String): List<MdB> {
    val out = mutableListOf<MdB>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    val bullets = mutableListOf<String>()
    var bulletOrdered = false
    var inCode = false
    val code = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            out += MdB.Para(para.toString().trim())
            para.setLength(0)
        }
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) { out += MdB.Bullet(bullets.toList(), bulletOrdered); bullets.clear() }
    }

    while (i < lines.size) {
        val rawLine = lines[i]
        val line = rawLine.trim()

        if (inCode) {
            if (line.startsWith("```")) { inCode = false; out += MdB.Code(code.toString()); code.setLength(0); i++; continue }
            code.append(rawLine).append('\n'); i++; continue
        }

        if (line.startsWith("```")) { flushPara(); flushBullets(); inCode = true; code.setLength(0); i++; continue }

        if (line.isBlank()) { flushPara(); flushBullets(); i++; continue }

        val heading = headingOf(rawLine)
        if (heading != null) { flushPara(); flushBullets(); out += MdB.Heading(heading.first, stripInline(heading.second)); i++; continue }

        if (line.matches(Regex("^(---|\\*\\*\\*|___)\\s*$"))) { flushPara(); flushBullets(); out += MdB.Rule; i++; continue }

        val image = parseImage(rawLine)
        if (image != null) { flushPara(); flushBullets(); out += MdB.Image(image.first, image.second); i++; continue }

        val quote = stripQuote(rawLine)
        if (quote != null) { flushPara(); flushBullets(); out += MdB.Quote(quote); i++; continue }

        val bullet = bulletOf(rawLine)
        if (bullet != null) {
            flushPara()
            if (bullets.isEmpty()) bulletOrdered = bullet.second
            bullets += bullet.first
            i++; continue
        }

        // 普通文本：若在列表后则先结束列表
        flushBullets()
        if (para.isNotEmpty()) para.append(' ')
        para.append(rawLine.trim())
        i++
    }
    flushPara()
    flushBullets()
    if (inCode) out += MdB.Code(code.toString())
    return out
}

private fun headingOf(line: String): Pair<Int, String>? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("#")) return null
    var level = 0
    while (level < trimmed.length && trimmed[level] == '#') level++
    if (level > 6 || level == 0 || (level < trimmed.length && trimmed[level] != ' ')) return null
    return level to trimmed.drop(level).trim()
}

private fun stripQuote(line: String): String? {
    val t = line.trimStart()
    if (!t.startsWith(">")) return null
    return t.drop(1).trim()
}

private fun bulletOf(line: String): Pair<String, Boolean>? {
    val t = line.trim()
    val unordered = when {
        t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> true
        else -> false
    }
    if (unordered) return t.drop(2).trim() to false
    val ordered = Regex("^\\d+\\.\\s+(.*)$").find(t)
    if (ordered != null) return ordered.groupValues[1].trim() to true
    return null
}

private fun stripInline(s: String): String = s.replace("**", "").replace("`", "")

/** 识别独立一行的 markdown 图片：![alt](src) */
private fun parseImage(line: String): Pair<String, String>? {
    val t = line.trimStart()
    val m = Regex("^!\\[([^]]*)\\]\\(([^)\\s]+)\\)\\s*$").find(t)
    return m?.let { it.groupValues[2] to it.groupValues[1] }
}

/**
 * 渲染一张 markdown 图片。
 * 支持 data:image…;base64 内嵌图，以及相对 [baseDir] / 绝对 的本地图片文件。
 */
@Composable
private fun MarkdownImage(src: String, alt: String, baseDir: String?) {
    val state = remember(src, baseDir) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src, baseDir) {
        state.value = withContext(Dispatchers.IO) { decodeMdImage(src, baseDir) }
    }
    val bmp = state.value
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = alt,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
        )
    } else {
        Text(
            if (alt.isNotBlank()) alt else "（无法加载图片）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 解码 markdown 图片源：base64 data URI → Bitmap；否则本地文件（相对 baseDir 或绝对） */
private fun decodeMdImage(src: String, baseDir: String?): Bitmap? {
    return try {
        if (src.startsWith("data:image")) {
            val comma = src.indexOf(',')
            if (comma < 0) return null
            val bytes = Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
            if (bytes.isEmpty()) null else decodeSampledBytes(bytes)
        } else {
            var f = File(src)
            if (!f.isAbsolute && baseDir != null) f = File(baseDir, src)
            if (!f.exists()) null else decodeSampledFile(f)
        }
    } catch (e: Throwable) {
        null
    }
}

private fun decodeSampledFile(f: File): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, opts)
    opts.inJustDecodeBounds = false
    opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight)
    return BitmapFactory.decodeFile(f.absolutePath, opts)
}

private fun decodeSampledBytes(bytes: ByteArray): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    opts.inJustDecodeBounds = false
    opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private fun sampleSize(w: Int, h: Int): Int {
    var sample = 1
    var maxDim = maxOf(w, h)
    while (maxDim / sample > 1600) sample *= 2
    return sample
}

// ---------------- 行内样式（粗体 / 斜体 / 删除线 / 行内代码 / 链接） ----------------

private const val INLINE_CODE_BG = 0x20_78909C.toInt()

/** 行内链接色：与品牌色一致（[rich] 不是 Composable，拿不到 MaterialTheme） */
private const val INLINE_LINK_COLOR = 0xFF0E7C66.toInt()

/** 行内链接的样式：品牌色 + 下划线 */
private fun linkStyles() = TextLinkStyles(
    style = SpanStyle(color = Color(INLINE_LINK_COLOR), textDecoration = TextDecoration.Underline),
)

/** 解析出来的行内链接；[end] 是 `)` 之后的下标 */
private data class MdLink(val label: String, val url: String, val end: Int)

/**
 * 解析 `[文字](网址)`。
 * 只认同一行内、括号配平的最短形态；**落单时返回 null**——流式输出里半截的
 * `[文字](` 是常态，调用方必须把它当普通字符继续，不能吞字符。
 */
private fun parseInlineLink(text: String, start: Int): MdLink? {
    val labelEnd = text.indexOf(']', start + 1)
    if (labelEnd < 0) return null
    if (text.getOrNull(labelEnd + 1) != '(') return null
    val label = text.substring(start + 1, labelEnd)
    if (label.contains('\n')) return null
    var depth = 1
    var i = labelEnd + 2
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return MdLink(label, text.substring(labelEnd + 2, i), i + 1)
            }
            '\n' -> return null
        }
        i++
    }
    return null
}

/**
 * 行内样式解析：`**粗体**`、`*斜体*`、`~~删除线~~`、`` `行内代码` ``、`[文字](网址)`。
 *
 * 判定顺序即优先级：`**` / `~~` 这类双字符标记必须先于单字符判定，否则开头会被吃成斜体。
 * 行内代码里的一切都当字面量。
 */
internal fun rich(text: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    var italic = false
    var strike = false
    var code = false
    val sb = StringBuilder()
    fun flush() {
        if (sb.isEmpty()) return
        val t = sb.toString(); sb.setLength(0)
        val style = SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            fontFamily = if (code) FontFamily.Monospace else null,
            background = if (code) Color(INLINE_CODE_BG) else Color.Unspecified,
        )
        if (style == SpanStyle()) append(t) else withStyle(style) { append(t) }
    }
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '`' -> { flush(); code = !code; i += 1 }
            code -> { sb.append(text[i]); i += 1 }
            text.startsWith("**", i) -> { flush(); bold = !bold; i += 2 }
            text.startsWith("~~", i) -> { flush(); strike = !strike; i += 2 }
            text[i] == '[' -> {
                val link = parseInlineLink(text, i)
                if (link == null) {
                    sb.append(text[i]); i += 1
                } else {
                    flush()
                    withLink(LinkAnnotation.Url(url = link.url, styles = linkStyles())) { append(link.label) }
                    i = link.end
                }
            }
            text[i] == '*' -> { flush(); italic = !italic; i += 1 }
            else -> { sb.append(text[i]); i += 1 }
        }
    }
    flush()
}