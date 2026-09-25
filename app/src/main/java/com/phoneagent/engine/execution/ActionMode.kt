package com.phoneagent.engine.execution

import com.phoneagent.domain.model.IntentType

/**
 * 动作模式（权限档位）：决定"AI 允许提出哪一类请求"。
 *
 * 与 [CapabilityManager.Mode]（通道模式：SHIZUKU / ACCESSIBILITY / READONLY）**正交**：
 * - 通道模式回答"能不能执行"（设备有没有相应权限）；
 * - 动作模式回答"允不允许 AI 提出这类请求"（用户给 AI 的授权范围）。
 *
 * 两者叠加生效：例如通道模式为 READONLY 时任何模式都不能自动执行；
 * 动作模式为保守时，即使有 Shizuku 权限也不允许 AI 点击/输入。
 */
enum class ActionMode(
    val key: String,
    val label: String,
    val labelEn: String,
    val summary: String,
    val summaryEn: String,
) {
    /** 保守：仅低风险命令（只读 + 导航 + 纯本地动作），不放行任何改动设备/外部状态的动作 */
    CONSERVATIVE(
        "CONSERVATIVE", "保守", "Conservative",
        "仅低风险命令：只读 / 导航 / 本地读写",
        "low-risk commands only: read-only / navigation / local read-write",
    ),

    /** 均衡：转译层全部意图可用（= 既有默认行为） */
    BALANCED(
        "BALANCED", "均衡", "Balanced",
        "转译层全部意图可用",
        "all translator intents available",
    ),

    /** 自由：均衡 + AI 自写 shizuku/adb/termux 命令 + 调用全量无障碍端点 */
    FREE(
        "FREE", "自由", "Free",
        "全部意图 + AI 自写命令 + 全量无障碍端点",
        "all intents + self-written commands + all accessibility endpoints",
    );

    companion object {
        /** 默认档位：均衡即既有行为，保证升级后零行为变化 */
        val DEFAULT = BALANCED

        /** 宽松解析持久化的档位字符串；非法/空值一律回落到 [DEFAULT]（避免脏数据把权限意外放大或收紧） */
        fun fromKey(raw: String?): ActionMode =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 无障碍端点白名单条目：自由模式下 AI 可通过 `a11y` 意图调用的一个底层接口。
 *
 * @param name 端点名（AI 在 `endpoint` 字段填这个）
 * @param requiredArgs 必填参数名（AI 放在 `args` 对象里）
 * @param optionalArgs 可选参数名
 * @param description 一句话说明（注入提示词）
 * @param descriptionEn 英文一句话说明（英文提示词用，避免英文提示里混中文）
 */
data class A11yEndpoint(
    val name: String,
    val requiredArgs: List<String>,
    val optionalArgs: List<String>,
    val description: String,
    val descriptionEn: String,
) {
    /** 提示词表格里的一行参数写法 */
    fun argsText(): String = (requiredArgs + optionalArgs.map { "$it?" }).joinToString(",").ifEmpty { "—" }
}

/**
 * 动作模式策略：三档权限的**唯一定义点**。
 *
 * 风险分级、放行判定、无障碍端点白名单全部收口在这里，端侧门控与提示词共用同一份数据，
 * 杜绝"提示词说能用、端侧其实拒了"或反过来的漂移（与 [com.phoneagent.feature.browser.BrowserGuard] 同思路）。
 */
object ActionPolicy {

    /** 放行判定结果 */
    sealed class Verdict {
        /** 放行 */
        data object Allowed : Verdict()
        /** 拒绝：reason 为给 AI 看的中文人话原因（含换档建议） */
        data class Denied(val reason: String) : Verdict()
    }

    /**
     * 低风险意图（21 项）：不改变设备/外部状态，或只做本地读写与只读浏览。
     * 保守模式只放行这一档。
     */
    val lowRisk: Set<String> = setOf(
        // 纯本地：等待 / 说话 / 记忆 / 查设备 / 生成文档 / 引导用户回来看结果 / 收尾
        IntentType.WAIT, IntentType.SAY, IntentType.REMEMBER, IntentType.DEVICE_QUERY,
        IntentType.WRITE_DOC, IntentType.SHOW_AGENT, IntentType.FINISH, IntentType.GIVE_UP,
        // 纯导航与视图滚动（不触发任何按钮）
        IntentType.SWIPE, IntentType.SCROLL_TO, IntentType.PRESS,
        IntentType.BACK, IntentType.HOME, IntentType.REFRESH,
        // 页内低副作用操作
        IntentType.CLOSE, IntentType.COLLECT, IntentType.COPY, IntentType.CLEAR_INPUT,
        // 只读浏览网页
        IntentType.BROWSE_READ, IntentType.BROWSE_SCROLL, IntentType.BROWSE_BACK,
    )

    /**
     * 中风险意图（14 项）：会改动设备/外部状态，但可见、可撤销，或本就是用户明确要求。
     * 均衡模式起放行。
     */
    val midRisk: Set<String> = setOf(
        IntentType.OPEN_APP, IntentType.OPEN, IntentType.TAP, IntentType.LONG_PRESS, IntentType.INPUT,
        IntentType.FETCH, IntentType.BROWSE_OPEN, IntentType.BROWSE_CLICK, IntentType.BROWSE_INPUT,
        IntentType.SEARCH, IntentType.SHARE, IntentType.DOWNLOAD, IntentType.ADD, IntentType.SWITCH,
    )

    /**
     * 高风险意图（3 项）：真实不可逆（发送/授权结算/删除）。
     * 保守模式拒绝；均衡/自由放行但沿用既有 needs_confirmation 机制由 AI 自行标注。
     */
    val highRisk: Set<String> = setOf(
        IntentType.SEND, IntentType.CONFIRM, IntentType.DELETE,
    )

    /**
     * 自由模式专属意图：不属于转译层既有命令，保守/均衡一律拒绝。
     * - SHELL：AI 自写 shizuku/adb/termux 命令
     * - A11Y：直接调用无障碍端点
     */
    val freeOnly: Set<String> = setOf(
        IntentType.SHELL, IntentType.A11Y,
    )

    /** 已标注风险档的意图全集（= 低 + 中 + 高） */
    val tiered: Set<String> = lowRisk + midRisk + highRisk

    /**
     * 无障碍端点白名单（18 项）：自由模式下 AI 可调用的底层接口全集。
     * 覆盖 [com.phoneagent.device.a11y.ActionExecutor] 的全部 public 端点 +
     * [com.phoneagent.device.a11y.AgentAccessibilityService] 的感知类方法。
     */
    val a11yEndpoints: List<A11yEndpoint> = listOf(
        A11yEndpoint("click", listOf("x", "y"), emptyList(), "按像素坐标点击", "tap at pixel coordinates"),
        A11yEndpoint("long_click", listOf("x", "y"), emptyList(), "按像素坐标长按", "long-press at pixel coordinates"),
        A11yEndpoint(
            "click_node", listOf("target"), listOf("long_click"),
            "直点活节点控件（免坐标，优先用它）；target 写「id:资源名」或直接写控件文字",
            "click a live node control (no coordinates; prefer this); target is \"id:resourceName\" or the control's text",
        ),
        A11yEndpoint("swipe", listOf("x1", "y1", "x2", "y2"), listOf("duration_ms"), "按坐标滑动", "swipe between coordinates"),
        A11yEndpoint("swipe_direction", listOf("direction"), listOf("distance_px"), "按方向滑动（up/down/left/right）", "swipe by direction (up/down/left/right)"),
        A11yEndpoint("scroll", listOf("direction"), listOf("target"), "滚动页面；给 target 时滚动查找该控件", "scroll the page; with target, scroll until that control appears"),
        A11yEndpoint("scroll_container", listOf("direction"), emptyList(), "滚动当前可滚动容器", "scroll the current scrollable container"),
        A11yEndpoint("global_action", listOf("action"), emptyList(), "原始全局动作：1=返回 2=主页 4=通知栏 8=最近任务", "raw global action: 1=back 2=home 4=notifications 8=recents"),
        A11yEndpoint("back", emptyList(), emptyList(), "系统返回", "system back"),
        A11yEndpoint("home", emptyList(), emptyList(), "系统主页", "system home"),
        A11yEndpoint("recents", emptyList(), emptyList(), "最近任务", "recent tasks"),
        A11yEndpoint("launch_app", listOf("package"), emptyList(), "按包名启动应用", "launch an app by package name"),
        A11yEndpoint("open_uri", listOf("uri"), listOf("package"), "打开链接/文件/深链（可指定应用包名）", "open a link/file/deep-link (optionally with a target package)"),
        A11yEndpoint("open_settings_action", listOf("action"), emptyList(), "打开系统设置页（如 accessibility_settings / wifi）", "open a system settings page (e.g. accessibility_settings / wifi)"),
        A11yEndpoint("type_text", listOf("text"), listOf("target"), "向当前焦点或指定控件输入文字", "type text into the current focus or a given control"),
        A11yEndpoint("capture_tree", emptyList(), emptyList(), "重新抓取当前页面元素树（结果回注给你）", "re-capture the current element tree (result is fed back to you)"),
        A11yEndpoint("screenshot", emptyList(), emptyList(), "截取当前屏幕位图", "capture the current screen bitmap"),
        A11yEndpoint("can_screenshot", emptyList(), emptyList(), "查询本机当前是否具备截图能力", "check whether screen capture is currently available"),
    )

    private val endpointsByName: Map<String, A11yEndpoint> = a11yEndpoints.associateBy { it.name }

    /** 按端点名查白名单条目；未知端名返回 null（调用方据此失败并回报可选清单） */
    fun endpointOf(name: String?): A11yEndpoint? = endpointsByName[name?.trim()?.lowercase()]

    /**
     * 判定当前档位是否放行该意图。
     *
     * 未知意图（不在任何档位、也不是自由专属）一律放行——交给转译层按"未知意图"失败处理，
     * 这里不越权拦截，避免两条链路各说一套。
     */
    fun allows(mode: ActionMode, intent: String?): Verdict {
        val name = intent?.trim().orEmpty()
        if (name.isEmpty()) return Verdict.Allowed
        return when (mode) {
            ActionMode.FREE -> Verdict.Allowed
            ActionMode.BALANCED -> if (name in freeOnly) Verdict.Denied(freeModeOnlyReason(name)) else Verdict.Allowed
            ActionMode.CONSERVATIVE -> when {
                name in lowRisk -> Verdict.Allowed
                name in freeOnly -> Verdict.Denied(freeModeOnlyReason(name))
                name in tiered -> Verdict.Denied(conservativeReason(name))
                // 认不出的意图不在这里拦：转译层会以"未知意图"明确失败，
                // 门控抢着报"越权"只会把拼错的意图名说成权限问题，误导 AI 去换档
                else -> Verdict.Allowed
            }
        }
    }

    private fun freeModeOnlyReason(intent: String): String =
        "意图「$intent」仅在自由模式下可用（自写命令 / 调用无障碍端点）。当前动作模式不允许它；" +
            "请改用转译层既有意图完成当前目标；确实必须用到时，用 say 提示用户到「Agent 页输入栏下方的动作模式」切换到自由模式。"

    private fun conservativeReason(intent: String): String =
        "当前为保守模式（仅低风险命令），意图「$intent」属于中/高风险动作，端侧已拒绝，未对设备做任何操作。" +
            "保守模式下可用：${lowRisk.joinToString("/")}。" +
            "请改用上列意图推进任务；若确实必须点击或输入，用 give_up 说明需要用户把动作模式切换到均衡。"

    /**
     * 覆盖度自检：返回"已标注风险档但不在 [IntentType.ALL] 里"的意图（多余项）。
     * 与 [missingTiers] 一起供单测断言，防止新增意图时漏标注。
     */
    fun extraTiers(): Set<String> = tiered - IntentType.ALL

    /** 覆盖度自检：返回"[IntentType.ALL] 里未标注风险档、也非自由专属"的意图（漏标注项） */
    fun missingTiers(): Set<String> = IntentType.ALL - tiered - freeOnly
}