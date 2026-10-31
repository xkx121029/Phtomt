package com.phoneagent.domain.model

import com.phoneagent.engine.execution.IntentResolver
import com.phoneagent.engine.execution.IntentTranslator
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 意图表闭合性自检（替代"把 [IntentType] 常量改成枚举"的低风险方案）。
 *
 * 为什么不做枚举化：`intent` 字段是 AI 的 JSON 输出，枚举化后所有历史/第三方模型输出都要经过
 * 一个宽容的字符串→枚举回落，反而把"未知意图"从显式的 `ALL` 校验挪进了隐式分支；
 * 而真正会出错的地方不是类型，而是**新增一个常量后忘了登记**，这正是下面用反射兜住的。
 *
 * 三条不变量：
 * 1. [IntentType.ALL] 与源码里声明的全部常量完全相等（漏登记 → 白名单校验会误判合法意图为非法）；
 * 2. [IntentType.TO_ACTION] 的键都在 [IntentType.ALL] 内（写错别字 / 复制粘贴错误）；
 * 3. 转译层分派表 ∪ [IntentType.VIA_OTHER_CHANNELS] 恰好覆盖 [IntentType.ALL]
 *    （两者有交集说明该意图被两处同时处理，容易出现行为不一致）。
 */
class IntentTypeCoverageTest {

    /** 反射拿到所有 const val 意图常量（Kotlin 的 const val 会编译成 static final String 字段） */
    private fun declaredIntentConstants(): Set<String> =
        IntentType::class.java.declaredFields
            .filter { it.type == String::class.java && Modifier.isStatic(it.modifiers) }
            .mapNotNull { runCatching { it.get(null) as? String }.getOrNull() }
            .toSet()

    private val translator: IntentTranslator = IntentTranslator(mockk(), mockk(), IntentResolver())

    @Test
    fun ALL_与源码声明的常量完全一致() {
        val declared = declaredIntentConstants()
        // 前置条件：反射确实读到了常量（否则下面的断言会假绿）
        assertTrue("未读到任何意图常量，反射失效", declared.size > 20)

        val missingFromAll = declared - IntentType.ALL
        assertTrue("常量未登记进 ALL（AI 输出这些意图会被判非法）：$missingFromAll", missingFromAll.isEmpty())

        val staleInAll = IntentType.ALL - declared
        assertTrue("ALL 里有不存在的常量（已改名/删除未清理）：$staleInAll", staleInAll.isEmpty())
    }

    @Test
    fun TO_ACTION的键都在ALL内() {
        val invalid = IntentType.TO_ACTION.keys - IntentType.ALL
        assertTrue("TO_ACTION 含非法意图键：$invalid", invalid.isEmpty())
    }

    @Test
    fun 每个意图都有确定的处理方() {
        val handled = translator.handledIntents
        val overlap = handled intersect IntentType.VIA_OTHER_CHANNELS
        assertTrue("同一意图被转译层与其他通道同时处理，行为会不一致：$overlap", overlap.isEmpty())

        val uncovered = IntentType.ALL - handled - IntentType.VIA_OTHER_CHANNELS
        assertTrue(
            "意图既没有转译策略也没登记到 VIA_OTHER_CHANNELS，AI 输出后只会得到未知意图：$uncovered",
            uncovered.isEmpty(),
        )

        // 反向：转译表里也不该出现 ALL 之外的类型（拼写错误会让策略永远命中不到）
        val stray = handled - IntentType.ALL
        assertTrue("转译表注册了非法意图：$stray", stray.isEmpty())
    }
}
