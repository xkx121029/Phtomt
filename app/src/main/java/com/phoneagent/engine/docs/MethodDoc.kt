package com.phoneagent.engine.docs

import kotlinx.coroutines.withTimeoutOrNull

/**
 * 方法文档：一件"事"怎么做成的判断依据，而不是"点哪里"的录像。
 *
 * 与既有 `AgentDoc`（云端执行文档，正文是一段 Markdown）的区别：
 * 这里按训练线约定的字段结构化返回，模型能直接拿来核对前提、判断成败、避开易错点；
 * [actions] 只是历史操作轨迹，仅作参考，不要求照做——页面往往已经改版，
 * 照着旧录像点反而更容易点错。
 */
data class MethodDoc(
    /** 服务端主键（如 `alipay.SetFontSizeLevel`），同 task_id 的文档在同步时按它覆盖 */
    val taskId: String = "",
    /** 一句话摘要：这份文档讲的是哪件事、适用什么场景 */
    val summary: String,
    /** 归一化 App 名（alipay / bilibili / notes …），用于执行期按前台应用加权 */
    val app: String = "",
    /** `proven`=有成功轨迹；`unproven`=只有失败轨迹，只能当参考思路，不能当可行性证明 */
    val evidenceStatus: String = "",
    /** 前提：动手前必须成立的条件（登录态、已进入某页、某项权限） */
    val preconditions: List<String> = emptyList(),
    /** 成功判断点：怎么确认这一步/这件事真的成了 */
    val successChecks: List<String> = emptyList(),
    /** 未完成信号：看起来做完了其实没做成（执行期自我否决用） */
    val notDoneWhen: List<String> = emptyList(),
    /** 判断点：遇到什么信号 → 怎么判断 → 可选路径 */
    val decisionPoints: List<DecisionPoint> = emptyList(),
    /** 易错点：最容易走错的地方与常见坑 */
    val pitfalls: List<String> = emptyList(),
    /** 收尾校验：做完之后要回头核对什么 */
    val wrapUpChecks: List<String> = emptyList(),
    /** 分支做法：不同情形下的替代路径 */
    val branches: List<Branch> = emptyList(),
    /** 历史操作轨迹，仅作参考（页面可能已变化，不可当成必做步骤） */
    val actions: List<String> = emptyList(),
) {
    /** 一个判断点：信号 → 判断理由 → 可选路径 */
    data class DecisionPoint(
        val trigger: String,
        val thinking: String,
        val options: List<String> = emptyList(),
    )

    /** 一条分支做法：适合什么情形、怎么做 */
    data class Branch(
        val name: String,
        val whenToUse: String,
        val approach: String,
    )
}

/** 规划阶段方法文档块的字符预算：够放下 2-3 篇的判断依据，又不至于把提示词堆成几 KB。 */
const val METHOD_DOC_PLAN_BUDGET = 700

/** 执行阶段每步方法文档块的字符预算：只放当前最相关的判断点，避免每步重复堆文档。 */
const val METHOD_DOC_STEP_BUDGET = 450

/**
 * 方法文档检索器：**可替换的实现点**。
 *
 * 主流程只依赖这个接口；线上检索接口就绪后换一个实现即可，调用方不用改。
 * 实现必须自己控制耗时（建议 [retrieve] 内部超时），并且**查不到就返回空列表**，
 * 不要抛异常、不要阻塞任务——"没有方法文档"是正常情况，按常规判断继续。
 */
interface MethodDocRetriever {
    suspend fun retrieve(task: String, foregroundPackage: String?, limit: Int = 3): List<MethodDoc>
}

/**
 * 线上接口未就绪时的空实现：永远返回空列表。
 *
 * 主流程据此走"没有对应方法，按常规判断"分支，不阻塞、不报错。
 */
object EmptyMethodDocRetriever : MethodDocRetriever {
    override suspend fun retrieve(task: String, foregroundPackage: String?, limit: Int): List<MethodDoc> = emptyList()
}

/**
 * 带超时护甲的检索入口：检索实现再怎么慢，也不能把任务卡在"动手前"。
 * 超时或异常一律按"没查到"处理，返回空列表。
 */
suspend fun MethodDocRetriever.retrieveSafely(
    task: String,
    foregroundPackage: String?,
    timeoutMs: Long,
    limit: Int = 3,
): List<MethodDoc> = runCatching {
    withTimeoutOrNull(timeoutMs) { retrieve(task, foregroundPackage, limit) }
}.getOrNull().orEmpty()
