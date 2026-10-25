package com.phoneagent.engine.prompt

import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.engine.PromptLang

/**
 * Agent Hook 管线（对齐 Operit 的 hooks 注册表设计）：
 *
 * 提示词与消息的"组装"在 [PromptAssembler]（区块选块 + 渲染，金样本锁定，不做扩展点），
 * 而"外围注入与出口改写"走这里 —— 注册表 + 顺序管线：
 *
 * - [SystemPromptHook]：任务级系统提示扩展。任务开始时逐个调用，返回的文本作为
 *   追加的 system 消息注入初始上下文（返回 null 表示本轮无内容）。
 *   典型用途：记忆注入、设备环境摘要、技能补充说明。
 * - [MessageTransformHook]：决策消息出口改写。每步决策发送前对整组消息做最后一次
 *   加工（脱敏、翻译、裁剪历史等），返回改写后的消息列表。
 *
 * 管线语义：
 * - 同类 hook 按 [order] 升序执行，order 相同按注册先后；
 * - hook 抛出的异常被吞掉并记录（hook 是增强件，不允许它打断主流程）；
 * - 注册表进程内生效（不落盘），内置 hook 在引擎初始化时注册。
 */
object AgentHookRegistry {

    /** hook 运行上下文：只带"此刻的事实"，不带引擎引用，hook 侧无法反向操控引擎 */
    data class HookContext(
        val task: String,
        val lang: PromptLang,
        val step: Int,
        val actionModeKey: String,
    )

    /** 任务级系统提示扩展：返回要追加的 system 文本；null 表示本轮无内容 */
    fun interface SystemPromptHook {
        val id: String get() = this::class.simpleName.orEmpty()
        val order: Int get() = 0
        suspend fun contribute(ctx: HookContext): String?
    }

    /** 决策消息出口改写：返回改写后的消息列表（可直接返回原列表表示不改） */
    fun interface MessageTransformHook {
        val id: String get() = this::class.simpleName.orEmpty()
        val order: Int get() = 0
        suspend fun transform(messages: List<ChatMessageDto>, ctx: HookContext): List<ChatMessageDto>
    }

    private val systemHooks = LinkedHashMap<String, SystemPromptHook>()
    private val messageHooks = LinkedHashMap<String, MessageTransformHook>()
    /** hook 异常上报口（引擎初始化时接线，默认丢弃） */
    @Volatile var onError: ((String, Throwable) -> Unit)? = null

    fun registerSystem(hook: SystemPromptHook) { systemHooks[hook.id] = hook }
    fun registerMessage(hook: MessageTransformHook) { messageHooks[hook.id] = hook }

    fun unregisterSystem(id: String) { systemHooks.remove(id) }
    fun unregisterMessage(id: String) { messageHooks.remove(id) }

    /** 已注册 hook 的 id 清单（排障/设置页展示用） */
    fun systemHookIds(): List<String> = systemHooks.keys.toList()
    fun messageHookIds(): List<String> = messageHooks.keys.toList()

    /**
     * 任务级系统提示扩展：按 order 依次收集非空贡献，逐条作为独立 system 消息返回。
     * 单个 hook 失败不影响其余（增强件不拖垮主流程）。
     */
    suspend fun runSystemHooks(ctx: HookContext): List<ChatMessageDto> =
        systemHooks.values.sortedBy { it.order }.mapNotNull { hook ->
            runCatching { hook.contribute(ctx) }
                .onFailure { onError?.invoke("system:${hook.id}", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = text)))
                }
        }

    /**
     * 决策消息出口管线：按 order 依次过全部 message hook。
     * 单个 hook 失败时保留上一个 hook 的输出继续走（同样不拖垮主流程）。
     */
    suspend fun runMessagePipeline(messages: List<ChatMessageDto>, ctx: HookContext): List<ChatMessageDto> {
        var current = messages
        messageHooks.values.sortedBy { it.order }.forEach { hook ->
            current = runCatching { hook.transform(current, ctx) }
                .onFailure { onError?.invoke("message:${hook.id}", it) }
                .getOrDefault(current)
        }
        return current
    }
}
