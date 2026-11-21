package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.engine.perception.PageFingerprint
import kotlinx.coroutines.delay

/**
 * 启动后回读前台：观察窗内轮询页面，判定目标应用**是否真的到了前台**。
 *
 * 为什么不看接口返回值：部分 ROM 会静默拦截 Provider 的后台 startActivity
 * （不抛异常、窗口根本不出现），接口"成功"但应用没起来。只认返回值的话，AI 会一直
 * 以为"已打开"而反复用同一条无效通道（真机表现就是"不停用无障碍转译执行"）。
 * 因此启动阶梯每一级发完指令后都调用本函数，三级都确认不到才如实报失败。
 *
 * 三态（`Boolean?`）是本函数存在的全部理由，别把它折成两态：
 * - `true`  → 已确认到前台：唯一能报成功的分支；
 * - `false` → 整个观察窗都读得到页面，却始终没见到目标应用：这一级没交付，交给下一级通道；
 * - `null`  → 观察窗内**一次都没读到页面**（无障碍断开 / Provider 取树超时）：既不能确认生效，
 *   也没有证据说它没生效。折成 `false` 会把应用再拉起一遍，折成 `true` 就是假成功。
 *
 * 读取是注入的 [capture]（生产即 `AgentAccessibilityService.captureScreenAsync`）：本函数因此
 * 不碰 Android，可在 JVM 单测里用一串「不同时刻的页面」把三个出口逐个走通。
 */
internal object LaunchWatch {

    /**
     * launch 后等待目标页面出现的最长时间（毫秒）。
     * 从 800ms 提到 1200ms：冷启动普遍超过 800ms，观察窗太短会把"启动成功但还没画出来"
     * 误判成失败，进而触发不必要的通道切换。轮询一旦确认会立即返回，正常启动不受影响。
     */
    const val SETTLE_MS = 1200L

    /** launch 后轮询页面是否变化的间隔（毫秒） */
    const val POLL_MS = 100L

    /**
     * @param capture 回读当前页面；返回 null 表示这一次没读到（异常已由调用方收口）
     * @param targetPkg 目标应用包名
     * @param before 启动前的页面，用作"页面有没有变化"的基准
     * @return true=已确认前台；false=观察窗内读得到页面但没见到目标；null=一次都没读到页面
     */
    // 两个 continue 的语义不同（这次没读到 / 读到了但只有空壳），合并会把三态的分界抹掉
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun watch(
        capture: suspend () -> ScreenSnapshot?,
        targetPkg: String,
        before: ScreenSnapshot,
    ): Boolean? {
        val beforeFp = PageFingerprint.computeMeaningful(before)
        var observed = false
        var waited = 0L
        while (waited < SETTLE_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            val now = capture() ?: continue
            if (now.missingAccessibility) continue
            observed = true
            if (now.packageName == targetPkg || PageFingerprint.computeMeaningful(now) != beforeFp) return true
        }
        return if (observed) false else null
    }
}
