package com.phoneagent.engine.execution

import com.phoneagent.device.a11y.AgentAccessibilityService
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
 * 动作执行后的取样节奏：**唯一定义点**。
 *
 * 点击（[ClickRunner]）与滑动/滚动/输入（[VerifiedClickExecutor]）共用同一套取样时刻——
 * 两处各写一份的后果很实际：同一条任务里"响应快的页面"一半在短轮询下秒判成功、
 * 另一半还在固定等待里干等 600ms，判定标准与耗时都不一致。
 */
internal object VerifyTiming {
    /** 累计等待时刻（毫秒）：首次取样就能确认的页面不必等满，两次取样都没变化才判失败 */
    val SAMPLE_DELAYS_MS = longArrayOf(200L, 500L)
}

/**
 * 带验证的执行器：执行动作并通过页面指纹对比确认是否生效。
 * 对应文档“第 4 层 带验证的点击执行器”。
 *
 * 执行前记录指纹 → 执行 → 短轮询取样 → 对比指纹。
 * 指纹变化 → 成功（一旦发现变化立即返回，不必等满整个观察窗）；不变 → 失败。
 *
 * 读不到页面时按"已执行但未确认"算成功：此时报失败会误触发"连续失败请求用户介入"，
 * 而下一步的观察本来就会给出真实结果。与 [ClickRunner] 同一口径。
 */
class VerifiedClickExecutor {

    /** 执行动作并验证页面是否发生变化 */
    suspend fun executeAndVerify(
        snapshot: ScreenSnapshot,
        perform: suspend () -> Boolean,
    ): VerifyResult {
        val before = PageFingerprint.computeMeaningful(snapshot)
        val executed = perform()
        if (!executed) return VerifyResult(false, "动作执行返回失败", before, before)

        var after = before
        var captured = false
        for (delayMs in VerifyTiming.SAMPLE_DELAYS_MS) {
            delay(delayMs)
            val afterSnapshot = AgentAccessibilityService.instance?.captureScreen() ?: break
            captured = true
            after = PageFingerprint.computeMeaningful(afterSnapshot)
            if (before != after) return VerifyResult(true, "页面已变化", before, after)
        }
        if (!captured) {
            return VerifyResult(true, "已执行；此刻读不到页面，变化未能确认，以下一步观察为准", "", "")
        }
        return VerifyResult(false, "页面未变化，动作可能未生效", before, after)
    }
}