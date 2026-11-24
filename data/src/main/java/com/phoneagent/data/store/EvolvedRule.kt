package com.phoneagent.data.store

import kotlinx.serialization.Serializable

/**
 * 经验规则：从往次任务提炼出的**做法类经验**，与 [AiMemoryEntry] 的唯一区别是**带作用域**。
 *
 * 为什么需要作用域：AI 记忆是"全量注入"的——任何任务、任何页面都带上全部记忆，
 * 于是"在美团点餐要先登录"这种只对美团成立的经验，会在用户发微信时也挤进决策上下文，
 * 稀释当下真正重要的信息。规则记下"这条经验对谁成立"，装配时只注入当下命中的那几条。
 *
 * 作用域由 [com.phoneagent.engine.RuleScoper.inferScope] 自动推断，不新增任何用户配置界面。
 */
@Serializable
data class EvolvedRule(
    val id: Long = 0L,
    /** 一句话规则，要求可执行、可验证 */
    val content: String,
    /** 分类：preference | habit | tip | avoid | general */
    val kind: String = "general",
    /** 作用域类型，取值见 companion 的 `SCOPE_*` */
    val scopeKind: String = SCOPE_GLOBAL,
    /** 作用域取值：PACKAGE 时是包名，KEYWORD 时是关键词，GLOBAL 时为空 */
    val scopeValue: String = "",
    val confidence: Double = 0.7,
    /** 跨任务累计的命中次数。容量淘汰时先走命中少的 */
    val hitCount: Int = 0,
    /** 来源任务名 */
    val sourceTask: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
) {
    companion object {
        /** 全局：任何任务都成立（如"用户偏好简洁回复"） */
        const val SCOPE_GLOBAL = "GLOBAL"

        /** 限定应用：只在某个包里成立（如"美团首页的弹窗要先关掉"） */
        const val SCOPE_PACKAGE = "PACKAGE"

        /** 限定关键词：任务文本里出现该词才成立（如"黄焖鸡"） */
        const val SCOPE_KEYWORD = "KEYWORD"

        /** 库容量上限：超出按命中次数升序淘汰，长期没命中的先走 */
        const val MAX_RULES = 60

        /**
         * 容量兜底：超出上限时淘汰命中次数最少的（次数相同则淘汰更早创建的）。
         * 纯函数，可直接 JVM 单测。
         */
        fun trimToLimit(list: List<EvolvedRule>): List<EvolvedRule> {
            if (list.size <= MAX_RULES) return list
            val victims = list.sortedWith(compareBy({ it.hitCount }, { it.createdAt }))
                .take(list.size - MAX_RULES)
                .map { it.id }
                .toSet()
            return list.filterNot { it.id in victims }
        }
    }
}