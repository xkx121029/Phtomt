package com.phoneagent.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.CapabilityRegistry
import com.phoneagent.domain.model.IntentType
import com.phoneagent.ui.icons.AppIcons

/**
 * 能力端点的界面身份：中文名 + 图标。
 *
 * 为什么单独一个文件：
 * - **中文名只有一份**：以 [CapabilityRegistry] 为唯一事实源，这里不再手抄名称表。
 *   以前 UI 侧的 HumanTranslator 自己维护一份动作名，新能力（定闹钟、剪贴板、日历…）
 *   进来后没跟上，卡片标题就退回英文或「未知操作」。
 * - **图标按意图登记**：[CapabilityRegistry] 只描述「能做什么」，不管「长什么样」，
 *   图标属于界面语言，放在 UI 层，但仍按意图名一一对应，缺一个由
 *   `CapabilityVisualsTest` 直接判红。
 *
 * 兜底只在**非能力**的动作类型上生效（如引擎内部的 `recents`、`task_done`）；
 * [IntentType.ALL] 里的每一项都必须命中本文件的表，测试会遍历校验。
 */
object CapabilityVisuals {

    /** 意图 / 动作类型 → 图标。同一语义的动作与意图共用一项，避免两套图形 */
    private val iconMap: Map<String, ImageVector> = mapOf(
        // 导航
        IntentType.OPEN_APP to AppIcons.Launch,
        IntentType.OPEN to AppIcons.Globe,
        IntentType.BACK to AppIcons.ArrowBack,
        IntentType.HOME to AppIcons.Home,
        IntentType.REFRESH to AppIcons.Refresh,
        IntentType.SEARCH to AppIcons.Search,
        "recents" to AppIcons.ListTodo,
        "launch" to AppIcons.Launch,

        // 操作
        IntentType.TAP to AppIcons.Tap,
        IntentType.LONG_PRESS to AppIcons.LongPress,
        IntentType.INPUT to AppIcons.Keyboard,
        IntentType.PRESS to AppIcons.EnterKey,
        IntentType.SWIPE to AppIcons.ScrollVertical,
        IntentType.SCROLL_TO to AppIcons.ScrollSearch,
        IntentType.WAIT to AppIcons.Loading,
        IntentType.WAIT_FOR to AppIcons.Loading,
        IntentType.SEND to AppIcons.Send,
        IntentType.CONFIRM to AppIcons.CheckCircle,
        IntentType.CLOSE to AppIcons.Close,
        IntentType.SHARE to AppIcons.Share,
        IntentType.COLLECT to AppIcons.Star,
        IntentType.COPY to AppIcons.Copy,
        IntentType.DELETE to AppIcons.Delete,
        IntentType.DOWNLOAD to AppIcons.FileDownload,
        IntentType.ADD to AppIcons.Add,
        IntentType.SWITCH to AppIcons.Tune,
        IntentType.CLEAR_INPUT to AppIcons.DeleteOutline,

        // 引擎内部动作类型（不在能力注册表里，但会出现在步骤行与卡片标题）
        "click" to AppIcons.Tap,
        "long_click" to AppIcons.LongPress,
        "type" to AppIcons.Keyboard,
        "type_text" to AppIcons.Keyboard,
        "key" to AppIcons.EnterKey,
        "scroll" to AppIcons.ScrollVertical,
        "swipe_up" to AppIcons.ScrollVertical,
        "swipe_down" to AppIcons.ScrollVertical,
        "swipe_left" to AppIcons.ScrollVertical,
        "swipe_right" to AppIcons.ScrollVertical,
        "launch_assistant" to AppIcons.Mic,
        "task_done" to AppIcons.CheckCircle,
        "task_complete" to AppIcons.CheckCircle,
        "abort" to AppIcons.Cancel,
        "mcp_call" to AppIcons.Bolt,
        "a11y_call" to AppIcons.TouchApp,
        "read_notifs" to AppIcons.Notifications,
        "clear_notifs" to AppIcons.NotifOff,
        "device_ops" to AppIcons.Tune,

        // 控制与沟通
        IntentType.FINISH to AppIcons.CheckCircle,
        IntentType.GIVE_UP to AppIcons.Cancel,
        IntentType.SAY to AppIcons.Message,
        IntentType.SHOW_AGENT to AppIcons.SmartToy,
        IntentType.SEE to AppIcons.Preview,
        IntentType.ASSISTANT to AppIcons.Mic,
        IntentType.FETCH to AppIcons.Globe,

        // 时钟与日历
        IntentType.SET_ALARM to AppIcons.Alarm,
        IntentType.SET_TIMER to AppIcons.Timer,
        IntentType.ALARM_MANAGE to AppIcons.Notifications,
        IntentType.CALENDAR_OP to AppIcons.Calendar,

        // 设备与系统
        IntentType.DEVICE_INFO to AppIcons.Device,
        IntentType.DEVICE_CONTROL to AppIcons.Tune,
        IntentType.DEVICE_QUERY to AppIcons.Memory,
        IntentType.SYSTEM_SETTINGS to AppIcons.Settings,
        IntentType.CLIPBOARD to AppIcons.Clipboard,
        IntentType.VIRTUAL_DISPLAY to AppIcons.AppWindow,
        IntentType.TERMINAL_OP to AppIcons.Terminal,
        IntentType.SHELL to AppIcons.Terminal,
        IntentType.A11Y to AppIcons.TouchApp,

        // 记忆与文件
        IntentType.REMEMBER to AppIcons.Remember,
        IntentType.MEMORY_GET to AppIcons.History,
        IntentType.MEMORY_WRITE to AppIcons.Edit,
        IntentType.FILE_READ to AppIcons.Description,
        IntentType.FILE_WRITE to AppIcons.Remember,

        // 应用与个人数据
        IntentType.APP_MANAGE to AppIcons.Store,
        IntentType.PERSONAL_READ to AppIcons.Person,
        IntentType.NOTIF_READ to AppIcons.Notifications,
        IntentType.NOTIF_CLEAR to AppIcons.NotifOff,

        // 内置浏览器
        IntentType.BROWSE_OPEN to AppIcons.Globe,
        IntentType.BROWSE_READ to AppIcons.Description,
        IntentType.BROWSE_CLICK to AppIcons.Tap,
        IntentType.BROWSE_INPUT to AppIcons.Keyboard,
        IntentType.BROWSE_SCROLL to AppIcons.ScrollVertical,
        IntentType.BROWSE_BACK to AppIcons.ArrowBack,
        IntentType.BROWSE_INFO to AppIcons.Info,
        IntentType.BROWSE_RELOAD to AppIcons.Refresh,
        IntentType.BROWSE_WAIT to AppIcons.Loading,
        IntentType.WEB_SEARCH to AppIcons.Search,

        // 文档
        IntentType.WRITE_DOC to AppIcons.Description,
    )

    /**
     * 意图 / 动作类型对应的图标；未登记返回 null。
     *
     * 能力项一律有值，测试按 [IntentType.ALL] 遍历断言，缺一项即失败。
     */
    fun iconOrNull(type: String?): ImageVector? = iconMap[type?.trim().orEmpty()]

    /** 卡片、步骤行等处取图标：未登记的动作类型退回机器人图标，保证不出现空白位 */
    fun iconOf(type: String?): ImageVector = iconOrNull(type) ?: AppIcons.SmartToy

    /**
     * 意图 / 动作类型的中文名。
     *
     * 能力项取自 [CapabilityRegistry] 的 `name`（唯一事实源）；非能力动作类型
     * 交给 [HumanTranslator]（其内部也只对已知动作给中文）。
     */
    fun nameOf(type: String?): String {
        val key = type?.trim().orEmpty()
        if (key.isEmpty()) return "其他操作"
        CapabilityRegistry.specOf(key)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return HumanTranslator.actionVerb(key)
    }
}
