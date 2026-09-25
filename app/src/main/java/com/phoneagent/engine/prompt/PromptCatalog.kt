package com.phoneagent.engine.prompt

/**
 * 提示词区块目录：**全项目提示词正文的唯一清单**。
 *
 * 每个区块 = 一段可独立启停的正文 + 它的装配条件（[PromptBlock.requires] / [PromptBlock.excludes]）。
 * 装配规则由 [PromptAssembler] 执行：按 [PromptBlock.order] 排序后 `prefix + body + sep` 首尾相接，
 * 最后**整组一次性**渲染 `{占位符}`——不做块间额外插空，因此"全开时逐字符等于重构前的原文"。
 *
 * [PromptBlock.sep] 不是排版偏好，而是从原文逐字节算出来的桥接换行：
 * `body + sep` 必须在所有区块按序拼接后精确复现原字面量。改动 sep 就等于改提示词。
 */
internal object PromptCatalog {

    // ==================== 系统提示主体 ====================

    private val SYSTEM = listOf(
        PromptBlock(
            id = "sys.role", group = PromptGroup.SYSTEM, order = 0, sep = "\n\n",
            bodyCN = PromptBodies.SYS_ROLE_CN, bodyEN = PromptBodies.SYS_ROLE_EN,
            origin = "AgentPrompts.systemCN/EN 首段",
        ),
        PromptBlock(
            id = "sys.iron", group = PromptGroup.SYSTEM, order = 1, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_IRON_CN, bodyEN = PromptBodies.SYS_IRON_EN,
            origin = "AgentPrompts.systemCN/EN「# 铁律」",
        ),
        PromptBlock(
            id = "sys.completion", group = PromptGroup.SYSTEM, order = 2, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_COMPLETION_CN, bodyEN = PromptBodies.SYS_COMPLETION_EN,
            origin = "AgentPrompts.systemCN/EN「# 任务完成」",
        ),
        PromptBlock(
            id = "sys.page_data", group = PromptGroup.SYSTEM, order = 3, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_PAGE_DATA_CN, bodyEN = PromptBodies.SYS_PAGE_DATA_EN,
            origin = "AgentPrompts.systemCN/EN「# 页面数据」",
        ),
        PromptBlock(
            id = "sys.intents", group = PromptGroup.SYSTEM, order = 4, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_INTENTS_CN, bodyEN = PromptBodies.SYS_INTENTS_EN,
            origin = "AgentPrompts.systemCN/EN「# 意图」",
        ),
        PromptBlock(
            id = "sys.intents_semantic", group = PromptGroup.SYSTEM, order = 5, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_INTENTS_SEMANTIC_CN, bodyEN = PromptBodies.SYS_INTENTS_SEMANTIC_EN,
            origin = "AgentPrompts.systemCN/EN「# 高层语义意图」",
        ),
        // 唯一被裁剪的两个大块：任务明确与网页/打开无关时才省掉，省下的字符直接降低每步理解负担。
        PromptBlock(
            id = "sys.web_browse", group = PromptGroup.SYSTEM, order = 6, sep = "\n\n",
            bodyCN = PromptBodies.SYS_WEB_BROWSE_CN, bodyEN = PromptBodies.SYS_WEB_BROWSE_EN,
            requires = setOf(PromptFlag.WEB_TASK),
            origin = "AgentPrompts.systemCN/EN「# 网页浏览」（需 WEB_TASK）",
        ),
        PromptBlock(
            id = "sys.open_link", group = PromptGroup.SYSTEM, order = 7, sep = "\n\n",
            bodyCN = PromptBodies.SYS_OPEN_LINK_CN, bodyEN = PromptBodies.SYS_OPEN_LINK_EN,
            requires = setOf(PromptFlag.OPEN_TASK),
            origin = "AgentPrompts.systemCN/EN「# 打开链接与文件」（需 OPEN_TASK）",
        ),
        PromptBlock(
            id = "sys.targeting", group = PromptGroup.SYSTEM, order = 8, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_TARGETING_CN, bodyEN = PromptBodies.SYS_TARGETING_EN,
            origin = "AgentPrompts.systemCN/EN「# 目标定位」",
        ),
        PromptBlock(
            id = "sys.routing", group = PromptGroup.SYSTEM, order = 9, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_ROUTING_CN, bodyEN = PromptBodies.SYS_ROUTING_EN,
            origin = "AgentPrompts.systemCN/EN「# 独占路由规则」",
        ),
        PromptBlock(
            id = "sys.countdown", group = PromptGroup.SYSTEM, order = 10, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_COUNTDOWN_CN, bodyEN = PromptBodies.SYS_COUNTDOWN_EN,
            origin = "AgentPrompts.systemCN/EN「# 倒计时广告」",
        ),
        PromptBlock(
            id = "sys.irreversible", group = PromptGroup.SYSTEM, order = 11, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_IRREVERSIBLE_CN, bodyEN = PromptBodies.SYS_IRREVERSIBLE_EN,
            origin = "AgentPrompts.systemCN/EN「# 不可逆操作」",
        ),
        PromptBlock(
            id = "sys.cn_apps", group = PromptGroup.SYSTEM, order = 12, sep = "\n\n",
            bodyCN = PromptBodies.SYS_CN_APPS_CN, bodyEN = PromptBodies.SYS_CN_APPS_EN,
            origin = "AgentPrompts.systemCN/EN「# 国产应用速查」",
        ),
        PromptBlock(
            id = "sys.fields", group = PromptGroup.SYSTEM, order = 13, sep = "\n\n", safe = true,
            bodyCN = PromptBodies.SYS_FIELDS_CN, bodyEN = PromptBodies.SYS_FIELDS_EN,
            origin = "AgentPrompts.systemCN/EN「# 统一字段」",
        ),
        PromptBlock(
            id = "sys.decision_rules", group = PromptGroup.SYSTEM, order = 14, sep = "\n\n",
            bodyCN = PromptBodies.SYS_DECISION_RULES_CN, bodyEN = PromptBodies.SYS_DECISION_RULES_EN,
            origin = "AgentPrompts.systemCN/EN「# 决策规则」",
        ),
        PromptBlock(
            id = "sys.failure_paths", group = PromptGroup.SYSTEM, order = 15, sep = "\n\n",
            bodyCN = PromptBodies.SYS_FAILURE_PATHS_CN, bodyEN = PromptBodies.SYS_FAILURE_PATHS_EN,
            origin = "AgentPrompts.systemCN/EN「# 失败路径」",
        ),
        PromptBlock(
            id = "sys.merge", group = PromptGroup.SYSTEM, order = 16, sep = "\n\n",
            bodyCN = PromptBodies.SYS_MERGE_CN, bodyEN = PromptBodies.SYS_MERGE_EN,
            origin = "AgentPrompts.systemCN/EN「# 动作合并」",
        ),
        PromptBlock(
            id = "sys.forbidden", group = PromptGroup.SYSTEM, order = 17, sep = "", safe = true,
            bodyCN = PromptBodies.SYS_FORBIDDEN_CN, bodyEN = PromptBodies.SYS_FORBIDDEN_EN,
            origin = "AgentPrompts.systemCN/EN「# 禁止输出」",
        ),
    )

    // ==================== 动作模式（三档互斥） ====================
    //
    // 原文由 buildString + append 拼装（含三张运行期表格），无法逐行搬迁，故手写在这里。
    // 三块各自带同一段表头，由互斥标志位保证只有一块在场。

    private const val MODE_HEAD_CN =
        "# 动作模式（当前：{mode_label}，{mode_summary}）\n" +
            "端侧会按这个档位**逐条拒绝**越权意图：被拒的意图不会对设备产生任何操作，只会回你一句中文原因。\n"
    private const val MODE_HEAD_EN =
        "# Action Mode (current: {mode_label_en} / {mode_key}) — {mode_summary_en}\n" +
            "The device **rejects** over-privileged intents one by one according to this mode: a rejected intent performs nothing on the device and only returns a short reason.\n"

    private val ACTION_MODE = listOf(
        PromptBlock(
            id = "mode.conservative", group = PromptGroup.ACTION_MODE, order = 0, sep = "\n", safe = true,
            requires = setOf(PromptFlag.ACTION_CONSERVATIVE),
            exclusiveGroup = "action_mode",
            origin = "AgentPrompts.actionModeSection 保守档",
            bodyCN = MODE_HEAD_CN + """
            - 本档只放行低风险意图（只读 / 导航 / 本地读写）：{low_risk}。
            - 其余意图（点击、输入、打开应用、搜索、发送、确认、删除、网页点击与填表等）一律被端侧拒绝，不要尝试。
            - 确实必须点击或输入才能推进时：用 give_up，在 reason 里说明「需要用户把动作模式切到均衡」，而不是反复重试被拒的动作。
            """.trimIndent(),
            bodyEN = MODE_HEAD_EN + """
            - This mode allows low-risk intents only (read-only / navigation / local read-write): {low_risk}.
            - Every other intent (tap, input, open_app, search, send, confirm, delete, web click/fill, ...) is rejected. Do not attempt them.
            - If you truly cannot proceed without tapping or typing: use give_up and state in `reason` that the user must switch the action mode to Balanced — never keep retrying a rejected action.
            """.trimIndent(),
        ),
        PromptBlock(
            id = "mode.balanced", group = PromptGroup.ACTION_MODE, order = 1, sep = "\n", safe = true,
            requires = setOf(PromptFlag.ACTION_BALANCED),
            exclusiveGroup = "action_mode",
            origin = "AgentPrompts.actionModeSection 均衡档",
            bodyCN = MODE_HEAD_CN + """
            - 本档放行转译层的全部意图（含点击/输入/打开/搜索/发送/确认/删除，以及全部 browse_* 网页操作），与上面两张意图表完全一致。
            - 不可用的只有「自写 shell 命令」与「直调无障碍端点」——那两项要在自由模式下才开放，本档不要输出。
            """.trimIndent(),
            bodyEN = MODE_HEAD_EN + """
            - This mode allows every translator intent (tap/input/open/search/send/confirm/delete and all browse_* web operations), exactly as the two intent tables above describe.
            - The only things unavailable are self-written shell commands and direct accessibility-endpoint calls; those require Free mode. Do not emit them here.
            """.trimIndent(),
        ),
        PromptBlock(
            id = "mode.free", group = PromptGroup.ACTION_MODE, order = 2, sep = "\n", safe = true,
            requires = setOf(PromptFlag.ACTION_FREE),
            exclusiveGroup = "action_mode",
            origin = "AgentPrompts.actionModeSection 自由档",
            bodyCN = MODE_HEAD_CN + """
            - 本档在均衡的基础上额外放行两类能力：自写命令（intent=shell）与直调无障碍端点（intent=a11y）。
            - 这两类是**兜底手段**：能用上面意图表说清楚的事，就用意图表；只有意图表确实表达不了时才动用它们。
            - 自写命令在本任务首次执行前会弹给用户确认一次（批准后本次任务内不再问；被拒后就别再发同一条命令，换个做法继续）。

            ## 自写命令（intent=shell）
            command 字段填命令原文。下面这张友好命令表和裸 shell 命令都能用：
            {shell_commands}

            裸命令示例（端侧原样交给 shizuku / 无线 ADB / Termux 执行）：
            {"intent":"shell","command":"pm list packages | grep 相机","reasoning":"查相机包名","expected":"列出相机相关包名","confidence":0.9}
            通道由端侧按可用性自动选（shizuku / 无线 ADB / Termux）；终端输出会作为「上一步结果」回给你。

            ## 直调无障碍端点（intent=a11y）
            endpoint 填端点名，端点所需参数直接写在 args 对象里：
            | 端点 | 参数 | 说明 |
            |---|---|---|
            {a11y_table}

            示例：{"intent":"a11y","endpoint":"click_node","args":{"target":"搜索"},"reasoning":"直点搜索控件","expected":"进入搜索页","confidence":0.9}
            端点名与参数名写错会被端侧直接指出（不重试）；坐标类端点只在元素树确实没有该控件时才用。
            """.trimIndent(),
            bodyEN = MODE_HEAD_EN + """
            - On top of Balanced, this mode also allows two extra capabilities: self-written commands (intent=shell) and direct accessibility-endpoint calls (intent=a11y).
            - Both are **fallbacks**: if the intent tables above can express it, use them; reach for these only when the tables genuinely cannot.
            - Your first self-written command in a task is confirmed with the user once (approved = never asked again within that task; if denied, do not resend the same command — take another route).

            ## Self-written command (intent=shell)
            Put the raw command in `command`. Both the friendly command table below and raw shell commands work:
            {shell_commands}

            Raw command example (executed verbatim via shizuku / wireless ADB / Termux):
            {"intent":"shell","command":"pm list packages | grep camera","reasoning":"find camera package","expected":"camera-related packages listed","confidence":0.9}
            The channel is auto-selected on-device (shizuku / wireless ADB / Termux); terminal output comes back as the previous step result.

            ## Direct accessibility endpoint (intent=a11y)
            Put the endpoint name in `endpoint` and its arguments in the `args` object:
            | Endpoint | Args | Description |
            |---|---|---|
            {a11y_table}

            Example: {"intent":"a11y","endpoint":"click_node","args":{"target":"Search"},"reasoning":"click the search control directly","expected":"search page opens","confidence":0.9}
            A wrong endpoint or argument name is reported back immediately (no retry); coordinate endpoints are only for controls the element tree truly lacks.
            """.trimIndent(),
        ),
    )

    // ==================== 功能可用性声明（两版互斥） ====================

    private val CAPABILITIES = listOf(
        PromptBlock(
            id = "cap.vision", group = PromptGroup.CAPABILITIES, order = 0, safe = true,
            requires = setOf(PromptFlag.HAS_VISION),
            exclusiveGroup = "capabilities",
            origin = "AgentPrompts.capabilitiesLang 有视觉版",
            bodyCN = "视觉理解：已启用。本轮已附带屏幕截图，可直接看图判断元素位置、图标与图表含义。",
            bodyEN = "Vision: enabled. A screenshot of the current screen is attached this turn — read it directly for element positions, icons and charts.",
        ),
        PromptBlock(
            id = "cap.novision", group = PromptGroup.CAPABILITIES, order = 1, safe = true,
            excludes = setOf(PromptFlag.HAS_VISION),
            exclusiveGroup = "capabilities",
            origin = "AgentPrompts.capabilitiesLang 无视觉版",
            bodyCN = "视觉理解：未启用。完全依赖元素树中的 id/label/坐标与文本进行操作。",
            bodyEN = "Vision: disabled. Rely entirely on element-tree id/label/coordinates and text.",
        ),
    )

    // ==================== 技能区块 ====================
    //
    // 原文由 append 逐行拼装（含运行期 MCP 表），故手写在这里。
    // 每块自带尾随换行；整组装配后由调用方 trimEnd()，与原文 return sb.toString().trimEnd() 一致。

    private val SKILLS = listOf(
        PromptBlock(
            id = "skills.head", group = PromptGroup.SKILLS, order = 0, safe = true,
            origin = "AgentPrompts.skillSection 固定段落",
            bodyCN = """
            # 技能（Skill）
            - 上面表里的能力既可用意图名调用，也可用技能 id 或技能名调用（如 skill_open_app / 打开应用），两者完全等价。
            - 用技能名调用时，参数可放进 args 对象，也可直接写成意图的扁平字段，两种写法等价：
              {"intent":"skill_swipe","args":{"direction":"up"}} 与 {"intent":"swipe","direction":"up"} 效果相同。
            - 被停用的技能不可调用，改用其它方式完成，或让用户在「技能与能力」页启用。
            """.trimIndent() + "\n",
            bodyEN = """
            # Skills
            - Every capability in the table above can also be invoked by skill id or skill name (e.g. skill_open_app), fully equivalent.
            - When invoking by skill name, parameters may go in an `args` object or directly as the intent's flat fields; both are equivalent:
              {"intent":"skill_swipe","args":{"direction":"up"}} equals {"intent":"swipe","direction":"up"}.
            - Disabled skills are not callable: use another way, or ask the user to enable them on the Skills page.
            """.trimIndent() + "\n",
        ),
        PromptBlock(
            id = "skills.mcp", group = PromptGroup.SKILLS, order = 1,
            requires = setOf(PromptFlag.MCP_TOOLS),
            origin = "AgentPrompts.skillSection MCP 表（mcpLines 非空）",
            bodyCN = """
            - 调用 MCP 技能：intent 填技能 id，参数放进 args 对象，例如：
              {"intent":"mcp_filesystem_read_file","args":{"path":"/sdcard/a.txt"},"reasoning":"读取文件","expected":"拿到文件内容","confidence":0.9}
            | 技能 id | 名称 | 说明 | 参数 |
            |---|---|---|---|
            {mcp_table}
            MCP 调用结果会作为上一步结果回给你；调用失败按普通步骤失败处理，用中文说明原因，禁止臆造返回内容。
            """.trimIndent() + "\n",
            bodyEN = """
            - To call an MCP skill: put the skill id in `intent` and the arguments in an `args` object, e.g.
              {"intent":"mcp_filesystem_read_file","args":{"path":"/sdcard/a.txt"},"reasoning":"read file","expected":"file content","confidence":0.9}
            | skill id | name | description | params |
            |---|---|---|---|
            {mcp_table}
            MCP results are returned to you as the previous step result; a failed call is a normal step failure — explain the reason in Chinese and never invent the result.
            """.trimIndent() + "\n",
        ),
        PromptBlock(
            id = "skills.mcp_server_only", group = PromptGroup.SKILLS, order = 1,
            requires = setOf(PromptFlag.MCP_SERVER_ONLY),
            origin = "AgentPrompts.skillSection「已配置但未绑定」",
            bodyCN = "- 已配置 MCP 服务器但尚未绑定技能：当前没有任何 MCP 技能可调用（需先在「技能与能力 → MCP」页点「绑定为技能」）。\n",
            bodyEN = "- MCP servers are configured but no tool is bound as a skill yet, so no MCP skill is callable (bind them on the MCP page first).\n",
        ),
        PromptBlock(
            id = "skills.disabled", group = PromptGroup.SKILLS, order = 2,
            requires = setOf(PromptFlag.DISABLED_SKILLS),
            origin = "AgentPrompts.skillSection 停用清单",
            bodyCN = "- 已停用（不可调用）：{disabled_names}\n",
            bodyEN = "- Disabled (not callable): {disabled_names}\n",
        ),
    )

    // ==================== 任务规划 ====================

    private val PLANNING = listOf(
        PromptBlock(
            id = "plan.header", group = PromptGroup.PLANNING, order = 0, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_HEADER_CN, bodyEN = PromptBodies.PLAN_HEADER_EN,
            origin = "AgentPrompts.planning 开头（模式 + 用户任务 + 偏好 + 已装应用）",
        ),
        PromptBlock(
            id = "plan.device_state", group = PromptGroup.PLANNING, order = 1, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_DEVICE_STATE_CN, bodyEN = PromptBodies.PLAN_DEVICE_STATE_EN,
            origin = "AgentPrompts.planning「# 当前设备状态」",
        ),
        PromptBlock(
            id = "plan.role", group = PromptGroup.PLANNING, order = 2, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_ROLE_CN, bodyEN = PromptBodies.PLAN_ROLE_EN,
            origin = "AgentPrompts.planning「# 角色」",
        ),
        PromptBlock(
            id = "plan.decomposition", group = PromptGroup.PLANNING, order = 3, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_DECOMPOSITION_CN, bodyEN = PromptBodies.PLAN_DECOMPOSITION_EN,
            origin = "AgentPrompts.planning「# 分解规则」",
        ),
        PromptBlock(
            id = "plan.env_intents", group = PromptGroup.PLANNING, order = 4, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_ENV_INTENTS_CN, bodyEN = PromptBodies.PLAN_ENV_INTENTS_EN,
            origin = "AgentPrompts.planning「# 环境与意图」",
        ),
        PromptBlock(
            id = "plan.doc_task", group = PromptGroup.PLANNING, order = 5, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_DOC_TASK_CN, bodyEN = PromptBodies.PLAN_DOC_TASK_EN,
            origin = "AgentPrompts.planning「# 文档类任务」",
        ),
        PromptBlock(
            id = "plan.ambiguity", group = PromptGroup.PLANNING, order = 6, sep = "\n\n",
            bodyCN = PromptBodies.PLAN_AMBIGUITY_CN, bodyEN = PromptBodies.PLAN_AMBIGUITY_EN,
            origin = "AgentPrompts.planning「# 歧义检测条件」",
        ),
        PromptBlock(
            id = "plan.output_format", group = PromptGroup.PLANNING, order = 7, sep = "",
            bodyCN = PromptBodies.PLAN_OUTPUT_FORMAT_CN, bodyEN = PromptBodies.PLAN_OUTPUT_FORMAT_EN,
            origin = "AgentPrompts.planning「# 输出格式」",
        ),
    )

    // ==================== 每步决策 ====================

    private val DECISION = listOf(
        // 原文里记忆是内联在"页面提示：…"同一行之后追加的，无法块级拼接，
        // 故把 header 原有的桥接空行拆成 header.sep("\n") + tri_state.prefix("\n")，
        // 中间正好留给按需注入的记忆段与经验规则段（两者各自以 "\n" 收尾）。
        PromptBlock(
            id = "dec.header", group = PromptGroup.DECISION, order = 0, sep = "\n", safe = true,
            bodyCN = PromptBodies.DEC_HEADER_CN, bodyEN = PromptBodies.DEC_HEADER_EN,
            origin = "AgentPrompts.decision 开头（任务/步骤/上一步/失败/页面提示）",
        ),
        PromptBlock(
            id = "dec.memory", group = PromptGroup.DECISION, order = 1, sep = "\n",
            requires = setOf(PromptFlag.HAS_MEMORY),
            origin = "AgentPrompts.memoryBlock（原本内联在页面提示行后）",
            bodyCN = "记忆（供参考，与当前页面冲突时以页面为准）：\n{memory}",
            bodyEN = "Memory (reference only; the current page wins on conflict):\n{memory}",
        ),
        PromptBlock(
            id = "dec.evolved_rules", group = PromptGroup.DECISION, order = 2, sep = "\n",
            requires = setOf(PromptFlag.HAS_EVOLVED_RULES),
            origin = "经验规则注入（见 RuleScoper）",
            bodyCN = "经验规则（往次任务提炼，仅在相关时参考；与当前页面冲突时以页面为准）：\n{evolved_rules}",
            bodyEN = "Learned rules (distilled from past tasks; use only when relevant; the current page wins on conflict):\n{evolved_rules}",
        ),
        PromptBlock(
            id = "dec.tri_state", group = PromptGroup.DECISION, order = 3, prefix = "\n", sep = "\n\n",
            bodyCN = PromptBodies.DEC_TRI_STATE_CN, bodyEN = PromptBodies.DEC_TRI_STATE_EN,
            origin = "AgentPrompts.decision「# 上一步结果三态」",
        ),
        PromptBlock(
            id = "dec.failure", group = PromptGroup.DECISION, order = 4, sep = "\n\n",
            bodyCN = PromptBodies.DEC_FAILURE_CN, bodyEN = PromptBodies.DEC_FAILURE_EN,
            origin = "AgentPrompts.decision「# 失败处理」",
        ),
        PromptBlock(
            id = "dec.iron_step", group = PromptGroup.DECISION, order = 5, sep = "\n\n",
            bodyCN = PromptBodies.DEC_IRON_STEP_CN, bodyEN = PromptBodies.DEC_IRON_STEP_EN,
            origin = "AgentPrompts.decision「# 本步要求」",
        ),
        PromptBlock(
            id = "dec.intent_timing", group = PromptGroup.DECISION, order = 6, sep = "\n\n",
            bodyCN = PromptBodies.DEC_INTENT_TIMING_CN, bodyEN = PromptBodies.DEC_INTENT_TIMING_EN,
            origin = "AgentPrompts.decision「# 意图选择时机」",
        ),
        PromptBlock(
            id = "dec.always.head", group = PromptGroup.DECISION, order = 7, sep = "\n",
            bodyCN = PromptBodies.DEC_ALWAYS_HEAD_CN, bodyEN = PromptBodies.DEC_ALWAYS_HEAD_EN,
            origin = "AgentPrompts.decision「# 随时可用的意图」标题行",
        ),
        PromptBlock(
            id = "dec.always.doc", group = PromptGroup.DECISION, order = 8, sep = "\n",
            bodyCN = PromptBodies.DEC_ALWAYS_DOC_CN, bodyEN = PromptBodies.DEC_ALWAYS_DOC_EN,
            origin = "AgentPrompts.decision 随时可用：文档",
        ),
        PromptBlock(
            id = "dec.always.remember", group = PromptGroup.DECISION, order = 9, sep = "\n",
            bodyCN = PromptBodies.DEC_ALWAYS_REMEMBER_CN, bodyEN = PromptBodies.DEC_ALWAYS_REMEMBER_EN,
            origin = "AgentPrompts.decision 随时可用：记忆",
        ),
        PromptBlock(
            id = "dec.always.device_query", group = PromptGroup.DECISION, order = 10, sep = "\n",
            bodyCN = PromptBodies.DEC_ALWAYS_DEVICE_QUERY_CN, bodyEN = PromptBodies.DEC_ALWAYS_DEVICE_QUERY_EN,
            origin = "AgentPrompts.decision 随时可用：查设备",
        ),
        PromptBlock(
            id = "dec.always.web", group = PromptGroup.DECISION, order = 11, sep = "\n",
            bodyCN = PromptBodies.DEC_ALWAYS_WEB_CN, bodyEN = PromptBodies.DEC_ALWAYS_WEB_EN,
            origin = "AgentPrompts.decision 随时可用：网页",
        ),
        PromptBlock(
            id = "dec.always.say", group = PromptGroup.DECISION, order = 12, sep = "\n",
            bodyCN = PromptBodies.DEC_ALWAYS_SAY_CN, bodyEN = PromptBodies.DEC_ALWAYS_SAY_EN,
            origin = "AgentPrompts.decision 随时可用：say",
        ),
        PromptBlock(
            id = "dec.always.show_agent", group = PromptGroup.DECISION, order = 13, sep = "\n\n",
            bodyCN = PromptBodies.DEC_ALWAYS_SHOW_AGENT_CN, bodyEN = PromptBodies.DEC_ALWAYS_SHOW_AGENT_EN,
            origin = "AgentPrompts.decision 随时可用：show_agent",
        ),
        PromptBlock(
            id = "dec.output", group = PromptGroup.DECISION, order = 14, sep = "",
            bodyCN = PromptBodies.DEC_OUTPUT_CN, bodyEN = PromptBodies.DEC_OUTPUT_EN,
            origin = "AgentPrompts.decision「# 输出」",
        ),
    )

    // ==================== 审核者 ====================

    private val REVIEW = listOf(
        PromptBlock(
            id = "rev.system", group = PromptGroup.REVIEW, order = 0, safe = true,
            bodyCN = PromptBodies.REV_SYSTEM_CN, bodyEN = PromptBodies.REV_SYSTEM_EN,
            origin = "AgentPrompts.reviewSystem",
        ),
    )

    // ==================== 记忆提炼 ====================

    private val DISTILL = listOf(
        PromptBlock(
            id = "dist.memory", group = PromptGroup.DISTILL, order = 0,
            bodyCN = PromptBodies.DIST_MEMORY_CN, bodyEN = PromptBodies.DIST_MEMORY_EN,
            origin = "AgentPrompts.memoryDistill",
        ),
    )

    /** 全部区块（55 块），按组拼接，组内已按 order 排序 */
    val all: List<PromptBlock> =
        SYSTEM + ACTION_MODE + CAPABILITIES + SKILLS + PLANNING + DECISION + REVIEW + DISTILL

    init {
        val dup = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "区块 id 重复：$dup" }
    }

    /** 取某组的区块（已按 order 排序；order 相同者按声明顺序，保证结果确定） */
    fun of(group: PromptGroup): List<PromptBlock> = all.filter { it.group == group }
}