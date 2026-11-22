package com.phoneagent.core.ai

/**
 * 端点适配：把用户填的「API 地址」规整成可用端点，并在发请求前拦掉会让 API Key 明文上路的配置。
 *
 * 全是纯字符串函数：不碰网络、不碰 Android，可以脱离 provider 单测。
 */
internal object AiEndpoints {

    /** 内网/本机常见后缀：mDNS、企业内网与家用路由器命名，未写协议时按 http 处理。 */
    private val LOCAL_HOST_SUFFIXES = listOf(
        ".local", ".localhost", ".lan", ".home", ".internal", ".intranet", ".corp", ".localdomain",
    )

    /**
     * 规范化 API 基地址：补协议、去末尾斜杠。
     *
     * 自建推理服务（Ollama、LM Studio、one-api、vLLM 等）常被填成 `192.168.1.5:8000/v1`
     * 或 `localhost:11434/v1`；而 OkHttp 的 `Request.url()` 要求带完整协议头，缺协议会直接抛异常。
     * 因此未写协议时按主机推断：本机/内网补 `http://`，公网域名补 `https://`；
     * 已写 `http://` 的地址原样保留 —— 明文流量默认被清单拦下（release），
     * 只有 debug 构建或用户显式打开「允许 http 端点」时才放行（见 AppSettings.Settings.configProblem）。
     */
    internal fun normalizeBaseUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return base
        if (base.startsWith("http://", true) || base.startsWith("https://", true)) return base
        return if (isLocalHost(base)) "http://$base" else "https://$base"
    }

    /**
     * 校验地址是否会把 API Key 明文发到公网：返回问题描述（null = 可以发）。
     *
     * 清单里 `usesCleartextTraffic=true` 是为了让自建服务可用，但它对公网域名一视同仁——
     * 用户把某个服务商地址误填成 `http://api.xxx.com/v1` 时，`Authorization: Bearer <key>`
     * 会整段明文上路，且不会有任何提示。这里在发请求前拦掉这类配置：
     * 内网/本机仍然允许 http（那正是明文放行的目的），公网一律要求 https。
     */
    internal fun cleartextProblem(baseUrl: String): String? {
        val base = baseUrl.trim()
        val plainHttp = base.startsWith("http://", ignoreCase = true)
        // 不是明文 http，或目标是本机/内网：都属于「明文放行」的本意，不拦
        if (!plainHttp || isLocalHost(base.substringAfter("://"))) return null
        val host = base.substringAfter("://").substringBefore('/').substringBefore(':')
        return "接口地址「$host」是公网地址却用了 http，API Key 会明文传输。" +
            "请把地址改成 https:// 开头；只有本机与内网服务才建议用 http。"
    }

    /** 从 URL 取出主机名，判断是否属于「本机/内网」：localhost、私有网段、单段主机名、内网后缀。 */
    private fun isLocalHost(url: String): Boolean {
        val authority = url.substringBefore('/').substringAfter('@')
        val host = (
            if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
            else authority.substringBefore(':')
            ).lowercase()
        if (host.isEmpty()) return false
        // IPv6 字面量：仅回环 / 链路本地 / ULA 视为内网
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fe80:") ||
                host.startsWith("fc") || host.startsWith("fd")
        }
        if (!host.contains('.')) return true    // 单段主机名，如 nas、myserver
        privateIpv4(host)?.let { return it }
        return LOCAL_HOST_SUFFIXES.any { host.endsWith(it) }
    }

    /** 私有 IPv4 判定；非 IPv4 字面量返回 null，交由域名分支处理。 */
    private fun privateIpv4(host: String): Boolean? {
        val nums = host.split('.').map { it.toIntOrNull() ?: return null }
        if (nums.size != 4 || nums.any { it !in 0..255 }) return null
        val (a, b) = nums[0] to nums[1]
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /**
     * 模型列表端点：与 [chatCompletionsUrl] 同样兼容各服务商填法差异 ——
     * 用户误把完整端点（`…/v1/chat/completions`）填进「API 地址」时先摘掉，再拼 `/models`。
     */
    internal fun modelsUrl(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl).removeSuffix("/chat/completions")
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /**
     * 规范化对话补全端点，兼容各服务商填法差异，避免 404：
     * - 末尾多余斜杠（`https://api.deepseek.com/v1/`）
     * - 误把完整端点填进「API 地址」（`https://api.deepseek.com/v1/chat/completions`）
     * - 不带版本段（`https://api.deepseek.com`，DeepSeek 原生支持）
     * - 未写协议（`192.168.1.5:8000/v1`、`localhost:11434/v1`，见 [normalizeBaseUrl]）
     */
    internal fun chatCompletionsUrl(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /**
     * 单项探测判定（只看状态码，不做 image/tool 关键词启发式 —— 5xx 的错误正文会被误判成"不支持"）：
     * - 2xx → `true`：该能力可用
     * - 415 / 422 → `false`：服务端明确拒绝了这种请求体（媒体类型 / 字段不被支持）
     * - 401 / 403 → `null`：鉴权问题（调用方已在整体层面拦截，不会走到这里）
     * - 400 / 5xx / 其他 → `null`：未测出（字段不认识、网关改写、服务端异常都落这里）
     */
    internal fun probeVerdict(status: Int): Boolean? = when {
        status in 200..299 -> true
        status == 415 || status == 422 -> false
        else -> null
    }

    /** 模型名相关线索：命中即认为"模型名/地址不对"，整体失败（而不是记成"该能力不支持"）。 */
    private val MODEL_NAME_ERROR_HINTS = listOf(
        "model not found", "model_not_found", "unknown model", "invalid model",
        "model does not exist", "no such model", "unsupported model",
        "模型不存在", "无效的模型", "模型名", "不存在的模型",
    )

    /** 是否是"模型名/地址错"：404 一律算；400 需正文出现模型名线索（避免把"不支持图片"误判）。 */
    internal fun isModelNameError(status: Int, body: String): Boolean {
        if (status == 404) return true
        if (status != 400) return false
        val lower = body.lowercase()
        return MODEL_NAME_ERROR_HINTS.any { lower.contains(it) }
    }
}
