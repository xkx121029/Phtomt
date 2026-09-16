package com.phoneagent.engine.execution.VerifiedClickExecutor

import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.engine.perception.PageFingerprint
import kotlinx.coroutines.delay

/**
 * 执行验证结果：动作是否真正改变了页面。
 */
data class VerifyResult(
    val success: Boolean,
    val reason: String,
    /** 执行前指纹 */
    val beforeFingerprint: String,
    /** 执行后指纹 */
    val afterFingerprint: String,
)

/**
 * 带验证的执行器：执行动作并通过页面指纹对比确认是否生效。
 * 对应文档“第 4 层 带验证的点击执行器”。
 *
 * 执行前记录指纹 → 执行 → 等待 → 对比指纹。
 * 指纹变化 → 成功；不变 → 失败（可升级重试）。
 */
class VerifiedClickExecutor(
    private val inner: com.phoneagent.device.a11y.ActionExecutor,
) {

    /** 执行动作并验证页面是否发生变化 */
    suspend fun executeAndVerify(
        snapshot: ScreenSnapshot,
        action: AgentAction,
        perform: suspend () -> Boolean,
    ): VerifyResult {
        val before = PageFingerprint.computeMeaningful(snapshot)
        val executed = perform()
        if (!executed) return VerifyResult(false, "动作执行返回失败", before, before)

        // 等待页面刷新
        delay(600)
        val afterSnapshot = AgentAccessibilityService.instance?.captureScreen() ?: snapshot
        val after = PageFingerprint.computeMeaningful(afterSnapshot)

        return if (before != after) {
            VerifyResult(true, "页面已变化", before, after)
        } else {
            VerifyResult(false, "页面未变化，动作可能未生效", before, after)
        }
    }
}