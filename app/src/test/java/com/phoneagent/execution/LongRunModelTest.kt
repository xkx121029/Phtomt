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
 * 长线任务闭环测试：让真实 agnes-2.5-flash 逐步决策，走一个多阶段的"团购下单"流程，
 * 每步把模型输出的意图经真实 [IntentTranslator] 转译成端侧命令，模拟执行并推进到下一阶段，
 * 直到模型输出 finish。目标是尽量覆盖转译层/命令层更多"段点"。
 *
 * 通过环境变量 AGNES_API_KEY 注入密钥（未配置自动跳过），运行结果写入 build/real_run_result.txt。
 */
class LongRunModelTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val systemPrompt =
        "你是一个运行在手机上的智能体。用户给了一个总目标，当前正处于其中的某个步骤。\n" +
            "step 表示当前做到第几步。请基于「当前页面元素」决定本步要做的唯一动作，只输出一个 JSON 意图。\n" +
            "intent 只能取：open_app, open, tap, long_press, input, swipe, press, wait, scroll_to, write_doc, finish, give_up, back, home, refresh, search, send, confirm, close, share, collect, copy, delete, download, add, switch, clear_input。\n" +
            "只表达要做的事，绝不输出 shell/无障碍指令/像素坐标/点击命令，那些由端侧转译。\n" +
            "输出形如 {\"intent\":\"...\",\"reasoning\":\"给用户看的一句中文\",\"reason\":\"依据\"}，需要目标时追加 target:{\"by\":\"text\",\"value\":\"控件文字\"}，需要文字时追加 text。\n" +
            "职责边界：当且仅当页面元素已明确表明「任务全部完成」时才输出 finish；其他情况必须完成当前屏幕上的动作，绝不提前 finish。"

    private val task =
        "总目标：在团购App完成一次奶茶下单并收藏。流程：打开团购App → 搜索'奶茶' → 查看第一个商品 → 加入购物车并确认 → 收藏该商品 → 返回首页。请按当前页面逐步推进，每步只做一步。"

    private data class Stage(val name: String, val elements: List<UiElement>)

    private fun call(content: String): String {
        val apiKey = System.getenv("AGNES_API_KEY")
        assumeTrue("未配置 AGNES_API_KEY，跳过长线真实模型测试", !apiKey.isNullOrBlank())
        val messages = JsonArray(
            listOf(
                buildJsonObject { put("role", "system"); put("content", systemPrompt) },
                buildJsonObject { put("role", "user"); put("content", content) },
            ),
        )
        val body = buildJsonObject {
            put("model", "agnes-2.5-flash")
            put("temperature", 0.2)
            put("max_tokens", 2048)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", messages)
        }.toString()
        val request = Request.Builder()
            .url("https://api.agnes-ai.cn/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AssertionError("HTTP ${resp.code}: $raw")
            return json.parseToJsonElement(raw).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?.trim() ?: throw AssertionError("模型未返回内容: $raw")
        }
    }

    private fun parseIntent(content: String): AgentIntent {
        var c = content.trim()
        if (c.startsWith("```")) {
            val nl = c.indexOf('\n'); val end = c.lastIndexOf("```")
            c = if (nl >= 0 && end > nl) c.substring(nl + 1, end).trim() else c.removePrefix("```").removeSuffix("```").trim()
        }
        val s = c.indexOf('{'); val e = c.lastIndexOf('}')
        return json.decodeFromString(AgentIntent.serializer(), if (s >= 0 && e > s) c.substring(s, e + 1) else c)
    }

    private fun elem(index: Int, text: String, semanticId: String? = null) = UiElement(
        index = index, className = "android.widget.Button", type = "Button", text = text,
        x = 540, y = 1200, left = 0, top = 1000, right = 1080, bottom = 1400,
        clickable = true, semanticId = semanticId,
    )

    @Test
    fun 长线任务_逐步决策_覆盖多命令段点_直至完成() {
        val capability = mockk<CapabilityManager>()
        every { capability.currentMode() } returns CapabilityManager.Mode.ACCESSIBILITY
        val translator = IntentTranslator(capability, mockk<AppNameResolver>(relaxed = true), IntentResolver())

        val stages = listOf(
            Stage("桌面", listOf(elem(0, "团购App"))),
            Stage("App首页", listOf(elem(0, "搜索商家/商品", "search_box"), elem(1, "分类归纳"))),
            Stage("搜索结果", listOf(elem(0, "珍珠奶茶·中杯", semanticId = null), elem(1, "柠檬茶"))),
            Stage("商品详情", listOf(elem(0, "加入购物车", "confirm_btn"), elem(1, "收藏", "collect_btn"), elem(2, "返回", "back_btn"))),
            Stage("确认弹窗", listOf(elem(0, "确定", "dlg_allow"), elem(1, "取消", "dlg_dismiss"))),
            Stage("已加购详情", listOf(elem(0, "去结算", "checkout_btn"), elem(1, "收藏", "collect_btn"))),
            Stage("我的收藏", listOf(elem(0, "返回首页", "back_btn"))),
            Stage("首页·完成", listOf(elem(0, "已完成"))),
        )

        val result = StringBuilder("【长线任务真实模型闭环】\n")
        val covered = linkedSetOf<String>()
        val coveredIntents = linkedSetOf<String>()
        var finished = false
        var finalIntent = ""

        stages.forEachIndexed { step, stage ->
            val snapshot = ScreenSnapshot(packageName = "com.meituan", screenWidth = 1080, screenHeight = 2400, elements = stage.elements)
            val prompt = "step=$step，页面：${stage.name}。${stage.elements.joinToString { it.text ?: "" }}。$task 请输出本步意图。"
            val raw = call(prompt)
            val intent = parseIntent(raw)
            finalIntent = intent.intent
            coveredIntents += intent.intent

            val typeResult = if (intent.intent == "finish") {
                "TASK_DONE(finish)"
            } else {
                when (val r = translator.translate(intent, snapshot)) {
                    is IntentTranslator.TranslationResult.Command -> r.action.type
                    is IntentTranslator.TranslationResult.Failed -> "FAILED:${r.reason}"
                    is IntentTranslator.TranslationResult.MissingParam -> "MISSING[${r.field}]:${r.reason}"
                }
            }
            covered += typeResult
            result.appendLine("step$step [${stage.name}] intent=${intent.intent} → $typeResult\n  raw: $raw")

            if (intent.intent == "finish" || intent.intent == "give_up") { finished = intent.intent == "finish"; return@forEachIndexed }
        }

        File("build/real_run_result.txt").writeText(result.toString())
        File("build/real_run_result.txt").appendText(
            "\n覆盖命令段点(${covered.size} 个): ${covered.joinToString(" | ")}\n" +
            "覆盖意图(${coveredIntents.size} 个): ${coveredIntents.joinToString(" | ")}\n",
        )

        assertTrue("任务未自然走到 finish（末尾意图=$finalIntent）", finished)
        assertTrue("覆盖命令段点过少: ${covered.joinToString(", ")}", covered.size >= 4)
        assertTrue("覆盖意图段点过少: ${coveredIntents.joinToString(", ")}", coveredIntents.isNotEmpty())
    }
}
