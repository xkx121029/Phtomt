package com.phoneagent.device.provider

import com.phoneagent.provider.ProviderApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件契约判定的回归（R2 审计 P1-4）。
 *
 * 回归目标：旧判据 `needsInstall` 只比 versionName 且只判 `<`。号段被回拨过（200 万段 → 9xxx 段）之后：
 * - 装着**更新**的组件 → 判成"已是最新"，缺的方法被 binder 吞成 null；
 * - 装着**更旧**的组件 → 弹"需更新"，而安装器拒降级，用户原地打转。
 * 契约版本（AIDL apiVersion）相等才兼容，方向不同、修法不同。
 */
class ProviderContractTest {

    private val required = ProviderApi.API_VERSION

    @Test
    fun `版本相等才兼容`() {
        assertEquals(ProviderContract.Verdict.Compatible, ProviderContract.judge(required))
        assertNull(ProviderContract.mismatchReason(required))
    }

    @Test
    fun `组件更旧要点名更新组件`() {
        val verdict = ProviderContract.judge(required - 1)
        assertTrue("应判为组件旧：$verdict", verdict is ProviderContract.Verdict.ProviderOutdated)
        val reason = ProviderContract.mismatchReason(required - 1).orEmpty()
        assertTrue("原因里要给出可操作出路：$reason", reason.contains("更新服务组件"))
        assertTrue("原因里要带上两个版本号：$reason", reason.contains("${required - 1}") && reason.contains("$required"))
    }

    @Test
    fun `组件更新要提示升级本应用而不是装回旧组件`() {
        // 回拨号段的真实症状：装着的组件比本应用新，装回随包组件在系统侧是降级，会被拒绝
        val verdict = ProviderContract.judge(required + 2000000)
        assertTrue("应判为本应用旧：$verdict", verdict is ProviderContract.Verdict.AppOutdated)
        val reason = ProviderContract.mismatchReason(required + 2000000).orEmpty()
        assertTrue("原因里要说明该升级本应用：$reason", reason.contains("升级本应用"))
        assertTrue("原因里要说明装回旧组件会被拒：$reason", reason.contains("降级"))
    }

    @Test
    fun `读不到版本时不下兼容结论`() {
        assertEquals(ProviderContract.Verdict.Unknown, ProviderContract.judge(null))
        assertNull("未知不该编出一条原因", ProviderContract.mismatchReason(null))
    }
}
