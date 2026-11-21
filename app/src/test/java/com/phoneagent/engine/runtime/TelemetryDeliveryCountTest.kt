package com.phoneagent.engine.runtime

import android.content.Context
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.engine.execution.Delivery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 交付分类埋点：三种失败各占一个桶。
 *
 * 回归目标（HPA 决策）：`TelemetryRecorder.recordDelivery` 过去对
 * `DELIVERED_UNCONFIRMED` 什么都不做（注释写着"等看板出现再加独立计数器"）。
 * 把「未确认」从布尔里分出来之后，如果遥测看不见它，就无法判断这条路径在真机上
 * 是在变好还是变坏；折进 `noEffect` 又会让「没看见」被记成「看见了没效果」。
 */
class TelemetryDeliveryCountTest {

    private fun recorder(): Pair<TelemetryRecorder, EngineStore> {
        val store = EngineStore()
        val recorder = TelemetryRecorder(
            appContext = mockk<Context>(relaxed = true),
            memory = mockk<MemoryStore>(relaxed = true),
            store = store,
        )
        return recorder to store
    }

    @Test
    fun `未确认单独计数_不混进没生效也不混进没送达`() {
        val (recorder, store) = recorder()

        recorder.recordDelivery(Delivery.DELIVERED_UNCONFIRMED)
        recorder.recordDelivery(Delivery.DELIVERED_UNCONFIRMED)
        recorder.recordDelivery(Delivery.DELIVERED_NO_EFFECT)
        recorder.recordDelivery(Delivery.NOT_DELIVERED)

        val metrics = store.metrics.value
        assertEquals(2, metrics.unconfirmedCount)
        assertEquals("未确认不是「看见了没效果」", 1, metrics.noEffectCount)
        assertEquals(1, metrics.notDeliveredCount)
    }

    @Test
    fun `成功的步不占任何失败桶`() {
        val (recorder, store) = recorder()

        recorder.recordDelivery(Delivery.EFFECTIVE)

        val metrics = store.metrics.value
        assertEquals(0, metrics.unconfirmedCount)
        assertEquals(0, metrics.noEffectCount)
        assertEquals(0, metrics.notDeliveredCount)
    }
}
