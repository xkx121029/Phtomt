package com.phoneagent.ui.theme

import com.phoneagent.data.prefs.WorkbenchView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风格预设与工作台视图的归一规则：未知/空值一律回落默认，避免脏配置把首页切空白。
 */
class UiStyleTest {

    @Test
    fun `classic is the default style`() {
        assertEquals("classic", UiStyle.DEFAULT.id)
        assertEquals(UiStyle.CLASSIC, UiStyle.of("classic"))
    }

    @Test
    fun `unknown style falls back to classic`() {
        assertEquals(UiStyle.CLASSIC, UiStyle.of(null))
        assertEquals(UiStyle.CLASSIC, UiStyle.of(""))
        assertEquals(UiStyle.CLASSIC, UiStyle.of("neo"))
    }

    @Test
    fun `workbench is selectable by id`() {
        assertEquals(UiStyle.WORKBENCH, UiStyle.of("workbench"))
        assertTrue(UiStyle.WORKBENCH.toneScale < UiStyle.CLASSIC.toneScale)
    }

    @Test
    fun `workbench view defaults to agent`() {
        assertEquals(WorkbenchView.AGENT, WorkbenchView.normalize(null))
        assertEquals(WorkbenchView.AGENT, WorkbenchView.normalize("unknown"))
    }

    @Test
    fun `workbench view keeps coding`() {
        assertEquals(WorkbenchView.CODING, WorkbenchView.normalize("coding"))
        assertFalse(WorkbenchView.normalize("agent") == WorkbenchView.CODING)
    }
}
