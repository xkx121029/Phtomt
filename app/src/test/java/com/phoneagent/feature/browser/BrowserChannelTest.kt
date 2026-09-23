package com.phoneagent.feature.browser

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置浏览器通道的判定测试（纯 JVM，注入假 [BrowserExecutor]，不碰 WebView）。
 *
 * 钉住三件事：
 * 1. **分流边界**：只有 6 个 browse_* 归本通道，别把 tap/open 抢过来；
 * 2. **参数校验发生在执行之前**：缺 uri / 缺 target / 方向非法时压根不该落到执行层；
 * 3. **只读护栏是"按不可逆性放行"**：普通点击照常执行，命中不可逆词表时**必须一步不落地拒掉**
 *    （断言执行层没被调用，这才是"拒绝"与"执行了再报错"的区别）。
 */
class BrowserChannelTest {

    /** 记录每一次调用的假执行器；全部返回成功，便于观察"到底有没有落下去" */
    private class FakeExecutor : BrowserExecutor {
        val calls = mutableListOf<String>()
        var result: BrowseResult = BrowseResult(true, "ok")

        override suspend fun open(url: String): BrowseResult { calls += "open:$url"; return result }
        override suspend fun read(): BrowseResult { calls += "read"; return result }
        override suspend fun click(by: String, value: String, guard: Boolean): BrowseResult {
            calls += "click:$by=$value,guard=$guard"; return result
        }
        override suspend fun input(by: String, value: String, text: String): BrowseResult {
            calls += "input:$by=$value=$text"; return result
        }
        override suspend fun scroll(direction: String): BrowseResult { calls += "scroll:$direction"; return result }
        override suspend fun back(): BrowseResult { calls += "back"; return result }
    }

    private fun channel(readOnly: Boolean, executor: FakeExecutor) =
        BrowserChannel(readOnly = { readOnly }, executor = executor)

    private fun click(value: String, by: String = "text", needsConfirmation: Boolean = false) = AgentIntent(
        intent = IntentType.BROWSE_CLICK,
        target = AgentIntentTarget(by = by, value = value),
        needsConfirmation = needsConfirmation,
    )

    // ---- 分流边界 ----

    @Test
    fun handles_只认六个browse意图() {
        val ch = channel(false, FakeExecutor())
        BrowserChannel.INTENTS.forEach { assertTrue(it, ch.handles(it)) }
        assertFalse(ch.handles(IntentType.TAP))
        assertFalse(ch.handles(IntentType.OPEN))
        assertFalse(ch.handles(IntentType.WRITE_DOC))
        assertFalse(ch.handles(IntentType.FINISH))
    }

    @Test
    fun label_与端侧步骤名同源() {
        val ch = channel(false, FakeExecutor())
        assertEquals("点击网页元素", ch.label(IntentType.BROWSE_CLICK))
    }

    // ---- 参数校验：不合格不落到执行层 ----

    @Test
    fun browseOpen_缺uri_回报缺参且不执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(AgentIntent(intent = IntentType.BROWSE_OPEN))
        assertTrue(out is BrowserChannel.Outcome.Missing)
        assertEquals("uri", (out as BrowserChannel.Outcome.Missing).field)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun browseOpen_非http网址_直接失败且不执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(
            AgentIntent(intent = IntentType.BROWSE_OPEN, uri = "content://media/1"),
        )
        assertTrue(out is BrowserChannel.Outcome.Failed)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun browseClick_缺target_回报缺参且不执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(AgentIntent(intent = IntentType.BROWSE_CLICK))
        assertTrue(out is BrowserChannel.Outcome.Missing)
        assertEquals("target", (out as BrowserChannel.Outcome.Missing).field)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun browseInput_缺text_回报缺参且不执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(
            AgentIntent(intent = IntentType.BROWSE_INPUT, target = AgentIntentTarget(by = "text", value = "搜索")),
        )
        assertTrue(out is BrowserChannel.Outcome.Missing)
        assertEquals("text", (out as BrowserChannel.Outcome.Missing).field)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun browseScroll_方向非法_失败且不执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(
            AgentIntent(intent = IntentType.BROWSE_SCROLL, direction = "diagonal"),
        )
        assertTrue(out is BrowserChannel.Outcome.Failed)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun browseScroll_方向缺省_默认向下() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(AgentIntent(intent = IntentType.BROWSE_SCROLL))
        assertTrue(out is BrowserChannel.Outcome.Ok)
        assertEquals(listOf("scroll:down"), ex.calls)
    }

    // ---- 只读护栏：该放行的放行 ----

    @Test
    fun 只读模式_普通点击照常执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(true, ex).execute(click("下一页"))
        assertTrue(out is BrowserChannel.Outcome.Ok)
        assertEquals(listOf("click:text=下一页,guard=true"), ex.calls)
    }

    @Test
    fun 只读模式_打开读正文滚动后退照常执行() = runTest {
        val ex = FakeExecutor()
        val ch = channel(true, ex)
        ch.execute(AgentIntent(intent = IntentType.BROWSE_OPEN, uri = "https://example.com"))
        ch.execute(AgentIntent(intent = IntentType.BROWSE_READ))
        ch.execute(AgentIntent(intent = IntentType.BROWSE_BACK))
        assertEquals(listOf("open:https://example.com", "read", "back"), ex.calls)
    }

    @Test
    fun 非只读模式_不可逆目标也放行且不开启脚本探针() = runTest {
        val ex = FakeExecutor()
        val out = channel(false, ex).execute(click("确认支付"))
        assertTrue(out is BrowserChannel.Outcome.Ok)
        // guard=false：非只读模式下无需注入词表探针
        assertEquals(listOf("click:text=确认支付,guard=false"), ex.calls)
    }

    @Test
    fun 填表单_只读模式不查词表() = runTest {
        val ex = FakeExecutor()
        val out = channel(true, ex).execute(
            AgentIntent(
                intent = IntentType.BROWSE_INPUT,
                target = AgentIntentTarget(by = "text", value = "收货地址"),
                text = "北京市朝阳区",
            ),
        )
        assertTrue(out is BrowserChannel.Outcome.Ok)
        assertEquals(listOf("input:text=收货地址=北京市朝阳区"), ex.calls)
    }

    // ---- 只读护栏：该拦的拦住，且不落到执行层 ----

    @Test
    fun 只读模式_命中不可逆词_拒绝且一步未执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(true, ex).execute(click("确认支付 23.00"))
        assertTrue(out is BrowserChannel.Outcome.Refused)
        assertTrue((out as BrowserChannel.Outcome.Refused).reason.contains("支付"))
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun 只读模式_AI自报不可逆_拒绝且一步未执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(true, ex).execute(click("下一页", needsConfirmation = true))
        assertTrue(out is BrowserChannel.Outcome.Refused)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun 只读模式_危险命名的CSS选择器_拒绝且一步未执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(true, ex).execute(click("#pay-btn", by = "id"))
        assertTrue(out is BrowserChannel.Outcome.Refused)
        assertTrue(ex.calls.isEmpty())
    }

    @Test
    fun 只读模式_普通CSS选择器_照常执行() = runTest {
        val ex = FakeExecutor()
        val out = channel(true, ex).execute(click("#search-input", by = "id"))
        assertTrue(out is BrowserChannel.Outcome.Ok)
        assertEquals(listOf("click:id=#search-input,guard=true"), ex.calls)
    }

    // ---- 执行层结果 → 通道结果 ----

    @Test
    fun 脚本侧探针拒绝_上报为Refused而非Failed() = runTest {
        val ex = FakeExecutor()
        ex.result = BrowseResult(false, "只读模式下命中不可逆词表，已拒绝点击", refused = true)
        // AI 给的文字本身不命中词表（放行到执行层），但脚本定位到的元素真实文字命中 → 由脚本侧拒绝
        val out = channel(true, ex).execute(click("第二条"))
        assertTrue(out is BrowserChannel.Outcome.Refused)
        assertEquals(listOf("click:text=第二条,guard=true"), ex.calls)
    }

    @Test
    fun 脚本侧普通失败_上报为Failed() = runTest {
        val ex = FakeExecutor()
        ex.result = BrowseResult(false, "浏览器还没打开")
        val out = channel(false, ex).execute(AgentIntent(intent = IntentType.BROWSE_READ))
        assertTrue(out is BrowserChannel.Outcome.Failed)
        assertEquals("浏览器还没打开", (out as BrowserChannel.Outcome.Failed).reason)
    }
}