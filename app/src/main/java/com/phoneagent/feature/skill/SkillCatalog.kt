package com.phoneagent.feature.skill

import com.phoneagent.domain.model.IntentType

/**
 * 内置 Skill 目录：把原硬编码命令（意图）升级为内置 Skill。
 *
 * 每个内置 Skill 通过 [Skill.legacyIntent] 指回旧命令意图名，由 [SkillCompat] 兼容层保证
 * “旧命令 → 对应内置 Skill → 原有转译逻辑”的向下兼容链路不变。AI 叫出旧意图名或技能名都可达。
 */
object SkillCatalog {

    /**
     * 高层语义技能的通用可选参数。
     *
     * 这些技能的落地方式是"点端侧标注好的语义控件"，正常无需参数；但页面未标注出对应语义控件时，
     * 转译层支持用 AI 给的 target 兜底定位一次（见 SemanticActionStrategy）。此前技能声明里没有这个
     * 参数，AI 便无从提供 —— 接口是空的，能力也就在这条路上不可达。故统一声明为可选参数。
     */
    private val optionalTarget = listOf(
        SkillParam("target", "目标（可选）", "text", description = "语义控件未命中时兜底定位，如 ctl_3 或控件文字"),
    )

    /** 意图名 → 技能定义（name/description/category/params）。 */
    private val catalog: Map<String, Skill> = buildMap {
        put(
            IntentType.OPEN_APP, Skill(
                id = "skill_open_app", name = "打开应用", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.OPEN_APP,
                description = "打开指定应用。参数 app 为应用中文名或包名，如「美团」或 com.sankuai.meituan。",
                params = listOf(
                    SkillParam("app", "应用名/包名", "text", required = true, description = "要打开的应用中文名或包名"),
                ),
            ),
        )
        put(
            IntentType.OPEN, Skill(
                id = "skill_open_deeplink", name = "深链直达", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.OPEN,
                description = "通过深链或协议直达目标应用页面。参数 uri 为 https 链接或应用私有 scheme；或 app+page 走页面直达索引。",
                params = listOf(
                    SkillParam("uri", "深链", "text", defaultValue = "", description = "https:// 或 scheme://"),
                    SkillParam("app", "应用名", "text", description = "目标应用名（配合 page）"),
                    SkillParam("page", "页面序号", "number", description = "软件页面直达索引序号"),
                ),
            ),
        )
        put(
            IntentType.TAP, Skill(
                id = "skill_tap", name = "点击", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.TAP,
                description = "点击某个控件。参数 target 的 by 用 id 引用控件编号（如 ctl_3），或 text 用外显文字。",
                params = listOf(
                    SkillParam("target", "目标", "text", required = true, description = "控件 id（ctl_1）或控件文字/语义"),
                ),
            ),
        )
        put(
            IntentType.LONG_PRESS, Skill(
                id = "skill_long_press", name = "长按", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.LONG_PRESS,
                description = "长按某个控件。参数 target 见 tap。",
                params = listOf(
                    SkillParam("target", "目标", "text", required = true),
                    SkillParam("duration_ms", "长按时长(ms)", "number", defaultValue = "800"),
                ),
            ),
        )
        put(
            IntentType.INPUT, Skill(
                id = "skill_input", name = "输入文字", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.INPUT,
                description = "向输入框 target 输入文字 text。",
                params = listOf(
                    SkillParam("target", "目标输入框", "text", required = true),
                    SkillParam("text", "要输入的字", "text", required = true),
                ),
            ),
        )
        put(
            IntentType.SWIPE, Skill(
                id = "skill_swipe", name = "滑动", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SWIPE,
                description = "按方向滑动屏幕。参数 direction = up|down|left|right。",
                params = listOf(
                    SkillParam("direction", "方向", "select", required = true, options = listOf("up", "down", "left", "right")),
                ),
            ),
        )
        put(
            IntentType.PRESS, Skill(
                id = "skill_press", name = "按键", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.PRESS,
                description = "触发系统按键。参数 key = BACK|HOME|ENTER|RECENT。",
                params = listOf(
                    SkillParam("key", "按键", "select", required = true, options = listOf("BACK", "HOME", "ENTER", "RECENT")),
                ),
            ),
        )
        put(
            IntentType.WAIT, Skill(
                id = "skill_wait", name = "等待", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.WAIT,
                description = "原地等待 wait_ms 毫秒，用于等待页面加载。",
                params = listOf(
                    SkillParam("wait_ms", "等待毫秒", "number", required = true, defaultValue = "1000"),
                ),
            ),
        )
        put(
            IntentType.SCROLL_TO, Skill(
                id = "skill_scroll_to", name = "滚动查找", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SCROLL_TO,
                description = "向上/向下滚动查找目标控件 target，找到即停。",
                params = listOf(
                    SkillParam("target", "要找的目标", "text", required = true),
                ),
            ),
        )
        put(
            IntentType.WRITE_DOC, Skill(
                id = "skill_write_doc", name = "写入文档", source = SkillSource.INTENT, isBuiltIn = true,
                category = "创作", legacyIntent = IntentType.WRITE_DOC,
                description = "把正文 text 生成文档，文件名可选（summary），结果在 Agent 页预览。",
                params = listOf(
                    SkillParam("text", "正文内容", "text", required = true),
                    SkillParam("summary", "文件名", "text", description = "可选，默认自动命名"),
                ),
            ),
        )
        put(
            IntentType.REMEMBER, Skill(
                id = "skill_remember", name = "记住信息", source = SkillSource.INTENT, isBuiltIn = true,
                category = "记忆", legacyIntent = IntentType.REMEMBER,
                description = "把有长期价值的信息写入本地记忆库（不操作屏幕、不碰设备）。",
                params = listOf(
                    SkillParam("text", "要记住的内容", "text", required = true, description = "一句话，如「用户偏好简洁界面」"),
                    SkillParam(
                        "summary", "分类", "select", defaultValue = "general",
                        options = listOf("preference", "fact", "habit", "tip", "general"),
                    ),
                ),
            ),
        )
        put(
            IntentType.FETCH, Skill(
                id = "skill_fetch", name = "取网页正文", source = SkillSource.INTENT, isBuiltIn = true,
                category = "取数", legacyIntent = IntentType.FETCH,
                description = "用命令行取网页/接口正文（需本机已安装并授权 Termux）。",
                params = listOf(
                    SkillParam("uri", "网页/接口地址", "text", required = true, description = "http:// 或 https://"),
                ),
            ),
        )
        put(
            IntentType.FINISH, Skill(
                id = "skill_finish", name = "完成", source = SkillSource.INTENT, isBuiltIn = true,
                category = "控制", legacyIntent = IntentType.FINISH,
                description = "任务已完成，给出完成说明 summary。",
                params = listOf(SkillParam("summary", "完成说明", "text")),
            ),
        )
        put(
            IntentType.GIVE_UP, Skill(
                id = "skill_give_up", name = "放弃", source = SkillSource.INTENT, isBuiltIn = true,
                category = "控制", legacyIntent = IntentType.GIVE_UP,
                description = "任务无法继续，给出原因 reason 后放弃。",
                params = listOf(SkillParam("reason", "放弃原因", "text")),
            ),
        )
        // ---- 高层语义接口升级为内置技能 ----
        put(
            IntentType.BACK, Skill(
                id = "skill_back", name = "返回", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.BACK, description = "返回上一页：优先点语义返回按钮，找不到则系统返回。",
            ),
        )
        put(
            IntentType.HOME, Skill(
                id = "skill_home", name = "回桌面", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.HOME, description = "回到桌面/首页（系统 HOME）。",
            ),
        )
        put(
            IntentType.REFRESH, Skill(
                id = "skill_refresh", name = "刷新", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.REFRESH, description = "刷新当前页面。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SEARCH, Skill(
                id = "skill_search", name = "搜索", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SEARCH, description = "进入搜索：聚焦搜索框或点搜索入口。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SEND, Skill(
                id = "skill_send", name = "发送", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SEND, description = "发送消息/提交。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.CONFIRM, Skill(
                id = "skill_confirm", name = "确认", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.CONFIRM, description = "确认当前操作（授权/确定/结算）。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.CLOSE, Skill(
                id = "skill_close", name = "关闭", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.CLOSE,
                description = "关闭当前弹窗/广告/标签或广告页。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SHARE, Skill(
                id = "skill_share", name = "分享", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SHARE, description = "分享当前内容。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.COLLECT, Skill(
                id = "skill_collect", name = "收藏", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.COLLECT, description = "收藏当前内容。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.COPY, Skill(
                id = "skill_copy", name = "复制", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.COPY, description = "复制目标内容。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.DELETE, Skill(
                id = "skill_delete", name = "删除", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.DELETE,
                description = "删除目标（不可逆，自动触发用户确认）。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.DOWNLOAD, Skill(
                id = "skill_download", name = "下载", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.DOWNLOAD, description = "下载当前目标。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.ADD, Skill(
                id = "skill_add", name = "新增", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.ADD, description = "新增/添加一项。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SWITCH, Skill(
                id = "skill_switch", name = "切换开关", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SWITCH, description = "切换开关状态。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.CLEAR_INPUT, Skill(
                id = "skill_clear_input", name = "清空输入", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.CLEAR_INPUT, description = "清空输入框内容。",
                params = optionalTarget,
            ),
        )
    }

    fun builtins(): List<Skill> = catalog.values.toList()

    fun byLegacyIntent(intentName: String): Skill? = catalog[intentName]

    fun byId(id: String): Skill? = catalog[id]
}