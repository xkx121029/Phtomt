# Eta 与 Phantom：向 AI 开放的工具 / 提示词 对比清单

> 对比对象
> - Eta：<https://github.com/Mangi-11/Eta>，本地源码 `C:\Users\linch\AppData\Local\Temp\eta-src`
> - 本项目：`happy_phone agent`（下称“我们”）
> - 生成日期：2026-11-03
>
> 统计口径
> - Eta 的“工具”= OpenAI/Anthropic function calling 的 `tools` JSON Schema，由 `AgentToolCatalog.build(...)` 每轮组装、`AgentToolCapabilities.project(...)` 按运行条件裁剪。
> - 我们的“能力”= 单一 intent 对象里的一个 `intent` 值，声明点唯一在 `CapabilityRegistry`，同时派生运行时门控、技能目录与提示词意图表。
> - 两者数量不能直接相减：Eta 一项工具 ≈ 我们一项能力或若干项能力之和。

---

## 0. 结论（先看这里）

1. **架构路线不同，不是谁多谁少**：Eta 把能力声明放进机器可读的 JSON Schema（模型按函数签名调用，参数错误在协议层就被挡住）；我们把能力声明写进提示词 Markdown 意图表（模型输出单意图 JSON，端侧门控兜底）。
2. **Eta 的工具面显著更宽**：常规档约 48 项、全开约 124 项（含 41 项敏感只读、23 项手机域、9 项设备直连、7 项文件、3 项终端、2 项子代理）；我们固定 46 项能力，其中屏幕操作占 23 项。
3. **Eta 的设备直连覆盖面更广**：闹钟/计时器之外还有音量、媒体、手电筒、亮度、息屏、勿扰、响铃、热点、日历 CRUD、闹钟读改删、便签 CRUD、系统开关、应用冻结。
4. **我们在“语义化 + 风险分级 + 中文运营规则”上更强**：15 个高层语义意图（返回/回桌面/刷新/关闭/收藏/复制/清空/搜索/分享/下载/新增/切换/发送/确认/删除），四档风险 + 三档动作模式 + 不可逆确认，以及目标定位、动作合并、失败路径、倒计时广告、国产应用速查等中文场景规则。
5. **Eta 的提示词只讲纪律，不讲清单**：能力在 `tools` 里，system 只写“怎么用、什么时候不该用”（观察/坐标空间/observation_id/lease/结果三态）；我们把“有哪些能力”也写进 system，因此提示词更长、更依赖金样本锁字节。
6. **最值得迁移的三个结构**：①按运行条件投影能力（不可用能力从提示词里直接消失，而不是等运行时拒绝）；②补 `wait_for_text` / `wait_for_package` 两个可验证等待；③给 `CapabilityParam` 补机器可校验的取值范围/枚举。
7. **不建议照搬的部分**：roleplay/人格系统、虚拟屏 `present_to_user`、个人数据全量检索、子代理——它们是 Eta 的产品定位带来的，不是“手机操控 Agent”的必需面。

---

## 1. 总览数字

| 维度 | Eta | 我们 |
|---|---|---|
| 能力暴露方式 | function calling（`tools` JSON Schema） | 单意图 JSON（`intent` + 扁平字段/`args`） |
| 单轮可调用数量 | 允许多个工具同轮并行 | 铁律第 3 条：每步只输出一个意图 |
| 能力数量 | 常规档 48 项；全开约 124 项 | 46 项 |
| 风险/权限模型 | Root 三档（NONE/PARTIAL/REQUIRED）+ 2 个敏感开关 + 每轮能力投影 | 4 档 RiskTier（LOW 25 / MID 16 / HIGH 3 / EXT 2）+ 3 档动作模式 |
| 执行通道 | Root Shell / 无障碍 / 离屏浏览器 / 文件 / 终端 / 虚拟屏 | SCREEN 23 / BROWSER 6 / LOCAL 10 / SHELL 2 / CLOCK 2 / VISION 1 / VOICE 1 / A11Y 1 |
| 提示词规模 | 1 段主 system + 5 段条件 system（终端/托管搜索/浏览器/子代理/人格）+ 记忆段 + 技能段 | 9 组 60 个区块（SYSTEM 21 / ACTION_MODE 3 / CAPABILITIES 2 / SKILLS 6 / PLANNING 9 / DECISION 16 / DOCS 1 / REVIEW 1 / DISTILL 1） |
| 提示词稳定性 | 系统提示只放会话内稳定事实，靠前缀缓存；瞬时状态走工具结果 | 逐区块 sha256 金样本锁字节（`PromptSnapshotTest`） |
| 观察机制 | `observe_screen` 显式观察，节点 + `observation_id` + 可选截图；动作结果带 `after` 新界面 | 引擎每步自动采集页面块，无显式观察工具、无 `observation_id` |

---

## 2. Eta 工具目录（按 catalog）

组装入口：`AgentToolCatalog.build(...)`，按开关依次追加；裁剪入口：`AgentToolCapabilities.project(...)`。

| catalog | 数量 | 工具 | 公开条件 / 备注 |
|---|---|---|---|
| `AgentContextAppToolCatalog` | 7 | get_current_context / search_apps / launch_app / present_to_user / open_uri / observe_screen / release_gui | 始终公开 |
| `AgentGestureToolCatalog` | 8 | tap / tap_area / tap_element / long_press / long_press_element / swipe / scroll / scroll_element | 始终公开；坐标工具强制 `coordinate_space`，节点工具强制 `observation_id` |
| `AgentTextSystemToolCatalog` | 8 | type_text / set_clipboard / get_clipboard / press_key / wait / wait_for_text / wait_for_package / open_system_panel | 始终公开 |
| `AgentDeviceToolCatalog` 直连 | 9 | inspect_app / set_alarm / set_timer / device_status / network_info / top_memory_apps / top_storage_apps / media_control / set_volume | `deviceDirectTools` |
| `AgentPhoneToolCatalog` | 23（直连 8 / 只读 3 / 写 12） | 手电筒 2、系统开关 1、显示 3、声音 2、勿扰 1、热点 2、日历 6、闹钟 3、便签 3 | 按 root 与权限逐条投影 |
| 敏感只读 | 41（个人检索 10 + 系统 31） | 个人：search_bills / search_todos / search_calendar_todos / search_memory_collections / search_daily_events / search_flights / search_hotels / search_trains / read_personal_item / summarize_bills；系统：get_setting / wifi_credentials / recent_notifications / search_notification_history / recent_app_activity / app_usage_summary / get_current_location / get_device_environment / list_alarms / list_active_timers / search_clipboard_history / get_health_summary / read_sms_code / get_logcat / search_media / search_audio / search_recordings / search_files / search_calendar_events / search_contacts / search_call_history / search_messages / search_downloads / search_notes / search_coloros_recordings / search_recording_summaries / search_system_memories / search_saved_places / search_personal_orders / search_qq_chat_images / search_wechat_chat_images | `deviceSensitiveReadTools`；结果不进入持久历史 |
| 敏感写 | 3 | set_setting / set_device_state / app_state_control | `deviceSensitiveActionTools`；多数需 Root |
| `AgentWebToolCatalog` | 2 | web_search / fetch_url | `browserTools`；`web_search` 可选 |
| `AgentBrowserToolCatalog` | 1 | browser_use（13 个 action：navigate / get_readable / get_text / find_elements / click / type / scroll / screenshot / get_page_info / go_back / go_forward / reload / wait_for_selector） | `browserTools` |
| `AgentSkillToolCatalog` | 3～6 | skills_list / skills_read / skills_read_resource；+ skills_list_curated / skills_inspect_github / skills_install_from_github | GitHub 发现/安装各一个开关 |
| `AgentMemoryToolCatalog` | 2 | memory_get / memory_write（revision CAS + replace_range/append/clear） | `memoryTools`；`memoryWritable` |
| `AgentTerminalToolCatalog` | 3 | terminal（exec/open/close/check_root）/ terminal_job / terminal_daemon | `terminalTools` |
| `AgentFileVisionToolCatalog` | 1 | read_image | `terminalTools` |
| `AgentFileToolCatalog` | 7 | read_file / write_file / edit_file / stat_file / list_directory / glob_files / grep_files | `terminalTools`；支持 environment=android/linux |
| `AgentSubagentToolCatalog` | 2 | subagent / subagent_profile | 引擎侧追加，不在此 build 内 |
| `AgentConversationToolCatalog` | 1 | conversation_history | 同上 |

---

## 3. 逐项对照：Eta 工具 → 我们的对应能力

判定：`对等` / `更强`（我们更强）/ `更弱` / `缺失`（我们没有）。

### 3.1 上下文、应用与屏幕

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| get_current_context | DEVICE_QUERY(kind=time) | 更弱 | 我们只到本机时间，无时区、无最近位置 |
| search_apps | DEVICE_QUERY(kind=apps, filter) | 更弱 | 无 `include_system` / `limit`，一次性返回 |
| launch_app | OPEN_APP | 对等 | 我们都支持名称/包名解析；Eta 多 `display=auto/virtual/main` 画布选择 |
| present_to_user | 无 | 缺失 | 我们引擎内部有副屏（VirtualDisplayAgent），但没有面向 AI 的“移交到主屏”能力 |
| open_uri | OPEN | 对等 | 我们多了 `app+page` 页面直达索引 |
| observe_screen | 引擎每步自动观察 | 机制不同 | 我们无显式观察工具、无 `include_screenshot/max_nodes` 开关、无 `observation_id` |
| release_gui | 无 | 缺失 | 我们单任务独占 GUI，无租约概念 |

### 3.2 手势

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| tap / tap_area | TAP（自由模式另有 a11y.click / click_node） | 我们更强（语义）/ 更弱（坐标） | 我们按 `ctl_N`/文字定位，Eta 支持矩形中心与比例坐标 |
| tap_element | TAP | 对等 | 我们无 `observation_id` 一致性校验 |
| long_press / long_press_element | LONG_PRESS | 对等 | 我们 `duration_ms` 默认 800 |
| swipe | SWIPE | 更弱 | 我们只有方向，无起止坐标、无 `duration_ms` |
| scroll | SWIPE(direction) / SCROLL_TO | 更弱 | 我们无 `amount=page/small` 粒度 |
| scroll_element | 无 | 缺失 | 不能指定某个可滚动容器滚动 |

### 3.3 文本、等待与系统操作

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| type_text | INPUT | 更弱 | 我们无 `mode=append`、无 `submit=true`、不能按节点 index 直写（必须先点 target） |
| set_clipboard / get_clipboard | 无 | 缺失 | 只能自由模式 shell 间接做 |
| press_key | PRESS | 更弱 | 我们有 BACK/HOME/ENTER/RECENT；Eta 另有 PASTE/NOTIFICATIONS/QUICK_SETTINGS |
| wait | WAIT | 对等 | 都是固定毫秒等待 |
| wait_for_text | 无 | 缺失 | 我们只能“等固定时间 + 重新观察”，失败多耗一轮 |
| wait_for_package | 无 | 缺失 | 打开应用后无法等目标应用真正到前台 |
| open_system_panel | 无（a11y.global_action=4 可近似） | 更弱 | 仅自由模式可绕 |

### 3.4 设备直连

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| set_alarm | SET_ALARM（CLOCK 通道） | 我们更强 | 我们支持 `when` 口语解析（7 点半/半小时后/明天早上 6 点/每天 7 点），Eta 要求模型自己算好 hour/minute；Eta 多 `vibrate` |
| set_timer | SET_TIMER（CLOCK 通道） | 对等 | 都是 AlarmClock 意图直达，`verified=false` 如实回报 |
| inspect_app | 无 | 缺失 | 版本/UID/来源/权限列表 |
| device_status | DEVICE_QUERY(kind=all) 部分 | 更弱 | 我们无内存、开机时长 |
| network_info | DEVICE_QUERY(kind=network) | 更弱 | 我们只给联网方式 |
| top_memory_apps / top_storage_apps | 无 | 缺失 | |
| media_control | 无 | 缺失 | 播放/暂停/上下一首 |
| set_volume | 无 | 缺失 | 6 条音量通道 + 百分比 |
| get_flashlight / set_flashlight | 无 | 缺失 | |
| get_device_state / set_device_state | 无 | 缺失 | Wi‑Fi/蓝牙/移动数据/飞行模式/定位/NFC/省电/旋转/亮度/深色 |
| get_display_state / set_brightness / set_screen_timeout | 无 | 缺失 | |
| get_sound_state / set_sound_mode / set_do_not_disturb | 无 | 缺失 | |
| get_hotspot / set_hotspot | 无 | 缺失 | |
| list_calendars / read_calendar_event / create_calendar_event / create_calendar_events / update_calendar_event / delete_calendar_event | 无 | 缺失 | 完整日历 CRUD |
| update_alarm_time / set_alarm_enabled / delete_alarm | 无 | 缺失 | 我们只能新增闹钟，不能改/关/删（AlarmClock 无公开删除接口，Eta 走 Root 私有 Provider） |
| create_note / read_note / delete_note | 无 | 缺失 | 便签 CRUD |
| app_state_control | 无 | 缺失 | 强制停止/冻结/解冻 |

### 3.5 敏感只读与个人数据

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| get_setting | 无 | 缺失 | |
| wifi_credentials | 无 | 缺失 | |
| recent_notifications | NOTIF_READ | 对等 | 我们读全部通知，无包名过滤/条数上限 |
| search_notification_history | 无 | 缺失 | 7 天通知历史 |
| recent_app_activity / app_usage_summary | 无 | 缺失 | 需要使用情况访问权 |
| get_current_location / get_device_environment | 无 | 缺失 | |
| list_alarms / list_active_timers | 无 | 缺失 | 我们无法“读出已有闹钟” |
| search_clipboard_history | 无 | 缺失 | |
| get_health_summary / read_sms_code | 无 | 缺失 | |
| get_logcat | 无 | 缺失 | |
| 个人检索 10 项（账单/待办/日历待办/记忆合集/生活事件/航班/酒店/火车/read_personal_item/summarize_bills） | 无 | 缺失 | 这是 Eta 的“个人数据上下文”产品面 |
| search_media / search_audio / search_recordings / search_files / search_calendar_events / search_contacts / search_call_history / search_messages / search_downloads / search_notes / search_coloros_recordings / search_recording_summaries / search_system_memories / search_saved_places / search_personal_orders / search_qq_chat_images / search_wechat_chat_images | 无 | 缺失 | 同上 |

### 3.6 网页与浏览器

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| web_search | 无 | 缺失 | 我们只有内置浏览器 + Termux 取正文，没有搜索工具 |
| fetch_url（support document_id 续页/offset/max_chars） | FETCH（SHELL 通道，需 Termux） | 更弱 | 依赖 Termux，无缓存续页 |
| browser_use（13 action） | BROWSE_OPEN / BROWSE_READ / BROWSE_CLICK / BROWSE_INPUT / BROWSE_SCROLL / BROWSE_BACK | 略弱 | 我们按意图拆分更安全、参数更少；Eta 多 screenshot / get_page_info / reload / go_forward / wait_for_selector |

### 3.7 技能、记忆、终端、文件、子代理

| Eta 工具 | 我们的对应 | 判定 | 说明 |
|---|---|---|---|
| skills_list / skills_read / skills_read_resource | 技能目录（SkillCatalog）+ 提示词技能表（内置/MCP/宏/停用/教训） | 部分 | 我们有技能体系，但没有 SKILL.md 正文按需读取、没有 Skill 内资源读取 |
| skills_list_curated / skills_inspect_github / skills_install_from_github | 无 | 缺失 | 不支持从 GitHub 发现/安装技能 |
| memory_get | 无（记忆简报直接注入） | 缺失 | AI 不能按需检索记忆 |
| memory_write | REMEMBER | 更弱 | 我们只能“追加一条”，无 `replace_range/append/clear`、无 revision CAS |
| terminal（exec/open/close/check_root） | SHELL（自由模式，一次性命令） | 更弱 | 我们无持久会话、无 Root 检测、无权 `identity/environment/cwd` |
| terminal_job / terminal_daemon | 无 | 缺失 | 无后台任务与常驻服务 |
| read_file / write_file / edit_file / stat_file / list_directory / glob_files / grep_files | 无（WRITE_DOC 只产文档；SHELL 可绕） | 缺失 | 文件读写不是一等能力 |
| read_image | SEE（当前截图追问） | 更弱 | 我们只能看“当前屏幕”，不能读指定路径/相册 URI |
| subagent / subagent_profile | 无（编码任务走内部 vibecoding 委托块） | 缺失 | 无通用子代理 |
| conversation_history | 无 | 缺失 | 我们任务间上下文清零，无历史检索 |

---

## 4. 我们有、Eta 没有

| 我们的能力 | 类型 | 为什么 Eta 没有对应 |
|---|---|---|
| BACK / HOME / REFRESH / CLOSE / COLLECT / COPY / CLEAR_INPUT / SEARCH / SHARE / DOWNLOAD / ADD / SWITCH / SEND / CONFIRM / DELETE | 15 个高层语义意图 | Eta 全部走 `tap_element` / 坐标；语义词由模型自己翻译成节点 |
| FINISH / GIVE_UP / SAY / SHOW_AGENT / WRITE_DOC | 任务控制与产出 | Eta 用自然语言回复结束任务，没有显式终止意图与文档产出工具 |
| RiskTier 四档 + `needs_confirmation` + 三档动作模式（保守/均衡/自由） | 运行时门控 | Eta 只有 root/权限裁剪与敏感开关，没有“用户档位放行集合” |
| CLOCK 通道（AlarmClock 意图直达，中文口语时间解析） | 独立通道 | Eta 的 `set_alarm` 要求模型先算好时:分；我们的解析在端侧 |
| 中文失败原因 + `capabilityCode` + `notDelivered` | 失败语义 | Eta 用错误码（ROOT_REQUIRED / ACCESSIBILITY_UNAVAILABLE…），无中文引导 |
| 云端执行文档注入（DOCS） | 提示词块 | 无 |
| 经验规则注入 / 技能调用教训 / 记忆提炼 / 审核者 | 提示词块 | 无 |
| 规划阶段（PLANNING 9 块） | 提示词块 | Eta 明确要求“不要先输出计划，直接调工具” |
| 目标定位 / 动作合并 / 统一字段 / 失败路径 / 倒计时广告 / 国产应用速查 / 独占路由 | 中文场景规则 | 无对应 |

---

## 5. 门控、权限与风险模型

| 维度 | Eta | 我们 |
|---|---|---|
| 能力可见性 | 每轮冻结：`AgentToolCapabilities.project` + `AgentToolRequirements.project` 直接**移除**不可用工具（无 Root 时 Root 工具不出现，无 NFC 时枚举项被剔除） | 提示词列全表；不可用通道由 `ActionPolicy` 运行时拒绝并回中文原因 |
| 风险分级 | 三段布尔（direct / sensitiveRead / sensitiveWrite）+ 每工具 `RootRequirement`（NONE/PARTIAL/REQUIRED） | `RiskTier`（LOW 25 / MID 16 / HIGH 3 / EXTENDED 2） |
| 确认 | 无 per-call 确认；提示词明确“用户要求发送就直接发，不追加二次确认” | HIGH 档自动 `needs_confirmation`；自由模式 shell 每任务首次确认 |
| 敏感数据 | `AgentSensitiveToolPolicy.isSensitive()`：`mcp_` 前缀 + 名单 + `PersonalSearchTools.names` + `AgentPhoneToolCatalog.names`，原文不进持久历史 | 无对应策略；靠权限（通知使用权）+ 风险档 |
| 能力错误 | 统一错误码：ROOT_REQUIRED / LSPOSED_REQUIRED / DEVICE_UNSUPPORTED / ACCESSIBILITY_UNAVAILABLE / CALENDAR_PERMISSION_REQUIRED / APP_USAGE_ACCESS_REQUIRED / LOCATION_PERMISSION_REQUIRED / ACTION_OUTCOME_UNKNOWN / DIRECTION_MISMATCH | 中文原因 + 稳定能力编号（如无障碍）+ `notDelivered` 标记 + 通道降级（`ChannelLadder`） |
| 通道 | Root Shell / 无障碍 / 离屏浏览器 / 文件 / 终端 / 虚拟屏 | SCREEN（无障碍 → Shizuku → 无线 ADB）/ SHELL（Shizuku → ADB → Termux）/ CLOCK / BROWSER / VISION / VOICE / A11Y |

**关键差异**：Eta 的“不可用”发生在**提示词之前**（模型看不到），我们的“不可用”发生在**执行时**（模型看得到但会被拒）。前者省一轮、少幻觉；后者保留完整能力表，但要求模型学会“被拒就换路”。

---

## 6. 提示词结构对照

### Eta（`AgentPromptBuilder`，多段 system message）

| 顺序 | 段 | 条件 |
|---|---|---|
| 1 | 用户自定义 `config.systemPrompt` | 非空且非 roleplay |
| 2 | 主 system：模型身份 → 主动取证 → GUI 纪律（observe/coordinate_space/observation_id/lease/after）→ 输入/滚动/失败码 → Markdown 排版 | 始终 |
| 3 | 文件与终端段（Root 纪律、environment/identity/cwd、后台任务） | `terminalTools` |
| 4 | 托管网页搜索段 | `usesHostedWebSearch` |
| 5 | 浏览器段（web_search/fetch_url/browser_use 分工 + 外部数据不可信） | `browserTools` |
| 6 | 子代理段（何时派发、并行、自定义类型） | `subagentTools` |
| 7 | 人格段 | roleplay |
| 8 | 记忆段（revision/bytes/core 预算 + `<memory_core>` + `<memory_headings>` 快照） | 记忆启用 |
| 9 | 技能段（`id/name/path/capabilities/description` 索引，正文按需 `skills_read`） | 已装技能非空 |
| 10 | 历史 + 当前用户消息（可带图） | 始终 |

特点：**能力不在提示词里，只在 `tools` 里**；提示词只负责“纪律、优先级、失败语义、缓存稳定”。

### 我们（`PromptCatalog`，9 组 60 区块 + 占位符渲染）

| 组 | 区块数 | 内容 |
|---|---|---|
| SYSTEM | 21 | 角色 / 铁律 / 任务完成 / 页面数据 / 意图表 / 高层语义意图 / 网页浏览（WEB_TASK）/ 打开链接（OPEN_TASK）/ 目标定位 / 独占路由 / 语音助手（ASSISTANT_TASK）/ 编码委托（CODE_TASK）/ 普通对话（CHAT_TASK）/ 倒计时广告 / 不可逆 / 国产应用 / 统一字段 / 决策规则 / 失败路径 / 动作合并 / 禁止输出 |
| ACTION_MODE | 3（互斥） | 保守 / 均衡 / 自由；注入 `{low_risk}`、`{shell_commands}`、`{a11y_table}` |
| CAPABILITIES | 2（互斥） | 有视觉 / 无视觉 |
| SKILLS | 6 | 技能头 / MCP 表 / 仅配置未绑定 / 停用清单 / 宏技能 / 技能教训 |
| PLANNING | 9 | 计划头 / 设备状态 / 角色 / 分解规则 / 环境与意图 / 文档任务 / 语音助手捷径 / 歧义 / 输出格式 |
| DECISION | 16 | 决策头 / 记忆 / 经验规则 / 三态 / 失败处理 / 本步要求 / 时机 / 随时可用（文档/记忆/设备/网页/say/show_agent/see）/ 输出 |
| DOCS | 1 | 云端执行文档（DOC_HIT） |
| REVIEW | 1 | 审核者 |
| DISTILL | 1 | 记忆提炼 |

特点：**能力清单 + 风险档 + 场景规则都在提示词里**，靠 `PromptSnapshotTest` 的 sha256 金样本锁字节，装配按 `PromptFlag` 裁剪、按互斥组保证三档模式与视觉两版只出现一套。

---

## 7. 提示词内容主题逐项对照

| 主题 | Eta 写法 | 我们写法 | 差异评价 |
|---|---|---|---|
| 输出协议 | 自然语言 + function calling；工具结果回注 | 铁律：纯 JSON 单意图，禁止数组/Markdown，每步一个 | 我们约束更硬，换来解析稳定；但牺牲并行 |
| 能力声明位置 | `tools` JSON Schema（模型看到的签名） | Markdown 意图表（模型背的表格） | Eta 更抗参数漂移；我们改一句话就能改行为 |
| 每步上下文 | `observe_screen` 按需，节点 + `observation_id` + 可选截图 | 引擎每步自动注入「当前页面」块（前台应用/分辨率/元素行/页面提示/视觉描述） | 我们省一次调用，但无法表达“同一观察快照”一致性 |
| 坐标语义 | `coordinate_space` 必填三选一（normalized/screen/screenshot） | 按 `id/label/target` 定位；坐标只出现在自由模式 a11y 端点 | Eta 更适合视觉模型；我们更适合语义定位 |
| 并行 | 允许同轮多个工具；独立子任务并行 | 铁律禁止合并，每步一个 | 我们慢但可控 |
| 计划 | 明确“不要先输出计划，直接调工具” | 独立规划阶段 + 计划铁律 + 分步执行 | 哲学相反：Eta 行动优先，我们计划优先 |
| 观察复用 | 动作结果 `after` 自动带新界面与新 `observation_id`，足够就别再观察 | 执行后重新观察 + 验证器 + 页面指纹 | Eta 更省；我们更保守 |
| 失败语义 | `ACTION_OUTCOME_UNKNOWN` / `DIRECTION_MISMATCH` → 必须重新观察，禁止重放 | 「上一步结果三态」+ 失败处理 + 幂等保护 + 通道降级 | 两者都禁重放，我们多一层通道兜底 |
| 记忆 | MEMORY.md 快照 + `memory_get` / `memory_write`（revision CAS、局部替换） | 记忆简报注入 + `REMEMBER` 写入 + 任务后提炼 | Eta 可编辑可检索；我们只写不读 |
| 技能 | SKILL.md 索引 + `skills_read` / `skills_read_resource` 按需加载 | 技能表 + MCP 表 + 宏表 + 停用清单 + 调用教训 | 我们偏“能力别名”，Eta 偏“文档化技能包” |
| 网页 | 独立段：web_search / fetch_url / browser_use 分工 + 外部数据不可信 | `sys.web_browse`（WEB_TASK）+ `sys.open_link`（OPEN_TASK）分块裁剪 | 我们省 token；Eta 覆盖更全 |
| 终端/文件 | 独立段：Root 纪律、android/linux 双环境、job/daemon、文件工具 | 自由模式块内嵌友好命令表 + a11y 端点表 | Eta 是完整开发环境；我们是兜底 |
| 子代理 | 独立段 + `subagent` 工具（预设 explore/coder/plan + 临时类型） | 仅编码任务委托块（vibecoding） | 我们只覆盖编码一类 |
| 安全 | “工具已公开 = 用户已开启”；敏感原文不落盘 | 动作模式 + 不可逆确认 + 中文拒绝理由 | 两种都合理；Eta 更依赖投影，我们更依赖拒绝 |
| 缓存 | 系统提示只放稳定事实（Root 只二值），瞬时状态走工具结果 | 金样本锁字节；动态块按 flag 裁剪 | Eta 面向 API 前缀缓存；我们面向回归测试 |
| 多角色 | 人格段 + 角色记忆工具 | 无 | 与我们的产品定位无关 |

---

## 8. 参数 Schema 表达力

| 能力 | Eta | 我们 |
|---|---|---|
| 类型系统 | JSON Schema：string/integer/boolean/array/object | `CapabilityParam.type`：text/number/boolean/select |
| 取值范围 | `minimum` / `maximum` / `maxLength` / `pattern` | 仅 `options`（select）+ `defaultValue`；范围在代码里检查 |
| 必填与互斥 | `required` / `oneOf` / `additionalProperties=false` | `required` 单字段；互斥靠提示词文字（如 `when` 与 `hour,minute` 二选一） |
| 枚举 | 大量 `enum`（动作、方向、按键、音量通道、设置命名空间） | 只有 `select` 的 `options`（direction/key/kind 等） |
| 交叉约束 | `index + observation_id` 成对、`expected_revision`、`cursor` 续查 | 无；缺参推断 + 端侧中文拒绝 |
| 校验时机 | 请求发出前（模型看到签名）+ 执行前（Runtime 复核） | 转译层/执行层（`IntentTranslator`、`ClockChannel` 等） |
| 后果 | 参数错误在协议层就被挡住，少一轮 | 参数错了要“执行 → 中文拒绝 → 重发”，多一轮 |

---

## 9. 可迁移清单（按优先级）

### P0：低成本、直接减轮次

1. **能力可用性投影**（对齐 `AgentToolCapabilities.project`）
   - 现状：意图表全量写进提示词，缺权限时等 `ActionPolicy` 拒绝。
   - 做法：在 `PromptFlag` 增加运行态标志（通知使用权 / Termux / 视觉 / 语音助手 / 时钟可解析），装配时整行裁掉对应意图（`NOTIF_READ`、`NOTIF_CLEAR`、`FETCH`、`SEE`、`ASSISTANT`、`SET_ALARM`/`SET_TIMER`），`ActionPolicy` 保留为第二道闸。
   - 收益：模型不再“看到能力去撞墙”，每类缺失能力省 1 轮。

2. **补 `wait_for_text` / `wait_for_package`（LOW，SCREEN 通道）**
   - 现状：只有固定 `WAIT`，页面没到就重观察、重决策。
   - 做法：新增两条 `CapabilitySpec`；端侧复用现有快照采集做带超时的文本/包名判定，超时给中文原因（“等待 10 秒未出现「xx」，当前前台是 yy”）。

3. **参数约束前移**
   - `CapabilityParam` 增加 `min` / `max` / `pattern`，`IntentTranslator` 入口统一校验并回“字段+期望范围+实际值”。
   - 等价于 Eta 的 `minimum/maximum/maxLength/pattern`，但不需要改成 function calling。

### P1：补齐高价值直连能力

4. **闹钟读/改/删**：`list_alarms`（只读）、`set_alarm_enabled`、`delete_alarm`（需 Root/私有 Provider；无 Root 时明确“不支持，只能到时钟应用手动删”）。当前只能新增，用户说“取消 7 点闹钟”只能走 GUI。
5. **记忆可读 + 可编辑**：`memory_get`（按 query/分页）与 `REMEMBER` 扩展 `mode=append/replace/clear` + revision 冲突提示；对齐 `memory_write` 的 CAS 思路，避免重复追加。
6. **设备直连小集合**：音量、媒体控制、手电筒、勿扰/响铃、亮度——Eta 里都是 non-GUI 直控，能直接砍掉整段 GUI 步骤；每项一条 `CapabilitySpec` + 一条 SCREEN/SHELL 之外的直连通道。
7. **浏览器补 3 个动作**：`get_page_info`（标题/URL/是否加载完）、`reload`、`wait_for_selector`；这三项最能减少“盲等 + 重开页”。

### P2：产品面差距，按定位取舍

8. 网页搜索（`web_search`）：我们目前没有搜索工具，长尾信息只能靠用户给 URL。
9. 终端会话/后台任务（`terminal` open/session + `terminal_job`）、文件工具（读/写/编辑/glob/grep）。
10. 子代理（`subagent`）与历史检索（`conversation_history`）。
11. 个人数据检索（账单/待办/航班/酒店/联系人/短信等）与 GitHub 技能安装。

---

## 10. 明确不迁移的部分及理由

| Eta 设计 | 不迁移理由 |
|---|---|
| roleplay 人格系统 + 角色记忆 | 与“手机操控 Agent”定位无关；会污染系统提示前缀缓存 |
| `present_to_user` + 虚拟屏画布 | 我们已有副屏执行能力，但把“后台屏移交主屏”做成 AI 工具会显著扩大状态机复杂度 |
| 41 项敏感只读 / 个人数据检索 | 权限与隐私成本高，且不属于当前核心任务；需要单独做权限申请与脱敏设计 |
| `subagent` / `AgentConversationToolCatalog` | 我们是单任务单线执行，任务间上下文清零是既定纪律 |
| 偏差：`type_text(mode=append/submit)` | 可做，但优先级低于 P0；当前 `INPUT + PRESS(ENTER)` 已能覆盖搜索/发送 |

---

## 附录 A：文件索引

### Eta（临时源码目录）
- `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentToolCatalog.kt`：工具组装总入口
- `.../model/AgentContextAppToolCatalog.kt`、`AgentGestureToolCatalog.kt`、`AgentTextSystemToolCatalog.kt`、`AgentDeviceToolCatalog.kt`、`AgentWebToolCatalog.kt`、`AgentBrowserToolCatalog.kt`、`AgentSkillToolCatalog.kt`、`AgentMemoryToolCatalog.kt`、`AgentTerminalToolCatalog.kt`、`AgentFileVisionToolCatalog.kt`、`AgentFileToolCatalog.kt`、`AgentPhoneToolCatalog.kt`、`AgentPersonalSearchToolCatalog.kt`
- `.../tool/AgentToolCapabilities.kt`：每轮能力冻结与投影（root/lsposed/权限/设备特性）
- `.../model/AgentSensitiveToolPolicy.kt`：敏感工具判定（原文不落持久历史）
- `.../model/AgentPromptBuilder.kt`：系统提示组装（主段 + 条件段 + 记忆段 + 技能段）
- `.../subagent/AgentSubagentToolCatalog.kt`、`.../model/AgentConversationToolCatalog.kt`：子代理与历史检索工具

### 本项目
- `app/src/main/java/com/phoneagent/domain/model/CapabilitySpec.kt`：46 项能力声明 + 通道/风险/权限/确认 + 18 项 a11y 端点白名单
- `app/src/main/java/com/phoneagent/engine/prompt/PromptCatalog.kt`：9 组 60 个提示词区块与装配条件
- `app/src/main/java/com/phoneagent/engine/prompt/PromptBodies.kt`：区块正文（意图表 CN/EN 等）
- `app/src/main/java/com/phoneagent/engine/prompt/PromptAssembler.kt`：按 `PromptFlag` 裁剪、互斥组、占位符渲染
- `app/src/main/java/com/phoneagent/engine/prompt/PromptTemplate.kt`：`PromptVars` 与单趟占位符渲染
- `app/src/main/java/com/phoneagent/engine/execution/IntentTranslator.kt`：意图 → 设备动作（含 CLOCK/ASSISTANT 策略）
- `app/src/main/java/com/phoneagent/device/clock/ClockChannel.kt`：AlarmClock 直达通道
- `app/src/main/java/com/phoneagent/engine/TaskKindDetector.kt`：任务类型判定，决定 `ASSISTANT_TASK` 等 flag

> 注：`PromptCatalog.kt` 中注释写“全部区块（56 块）”，按代码实际统计为 60 块（SKILLS 组多出 `skills.mcp` 与 `skills.mcp_server_only` 两个互斥块），属注释陈旧。
