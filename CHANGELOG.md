# 更新日志

本文件记录 Happy Phone Agent (Phtomt) 的全部 noteworthy 变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [v0.2.721] — 2026-10-04

这一批 71 个文件、+1869 / −594，没有新能力，全是**把已经写下的承诺兑现**。摊开看反复出现三类：

**注释声称做了、实现没做**——注释写着 `reply` 兼容裸字符串，代码却硬取对象；注释写着"单字符叉号必须带类型判断"，
实现里根本没有这个判断；`validateUniqueName` 的注释写"校验唯一"，条件却写反成"只拒绝大小写不同的"，
**完全同名的反而放行**。

**失败被记成成功**——给不存在的 Activity 发广播，`sendBroadcast` 无论应用在不在都"成功"；
Termux 回传里既没有退出码也没有任何输出，却按 `exit_code=0` 报成功；引导恢复成功后忘了记账，
进度不涨、失败计数不清。

还有一类**只在特定条件下才现形的洞**——微信聊天里出现"转账"两个字就整页锁死；
广告点了还在就一轮一轮点下去；投影授权丢失后服务还以"无投影却常驻前台"的僵尸状态活着。

隐私面这次收了两处边界：敏感页面清单补上漏掉的银行，日志与诊断导出补上脱敏——
日志正文里常常就是页面原文（短信验证码、卡号、姓名），此前是明文落盘的。

### 新增

**不可逆动作确认门：执行前先问用户**

- 引擎新增 `AgentAction.needsUserConfirmation`（`engine/AgentEngine.kt`）：AI 或转译层标记为不可逆的动作
  （支付确认、删除、清空…），执行前挂起等待答复。**超时按取消处理**，不让任务无限挂住
- 任务流末尾插入确认卡（`ui/agent/AgentScreen.kt`、`ui/MainViewModel.kt`），与悬浮窗**共用引擎的同一信箱**。
  卡片出现时自动滚到它本身——这是引擎的阻塞态，不能让用户误以为 AI 没了动静；回看历史任务时照样显示，
  避免用户找不到出口
- 拒绝的语义是"换做法"而非"硬执行"：该步记跳过，用户的决定回注给 AI 重新规划

**两段式确认铺到其余破坏性入口**

- 悬浮窗关闭键（`overlay/FloatingWindowService.kt`）：第一次点只把文案换成「再点确认停止」，2.5 秒自动复位，
  再点才真正终止任务——一次误触不该杀掉正在执行的任务
- 单条删除 / 清空存档 / 删除端点 / 批量删技能（`ui/memory/MemoryLists.kt`、`ui/debug/DebugScreen.kt`、
  `ui/settings/SettingsData.kt`、`ui/settings/SettingsComponents.kt`、`ui/skill/SkillsTab.kt`）：
  第一次点只进入 armed 态（图标染红、文案变「再点确认…」、读屏文案同步改），3 秒不点自动复位

**其他新增**

- 敏感页面检测新增**微信专用窄表**（`core/security/SensitivePageDetector.kt`）：微信是通用应用，
  聊天正文里出现"银行卡/余额/转账"是常态，套宽表会把普通聊天页误判成支付页而**整页锁死**，
  只有真正进入收付款页才该只读；同时补上漏掉的中信、邮储，以及挂在 `com.chinamworld.*` 前缀下的中行/建行系
- 记忆库三类数据加上总量上限并按时间淘汰最旧（`data/store/MemoryStore.kt`）：
  AI 记忆、异常经验、用户画像，长期使用不再随任务数无界增长

### 修复

**判据里藏着的洞**

- **否定守卫**（`domain/rules/LocalDecisionEngine.kt`）：「不允许」「不同意」只是子串里含正向词，
  原词表会把它当"允许"按钮点下去；同时把裸「确定 / ok / continue」移出正向词表——普通页面的确认按钮也会命中
- **稀疏守卫**（`domain/rules/EngineRules.kt`）：只有元素 ≤6 的页面才可信地呈现"完成证据"；
  元素多的页面里出现"操作成功"可能只是列表里的一条历史记录，据此跳过动作会让真正的副作用操作永远执行不了
- **数字键映射**（`domain/rules/ShellCommands.kt`）：单个数字字符映射为数字键（`KEYCODE_0=7`…`KEYCODE_9=16`），
  否则 `key 5` 会把 5 当键码（5 = CALL 键），打不出数字
- **名称查重写反**（`feature/mcp/McpRules.kt`）：`validateUniqueName` 原条件只拒绝"大小写不同"的重名，
  完全同名反而放行；改为忽略大小写判重并支持编辑时排除自身
- **英文词左词边界**（`feature/browser/BrowserGuard.kt`）：`border` 不再被当成 order、`resend` 不再被当成 send
- **CSS 选择器形态**（`feature/skill/SkillCompat.kt`）：`{"target":"#login"}` 以前一律归 `by=text`，
  选择器被当成"页面里的一段文字"去找，永远找不到；现按扁平写法同款传 `by=id`
- **空语义目标不命中**（`feature/task/TemplateMatcher.kt`）：去符号后无任何字母/数字/汉字的目标没有可匹配语义
- **承接词过宽**（`domain/rules/SessionContext.kt`）：纯指代词（它/这个/那个）与裸「加上」在新任务里同样常见，
  子串命中会把完全独立的新任务误判成追问而被上一轮带偏，已移出
- **URL 白名单过滤**（`engine/execution/IntentTranslator.kt`）：引号 / 反引号 / 分号可拼接第二条命令
  （`http://x; rm -rf /`），一律拒绝
- **只读模式横切约束**（`engine/execution/IntentTranslator.kt`）：`WAIT` / `WRITE_DOC` / `REMEMBER` /
  `DEVICE_QUERY` / `SAY` / `SHOW_AGENT` / `TASK_DONE` 不触碰设备，只读模式下同样放行；`FINISH` 与 `GIVE_UP`
  都产出 `TASK_DONE`——收尾不是设备操作，否则任务永远无法正常结束
- **JSON 字段解析**（`domain/model/AgentIntent.kt`）：`JsonNull` 等非整数形态统一走抛异常，不静默取默认值
- **跑马灯默认色少一位**（`data/prefs/AppSettings.kt`）：第三色 `FF6B9D` 只有 6 位，按 ARGB 解析时被当成
  缺省 alpha，补齐为 `FFFF6B9D`

**把"成功"的定义收回原处**

- **无 LAUNCHER 入口时诚实失败**（`device/a11y/ActionExecutor.kt`）：给不存在的 Activity 发 MAIN+LAUNCHER 广播，
  无论应用是否存在 `sendBroadcast` 都"成功"，会让 AI 误以为已打开而不再换方案
- **Termux 无有效回传按失败处理**（`device/shell/TermuxBridge.kt`）：结果 Bundle 里既无退出码也无任何输出
  （如 `allow-external-apps` 未开），此前按 `exit_code=0` 报 `Success("")`
- **失败必须显式抛回主循环**（`engine/AgentEngine.kt`）：静默 `return null` 会让上层把"单次网络抖动"当成
  「决策为空」直接杀任务，且连续失败护栏统计不到这条路径
- **引导恢复成功要对齐记账**（`engine/AgentEngine.kt`）：原来漏了这一步，引导成功后进度不涨、失败计数也不清
- **失败留档去重**（`engine/AgentEngine.kt`）：引导重试后无论成败都会走到统一留档，先记一遍会造成同一步双重留档；
  只有用户离开（直接 `return`）才补记
- **广告自动点击护栏**（`engine/AgentEngine.kt`）：同一关闭目标连续命中 ≥2 次说明自动点击无效（点了广告还在），
  此时停止自动点击、该轮也不再剔除广告内容，把真实页面交给 AI 重新决策
- **`raw` 前缀大小写不敏感**（`engine/execution/ShellRules.kt`）：`removePrefix` 只认小写，会漏掉 `RAW` 前缀

**语义定位与视觉坐标**

- **semanticId 回填**（`engine/AgentEngine.kt`）：`annotate()` 把 `semanticId` 标在它**新建的元素列表**上，
  原始快照里的元素全为 null；不回填的话端侧决策的 `by="id"` 定位与转译层的语义控件命中
  （send / confirm / close / delete）永远失败
- **视觉坐标"消费一次"**（`engine/AgentEngine.kt`）：上一步 `see` / 视觉定位产出的坐标只供本次转译使用，
  取走即清空；不再在每轮开头无条件清零（那样 `see` 拿到的坐标下一步必被清掉，等于白定位）
- **提前完成拦截门槛**（`engine/AgentEngine.kt`）：有计划时 = 规划步数向上取整的 60%，不再叠加"至少 3 步"的下限，
  否则两三步的小任务会被强行拦截到凑步数

**通道与服务**

- **ADB 链路**（`device/shell/AdbTcpSession.kt`、`AdbWirelessTransport.kt`、`AdbSocket.kt`、`AdbProtocol.kt`、
  `MdnsAdbResolver.kt`）：读超时不再永久阻塞；只处理寻址到本流的帧（历史流残留帧直接丢弃），
  退出前排空本流残留帧避免污染下一次会话；mDNS 同一时间只允许一个 pending resolve，
  且只接受 owner name 含配对服务标签的 SRV（否则局域网内任意服务的 SRV 都会被当成配对端口）；
  配对 salt 读出恰为 32 字节才采用
- **Shizuku**（`device/shell/ShizukuManager.kt`）：`stderr` 由独立线程持续读入——主线程若先读 `stderr`
  再读 `stdout`，两条管道任一写满都会让子进程写阻塞、主线程又等不到退出，直接死锁；命令加超时强杀
- **Service 被系统重建（START_STICKY，`intent == null`）后的处置**：截屏服务此时投影授权已随进程丢失，
  不能以"无投影却常驻前台"的僵尸状态存活，取消前台通知并自杀（`device/screen/ScreenSharingService.kt`）；
  边缘光效同理，不能走 `else` 分支让光效"死而复生"（`feature/edge/EdgeLightingService.kt`）；
  悬浮窗任务卡复位为空闲文案，不继续摆出"任务进行中"误导用户（`overlay/FloatingWindowService.kt`）
- **截图前隐藏覆盖物**（`overlay/FloatingWindowService.kt`）：跑马灯、悬浮球、底部选项卡都是独立窗口、
  都会浮在屏幕上，此前只隐藏了光标；现在一并隐藏并记录原可见态，截完按原样恢复，
  且不推翻用户主动收起（`userHidden`）的面板
- **滑动越界钳制**（`device/a11y/ActionExecutor.kt`）：±600px 的终点可能越出屏幕被系统拒绝，钳制在屏内，
  并保留至少 1px 位移（避免元素贴边时零长轨迹手势被拒）
- **外部视觉降采样**（`device/vision/ExternalVisionProvider.kt`）：先降采样再转 RGBA，
  整屏原尺寸数组会撑爆 binder 事务缓冲

**其他修复**

- **AI 客户端取消与重试口径**（`core/ai/AiClient.kt`）：协程取消（任务停止 / 页面关闭）必须穿透重试循环
  向上传播，并同步取消 OkHttp 请求；401/403/404 属鉴权 / 地址错误，重试不会变好，直接失败；
  200 但正文不是预期 JSON（网关改写 / HTML 提示页）时保留原始返回，避免只剩解析异常
- **HTML 转 Markdown**（`core/text/HtmlToMarkdown.kt`）：`script` / `style` / `noscript` 整段跳过、
  不按子树配平（JS 里的伪标签会干扰配平，且脚本内容绝不能被当正文吐给 AI）；
  未闭合的 `<title>` 此前会一直吞到文末，现在最多取 200 字符
- **通知 ID 顺序分配**（`core/notify/TaskProgressNotifier.kt`）：多任务各有一个独立 ID，不再互相覆盖；映射表加上限
- **存档原子替换**（`data/store/DebugRecordsStore.kt`）：先写临时文件再 rename，写到一半被杀不会留下半个
  读不回来的存档；扩展名 `.png` → `.jpg`（内容本就是 JPEG）
- **id 撞车**（`data/store/MemoryStore.kt`）：原 `id = System.currentTimeMillis()`，同毫秒两次写入会互相覆盖，
  改用 UUID 派生
- **日志导出脱敏**（`data/export/LogExporter.kt`）：消息与 detail 是页面快照 / 响应原文，
  与诊断报告走同一套 `DataSanitizer`，不再明文落盘
- **MCP 协议**（`feature/mcp/McpClient.kt`、`OkHttpMcpTransportFactory.kt`、`McpManager.kt`）：
  `required` 是字符串数组需正规解析（旧实现 `toString` + 去引号对转义内容会带反斜杠）；
  会话 id 属单条连接，不能放在 `object` 级共享；启停服务器后失效对应缓存，下次调用按新配置重建
- **市场条目不再是"MCP 端点"**（`feature/mcp/McpMarketplace.kt`）：天气 / 汇率 / 新闻 / 行情是普通 REST 站点，
  直接当 MCP 端点连必失败，`isPublic` 全部改回 `false`，只作"需自建 MCP 网关"的示例源
- **复选框 / 单选切换**（`feature/browser/script/InteractScripts.kt`）：没有"填字"语义，先按 FILL 判定目标态
  再用 `el.click()` 切换并校验
- **同名文档不静默覆盖**（`feature/document/DocumentEngine.kt`）：已存在时追加时间戳后缀
- **代理对安全截断**（`feature/browser/BrowserBridge.kt`）：落点若是高代理项（emoji / 生僻字被切成两半）则回退一位
- **广告区域边距**（`feature/adskip/AdContentFilter.kt`）：扩张边距由绝对 220px 改为屏幕短边的 12%，
  高分屏太小、低分屏太大；单字符叉号仅图片类控件视为关闭按钮（`x` 在文本类控件里可能是序号或变量）
- **弹窗按钮词表**（`engine/perception/PageAnnotator.kt`）：英文词走整词匹配，避免子串误伤；
  「退出登录」这类按钮不再当系统返回键；输入框清空词表不含 `delete`（`btn_delete_item` 不是清空输入框）
- **实效点击比对**（`engine/execution/VerifiedClickExecutor.kt`）：过滤无障碍断开时的缺失态快照，
  避免拿"空壳快照"比对指纹误判成"页面未变化"
- **无障碍可用性口径统一**（`ui/MainViewModel.kt`）：系统开关已打开 **且** 服务实例已连接才算可用——
  只查开关会漏掉"开关开着但实例没连上"，只查实例则覆盖不了开机未拉起

### 优化

- **滑杆拖动不落盘**（`ui/settings/SettingsAgent.kt`、`ui/settings/SettingsVisual.kt`）：
  拖动只更新本地编辑态（数值即时跟手），松手才写 DataStore——拖一次滑杆此前会产生几十次写入
- **测试失败不堵死保存**（`ui/settings/SettingsAiModels.kt`）：部分供应商只在特定模型 / 地区放行，
  连接测试通不过但配置本身可用，给「仍要保存」逃生门
- **用时以引擎起点为基准**（`ui/agent/AgentRunStatusStrip.kt`）：重组 / 离开页面再回来不再把计时清零重走
- **规划期的出口**（`ui/agent/AgentComposer.kt`、`ui/agent/AgentRunStatusStrip.kt`）：规划可能迟迟不归，
  在禁用的发送键旁补「取消规划」文字按钮；状态条明说在规划，不再照搬 `IDLE` 显示"空闲"
- **协助浮层独立输入草稿**（`ui/agent/AgentScreen.kt`）：与主输入框分开，两边不再串扰
- **全局反馈覆盖层提到二级页之外**（`ui/MainActivity.kt`）：二级页（`extrasPage` 非空）同样能弹反馈；
  自动消失计时补上（普通提示 4s、带操作按钮 6s 留出点击时间），key 挂在新条上，
  不会把旧条的倒计时错套到新条（`ui/components/Components.kt`）
- **技能批删 / 导入挪到后台线程**（`ui/MainViewModel.kt`、`ui/skill/SkillsTab.kt`）：
  含持久化 IO 与 JSON 解析，结果回主线程再弹浮条
- **技能编辑器保存前预检**（`ui/skill/SkillEditorDialog.kt`）：INTENT 技能要能映射到可执行意图才可保存，
  判定失败时阻止保存并展示原因（不走真实 registry 模拟调用——新技能尚未入库、被编辑技能若处于停用态都会误报）
- **无线 ADB 页**（`ui/skill/WirelessAdbTab.kt`）：内容超出屏幕时可滚动（小屏 / 大字号），
  本机 IP 随网络切换变化，每次回到前台重算
- **测试页并发守卫**（`ui/test/TestScreen.kt`）：引擎不受理并发任务，任务运行 / 规划中再点只会进入无人受理的死态；
  不用 `enabled = false` 一禁了之（禁用态点了没反应，用户不知道原因），改为守卫 + 浮条给理由
- **去掉失效的"浏览器"直达索引**（`domain/model/AppPageIndex.kt`）：内置浏览器已改为静默宿主，
  这条只指向本应用二级页的索引不再有路由意义
- **提示词金样本重算**：判据与文案变更后 `app/src/test/resources/prompt_golden/snapshot.sha256` 同步更新，
  单测随判据收紧（`LocalDecisionEngineTest`、`SessionContextTest`、`IntentTranslatorStrategyTest`、
  `ShellRulesTest`、`AdbProtocolTest`）

---

## [v0.2.714] — 2026-09-29

口径先说清：这段时间本文件留下 43 条版本记录。版本号的第三位是**全局构建号**——每次 assemble / bundle
都自增（调试构建也算），其间从 265 走到 714、跨 449 个号，所以它不等于发版次数。区间的起点是 v0.1.265
（2026-09-13，这段时期的上一条更新），它自身的能力不算本期新增，下文标「起点版本」者即此类。

把这 43 条摊开看，
反复出现的其实只有三句话：**同一件事被写了两遍**（通道判定、定位规则、护栏词表、
完成判定…改一处必漏一处）、**判据依赖了看不见的东西**（让模型去看"页面指纹"、
让肉眼保证视觉路由、提示词说"能用"而端侧其实拒了）、
**现在没坏但每次改动都在加利息**（工具链落后两年、改一句决策要在四千行里找位置、
构建门禁只跑测试不做静态检查）。

下面先列出期间新增的功能，再按主题归纳做了什么、为什么；本版自身只做一件事——按角色拆分引擎，
细节在文末「本版改动」；每条改动的完整理由与细节仍在各自版本里。

### 期间新增功能

范围说明：标「起点版本」的几条出自 v0.1.265，即这段时期的上一条更新，当时已经写过；
列在这里只为交代起点，不计入本期新增。

**AI 新长出的能力**

- `see` 看图追问——带目的的按需视觉调用，带 `target` 时回 JSON 拿坐标供下一步复用；内置技能 `skill_see`（v0.1.611）
- `show_agent` 把用户拉回 Agent 页当场看结果 + `DocViewerScreen` 全屏阅读页（v0.1.544）
- `device_query` 按需查本机信息（应用清单 / 时间 / 电量 / 网络 / 存储，`apps` 支持关键词过滤）（v0.1.335）
- `say` 纯对话意图：模型判断不用碰手机时直接回答，**不请求批准、不进入执行**（v0.1.473）
- `open` 打开链接与本地文件：本地路径归一化为 `content://`（免存储权限、不自建 FileProvider）、
  40 种扩展名推断 MIME、未指定时优先系统应用（v0.1.413）
- `fetch` 取网页正文：AI 只说取哪个地址，`curl` 命令由端侧拼装（v0.1.314）
- `shell`（AI 亲写命令）与 `a11y`（AI 点名无障碍端点）两条自由模式专属意图（v0.1.513）
- 给 AI 补上它看不见的事实：环境上下文每步注入、多轮对话承接最近 3 条已完成任务、任务结论落库（v0.1.335）
- 任务记忆 `TaskMemoryEntry`：目标 / 用户要求 / 已验证做法随每一步落库，记忆页新增「任务记忆」分区（v0.1.328）

**新增的技能体系与 MCP**

- MCP 全链路：校验规则 `McpRules`、DataStore 持久化 `McpStore`、内置市场 `McpMarketplace`、
  `describe()` 握手 + JSON Schema 参数解析、工具清单结构化注入系统提示、McpTab（含请求/响应 JSON 原文）、
  绑定为技能（起点版本 v0.1.265）
- 技能归一化 `SkillCompat.normalize`：意图名 / 技能 id / 技能名三种写法等价，MCP 技能就地调用
  （20s 超时、输出截断、连续 3 次被拒停止），新增动作类型 `ActionType.MCP_CALL`（v0.1.326）
- 全屏技能编辑器：结构化参数编辑（参数名 / 类型 / 必填 / 候选项 / 默认值 / 说明，可动态增删）（起点版本 v0.1.265）

**新增的通道**

- Termux 通道 `TermuxBridge` + `TermuxResultReceiver` / `TermuxResultRelay`，
  执行通道扩为四态 `AUTO` / `ADB` / `SHIZUKU` / `TERMUX`（v0.1.314）
- 三档动作模式 `ActionMode` + 策略唯一定义点 `ActionPolicy` + 18 项无障碍端点白名单 +
  `aiAuthored` 标记（AI 亲写命令在本任务首次执行前确认一次）（v0.1.513）
- 点击流水线 `ClickRunner`（活节点直点 → 手势点控件最新位置 → 滚动一屏查找）、
  `ActionExecutor.clickNode` / `scrollContainer`、定位口径唯一定义点 `IntentResolver`（v0.1.502）

**浏览器**

- `BrowserChannel`：与 `IntentTranslator` 同级的独立通道 + 不可逆词表唯一定义点 `BrowserGuard`
  （中文 19 词 / 英文 13 词）——网页读写不再依赖无障碍 / Shizuku / 无线 ADB，只读模式照常可用；
  配套 `feature/browser/script/` 脚本四件套（v0.1.430）
- `HeadlessWebHost` 静默浏览器宿主（全透明、不吃触摸、不抢焦点）+ `browse_open` 静默优先 +
  `createBrowserWebView()` 构造收口成一份（v0.1.543）
- `HtmlToMarkdown` 四段流水线解析器：`browse_read` 正文改 Markdown（链接内联在正文里），
  回注预算 1200 → 4000 字符（v0.1.406）

**新增的界面组件**

- `InlineOverlay` 页内浮层 / `LocalSnackbar` 页内反馈 / `OverlayScrim` 压暗底 / `DurationPulse` 呼吸时长
  ——四者各自成为唯一定义点，系统弹窗与 Toast 就此清零（v0.1.543）
- `AgentAssistSheet` 底部协助浮层：答疑与协助两种场景共用一副骨架，仅出口不同（v0.1.321）
- `CursorMode` 光标三形态 `TAP` / `LONG_PRESS` / `SWIPE` + 派发统一入口 `dispatch`（v0.1.561）
- 模型库与职责分配三层结构（端点 → 模型库 → 职责分配）+ 能力探测 `probeAbility`（只用真实请求）+
  模型选择弹层 `ModelPickerDialog` + 开关「主模型识图时跳过视觉描述」（v0.1.445）
- 底部跑马灯独立成窗（长宽随内容自适应，`FLAG_NOT_TOUCHABLE` 不吃触摸）+「跟随状态变色」开关（v0.1.473）
- `GlassHeaderScaffold` 玻璃页眉骨架：把「取样源与玻璃面必须是同层兄弟节点」收进骨架（v0.1.473）
- 任务执行期间隐藏系统状态栏（先记原值再改、收尾放进 `NonCancellable`）（v0.1.415）
- 实时思考回显 + 命令与输出回显（v0.1.316）、失败原因回显 + 「端侧」徽标（v0.1.318）
- `DocumentEngine` + Agent 页文档预览卡片（文件名 + 默认展开的 Markdown 正文）（v0.1.313）
- 调试面板四页签（v0.1.386）

**新增的工具与内容**

- 提示词审计工具链：`PromptDumpTest`（逐字节导出）+ `tests/prompt_audit.mjs`（逐条送审）+
  提示词系统文档（53 块 8 组清单）（v0.1.620）
- Agent 运行时广告过滤 `AdContentFilter`：识别广告并直接点关闭，广告信息不回传给 AI（起点版本 v0.1.265）
- 明文 HTTP 放行 + 地址自动补协议（内网 / localhost 补 `http://`，公网补 `https://`）（v0.1.333）
- Release APK 签名构建（R8 混淆 + arm64-v8a 单 ABI）+ APK 上传 Gitee / GitHub Release（起点版本 v0.1.265）
- 官网独立站点（Vue 3 + Vite）与宣传片（v0.1.335）

### 决策与执行：每一步都要有据可依

- **点击做成一条分层流水线**（v0.1.502）：活节点直点 → 手势点控件最新位置 → 滚动一屏查找，
  **只在动作根本没交付时才升级**——同一个控件被按两遍，对发送/提交/删除就是重复副作用；
  定位口径同时收进 `IntentResolver`，引擎里三份私有副本（`resolveTarget` / `resolvePoint` /
  `relocateOnLatestPage`）删除
- **落点与目标不一致要说出来**（v0.1.543、v0.1.619）：派发手势前先 `hitTest()` 问清"这一下会落到谁身上"，
  失败原因按"该重新定位"与"该换意图"分开给指引；但**已生效的点击不因此改判**，
  只写日志与警示——判成"可疑"会让 AI 重按一次
- **视觉兜底覆盖全部定位方式**（v0.1.619）：触发条件由 `by=hint` 放宽为"端侧在元素树里没命中"，
  且先在免费路径试一次；`type_text` / `scroll` / `swipe` 执行前按当前页面重定位
- **主模型识图三态化**（v0.1.611）：`AUTO` / 强制开 / 强制关，`AUTO` 以真实探测结果为准；
  新增 `see` 意图（带目的的按需视觉调用）；hint 定位优先级从"先花网络的远端、后本地免费"倒回来
- **视觉链路必须有上限**（v0.1.324）：视觉 25s、截图 2.5s 看门狗，超时降级为只用元素树；
  元素树稀疏时视觉链路不再被总开关掐断（v0.1.473），也不再往上下文写「未识别到控件」——
  那句话会被 AI 当成"页面上没有可操作控件"的事实
- **给 AI 补上它看不见的事实**（v0.1.335）：环境上下文每步注入（时间 / 前台应用 / 网络 / 电量 / 应用数）、
  `device_query` 按需取数、会话承接最近 3 条已完成任务、任务成功时把结论落库
- **任务记忆取代易失的进度摘要**（v0.1.328）：目标 / 用户要求 / 已验证做法随每一步落库；
  任何退出路径都不把状态停在"进行中"
- **回显补齐**（v0.1.316、v0.1.318）：流式思考、命令与输出、失败原因，以及端侧决策的留档——
  端侧走过的路此前在 Agent 页完全不可见，用户体感是"AI 没动，页面自己变了"
- 纯对话不再走批准流程（`say` 意图 + `PlanPhase.Reply`，v0.1.473）

### 授权与通道：把"批不批"交回用户

- **三档动作模式**（v0.1.513）：保守 / 均衡 / 自由。最高档开出两条兜底通道——
  `shell`（AI 亲写命令）与 `a11y`（直调无障碍端点，18 项白名单，端点名与必填参数都校验）；
  AI 亲写的命令在**本任务首次执行前**确认一次，连续被拒 3 次即停止并提示换档
- **浏览器提成与转译层同级的独立通道**（v0.1.430）：网页读写不依赖无障碍 / Shizuku / 无线 ADB，
  只读模式下照常可用；护栏改为"按不可逆性放行"（中文 19 词 + 英文 13 词），词表由 `BrowserGuard` 一处定义
- **执行通道扩到四条**（v0.1.314、v0.1.415）：AUTO 按 无线 ADB → Shizuku → Termux 顺位；
  Termux 带来 `fetch` 意图（AI 只说取哪个地址，命令由端侧拼装）；两条执行链各自内联的通道选择
  后来收敛成 `ShellRules.pickChannel`（本版）
- **自建推理服务不再被公网 HTTPS 绑架**（v0.1.333）：放行明文 HTTP，未写协议时按主机推断
  （内网 / 私有网段 / localhost 补 `http://`）
- **打开链接与文件**（v0.1.413）：本地路径归一化为 `content://`（不需要存储权限、不自建 FileProvider）、
  按扩展名推断 MIME、泛指类目优先系统应用

### 浏览器：从"策略表里的一颗策略"到独立通道

内置浏览器此前只是转译层策略表里的一颗策略：AI 说 `browse_open`，端侧先把它当普通手机动作走一遍授权判定，
只读模式下还要过一张白名单。于是"没有无障碍 / 没有 Shizuku 就用不了浏览器""网页里点个链接也被当成操作手机拦下"
这类别扭一直在——浏览器本来就不碰用户的手机，它的权限不该由操作手机的通道来定。同一时期还压着两个源头：
打开网页会把 App 界面切走、把用户正在做的事打断；以及 AI 读到的正文只是 `innerText` 压平后的一坨文字。

- **提成与 `IntentTranslator` 同级的独立通道**（v0.1.430）：网页读写走注入的 DOM 脚本，不经无障碍 / Shizuku /
  无线 ADB，也不进策略表，**只读模式下照常可用**；护栏改为"按不可逆性放行"，词表由 `BrowserGuard` 一处定义
  （中文 19 词 + 英文 13 词），且**执行前就拒**，拒绝语明确写"不要重试同一动作"
- **旧链路整条删除**（v0.1.430）：`BrowserStrategy` 与只读白名单、`IntentType.TO_ACTION` 的 6 条映射、
  `ActionType.BROWSE`、`AgentAction.op` 全部移除——浏览器不再产生任何 `AgentAction`；引擎主循环改为
  **先过浏览器通道再走转译**
- **脚本四件套**（v0.1.430）：`BrowserJs`（选择器与共享 JS 助手）/ `ReadScript` / `InteractScripts` / `NavScripts`
- **清单里看得见就点得到**（v0.1.430）：`browse_read` 返回"可操作元素清单"，清单与点击**同源**
  （都由 `labelOf` 取名、共用同一套选择器），解决"只有 aria-label 的图标按钮清单里看得见、点下去却说找不到"；
  下拉框改为按选项文字设 `selectedIndex` 并派发 `input` / `change`（WebView 里 `select.click()` 不弹原生下拉），
  `browse_click` 候选先按真控件选择器找、再退到"页面文字"那层宽网
- **静默宿主 `HeadlessWebHost`**（v0.1.543）：把给 AI 用的 WebView 挂进全透明、不吃触摸、不抢焦点的悬浮窗，
  屏幕上一个像素都看不到，页面照常布局、脚本照常执行；**不用"把窗口挪到屏幕外"**——部分 ROM 会把越界窗口
  判成不可见而停掉渲染，网页就成了永远加载不完的样子；`importantForAccessibility` 设为 `NO_HIDE_DESCENDANTS`，
  避免网页节点混进"当前前台窗口"的元素树污染感知；建不起来（没给悬浮窗权限）**不硬撑**，回退旧路径
- **`browse_open` 改为"静默优先"**（v0.1.543）：用户正开着浏览器二级页时用那一份（页面就在屏幕上，AI 能靠截图看见），
  否则用静默宿主；两种都拿不到才把 App 切到浏览器二级页；`createBrowserWebView()` 把可见页与静默宿主的
  WebView 构造收口成一份
- **提示词中英各 7 处同步**（v0.1.543）：写明 `browse_read` 是静默模式下**唯一**能看到网页的方式、
  `browse_back` 之后不再需要 `press key=BACK`——落掉任何一处，AI 都会去等一张永远不会出现的截图
- **正文改 Markdown**（v0.1.406）：`HtmlToMarkdown` 四段流水线解析器（链接**内联**在正文里，因此取消独立链接清单），
  回注预算 1200 → 4000 字符；浏览器侧脚本的丢弃表 / 块级表 / 自闭合表 / 行内标记表 / 隐藏类名表 / 转义表 /
  围栏字面量七张表全部由 Kotlin 常量**插值生成**，改规则只改一处，两侧不可能漂移
- **提示词层面厘清分工**：需要读/操作网页内容归 `browse_*` 独占、只是把网址给用户看走 `open` + 系统浏览器、
  **禁止用 `open_app` 打开"浏览器"来上网**（v0.1.413）；网页浏览章节前移并新增"浏览器不受操作手机通道约束"
  铁律（v0.1.430）；"不用等谁放行"这类含糊表述换成明确口径（v0.1.620）
- **回归用例钉住**（v0.1.430）：`BrowserGuardTest`（含"该放行的普通操作"反例清单，钉住只读模式可用性）、
  `BrowserChannelTest`（注入假执行器，断言被拒时执行层一步未落地）

### 提示词：从手改文案到可审计闭环

- **提示词可导出、可送审、有金样本兜底**（v0.1.620）：`PromptDumpTest` 把全部入口点提示词逐字节导出到
  `app/build/prompt_dump/`，`tests/prompt_audit.mjs` 读 manifest 逐条送模型审视
  confusions / contradictions / missing / redundant
- **同一件事只留一套口径**（v0.1.679）：完成判定去掉"页面指纹"这一模型看不见的术语；
  "没进展"在决策规则与失败处理里的两套次数合并为一套；`open` 三选一写明互斥；
  目标值一律取**屏幕上真实显示的文字**；倒计时广告的等待不计入失败次数

### 技能与 MCP

- **MCP 从"能配"到"能用"**（起点版本 v0.1.265）：校验规则、DataStore 持久化、内置市场、`describe()` 握手解析、
  工具清单结构化注入系统提示、模块 UI（含请求/响应 JSON 原文）、技能编辑器全屏化
- **技能真正驱动执行**（v0.1.326、v0.1.327）：意图名 / 技能 id / 技能名三种写法等价，声明的参数
  回填到意图字段（此前运行时被静默丢弃，技能只是空壳），启停开关真正生效；
  MCP 技能就地调用、20s 超时、连续 3 次被拒停止
- **新增内置能力**：`skill_see`（v0.1.611）、`skill_show_agent`（v0.1.544，把用户拉回 Agent 页当场看结果）、
  `skill_shell` / `skill_a11y`（v0.1.513）

### 界面：Material 3 Expressive 与令牌收口

- **全项目收口到 `AppRadii` / `AppSpacing` / `AppColors` / `Motion` 四套令牌，系统弹窗与 Toast 清零**
  （v0.1.543）：页内反馈一律走 `InlineOverlay` / `LocalSnackbar`；圆角固定三层
  （任务流条目 / 嵌套块 / 浮层）；入场方向统一为"自下而上"
- **悬浮窗**：跟随系统深浅色（`FloatingUi.Palette` + `themed{}` 登记式重上色，不重建窗口）、
  圆角收成三档 + 发丝描边、答疑面板可收起并留一个胶囊入口（v0.1.543）；
  先做过一轮减法——玻璃三层、详情默认折叠、边缘吸附与越界回收（v0.1.332）
- **跑马灯独立成窗**（v0.1.473）：长宽随文字自适应、跟随状态变色、`FLAG_NOT_TOUCHABLE` 绝不吃触摸
- **光标覆盖三种动作形态**（v0.1.561）：`TAP` / `LONG_PRESS` / `SWIPE` 接进四条执行路径；
  滑动用虚线指示去向、实线指示已走过，推进时长与手势同源
- **模型配置改为三层信息架构**（v0.1.445）：端点 → 模型库 → 职责分配，地址与 Key 只填一次；
  能力（识图 / 纯文字 / 调用工具）由**真实请求探测**得出并挂成徽章；主模型被证实能识图后每步直接看图
- **界面精简**（v0.1.315）：Agent 升为主界面，App 内玻璃材质移除，悬浮窗降为可选能力
- 工具链升到 material3 1.4.0——这是 **Material 3 Expressive** 组件的起点，
  此前版本根本没有这批 API（v0.1.693）

### 工程地基

- **搬包重分层**（v0.1.310）：148 个主源文件 `package` 与所在目录 100% 对齐，
  7 个超大 Compose 页面（537~1119 行）拆成约 30 个职责单一文件，日志导出实现自 `MainViewModel` 外迁，
  行为零变化、单测基线不下滑
- **工具链补齐**（v0.1.693）：Kotlin 2.0.21 → 2.4.20、AGP 8.7.3 → 8.13.2、compileSdk/targetSdk 36；
  删掉两个零引用依赖；仓库改国内镜像优先（同一批新依赖解析从"十几分钟没结果"降到 1~2 分钟）；
  接入 detekt（基线豁免存量 1836 条）与 JaCoCo——**至此新增代码再犯同类问题会立刻失败**
- **引擎按角色拆分**（本版）：`AgentEngine.kt` 4459 → 4219 行，抽出 AI 输出解析
  （`AgentResponseParser`）、设备事实读取（`DeviceFacts`）、执行验证判据（`VerifyRules`）、
  视觉路由（`VisionRouting`）、shell 判定（`ShellRules`）五层，新增 73 个单测；
  只抽"纯逻辑、能被单测覆盖"的单元，每步编译 + 单测全绿再走下一步
- **稳定性加固**（v0.1.334）：异常不再穿透整个任务、队列改原子操作不再丢任务、
  协作事件改 `Channel(CONFLATED)` 不再静默丢弃、主循环高风险调用点逐个隔离并加连续异常护栏——
  区别在于出问题时它降级、重试、请求用户介入，而不是无声停摆
- 官网独立站点（Vue 3 + Vite）与宣传片落进仓库（v0.1.335）

### 本版改动（v0.2.714）

`AgentEngine.kt` 4459 行，`runInner` 一个函数 676 行。这不是"写得丑"，是**改不动**：
任何一次决策微调都要在四千行里找位置，改完没法单测（要靠真机 + 无障碍服务才能跑起来），
评审也只能靠肉眼。这一批开始按角色拆分，但**不做一次性重写**——
先抽"已经被旁路使用、纯逻辑、能被单测覆盖"的单元，每步编译 + 单测全绿再走下一步。
本批完成四步（AI 输出解析、设备事实读取、执行验证判据 + 视觉路由、shell 执行链），
`AgentEngine.kt` 4459 → 4219 行，新增 73 个单测。

#### 优化

- **抽出 AI 输出解析层**（新增 `engine/AgentResponseParser.kt`，157 行）
  - 引擎原先自己既做编排又做"把模型吐出来的一段字变成引擎能用的对象"，
    解析逻辑夹在四千行里既看不清边界也没法单测。现在这层只做一件事：
    **字符串进、模型对象出**，不落任何副作用——日志与 `activePlan` 的写入仍留在
    `AgentEngine.parsePlanResponse`，调用方行为逐字未变
  - 与 `domain/rules/EngineRules.kt` 的分工：那里放"判定规则"（要不要审核、温度多少），
    这里放"格式解析"。新增 `AgentResponseParserTest`（20 个用例）覆盖
    澄清 / 纯对话 / 计划三种正常去向与两种失败，以及代码块包裹、前后夹解释文字等真实模型输出
- **抽出设备与环境事实读取层**（新增 `engine/DeviceFacts.kt`，196 行）
  - 对应拆分目标里的 ContextBuilder **取数部分**：只回答"这台机器现在是什么样"
    （装了哪些应用、屏幕多大、有没有网、多少电、剩多少空间），
    不负责拼提示词、不做任何决策。取数与组装分开后，"取错了"和"写错了"是两类问题，各自能单独验证
  - 应用数量缓存随之收拢进该类，按任务失效（`resetAppCache()`），
    原先散落在引擎里的 `@Volatile` 缓存字段与 `MAX_DEVICE_QUERY_APPS` 常量一并删除
  - 两个纯文案函数（应用清单提示词、`device_query` 查询答复）提到 `companion object`
    并新增 `DeviceFactsTextTest`（9 个用例）——**截断必须显式披露**这条约定原先只存在于代码里，
    现在被测试锁住：清单漏项会让 AI 把已装应用判成"没装"，进而反问或直接放弃任务
- **抽出执行验证判据**（新增 `engine/execution/VerifyRules.kt`）
  - 原先主循环里内联判断"这次失败要不要再跑两遍""这条错误算不算结构性错误""这一步在历史里记成什么状态"。
    这些判据全部依赖 `verify.reason` 的**文案**：改一句提示词就可能悄悄改变重试行为，而夹在 600 行主循环里
    既看不见也没法验证。抽出来后"改文案是否影响重试"成为一条可断言的规则（`VerifyRulesTest` 13 个用例）
  - 顺带把 `verified_success` / `unverified` 这两个被多处手写、且被 `isConfirmed` 反向比对的字符串
    收成常量，避免新增第三种写法
- **抽出决策链路的视觉路由与来源归属**（`core/ai/VisionRouting.kt`）
  - 端侧 3B / 云端视觉 / 文字描述三条链路互相补位的条件（含"复杂页交给云端、但云端不可用时必须回落 3B"）
    下沉为纯函数 `VisionRouting.route`，用 9 个用例把路由真值表钉死——
    任何一条链路被"优化"掉，都会让 AI 面对一个完全不可见的页面，这里不让它靠肉眼保证
  - 「本轮视觉来源」由字符串字面量改为枚举（`VisionRouting.Source`），
    原先下游靠 `source == "云端"` 做比对的写法一并消除；模型名映射随之收进 `modelOf`
- **抽出 shell 执行链的通道判据与输出整理**（新增 `engine/execution/ShellRules.kt`）
  - 两条执行链（`runRealShell` 回注 AI / `execShellViaChannel` 端侧自发）原先**各自内联了一份完全相同的
    通道选择**：`ADB`/`SHIZUKU`/`TERMUX`/`AUTO` 四个分支 × 三条通道的可用性判断，连无线 ADB 的桥接 lambda
    也抄了两遍。改一处漏一处，就会出现"这条命令走的通道和用户选的不一样"。现在通道判据收敛为纯函数
    `ShellRules.pickChannel`（显式偏好只认自己那条，AUTO 按 无线ADB → Shizuku → Termux 顺位），
    失败原因文案随之拆成"回注 AI 的完整版"与"端侧自发的简短版"两处，逐字保留原提示
  - Termux 工具链命令识别（`isTermuxToolCommand`）与 shell 输出的预算截断（`renderShellOutput`）一并下沉。
    后者保持**先嗅探 + 转 Markdown、再按预算截断**的顺序约定——顺序反过来会把 4000 字符的 HTML 前缀
    截在 `<head>` 中段，转换器要么拿不到正文、要么把残片当正文，等于白转；该约定原先只写在注释里，
    现在由用例锁住（含"JSON / dumpsys XML 一律不瞎转"与"超预算截断到 4000"）
  - 真实副作用（Shizuku / 无线 ADB / Termux / 无障碍的调用、`lastShellOutput` 写入）仍留在引擎，
    本步只搬移与收敛判据，不改执行行为。新增 `ShellRulesTest`（17 个用例）
- **版本号方案切到 0.2.x 线**（`app/build.gradle.kts`）
  - `versionName` 由 `"0.1.${buildNumber}"` 改为 `"0.2.${buildNumber}"`——上一批已经把工具链升到
    Material 3 **Expressive** 的起点（material3 1.4.0 / compileSdk 36 / Kotlin 2.4.20）并开始按角色拆分引擎，
    是一次代际升级，却仍顶着 0.1.x 的号往下走，从版本上看不出"东西换了"
  - `versionCode` 仍按构建自增（版本比较语义不变），只换前缀：升到 0.2 的判定权在版本名上，
    不动安装与覆盖升级的行为

#### 修复

- **`reply` 写成裸字符串时被误判为"规划解析失败"**（`AgentResponseParser.replyOf`）
  - 原代码注释声称"兼容 `reply` 为对象与裸字符串两种写法"，但实现用 `root["reply"]?.jsonObject`
    硬取——遇到 `{"reply": "我在的"}` 会先抛 `IllegalArgumentException` 被外层 `try` 吞成
    「规划解析失败」，注释里的兼容分支**永远走不到**。改用类型判定
    （`is JsonObject` / `is JsonPrimitive`）让兼容真正生效，并在单测里锁住两种写法

#### 已知差异（保留原状，未改）

- 调试轨迹里端侧描述通道的模型名仍写作「ML Kit 中文OCR」。本地读图自 v2.2 起已统一由外挂视觉 Agent 承担、
  主程序不再内置 OCR，这个标签写的是旧实现。本批只做抽取、不改显示口径，故沿用原文并在此记明

---

## [v0.1.693] — 2026-09-29

这一批不动 Agent 的决策逻辑，只补工程地基：工具链落后了两年、依赖里躺着两个从未被引用的库、
设计令牌有四处各写一遍的魔数、构建门禁只跑测试不做静态检查。共同点是"现在没坏，
但每次改动都在悄悄加利息"——拖得越久，升级越贵。所以按"删干净 → 对齐版本 → 加闸门"三步走，
验收标准是**编译 + 单测 + lint + 静态检查全绿**，不含行为变化。

### 工程

- **工具链升级**（`gradle/libs.versions.toml`、`app/build.gradle.kts`）
  - Kotlin 2.0.21 → 2.4.20、AGP 8.7.3 → 8.13.2、Compose BOM 2024.12.01 → 2026.06.01、
    material3 1.3.1 → 1.4.0、compileSdk/targetSdk 35 → 36（Android 16）
  - material3 1.4.0 是 Material 3 **Expressive** 组件的起点（MotionScheme、形状形变、加载指示器）：
    此前版本根本没有这批 API，"按 Expressive 标准做 UI"无从谈起
  - 同步 core-ktx 1.15.0 → 1.17.0、lifecycle 2.8.7 → 2.10.0、activity-compose 1.9.3 → 1.12.4
  - **未取**最新的 Compose BOM 2026.09.00：那批（ui 1.12.x）要求 AGP 9.1+ 且 compileSdk 37，
    会把这次升级从"换版本"变成"换构建系统"，风险不成比例；两条线的 material3 都是 1.4.0，
    Expressive 该有的组件一个不少
- **Kotlin 2.4 破坏性改动适配**（`app/build.gradle.kts`）：`kotlinOptions.jvmTarget = "17"`
  在 2.4 已移除（字符串形式直接编译报错），改为 `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`
- **依赖清理**：删掉 `navigation-compose` 与 `material-icons-extended`——全项目零引用
  （图标体系早已整体迁到 Lucide，见 `ui/icons/AppIcons.kt`）。声明却不引用，等于每次构建
  都白付解析与体积的代价，也让"到底用哪套"变得含糊
- **仓库顺序改为国内镜像优先**（`settings.gradle.kts`）：原先 `google()` / `mavenCentral()` 排在最前，
  国内直连常只有几十 KB/s，镜像被放在末尾只能兜底。调序后同一批新依赖的解析从"十几分钟没结果"
  降到 1~2 分钟
- **质量门禁补齐**（`app/build.gradle.kts`、`.github/workflows/android-ci.yml`）
  - 接入 **detekt**：默认规则集 + 基线豁免存量 1836 条。"基线只固化今天已有的问题"，
    所以这次接入不会先爆红，但新增代码再犯同类问题（超长行、超长函数、未用导入…）会立刻失败——
    这是防止 4000 行级文件继续变胖的唯一自动闸门
  - 接入 AGP 内置 **JaCoCo**（`enableUnitTestCoverage`），CI 产出并上传覆盖率报告。
    不设覆盖率阈值：本工程主体是无障碍 / Shizuku / 悬浮窗这类只能在真机跑的代码，
    JVM 单测天然够不着，硬设阈值只会逼出"为凑数字而写"的测试
  - 当前基线：522 个单测全过，指令覆盖 15%、分支覆盖 13%——这个数字用来**看盲区**，不用来考核

### 优化

- **按压缩放收口为单一令牌**（`ui/theme/Motion.kt` 新增 `PressScale` 与 `pressScaleSpec()`）：
  原先 `PressableScale` 与悬浮导航栏各写一遍 `0.97f` 加同一组弹簧参数，改一处必漏一处；
  现在两处都引用同一个令牌，手感不会再各自漂移
- **主题去掉死参数**（`ui/theme/Theme.kt`）：`PhoneAgentTheme(dynamicColor: Boolean = false)`
  的函数体从未读过它——留着会让调用方以为"这个开关能开动态取色"

### 修复

- **升级后新出现的三条 lint 错误**（均来自新库新增的检查项）
  - `MainActivity.kt`、`SettingsScreen.kt`：`LocalContext.current as Activity` 改为
    `LocalActivity.current`。LocalContext 不保证是 Activity，强转在预览 / 测试环境会直接崩
  - `SettingsLongRun.kt`：检查点在 composable 内读系统 `Locale`（`NonObservableLocale`），
    抽成普通函数 `timestampText()` 并改用 `Locale.ROOT`——这条格式里只有数字和连字符，
    用 ROOT 显示不变，也不必靠 suppress 把提示压掉

### 文档

- **规划文档与代码对账**（`杂项/HPA中长线任务优化及agent逻辑优化文档.md`）：v2.2 声称
  "中等任务用脚本执行（一次规划、偏差才问）"，但代码里 `ExecutionStrategy.SCRIPT` 只是个
  **被记录的标签**——`AgentEngine.run` 读出它写进日志与任务标签，执行路径始终是"每步问云端"的循环，
  也没有 `TaskScript` / `ScriptStep` / `ScriptExecutor`。已在正文与结尾标注实现状态，
  避免把"规划中"读成"已完成但漏勾"

---

## [v0.1.679] — 2026-09-27

上一批把提示词工程带进了可审计闭环，这一批就用审计的视角把文案里剩下的口径问题清了一遍。
共同点还是"同一件事在两处各写一遍"：完成判定让模型去看"页面指纹"——那是端侧的内部变量，模型根本看不见；
"没进展"在决策规则与失败处理里各给了一套次数；`open` 的三种形态没说清互斥；`page` 没标明是字符串；
"用户说的文字"与"屏幕上显示的文字"混为一谈。逐条对齐后，模型看到的是它真能自查的东西，且只有一套口径。

### 优化

- **完成判定去术语化**（`PromptBodies.SYS_COMPLETION`）：「页面指纹与任务开始时相同」改为
  「当前页面与任务开始时完全没变（元素树内容一致）」——"指纹"是端侧内部变量，模型无法据它自查、
  只能靠猜；换成它真能看到的东西才可验证
- **完成判定全项目只留一条标准**（`PromptBodies.DEC_IRON_STEP`）：决策段原先自写一套
  （"目标结果出现 / 目标页面打开 / 目标文档生成"），与「任务完成」那一条（多一项"内容完整呈现"、
  且要求"亲眼看到"）不一致；照着前者走就会漏掉后者。现直接指回同一条
- **无进展判定与失败处理合并为同一套次数**（`SYS_DECISION_RULES` / `DEC_FAILURE`）：
  原写"同一 intent+target 连续 2 次且页面指纹未变 → 必须换策略、不得第 3 次原样重试"，
  失败处理却写"连续 3 次未生效 → give_up"，两套口径并存时按哪条都说得通。
  现统一为"换过仍无进展、累计 3 次未生效 → give_up"，并去掉"指纹"这一内部术语
- **失败处理补上"与计划"的优先级**（`DEC_FAILURE`）：计划写的那一步已经失败过时，
  本步应先按上面的方法换方式，不必原样重做计划写的那一下；换过仍失败才 give_up，并说明计划这一步走不通
- **`open` 三选一说清互斥**（`PromptBodies` 意图表，中英同步）
  - 明确"三选一，**不要混着填**"：`uri` 用于用户给的网址/文件；`app + page` 用于用户指某软件里的某个页面，
    **这一形态没有 uri**；`uri + app` 是网址/文件已定、只额外点名用哪个应用打开
  - `page` 标注为**字符串**形式的序号（如 `"1"`）——此前只写"App 内页直达索引"，模型会填成数字
- **目标值一律用"屏幕上真实显示的文字"**（`SYS_TARGETING`，中英各新增一段）：
  界面是中文就填中文——用户说 "Buy Now" 而按钮写的是「立即购买」，就填「立即购买」；用户用什么语言说
  不影响。此前只说"取元素清单里的文字"，模型仍会把用户的口语原样搬进去，字面永远匹配不上
- **动作合并的禁止项说明更精确**（`SYS_MERGE`）：原写"第一个动作会跳转新页面"，
  容易被读成"合并里有跳页就不许"，从而白白放弃「输入+搜索」；现明确是**第一个动作自身**跳页才禁止，
  第二个跳页正是「输入+搜索」进结果页的常规路径
- **倒计时广告的等待不计入失败次数**（`DEC_INTENT_TIMING`）：明确 wait 永远不会「❌ 未生效」，
  一直等到广告消失再继续，别因为等了几次就收尾——否则"广告上一律不许点"与"3 次就收尾"会互相打架
- **网页浏览的通道特权换掉一句含糊表述**（`PromptBodies.BROWSE`）："不用等谁放行"改为
  "不会卡在无障碍/Shizuku/ADB 的授权门上"，把"等谁"这个模糊主语点明

### 修复

- **金样本回填**（`PromptDumpTest`）：页面、计划、任务记忆三处样本与上面的口径同步
  - 支付页样本补上两个支付方式控件与 `viewId`（此前五个元素里只有一个带 id，
    与"元素清单附带 `viewId`"的现状不符，模型没法照着样本学 `by=id`）
  - 计划在"点确认支付"前补一步"把支付方式切到余额"，当前步骤改为 `5/6`，
    让任务、计划、当前步骤、页面、任务记忆五者同指一件事
  - 任务记忆与经验规则换成当前页面**当场可验证**的表述（"不吃辣，辣度要选不辣"），
    去掉"支付按钮常被优惠弹窗遮挡"这类在页面上无法验证的旧样本

---

## [v0.1.620] — 2026-09-27

提示词工程从"手改文件 + 经验验证"升级为可审计闭环：导出的提示词可被真实模型逐条审查，
改文案前先有金样本兜底；同时把两处文案行为口径收紧。

### 新增

- **提示词审计工具链**
  - `PromptDumpTest`（离线导出）：把全部入口点提示词逐字节导出到 `app/build/prompt_dump/`
    （`<id>.txt` + `manifest.json`），供外部脚本送审
  - `tests/prompt_audit.mjs`（Node 审计脚本）：读 manifest 逐条送 agnes-2.5-flash 审视
    confusions / contradictions / missing / redundant；支持 `--mode=run` 实跑检查合规、
    `--with-context` 补 system 消息、`--resume` 断点续跑
  - `杂项/HPA提示词系统文档.md`：53 块 8 组清单、装配流水线、标志位/占位符全表、
    同源点、注入预算、维护指南、安全网单测
- **会话承接英文指代词**（`SessionContext`）：`STRONG_MARKERS_EN` + 整词正则（`Regex.escape`
  + `\b` 边界 + IGNORE_CASE），避免 "against"/"bargain" 误命中 "again"

### 优化

- `write_doc` 缺关键信息时行为口径从 `clarify` 收紧为 `give_up 并说明缺什么`（中英提示词同步，金样本回填）
- `ActionMode` 保守档描述「只读」改「读取信息」，与动作语义对齐

---

## [v0.1.619] — 2026-09-26

执行准确度存在四处结构性缺口：视觉兜底只服务 `by=hint`（WebView/自绘页里 AI 按 `by=text`
给出的目标明明看得见，却只收到"定位失败"）；只有点击做了执行前重定位，输入/滚动的坐标还停在
"观察那一刻"；点错但页面有反应被判成功、且这件事不会告诉 AI；元素清单不给 `viewId`，
AI 只能靠文字匹配选目标。

### 优化

- **视觉兜底覆盖全部定位方式**（[AgentEngine.kt](./app/src/main/java/com/phoneagent/engine/AgentEngine.kt)）
  - 触发条件由「`by=hint`」放宽为「端侧在元素树里没命中」：`by_id`/`by_text` 未命中同样落到视觉定位
  - 先在元素树里试一次，命中就不发视觉请求（不让免费路径为付费路径让路）
  - 提示词 `SYS_TARGETING` 中英各补一句，说明该兜底由端侧自动完成（文案变更已同步金样本）
- **非点击动作在执行前重定位**（`AgentEngine.executeWithVerify`）
  - `type_text` / `scroll` / `scroll_to` / `swipe` 执行前重新抓当前页面，
    按 `viewId`/文字 + 最近距离重定位目标（刻意不用遍历序号，跨快照不稳定）
  - 指纹基准同步换成"此刻"的页面：否则这几秒里页面自身的漂移会被当成"动作已生效"
  - 方向滑动不在其列——它按设计以屏幕中心为起点，不带元素目标
- **元素清单附带 `viewId`**（`UiElement.describe()`）：AI 对更多控件可用 `by=id` 精确定位，
  不必只靠同名文字（同一段文字常同时挂在容器与内层控件上）

### 修复

- **落点与目标不一致不再静默**（`ClickRunner` + `VerifyResult.note`）
  - 点击生效、但落点命中的是别的控件时，结果仍按成功算（已生效的点击若被判成"可疑"，
    AI 很可能重按一次，对发送/删除这类不可逆按钮就是重复副作用）
  - 但警示写进 `note` 并回注下一轮决策：否则 AI 会把并非本次操作带来的页面变化当成预期结果继续推进

---

## [v0.1.611] — 2026-09-25

主模型视觉链路存在五个断点：提示词与当下目的无关（描述是整屏铺开的通用罗列）；hint 定位优先级反了
（先走要花钱发网络的远端 AIDL，再回落零成本的本地匹配）；云端视觉明明返回了坐标却没人接（同一张图
编码上传两次）；`base64Image` 每步重新 scale/JPEG/Base64（同一步内被决策与定位各算一遍）；主模型
能否读图只看手填布尔、模型库里真实探测出的能力完全不参与。本次以三态识图、统一视觉提问入口 `see`
、缓存与优先级修正收口这条链路。

### 新增

- **主模型识图三态**（`core/ai/VisionRouting.kt`）
  - `AUTO`（按模型库探测结果决定，探测不出等同不支持）/ `强制开` / `强制关`，`resolve` 为唯一合流口径
  - 旧布尔 `has_vision` 仅作迁移读取（true→`ON`），不再写入；设置页换三档分段按钮
  - `AgentEngine.mainSeesImage(s)` 收口所有判定点，决策附图、能力提示、trace 全走它
- **看图追问意图 `see`**（带目的的按需视觉调用）
  - `IntentType.SEE` + 内置技能 `skill_see`，`SkillCompat.Normalized.Vision` 归一化（缺「问题」中文拒绝）
  - `AgentEngine.invokeVisionAsk`：一句 purpose + 上下文干净的单条消息，答案作为上一步结果回注；
    带 `target` 时回 JSON 拿坐标，写入 `lastVisualCoordinate` 供下一步复用
  - 归低风险档（保守模式可用），不触碰设备、不产生动作，照 `browse_*` 先例不设独立 `ActionType`
  - 提示词：意图表（中英）、铁律「需要视觉判断时用 see 追问」、`dec.always.see` 区块、
    capabilities 有/无视觉两版各补一句；金样本同步回填
- **`ACTION_SCHEMA` 的 intent enum 现场生成**（`AiClient.kt`）
  - 由 `IntentType.ALL` 拼出，不再手抄 —— 此前手抄落后全集 12 项，结构化回退路径会把新意图判非法

### 修复

- **hint 定位优先级反转**：本地免费匹配（`ControlFormat.locate`）提到最前，远端 AIDL 退居其后，
  云端视觉再次；先花钱后免费的顺序倒过来了
- **云端视觉坐标复用**：每步自动描述改带 purpose，其返回的坐标映射为 `DetectedControl`
  并入本地区域表，不再"描述归描述、定位归定位"各传一次图
- **同图重复编码**（`AiClient.base64Image`）：单槽缓存（同一位图对象直接复用 Base64 结果），
  每步开头 `clearImageCache()` 释放
- **云端条目画框错位**（`DetectedControl.drawBoxes`）：无 bounds 的云端条目跳过不画，
  避免框画到左上角当假证据

### 验证

- `compileDebugKotlin` / `testDebugUnitTest`（524 项，含新增 14 项）/ `assembleDebug` 全通过
- grep 复核：`settingsVal.hasVision`、`visionDescribe(`、`visionLocate(` 零残留；
  `HAS_VISION` 仅存迁移读取与 `PromptFlag` 枚举两处预期用法

---

## [v0.1.561] — 2026-09-25

虚拟光标此前只有"点击"一种形态，而且**只有降级到坐标手势那条路才会点亮它**——节点直点（多数点击实际走的就是
这条）在屏幕上不留任何痕迹，滑动则完全没有光标。于是用户看到的是"页面自己变了"，看不出 AI 在动手机。
本次把光标接进三种动作、四条执行路径，各配一套画法与时间线：点击飞向落点后做按压脉冲；长按压住不放、
光环呼吸、外圈张开；滑动落在起点后沿轨迹推进，虚线指示去向、实线指示已走过。

### 新增

- **光标形态 `CursorMode`**（`overlay/CursorPointerView.kt`）
  - `TAP` / `LONG_PRESS` / `SWIPE`，同一个 View 按形态切换画法与时间线切段
- **长按形态**：移动 → 压住不放 → 松开
  - 保持段光环按正弦呼吸（取整数周期，首尾落在零相位上，衔接不断），外圈随按压进度张开、松开时收回
  - 保持时长由调用方传入，与真实按压同为 800ms，手感对得上
- **滑动形态**：沿轨迹推进 → 松手后轨迹淡出
  - 未走到那段为细虚线（指示去向），已走过那段为粗实线（指示走了多远），终点补一个方向箭头
  - 推进时长与手势 `durationMs` 同源，轨迹才和手指同步；光标停在轨迹末端
- **派发统一入口**（`overlay/CursorOverlayService.kt`）
  - `point` / `longPress` / `swipe` 三个门面收敛到私有 `dispatch`，落位方式只在一处决定
  - 滑动刻意不做"先飞过去"：真实手指也是骤然落在起点上的，先飞会让整条轨迹晚于手势，
    看起来像光标在追着手指跑

### 修复

- **节点直点没有光标**（`device/a11y/ActionExecutor.kt`）
  - `clickNode` 先按控件 bounds 把光标送到控件中心再 `performAction`，并改为 `suspend`，
    使"光标先到位再动手"的同步模式对这条路径同样生效
  - 这是 `ClickRunner` 执行阶梯的第一级，多数点击实际走它；此前光标只在降级到坐标手势后才出现
- **滑动没有光标**（`device/a11y/ActionExecutor.kt`）
  - `swipe` 在派发前按 `(x1,y1)→(x2,y2)` 与 `durationMs` 播放轨迹，`swipeDirection`、
    元素内 `scroll`、滚动查找一并覆盖
- **滚动容器没有光标**（`device/a11y/ActionExecutor.kt`）
  - `scrollContainer`（`ACTION_SCROLL_FORWARD` / `BACKWARD`）没有坐标、没有手势，此前屏幕上全无痕迹；
    改为按容器 bounds 现场推导一段纵向轨迹。这条是 AI「滚动查找 / `scroll_to` / `scroll_container`」
    的主路径，与「AI 不给坐标」正好对得上
- **shell 通道的滑动与长按没有正确光标**（`domain/rules/ShellCommands.kt`、`engine/AgentEngine.kt`）
  - 原 `parseTapPoint` 反查只认"点"，注释里就写着"真正的滑动静默跳过"：`input swipe` 滑动没有光标、
    起终点相同的 `input swipe`（长按）被画成点击
  - 改为 `sealed interface Motion { Tap / LongPress / Swipe }` + `parseMotion`：按起终点是否相同
    区分长按与滑动，并取命令里第 5 个参数的毫秒数作为按压 / 推进时长
  - `triggerCursorForShell` 按形态分派到 `point` / `longPress` / `swipe`
- **长按与点击共用同一个脉冲动效**：两者观感无差别，用户分不清 AI 是按了一下还是按住不放
- **长按时长 800ms 字面值**：收口为 `ActionExecutor.LONG_PRESS_MS`，手势时长与光标动效时长同源

### 说明

- `CursorPointerView.animateTo` 由 `animate(mode, …)` 取代；光标已压在落点上时移动段压到 0，
  不做无意义的空移
- `CursorOverlayService.setVisible`（截图前隐藏）对三种形态一视同仁，轨迹同样不会被截进画面污染 AI 读屏
- 设计前提是**AI 不会以坐标表达滑动**（`swipe` 只给 `direction[,distance_px]`，`scroll_to` 只说"要找什么控件"），
  所以滑动的几何一律由本机现场推导：容器边界、屏幕中心、shell 命令里的起止点——AI 不给坐标，光标照样有轨迹
- 单测 `ShellCommandsTapPointTest` 随之改为 `ShellCommandsMotionTest`，断言三形态反查

---

## [v0.1.544] — 2026-09-25

新增 **`show_agent`** 动作与意图（纯端侧、不操作设备）：把用户拉回 Agent 页当场看结果。
`text` 可选——给了就先落成 Markdown 文档，随后在 Agent 页全屏展示；`summary` 作为标题。
该动作在只读模式下同样放行，并注册为内置技能 `skill_show_agent`（沟通类）。

### 新增

- **`IntentType.SHOW_AGENT` / `ActionType.SHOW_AGENT`**（`domain/model/AgentIntent.kt`、`AgentAction.kt`）
  - 不触碰设备：拉起本应用并切到 Agent 页，`text` 可选先存为文档
- **执行链路**（`engine/execution/IntentTranslator.kt`、`ActionMode.kt`）
  - 转译为 passthrough 动作；只读模式下与 SAY / REMEMBER / DEVICE_QUERY 同档放行
  - 低风险意图清单扩至 21 项，保守模式同样放行
- **技能注册**（`feature/skill/SkillCatalog.kt`、`SkillCompat.kt`）
  - `skill_show_agent`，分类「沟通」，`legacyIntent = SHOW_AGENT`，支持 `text` / `summary` 参数
- **`DocViewerScreen` 全屏阅读页**（`ui/agent/DocViewerScreen.kt`，新建）
  - 整屏只放正文，覆盖含底部导航栏；出口为底部「返回主页」按钮与返回手势
  - `MainActivity` 新增 `showAgentPage` 静态入口与 `EXTRA_AGENT_PAGE` / `EXTRA_AGENT_DOC` 常量，引擎调用后自动切回 Agent 页并可选全屏展示
  - `AgentItems.kt` 文档预览卡片新增全屏图标入口（`AppIcons.Expand`）
- **提示词与策略同步**（`engine/AgentEngine.kt`、`engine/AgentPrompts.kt`、`execution/ActionModePolicyTest.kt`）
  - 中英文提示词均补充 `show_agent` 意图说明、使用边界（用户已在 Agent 页时禁用）及文档类任务末尾补步规则
  - 低风险意图清单 20 → 21 项，三档合计 37 → 38，对应测试断言同步更新

---

## [v0.1.543] — 2026-09-25

同一套设计语言此前只落在"页面骨架"这一层：卡片、顶栏、配色有令牌，但**具体页面里的圆角、间距、颜色、动画时长
还散着大量字面值**，于是同一种"卡片"在不同页里半径不同、同一种"呼吸"在不同模块里快慢不一。
更割裂的是还有三处系统弹窗与五处 Toast——它们自带另一套字体、圆角与入场方式，用户一眼就能看出"这不是同一个应用"。
本次把全项目（含 Agent 页）收口到既有的 `AppRadii` / `AppSpacing` / `AppColors` / `Motion` 四套令牌，
并把系统弹窗与 Toast 清零：页内反馈一律走内嵌浮层与内嵌浮条。

同一批里还有三处"两套标准并存"的地方，症状各不相同但病根一样：**同一件事被写了两遍**。
内置浏览器一上网就把 App 界面切走、把用户正在做的事打断；点击成败的判定在点击与滑动之间各有一套等待节奏，
同一条任务里一半动作秒判、一半动作干等；悬浮窗只认浅色、一旦弹出答疑面板就整块占着屏幕不能收起，
而它的圆角与配色又是另起一套、与 App 侧同名的协助浮层对不上。以下逐项收口。

### 新增

- **`InlineOverlay`：全站统一的页内浮层**（`ui/components/Components.kt`）
  - 压暗底 + 居中面板；圆角取 `AppRadii.Overlay`、底色取 `AppTheme.colors.surfaceBase`，
    入场为淡入 + 自下而上并经 `reduceMotion` 归零——与底部 Tab、二级页同一套入场语言
  - 新增 `fillHeight` 参数：内容里有 `weight(1f)` 的长列表（如模型选择）必须打开——
    面板高度不定时权重拿不到可分配空间；短内容保持默认的"随内容收紧"，浮层才不会变成一块空板
- **`LocalSnackbar`：页面内轻量反馈的唯一入口**（`SnackbarState` 由 `MainActivity` 提供）
  - 项目不使用系统 Toast：各页只调 `snackbar.show(文案, 类型)`，投递位置与样式由宿主统一决定
- **`OverlayScrim`：压暗底的唯一定义点**：`InlineOverlay` 与 Agent 任务抽屉共用同一层黑度，不再各调一个值
- **`GlassHeaderScaffold` 新增 `overlay` 槽**：满屏浮层交给骨架承载，压得住页眉与正文
- **`DurationPulse = 900`：呼吸动画的唯一定义点**，统一 4 处——概览页运行指示、Agent 规划点、
  Agent 执行状态点、列表骨架 shimmer；概览运行指示同时接上"减少动画"
- **`HeadlessWebHost`：静默浏览器宿主**（`feature/browser/HeadlessWebHost.kt`，新文件）
  - 把给 AI 用的 WebView 挂进一个**全透明、不吃触摸、不抢焦点**的悬浮窗（`TYPE_APPLICATION_OVERLAY`，
    与悬浮窗服务同一类型），`alpha = 0`：窗口照常参与合成，页面照常布局、脚本照常执行，屏幕上却一个像素都看不到
  - **不用"把窗口挪到屏幕外"**：部分 ROM 会把越界窗口直接判成不可见而停掉渲染，网页就成了永远加载不完的样子
  - 窗口尺寸取 `getRealSize` 的真实屏幕尺寸，网页拿到与可见页一致的视口；`importantForAccessibility` 设为
    `NO_HIDE_DESCENDANTS`——AI 的元素树来自"当前前台窗口"，网页节点混进去只会污染感知
  - 建不起来（系统没给悬浮窗权限）**不硬撑**：返回 null，由调用方回退到旧的"切到可见页"路径；
    建好后一直留着复用，省掉每次任务重建 WebView 的开销
- **`createBrowserWebView()`：给 AI 用的 WebView 构造收口成一份**
  - 可见页（`ui/browser/BrowserScreen`）与静默宿主共用同一份设置与两个客户端——原先只有 `BrowserScreen`
    一份内联实现，再写第二份必然会漂成"可见页能点、静默页点不动"
- **`VerifyTiming.SAMPLE_DELAYS_MS`：动作取样节奏的唯一定义点**（`engine/execution/VerifiedClickExecutor.kt`）
  - 点击（`ClickRunner`）与滑动/滚动/输入（`VerifiedClickExecutor`）共用同一套取样时刻。两处各写一份的后果很实际：
    同一条任务里"响应快的页面"一半在短轮询下秒判成功，另一半还在固定等待里干等
- **`ActionExecutor.hitTest()` + `NodeHit`：落点审计**（`device/a11y/ActionExecutor.kt`）
  - 派发手势**之前**先问清"这一下会落到谁身上"（只读探测，不派发任何动作）；`NodeHit.describe()` 供日志与失败原因使用
  - 不写清楚这一步，AI 只会把同一下再点一遍：落点不是目标控件是**定位/坐标偏了**（该重新定位），
    落点就是目标控件是**控件本身无响应**（该换意图），下一步的做法完全不同
- **`ActionExecutor.DerivedLabel`：派生标签的唯一定义点**
  - 可点击容器常常没有自己的文字（微信聊天列表行就是这样，标签挂在不可点击的子控件上），
    于是用后代文字拼一个标签。感知层（收录元素）与执行层（回活节点树定位）**必须共用这一份定义**
  - 上限 `MAX_PARTS = 3` / `MAX_CHARS = 60` / `MAX_DEPTH = 3`：防止列表容器把整页文字拼成一大串
  - `recycleChildren` 参数区分两种用法：感知层取完回收子节点（避免 API<33 上的泄漏），
    执行层必须传 `false`——它要把命中的**节点句柄**交给上层 `performAction`，一旦回收就会点到已失效的节点上
- **`ActionExecutor.liveCenter(selector)`：目标控件"此刻"的中心点**
  - 供手势点击前取最新落点：AI 思考的数秒里页面可能已滚动/被顶动，快照坐标早已偏离
- **`FloatingUi.Palette` + `themed{}`：悬浮窗跟随系统深浅色**（`overlay/FloatingUi.kt` / `FloatingWindowService.kt`）
  - 悬浮窗跑在 Service 里，拿不到 App 的 Compose 主题（`LocalAppColors`），所以直接读系统深浅色开关——
    App 侧 `PhoneAgentTheme` 默认也是跟着它走的，于是两边永远在同一时刻翻面，不需要跨进程同步状态
  - 新增整套深色取值（`PANEL_DARK` / `PANEL_SUNKEN_DARK` / `TEXT_*_DARK` / `BRAND_DARK` / `ON_BRAND_DARK`）
    与 `ui/theme` 的 `DarkAppColors` 同源。深色下主色是**亮青、压在上面的字是深墨**，底/字关系整体翻转——
    深绿 `#0E7C66` 画在近黑面板上会糊掉
- **答疑面板可收起 + 右下角入口胶囊**（`interactCollapsed` / `collapsedPill`）
  - 收起是"让出屏幕"而不是关掉问题：AI 仍在等这个答案，所以必须留一个入口能点回来，否则任务会永久挂住
- **Agent 页动作模式选择器：收起时只留一个档位按钮，点它从下往上拉出三档**（`ui/agent/AgentComposer.kt`）
  - 三档全摆开会把底部操作区摊宽，也会把"我现在处于哪一档"淹没在并排按钮里；收成一个按钮，"当前权限"一眼可见
  - 面板长在触发器**正上方、同一块底部玻璃之内**（与协助浮层同一种做法），玻璃板随之上抬，
    输入框不会被面板盖住，也不引入额外的弹窗窗口
  - 选中标记是档位名后面跟一个勾，而不是只靠底色——不依赖色彩分辨

### 变更

- **Agent 页优先收口**：动作模式切换条的间距节奏、图标尺寸与选中态全部改走 `AppSpacing` / `AppRadii` / `AppTheme.colors`，
  与输入面、计划清单读成同一套语言
- **设置页二级页转场由横向改为自下而上**：此前 `slideInHorizontally` 是全项目唯一一处水平位移，
  与 `MainActivity` 里"全项目只保留自下而上入场"的注释直接矛盾；现改为纵向升起 + 淡入
- **三处系统弹窗清零**：技能编辑器、模型选择、能力状态由 `AlertDialog` / `Dialog` 改为 `InlineOverlay`
- **五处 Toast 清零**：技能页 3 处、调试页 1 处、概览页 1 处改为 `LocalSnackbar`（带成功/警告类型）
- **字面色改语义色**：模型页的"测试通过"绿、记忆图谱的连线色改走 `AppTheme.colors`，深浅色主题下不再各偏一档
- **记忆图谱连线动效**改走 `DurationFast` + `EaseOut`；测试结果视图的语言标签改用 `StatusPill`
- 卡片圆角、按钮内边距等字面值择要改令牌（不做无差别替换，避免把语义不同的间距压成同一个数）
- **内置浏览器改为"静默优先"**（`feature/browser/BrowserBridge.kt`）
  - `browse_open` 的优先级：用户**正开着**浏览器二级页时用那一份 WebView（页面就在屏幕上，AI 能靠截图看见，
    这条路径本就可信可见）→ 否则在后台静默宿主里加载（界面不切走，用户该看什么还看什么）→
    两种都拿不到（宿主建不起来）才回退到老路径：把 App 切到「浏览器」二级页
  - 旧路径只在"看得见"这一点上不同：静默模式下 AI 不靠截图看网页，内容一律走 `browse_read`
- **提示词全量同步，中英各 7 处**（`engine/AgentPrompts.kt`）：`browse_open` 能力表、网页浏览章节开头、
  `browse_read` 是静默模式下**唯一**能看到网页的方式、`browse_back` 之后不再需要 `press key=BACK` 离开浏览器、
  示例里的 `expected`、规划步的上网提示、决策步的上网提示
  - 落掉任何一处，AI 都会以为"下一步截图里有网页"——它会等一张永远不会出现的截图
- **`ClickRunner` 手势落点一律取"此刻"的位置**：活节点当前位置 → 元素最新位置 → 快照坐标，
  逐个 `clampToScreen` 夹进屏幕可见区后 `distinctBy` 去重；只有手势**没派发出去**才换下一个落点
  - 排序只按时间新鲜度，不按坐标猜；派发成功即视为已交付，不重复按（同一个控件被按两遍，对发送/提交/删除就是重复副作用）
- **`VerifiedClickExecutor` 由固定等待改为短轮询**：`delay(600)` → 按 `VerifyTiming.SAMPLE_DELAYS_MS` 取样，
  一旦发现变化立即返回，不必等满整个观察窗；顺带去掉那个从未被使用的 `action` 参数（调用方 8 处签名同步简化）
- **悬浮窗圆角收成三档 + 浅底一律"实色 + 发丝描边"**（`overlay/FloatingUi.kt`）
  - `RADIUS_CARD = 28` / `RADIUS_PANEL = 18` / `RADIUS_TILE = 14`（对应 `AppRadii.Tile`）/ `RADIUS_CHIP = 12` /
    `RADIUS_PILL = 999`。同一块面板内只允许出现这几档，不许再混进 12 / 18 / 20 这类"感觉差不多"的数字——
    圆角不一致时，读起来像几个不同来源的控件拼在一起
  - 层次改用**发丝描边**（浅色 8% 黑 `PANEL_EDGE` / 深色 8% 白 `PANEL_EDGE_DARK`）而不是叠半透明黑：
    叠层会让表面变成半透明，悬浮在别的 App 画面上时底色透出来，读起来脏
- **答疑内容改为"限高内滚"**（`MaxHeightScrollView`）：短文案贴合内容高度，超长才内滚
  - 不用固定 140dp：短文案下会在卡片里留一片空白，面板看着像"没做完"
  - 也不用"高度 0 + weight 1"：选项卡窗口是 `WRAP_CONTENT`，`LinearLayout` 在 `AT_MOST` 下会把整屏剩余空间
    分给权重子视图，窗口被撑到整屏高——一层透明却可触摸的窗口会盖住整块屏幕
- **悬浮窗出口行：次要在左、主操作在右**，中间用一条权重空隙把主操作推到最右——主操作的落点固定，
  不会因为次要按钮文案长短而左右横跳（与 App 侧协助浮层的出口行同构）
- **悬浮窗选项行**（澄清候选答案）圆角取 `RADIUS_TILE`、底色取下沉实色——与输入框同一档，
  读起来是"能点的行"而不是按钮

### 修复

- **满屏浮层被压成 0 高**：`fillMaxSize()` 在 `verticalScroll` / `LazyColumn` 这类高度不受约束的容器里会退化为 0，
  因此浮层必须挂在有界根节点上。两处结构性修复：技能编辑器改由 `SkillManagerScreen` 在骨架 `overlay` 槽渲染；
  模型选择浮层上提到 `SettingsScreen` 的根 `Box`（`pickRole` 状态一并上提，`applyRoleModel` 改 `internal`，
  `SkillsTab` 签名改为 `SkillsTab(vm, onOpenEditor)`），并把这条判据写进代码注释
- 清理各文件的死 import（`AlertDialog`、`androidx.compose.ui.window.Dialog`、`android.widget.Toast`），
  全项目已无任何系统弹窗与 Toast 残留
- **微信这类"可点击容器"的节点直点在静默失效**：感知层收录元素时会把容器的后代文字拼成标签
  （聊天列表行 →「末影箱 / 测试消息 / 昨天」），元素树里 AI 看到的正是这串拼出来的标签；
  而执行层回活节点树定位时只比节点**自身**文字，于是这类目标永远匹配不到，
  「节点直点」这一级悄然失效、退化成按坐标猜。现两侧共用 `DerivedLabel` 一份定义
- **元素只露出一半时手势派发到屏幕外**：元素树里的"可见"只保证它露了一部分，列表底部半截可见的行，
  其中心点可能已经在屏幕外；派发到屏幕外的点要么被系统拒绝、要么落在别处，表现出来就是"点了没反应"。
  现所有落点先 `clampToScreen`
- **落点与目标不一致时只回一句"未生效"**：AI 无从分辨是定位偏了还是控件不响应，多半会把同一下再点一遍
  （对不可逆按钮就是重复副作用）。现 `ClickRunner.missReason()` 把落点命中的控件写进失败原因，
  并按 `isMismatch()` 分成"该重新定位"与"该换意图"两种指引
  - 反过来，"已派发且页面确实变了、但落点与目标不一致"这一路**只记日志、不动判定结果**——
    已经生效的点击若被判成"可疑"，AI 很可能重按一次，而对发送/删除这类按钮，重按的代价不可接受
  - `isMismatch` 两侧都按**包含**比对、不做严格相等：目标可能是外层容器、落点可能是它内层的图标/文字，
    点下去照样算点中；不可点击的落点一律算"无法判定"——此时点击其实由祖先容器承接，据此报"点错"就是误报
- **读不到页面时点击被判失败**：这会误触发"连续失败即请求用户介入"，而下一步的观察本来就会给出真实结果。
  现按"已执行但未确认"算成功，并把口径写进 `VerifyResult` 的说明（`ClickRunner` 与 `VerifiedClickExecutor` 统一）
- **只把答疑面板置 GONE 还不足以让出屏幕**：窗口是 `MATCH_PARENT` 宽，收起来之后仍然是一条全宽、
  几十 dp 高、什么都看不见却能触摸的窗口，落在其中的操作全被它吃掉。现收起时把窗口本身也收窄到
  `WRAP_CONTENT` + 右对齐（`applySheetCollapsedWindow`），屏幕上只剩胶囊那一小块归悬浮窗
- **收起答疑面板时软键盘与窗口焦点没收干净**：留着可聚焦的窗口会让底下 App 的输入法行为异常
- **系统切深浅色会丢掉用户已经打好的半句回答**：答疑的选项行/出口行是每次交互现建的，登记不到静态表里，
  只能按上次入参重绘；现重绘前先把"正在输入"状态与已输入内容记下、之后原样还回去
  （用同一个 `Handler` 的 FIFO 顺序补回输入状态，后入队的一定跑在重绘之后）
- **切深浅色不能重建整扇窗口**：视图是一次性建出来的（`buildPanel` 只跑一次），重建会丢掉正在跑的任务状态。
  现改为**登记式重上色**：建视图时顺手登记一段"拿到配色怎么涂"的代码，翻面时把所有登记项重跑一遍（`themed{}`）
- **悬浮窗在系统转深浅色时会把上一个问题重新摆回屏幕上**：翻面重绘时用的是"最近一次答疑的入参"，
  问题已经答完却留着这份记录，旧问题就会被重新渲染出来。现答复完毕/任务结束时一并清掉
- **留空点击「指导 AI」会静默挂起**：空串被 `provideUserHint` 吞掉，AI 那边等不到任何输入。
  现空输入等价于「已手动处理」
- **超长答疑内容把面板撑满整屏**：见上方"限高内滚"

---

## [v0.1.513] — 2026-09-24

以前"AI 能做什么"是一道**全有或全无**的开关：只要设备有 Shizuku 或无障碍权限，AI 就能点、能输入、能发消息，
用户在设置里既收不紧也放不开。反过来，真正需要 AI 多干一点的场合（查个包名、点一个元素树里根本没有的控件）
反而无从下手——端侧那套友好命令表只认识自己列过的几十个词，表外一律报"未知命令"。
本次把授权范围做成**三档动作模式**，并在最高档开出两条兜底通道：**自写命令**与**直调无障碍端点**。
两件事必须配套：放开能力的同时，把"批不批"交回用户手里。

### 新增

- **动作模式：三档授权范围**（`engine/execution/ActionMode.kt`）
  - **保守**：只放行低风险意图（只读 / 导航 / 本地读写，共 20 项），点击、输入、打开应用、搜索、发送、删除一律被拒
  - **均衡**：转译层全部意图可用——**即既有默认行为**，升级后零行为变化
  - **自由**：均衡 + 自写命令 + 全量无障碍端点
  - 与 `CapabilityManager.Mode`（通道模式）**正交**：通道回答"能不能执行"（设备有没有权限），
    动作模式回答"允不允许 AI 提出这类请求"（用户给的授权范围），两者叠加生效。只读通道下即使选自由档也执行不了
- **`ActionPolicy`：三档权限的唯一定义点**：风险分档、放行判定、无障碍端点白名单全部收口在这一个对象里，
  端侧门控与提示词共用同一份数据。与 `BrowserGuard` 同思路——同一件事写两遍，就会出现"提示词说能用、端侧其实拒了"
- **自由模式专属意图**（`IntentType.SHELL` / `IntentType.A11Y`）
  - `shell`：AI 亲写命令原文（`command` 字段），shizuku / 无线 ADB / Termux 通道由端侧按可用性自动选，AI 对通道无感知
  - `a11y`：AI 点名一个无障碍端点（`endpoint`）并给出参数（`args`），是"转译层够不着时的最后手段"
  - 两项都**只在自由模式下可用**，保守/均衡一律拒绝并回报中文换档建议
- **`AgentAction.aiAuthored`：区分"谁写的命令"**
  - AI 亲写的命令带这个标记，执行层据此在**本任务首次执行前**向用户确认一次（批准后本任务内不再问；拒绝后本任务内不再执行任何自写命令）
  - 端侧自己编排的命令（如 `fetch` 落到 Termux 的 `curl`）**不带**标记，不触发确认——AI 只给了个网址，命令不是它写的
  - 用 `Channel.CONFLATED` 信箱而非 `SharedFlow`：用户抢在等待方订阅之前点按，值也必须被缓存住，
    否则主循环会永久挂死在等待上（与 `userHintMailbox` 同一套理由）
  - `resolveShellApproval` **刻意不走 `_needsUser`**：协助面板是由 `pendingShellCommand` 驱动的独立交互，
    若挂到同一个标志上，两套等待会互相覆盖——一边关掉标志，另一边永远等不到
- **无障碍端点白名单 18 项**（`ActionPolicy.a11yEndpoints`）：`click` / `long_click` / `click_node` / `swipe` /
  `swipe_direction` / `scroll` / `scroll_container` / `global_action` / `back` / `home` / `recents` /
  `launch_app` / `open_uri` / `open_settings_action` / `type_text` / `capture_tree` / `screenshot` / `can_screenshot`
  - 端点名与**必填参数**都在白名单校验；写错即失败并**回报可选清单**，杜绝"AI 凭印象写一个端点名"这种无处可查的调用
  - 每个端点配 `description` / `descriptionEn` 两份说明：英文提示词直接取后者，避免英文提示里混中文
- **`SkillCatalog` 新增两项内置技能**（`skill_shell` / `skill_a11y`，归入「自由模式」分类），
  与 `IntentType` 一一对应；`SkillCompat` 同步支持从技能参数还原 `command` / `endpoint`
- **设置新增三个页面**（`ui/settings/`）：**权限与执行通道** / **数据与存储** / **关于与帮助**
  - 权限页只做**聚合不做重写**：权限雷达复用主页的 `PermissionRadar`，执行通道直接复用技能页的 `WirelessAdbTab`，
    三处状态永远同源，不会各写一套后互相漂移
  - 数据页按「能不能撤销」排序——导出只读放最前，记忆只清不恢复居中，整机重置单独占一张卡避免误点
  - 每一个破坏性动作都要点两次（首次把文案换成"确认…"）才执行，与既有危险操作同一套约定
- **自定义系统提示词**（`AppSettings.Settings.systemPrompt`）：留空用内置，填写后整体替换内置系统提示词
  - 编辑态与生效态**分离**：文本类改动不逐字落库，否则设置流会回灌编辑态，长文本贴进去会与输入互抢光标
  - 「未保存」提示优先于「当前生效」：清空输入框但还没保存时，生效中的仍是旧值，不能显示成"内置提示词"
- **页眉「浮起」`HeaderLiftState`**（`ui/components/Glass.kt`）：页眉始终吸顶，但页面一旦离顶就自动脱开上缘多让 12dp
  - 离顶距离**不去各页要**，改为从嵌套滚动里听（`headerLift` 修饰符挂在页面根节点）：调试页与技能页的滚动容器
    藏在页签面板里，逐个透传会把改动摊到整棵组件树
  - 只听"被消费掉的位移"，并在两处夹住：想往上滚却一点没被消费 → 说明已经在最顶上，直接复位；累计量夹在 `[0, 48dp]`
  - 浮起量**不补动画**，直接跟着滚动量连续变化——补了反而会落后于手指
  - 配套新增 `AppRadii.Header`（32dp，比 `Hero` 再圆一档）：一块悬空的板才不像一条被掰圆的横杠

### 改进

- **铁律 2 按档分叉**（`AgentPrompts.ironRule2CN/EN`）：默认档仍是"禁止输出 shell 命令、无障碍指令、像素坐标"；
  自由档才放开 `intent=shell` / `intent=a11y`，但**同时写明这两类只是兜底**，并要求不得输出无障碍实现细节
  （如 `performAction`、节点对象）——放开的是"能做什么"，不是"能描述怎么做"
- **授权范围一段无论如何都要追加**（`AgentPrompts.actionModeSection`）：它是端侧真实拒绝逻辑的说明书，
  缺了它 AI 会按自己的想象发请求，然后在门控那里反复撞墙。**用户自定义系统提示词也照样追加**——
  自定义不该成为绕过门控说明书的口子
- **提示词与端侧门控同源**：AI 在授权范围里看到的可用意图清单，就是 `ActionPolicy` 真实放行的那一批，
  不会出现"说了能用其实被拒"。自由档的命令表与端点全表都是**插值生成**（`ShellCommands.promptDoc` / `a11yEndpoints` 遍历），
  不是手抄的第二份
- **动作模式门控的位置是刻意的**（`AgentEngine` 主循环）：技能归一化**之后**（此时 intent 已是标准意图名），
  浏览器分流**之前**（`browse_*` 走独立通道、不进转译层，只有卡在这里才能把它一并覆盖）。
  转译层内部对 `shell` / `a11y` 另有自检，那只是**纵深防御**，不替代此处
- **连续被拒 3 次即停止任务**（`MAX_MODE_DENY_STREAK`）：保守模式下 AI 若反复尝试被禁动作，
  只会把步数耗在无意义的往返上；明确停下并告诉用户去换档，比让它空转到 `maxSteps` 更诚实
- **引导路径同样要过门控**：用户给的"指导"不能成为越权的旁路——引导回来的意图要过动作模式门控，
  引导路径里的自写命令也要过首次确认，否则等于"第一次自写命令"可以不被确认就执行
- **任务抽屉改为从左侧整条拉出**（`AgentTaskDrawer`）：面板贴死上/下/左三条边，横向位移就是抽屉自身的物理隐喻
  ——"拉出"这个动作读得出来，靠淡入或上浮则读不出来。这是全站"入场只做自下而上"的**唯一例外**，
  其余页面与面板仍是纵向入场 + 淡入，不做缩放；`reduceMotion` 下位移归零
- **任务入口换成三横线**（`AppIcons.Menu`）：它打开的是从左侧拉出的抽屉，"三横线 = 拉出侧栏"是全局通行的约定，
  比一枚只能表示"看过什么"的时钟图标更能说明点下去会发生什么。**也不再挂任务数角标**——
  数量本身不构成要不要打开抽屉的理由，反而是顶栏里唯一会随任务增减而跳动的东西
- **抽屉打开时悬浮导航栏让位但净空照旧**（`MainActivity` / `AgentScreen.onDrawerOpenChange`）：
  抽屉盖住整页，别浮一层玻璃在它上面；但净空**不变**——净空一变，页面末项与输入区会在抽屉底下整段跳一下
- **底部玻璃板内层留白改为与上下同为 12dp**（`AgentGlassInnerPad`）：原先取 `Lg - Inset = 4dp`
  （想把面板退进去的 16dp 补回来），结果是「审核 AI」首字和输入框左边框一起贴在面板边线上——等于没有留白，
  而同一个容器的上下留白却是 12dp，横竖不对称。面板边框与内容之间得有自己的一圈呼吸感，不能靠外边距充数
- **窄屏下官网导航与正文对齐**：整条缩进后内层要少让一圈同等的宽，否则左右会比正文多出 `head-lift`

### 修复

- **悬浮窗一跑任务整屏发灰**：`blurBehindRadius` 糊的是"窗口背后的**整块屏幕**"，而不是卡片那 300dp 见方
  （框架文档写得很明确：*Blur behind blurs the whole screen behind the window*）；
  只有 `Window#setBackgroundBlurRadius` 才是"只糊窗口自身范围"，而悬浮窗是用 `WindowManager` 直接加 View 的，
  **没有 `Window` 对象**，拿不到那个 API。"只糊卡片"没有公开 API 可走，索性不糊——
  卡片退回一块 85% 不透明度的玄青 + 发丝描边。留着半透明是因为它仍有层次：透出来的是清晰原样，不是磨砂
- **AI 给的像素坐标在自由档下被端侧误当作"未知命令"挡掉**：友好命令表只是端侧词汇，**不是权限边界**；
  在自由档把它当白名单会把自由模式变回均衡模式。现在 `aiAuthored` 的命令端侧认不出来也原样交下去
- **`ShellCommands` 提示词示例的字段名写成了 `type`**：示例是 AI 抄写的模板，里面写着 `type` 等于教它违反铁律 1，
  已统一改为 `intent`
- **`a11y` / `shell` 的结构性错误被反复重试**：端点名、参数名写错，重跑同一串动作不会自己变好。
  已并入既有的"确定性错误不重试"名单（新增「未知无障碍端点」「缺少参数」「缺少 endpoint」三项）
- **无障碍感知类端点的回答回不到 AI 手里**：`can_screenshot` / `capture_tree` 这类端点本身就是"问一句话"，
  不回注等于 AI 问了却永远收不到答案，接下来只能靠猜。现在端点输出作为"无障碍端点输出"回注下一轮决策
- **输入框占位文案与真正输入的文字没落在同一条竖线上**（`AgentComposer`）：`contentAlignment` 由 `Center` 改为
  `CenterStart`——只借它把单行文字在 40dp 高度里竖直居中，水平方向必须贴左
- **抽屉末项压在系统手势条下面**（`AgentTaskDrawer`）：抽屉拉开时导航栏让位、面板一路铺到屏幕底，
  末项得自己让开系统手势区
- **占位文案与提交按钮在协助面板里无处可去**：自写命令确认只有「批准 / 拒绝」两个出口，
  新增 `allowFreeText` 开关隐藏自由输入与提交行（`AgentAssistSheet`），避免给用户一个输入完没地方去的框

### 移除

- **`FloatingWindowService.applyBlurBehind` 及其 `FLAG_BLUR_BEHIND`**：见上方"修复"
- **`FloatingUi.GLASS_BLUR`**：同一原因，配套常量一并删除，不留悬空配置

## [v0.1.502] — 2026-09-24

点击以前只有"算一次坐标 → 派发一次手势"一条路，而坐标是按**观察那一刻**的元素树算好的，中间隔着一次 AI 请求
（可能数秒）。这期间只要页面有布局变化——系统栏显隐、键盘弹出、列表滚动、启动动画——旧坐标就整体偏移，
表现就是"点到目标旁边的控件上"；点空之后也没有补救，只能把整条动作重跑一遍，而重跑用的还是同一个坐标。
本次把点击做成一条**分层流水线**，并把散落的定位逻辑收成一份。

### 新增

- **`ClickRunner`：点击流水线**（`engine/execution/ClickRunner.kt`）
  - **① 活节点直点**：在**此刻**的活节点树上重新找到控件（`ActionExecutor.clickNode`），直接 `performAction(ACTION_CLICK)`；
    控件本身不可点则**上溯最近的可点击祖先**（限 3 层，再往上就是整卡片/整屏级容器）。交给系统分发到控件本身，
    不受坐标漂移影响，也无需等待手势回调
  - **② 手势点控件最新位置**：①找不到节点/不可点（＝没交付）才降级；只有手势**根本没派发出去**
    （系统拒绝/被取消）才换快照坐标再试一次——派发成功即视为已交付，**不再重复按**
  - **③ 滚动一屏查找**：仅在"自始至终没定位到目标"时前置启用，而后在滚动后的页面上重查一次
  - 升级原则：**只在动作根本没交付时才升级**——同一个控件被按两遍，对发送/提交/删除就是重复副作用
- **`ActionExecutor.clickNode` / `scrollContainer`**：新增按 `NodeSelector`（viewId → 文字精确 → 文字包含 → 位置兜底）直点控件，
  以及向页面的**可滚动容器**下发 `ACTION_SCROLL_FORWARD/BACKWARD`。比坐标滑动精确——坐标滑动是从屏幕中心盲划，
  碰到横向轮播、悬浮按钮、非滚动区域时要么划不动、要么划错容器
- **`IntentResolver`：定位口径的唯一入口**（`engine/execution/IntentResolver.kt`）
  - `resolveAction`（元素索引 → target.method → 动作坐标）、`relocateOnLatest`（按当前页面重定位）、
    `matchInFresh`（跨快照找"同一个控件"）、`nodeSelectorOf`（推导活节点线索）
  - 引擎里原先的 `resolveTarget` / `resolvePoint` / `relocateOnLatestPage` **三份私有副本已删除**——
    同一套规则写两遍，改了一处漏一处，就会出现"引擎点的位置和别处算的不一样"

### 改进

- **点击成功判定分层**（`ClickRunner.judge`）：前台应用变了 → 目标控件自身状态（选中/值/文字/无障碍描述）变了 → 整页指纹变了。
  中间这层不能省：**在列表里勾选一项并不会改变整页指纹**，只看指纹会把成功误判成失败
- **确认改用短轮询**（200ms → 500ms 两次取样）：响应快的页面首取样就能确认，不必像原先那样无论快慢都干等；
  慢页面（动画/网络）也仍有观察窗
- **点击前先核对前台应用**：AI 思考的数秒里页面可能已经切走，不核对就会把点击落到别的应用上；
  执行前抓的那份快照同时充当"这一串点击有没有产生变化"的判定基准
- **重定位只在同一应用内**：包名变了说明页面已经切走，旧控件与旧坐标一并失效，该交给原本的失败/重规划逻辑处理，
  不能拿新页面上的同名控件硬点。重定位也不复用 `resolveAction`——它优先按 `elementIndex` 取元素，
  而索引是**遍历序号**，两次抓取之间并不稳定，拿旧索引到新树上取元素会取到完全不同的控件
- **点击类动作不再交给外层重跑**：`ClickRunner` 内部已把四种方式逐级穷尽过一遍，外层再重跑只是把同一串动作重复执行
- **任务卡片改为毛玻璃**（`FloatingUi.BRAND_GLASS` + `FloatingWindowService.applyBlurBehind`）：
  底色用 App 的品牌色「玄青」半透明化（85% 不透明度），真实磨砂交给窗口的 `blurBehindRadius`——
  由 SurfaceFlinger 把窗口背后的内容做高斯模糊。两者**必须配套**：只加半透明不加模糊，透出来的是清晰底图，
  文字对比度会随底图乱跳
  - 不做到更透是刻意的：模糊在 Android 12 以下、省电模式、系统"降低透明度"下都会失效，那时这块底就是唯一的文字背景，
    85% 的玄青仍能压住白字
  - 刻意不查 `WindowManager.isCrossWindowBlurEnabled()`：SDK 存根把它声明成了实例方法（`javap` 可见 `ACC_PUBLIC` 无 `ACC_STATIC`），
    Kotlin 里静态调用编译不过；而且查了也不改变行为——模糊被系统关掉时框架直接忽略这个半径
- **跑马灯跟随状态变色时改为单色实心**：只给一个阶段色就整块铺纯色，绝不走渐变。此前不足两色会被补成"首色+尾色"两段，
  一旦上游给空列表（配色串解析失败等）就退化成蓝→紫的横向渐变，跑马灯上凭空多出一层颜色
- **设置页跑马灯预览的字样式与 `MarqueeView` 一字不差**（14sp、常规字重、零字距、不设行高）：
  原先用的是 `typography.labelLarge`——14sp/Medium/0.1sp 字距、还带 20sp 行高，同一个「内边距」值下
  预览的胶囊比实际厚 4dp 左右、字也更粗
- **Tab 转场统一为「自下而上小幅升起 + 淡入」**（位移取屏高 1/12）：底部 Tab 是**平级目的地**，
  左右位移在移动端是"推入/推出"的语义，暗示两个页面有层级先后，方向本身就是假的；全项目不再引入水平位移
- **底部悬浮导航栏留出上下对称的呼吸间距**：条下方到系统手势区、条上方到页面末项用同一个值——
  只留一侧会出现"下面松、上面贴"的失衡，尤其 Agent 页输入区也浮在条上方
- **澄清候选答案改为竖排**（`AgentComposer`）：原先那排横向滚动胶囊会把每条说明压成一行省略号，
  且第三条起就滑出屏幕——"一共有哪些可选"这件事本身先看不见了，而这正是用户此刻唯一要判断的事。
  「✏️ 我想自己说」是一个**入口**而非答案，点它把焦点交给输入框，不当成选项提交
- **运行状态条是步数/相位/用时的唯一口径**：任务流里的实时行只负责"AI 此刻在说什么"，
  顶栏不再挂「运行中」胶囊（同一件事说三遍），完成摘要底行也只放成本与速度，步数交给顶部胶囊
- 工具链折叠行的 `key` 改锚**链首步**：链是边走边长的，用尾步号做 key 会让每来一步就换一个 key——
  LazyColumn 视作新项、展开状态被丢掉、入场动画重播
- 折叠区折叠行的主文案用"第 N 步 + 工具名"：步号是用户与轨道、与展开后步骤行对齐的唯一坐标
- 空态不再写"描述你想让 AI 替你做的事…"这类说明——输入框占位文案与顶栏副标题已经说过两遍，空态只负责给出口

### 修复

- **AI 给的像素坐标被当成比例放大数倍**：`IntentResolver` 里此前一律按比例相乘，一给像素坐标就会被放大再夹到屏幕边缘，
  表现出来就是"点哪儿都不对"；现在统一走 `parseCoordinate` 的比例/像素双口径
- **跑马灯渐变平铺导致凭空多出几段颜色**：`TileMode` 由 `REPEAT` 改为 `CLAMP`——`REPEAT` 是平铺语义，
  万一渐变宽度与面板当前宽度不同步会把整条色带再重复一遍；设置页预览用的是不平铺的横向渐变，这里必须同源
- **同一段文字同时挂在容器与内层控件上时点到容器中心**：候选排序改为"可点击的优先 → 面积最小的优先 →
  离快照位置最近的"，否则可能点到离用户看到的按钮很远的地方
- 关闭广告的阶段写 `ACTING` 而非 `ACTION`：后者会落到 `FloatingUi.phaseColor` 的兜底琥珀色上，
  跑马灯闪出一个设置页阶段清单里根本没列的颜色

### 移除

- **「任务执行期间隐藏系统状态栏」整条移除**（设置项 + `hideStatusBarForTask` / `restoreStatusBarAfterTask` + 悬浮窗联动）：
  跑马灯已移到底部，不再被状态栏压住；而这个特性要经 shell 通道改系统 `policy_control`，
  部分机型无效、还有把状态栏永久固化在隐藏态的风险。任务卡片的上边界不再跟随状态栏隐藏状态，始终退到状态栏下方

---

## [v0.1.473] — 2026-09-23

跑马灯以前挂在任务卡片里——卡片只有 300dp 宽、还能被用户随手拖走，于是色带跟着一起跑，文字稍长就滚不动。
这次把它拆成**独立窗口**浮在屏幕底边之上，长宽随文字自适应，并新增「跟随状态变色」。同时收拾了一批
「AI 看不见页面就空转」的问题：元素树读不到控件时视觉链路不再被总开关掐断，纯对话也不再走批准流程。

### 新增

- **底部跑马灯独立成窗**（`overlay/MarqueeView.kt` + `FloatingWindowService.showMarquee()`）
  - **长宽随内容自适应**：宽 = 文字宽 + 左右内边距（超出屏幕可用宽才封顶并开始滚动），高 = 文字高 + 上下内边距。
    窗口是 `WRAP_CONTENT`，视图量多少窗口就多大——改内边距只需让面板重新测量，不必再动窗口 `LayoutParams`
  - `FLAG_NOT_TOUCHABLE`：它只负责显示，绝不吃掉任何触摸
  - `fitInsetsTypes = 0`：清掉系统栏 inset 适配，底部偏移才是从物理屏底算起
  - 位置落在**导航栏之上再留净空**：面板压在系统手势条上会干扰上滑手势
  - 截图时一并隐藏、截完按原样恢复（它浮在屏幕底部，一定会被截进画面）
- **「跟随状态变色」开关**（默认开）：底色由当前阶段决定（观察 / 思考 / 执行 / 完成 / 出错，
  色值一律取自 `FloatingUi`，不在设置页另抄一套）；关掉则回落到用户自定义渐变
- **纯对话：`say` 意图 + `PlanPhase.Reply`**：模型判断这次不用碰手机时，直接用一句话回答，
  **不请求批准、不进入执行**，回答作为任务流里的一条消息呈现——"问一句你好不该走批准流程"
- **`GlassHeaderScaffold` 玻璃页眉骨架**：把「取样源 + 玻璃面必须是同层兄弟节点」这套约定收进骨架，
  正文整屏铺开当取样源，页眉是一块浮在正文之上的玻璃板

### 改进

- **元素树稀疏/为空时，视觉链路不再被总开关掐断**（`AgentEngine`）
  - 截图：元素树稀疏时**无条件截图**，不再被「截图总开关」「视觉总开关」卡住——这时元素树已不足以支撑决策，
    视觉是唯一的信息来源
  - `visionConfig(force = complexPage)`：同样理由，稀疏页必须拿到视觉配置，否则 AI 面对的是完全不可见的页面
  - 混合路由下复杂任务若**云端不可用则回落端侧 3B**，不能两条路都断
- **不再往上下文写「未识别到控件」**：那句话会被 AI 当成"页面上没有可操作控件"的事实，从而放弃尝试、编造动作或直接收尾；
  真实情况只是这一路视觉没结果，元素树与其它来源仍然有效
- **无障碍抓取重做**（`AgentAccessibilityService`）
  - 收录「自带文字的可见节点」：让 AI 能"读"页面（正文、列表项文字），并支持按文字定位
  - **可点击容器用后代文字补标签**：微信这类容器自身没有文字（文字挂在不可点击的子控件上），
    此前 AI 只拿到一堆无名方框，按 `by=text` 定位必然失败。标签最多 3 段 / 60 字 / 深度 3，落在 `contentDescription`，
    不污染 `text` 的真实值
  - **已在可交互容器内的普通子节点不再重复收录**：它的文字已经补到容器标签上，重复收录只会让 AI 在同一位置看到两个目标
  - 纯文字节点收录上限 80：正文页动辄上百条，全塞进决策上下文会明显推高成本
  - 新增 `TreeScan` 统计，元素树为空时把「访问 / 不可见 / 无标签 / 尺寸为0 / 容器内重复 / 纯文字收录」写进任务日志——
    否则只能看到"未检测到可交互元素"，分不清是系统不给节点还是被筛选条件挡掉
- **`device_query` 上限重定**：返回内容上限 1500 → 9000 字符；应用清单上限 300 条，且**截断时必须显式告知**
  （"仅列出前 N 个，剩下请用 filter 缩小范围"）——提示词里写着"目标应用未安装 → 澄清或 give_up"，
  清单一旦静默漏项，AI 就会把已装的应用判成"没装"
- **待批准计划面板搬进输入栏**（`AgentComposer`）：批准是"该我拍板了"的动作，和输入框一样属于底部操作区；
  摆进任务流会跟历史消息混在一起，用户得往上翻才找得到按钮。步骤清单限高内滚，不会把底部面板顶到半屏高
- **澄清提问与选项同样由输入栏承载**，任务流不再重复出一条
- **打字机必须吃全量文本**：先截断再打字会破坏"前缀单调性"，打字机会判定为"换了内容"而整段跳变（表现就是"分段蹦"）；
  限长改到渲染处，只贴尾部片段
- 折叠区的原始数据块按 Markdown 排版并保留原始换行——元素树此前被软换行连成一整段，反而比纯文本更难读
- 设置页跑马灯**等比预览**：胶囊圆角、内边距、贴底留白都与 `MarqueeView` 对齐——
  预览与实际一旦各写一套，用户看到的和拿到的就不是同一个东西；「跟随状态变色」预览列出五个阶段各自的底色
- 跑马灯内边距语义由「色带厚度」改为「上下内边距」（默认 26 → 8）；读取旧值时 `coerceIn(4, 28)`，避免旧存档的面板过厚
- 调试页：导出 / 清空 / 画框 / 能力状态收进内嵌菜单，页头只留一个入口；能力项齐全时提示条不占位
- 记忆图谱页：视角切换改为「自下而上的淡入」，补骨架屏加载

### 修复

- **跑马灯相位色失效**：`Paint` 里 shader 优先级高于 color，给文字设了渐变 shader 之后，传进来的颜色会被**无声覆盖**。
  底色改为直接用用户选的原色（文字固定白色），不再向白色柔化或按阶段混色——否则设置页的预览永远对不上实际
- **跑马灯该滚的不滚、不该滚的乱滚**：滚动判据由"view 宽度"改为"文字宽 vs 内容区宽"——
  面板宽度跟着文字走，用 view 宽度判断会让所有文本都算"放得下"，跑马灯就永远不滚了
- **玻璃整块不画（表现为完全透明）**：Haze 默认只画「zIndex 严格小于本层取样源」的区域，
  全局悬浮导航栏在内容层之外又套了一层取样源、把 zIndex=0 传给了本页所有后代；本页自己的取样源 zIndex 也是 0，
  于是过滤条件 `0 < 0` 为 false，本页取样区被全部滤掉 → `areas` 为空 → 直接跳过绘制。
  本页只取样同层兄弟源，不存在把自己画进取样层的自反馈，过滤没有意义，一律放行
- **任务卡片压住状态栏的时间与电量**：`getIdentifier("status_bar_height")` 在相当一部分 ROM / 高版本系统上取不到、返回 0，
  于是可拖动上边界变成 0；改为按 inset 取值，并**兜底宁可多留也不返回 0**
- **新问题的回答和上一轮旧气泡同屏**：新任务开始时清空上一轮的 `_sayEvents`——纯对话不会进 `run()`，靠它清就太晚

---

## [v0.1.445] — 2026-09-23

主模型以前被当成"只会读文字"的模型：带不带截图由「启用视觉理解」一个开关手工决定，用户既不知道自己在用的模型
到底看不看得懂图，也要把同一个 API 地址在"主模型 / 视觉模型 / 思考模型"三张卡里各填一遍。本次把模型配置
改成三层信息架构 —— **端点 → 模型库 → 职责分配**：地址和密钥只在端点填一次，模型从 `/models` 一键拉取入库，
能力（识图 / 纯文字 / 调用工具）由**真实请求探测**得出并挂成徽章；主模型只要被证实能识图，每步截图就直接交给它看。

### 新增

- **模型库与职责分配三层结构**（`core/ai/ModelCatalog.kt` + 设置页重写）
  - **端点**：API 地址 + Key，一个端点下可有多个模型；「获取模型」拉 `/models` 批量入库（网关常有上百条，
    只写库不做逐条探测），404 时给中文提示"该服务未提供模型列表接口，请手动填写模型名"
  - **模型库**：按端点分组 + 筛选框，每行显示能力徽章与「重新探测」
  - **职责分配**：主模型 / 视觉模型 / 思考模型三行只记"用哪个模型"，地址与 Key 由所属端点提供，
    点整行开内嵌弹层挑模型（可手填模型名），长按仍可拖动排序
- **能力探测 `AiClient.probeAbility`：只用真实请求，不做启发式**
  - 顺序 文本 → 识图 → 工具，文本这一步失败即整体失败（说明地址 / Key / 模型名有问题），
    避免把"模型名错"记成"该能力不支持"误导用户
  - 能力值三态：`true` 支持 / `false` 明确拒绝（HTTP 415 / 422）/ `null` **未测出**（400、5xx、200 但空正文）——
    `null` 与 `false` 在库里、徽章上、弹层里都分开显示（"识图 ?" ≠ "纯文字"），断网不会把模型标成不支持
  - **探测不复用重试链路**：单发请求 + 20s callTimeout。`executeWithRetry` 的 429 退避（2/4/8/16s）会把一次探测
    拖到分钟级，而且会把「`finish_reason=tool_calls` + `content=null`」误判成"AI 返回空内容"
  - 探测用的图片是一张 64×64 纯红小图：够模型解码，又不产生需要审核的内容
- **主模型可直接识图**：`hasVision && attachScreenshot && 有截图` 三者同时成立时，截图直接进主模型的消息
  （此前"启用视觉理解"只是让视觉模型去看图，主模型始终只拿文字描述）
- **新增开关「主模型识图时跳过视觉描述」**（默认开）：主模型能直接看图时，不再额外调用视觉模型生成文字描述，
  省一次调用与等待；关掉则两条都跑（视觉描述仍作为补充信息注入）
- **模型选择弹层 `ModelPickerDialog`**：内嵌 Dialog，列出模型库里带能力徽章的模型，支持筛选与手填模型名

### 改进

- **快捷预设从"只填输入框"改成"组合配置"**：套用预设时同时把该服务商建为端点、把主 / 视觉 / 思考三个模型写进模型库
- **老配置零丢失迁移**：`ModelCatalogCodec.migrateFromLegacy` 把旧的九个扁平字段按归一化 URL 分组还原成端点与模型
  （视觉 / 思考槽地址或 Key 为空时回退主槽，与引擎 `visionConfig` / `reasoningConfig` 取值一致）
  - 迁移是**纯函数、不写回**：DataStore 的 `map` 里不能写，且"读时写"会有竞态；用户把端点全删空时存的是 `"[]"`
    而非空串，所以不会复活
- **`ModelAbility` 里的 `note` 保留原文摘要**：未测出的那一项带上 `HTTP 状态码 + 返回正文片段`，
  用户能分辨到底是"服务端 500"还是"网关把字段吃了"
- **能力徽章反哺设置**：主槽同名模型被探测出 `vision = true` 时单向打开「启用视觉理解」（反向不自动关，尊重用户手改）
- **提示词同步**：`capabilitiesLang` 的视觉说明改为"本轮已附带屏幕截图，可直接看图判断元素位置、图标与图表含义"——
  入参也由 `hasVision` 换成"这一轮真的带了截图"，避免提示词说"你有图"而实际没发图
- **职责行显示端点归属**：`主模型 · open.bigmodel.cn/api/paas/v4`，改模型不必回头核对地址
- 编辑地址时同步推导端点 id 并把该端点的模型条目一起迁移，模型不会变成孤儿；保存前再兜一层归一化与去重

### 测试

- 新增 `ModelUrlTest`（`/models` 拼装的四种填法：裸 base、带 `/v1`、带 `/v1/models`、整条 `chat/completions`）
- 新增 `ModelProbeVerdictTest`（2xx/415/422/400/5xx 的判定，以及"400 + 不支持图片"不能被误判成模型名错）
- 新增 `ModelCatalogCodecTest`（编解码往返含 `null` 能力值、坏 JSON 退空表、老配置三种形态迁移）

---

## [v0.1.430] — 2026-09-23

内置浏览器此前只是转译层策略表里的一颗策略：AI 说 `browse_open`，端侧先把它当普通手机动作走一遍授权判定，
只读模式下还要过一张白名单。于是"没有无障碍/没有 Shizuku 就用不了浏览器""网页里点个链接也被当成操作手机拦下"
这类别扭一直存在——浏览器本来就不碰用户的手机，它的权限不该由操作手机的通道来定。本次把浏览器提成一条
**与转译层同级的独立通道**，并顺手把"清单里看得见的元素点不到""下拉框点不开"两个真实网页问题一起修掉。

### 新增

- **`BrowserChannel`：与 `IntentTranslator` 同级的浏览器通道**（`feature/browser/`）
  - **不依赖设备能力**：网页读写走注入的 DOM 脚本，不经无障碍 / Shizuku / 无线 ADB，也不进策略表，
    因此**只读模式下照常可用**——打开网页、抓正文、滚动、后退、以及网页里的普通点击与填表单一律放行
  - **只读护栏改为"按不可逆性放行"**：只有命中不可逆词表的支付 / 下单 / 删除 / 发送等才拒，
    并且是**执行前就拒**（执行层压根不会被调用），拒绝语明确写"不要重试同一动作"
  - 参数校验、护栏判定、措辞全在这一层，`BrowserExecutor` 作为执行缝（生产实现 `BridgeExecutor` 转调 `BrowserBridge`），
    于是这层判定可以纯 JVM 单测
- **`BrowserGuard`：不可逆词表唯一定义点**。中文 19 词 + 英文 13 词，Kotlin 判定、注入脚本的探针、提示词三处引用同一份
  - 刻意**不收**「确认 / 确定 / 提交 / 继续」这类泛词——搜索、翻页、登录、同意条款全带这些字，收进去只读模式就废了
  - `jsWords()` 把词表插值进 `browse_click` 脚本：只读模式下脚本对**真正被定位到的元素**再探一次词表，
    命中即拒绝且不派发点击（`by=id` 时 AI 给的文字未必等于元素真实文字，这是最后一道兜底）
- **`feature/browser/script/` 脚本四件套**：`BrowserJs`（选择器与共享 JS 助手）/ `ReadScript` / `InteractScripts` / `NavScripts`

### 改进

- **`browse_read` 返回"可操作元素清单"**：每行形如「N) [种类] 元素文字（提示：…）（当前=…，选项=a|b）」，
  清单里的文字就是下一步 `browse_click` / `browse_input` 的 target，AI 原样取用即可
  - 清单与点击**同源**：都由 `BrowserJs.HELPERS` 的 `labelOf` 取名、共用同一套选择器，
    彻底解决"只有 aria-label 的图标按钮清单里看得见、点下去却说找不到"
  - `labelOf` 取值顺序补上 `placeholder`（且排在 `value` 之前），否则输入框填过字后名字会越用越歪
- **下拉框在网页里可以真正选中**：WebView 里 `select.click()` 不弹原生下拉，改为按选项文字设 `selectedIndex`
  并派发 `input`/`change`；`select` 被 `browse_click` 命中时回一句"请改用 browse_input"引导而非静默失败
- **`browse_click` 候选扩面**：先按真控件选择器找，再退到"页面文字"那层宽网；精确匹配优先、包含匹配取最短文本
- **提示词重建网页浏览章节**：中英双语把「网页浏览」前移到「打开链接与文件」之前，新增"浏览器不受操作手机通道约束"
  铁律与只读语义说明；不可逆关键词表不再手写，改为 `${BrowserGuard.promptWords()}` 插值（改词表即提示词同步）
- **`UNBLANK` 同时清理 `form[target]`**：只清 `a[target]` 时表单提交仍会弹出新窗口，网页跑到看不见的地方
- `read` 的尾部预留从 400 提到 900 字符，保证正文变长时清单不被挤掉

### 修复

- **删除旧链路**：`IntentTranslator` 的 `BrowserStrategy` 与只读白名单、`IntentType.TO_ACTION` 里的 6 条映射、
  `ActionType.BROWSE`、`AgentAction.op` 字段全部移除——浏览器不再产生任何 `AgentAction`
- 引擎主循环与引导路径改为**先过浏览器通道再走转译**；引导路径不 `continue`，避免丢掉留档
- `DebugRecordsStore` / `TaskStore` 的旧存档里残留的 `"op"` 键由 `ignoreUnknownKeys` 忽略，不需要迁移

### 测试

- 新增 `BrowserGuardTest`（含"该放行的普通操作"反例清单，钉住只读模式可用性）、
  `BrowserChannelTest`（注入假执行器，断言"被拒时执行层一步未落地"）
- `HtmlToMarkdownTest` 同源断言扩充到选择器与护栏词表；`EngineRulesTest` 补 6 个浏览意图的中文步骤名断言

---

## [v0.1.415] — 2026-09-22

跑马灯色带明明铺到了屏幕物理顶，却一直被系统状态栏窗口压住，只从状态栏下缘露出一条窄边。本次在任务执行期间
临时隐藏状态栏让色带真正贴顶，顺带修掉一个一直存在的光标坐标偏差。

### 新增

- **任务执行期间隐藏系统状态栏**（设置 → 视觉，默认开启）：任务开始时经真实 shell 通道
  （无线 ADB / Shizuku / Termux）执行 `settings put global policy_control immersive.status=*`，任务结束自动恢复
  - **先记原值再改**：用户或系统可能本来就设过 `policy_control`，原值非空则原样写回，为空才 `delete`；
    读到 `immersive*` 前缀（上一次任务被系统杀掉留下的残留）时按"原本没有"处理，避免把状态栏永久固化在隐藏态
  - **收尾放进 `NonCancellable`**：用户点停止会取消协程，普通 suspend 调用直接抛 `CancellationException` 不执行，
    状态栏就会一直隐藏着——恢复动作必须落在不可取消的上下文里
  - 纯观感增强，**任何失败都只是"没隐藏"**：无 shell 通道或机型不支持时只记一条日志，不影响任务本身

### 改进

- **`AgentEngine.execShellViaChannel()`：端侧自发的 shell 不污染 AI 上下文**
  与 `runRealShell` 共用同一套通道选择（AUTO：无线 ADB → Shizuku → Termux），但产物**不写进「上一步 shell 输出」**
  ——隐藏/恢复状态栏这类命令的返回值对 AI 没有意义，不该占用决策预算
- **`FloatingWindowService`：状态栏隐藏时跑马灯文字安全区归零**。色带背景本来就铺到物理顶、窗口位置也不用动，
  唯一要跟着变的是文字安全区；否则文字还留在原状态栏位置，会整段掉到色带之外
  - 隐藏状态放在 companion 静态标志上：任务可能早于悬浮窗服务启动，实例晚一步起来时仍要按"已隐藏"布局

### 修复

- **点击光标整体偏低约一个状态栏高度**：`CursorOverlayService` 的窗口没有清 `fitInsetsTypes`，
  API 30+ 默认 `systemBars()` 会把窗口内容整体推到状态栏下方——画在 `(x, y)` 的像素实际落在物理屏
  `y + 状态栏高度` 处，与 `dispatchGesture` / Shizuku `input tap` 使用的物理屏幕坐标对不上。
  清空后坐标系才从物理屏顶开始（与 `FloatingWindowService` 同源修复）

---

## [v0.1.413] — 2026-09-21

"用手机文档软件打开这个 ppt""用浏览器打开这个网址"这类请求此前走不通：本地 `file://` 交给别的应用会被系统直接抛
`FileUriExposedException`，只给 `content://` 不给类型时文档软件又不在候选里；而"打开浏览器"到底该开内置的还是系统的、
该开应用还是该开网页，提示词里也没有分界。本次补齐端侧能力并把分工写进提示词。

### 新增

- **`device/a11y/OpenTarget.kt`：打开目标的归一化与类型推断**（纯函数，直接单测）
  - 本地路径 → 系统「外部存储文档提供者」`content://`（`/sdcard/Download/x.ppt`、`/storage/emulated/0/…`、
    `/storage/<外置卡卷>/…`、`file:///…` 四条路径统一映射）；`:` 与 `/` 按提供者要求百分号转义
    - 由此**不需要任何存储权限、也不需要自建 FileProvider**，配合 `FLAG_GRANT_READ_URI_PERMISSION` 临时授权即可
  - 按扩展名推断 MIME（文档/图片/音视频/压缩包等 40 种）：只给 `content://` 不给类型时，文档类应用不会进候选，
    表现为"没有应用可打开"；认不出的扩展名返回 null 交系统自判，**绝不硬编一个错的类型**
- **`ActionExecutor.openUri(uri, pkg)` 支持指定应用 + 优先系统应用**：未指定包名时用 `queryIntentActivities`
  挑 `FLAG_SYSTEM` 的应用处理，查不到才交回系统默认/选择器；无应用可处理时回报可执行的中文原因
- **`AgentEngine` OPEN 分支**：`uri` 非空时 `app` 字段语义 = "用哪个应用打开"（应用名/包名，解析不出则交回系统默认）

### 改进

- **`AppNameResolver` 候选优先系统自带应用**：AI 说"打开浏览器"这类**泛指类目**时，机器上常同时装着系统浏览器与
  第三方浏览器，按安装顺序取第一个结果随机；现在精确/模糊匹配命中多个候选时先挑系统应用
  （`pickPreferredPackage` 抽成纯函数以便单测），"微信"这类无系统版的第三方应用不受影响
- **提示词中英双语重构「打开」的分工**（意图表 `open`/`open_app` 行、新增"打开链接与文件"段、网页浏览段、
  铁律 2 四分类、国产应用速查、规划器与 `situationalExtras` 两段）：
  - ① 需要读/操作网页内容 → `browse_open` + `browse_*`，独占；② 只是把网址给用户看、或用户点名"用浏览器打开"
    → `open` + `uri` 交系统浏览器；③ App 内页/系统页/公开 scheme → `open` 深链直达；④ 本地文件 → `open` + 文件路径
  - **明确禁止用 `open_app` 打开"浏览器"来上网**（打开应用 ≠ 打开网页，只会白白多两步）——该禁令写进铁律 2 与网页浏览边界
  - 泛指类目（文档、相册、邮件…）直接写类目名，端侧自动挑系统应用；只有用户点名第三方应用才写它的名字
- **`SkillCatalog`**：`skill_open_deeplink` 由"深链直达"改为"打开链接/文件"（描述覆盖交系统应用打开），
  `skill_open_app` 描述补"同名多个时优先系统自带"

### 修复

- **"用文档软件打开这个 ppt"被当成"写一份 ppt"**：`docHit` 关键词含 `ppt`/`文档`，会注入 `write_doc` 模板盖过 `open`；
  新增 `openTargetHit`（扩展名/本地路径/"用文档打开"等）并在命中时压掉 `docHit`

### 测试

- 新增 `OpenTargetTest`（17 例：本地判定 / 四种路径归一化 / 非本地原样放行 / MIME 推断与认不出返回 null）
- 新增 `AppNameResolverPickTest`（4 例：优先系统、无系统取第一、多系统取第一、空列表返回 null）
- 真实提示词回归新增 D10（给用户看的网址走 `open` 而非 `browse_open`）、D11（打开本地文件走 `open` 而非 `write_doc`）、
  D12（泛指类目开应用走 `open_app`）——CN 18/18、EN 16/16 全绿

---

## [v0.1.406] — 2026-09-21

AI 读网页此前只拿到 `innerText` 压平后的一坨文字——标题层级、列表、表格、代码块全丢，链接也只剩文字。
本次**自研**一套 HTML → Markdown 转换器（不引入 jsoup 等任何第三方解析库），让 AI 拿到结构化正文。

### 新增

- **`core/text/HtmlToMarkdown.kt`：四段流水线解析器**（预清洗 → 手写标签扫描 → 显式栈式状态机 → Markdown 序列化）
  - **全程无递归**：畸形页面、千层 `div` 都不会栈溢出；标签扫描无回溯正则，一次线性扫描
  - 四重兜底：输入切片 150 万字符、嵌套深度 256、生成上限 8000、块边界截断到 AI 可见预算 4000
  - `takeBlocks` 是唯一截断实现点：只切块边界，绝不切出半个 `[文字](网址` 或未配平的 `[`
  - `isHtml` 强/弱信号双重嗅探：`dumpsys` 的 XML、`uiautomator dump`、接口 JSON 里内嵌的 HTML 字符串都不会被误转
  - 覆盖标题层级 / 段落 / 有序无序与嵌套列表 / 引用 / 围栏代码块（含语言名、正文含围栏时自动加长）/ GFM 管道表 / 图片 / 命名实体与数字实体解码
- **两侧规则同源**：浏览器侧脚本 `BrowserScripts.READ` 的丢弃表 / 块级表 / 自闭合表 / 行内标记表 / 隐藏类名表 / 转义表 / 围栏字面量，
  全部由 Kotlin 常量**插值生成**。改规则只会改一处，两侧不可能漂移（单测直接从脚本源码里比对这七张表）

### 改进

- **`browse_read` 正文改为 Markdown**：链接**内联**在正文里（`[文字](网址)`），因此取消原来的独立链接清单；
  回注给 AI 的预算 1200 → **4000** 字符，表格按 GFM 管道表输出（跨列/跨行按设计忽略，已在提示词中声明）
- **`fetch` 兜底转换**：shell 输出统一 1200 → **4000** 字符；返回 HTML 时自动转 Markdown 再回传，
  修正了"先按 1200 截断、再转换"导致的**停在 `<head>` 中段**的缺陷——现在先嗅探转换、后截断
- **提示词中英同步**：意图表、网页浏览块、规划器意图清单、`situationalExtras` 的 browse/fetch 两段全部对齐；
  fetch 的边界收窄为"目标是纯文本接口时用它"，与"普通网址必须 `browse_open`"不再冲突
- 脏数据防御：`<div hidden>` / `class="sr-only"` / `aria-hidden="true"` / `style="display:none"` 整棵子树丢弃；
  单元格内被块化的文本（`<td><p>x</p></td>`）留在表格内，不再甩到表格外

### 修复

- 行内标记前的空格被吞（`你好 <strong>加粗</strong>` 输出成 `你好**加粗**`，两个词被粘成一个）
- 嵌套列表缩进丢失（隐式闭合时误把内层 `<ul>` 一起弹掉）
- `<title>` 永远取不到（`<head>` 整棵被丢弃前未抽取元信息）

### 测试

- `HtmlToMarkdownTest` **21 条**全绿（嗅探 / 块级结构 / 代码块 / 链接图片 / 表格 / 噪声与实体 / 截断 / 两侧同源）
- 真实提示词回归 **28 条**全绿（CN 15 + EN 13），含新增 D08（从内联链接文字取 `browse_click` 目标）、
  D09（网页正文整理成文档走 `write_doc`）

## [v0.1.386] — 2026-09-21

调试面板重做：六个平级页签收敛为「执行流 / 指标 / 对话 / 日志」四个，历史、步骤、时间线三个面板合并进新的执行流视图。

### 改进

- **执行流**：决策层的 `StepTrace` 与执行层的 `StepRecord` 按 `(taskId, step)` 合并成一条时间轴，按执行倒序排列，每条带 tokens、耗时、未确认步、低置信度步与是否 `task_done`。此前「想了什么」和「做了什么」分在两个页签，同一步对不上，执行记录也归不到具体任务
- **页签栏**：六个等分页签在窄屏或大字号下会把「时间线」这类三字标签挤断，改为最小宽度 + 可横向滚动的胶囊组，选中态跟随底部导航
- **能力条**：五项能力齐全时不再常驻显示，只在有缺失时给出可展开提示，完整状态移入页头菜单

## [v0.1.335] — 2026-09-20

AI 此前是在"真空"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住
上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。
本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、
**会话承接**（上一轮任务的目标与结论）。

### 新增

- **环境上下文默认注入**（`AgentPrompts.environment`）：每次规划与每步决策都带上端侧实时采集的事实——
  当前时间（`yyyy-MM-dd 周几 HH:mm`）、前台应用（`应用名(包名)`）、网络状态、电量（含是否充电）、
  已安装可启动应用数；缺项整行省略，不出现空标签
- **`device_query` 技能：本机信息按需查询**（`IntentType.DEVICE_QUERY` → `ActionType.DEVICE_QUERY`）
  - 参数 `kind = apps|time|battery|network|storage|all`，`filter` 仅 `kind=apps` 时生效（如「相机」）
  - 纯本地读取、不触碰设备：只读模式下同样放行（与 remember/wait 同列）
  - 查询结果作为「上一步结果」回注下一轮决策（截断 1500 字符），AI 拿到事实再决定下一步
  - `kind` 非法直接失败并列出可选值，不会拿着无效参数空转
  - **应用清单不给全量**：环境上下文只给个数，要清单必须走 device_query，
    避免每步都往提示词里塞一长串包名
- **多轮对话承接**（`SessionContext` + `AgentPrompts.sessionContext`）
  - 判定追问：含「接着/刚才/这个/改成/换成…」等强指代词直接认定；弱承接词「再」只在短句（≤12 字）里认定，
    避免把「打开微信」这类自带完整目标的短指令误判为追问
  - 注入最近 3 条已完成任务（目标 + 状态 + 结论，按时间倒序，进行中的不取——那条正是当前任务自己）
  - 是追问时明确写「必须以『上一轮任务』为目标主体」；不是追问时提示按相关性参考、无关就独立执行
- **任务结论落库**（`TaskMemoryEntry.conclusion`）：任务成功收尾时把完成摘要写进结论，
  作为下一轮承接的"上次结果"；结论为空则回退取最后一条完成方法
- **官网与宣传片**
  - `website/` 独立站点（Vue 3 + Vite），含首页/更新日志/文档/下载/FAQ 公共区，以及后台管理区
  - `promo/index.html` 单页宣传片，40 幕 × 7 秒结构，展示 Agent 的核心流程
  - GitHub Pages 自动部署（`.github/workflows/site-pages.yml`）

### 优化

- **性能**：已安装应用数每任务只查一次 `PackageManager`；会话承接块每任务只构建一次，
  随任务开始、规划、执行入口三处作废缓存，避免串轮
- **提示词双语同步**：中英两版系统提示词都补上 device_query 意图表行、独占路由规则
  （需要本机事实用 device_query，不要翻设置页）与决策阶段的「本机信息提醒」

### 测试

- 新增 `SessionContextTest`（4 例）：强指代词追问、弱词「再」仅在短句成立、自带完整目标不误判、空白输入
- 新增 `AgentPromptsContextTest`（6 例）：中/英环境上下文内容、缺项不渲染空标签、无历史不注入承接、
  追问强调承接、非追问提示独立
- `SkillCompatTest` 增 2 例：按技能名调用回填 kind/filter、标准意图名原样放行
- `IntentTranslatorStrategyTest` 增 4 例：转译字段、缺 kind 默认 all、非法 kind 失败、只读模式放行

---

## [v0.1.334] — 2026-09-20

本轮只做加固、不改产品行为：把四类会让任务「卡死」或「状态残留」的缺陷堵掉——
异常穿透、协作事件丢失、队列竞态、主循环单点故障。任务该怎么做还是怎么做，
区别在于出问题时它降级、重试、请求用户介入，而不是无声停摆。

### 修复

- **异常不再穿透整个任务**（`AgentEngine.run`）
  - `runInner` 里的未捕获异常此前会顺着协程传到 `processQueue`，把队列 worker 一起带走：
    界面永久停在「运行中」，之后排队的任务也不再执行。现由 `run()` 统一收敛，
    并按 taskId 兜底复位运行状态
  - `approvePlan()` 原先用 `runCatching{ run() }.onFailure{}` 包住任务启动，会把
    `CancellationException` 一并吞掉 —— 「停止」按钮再也打不断已批准的计划，
    而且「用户停止」还会被误报成「执行异常」
- **兜底复位加归属守卫**（`EngineRules.shouldFallbackReset`）
  - `stop()` 取消协程后收尾是异步跑的：用户立刻发起新任务时，旧任务的收尾会把新任务的
    `agentRunning` 置 false、状态打回空闲，还会关掉新任务的悬浮窗。现按 taskId + 终态双重守卫
- **多任务队列改原子操作**（`PendingTaskQueue`）
  - 原先是对 `MutableStateFlow` 做 `_taskQueue.value + task` 的非原子「读—改—写」，
    并发提交时后写的会覆盖先写的，任务被静默吞掉；worker 启动与退出之间还存在
    「入队方既看不到活着的 worker、也看不到非空队列」的窗口，任务就此漏跑
  - 现由独立锁保证原子性，退出时以 `job === self` 只清自己的引用，
    避免误清入队方刚启动的新 worker 导致双 worker 重复执行同一任务
- **用户协作事件不再丢失**（AgentEngine 协作信箱）
  - 「敏感页保护 / 动作连续失败」的原因此前走 `MutableSharedFlow.tryEmit`，
    无订阅者时值被静默丢弃，界面上只能看到一个没有任何说明的协作面板。
    现改为 `Channel(CONFLATED)` 单槽信箱，用户抢在订阅之前输入的内容也能被缓冲住
  - 原因同步写入 `agentState.message`，界面才有东西可显示；
    非等待状态下的误触投递由 `_needsUser` 门控拦下，避免残留值让下一次等待被立刻满足而跳过等待

### 优化

- **主循环高风险调用点单点隔离**：`observe()` 读元素树、截图、端侧决策、云端决策、
  技能归一化、意图转译、动作执行、步骤留档全部包上异常兜底。无障碍服务被系统回收、
  截图权限被回收、Shizuku/Termux 通道断开时，只降级当前这一步
  （走既有的「连续 3 次失败 → 请求用户介入」链路），不再终止整个任务
- **决策链路连续异常护栏**：连续 5 次决策抛异常（如 API 地址错误、网络完全不可达）时
  收尾并提示「AI 决策链路持续异常，已停止任务」，不再无限空转；
  `WATCHDOG` 超时保持既有「等待后重试」语义不变

### 测试

- 新增 `PendingTaskQueueTest`（5 例）：FIFO 顺序、空队列取出返回 null、
  并发入队不丢任务、边入队边取出不重复不丢任务
- `EngineRulesTest` 补 `shouldFallbackReset` 4 例：归属一致且非终态允许复位、
  `DONE` 终态不复位、taskId 不一致不复位、无归属 id 只靠终态守卫

---

## [v0.1.333] — 2026-09-20

让 API 地址不再被「公网 HTTPS」绑架：自建推理服务（Ollama、LM Studio、one-api 等）多跑在内网，
地址常是 `192.168.1.5:8000/v1` 或 `localhost:11434/v1`。此前这类配置走不通有两条原因——
系统的明文流量策略会直接拦下 http:// 请求，漏写协议时 OkHttp 也会因地址不完整而抛异常。

### 新增

- **明文 HTTP 放行**（`AndroidManifest.xml`）：`android:usesCleartextTraffic="true"`。
  targetSdk 28 起系统默认禁止明文流量，不放开则 http:// 的 API 地址一律失败于
  "CLEARTEXT communication to xxx not permitted by network security policy"
- **地址自动补协议**（`AiClient.normalizeBaseUrl`）：未写协议时按主机推断——localhost、
  私有网段（127/10/172.16-31/192.168）、单段主机名（nas、myserver）与 .local/.lan/.internal
  等内网后缀补 `http://`，公网域名补 `https://`；已写 `http://` 的地址原样保留，不会被改写成 https
- 设置页「API 地址」下补一行说明：支持 http://、内网地址与 localhost，不写协议时自动补全

### 测试

- 新增 `ApiEndpointNormalizeTest`（5 例）：协议保留、本机/私有网段/内网后缀补 http、
  公网域名补 https、空串处理

---

## [v0.1.332] — 2026-09-20

给悬浮窗做减法：任务执行时它以小窗形态长时间贴在屏幕上，此前却把 AI 的发送/返回/审核三段
内容全摊在体内，窗口被撑成一块"屏幕补丁"；玻璃背景又叠了八层光学效果（菲涅尔四边反射、
动态光斑、棱镜虹彩色散…），热闹得不像系统组件；再加上窗口可被拖出屏幕、拖丢后任务还在跑
却看不见状态。本次从信息密度、视觉、交互、性能四方面收敛。

### 优化

- **AI 详情默认折叠**（`FloatingWindowService`）
  - 详情区（发送给 AI / AI 返回 / 审核结论）高 112dp，此前每来一段流式文本就自动弹出，
    窗口在任务执行中反复变高变大 —— 正是遮挡屏幕的主要来源
  - 现默认只占「跑马灯 + 头部 + 状态行」三行；内容照常在后台累积，点头部或头部新增的
    「详情/收起」按钮即可展开查看，展开状态在任务内保持、任务结束复位
  - 一并去掉详情区自带的「AI 徽章 + 思考中」标题行：顶部阶段徽章已经说明了当前处于思考中，
    再叠一行只是重复信息、白占高度；分栏标题 SENT/RESPONSE/REVIEW 改为中文
- **玻璃背景降为三层**（`LiquidGlassDrawable`）
  - 去掉菲涅尔四边反射、动态光斑、底部阴影渐变、棱镜虹彩色散，只留半透明白底 +
    顶部折射高光 + 一道左上斜向柔光，外侧由发丝描边与细边框收边；虹彩是最显"脏"的一层，
    白玻璃上叠三原色渐变会让整体发浑
  - 投影高度 18dp → 12dp（M3 柔和浮起，不需要夸张阴影来证明"浮起"）
- **交互：边缘吸附与越界回收**（`FloatingWindowService.settlePosition`）
  - 窗口用 `FLAG_LAYOUT_NO_LIMITS`，本可被拖到屏幕外；松手时若贴近左右边缘则吸附贴边
    （留 8dp 边距），纵向越界则回收进屏幕 —— 不再出现"窗口拖丢了、任务还在跑"的情况
  - 惯性滑行的活动范围同样收进屏内，并保留跑马灯贴顶的负 y 上限
- **交互：轻点头部展开详情**：按触摸阈值区分"轻点"与"拖动"（此前手指的微小抖动也算位移，
  拖动与点击无法共存）；拖动时不再跟随手指做光斑重绘

### 性能

- **Shader 缓存**（`LiquidGlassDrawable`）：光学层改为仅在尺寸变化时重建，绘制期间零分配。
  悬浮窗在任务执行期间每帧重绘，此前每帧要新建十来个 `LinearGradient`/`RadialGradient`
- **跑马灯不可见时停帧**（`MarqueeView`）：截图隐藏、任务结束隐藏时不再每帧请求重绘
  （GONE 的视图仍会把 Choreographer 帧回调与遍历持续拉起来），恢复可见后从当前相位续滚
- **通知节流**（`FloatingWindowService`）：AI 思考同步到通知栏限制为最小间隔 700ms ——
  流式增量每秒数次，逐条 `notify` 是跨进程调用，此前是白烧的固定开销

### 变更

- 悬浮窗尺寸与位置常量（宽度、投影、贴边留白、吸附阈值、详情区高度）集中到
  `FloatingUi` 令牌，不再散落在窗口创建、惯性滑行、吸附各处
- 面板入场动画改为「淡入 + 自下而上 12dp 位移」，去掉缩放（缩放与位移叠加会让视觉重心漂移，
  且入场方向应统一为自下而上）

---

## [v0.1.331] — 2026-09-20

让点击光标只属于「任务执行中」：此前任务成功跑完后从不撤下光标，它就停在最后一次点击的位置
一直挂在屏幕上；服务还声明了 `START_STICKY`，被杀后系统用空 intent 重建时也会凭空挂出一个光标。

### 修复

- **任务正常完成不撤光标**（`AgentEngine.run`）
  - 只在该路径补 `hide()` 是治标：主循环有多个 `return` / 异常出口，逐个补容易漏
  - 现统一放在 `run()` 的 `finally` 里，成功 / 失败 / 用户停止 / 异常一律撤下；
    并按 `taskId` 判定归属——停止协程后 `finally` 是异步跑的，用户若立刻发起新任务，
    旧任务的收尾不能把新任务刚挂上的光标一并撤掉
- **服务被重建后凭空出现光标**（`CursorOverlayService`）
  - `START_STICKY` 让系统在服务被杀后用 `null` intent 重建它，重建即走「显示」分支挂出光标，
    而此时并没有任何任务在执行；现改为 `START_NOT_STICKY`
  - 新增「任务执行中」这一唯一可见性凭据（`wanted`，由 `show` / `hide` 驱动）：
    `show` 的启动请求与 `hide` 抢跑（请求姗姗来迟）时，`onStartCommand` 直接 `stopSelf`，
    不再补挂光标
- **撤下光标改走进程内直连**：原来靠 `startService(ACTION_HIDE)` 通知服务自撤，
  而任务大多在 App 处于后台时结束，后台启动服务可能被系统拒绝（异常被 `runCatching` 吞掉），
  光标就留在屏幕上；服务实例本就在同一进程，现直接在主线程撤下视图并停掉服务，
  撤下前再确认一次 `wanted`，避免误撤新任务刚挂上的光标

---

## [v0.1.330] — 2026-09-20

修「悬浮窗在的时候整个手机都点不动」：底部选项卡窗口被内容撑成整屏高，成了一层看不见的全屏
可触摸层，屏幕上任何点击都落在它身上；而顶部跑马灯是另一个窗口，照旧滚动 —— 于是看上去
只有跑马灯在动、手机点哪儿都没反应。

### 修复

- **选项卡窗口被撑成整屏高**
  - 根因：交互面板的内容滚动区用「高度 0 + weight 1」占满剩余空间，而选项卡窗口是
    `WRAP_CONTENT`；LinearLayout 在 `AT_MOST` 下会把「沿高度的剩余空间」——也就是整块屏幕——
    全分给权重子视图，面板因此被撑到整屏高。窗口本身又是 `MATCH_PARENT` 宽、贴底，
    合起来就是一张盖住全屏的透明层
  - 现改为固定限高的滚动区：面板始终是一张内容大小的卡片，短文案不留大片空白，超长文案在卡内滚动
- **面板隐藏时窗口仍占一条透明可触摸区域**
  - 容器的左右/底边留白挪到**面板自己的外边距**上：容器是窗口根视图，它的内边距即使面板
    `GONE` 也照样把窗口撑出一段高度，零内容却可触摸，会持续吃掉屏幕底部的操作
  - `hideSheet()` 收起动画结束后同步把面板置 `GONE`：窗口根即使自身 `GONE` 仍会被测量，
    面板留在 `VISIBLE` 会继续撑出同样高度的可触摸窗口
- **四类交互的用户操作此前全都点不到**
  - 交互面板早已不挂在顶部窗口（改由底部选项卡承载），但 `showInteraction` 对
    `approve` / `savetemplate` 仍走"顶部渲染"分支：只把面板设成 `VISIBLE`、不调 `showSheet()`，
    父容器默认 `GONE`，面板永远不可见 —— 需要批准计划时用户点什么都没反应，任务一直挂着
  - 反过来 `clarify` / `guide` 只调 `showSheet()` 而不把面板设成 `VISIBLE`，滑出来的是空容器
  - 现统一为「面板置 `VISIBLE` + 重建按钮 + `showSheet()`」，批准 / 保存模板 / 澄清 / 指导
    都能正常看到并点到；顺带去掉了面板上叠加的缩放动画，入场方向统一为自下而上

---

## [v0.1.329] — 2026-09-20

继续修 Agent 页点输入框后「输入区浮得比键盘顶更高、中间空出一块」：v0.1.325 让导航栏给键盘
让位的方向是对的，但判定本身读错了位置，加上 Manifest 没有声明输入法模式，两条路各自多顶了一截。

### 修复

- **键盘判定改在内容层读**：`WindowInsets.ime` 原先写在 `Scaffold` 的 `bottomBar` lambda 里，
  而 bottomBar 是 `SubcomposeLayout` 的子组合，在其中读 insets 不保证随键盘弹出而重组 ——
  读到旧值 `false` 时导航栏不让位，`Scaffold` 的底部内边距仍带着整条导航栏高度，
  输入区又整段 `imePadding()` 避让 IME，两者相加就把输入区顶到键盘顶之上一条导航栏的高度。
  现上移到 `ActivityContent` 组合体内读一次，导航栏与输入区必定同一帧让位
  - 顺带核实：Material3 1.3.1 的 `Scaffold` **不会**消费自己算出的 innerPadding
    （`ScaffoldKt` 各内部类里没有 `consumeWindowInsets` 调用），
    所以"脚手架内边距 + 输入区 imePadding"确实是各自独立相加的，两处都必须为 0
- **显式声明 `android:windowSoftInputMode="adjustResize"`**：此前 Manifest 完全没有声明，
  系统按 `adjustUnspecified` 自行判定，Compose 根视图不是 ScrollView 时可能判成 `adjustPan` ——
  系统把整个窗口内容往上平移，与页面内的 `imePadding()` 再叠加一次同样的抬升。
  edge-to-edge（`enableEdgeToEdge` + targetSdk 35）下 `adjustResize` 不会压缩窗口，
  IME 只通过 `WindowInsets.ime` 上报，输入区位置完全由页面内的 `imePadding()` 决定

---

## [v0.1.328] — 2026-09-20

给长线任务一个不会丢的「任务记忆」：此前 AI 只知道最近 3 步做过什么，且这 3 条纯内存、轮转即丢，
而决策历史还会被压缩到最近几轮 —— 任务跑到后半程，最初的目标与用户中途的交待就看不见了。

### 新增

- **任务记忆（`TaskMemoryEntry` + DataStore key `task_memory`）**：一次任务执行期间持续维护
  「目标 / 用户要求 / 已验证做法」，随每一步落库，任务中断后记忆页仍可查看
  - 目标 = 任务原文；用户要求 = 任务原文 + 执行中用户在悬浮窗「指导输入」里说的话
  - 每步验证生效的动作摘要记为「已验证有效的做法」，上限 10 条（超量丢最旧）
  - 用户要求上限 8 条；只跳过「去掉空白标点后完全相同」的重复项 —— 用户中途的补充指令
    往往与任务原文措辞相近（"帮我在美团点一份黄焖鸡" → "帮我再点一份黄焖鸡"，bigram 相似度恰好 0.5），
    用模糊去重会被静默丢掉，等于没记住用户需求，故这里不用 `AiMemoryDedupe.isSame`
- **每轮决策注入完整任务记忆**（`AgentEngine.taskMemoryText`）：固定注入「目标 + 用户要求」，
  已完成步数 > 0 时再附「执行进度 + 阶段 X/Y + 已验证有效的做法」，让 AI 不会重复执行已完成步骤
- **记忆页新增「任务记忆」分区**（`TaskMemoryList`）：按任务卡片展示任务名、中文状态徽标
  （进行中 / 已完成 / 未完成 / 已中断）、目标、用户要求、完成方法，支持单条删除与整区清空；
  统计概览扩为三列（任务记忆 / 异常经验 / 用户画像）

### 变更

- **任务记忆取代易失的进度摘要**：删除 `progressNotes` / `MAX_PROGRESS_NOTES`（最近 3 条内存队列）与
  `progressSummaryText()`，改由任务记忆承担「已完成什么」的记忆职责
- **任何退出路径都不会把状态停在「进行中」**：成功 → 已完成、步数耗尽 / 决策为空 / 技能连续被拒 /
  用户拒绝协助 → 未完成、用户主动停止 → 已中断；`run()` 外层加 `try/finally` 兜底，防漏改
  - 兜底收尾按 `taskId` 判定归属：`stop()` 取消协程后 finally 是异步执行的，用户若立刻发起新任务，
    旧任务的 finally 会在新任务已经开始之后才跑到，不加判断会把新任务的记忆误标为「未完成」，
    且新任务随后成功时会被终态守卫挡住而永远写不进去
- **乱序落库保护**（`upsertTaskMemory`）：引擎是「内存快照 + fire-and-forget 落库」，旧快照可能后到，
  传入条目的 `updatedAt` 早于库中记录时直接丢弃，不覆盖新进度；库容量 30 条，超量优先淘汰已结束的任务

### 测试

- 新增 `TaskMemoryEntryTest`：用户要求追加 / 去重 / 超 8 条裁剪 / 空白忽略、完成方法去重与超 10 条裁剪、
  `withStatus` 更新状态与步数、四种状态的文案；含一条回归用例锁定「与任务原文措辞相近的追加指令
  必须保留」，防模糊去重再次吃掉用户指令

---

## [v0.1.327] — 2026-09-19

让技能声明的参数真正驱动执行：此前内置技能只是「一个名字」，技能页展示的参数（app / target /
direction / key / wait_ms …）在运行时被静默丢弃，等于空壳。

### 修复

- **技能参数被丢弃**（`SkillCompat.normalize`）
  - 归一化只重写了意图名，完全没读 `args` → `{"intent":"skill_swipe","args":{"direction":"up"}}`
    归一化出的意图里 `direction` 仍是 `null`，转译后滑不动；`skill_press` 丢 `key` 后变成空按键
  - 现新增 `applyArgs()`：把 `args` 里**真正给出**的字段回填到意图字段（app / target / uri / page /
    text / summary / reason / direction / key / wait_ms / duration_ms），AI 直接写在扁平字段上的值不受影响
  - 两种写法从此等价：`{"intent":"skill_swipe","args":{"direction":"up"}}` 与 `{"intent":"swipe","direction":"up"}`
- **`target` 写成 `by:` 前缀会崩溃**（`SkillCompat.parseTarget`）
  - `raw.startsWith("by:")` 时用 `indexOf(":")` 拿到的是前缀自身的冒号（下标 2），
    再 `substring(3, 2)` 直接抛 `StringIndexOutOfBoundsException`
  - 现改为 `indexOf(':', 3)` 并校验 by/value 非空，非法时回退 `by=text`

### 新增

- **高层语义技能补上可选 `target` 参数**：刷新/搜索/发送/确认/关闭/分享/收藏/复制/删除/下载/新增/
  切换开关/清空输入共 13 个技能，其转译策略本就支持「语义控件未命中时用 AI 给的 target 兜底定位」，
  但技能声明里没有这个参数，AI 无从提供 —— 现统一声明为可选参数，接口与能力对齐
- 提示词技能区块补充说明：用技能名调用时 `args` 与扁平字段两种写法等价（中英双语）

### 测试

- 新增 4 条用例：`args` 回填到意图字段（swipe/open_app/press/wait/remember）、`target` 三种写法解析
  （含 `by:` 不再崩溃）、`args` 缺失时不清空扁平字段、高层语义技能可用 `args` 传 target

---

## [v0.1.326] — 2026-09-19

打通「技能（Skill）」到执行链路的最后一环：此前技能体系只有声明与 UI，AI 提示词里没有技能清单、
AI 也用不了技能名，技能页的启停开关对运行时完全没有影响。现在技能真正可被 AI 调用。

### 新增

- **技能归一化收口点**（`SkillCompat.normalize` + `SkillExecutionGateway.normalize`）
  - AI 的 `intent` 字段现在有三种合法写法：标准意图名（`tap`）、内置技能 id / 技能名（`skill_open_app` / `打开应用`）、MCP 技能 id
  - 内置技能 → 归一化为等价意图，**继续走原有 IntentTranslator 链路（原逻辑一字不改）**
  - MCP 技能 → 解析为 `Normalized.Mcp`，由端侧就地调用远端工具
  - 未知 / 已停用 / 缺必填参数 → 返回中文原因
- **AI 提示词注入技能区块**（`AgentPrompts.skillSection`）
  - 说明「意图名 / 技能 id / 技能名」三者等价，给出 MCP 技能的确切调用格式
  - 只列出**真正可调用**的 MCP 技能（含参数名与必填标记），并声明已停用技能不可调用
  - 旧实现罗列服务器全部工具，但端侧并无对应调用路径 —— 提示词与可执行能力现在严格一一对应
- **AgentEngine 接入技能执行链路**
  - 主循环在「转译」之前做归一化：内置技能放行、MCP 技能就地调用、错误回注给 AI 纠正
  - MCP 调用结果作为「上一步结果」注入下一轮决策上下文，AI 据此判断目标是否达成
  - MCP 调用带 20s 超时兜底、输出截断 1200 字；连续 3 次被拒（未知 / 停用 / 缺参）则停止任务，防死循环
  - 新增动作类型 `ActionType.MCP_CALL`（中文标签「调用技能」）
- **补齐内置技能目录**：`SkillCatalog` 补上缺失的 `remember`（记住信息）/ `fetch`（取网页正文），
  目录与 `IntentType.ALL` 从此一一对应，并同步补 `SkillCompat.toIntent` 映射

### 修复

- **技能启停开关此前不生效**：现在停用技能会被归一化直接拒绝，并告知 AI 改用其它方式或请用户启用
- **`remember` / `fetch` 在技能页查不到**：两者早已在意图全集与转译策略表里，只是目录漏登记

### 测试

- 新增归一化用例：标准意图名放行、技能 id / 中文名翻回等价意图、`remember`/`fetch` 可映射、
  未知与已停用拒绝、MCP 分流与缺参拒绝
- 新增「内置目录与意图全集一一对应」用例，防止目录再次漏项

---

## [v0.1.325] — 2026-09-19

修 Agent 页点输入框弹出键盘后，输入区「升得太高、中间留白」的问题。

### 修复

- **键盘上方空出一整条底部导航栏**
  - 根因：`Scaffold` 的 `bottomBar` 用 `WindowInsets.isImeVisible` 判断键盘是否弹出，
    而该可见位在部分机型 / ROM 上不可靠（键盘已经弹出，它仍是 `false`）→ 底部导航栏没有收起
  - 于是 `Scaffold` 的 innerPadding 里仍带着导航栏高度，输入区又用 `imePadding()` 整段避让 IME，
    两者相加 = **导航栏高度 + 键盘高度** —— 输入区被多抬起一截，多出来的那块正是那条空白
  - 现改用 **IME 实际占位高度**判断：`WindowInsets.ime.getBottom(LocalDensity.current) > 0`
    - 它与输入区的 `imePadding()` 同源：只要输入区被抬起，导航栏在同一帧必定让位，
      不会再出现「抬了却没让位」的错位
  - 连带修复协助浮层（`AgentAssistSheet`）：它同样在 `Scaffold` 内容里做 IME 避让，一并受益

---

## [v0.1.324] — 2026-09-19

修复「Agent 一直卡在观察屏幕」：观察这一步里藏着两处没有上限的阻塞调用，卡住时界面既不动也不报错。

### 修复

- **视觉分析没有看门狗**：视觉链路（截图 → 端侧 3B / 云端视觉）全部发生在 `cloudDecide` 里，
  而状态切到「正在思考下一步」是在视觉分析**之后** —— 于是视觉一慢，界面就一直停在「观察屏幕」
  - 云端视觉 `visionDescribe/visionLocate` 走 OkHttp 同步请求（读超时 120s，且带 2~5 次重试）
  - 端侧 3B `detectControls` 单次 20s，同一步原本可能被调用**两次**（第 1 步失败后第 3 步又重试同一服务、同一张图）
  - 新增 `withVisionWatchdog(WATCHDOG_VISION_MS = 25s)`：视觉调用放到独立协程 await，
    超时立刻放弃视觉描述、只用无障碍元素树继续决策；`visionLocate`（hint 目标定位）同样纳入（20s）
  - 这一步开始前先把状态切到「正在识别屏幕内容」，用户能看见 AI 在做什么，而不是干等「观察屏幕」
- **同一步不重复调用外挂视觉**：`triedOnDevice3b` 记录本步是否已经找过外挂，避免第 3 步兜底再白等 20s
- **无障碍截图可能永不回调**：`takeScreenshot` 若被系统限流/内部错误，协程会永久挂起
  （悬浮窗已隐藏、循环停在观察阶段）→ 加 `SCREENSHOT_TIMEOUT_MS = 2.5s` 超时兜底，超时按「无截图」继续

### 说明

- 看门狗超时是**降级**而非失败：本步没有视觉描述，仍按元素树决策，任务不会中断。

---

## [v0.1.323] — 2026-09-19

修跑马灯色带没贴到屏幕物理顶边（落在状态栏下方）的问题。

### 修复

- **色带顶部落在状态栏下沿**
  - 根因：`statusBarHeight()` 只依赖 `resources.getIdentifier("status_bar_height", "dimen", "android")`，
    该资源名在相当一部分 ROM / 高版本系统上取不到，**返回 0**
  - 返回 0 时 `y = -statusBarHeight()` 退化成 `y = 0`，窗口顶正好落在状态栏下沿 —— 色带自然就到不了屏幕边
  - 现取值顺序：**WindowInsets**（API 30+，最可靠）→ 系统资源名 → 兜底 `dp(24)`
    （宁可多覆盖一点，也不能返回 0）

### 修复 · 连带

- **跑马灯文字会被状态栏压住**（此前被上面那个 0 掩盖）
  - `MarqueeView` 高度 = `marqueeHeightDp + statusBarHeight`，而文字原本在**整个高度**里居中；
    状态栏高度修正为真实值后，文字中心正好落进状态栏区域
  - `MarqueeView` 新增 `setTopInset(px)`：色带背景仍从屏幕物理顶铺下来，
    **文字只在状态栏以下的可用高度里居中**；悬浮窗在创建与尺寸变化时传入

---

## [v0.1.322] — 2026-09-19

优化悬浮窗顶部跑马灯（`MarqueeView`），修掉三处一直存在的显示缺陷。

### 修复

- **文字回到入场位时抽动**：文字画在 `x = offset`，完全离开左侧应满足 `offset < -(textWidth)`，
  原判据是 `offset < -w` —— 文字比屏幕窄时会提前回位，于是文字**还在屏内就跳回入场位**
  - 现改为 `offset < -(textWidth + gap)`，保证完全滚出后才接上下一段
- **相位色从未生效**：`Paint` 中 shader 优先级高于 color，原先给文字设了渐变 shader 后，
  `setText(text, marqueeColor(phase))` 传入的相位色被无声覆盖
  - 现文字改用相位色纯色（底色渐变继续承担彩色视觉），相位色这才真正随状态变化
- **状态更新把文字钉在原地**：AI 状态每秒更新数次，原 `setText` 每次都 `offset = 0f`，
  文字反复从头开始，看上去像卡住不动
  - 现更新文字不重置滚动相位；内容与颜色都没变时直接跳过，不做无谓重绘

### 优化

- **短文本居中静止**：文字窄于可视区时不再空转一整圈，同时停掉每帧重绘（省电）
- **单帧最大推进时长** 限制为 0.1 秒：视图不可见一段时间后恢复时，文字不会瞬移一大段
- 清理死代码：未使用的 `bitmap` / `shader` 字段与 `buildGradient()`、未使用的 `kotlin.math.min` 导入

---

## [v0.1.321] — 2026-09-19

需要协助 / 答疑时改为从页面下方浮入协助浮层。

### 新增

- **`AgentAssistSheet`**：底部协助浮层，承载「选项 + 输入框」的完整交互
  - 从页面下方浮入（`slideInVertically` 由下向上 + 淡入，与全站出现方向一致），退场反向
  - 面板加 `animateContentSize()`，文案换行 / 内容增减时高度平滑过渡，不「跳一下」
  - 两种场景共用同一副骨架，仅出口不同：
    - **答疑**（计划有歧义）：问题 + 选项（点一下即答）+ 自由输入 + 「提交」
    - **协助**（敏感页只读 / 动作连续未生效）：原因 + 「已手动处理」+ 指导输入 + 「告诉 AI」
  - 浮层出现时输入区让位（`if (shownAssist == null)`），否则同屏会出现两个输入框
  - 退场动画期间 `assist` 已为 null，缓存最后一次内容（`shownAssist`），避免面板先空掉再滑走
  - 视觉遵循三层圆角口径：浮层容器 = `Card`，输入框与选项 = `Tile`，出口按钮 = `Chip`

### 变更

- **`PlanClarifyItem` 去掉选项列表与手写输入**：这两处职责已由浮层承担，卡片只保留「问题原文 + 一句指路」
  - 同屏出现两套入口（卡片里一套、浮层里一套）会让人不知道该点哪个
  - 连带移除其 `onAnswer` 参数，调用点同步更新

### 说明

- 悬浮窗（桌面覆盖层）侧的 assist 交互未改动：`FloatingWindowService` 的 `clarify` / `guide` 面板仍独立工作，
  与 App 内浮层是两条并行入口（一个在桌面、一个在应用内）

---

## [v0.1.320] — 2026-09-19

修三个界面问题：顶部渐隐遮挡内容、无障碍提示重复。

### 修复

- **顶部渐隐遮罩常驻导致遮挡内容**
  - `TopFadeScrim` 高 56dp 且叠在列表区上层，而 `LazyColumn` 的 `top` padding 只有约 8dp，
    于是列表首项（空态图标、第一张卡的上半截）一出现就被压掉 56dp
  - 它本意是"内容滚到顶部被截断时柔化边界"，未滚动时不该存在：
    现由滚动位置驱动（`firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0`），
    经 `animateFloatAsState` 淡入淡出，静止在顶部时完全不绘制
  - `TopFadeScrim` 新增 `alpha` 参数（0 时直接不组合，不占布局）
- **无障碍未开启提示重复**
  - 空态判据 `showEmpty` 不含 `items`（只看 `traces.isEmpty()` 等），
    因此 `AgentEmptyState` 的引导文案与 `AgentTimelineMapper` 产出的 `Notice` 会同屏出现
  - 修复：空态时过滤掉任务流里的 `Notice`（空态的引导更完整，保留它）；非空态仍由 `Notice` 提示
  - 自动跟随滚动改用过滤后的列表，避免索引越界

---

## [v0.1.319] — 2026-09-19

统一 Agent 页的出现动效方向与卡片口径。

### 变更 · 动效方向

- **出现动效全站统一为「由下向上」**
  - `animateListItem` 去掉缩放（原 0.97 → 1），只保留淡入 + 纵向位移 24dp → 0
    - 理由：纵向位移与缩放松缩是两种观感，叠在同一元素上会互相打架
  - 4 处展开 / 收起（顶栏菜单、步骤卡「原始数据」、文档卡、助手文本卡）改为从底部展开：
    `expandVertically(expandFrom = Alignment.Bottom)` / `shrinkVertically(shrinkTowards = Alignment.Bottom)`
  - 步骤卡状态徽标（未生效 / 已生效）由纯淡入淡出改为「由下向上」滑入 + 淡入
  - 运行状态条入场同样改为「由下向上」（位移 it / 3）

### 变更 · 协调性

- **任务流条目统一圆角与描边**：同一列里此前混用 `AppRadii.Item`(20dp) 与 `AppRadii.Card`(24dp)，且部分卡片无描边
  - 统一为 `Item` + `1dp outlineSoft`：完成卡、失败卡、需协助卡、计划卡、空态警告块
  - 口径固定为三层：**任务流条目 = `Item`(20dp)**、**嵌套块 = `Tile`(14dp)**、**浮层 = `Card`(24dp)**
  - 顶栏下拉菜单属浮层，保持 `Card` 不变

---

## [v0.1.318] — 2026-09-18

补齐意图回显覆盖：此前有一整类意图在 Agent 页是隐形的。

### 修复

- **端侧决策的意图在 Agent 页完全不可见**（真实缺口，非罕见路径）
  - `localDecision.decide()` 命中时走直通分支（`fromLocal = true`），**不经过 `cloudDecide`**，因而不写 `StepTrace`
  - 而任务流以 trace 为骨架（`AgentTimelineMapper.buildRuns` 首行即 `if (traces.isEmpty()) return emptyList()`），
    于是这一步只留下 `StepRecord`、却挂不上列表 —— 用户体感是"AI 没动，页面自己变了"
  - 新增 `recordLocalStepTrace()`：端侧决策同样留档，以 `visionSource = "端侧决策"` 标注来源（`LOCAL_DECISION_SOURCE`）

### 新增

- **失败原因回显**：`StepRecord` 新增 `detail`（执行 / 转译结果说明），三处 `recordStep` 调用点均传入 `verify.detail`
  - 步骤卡片在「未生效」之外补上「原因：…」（红色），成功时弱化为执行层说明
  - 与 `shellOutput` 内容相同时不重复展示
- 卡片新增「端侧」徽标，区分"AI 想的"与"端侧规则直接做的"

### 变更

- **状态徽标平滑过渡**：`未生效 / 已生效` 改用 `AnimatedContent` + 淡入淡出切换，不再是瞬间跳变

---

## [v0.1.317] — 2026-09-18

参考 Aether 的回显手法，重做「进行中」状态的观感：把过程与结果在视觉上分开。

### 变更

- **进行中状态去卡片化**（`LiveStatusItem`）：不再铺卡片底色与边框，改为「一行弱化文字 + 1dp 细分线」
  - 借鉴点：Aether 的 `AgentWorkingStatusHeader` 用次要色文字 + 一条 1dp 细分线，让「还在进行的过程」不与「已完成的结果卡片」争夺视觉权重
  - 步骤卡（结果）保持卡片形态不变，两者层次因此拉开
- **新增「已工作 N 秒」**：`AgentState` 新增 `startedAtMillis`（任务启动时写入），经 `LiveStatus` 透出，UI 用 `produceState` 每秒刷新
  - 不足 1 秒不显示；超过 1 分钟显示「N 分 N 秒」
- **思考中改用微光扫过文字**（`ShimmerStatusText`）：取代原先的常驻脉冲圆点
  - 借鉴点：Aether 的 `ShimmerStatusText` 用 `Brush.linearGradient` 扫过文字表达"正在生成"，行程随文字长度伸缩、扫完停顿再重来
  - 仅在 `THINKING` 阶段启用；`reduceMotion` 时降级为静态文字（其余阶段本就是弱化静态文字）

### 移除

- `LiveStatusItem` 的脉冲圆点与按阶段取色的 `dotColor`（"正在活动"的语义已由微光承担）
- 连带清理失效 import（`CircleShape` / `EaseInOut` / `tween`）

### 说明

- 未逐行照搬 Aether（GPL-3.0）：只借鉴「过程弱化、结果成卡」的层次处理与微光手法，颜色、间距、动效参数全部走本项目已有令牌（`onSurfaceRaised` / `outlineSoft` / `AppSpacing`）

---

## [v0.1.316] — 2026-09-18

Agent 页回显增强：把 AI 正在生成的内容、以及命令的真实执行证据都展示到任务流里，并重排步骤卡片的信息层级。

### 新增

- **实时思考回显**：AI 决策的流式输出此前只推给悬浮窗，现在同步累积到 `AgentEngine.decisionStream` 并在 Agent 页展示
  - `AgentTimelineItem.LiveStatus` 新增 `streaming` 字段，实时状态卡下方贴出正在生成的内容（取尾部 10 行，像终端 tail）
  - 重试时同步清零（避免两遍文本叠加）；决策完成后清空，正文已落 `StepTrace` 的「原始数据」可回看
  - `AgentScreen` 对 `decisionStream` 做 150ms 节流，避免逐字重建 LazyColumn
- **命令输出回显**：`StepRecord` 新增 `shellOutput`，`AgentEngine` 以 `pendingShellOutput` 把本步输出精确关联到本步记录
  - 步骤卡片新增「命令与输出」块：上为实际执行的命令（`action.command`），下为 stdout / stderr；失败时显示可读原因
  - 此前 `lastShellOutput` 只回传给 AI，用户完全看不到 AI 跑了什么、拿到了什么

### 变更

- **步骤卡片信息层级重排**（`StepCallItem`）：一级「动作人话 + 生效状态」→ 二级「依据」→ 三级「命令与输出」→ 四级「元信息 + 原始数据」
  - 置信度从顶行 `StatusPill` 下移进元信息行（`耗时 · tokens · 置信度`），顶行只留"做了什么、成没成"

---

## [v0.1.315] — 2026-09-18

界面精简：Agent 升为主界面，App 内去掉玻璃材质，悬浮窗降为可选能力。

### 变更

- **主界面改为 Agent**：底部导航顺序调整为 Agent / 主页 / 记忆 / 设置，启动后默认进入 Agent
  - `HomeScreen` 保留为第二个 Tab，其快捷入口下标同步（Agent → 0）
  - 全局返回手势：非 Agent Tab 一律回到 Agent（原为回主页）
- **悬浮窗降为可选能力**（不再当作缺失权限来提示）
  - 设置页「运行参数」新增「启用桌面悬浮窗」开关（`floatingWindowEnabled`，默认开）
  - `AgentEngine.maybeStartFloating` 同时校验「开关开启 + 已授权」
  - Agent 页移除三处悬浮窗引导：任务流顶部 Notice、空态警告卡片、顶栏菜单的权限状态与「去授权」按钮
  - `AgentTimelineItem.NoticeKind` 只保留 `ACCESSIBILITY`（真正的硬前置）；顶栏菜单现只展示待执行队列

### 移除

- **App 内玻璃材质**：底部导航栏与记忆图谱容器的 `liquidGlass` 改为实色 / 半透明底
  - 删除 `ui/components/LiquidGlass.kt`（`liquidGlass` / `LiquidGlassCard` / `liquidGlassSurface`，改后全项目零引用）
  - `overlay/LiquidGlassDrawable.kt` **保留**：桌面悬浮窗视觉不变
- 连带清理无用 import（`LocalContext` / `liquidGlass` / `Color` 等）

### 说明

- 悬浮窗与边缘光效是两个独立开关：关闭悬浮窗不影响边缘光效（后者仍需悬浮窗权限，因为它是系统悬浮层绘制）

---

## [v0.1.314] — 2026-09-18

接入 Termux 作为执行通道，并把它"图形界面做不到"的命令行能力纳入意图转译层。

### 新增

- **`device/shell/TermuxBridge.kt`**：Termux 执行通道
  - 目标组件 `com.termux/.app.RunCommandService`（action `com.termux.RUN_COMMAND`），结果走 **PendingIntent 回传**（Termux 官方源码注释指出：结果目录文件方式在 `allow-external-apps` 未开时会永久挂起）
  - 暴露 `isInstalled()` / `hasRunCommandPermission()` / `isAvailable()` / `probe()` / `executeShell()`
  - 回传 Bundle 按键名子串防御性解析，兼容 Termux 版本间的 key 差异
- **`TermuxResultReceiver` + `TermuxResultRelay`**：结果回传接收器与挂起请求登记表（自收自发，`exported=false`）
- **`fetch` 意图（纳入转译层）**：AI 只说"取哪个地址的正文"，命令由端侧拼装
  - `IntentType.FETCH` + `TermuxFetchStrategy`：`uri` → `raw curl -sL --max-time 20 -- <url>`，地址字符白名单过滤
  - Termux 不可用时转译层直接给出可读失败，引导 AI 改走 UI 意图
- **执行通道四态**：`AUTO` / `ADB` / `SHIZUKU` / `TERMUX`；AUTO 顺序为 无线 ADB → Shizuku → Termux（第三顺位兜底）
- **能力页 Termux 卡片**：三项前置条件（已安装 / 已授权 / 已开启 allow-external-apps）+ 分步引导与实跑探测

### 变更

- `executeShellAction` 新增 Termux 工具链判定：`curl`/`python`/`jq` 等命令在 adb shell 中通常不存在，按命令名直接走 Termux，不受通道偏好影响
- 抽出 `finishShellResult`，shell 输出回传逻辑单点收口（原 `runRealShell` 尾部内联）
- 提示词（中英同步）：意图表与规划提示词新增 `fetch`；`situationalExtras` 在"任务需从网络取数 + 本机有 Termux"时按需注入 fetch 用法与边界，其余任务不增加任何篇幅

### 说明

- Termux 是**普通应用权限**的 Linux 环境，与无线 ADB / Shizuku 的系统级 shell 不同：可跑 curl/python/文本处理，**不能**执行 `am` / `pm` / `settings`
- 前置条件：Termux 侧 `~/.termux/termux.properties` 设 `allow-external-apps=true`，并授予本 App `com.termux.permission.RUN_COMMAND` 权限
- 本环境无法跑 Gradle / 无真机，需真机验证 Termux 回传链路（`probe()` 返回 `__HPA_TERMUX_OK__` 即通）

---

## [v0.1.313] — 2026-09-18

砍掉工作区模块，AI 的 `write_doc` 能力保留，生成结果改为在 Agent 页任务流内嵌预览。

### 新增

- **`feature/document/DocumentEngine.kt`**：承接原 `WorkAreaEngine` 中真正需要的那部分
  - 只做两件事：文档正文落盘到私有目录 `documents/`；把最近一次结果推成 `StateFlow<DocResult>`
  - 暴露 `writeDocument(content, fileName)` / `result` / `dismiss()` / `error`
- **Agent 页文档预览**：`AgentTimelineItem.DocPreview` + `AgentTimelineMapper` 新增 `doc` 入参
  - `AgentItems.kt` 新增 `DocPreviewItem`：文件名 + 默认展开的 Markdown 正文（限高 420dp 内滚、可展开/收起/关闭）
  - `AgentScreen` 的 `when(item)` 穷尽分支同步补齐

### 移除

- **工作区 UI 全量删除**：底部「工作区」Tab、`ExtrasPage.FileList` / `FileEditor` 二级页及其返回栏、`ui/workspace/` 8 个文件（WorkAreaScreen / FileListScreen / FileEditorScreen / WorkDisplayPanel / WorkFilesEntry / WorkGenerateCard / WorkLogStream / WorkPreviewCard）
- **`feature/workspace/WorkAreaEngine.kt`**：文件列表、编辑器、流式生成、AI 改写、操作日志等纯 UI 状态一并删除
- 主页「工作区」快捷入口；Tab 下标重排为 主页 0 / Agent 1 / 记忆 2 / 设置 3

### 变更

- `AgentEngine.executeWriteDoc`：落盘后提示语改为「文档已生成，可在 Agent 页查看」；`run()` 开头新增 `documentEngine?.dismiss()`，新任务不带上一份文档残留
- 提示词（中英同步）：意图表、独占路由铁律、规划/决策提示词中的「工作区」措辞统一改为「生成文档，结果在 Agent 页预览」
- 同步更新 README 目录树与模块说明、`杂项/项目完整流程说明.md` 的包表与 UI 结构图

---

## [v0.1.310] — 2026-09-16

对应提交 `74efefb`（重构区间 `2e906f3..74efefb`）。

本轮为纯结构调整：行为零变化（UI 参数、控制流、提示词、导出文案均未改动），目标是「文件职责单一、包名即层级」。

### 重构

- **UI 超大页面瘦身**：7 个 Compose 页面（537~1119 行）拆为约 30 个职责单一文件，根页面只保留入口与 Tab 定义
  - `ui/skill/SkillManagerScreen.kt` 1119 → 133 行：拆出 `SkillsTab` / `SkillEditorDialog` / `McpTab` / `WirelessAdbTab` / `PromptsTab`
  - `ui/debug/DebugScreen.kt` 1098 → 234 行：拆出 `DebugTabs` / `CapabilityStrip` / `DebugFormatters` + `panels/`（StepShot / Metrics / Chat / Log / History / Steps / Timeline）
  - `ui/workspace/WorkAreaScreen.kt` 688 → 171 行：拆出 `WorkFilesEntry` / `WorkGenerateCard` / `WorkPreviewCard` / `WorkLogStream` / `WorkDisplayPanel`
  - `ui/memory/MemoryGraphScreen.kt` → 184 行：拆出 `MemoryGraphCanvas` / `MemoryStats` / `MemoryLists`
  - `ui/home/HomeScreen.kt` 580 → 298 行：拆出 `HomeHero` / `PermissionRadarCard` / `HomeCards`
  - `ui/agent/AgentScreen.kt` 577 → 121 行：拆出 `AgentText` / `PlanPanel` / `AgentCards`
  - `ui/test/TestScreen.kt` 556 → 332 行：拆出 `TestPresetCard` / `TestResultViews`
- **UI 重复实现收敛**
  - `EmptyHint` 三份 → 统一到 `ui/components/Components.kt`（Debug 侧改名 `DebugEmptyHint`，规避同包顶层重名）
  - `MetricCard` / `StatCard` 结构同构 → 合并为 `StatTile(title, value, unit, accent)`
  - 手搓 `Card(shape = …)` → 收敛到 `Components.kt` 的 `SectionCard(title, count, countColor, onClear)`
  - 新增 `ui/components/Formatters.kt`：`formatClock` / `formatFileTime` / `formatSize` 三处同构实现归一
  - `ui/theme/Theme.kt` 新增 `AppSpacing`（4 / 8 / 12 / 16dp），替换页面内硬编码间距
  - `ui/agent/AgentScreen.kt` 的 `isMostlyChinese` 副本删除，改调 `domain.rules.EngineRules.isMostlyChinese`
- **全量搬包重分层**：148 个主源文件 `package` 与所在目录 100% 对齐，顶层包即未来 Gradle 模块边界
  - `core/`：`ai`(4) · `security`(1) · `text`(1，HumanTranslator) · `notify`(3)
  - `domain/`：`model`(9) · `rules`(3，EngineRules / ShellCommands / LocalDecisionEngine)
  - `data/`：`prefs`(1) · `store`(5，原 `memory` / `task` / `debug` / `mcp` / `prompt` 五处持久化收敛) · `export`(1)
  - `device/`：`a11y`(2) · `shell`(14，原 `shizuku` + `shizuku/adb` 扁平化) · `screen`(2) · `vision`(2)
  - `engine/`：根(2，AgentEngine / AgentPrompts) · `execution`(5) · `perception`(3) · `network`(1) · `prompt`(1)
  - `overlay/`：原 `floating` 全部 5 个
  - `feature/`：`task`(1) · `skill`(5) · `mcp`(6) · `workspace`(1) · `adskip`(2) · `edge`(2) · `test`(4)
  - `ui/`：原结构 + 新增 `ui/model`（PermissionRadar）
- **日志导出逻辑外迁**：新增 `data/export/LogExporter.kt`
  - 自 `MainViewModel` 迁出约 160 行文件导出实现（文本日志 / 分任务 JSON / 诊断报告）
  - `MainViewModel` 仅保留 3 个薄委托方法，UI 调用点零改动
  - 导出文案、字段、排版逐字保留；落盘参数抽为 `downloadValues()` 统一
  - `formatLogTimestamp` 随之迁入数据层，避免 `data → ui` 反向依赖

### 移除

- `engine/AgentPrompt.kt`（原 `agent/AgentPrompt.kt`）：全项目零引用死代码
- `app/build.gradle.kts`：未使用的 `androidx.navigation.compose` 依赖（全项目零引用）

### 测试

- 新增 `data/export/LogExporterTest.kt`（10 个用例）：空日志、指定任务无匹配、系统日志归组、多任务批量、诊断报告翻译 + 脱敏路径
- 测试源集补齐跨包 import（23 个测试类）

### 兼容性

- `AndroidManifest.xml` 5 处组件路径同步：`.device.a11y.AgentAccessibilityService` / `.device.screen.ScreenSharingService` / `.overlay.FloatingWindowService` / `.feature.edge.EdgeLightingService` / `.device.shell.AdbPairingReceiver`
- 跨仓库与跨组件的不透明标识符一律未改：`com.phoneagent.floating.STOP`、`com.phoneagent.edge.*`、`com.phoneagent.action.RECEIVE_PAIRING_CODE`、`com.phoneagent.agent_progress_overlay`，以及外挂 APK 契约包名 `com.phoneagent.ondevice`
- `proguard-rules.pro` 使用 `com.phoneagent.**` 通配符，keep 规则不受搬包影响
- `res/xml/accessibility_service_config.xml` 的 `settingsActivity` 指向 `com.phoneagent.ui.MainActivity`，`ui/` 位置未变

### 基础设施

- 单测基线不下滑：24 suites / 242 tests / 0 failed / 3 skipped（重构前 232 tests）
- `:app:lintDebug` / `:app:assembleDebug` / `:app:assembleRelease` 全部通过
- 打 tag `refactor-baseline` 锚定搬包前状态

---

## [v0.1.265] — 2026-09-13

### 新增
- **Release APK 签名构建**
  - 添加 `upload-keystore.jks`（自签名，有效期 10000 天）
  - `app/build.gradle.kts` 支持从 `upload-signing.properties` 加载签名配置
  - `upload-signing.properties` 加入 `.gitignore`（不提交到仓库）
  - Release 配置：R8 混淆 + arm64-v8a 单 ABI，包体大幅缩减
- **APK 上传 Release**：v0.1.265 release APK（约 10MB）发布到 Gitee / GitHub Release

### 新增
- **MCP 验证与使用规则** `app/src/main/java/com/phoneagent/mcp/McpRules.kt`
  - 服务器名（非空、无空格）、URL（须 http/https 且含有效主机）校验
  - 协议版本白名单（`2025-03-26`）、工具名合法性、参数必填/类型校验
- **MCP 服务器持久化** `app/src/main/java/com/phoneagent/mcp/McpStore.kt`
  - 基于 DataStore 存取服务器配置列表，配置变更后自动落盘
- **内置 MCP 市场** `app/src/main/java/com/phoneagent/mcp/McpMarketplace.kt`
  - 免费第三方源预设（经典工具类：filesystem/sqlite/github/notion；公共开放 API：天气/汇率/新闻/币价）
  - 支持分类筛选、关键词搜索、一键"选用"回填新增表单
- **MCP 信息解析增强** `app/src/main/java/com/phoneagent/mcp/McpClient.kt`
  - `describe()`：initialize 握手 + tools/list 枚举，返回 capabilities / serverInfo / 工具及结构化参数
  - `parseParamsFromSchema()`：从 JSON Schema 提取 type / required / enum / default / description
  - 记录最近一次请求/响应原文（JSON-RPC），供 UI 展示
- **MCP 信息结构化注入 Agent 提示词** `app/src/main/java/com/phoneagent/agent/AgentEngine.kt`
  - `mcpToolsPromptText()`：把已启用服务器的工具、参数、使用规则动态注入系统提示，无工具则不增加负担
- **MCP 模块 UI** `app/src/main/java/com/phoneagent/ui/skill/SkillManagerScreen.kt`
  - McpTab：市场选用、新增表单（含可选 Token）、请求信息 JSON 预览
  - 服务器卡片：启停开关、测试有效性、绑定为技能、删除、详情展开（协议版本/服务器元信息/能力/工具及参数）
  - 详情区展示最近请求/响应 JSON（等宽字体预览）
- **Agent 运行时广告过滤** `app/src/main/java/com/phoneagent/adskip/AdContentFilter.kt`
  - 识别"控件内含广告信息 + 跳过/关闭/× 按钮"的广告，直接返回可点击关闭按钮
  - 剔除广告相关元素，保证广告信息不回传给 AI
- **完整技能编辑器** `SkillEditorDialog` 全屏化
  - 支持名称 / ID（新建自动生成、编辑只读）/ 分类 / 说明 / 兼容旧命令意图
  - 结构化参数编辑：参数名 / 标签 / 类型（text·number·boolean·select）/ 必填 / 候选项 / 默认值 / 说明，可动态增删

### 增强
- `McpManager`：改为可变服务器列表（StateFlow 观察），新增 add/remove/setEnabled/replaceAll/describe/bindToolsToSkills
- `MainViewModel`：MCP 服务器观察、增删、启停、持久化、描述、请求/响应原文获取、市场条目查询
- `AppModule`：Koin 依赖注入接入 `McpStore`
- `AgentEngine`：决策循环集成广告过滤——识别到广告直接点击关闭并跳过本轮 AI 决策；无关闭按钮时以干净快照喂给 AI
- `AppModule` / `AgentEngine`：MCP 工具清单在任务开始时一次枚举并注入系统提示

---

## [v0.1.264] — 2026-09-10

对应提交 `c01b589`。

### 新增
- **ADB 无线配对通知栏向导** `notify/AdbPairingNotifier.kt`（121 行）
  - 搜索中 → 已发现设备 → 请求配对码（通知内联输入框）→ 配对中 → 成功/失败，全程通知栏驱动
  - `RemoteInput` 内联输入：用户无需回到 App 即可输入 6 位配对码
  - `POST_NOTIFICATIONS` 权限适配（Android 13+）
- **AdbPairingReceiver** `shizuku/adb/AdbPairingReceiver.kt`（22 行）
  - 接收通知内联配对码，委托给 `WirelessAdbPairingFlow.onPairingCode()`
- **WirelessAdbPairingFlow** `shizuku/adb/WirelessAdbPairingFlow.kt`（118 行）
  - Nsd 服务发现 → 配对码校验 → 网络配对 → Shizuku 拉起，完整状态机编排
- `AndroidManifest`：注册 `AdbPairingReceiver`，新增 `POST_NOTIFICATIONS` 权限

### 增强
- `AdbWirelessTransport`：`discoverService()` 改为公开方法
- `WirelessAdbModels`：`AdbPhase` 枚举扩展
- `MainViewModel`：接入配对流状态
- `SkillManagerScreen`：无线 ADB Tab 交互优化
- `AppModule`：Koin 注册新模块

---

## [v0.1.140] — 2026-09-02

对应提交 `c132122`。

### 新增

- **Skill 技能系统**（`app/src/main/java/com/phoneagent/skill/`）
  - `SkillCatalog`：内置技能库（系统级能力 + 实用脚本）
  - `SkillRegistry`：技能注册 / 查询 / 启用禁用管理
  - `SkillCompat`：旧 `legacyIntent` → 新 Skill 的兼容桥接
  - `SkillExecutionGateway`：统一执行入口（内部 intent / MCP 工具分发）
  - `SkillModels`：`Skill` / `SkillParam` / `SkillSource` / `SkillCategory` 数据模型
- **MCP 协议客户端**（`app/src/main/java/com/phoneagent/mcp/`）
  - `McpManager`：服务器配置 / 健康检查 / 绑定工具为 Skill
  - `McpClient`：通用 MCP 协议实现（initialize / tools/list / tools/call）
  - `OkHttpMcpTransportFactory`：HTTP + SSE 双模式传输
  - `McpModels`：MCP 协议数据模型
- **无线 ADB 配对**（`app/src/main/java/com/phoneagent/shizuku/adb/`）
  - `AdbWirelessTransport`：6 位配对码配对、无线调试连接
  - `WirelessAdbStateMachine`：状态机驱动（UNPAIRED → PAIRING → BOOTING → READY）
  - `ShizukuBootstrap`：配对成功后自动拉起 Shizuku
  - `AdbBootstrapTransport`：回退到 Shizuku provider 启动
  - `WirelessAdbModels`：阶段 / 错误 / 状态数据类
- **提示词模板引擎**（`app/src/main/java/com/phoneagent/prompt/`）
  - `PromptTemplate`：支持 `{task}{skills}{mcpTools}{controls}{currentApp}{lastResult}{goal}{auditRejection}{situational}` 占位符
  - `PromptTemplateStore`：DataStore 持久化，内置 + 自定义混合管理
- **控件树感知**（`app/src/main/java/com/phoneagent/perception/ControlTreeBuilder.kt` + `model/ControlNode.kt`）
  - 将无障碍节点压缩为结构化控件树供 LLM 消费
- **UI**（`app/src/main/java/com/phoneagent/ui/skill/SkillManagerScreen.kt`，651 行）
  - 技能 / MCP / 无线 ADB / 提示词 四段式 Tab 管理页
  - 技能卡片列表 + 批量选择 + 导出（JSON 到剪贴板）+ 导入
  - MCP 服务器增删改查 + 有效性测试 + 一键绑定为 Skill
  - 无线 ADB 配对引导（配对码输入 / 状态提示 / 错误恢复）
  - 提示词模板编辑 + 占位符说明

### 增强

- `MainViewModel`：新增 skills / mcp / adb / template 相关 StateFlow 与操作方法（+133 行）
- `IntentTranslator`：适配 Skill 系统，支持从 Skill 到 Intent 的扩展转译（+125 行）
- `AgentIntent` / `UiElement`：模型字段扩展
- `AppModule`：Koin 依赖注入更新，接入 SkillRegistry / McpManager / PromptTemplateStore

### 修复

- `SkillManagerScreen`：`selected` 状态改用 `mutableStateOf(mutableSetOf())` 加委托，确保 UI 正确重组
- `SkillManagerScreen`：`LocalContext.current` 在 `onClick` lambda 中非法调用 → 提升到 Composable 顶层一次性读取
- `HomeScreen`：补充 `Icons.Rounded.Devices` 图标导入

### 测试

- 新增 12 个单元测试：
  - `skill/SkillRegistryTest` / `SkillCompatTest` / `SkillExecutionGatewayTest`
  - `mcp/McpClientTest`
  - `shizuku/adb/ShizukuBootstrapTest`
  - `prompt/PromptTemplateEngineTest`
  - `perception/ControlTreeBuilderTest`
  - `execution/LongRunModelTest` / `RealModelDecisionTest` / `SimulatedTaskRunTest`
  - `execution/IntentTranslatorTest` / `IntentTranslatorStrategyTest`

---

## [v0.1.138] — 2026-09-01

对应提交 `f6caefa`。

### 新增

- **统一截图入口** `app/src/main/java/com/phoneagent/screen/ScreenCapture.kt`
  - 优先使用无障碍服务 `takeScreenshotBitmap()`（API 30+，无需 MediaProjection 前台服务）
  - 回退到 `ScreenSharingService`（MediaProjection 路径，兼容 API < 30）
  - 截图前自动隐藏悬浮窗，结束后恢复，避免污染画面
- **调试记录持久化** `app/src/main/java/com/phoneagent/debug/DebugRecordsStore.kt`
  - 日志 / 步骤轨迹 / 执行历史 / 对话写入 app 内部目录 `hpa_debug_hist/`
  - AgentEngine 启动时回载，重启不丢
  - 截图缩略图统一压缩到 360px 宽，降低内存与磁盘占用
- 单元测试：`ShellCommandsTest`（282 行）、`IntentTranslatorStrategyTest`（124 行）

### 重构

- `IntentTranslator`：`internal fun interface IntentTranslationStrategy` 改为 `private fun interface`
  - 解决 `TranslationContext`（文件内类型）被 `internal` 接口方法签名暴露导致的 Kotlin 编译错误
  - 对外暴露的仅保留 `class IntentTranslator` 本身，策略实现类变为文件内私有细节
- `AgentEngine`：接入 `ScreenCapture` 与 `DebugRecordsStore`
- `EngineRules`：规则引擎硬约束增强（+24 行）
- `AgentAccessibilityService`：截图能力扩展（+54 行）

---

## [v0.1.137] — 2026-08-31

对应提交 `fc567c2`。

### 新增

- **IntentTranslator 转译层** `app/src/main/java/com/phoneagent/execution/IntentTranslator.kt`
  - 将 AI 决策 `AgentIntent` → 端侧执行指令 `AgentAction` 的独立转译层
  - 策略模式：`OpenAppStrategy` / `TargetLocationStrategy` / `ScrollToStrategy`，每种意图一颗策略
  - `TranslationContext`：通道模式 / 页面快照 / 视觉坐标 hint / 公共动作基线
  - 新增意图只需实现 `IntentTranslationStrategy` 并注册进分派表
- **IntentResolver** `execution/IntentResolver.kt`：目标控件定位分发路由
- **AppNameResolver** `execution/AppNameResolver.kt`：App 名称 → 包名解析
- **CapabilityManager** `execution/CapabilityManager.kt`：通道能力探测（无障碍 / Shizuku / MediaProjection）
- **EngineRules** `agent/EngineRules.kt`：硬约束规则引擎（拦截危险动作 / 只读模式横切约束）
- **FloatingUi** `floating/FloatingUi.kt`：悬浮窗 UI 独立组件，从 `FloatingWindowService` 解耦
- **TemplateMatcher** `task/TemplateMatcher.kt`：长线任务模板匹配引擎
- **AgentIntent** `model/AgentIntent.kt`：标准化意图数据模型

### 重构

- `AgentEngine`：±673 行大重构，职责拆分，接入转译层 + 规则引擎
- `AgentPrompts`：提示词大更新
- `FloatingWindowService`：+307 行，悬浮窗增强
- `AiClient` / `ActionExecutor` / `LocalDecisionEngine` / `CloudAgent`：适配新架构

### 基础设施

- GitHub Actions CI：`.github/workflows/android-ci.yml`
- 6 个单元测试类初始入库

---

## [v0.1.136] — 2026-08-22

对应提交 `6248dd1`。

### 新增

- **长线任务系统** `app/src/main/java/com/phoneagent/task/TaskStore.kt`
  - 检查点持久化（DataStore）+ 模板库 + 执行策略
  - 中断恢复 / 任务分支 / 策略切换
- **AI 日志翻译** `app/src/main/java/com/phoneagent/debug/HumanTranslator.kt`
  - 将调试页的 AI 决策信息翻译为中文，方便非技术用户阅读
- **执行状态主动通知** `app/src/main/java/com/phoneagent/debug/ActiveNotifier.kt`
- **长线任务设置页** `app/src/main/java/com/phoneagent/ui/settings/SettingsLongRun.kt`
- **视觉控件数据模型** `app/src/main/java/com/phoneagent/vision/DetectedControl.kt`（统一外挂视觉 Agent 入口，替换原 LocalUiDetector / LocalVisionEngine）

### 增强

- `AgentEngine`：+374 行（长线任务逻辑 / 视觉回退 / 温度策略）
- `DebugScreen`：+392 行（双语 / 对比 / 翻译面板）
- `PageAnnotator`：+244 行（页面标注增强）

---

## [v0.1.134] — 2026-08-22

对应提交 `76c93c9`。

### 修复

- `AgentEngine`：元素树稀疏时（游戏 / WebView）自动截图并交给视觉模型处理，作为无障碍回退
- 视觉回退配置项在设置页的接入

---

## [v0.1.133] — 2026-08-22

对应提交 `4e50e33`。

### 新增

- **外部视觉服务 AIDL** `app/src/main/aidl/com/phoneagent/ondevice/IVisionService.aidl`
  - 跨进程视觉识别接口，允许外挂视觉 Agent 接入
- **外挂视觉 Provider** `app/src/main/java/com/phoneagent/vision/ExternalVisionProvider.kt`
- **Markdown 预览组件** `app/src/main/java/com/phoneagent/ui/workspace/MarkdownPreview.kt`

---

## [v0.1.132] — 2026-08-20

对应提交 `da76725`。

### 新增

- **ShellCommands** `app/src/main/java/com/phoneagent/agent/ShellCommands.kt`
  - AI 友好 ADB 命令解析器：`tap` / `swipe` / `key` / `launch` / `input` / `back` / `home` / `enter` 等
  - 支持比例 / 百分比 / 像素坐标多格式
- **ShizukuManager**（原模块增强）
  - 三态状态管理（UNAVAILABLE / PERMISSION_DENIED / READY）
  - 高权限 Shell 执行通道
- **AdSkipperCore** `app/src/main/java/com/phoneagent/adskip/AdSkipperCore.kt`
  - 自动识别并跳过广告弹窗 / 开屏广告
- **AppPageIndex**：App 页面直达索引（深链）
- **WorkAreaEngine**：工作区文件管理
- **TaskProgressNotifier**：系统通知进度
- **EdgeLighting**：曲面边缘光效

### 修复

- `.gitignore` 添加 `BUGS/` 与 `.trae/` 排除规则
- 移除 `BUGS/` 目录（改用 GitHub Issues 跟踪）

### 文档

- `README.md` 全面更新，补充所有新模块说明与版本历史

---

## [v0.1.130] — 2026-08-13

对应提交 `4eb400d`。

### 新增

- Happy Phone Agent (Phtomt) 首次开源
- 基于 Jetpack Compose + Material 3 Expressive 的 Android Agent 框架
- 多模型链路聚合（主模型 + 视觉模型 + 思考模型）
- ReAct 执行循环（观察 → 决策 → 执行 → 验证）
- 无障碍服务权限申请 / 悬浮窗权限管理
- 敏感页面检测 + 数据脱敏
- 页面指纹验证（防止执行过程中 App 跳转）
- Koin 依赖注入
- 版本号自动递增（`version.properties` + Gradle 构建钩子）
- MIT License

### 基础设施

- Git 初始化 + Gitee / GitHub 双远程推送
- `README.md` / `LICENSE` / `.gitignore`

---

## 版本号说明

`versionName` 格式为 `MAJOR.MINOR.BUILD_NUMBER`，当前主线为 `0.2.BUILD_NUMBER`；`versionCode` 等于 BUILD_NUMBER。
BUILD_NUMBER 存储在 `version.properties`，每次执行 `assemble` / `bundle` 任务时自动自增。
语义化主次版本号在项目跨阶段时手动提升（如 0.1 → 0.2）。

## 贡献

变更较大的功能请先在 Issues 中开 Discussion，小修直接提 PR。
提交信息遵循 Conventional Commits 风格（`feat:` / `fix:` / `refactor:` / `docs:` / `test:` / `build:`）。
