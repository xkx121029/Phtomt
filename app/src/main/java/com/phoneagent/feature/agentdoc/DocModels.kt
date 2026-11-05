package com.phoneagent.feature.agentdoc

import kotlinx.serialization.Serializable

/**
 * 云端执行文档的索引（`/api/agent-docs/index.json`）。
 *
 * 只含元信息与匹配条件，正文按需单独 GET（一篇一个 Markdown），
 * 这样一次任务通常只下载命中的那 1~2 篇，而不是整个文档库。
 */
@Serializable
data class DocIndex(
    val version: String = "",
    val generatedAt: String = "",
    val count: Int = 0,
    val docs: List<DocMeta> = emptyList(),
)

/** 单篇文档的元信息：匹配条件（包名 / 应用名 / 关键词 / 意图）+ 版本与 ETag */
@Serializable
data class DocMeta(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val version: String = "",
    val priority: Int = 50,
    val intents: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val chars: Int = 0,
    val updatedAt: String = "",
    val etag: String = "",
)

/** 一篇已取回正文的文档，直接拼进提示词 */
data class AgentDoc(
    val id: String,
    val title: String,
    val body: String,
) {
    val chars: Int get() = body.length
}
