package com.phoneagent.engine

/**
 * 任务意图（Superagent 融合）：把「操控手机 / 纯对话」收成一条判定。
 *
 * 编码是独立入口（Coding 页），不走这里：用户在 Coding 页说的话只可能交给编码 agent，
 * 不需要也不该再被分流一次。合并形态下 Agent 页只有这两种处置
 * （进 ReAct 感知循环 / 直接作答），边界靠云端语义判定，不靠端侧词表。
 */
enum class TaskIntent { AGENT, CHAT }

/**
 * 意图判定提示词 + 输出解析（纯函数，可 JVM 单测）。
 *
 * 解析刻意做得宽容：模型可能只回一个词、回 `{"intent":"chat"}`、也可能带一句解释。
 * 但**宽容不等于瞎猜**——拿不到任何一个候选时返回 null，由调用方降级为 [TaskIntent.AGENT]
 * （操控链路是能力最完整的兜底，判错也还能补救；判成 chat 则会让一个要动手的任务直接答一句话）。
 */
object IntentRouter {

    private val WORDS = mapOf(
        TaskIntent.AGENT to "agent",
        TaskIntent.CHAT to "chat",
    )

    /** 严格匹配用的整段词表 */
    private val STRICT = WORDS.entries.associate { (intent, word) -> word to intent }

    /** 宽松匹配：只在这两个词里找，取最先出现的那个 */
    private val LOOSE = listOf(
        "chat" to TaskIntent.CHAT,
        "agent" to TaskIntent.AGENT,
    )

    /** `{"intent":"chat"}` 这类结构化回包 */
    private val JSON_INTENT = Regex("\"intent\"\\s*:\\s*\"([a-z]+)\"")

    /** 宽松匹配只看开头这一小段：避免模型把整段提示词复述回来时把词表命中也算进去 */
    private const val LOOSE_SCAN = 120

    fun systemPrompt(lang: PromptLang): String = when (lang) {
        PromptLang.CN -> """
            |你是任务分流器。读一条用户输入，判断它要哪一类处理，只输出一个词：
            |- agent：需要在这台手机上动手（打开应用、点击、输入、查资料、设闹钟、发消息、整理手机里的内容等）
            |- chat：只需要用文字回答，不需要碰手机界面（知识问答、解释概念、翻译、写文章、闲聊）
            |
            |判断依据是用户真正想达成的结果，不要被单个字面词带偏。
            |无法确定时输出 agent。
            |只输出这个词，不要解释、不要标点、不要引号。
        """.trimMargin()

        PromptLang.EN -> """
            |You are a task router. Read one user input and decide which handling it needs. Output exactly one word:
            |- agent: the request needs action on this phone (open apps, tap, type, look things up, set alarms, send messages, organize local content)
            |- chat: a text answer is enough, no phone UI involved (questions, explanations, translation, writing, small talk)
            |
            |Judge by the outcome the user actually wants, not by a single literal keyword.
            |When unsure, output agent.
            |Output only that word. No explanation, no punctuation, no quotes.
        """.trimMargin()
    }

    /** 解析模型输出；拿不到明确判定时返回 null（调用方据此降级为 [TaskIntent.AGENT]） */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun parse(raw: String): TaskIntent? {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return null
        STRICT[text]?.let { return it }
        JSON_INTENT.find(text)?.groupValues?.getOrNull(1)?.let { STRICT[it]?.let { i -> return i } }
        val head = text.take(LOOSE_SCAN)
        return LOOSE
            .mapNotNull { (word, intent) -> head.indexOf(word).takeIf { it >= 0 }?.let { it to intent } }
            .minByOrNull { it.first }
            ?.second
    }

    /** 日志用的人话标签 */
    fun label(intent: TaskIntent): String = when (intent) {
        TaskIntent.AGENT -> "操控手机"
        TaskIntent.CHAT -> "对话直答"
    }
}
