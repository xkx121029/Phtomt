package com.phoneagent.execution

import com.phoneagent.engine.execution.AppNameResolver
import com.phoneagent.engine.execution.CapabilityManager
import com.phoneagent.engine.execution.CapabilityManager.Mode
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.execution.IntentResolver
import com.phoneagent.engine.execution.IntentTranslator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 策略化拆分后的补充用例：针对只读横切守卫、默认值分支、滚动方向分支、深链字段透传与定位失败路径。
 * 通过公开 [IntentTranslator.translate] 断言，验证重构未改变行为约定。
 */
class IntentTranslatorStrategyTest {

    private lateinit var capabilityManager: CapabilityManager
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var translator: IntentTranslator

    @Before
    fun setUp() {
        capabilityManager = mockk()
        appNameResolver = mockk()
        translator = IntentTranslator(capabilityManager, appNameResolver, IntentResolver())
    }

    private fun mode(m: Mode) = every { capabilityManager.currentMode() } returns m

    private fun elem(
        index: Int,
        text: String,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 60,
        semanticId: String? = null,
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = "Button",
        text = text,
        x = (left + right) / 2,
        y = (top + bottom) / 2,
        left = left, top = top, right = right, bottom = bottom,
        clickable = true,
        semanticId = semanticId,
    )

    private fun snapshot(vararg elements: UiElement) = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    private fun command(intent: AgentIntent, snapshot: ScreenSnapshot): AgentAction {
        val result = translator.translate(intent, snapshot)
        assertTrue("期望转译成功，实际: ${result}", result is IntentTranslator.TranslationResult.Command)
        return (result as IntentTranslator.TranslationResult.Command).action
    }

    private fun failure(intent: AgentIntent, snapshot: ScreenSnapshot): String {
        val result = translator.translate(intent, snapshot)
        assertTrue("期望转译失败", result is IntentTranslator.TranslationResult.Failed)
        return (result as IntentTranslator.TranslationResult.Failed).reason
    }

    @Test
    fun 只读模式_滑动也被拒绝() {
        mode(Mode.READONLY)
        val reason = failure(AgentIntent(intent = IntentType.SWIPE, direction = "up"), snapshot())
        assertTrue(reason.contains("只读模式"))
    }

    @Test
    fun remember_转译为记忆写入动作() {
        mode(Mode.SHIZUKU)
        val action = command(
            AgentIntent(intent = IntentType.REMEMBER, text = "用户喜欢简洁界面", summary = "preference"),
            snapshot(),
        )
        assertEquals(ActionType.REMEMBER, action.type)
        assertEquals("用户喜欢简洁界面", action.text)
        assertEquals("preference", action.summary)
    }

    @Test
    fun remember_只读模式也放行() {
        mode(Mode.READONLY)
        // remember 纯本地写库、不触碰设备，只读模式不应拒绝（与 wait/write_doc 同列）
        val action = command(AgentIntent(intent = IntentType.REMEMBER, text = "常去菜市场买菜"), snapshot())
        assertEquals(ActionType.REMEMBER, action.type)
    }

    @Test
    fun remember_缺内容_返回缺参而非失败() {
        mode(Mode.SHIZUKU)
        val result = translator.translate(AgentIntent(intent = IntentType.REMEMBER), snapshot())
        assertTrue("缺 text 应返回 MissingParam", result is IntentTranslator.TranslationResult.MissingParam)
    }

    @Test
    fun device_query_转译为端侧查询动作() {
        mode(Mode.SHIZUKU)
        val action = command(
            AgentIntent(intent = IntentType.DEVICE_QUERY, kind = "apps", filter = "相机"),
            snapshot(),
        )
        assertEquals(ActionType.DEVICE_QUERY, action.type)
        assertEquals("apps", action.text)
        assertEquals("相机", action.summary)
    }

    @Test
    fun device_query_缺kind默认全部() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.DEVICE_QUERY), snapshot())
        assertEquals(ActionType.DEVICE_QUERY, action.type)
        assertEquals("all", action.text)
    }

    @Test
    fun device_query_非法kind_转译失败() {
        mode(Mode.SHIZUKU)
        val reason = failure(AgentIntent(intent = IntentType.DEVICE_QUERY, kind = "contacts"), snapshot())
        assertTrue("应提示可选类别，实际: $reason", reason.contains("不支持 kind=contacts"))
    }

    @Test
    fun device_query_只读模式也放行() {
        mode(Mode.READONLY)
        // 纯本地读取、不触碰设备，只读模式不应拒绝（与 remember/wait 同列）
        val action = command(AgentIntent(intent = IntentType.DEVICE_QUERY, kind = "battery"), snapshot())
        assertEquals(ActionType.DEVICE_QUERY, action.type)
    }

    @Test
    fun finish_未给摘要_默认任务完成() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.FINISH), snapshot())
        assertEquals(ActionType.TASK_DONE, action.type)
        assertEquals("任务完成", action.summary)
    }

    @Test
    fun giveUp_未给原因_默认已放弃任务() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.GIVE_UP), snapshot())
        assertEquals(ActionType.TASK_DONE, action.type)
        assertEquals("已放弃任务", action.summary)
    }

    @Test
    fun scrollTo_目标含下方_向下滚() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "列表"))
        val action = command(
            AgentIntent(intent = IntentType.SCROLL_TO, target = AgentIntentTarget("text", "下方的入口")),
            s,
        )
        assertEquals(ActionType.SCROLL, action.type)
        assertEquals("down", action.direction)
    }

    @Test
    fun open_透传页面索引() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.OPEN, app = "QQ", page = 3), snapshot())
        assertEquals(ActionType.OPEN, action.type)
        assertEquals(3, action.page)
    }

    @Test
    fun longPress_目标未定位_转译失败() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "首页"))
        val reason = failure(
            AgentIntent(intent = IntentType.LONG_PRESS, target = AgentIntentTarget("text", "不存在的项")),
            s,
        )
        assertTrue(reason.contains("目标定位失败"))
    }

    // ---- 高层语义接口（端侧 semantic_id 定位，AI 不写命令/坐标） ----

    @Test
    fun refresh_命中语义按钮_转译为点击refresh_btn() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "刷新", semanticId = "refresh_btn"))
        val action = command(AgentIntent(intent = IntentType.REFRESH), s)
        assertEquals(ActionType.TAP, action.type)
        assertEquals(0, action.elementIndex)
        assertEquals("刷新", action.target?.value)
    }

    @Test
    fun confirm_按优先级命中dlg_allow() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(
            elem(0, "取消"),
            elem(1, "允许", semanticId = "dlg_allow"),
        )
        val action = command(AgentIntent(intent = IntentType.CONFIRM), s)
        assertEquals(ActionType.TAP, action.type)
        assertEquals(1, action.elementIndex)
    }

    @Test
    fun delete_不可逆_自动置confirm位() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "删除", semanticId = "delete_btn"))
        val action = command(AgentIntent(intent = IntentType.DELETE), s)
        assertEquals(ActionType.TAP, action.type)
        assertTrue(action.needsUserConfirmation)
    }

    @Test
    fun back_命中返回按钮_点击按钮() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "返回", semanticId = "back_btn"))
        val action = command(AgentIntent(intent = IntentType.BACK), s)
        assertEquals(ActionType.TAP, action.type)
    }

    @Test
    fun back_无返回按钮_退化为系统返回键() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "标题"))
        val action = command(AgentIntent(intent = IntentType.BACK), s)
        assertEquals(ActionType.KEY, action.type)
        assertEquals("BACK", action.keycode)
    }

    @Test
    fun home_转译为系统HOME键() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.HOME), snapshot())
        assertEquals(ActionType.KEY, action.type)
        assertEquals("HOME", action.keycode)
    }

    @Test
    fun 语义按钮缺失_转译失败() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "标题"))
        val reason = failure(AgentIntent(intent = IntentType.SHARE), s)
        assertTrue(reason.contains("语义控件"))
    }

    @Test
    fun confirm_语义缺失_但有target_回退命中() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "确定"))
        val action = command(
            AgentIntent(intent = IntentType.CONFIRM, target = AgentIntentTarget("text", "确定")),
            s,
        )
        assertEquals(ActionType.TAP, action.type)
        assertEquals(0, action.elementIndex)
    }

    @Test
    fun confirm_语义命中_优先于target() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(
            elem(0, "取消"),
            elem(1, "允许", semanticId = "dlg_allow"),
            elem(2, "确定"),
        )
        val action = command(
            AgentIntent(intent = IntentType.CONFIRM, target = AgentIntentTarget("text", "确定")),
            s,
        )
        // 语义优先：应命中 dlg_allow(index=1)，而非 target 指向的"确定"(index=2)
        assertEquals(1, action.elementIndex)
    }

    @Test
    fun 各剩余语义接口_命中对应按钮_统一转为tap() {
        mode(Mode.ACCESSIBILITY)
        val cases = mapOf(
            IntentType.CLOSE to "close_btn",
            IntentType.COPY to "copy_btn",
            IntentType.DOWNLOAD to "download_btn",
            IntentType.ADD to "add_btn",
            IntentType.SWITCH to "switch_toggle",
            IntentType.CLEAR_INPUT to "clear_input",
            IntentType.SHARE to "share_btn",
            IntentType.SEND to "send_btn",
            IntentType.SEARCH to "search_box",
        )
        cases.forEach { (intent, sid) ->
            val action = command(AgentIntent(intent = intent), snapshot(elem(0, sid, semanticId = sid)))
            assertEquals("$intent 应转 tap", ActionType.TAP, action.type)
            assertEquals("$intent 应命中语义按钮", 0, action.elementIndex)
        }
    }
}
