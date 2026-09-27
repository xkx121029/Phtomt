# HPA 提示词系统文档

> 适用版本：当前 `app/src/main/java/com/phoneagent` 工作区（v0.1.5xx）
> 编写日期：2026-09-26
> 覆盖范围：引擎侧全部发给 AI 模型的提示词——动态块装配系统、各入口点结构、注入预算、同源点与安全网
> 说明：`杂项/` 下的 `HPA项目提示词文档.md`、`HPA项目AI提示词全集.md`、`HPA提示词文档.md` 是 **v2.0 时代（`action`/`abort` 术语）** 的旧文档，正文已与代码脱节，仅可作历史参考

---

## 一、一句话总览

提示词不再是写死在代码里的大段字符串，而是 **53 个可独立启停的区块 + 一组装配条件**，
由 `PromptAssembler` 按「语言 + 标志位 + 变量」现场装配；与当下无关的大块自动缺席，
铁律 / 授权范围 / 禁止输出 / 能力声明等安全块永不缺席。

```
调用点（AgentEngine / 引擎各阶段）
    │  形参（lang / actionMode / task / 记忆 / 经验规则 …）
    ▼
AgentPrompts            ← 门面：把形参翻译成 PromptFlag + PromptVars，调装配器
    │
    ▼
PromptAssembler         ← 选块(requires/excludes) → 拼接(order + prefix/sep) → 渲染({占位符})
    │           ▲
    │           └── PromptContext{ lang, flags, vars }
    ▼
PromptCatalog           ← 全项目提示词正文的唯一清单（53 块、8 组）
    │
    ▼
PromptBodies            ← 正文常量（internal，仅 SYSTEM/PLANNING/DECISION/REVIEW/DISTILL 用）
```

---

## 二、文件职责

| 文件 | 职责 |
|---|---|
| [PromptBlock.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/prompt/PromptBlock.kt) | 三个数据定义：`PromptGroup`（按引擎入口点分的 8 组）、`PromptFlag`（装配条件 11 位）、`PromptBlock`（区块：正文 + 条件 + 安全标记 + 互斥族 + 顺序 + 两端空白 + 溯源） |
| [PromptCatalog.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/prompt/PromptCatalog.kt) | **唯一清单**：53 个区块的 id / 条件 / order / prefix / sep / origin。构造期校验 id 不重复 |
| [PromptBodies.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/prompt/PromptBodies.kt) | 正文常量（中英各一份）。只有不含运行期表格的组放这里；`ACTION_MODE` / `CAPABILITIES` / `SKILLS` 三组因含插值表格，正文直接写在目录里 |
| [PromptAssembler.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/prompt/PromptAssembler.kt) | 装配核心：`select` 选块、`assemble` 拼接+渲染、`plan` 审计逐块在场原因、`unsafeCrops` 安全块裁剪自检 |
| [PromptTemplate.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/prompt/PromptTemplate.kt) | `PromptVars`（26 个变量的默认值）+ `PromptTemplateEngine.renderOnce`（单趟占位符替换） |
| [AgentPrompts.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/AgentPrompts.kt) | **门面**：双语提示词全部公开入口；本文件已无硬编码长文案，只做「形参 → 标志位/变量 → 装配」 |
| [TaskKindDetector.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/TaskKindDetector.kt) | 任务类型**唯一判定点**：裁块与附加指导共用一套词表，杜绝"裁了块却又按普通任务注入指导" |
| [MemoryBrief.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/MemoryBrief.kt) | 记忆简报（画像 + AI 记忆），严格限长 |
| [RuleScoper.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/engine/RuleScoper.kt) | 经验规则的作用域推断、命中筛选与注入护栏 |

---

## 三、装配流水线

`PromptAssembler.assemble(group, ctx)` 只做三件事：

1. **选块**：`flags.containsAll(requires) && excludes.none { it in flags }` 才在场。
   判定规则只有一个定义点（`PromptAssembler.isPresent`），改规则只改这一个函数。
2. **拼接**：按 `order` 排序（同 order 按声明顺序，结果确定），逐块 `prefix + body + sep` 首尾相接，
   **块间不插任何额外字符**。
3. **渲染**：整组拼完后一次性 `renderOnce`，把 `{变量}` 替换成 `PromptVars` 的值。

两条必须守住的约定：

- **`sep` 不是排版偏好**，而是从原文逐字节算出来的桥接换行。改 `sep` 等于改提示词。
- **整组一次渲染，而不是逐块渲染再拼接**：正文里的 `{by: id|text|hint}`、`{"intent":...}` 这类花括号
  不是占位符；单趟替换天然避开它们，用户任务里若恰好写了 `{task}` 也不会被二次展开。
  占位符正则只认标识符：`\{([A-Za-z_][A-Za-z0-9_]*)\}`；**变量缺失时保留原文**（`{xxx}` 直接暴露在提示词里），
  而不是静默变空串。

---

## 四、区块清单（8 组 / 53 块）

> 注：`PromptCatalog.kt` 里 `all` 的注释写的是「55 块」，实际为 **53 块**（SYSTEM 18 + ACTION_MODE 3 + CAPABILITIES 2 + SKILLS 4 + PLANNING 8 + DECISION 16 + REVIEW 1 + DISTILL 1）。该注释已陈旧。

### 4.1 SYSTEM（18 块）— 系统提示主体

| id | 内容 | 条件 | safe |
|---|---|---|---|
| `sys.role` | 角色声明（Phantom / Android 操控 Agent） | 无条件 | |
| `sys.iron` | 铁律 7 条（纯 JSON / 字段名只能是 intent / 一步一意图 / 按步执行 / 不确定先尝试 / 你是用户的手 / 任务间独立）；第 2 条随动作模式切换 | 无条件 | ✅ |
| `sys.completion` | 任务完成判定：必须亲眼看到证据才能 finish，summary 写明证据 | 无条件 | ✅ |
| `sys.page_data` | 页面数据字段表（elements / context_hint / page_type / fingerprint） | 无条件 | ✅ |
| `sys.intents` | 意图表（24 个 intent + 必填字段），含 `open` 四类分流与 browse_* 全套 | 无条件 | ✅ |
| `sys.intents_semantic` | 15 个高层语义意图（back/home/refresh/search/send/confirm/close/share/collect/copy/delete/download/add/switch/clear_input） | 无条件 | ✅ |
| `sys.web_browse` | 网页浏览大块（通道特权、何时用、边界、示例） | **`WEB_TASK`** | |
| `sys.open_link` | 打开链接与文件（open + uri 交系统应用） | **`OPEN_TASK`** | |
| `sys.targeting` | target 四级定位优先级（id > text > hint > coordinate） | 无条件 | ✅ |
| `sys.routing` | 独占路由规则 5 条（write_doc / open 四类 / device_query / say / show_agent） | 无条件 | ✅ |
| `sys.countdown` | 倒计时广告必须 wait、禁止 tap | 无条件 | ✅ |
| `sys.irreversible` | 不可逆操作必须带 `needs_confirmation` | 无条件 | ✅ |
| `sys.cn_apps` | 国产应用速查表 + 泛指类目优先系统自带 | 无条件 | |
| `sys.fields` | 统一字段表（intent/reasoning/expected/confidence/needs_confirmation/target） | 无条件 | ✅ |
| `sys.decision_rules` | 决策规则 7 条（前台对齐、弹窗优先级、无进展判定等） | 无条件 | |
| `sys.failure_paths` | 失败路径预定义表 | 无条件 | |
| `sys.merge` | 动作合并条件（最多 2 个） | 无条件 | |
| `sys.forbidden` | 禁止输出反例清单 | 无条件 | ✅ |

**只有 `sys.web_browse` / `sys.open_link` 两块会被裁**，其余在任何合法标志组合下恒在场。

### 4.2 ACTION_MODE（3 块，互斥族 `action_mode`）— 授权范围

三块共用同一段表头（`# 动作模式（当前：{mode_label}，{mode_summary}）`），由 `requires` 保证只出一块：

| id | 条件 | 内容要点 |
|---|---|---|
| `mode.conservative` | `ACTION_CONSERVATIVE` | 只放行 {low_risk}；其余一律被端侧拒绝；必须点击/输入时用 give_up 建议换档，不要反复重试 |
| `mode.balanced` | `ACTION_BALANCED` | 放行转译层全部意图；仅"自写命令 / 直调端点"留给自由模式 |
| `mode.free` | `ACTION_FREE` | 均衡 + `intent=shell`（含 `{shell_commands}` 友好命令表）+ `intent=a11y`（含 `{a11y_table}` 端点表）；自写命令首次执行前弹窗确认一次 |

三块均 `safe = true`：这是"提示词 ↔ 端侧门控"的同源点，AI 看到的可用清单就是 `ActionPolicy` 真实放行的清单。

### 4.3 CAPABILITIES（2 块，互斥族 `capabilities`）— 功能可用性

| id | 条件 | 内容 |
|---|---|---|
| `cap.vision` | `HAS_VISION` | 视觉已启用，本轮附带截图，可用 see 追问 |
| `cap.novision` | 排除 `HAS_VISION` | 视觉未启用，完全依赖元素树，需要看图时用 see |

**这是独立的一条 system 消息**（`AgentEngine` 第 1424 行），不拼进系统主体。

### 4.4 SKILLS（4 块）— 技能区块

| id | 条件 | 内容 |
|---|---|---|
| `skills.head` | 无条件 | 技能名/id 与意图等价、args 与扁平字段等价、停用技能不可调用 |
| `skills.mcp` | `MCP_TOOLS` | MCP 技能表 `{mcp_table}` + 调用格式 + "禁止臆造返回内容" |
| `skills.mcp_server_only` | `MCP_SERVER_ONLY` | 配了服务器但一个工具都没绑定，当前无 MCP 技能可调 |
| `skills.disabled` | `DISABLED_SKILLS` | 停用清单 `{disabled_names}` |

整组装配后 `trimEnd()`；无技能且无停用项时门面直接返回空串，不给 AI 增加负担。

### 4.5 PLANNING（8 块）— 歧义检测 + 任务规划

| id | 内容 |
|---|---|
| `plan.header` | 模式标题 + 用户任务 `{task}` + 偏好 `{profile}` + 已装应用 `{installed_apps}` |
| `plan.device_state` | 手机已解锁且停在 Happy Agent；禁止规划解锁/唤醒/回桌面/进入本应用（唯一例外 `show_agent`） |
| `plan.role` | 深度规划器角色 |
| `plan.decomposition` | 分解规则 7 条：粒度/全程/受阻/可验证/禁止浅层/**3~8 步宁少勿多**/纯对话直接 reply |
| `plan.env_intents` | 可用意图全表 + 上网/文件/网址三条分流 + 语义意图 + "端侧负责定位与坐标" |
| `plan.doc_task` | 文档类任务规划 write_doc，必要时补一步 show_agent |
| `plan.ambiguity` | 歧义检测 5 类条件 |
| `plan.output_format` | 三种输出形态（reply / plan / clarification），只选一种 |

### 4.6 DECISION（16 块）— 每步决策

| id | 条件 | 内容 |
|---|---|---|
| `dec.header` | 无条件 safe | 【执行决策】任务 / 步骤 `{step_index}/{total_steps}` `{current_step}` / 上一步结果 `{last_result}` / 连续失败 `{failures}` / 页面提示 `{context_hint}` |
| `dec.memory` | `HAS_MEMORY` | 记忆段 `{memory}`（与页面冲突时以页面为准） |
| `dec.evolved_rules` | `HAS_EVOLVED_RULES` | 经验规则段 `{evolved_rules}` |
| `dec.tri_state` | 无条件 | 上一步结果三态（✅ 继续 / ⚠️ 先确认不要重发 / ❌ 换方式重试） |
| `dec.failure` | 无条件 | 失败处理：1~2 次换方式，3 次 give_up |
| `dec.iron_step` | 无条件 | 本步铁律：一步一动作、前台对齐、定位优先级、视觉追问用 see |
| `dec.intent_timing` | 无条件 | 何时必须用 swipe/scroll_to、long_press、wait |
| `dec.always.head` | 无条件 | 「# 随时可用的意图」标题行 |
| `dec.always.doc` | 无条件 | write_doc |
| `dec.always.remember` | 无条件 | remember（只记真正有长期价值的） |
| `dec.always.device_query` | 无条件 | device_query（不要翻设置页、不要每步查） |
| `dec.always.web` | 无条件 | browse_* 全流程 + open 分流 |
| `dec.always.say` | 无条件 | say（不代替动作，不连续超过 2 次） |
| `dec.always.show_agent` | 无条件 | show_agent（请人过来看，用户已在 Agent 页时不用） |
| `dec.always.see` | 无条件 | see（一次只问一件事，已问过不重复） |
| `dec.output` | 无条件 | 输出格式：单个 JSON 或数组合并（最多 2 个） |

`dec.memory` / `dec.evolved_rules` 由 `HAS_MEMORY` / `HAS_EVOLVED_RULES` 控制；两段都缺席时
`dec.header` 的 `sep="\n"` 与 `dec.tri_state` 的 `prefix="\n"` 正好接成原文的空行。

### 4.7 REVIEW（1 块）— 审核者

`rev.system`：资深审核员，只信任「当前页面」的真实元素树与前台应用，输出
`{"pass":bool,"why":"…","freefix":{…}|null}`。审核的 user 段在 `AgentEngine` 现场拼（任务 / 执行者拟执行意图 / 当前页面 / 页面提示）。

### 4.8 DISTILL（1 块）— 记忆提炼

`dist.memory`：任务结束后提炼长期信息（preference/fact/habit/tip），**最多 3 条、宁缺勿滥**，输出 JSON 数组。

---

## 五、标志位（PromptFlag，11 位）

| 标志 | 何时置位 | 影响 |
|---|---|---|
| `HAS_VISION` | **本轮真的带了屏幕截图**（不是用户勾的开关，见引擎 `effectiveHasVision`） | `cap.vision` / `cap.novision` 二选一 |
| `ACTION_CONSERVATIVE` / `ACTION_BALANCED` / `ACTION_FREE` | 引擎每轮必定且只置一个（来自 `AppSettings.actionMode`） | 动作模式三选一 |
| `WEB_TASK` | `TaskKindDetector` 命中网页类词 **或任务文本未知** | 注入 `sys.web_browse` |
| `OPEN_TASK` | 命中打开/直达类词 **或任务文本未知** | 注入 `sys.open_link` |
| `HAS_MEMORY` | 记忆简报非空 | 注入 `dec.memory` |
| `HAS_EVOLVED_RULES` | 有命中的经验规则 | 注入 `dec.evolved_rules` |
| `MCP_TOOLS` | 有已启用 MCP 技能 | 注入 `skills.mcp` |
| `MCP_SERVER_ONLY` | 配了 MCP 服务器但零绑定 | 注入 `skills.mcp_server_only` |
| `DISABLED_SKILLS` | 有被停用的技能 | 注入 `skills.disabled` |

**关键约定：任务文本未知时不裁。** `WEB_TASK` / `OPEN_TASK` 表达的是"可能与网页/打开相关"，
文本为空等于"无法判断"，此时保留对应区块，宁可多给也不误裁。

---

## 六、占位符（PromptVars）

| 变量 | 注入来源 |
|---|---|
| `task` | 用户任务原文（任务中途的用户指导已并入） |
| `last_result` / `lastResult` | 上一步执行结果 |
| `iron_rule_2` | `ironRule2CN/EN(actionMode)`：自由模式放开 shell/a11y，其余模式禁止命令与坐标 |
| `common_cn_apps` | `COMMON_CN_APPS` 常量（23 个国产应用 中文名=英文名/包名） |
| `irreversible_words` | `BrowserGuard.promptWords()` / `promptWordsEn()`（与端侧门控同源） |
| `mode_label` / `mode_summary` / `mode_label_en` / `mode_key` / `mode_summary_en` | `ActionMode` 枚举字段 |
| `low_risk` | `ActionPolicy.lowRisk`（22 项）斜杠连接 |
| `shell_commands` | `ShellCommands.promptDoc(lang)`（友好命令表 + 示例 + 裸包名自动补全说明） |
| `a11y_table` | `ActionPolicy.a11yEndpoints`（18 项）生成的 Markdown 表格行 |
| `mcp_table` | `SkillExecutionGateway.mcpSkillLine` 逐行 `| … |` |
| `disabled_names` | 停用技能名（中文顿号 / 英文逗号分隔） |
| `profile` | 用户画像（空则填「无」/「none」） |
| `installed_apps` | 已安装可启动应用清单（空则填「未知」/「unknown」） |
| `step_index` / `total_steps` / `current_step` / `failures` / `context_hint` | 引擎实时状态 |
| `memory` / `evolved_rules` | `MemoryBrief.build` / `RuleScoper.brief` |
| `outcome` / `steps_summary` | 记忆提炼的输入（`steps_summary` 截断 1500 字符） |

---

## 七、各入口点结构

### 7.1 系统提示（`AgentPrompts.system`）

```
[ 系统主体 ]              ← custom 非空时直接用用户自定义，否则装配 SYSTEM 组 18 块
"\n\n"
[ 动作模式段 ]            ← 必追加，无论是否自定义提示词
[ "\n\n" + 技能区块 ]     ← skills 非空时追加
```

**为什么动作模式段必追加**：它是端侧真实拒绝逻辑的说明书；缺了它，AI 会按自己的想象发请求，
然后在门控那里反复撞墙。

### 7.2 规划（`AgentPrompts.planning`）

装配 `PLANNING` 组 8 块，注入 `task` / `profile` / `installed_apps` / `common_cn_apps` / `irreversible_words`。

### 7.3 每步决策（`AgentPrompts.decision` + 引擎现场拼接）

`AgentEngine` 第 2205–2224 行的实际顺序：

```
AgentPrompts.decision(...)                       ← DECISION 组 16 块
+ planNote
+ "\n\n## 当前页面\n" + pageText                 ← 元素树文本
+ PageAnnotator.knownControlsText(elements)      ← 语义 ID 已知控件速查
+ AgentPrompts.situationalExtras(...)            ← 附加指导（4 类，见 7.5）
+ taskMemoryText()                               ← 任务记忆（目标/需求/已验证方法/状态/步骤）
+ AgentPrompts.environment(...)                  ← 环境上下文（时间/前台应用/网络/电量/应用数）
+ sessionContextText(task, lang)                 ← 会话承接（本对话更早的往来，最多 3 轮）
```

这份 `userText` 同时通过 `pushThinking` 实时展示在悬浮窗与 Agent 页，是排查提示词的**第一现场**。

### 7.4 环境上下文（`AgentPrompts.environment`）

| 行 | 内容 |
|---|---|
| 当前时间 | 决定"明天"是哪天 |
| 前台应用 | 决定面前这页属于谁 |
| 网络 / 电量 | 实时采集，空值填「未知」 |
| 已安装应用 | **只给数量**，清单需 AI 自己用 `device_query kind=apps` 查（可用 `filter` 缩小） |

### 7.5 会话承接（`AgentPrompts.sessionContext`）

把**本对话内**更早的往来（最多 3 轮）摆给 AI，`followUp` 为真（用户用了指代词）时额外强调"本轮说的是上一轮"。
无往来时返回空串。

### 7.6 按需附加指导（`AgentPrompts.situationalExtras`）

由 `TaskKindDetector.detect` 判定后按需追加，**与系统提示的裁块同源**：

| 命中 | 注入内容 | 关键约束 |
|---|---|---|
| `docHit`（且未 `openTargetHit`） | write_doc 完整模板 + 铁律 | 禁止屏幕打字/开记事本/shell 写文件 |
| `openHit` | open 直达说明 + `AppPageIndex.indexText()` 软件页面索引 | uri 必须是用户给的或上一步真实出现的 |
| `browseHit` | browse_* 用法 + 边界 + 通道特权 | 网页元素只能 browse_click 按文字点；不可逆词表来自 `BrowserGuard.promptWords()` |
| `fetchHit`（需 Termux 可用） | fetch 用法 + 边界 | 只用于纯文本接口；网页界面一律 browse_* |

`docHit` 与 `openTargetHit` **互斥**：后者命中时压掉前者，否则"用文档软件打开这个 ppt"会被注入 write_doc 模板而去"写"文档。

---

## 八、同源点（提示词 ↔ 端侧逻辑的唯一事实来源）

| 提示词里的内容 | 端侧定义点 | 保证 |
|---|---|---|
| 可用意图清单 | `ActionPolicy.lowRisk/midRisk/highRisk/freeOnly` | 提示词说能用 = 端侧真的能放行 |
| 不可逆操作词表 | `BrowserGuard.WORDS_CN/WORDS_EN`（19+13） | 端侧判定 / 注入脚本探针 / 提示词三处同源 |
| 友好命令表 | `ShellCommands.promptDoc` | 与命令解析同一份 |
| MCP 技能表 | `SkillExecutionGateway.enabledMcpSkills` + `mcpSkillLine` | 只列"已绑定且已启用"的技能 |
| 无障碍端点表 | `ActionPolicy.a11yEndpoints`（18 项） | 与 `ActionExecutor` 的 public 端点一致 |
| 意图枚举（结构化回退） | `IntentType.ALL` 插值进 `AiClient.ACTION_SCHEMA` 的 enum | 新增意图不会在第一道 schema 就被判非法 |
| 任务类型词表 | `TaskKindDetector` | 裁块与附加指导不会错位 |
| 软件页面索引 | `AppPageIndex.indexText()` | 与 open 的 app+page 解析同源 |
| 页面语义 ID | `PageAnnotator`（26 类控件 → semantic_id） | `sys.page_data` 里承诺的 semantic_id 真有值 |

---

## 九、注入预算与护栏

| 注入项 | 上限 | 定义点 |
|---|---|---|
| 记忆简报 | 总量 600 字符 / 单条 60 / 画像 200 | `MemoryBrief` |
| 经验规则 | 置信度 ≥ 0.6、最多 5 条、总量 800 字符、单条 200 字符、模糊去重 | `RuleScoper` |
| 会话承接 | 最多 3 轮，每轮目标与结论各截 120 字符 | `AgentEngine.MAX_PREVIOUS_TASKS` |
| 已装应用清单（规划时） | 300 条；截断时**显式写明**「仅列前 N 个，共 M 个，可用 device_query kind=apps 配合 filter 查」 | `AgentEngine` |
| device_query 输出 | 9000 字符；截断时追加「剩下 N 个请用 filter 缩小范围」 | `AgentEngine.MAX_DEVICE_QUERY_OUTPUT` |
| 记忆提炼输入 | 执行摘要 1500 字符 | 提炼调用点 |
| MCP 技能调用 | 超时 20 秒、输出截断 1200 字符、连续 3 次拒绝即停机 | 技能网关 / 引擎 |
| 单步决策看门狗 | 超时改为 wait，下一轮重试 | 引擎 |

> 铁律：**给 AI 的应用清单绝不静默截断**——被切掉的应用在 AI 眼里等同于"没安装"，
> 会直接导致误判为需要澄清或放弃任务。

---

## 十、语言与用户自定义

- **语言**：`AppSettings.promptLanguage`（`CN` / `EN`）→ `PromptLang`，决定取 `bodyCN` 还是 `bodyEN`。
  解析失败回落 `CN`。
- **自定义系统提示**：`AppSettings.systemPrompt` 非空时，**替换整个系统主体**（SYSTEM 组 18 块）；
  但 **动作模式段与技能区块仍会追加**。设置页「Agent 设置」提供编辑框，可一键清空回落到内置提示词。
- 英文提示词里的表格、示例、说明全部是英文，不混中文（`A11yEndpoint` 带 `descriptionEn` 就是为此）。

---

## 十一、安全网（单测）

| 测试 | 锁定什么 |
|---|---|
| [PromptSnapshotTest.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/test/java/com/phoneagent/engine/PromptSnapshotTest.kt) | **等价性金样本**：`app/src/test/resources/prompt_golden/snapshot.sha256` 逐节 SHA-256 指纹。指纹相等即逐字节相等。任何文案改动、空行增删、占位符渲染失败都会以节名报出来 |
| [PromptCropTest.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/test/java/com/phoneagent/engine/prompt/PromptCropTest.kt) | 三条不变式：① 安全块在任意合法标志组合下恒在场；② 系统提示只裁 `sys.web_browse` / `sys.open_link`；③ 裁块与附加指导判定同源 |
| [PromptRuleTest.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/test/java/com/phoneagent/engine/PromptRuleTest.kt) | 经验规则护栏：作用域推断、命中判定、条数/字数/置信度上界、去重 |
| [AgentPromptsContextTest.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/test/java/com/phoneagent/engine/AgentPromptsContextTest.kt) | 环境上下文、会话承接、动作模式三档、自定义提示词覆盖行为 |
| [PromptTemplateEngineTest.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/test/java/com/phoneagent/prompt/PromptTemplateEngineTest.kt) | 渲染器：单趟替换、缺变量保留原文、不二次展开 |

---

## 十二、维护指南

### 改一句文案

1. 改 `PromptBodies.kt`（或目录里的内联正文）；
2. **重导金样本**，并确认 diff 只有你改的那一句；
3. 跑全量单测。

金样本重导方式：`PromptSnapshot.fingerprints()` 的输出即金样本内容（该对象与测试同文件，既是断言也是导出工具）。

### 新增一个区块

1. 在 `PromptBodies.kt` 写中英正文（含变量用 `{占位符}`；注意 `trimIndent()`）；
2. 在 `PromptCatalog.kt` 的对应组里追加 `PromptBlock`：
   - `order` 必须与目标顺序一致（同 order 走声明顺序）；
   - `prefix` / `sep` 从原文字面量精确算出（**这是最易错的一步**）；
   - 是否 `safe`（铁律 / 授权范围 / 禁止输出 / 能力声明必须 `safe = true`）；
   - 如需二选一，挂同一 `exclusiveGroup`；
   - 填 `origin` 说明这段正文原来在哪；
3. 若需要新条件，加 `PromptFlag` 并在门面构造 `flags`；
4. 更新金样本 + 补单测。

### 常见坑

| 坑 | 说明 |
|---|---|
| 改 `sep` 当排版 | `sep` 是从原文逐字节算的桥接换行，改它 = 改提示词（金样本会红） |
| 给 safe 块挂 `requires` | `PromptCropTest` 会报"安全块被裁掉"；互斥族是唯一例外（同族出一块即合法） |
| 任务文本为空时裁块 | 违反"任务未知时不裁"约定，能力说明会被整块裁掉 |
| 逐块渲染再拼接 | 会把正文里的 JSON 花括号当占位符处理；必须整组一次渲染 |
| 应用清单静默截断 | 被截掉的应用在 AI 眼里等同于没安装 |
| 只改提示词不改端侧 | 提示词与门控必须同源；改了 `ActionPolicy` 就该同步检查提示词（反之亦然） |

---

## 十三、附录：其他模型侧提示词

| 场景 | 位置 | 内容 |
|---|---|---|
| 看图追问（`see` / 元素树稀疏兜底） | `AiClient.visionAsk` | 调用方现场给一句"这次看图的目的"；要坐标时要求返回 `{"x":0~1,"y":0~1}`，否则要求中文描述相关元素与位置。上下文干净：单条 user 消息，不带历史 |
| 结构化回退 | `AiClient.ACTION_SCHEMA` | `intent` 的 enum 由 `IntentType.ALL` 插值生成，防止新意图在 schema 处被判非法 |
| 审核者 user 段 | `AgentEngine` 第 2407–2420 行 | `## 任务` / `## 执行者拟执行意图` / `## 当前页面（真实证据）`（含前台应用）/ `## 页面提示` |
| 输出翻译 | `AgentEngine.translateText` | 「请把下面内容翻译成简体中文。只输出译文本身，不要任何修饰或解释：…」，带缓存 |
| 记忆提炼 user 段 | `AgentPrompts.memoryDistill` | 任务 / 结果 / 执行过程摘要 |

### 温度（`EngineRules` + `AgentEngine`）

| 阶段 | 温度 |
|---|---|
| 每步决策（正常） | 0.1 |
| 失败 ≥ 3 次（重规划） | 0.5 |
| 歧义检测 + 规划 | 0.3 |
| 审核者 / 智能重填 | 0.1 |
| 看图追问 / 记忆提炼 | 0.2 |
