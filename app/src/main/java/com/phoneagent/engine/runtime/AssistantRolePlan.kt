package com.phoneagent.engine.runtime

/**
 * 「设为默认数字助理」的端侧规划器（纯数据，不依赖 Android 类）。
 *
 * 为什么要剥出来：原来的 `AssistantRole` 直接调 RoleManager / startActivity，
 * UI 只能拿到「成功/失败」两个结果，失败时不知道为什么、也不知道下一步该跳哪一页。
 * 这里把判断条件、跳转候选链、失败原因全部变成可测试的纯数据，
 * **不返回 Intent 或 Activity**——UI 自己按 [AssistantRedirect.action] 构造 Intent 并 resolve，
 * 解析不到就试下一个候选。
 *
 * 契约文档：`docs/assistant-role-contract.md`。
 */
data class AssistantRoleFacts(
    /** Build.VERSION.SDK_INT */
    val apiLevel: Int,
    /** RoleManager.isRoleAvailable(ROLE_ASSISTANT) */
    val roleAvailable: Boolean,
    /** RoleManager.isRoleHeld(ROLE_ASSISTANT) */
    val roleHeld: Boolean,
    /** 当前角色持有者包名；RoleManager 不直接提供时由 UI 用 PackageManager 探测，可为空 */
    val roleHolderPackage: String? = null,
    /** 本应用包名，用于判断「持有者是不是自己」 */
    val selfPackage: String = "",
)

enum class AssistantRoleState(val key: String, val label: String) {
    /** 已持有：无需任何操作 */
    HELD("held", "已持有"),

    /** 未持有，但系统可弹授权框 */
    NOT_HELD("not_held", "未持有"),

    /** 角色被别的应用占用 */
    OCCUPIED("occupied", "被系统占用"),

    /** 系统版本或 ROM 不支持该角色（只能去设置手动指定） */
    UNSUPPORTED("unsupported", "不支持"),
}

enum class AssistantPrimaryAction(val key: String) {
    NONE("none"),
    REQUEST_ROLE("request_role"),
    OPEN_SETTINGS("open_settings"),
}

/**
 * 设置页跳转候选（有序）。
 *
 * UI 的落地方式：按顺序尝试 `Intent(redirect.action)`，`resolveActivity` 为 null 就试下一个；
 * [requiresResolve] 为 true 表示必须探测（这些 action 并非所有 ROM 都存在）。
 */
data class AssistantRedirect(
    /** 稳定键，用于埋点/分支，不要翻译 */
    val key: String,
    /** 系统 action 字符串，由 UI 自己构造 Intent */
    val action: String,
    /** 按钮/说明文字（中文成品） */
    val label: String,
    /** 适用 ROM/API 条件（中文，给排障与用户说明用） */
    val condition: String,
    val minApi: Int,
    val requiresResolve: Boolean = true,
)

data class AssistantRolePlan(
    val state: AssistantRoleState,
    /** 首选动作：UI 只需要按这个三选一 */
    val primaryAction: AssistantPrimaryAction,
    val primaryLabel: String,
    /** 当前状态的完整中文说明（直接展示，不要 UI 侧自己拼） */
    val explanation: String,
    /** 按优先级排序的设置页候选；已按当前 API 过滤 */
    val redirects: List<AssistantRedirect>,
    /** 失败原因键；非失败状态为 null */
    val failureKey: String?,
    /** 失败原因中文默认值；非失败状态为 null。UI 直接展示即可 */
    val failureText: String?,
    /** 不能弹授权框的原因（中文）；能弹时为 null */
    val requestBlockedReason: String?,
) {
    val canRequestRole: Boolean get() = primaryAction == AssistantPrimaryAction.REQUEST_ROLE
}

object AssistantRolePlanner {

    /** RoleManager 自 API 29 起提供 */
    const val ROLE_API = 29

    /**
     * AOSP/原生优先的数字助理设置页。该 action 并非公开 SDK 常量，因此这里用字符串，
     * UI 必须先 resolveActivity，解析不到再试后面的候选。
     */
    const val ACTION_VOICE_INTERACTION_SETTINGS = "android.settings.VOICE_INTERACTION_SETTINGS"

    /** 「默认应用」设置页（API 24+），有的 ROM 把数字助理归在这里 */
    const val ACTION_MANAGE_DEFAULT_APPS = "android.settings.MANAGE_DEFAULT_APPS"

    /** 语音输入设置页；EMUI/部分国产 ROM 的助手入口挂在这里 */
    const val ACTION_VOICE_INPUT_SETTINGS = "android.settings.VOICE_INPUT_SETTINGS"

    /** 系统设置首页，所有 ROM 的最终兜底 */
    const val ACTION_SETTINGS = "android.settings.SETTINGS"

    val REDIRECTS: List<AssistantRedirect> = listOf(
        AssistantRedirect(
            key = "voice_interaction",
            action = ACTION_VOICE_INTERACTION_SETTINGS,
            label = "打开「数字助理」设置",
            condition = "Android 8.0+ 且 ROM 提供该页（AOSP/原生优先）",
            minApi = 26,
        ),
        AssistantRedirect(
            key = "default_apps",
            action = ACTION_MANAGE_DEFAULT_APPS,
            label = "打开「默认应用」设置",
            condition = "Android 7.0+；部分 ROM 把助手归在默认应用里",
            minApi = 24,
        ),
        AssistantRedirect(
            key = "voice_input",
            action = ACTION_VOICE_INPUT_SETTINGS,
            label = "打开「语音输入」设置",
            condition = "Android 8.0+；EMUI/部分国产 ROM 的助手入口在这里",
            minApi = 26,
        ),
        AssistantRedirect(
            key = "system_settings",
            action = ACTION_SETTINGS,
            label = "打开系统设置（手动查找数字助理）",
            condition = "所有 ROM 的最终兜底",
            minApi = 1,
            requiresResolve = false,
        ),
    )

    fun plan(facts: AssistantRoleFacts): AssistantRolePlan {
        val holder = facts.roleHolderPackage?.takeIf { it.isNotBlank() }
        val held = facts.roleHeld ||
            (holder != null && facts.selfPackage.isNotBlank() && holder == facts.selfPackage)
        val redirects = REDIRECTS.filter { facts.apiLevel >= it.minApi }

        return when {
            held -> AssistantRolePlan(
                state = AssistantRoleState.HELD,
                primaryAction = AssistantPrimaryAction.NONE,
                primaryLabel = "已是默认数字助理",
                explanation = "本应用已是系统默认数字助理。长按电源键或边缘手势可以直接唤起，" +
                    "唤起时的屏幕内容会随任务一起交给模型。",
                redirects = redirects,
                failureKey = null,
                failureText = null,
                requestBlockedReason = null,
            )

            facts.apiLevel < ROLE_API -> AssistantRolePlan(
                state = AssistantRoleState.UNSUPPORTED,
                primaryAction = AssistantPrimaryAction.OPEN_SETTINGS,
                primaryLabel = "去系统设置手动指定",
                explanation = "当前系统是 Android ${facts.apiLevel}，没有数字助理角色接口" +
                    "（需要 Android 10 及以上），只能在系统设置里手动指定本应用。",
                redirects = redirects,
                failureKey = AssistantRoleMessages.KEY_LOW_API,
                failureText = AssistantRoleMessages.LOW_API,
                requestBlockedReason = "Android ${facts.apiLevel} 没有 RoleManager 数字助理角色",
            )

            !facts.roleAvailable -> AssistantRolePlan(
                state = AssistantRoleState.UNSUPPORTED,
                primaryAction = AssistantPrimaryAction.OPEN_SETTINGS,
                primaryLabel = "去系统设置手动指定",
                explanation = "这台机型没有向第三方应用开放「数字助理」角色（ROM 定制），" +
                    "无法弹出系统授权框，只能在系统设置里手动指定。下面按 ROM 兼容度排了多个入口。",
                redirects = redirects,
                failureKey = AssistantRoleMessages.KEY_ROLE_UNAVAILABLE,
                failureText = AssistantRoleMessages.ROLE_UNAVAILABLE,
                requestBlockedReason = "系统未向第三方应用开放数字助理角色",
            )

            holder != null && holder != facts.selfPackage -> AssistantRolePlan(
                state = AssistantRoleState.OCCUPIED,
                primaryAction = AssistantPrimaryAction.REQUEST_ROLE,
                primaryLabel = "设为系统数字助理",
                explanation = "默认数字助理现在由「$holder」占用。点主按钮会弹出系统授权框，" +
                    "确认替换后即生效；如果系统没有弹框，请按下面的设置入口手动切换。",
                redirects = redirects,
                failureKey = AssistantRoleMessages.KEY_OCCUPIED,
                failureText = AssistantRoleMessages.OCCUPIED,
                requestBlockedReason = null,
            )

            else -> AssistantRolePlan(
                state = AssistantRoleState.NOT_HELD,
                primaryAction = AssistantPrimaryAction.REQUEST_ROLE,
                primaryLabel = "设为系统数字助理",
                explanation = "本应用还不是默认数字助理。点主按钮会弹出系统授权框，" +
                    "确认后即生效；如果系统没有弹框，请按下面的设置入口手动指定。",
                redirects = redirects,
                failureKey = null,
                failureText = null,
                requestBlockedReason = null,
            )
        }
    }
}

/**
 * 失败/状态文案的稳定键与中文默认值。
 *
 * UI 侧必须直接展示 [textOf] 的默认值，不要另写一套说法——两个页面（设置页与引导页）
 * 否则会长出两种措辞。
 */
object AssistantRoleMessages {
    const val KEY_LOW_API = "assistant_role_low_api"
    const val KEY_ROLE_UNAVAILABLE = "assistant_role_unavailable"
    const val KEY_OCCUPIED = "assistant_role_occupied"
    const val KEY_DENIED = "assistant_role_denied"
    const val KEY_NO_SETTINGS_PAGE = "assistant_role_no_settings_page"

    const val LOW_API = "Android 10 以下没有数字助理角色接口，只能在系统设置里手动指定本应用。"
    const val ROLE_UNAVAILABLE = "这台机型没有开放数字助理角色，无法弹出授权框，请在系统设置里手动指定。"
    const val OCCUPIED = "默认数字助理已被其他应用占用；确认替换后才会切换到本应用。"
    const val DENIED = "系统授权框已关闭，但「数字助理」角色没有授予本应用。" +
        "可能是你点了取消，或系统弹出了其他应用的替换确认。请再试一次；" +
        "如果一直不成功，请按下面的设置入口手动指定。"
    const val NO_SETTINGS_PAGE = "系统里找不到数字助理设置页（ROM 可能删除了该入口），" +
        "请手动在「设置 - 应用 - 默认应用」里指定本应用。"

    fun textOf(key: String?): String? = when (key) {
        KEY_LOW_API -> LOW_API
        KEY_ROLE_UNAVAILABLE -> ROLE_UNAVAILABLE
        KEY_OCCUPIED -> OCCUPIED
        KEY_DENIED -> DENIED
        KEY_NO_SETTINGS_PAGE -> NO_SETTINGS_PAGE
        else -> null
    }
}
