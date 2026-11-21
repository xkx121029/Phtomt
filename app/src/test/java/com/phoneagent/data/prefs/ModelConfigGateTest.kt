package com.phoneagent.data.prefs

import com.phoneagent.core.ai.ModelTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起跑判据：模型「验证失败」必须拦在起跑之前，「未验证」不许拦。
 *
 * 这条是 UX-01 的另一半——首页不再显示「已接入」只是不再撒谎，如果引擎照旧放行，
 * 用户会拿一个已知失效的 Key 等 45 秒，然后收到「决策链路异常」，原因还是藏着的。
 * 反过来，把「未验证」也拦掉会把「先离线配好、以后再验」这条合法路径掐死。
 */
class ModelConfigGateTest {

    private val baseUrl = "https://open.bigmodel.cn/api/paas/v4"
    private val apiKey = "sk-test-1234567890"
    private val model = "glm-4.7-flash"

    private fun settings(
        verifyOk: Boolean? = null,
        verifyError: String = "",
        verifyTargetKey: String = apiKey,
    ) = AppSettings.Settings(
        apiBaseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        modelVerifyTarget = if (verifyOk == null) "" else ModelTrust.fingerprint(baseUrl, model, verifyTargetKey),
        modelVerifyAt = if (verifyOk == null) 0L else 1_766_000_000_000L,
        modelVerifyOk = verifyOk == true,
        modelVerifyError = verifyError,
    )

    @Test
    fun `验证失败时起跑前就拦住，并带出真实原因`() {
        val problem = settings(verifyOk = false, verifyError = "密钥无效或已过期").configProblem
        assertTrue("验证失败必须拦，实际 problem=$problem", problem != null)
        assertTrue("拦下来的文案要带真实原因，实际=$problem", problem!!.contains("密钥无效或已过期"))
    }

    @Test
    fun `未验证不拦——允许先离线配好再验`() {
        assertNull("未验证不该阻断起跑", settings(verifyOk = null).configProblem)
    }

    @Test
    fun `验证通过后起跑不受影响`() {
        assertNull(settings(verifyOk = true).configProblem)
    }

    @Test
    fun `填了 Key 但没填地址仍然按未配置拦`() {
        val problem = AppSettings.Settings(apiBaseUrl = "", apiKey = apiKey).configProblem
        assertEquals("还没有填写接口地址。请到「设置 → 模型与接入」填写后再下达任务。", problem)
    }

    @Test
    fun `换过 Key 之后旧的失败记录不再拦——要按当前配置判`() {
        // 记录的指纹属于旧 Key：当前配置没被验过，属于「未验证」，不该被一条陈旧的失败拦住
        assertNull(settings(verifyOk = false, verifyTargetKey = "sk-an-old-key").configProblem)
    }
}
