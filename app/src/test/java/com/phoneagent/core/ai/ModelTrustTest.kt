package com.phoneagent.core.ai

/**
 * 主模型四态的判定契约。
 *
 * 这些用例钉的是「首页凭什么说已接入」：判定错一次，就是在替用户编造一个成功信号（UX-01）。
 * 每条都从 [ModelTrust.resolve] 的入参直接构造场景，不经过任何 UI 或存储，
 * 所以能单独证明「哪种组合必须报哪一态」。
 */
class ModelTrustTest {

    private val baseUrl = "https://open.bigmodel.cn/api/paas/v4"
    private val apiKey = "sk-test-1234567890"
    private val model = "glm-4.7-flash"

    private fun verifiedRecord(ok: Boolean, at: Long = 1_766_000_000_000L, error: String = "") = Record(
        target = ModelTrust.fingerprint(baseUrl, model, apiKey),
        at = at,
        ok = ok,
        error = error,
    )

    private data class Record(val target: String, val at: Long, val ok: Boolean, val error: String)

    private fun resolve(record: Record?, key: String = apiKey, url: String = baseUrl, name: String = model) =
        ModelTrust.resolve(
            baseUrl = url,
            apiKey = key,
            model = name,
            verifiedTarget = record?.target ?: "",
            verifiedAt = record?.at ?: 0L,
            verifiedOk = record?.ok ?: false,
            verifyError = record?.error ?: "",
        )

    // ---- 未配置：三格有空缺就是没配，历史验证记录不许把它抬成「已验证」 ----

    @Test
    fun `没有 Key 就是未配置，即使曾经验证过`() {
        val snapshot = resolve(verifiedRecord(ok = true), key = "")
        assertEquals(ModelTrust.State.UNCONFIGURED, snapshot.state)
        assertFalse(snapshot.ready)
    }

    @Test
    fun `没有地址或模型名同样算未配置`() {
        assertEquals(ModelTrust.State.UNCONFIGURED, resolve(null, url = " ").state)
        assertEquals(ModelTrust.State.UNCONFIGURED, resolve(null, name = "").state)
    }

    // ---- 未验证：填齐了但没测过 ----

    @Test
    fun `填齐但从未验证只能是未验证`() {
        val snapshot = resolve(null)
        assertEquals(ModelTrust.State.UNVERIFIED, snapshot.state)
        assertFalse(snapshot.ready)
    }

    // ---- 已验证 ----

    @Test
    fun `同一份配置验证通过就是已验证`() {
        val snapshot = resolve(verifiedRecord(ok = true))
        assertEquals(ModelTrust.State.VERIFIED, snapshot.state)
        assertTrue(snapshot.ready)
        assertEquals(1_766_000_000_000L, snapshot.verifiedAt)
    }

    @Test
    fun `验证结论落盘后重新读取仍是已验证`() {
        // 模拟一次「验证 → 写到 DataStore → 重启后重新读出这四个原始值」
        val record = verifiedRecord(ok = true)
        val restored = Record(target = record.target, at = record.at, ok = record.ok, error = record.error)
        assertEquals(ModelTrust.State.VERIFIED, resolve(restored).state)
    }

    @Test
    fun `地址末尾斜杠与大小写差异不算换了配置`() {
        // 保存设置时地址会被 trim 掉末尾斜杠，指纹必须对这些写法差异免疫，
        // 否则用户一保存就把自己的「已验证」打回「未验证」
        val record = verifiedRecord(ok = true)
        assertEquals(
            ModelTrust.State.VERIFIED,
            resolve(record, url = "https://OPEN.bigmodel.cn/api/paas/v4/").state,
        )
    }

    // ---- 验证失败 ----

    @Test
    fun `验证失败要带出原因与时间`() {
        val snapshot = resolve(verifiedRecord(ok = false, error = "密钥无效或已过期"))
        assertEquals(ModelTrust.State.FAILED, snapshot.state)
        assertEquals("密钥无效或已过期", snapshot.error)
        assertEquals(1_766_000_000_000L, snapshot.failedAt)
        assertFalse(snapshot.ready)
    }

    @Test
    fun `失败原因缺失时不留空文案`() {
        assertEquals("连接测试未通过", resolve(verifiedRecord(ok = false, error = "  ")).error)
    }

    // ---- 改配置必须让旧结论作废 ----

    @Test
    fun `换 Key 后旧的成功结论作废`() {
        val snapshot = resolve(verifiedRecord(ok = true), key = "sk-another-key")
        assertEquals(ModelTrust.State.UNVERIFIED, snapshot.state)
    }

    @Test
    fun `换模型或换地址后旧的成功结论作废`() {
        val record = verifiedRecord(ok = true)
        assertEquals(ModelTrust.State.UNVERIFIED, resolve(record, name = "glm-4.6").state)
        assertEquals(ModelTrust.State.UNVERIFIED, resolve(record, url = "https://api.deepseek.com/v1").state)
    }

    @Test
    fun `改配置后连失败原因也一起作废`() {
        // 换了一个 Key，还挂着上一个 Key 的「密钥无效」会误导人
        val snapshot = resolve(verifiedRecord(ok = false, error = "密钥无效"), key = "sk-new-key")
        assertEquals(ModelTrust.State.UNVERIFIED, snapshot.state)
        assertEquals("", snapshot.error)
    }

    // ---- 指纹本身 ----

    @Test
    fun `指纹不包含明文 Key`() {
        val fingerprint = ModelTrust.fingerprint(baseUrl, model, apiKey)
        assertFalse("指纹里不该出现密钥明文：$fingerprint", fingerprint.contains(apiKey))
        assertFalse(fingerprint.contains("1234567890"))
    }

    @Test
    fun `指纹对同一份配置稳定对改动的部分敏感`() {
        val a = ModelTrust.fingerprint(baseUrl, model, apiKey)
        val b = ModelTrust.fingerprint(baseUrl, model, apiKey)
        assertEquals(a, b)
        assertNotEquals(a, ModelTrust.fingerprint(baseUrl, model, "sk-other"))
        assertNotEquals(a, ModelTrust.fingerprint("https://other.example/v1", apiKey, model))
    }

    // ---- 时间刻度 ----

    @Test
    fun `验证时间的说法按量级分档`() {
        val now = 1_766_000_000_000L
        assertEquals("", ModelTrust.describeAge(now, 0L))
        assertEquals("刚刚", ModelTrust.describeAge(now, now - 30_000L))
        assertEquals("5 分钟前", ModelTrust.describeAge(now, now - 5 * 60_000L))
        assertEquals("3 小时前", ModelTrust.describeAge(now, now - 3 * 3_600_000L))
        assertEquals("2 天前", ModelTrust.describeAge(now, now - 2 * 86_400_000L))
        // 时钟回拨不该出现「-1 分钟前」
        assertEquals("刚刚", ModelTrust.describeAge(now, now + 60_000L))
    }
}
