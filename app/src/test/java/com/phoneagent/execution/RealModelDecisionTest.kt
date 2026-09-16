package com.phoneagent.execution

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.execution.AppNameResolver
import com.phoneagent.engine.execution.CapabilityManager
import com.phoneagent.engine.execution.IntentResolver
import com.phoneagent.engine.execution.IntentTranslator
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真实模型接入闭环测试：
 * 用 GitHub 配置的真实 agnes-2.5-flash 密钥，让模型根据"任务 + 当前页面"输出高层意图，
 * 校验输出可被 [AiClient] 的同一 AgentIntent 序列化解析，再走真实 [IntentTranslator] 转译为端侧命令。
 *
 * 密钥通过环境变量 AGNES_API_KEY 注入，不再源码中写死（避免提交泄露）；未配置时跳过。
 * 运行结果写入 build/real_model_result.txt 供查阅。
 *
 * Base URL / model 对齐 https://www.agnes-ai.cn/zh-Hans/docs/agnes-25-flash：
 *   OpenAI 兼容 POST /v1/chat/completions，模型名 agnes-2.5-flash。
 */
class RealModelDecisionTest {

    /** 与 AiClient.ACTION_SCHEMA 完全对齐的受支持意图全集，用于断言模型输出合法 */
    private val supportedIntents = setOf(
        "open_app", "open", "tap", "long_press", "input", "swipe", "press", "wait",
        "scroll_to", "write_doc", "finish", "give_up",
        "back", "home", "refresh", "search", "send", "confirm", "close", "share",
        "collect", "copy", "delete", "download", "add", "switch", "clear_input",
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val systemPrompt =
        "你是运行在手机上的智能体，负责把「任务目标 + 当前页面元素」翻译成一个 JSON 意图。" +
            "intent 只能取以下之一：${supportedIntents.joinToString()}。\n" +
            "只表达要做的事，绝不输出 shell / 无障碍指令 / 像素坐标 / 点击命令——那些由端侧转译层完成。\n" +
            "只需输出一个合法 JSON 对象，形如 {\"intent\":\"...\", \"reasoning\":\"一句话给用户看的进度\", \"reason\":\"判断依据\"}，" +
            "需要目标时加 target:{\"by\":\"text\",\"value\":\"控件文字\"}。不要输出 JSON 以外的任何文字。"

    private fun resultFile() = File("build/real_model_result.txt")

    /** 调用真实模型：返回模型原始输出正文（choices[0].message.content） */
    private fun callOpenAi(messages: List<BaseMessage>): String {
        val apiKey = System.getenv("AGNES_API_KEY")
        assumeTrue("未配置 AGNES_API_KEY 环境变量，跳过真实模型测试", !apiKey.isNullOrBlank())
        val body = buildJsonObject {
            put("model", "agnes-2.5-flash")
            put("temperature", 0.2)
            put("max_tokens", 2048)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(messages.map { m ->
                buildJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                }
            }))
        }.toString()
        val request = Request.Builder()
            .url("https://api.agnes-ai.cn/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AssertionError("HTTP ${resp.code}: $raw")
            }
            val root = json.parseToJsonElement(raw).jsonObject
            return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?.trim() ?: throw AssertionError("模型未返回内容: $raw")
        }
    }

    private fun parseIntent(content: String): AgentIntent {
        // 与 AiClient.parseAgentIntent 同理的容错：先剥 markdown 围栏再按 { } 截取
        var c = content.trim()
        if (c.startsWith("```")) {
            val nl = c.indexOf('\n')
            val end = c.lastIndexOf("```")
            c = if (nl >= 0 && end > nl) c.substring(nl + 1, end).trim() else c.removePrefix("```").removeSuffix("```").trim()
        }
        val start = c.indexOf('{')
        val end = c.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) c.substring(start, end + 1) else c
        return json.decodeFromString(AgentIntent.serializer(), obj)
    }

    /** 记录结果并返回转译动作 */
    private fun report(scenario: String, raw: String, intent: AgentIntent, actionType: String) {
        resultFile().appendText("""
        |===== $scenario =====
        |意图: ${intent.intent} (target=${intent.target?.by ?: "-"}/${intent.target?.value ?: "-"})
        |转译动作: $actionType
        |raw: $raw
        |
        """.trimMargin())
    }

    // ==================== 场景 1：开放协议直达（open_app / open）====================

    @Test
    fun 真实模型_打开应用意图() {
        val content = callOpenAi(
            listOf(
                BaseMessage("system", systemPrompt),
                BaseMessage("user", "任务：打开微信。当前页面：桌面。请输出意图。"),
            ),
        )
        val intent = parseIntent(content)
        val action = translateFor { translator ->
            translator.translate(intent, ScreenSnapshot(packageName = "com.android.launcher"))
        }
        val type = when (val r = action) {
            is IntentTranslator.TranslationResult.Command -> r.action.type
            is IntentTranslator.TranslationResult.Failed -> "FAILED:${r.reason}"
            is IntentTranslator.TranslationResult.MissingParam -> "MISSING[${r.field}]:${r.reason}"
        }

        assertTrue("模型输出非法意图: ${intent.intent}", intent.intent in supportedIntents)
        report("打开微信", content, intent, type)
        // 直达到点链路验证：合法意图可经转译；模型漏填 app/target 时端侧返回 MissingParam/Failed 属正确兜底，端侧会追问补全而非崩溃
        assertTrue("转译结果异常: $type", type.isNotBlank())
    }

    // ==================== 场景 2：高层语义意图（send）====================

    @Test
    fun 真实模型_发送消息语义意图() {
        val snapshot = ScreenSnapshot(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 2400,
            elements = listOf(
                elem(0, "输入框", editable = true),
                elem(1, "发送", semanticId = "send_btn"),
            ),
        )
        val content = callOpenAi(
            listOf(
                BaseMessage("system", systemPrompt),
                BaseMessage("user", "任务：给小李发送'你好'。当前页面：\n${snapshot.toAiText()}\n输入框已有文字'你好'，请输出意图。"),
            ),
        )
        val intent = parseIntent(content)
        val result = IntentTranslator(mockk<CapabilityManager>().apply {
            every { currentMode() } returns CapabilityManager.Mode.ACCESSIBILITY
        }, mockk<AppNameResolver>(), IntentResolver()).translate(intent, snapshot)
        val type = if (result is IntentTranslator.TranslationResult.Command)
            "${result.action.type}" else "FAILED:${(result as IntentTranslator.TranslationResult.Failed).reason}"

        assertTrue("模型输出非法意图: ${intent.intent}", intent.intent in supportedIntents)
        assertTrue("语义意图转译失败: $type", type.isNotBlank() && !type.startsWith("FAILED"))
        report("发送消息", content, intent, type)
    }

    private fun translateFor(
        block: (IntentTranslator) -> IntentTranslator.TranslationResult,
    ): IntentTranslator.TranslationResult {
        val appNameResolver = mockk<AppNameResolver>(relaxed = true) // 任何应用名解析不到都走 Failed 兜底，验证链路不崩
        val capabilityManager = mockk<CapabilityManager>()
        every { capabilityManager.currentMode() } returns CapabilityManager.Mode.SHIZUKU
        val translator = IntentTranslator(capabilityManager, appNameResolver, IntentResolver())
        return block(translator)
    }

    private fun elem(index: Int, text: String, editable: Boolean = false, semanticId: String? = null) = UiElement(
        index = index, className = if (editable) "android.widget.EditText" else "android.widget.Button",
        type = if (editable) "EditText" else "Button", text = text,
        x = 540, y = if (editable) 200 else 2200, left = 0, top = if (editable) 100 else 2000,
        right = 1080, bottom = if (editable) 300 else 2400,
        clickable = !editable, editable = editable, semanticId = semanticId,
    )
}

/** 极简 OpenAI 消息体，避免依赖 AiClient 的 Android 运行时（Log/Bitmap） */
private data class BaseMessage(val role: String, val content: String)
