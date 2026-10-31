package com.phoneagent.core.log

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runCatchingLogged] 测试。
 *
 * 它替换的是执行链路里的裸 `runCatching`：行为必须与 `runCatching` 完全一致
 * （否则调用方满地的 `getOrNull() / getOrDefault()` 会跟着变），
 * 唯一的增量是在失败时留一条日志。这两件事分别用下面两组用例锁住。
 */
class CatchingLogTest {

    @Test
    fun runCatchingLogged_成功_原样返回结果() {
        assertEquals(42, runCatchingLogged("Test", "取值") { 42 }.getOrNull())
    }

    @Test
    fun runCatchingLogged_失败_仍返回失败结果而不是抛出() {
        // 关键契约：异常必须继续被吞成 Result.failure，调用方才能照常降级
        val result = runCatchingLogged("Test", "执行 shell") { error("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun runCatchingLogged_失败_可以继续用getOrDefault降级() {
        assertEquals("兜底值", runCatchingLogged("Test", "截图") { error("boom") }.getOrDefault("兜底值"))
    }

    @Test
    fun runCatchingLogged_协程取消_不按故障处理但仍向上传递() {
        // 用户点停止会让挂起点抛 CancellationException：这不是故障，调用方仍需看到失败结果
        val result = runCatchingLogged("Test", "等待用户") { throw CancellationException("任务已停止") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
