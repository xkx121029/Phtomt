# 判据索引

这张表是给**维护者**用的，不进 README（README 面向用户，函数名对用户是噪音，还会因为改名多一处要同步）。

它解决一个具体的缺口：**行为判据改在代码里，而描述它的句子躺在 README 表格或 `docs/` 里，两处没有任何机械联系。**今天出过一次真实事故——`EngineRules` 的幂等判据改了实现，README 里描述它的那一行没人动，文档继续宣称一个已经不成立的行为。

## 维护约定

1. **改判据函数 → 查本表 → 更新「文档里描述它的位置」列出的每一处。**列出的位置全都要看，不是看完第一个就算。
2. **新增判据 → 加一行。**先加「（docs 未描述）」，等它有对用户可见的影响时再补文档位置。
3. **不确定就留空这行，宁可少列不可错列。**本表的价值来自每一条都能点开对得上；写错函数名或行号会把维护者导向不存在的锚点，比缺一行更坏。
4. **`文件:行号` 会漂，函数名才是稳定锚点。**行号以写入时的 `main` 为准，重命名函数时请顺手更新本表。
5. 第三列写「（docs 未描述）」**不是遗漏，是结论**：这个判据目前没有对用户可见的说明，改它时不需要同步文档。
6. 同一格里出现多个行号时，省略文件名的 `:NNN` 指代**本格前面最近的那个文件**。路径一律写仓库相对全路径（`app/src/main/java/com/phoneagent/...`），不要写模块相对的 `domain/rules/...`。
7. **这张表由 `./gradlew :app:checkDocAnchors` 校验**（已挂在 `check` 上，CI 会跑）：文件不存在、行号越界、或该行找不到表里写的符号名，都会让构建变红。所以表不会悄悄过期——它自己会喊疼。

## 判据表

按一次动作的流经顺序排列：入口 → 授权 → 确认 → 判据 → 执行 → 验证。

| 判据函数 | 它决定什么行为 | 文档里描述它的位置 |
|---|---|---|
| `TaskEntry.fromDeepLink` / `fromGoogleAction` / `fromShareIntent` / `fromExtras`（`app/src/main/java/com/phoneagent/feature/entry/TaskEntry.kt:49`、`:67`、`:93`、`:137`） | 四类外部入口各自解析成什么任务目标（任务文本 / 续跑 / 纯导航 / 分享文件），`Target` 形状见 `app/src/main/java/com/phoneagent/feature/entry/TaskEntry.kt:36-45` | `README.md:573-584`（入口一览）、`CHANGELOG.md:44` |
| `EntryTrust.isProvenInternal`（`app/src/main/java/com/phoneagent/feature/entry/EntryTrust.kt:34`） | 调用方 uid 能否证明这次请求来自本应用自己（API 34+ 且 callerUid == selfUid）；证明不了的**一律按外部处理** | `README.md:511`、`CHANGELOG.md:44` |
| `EntryTrust.needsUserConfirmation`（`app/src/main/java/com/phoneagent/feature/entry/EntryTrust.kt:49`） | 外部来源里哪些要先过确认：任务文本 / 续跑要确认，打开记忆页与分享文件不确认 | `README.md:511`、`CHANGELOG.md:44` |
| `ActionPolicy.allows`（`app/src/main/java/com/phoneagent/engine/execution/ActionMode.kt:119`） | 三档动作模式各放行哪些意图；**未知意图一律放行**，交给转译层按「未知意图」失败（不抢着报越权，误导 AI 去换档） | `README.md:309-313`（三档表） |
| `EngineRules.isFinalSubmit`（`app/src/main/java/com/phoneagent/domain/rules/EngineRules.kt:38`） | 这个动作有没有副作用、要不要进幂等保护（提交 / 发送 / 下单 / 支付 / 删除 / 发布） | `README.md:512` |
| `EngineRules.isIrreversible`（`app/src/main/java/com/phoneagent/domain/rules/EngineRules.kt:48`） | 这个动作做错了能不能收回（删除 / 支付 / 退款 / 购买 / 转账），决定「跳过」和「重放」都必须先问用户 | `README.md:509`、`CHANGELOG.md:39` |
| `EngineRules.targetLabelOf`（`app/src/main/java/com/phoneagent/domain/rules/EngineRules.kt:165`） | 幂等与不可逆判据拿哪个文字当目标标签：活节点文字 > 选择器 > 模型自报的 `target.value`（后者是最弱信号） | `README.md:512` |
| `EngineRules.idempotencyVerdict`（`app/src/main/java/com/phoneagent/domain/rules/EngineRules.kt:99`） | `Skip` / `NeedsConfirm` / `Proceed` 三态。跳过要求**本任务内确实下发过同一动作 + 页面仍是完成态**；页面文字单独不构成跳过理由，只触发一次确认 | `README.md:512`、`docs/作品说明-评委版.md:31` |
| `EngineRules.normalizeSwipeDirection`（`app/src/main/java/com/phoneagent/domain/rules/EngineRules.kt:240`） | 滑动方向归一化（`up` / `UP` / `向上` / `上滑`）；认不出来返回 `null`，调用方必须当失败——绝不能把「没方向」当成「原地不动」 | `README.md:398`（`su`/`sd`/`sl`/`sr` 的语义） |
| `EngineRules.authoredCommandOf`（`app/src/main/java/com/phoneagent/domain/rules/EngineRules.kt:287`） | 哪些动作算「AI 亲写命令、端侧原样执行」：`shell`（`aiAuthored`）+ `terminal_op op=exec`。判据不看 AI 自报标记，默认 fail-closed | `README.md:510`、`CHANGELOG.md:45` |
| `DangerousTargetWords.hit`（`app/src/main/java/com/phoneagent/domain/rules/DangerousTargetWords.kt:115`） | 目标标签是不是不可逆的：中文不可逆 / 批量抹除 / 提交三档 + 各自的语境豁免，再叠英文左词边界那半边；返回命中的词 | `CHANGELOG.md:40` |
| `DangerousTargetGate.check`（`app/src/main/java/com/phoneagent/engine/execution/DangerousTargetGate.kt:122`） | 派发前的最后一问，**三态**：看得见且安全→放行，看得见且命中→确认，**看不见→也确认**（不猜安全）。只对 `TAP`/`CLICK`/长按/滑动生效；滑动落点读不到时放行（滑动多半只是滚动） | `README.md:509`、`CHANGELOG.md:39` |
| `UserGate.awaitActionConfirmation`（`app/src/main/java/com/phoneagent/engine/runtime/UserGate.kt:153`） | 不可逆动作的确认等待：置起待确认状态、**120 秒超时视为取消**、同一页面同一动作 **60 秒内不重复问**，跨页面必重新问（去重键见 `app/src/main/java/com/phoneagent/engine/runtime/UserGate.kt:212`） | `README.md:509`、`CHANGELOG.md:43` |
| `IntentResolver.coordinateToPixel` / `endpointPoint`（`app/src/main/java/com/phoneagent/engine/execution/IntentResolver.kt:280`、`:272`） | 坐标串 → 像素的**唯一换算点**：只接受 0~1 比例，越界返回 `null` 由上层报「坐标非法」，不静默夹到屏幕边缘；`a11y_call` 的 `click`/`long_click` 走同一个函数 | `README.md:313`、`CHANGELOG.md:49` |
| `ProviderContract.judge` / `mismatchReason`（`app/src/main/java/com/phoneagent/device/provider/ProviderContract.kt:44`、`:57`） | 服务组件能不能调：只认 AIDL `apiVersion` **相等**，不按 versionName 排序；不相等时按方向给「更新组件」或「升级本应用」，并**明确拒绝调用**而不是让 binder 吞成 null | `CHANGELOG.md:51`、`docs/project-structure.md:107` |
| `LaunchLadderPolicy.verdict`（`app/src/main/java/com/phoneagent/engine/execution/LaunchLadderPolicy.kt:36`） | 一次启动尝试的三态：`true`=已确认（唯一能报成功的分支）、`null`=已交付未确认（不记成功、也不换通道重跑）、`false`=没交付（交给通道阶梯换下一级） | `README.md:86`、`CHANGELOG.md:52` |
| `VerifyRules.shouldRetry`（`app/src/main/java/com/phoneagent/engine/execution/VerifyRules.kt:57`） | 这一步要不要重试：只看结构化字段（`delivery` / `structural`），不看失败文案——改文案不会悄悄改变重试行为 | `README.md:86` |
| `VerifyRules.historyStatus` / `deviceOpsStatus`（`app/src/main/java/com/phoneagent/engine/execution/VerifyRules.kt:152`、`:168`） | 这一步在执行历史里记成什么状态（`verified_success` / `unverified` / `no_effect` / `failed`），以及系统直连操作的「成功 / 已下发读不到回执 / 没执行」三态 | `README.md:86`、`CHANGELOG.md:52` |
| `PageFingerprint.compute` / `computeMeaningful` / `computeEffect`（`app/src/main/java/com/phoneagent/engine/perception/PageFingerprint.kt:42`、`:48`、`:64`） | 页面指纹的三种口径：`compute` 判页面有没有推进、`computeMeaningful` 判语义变化、`computeEffect` 判动作有没有生效（**含控件状态**，开关类只能靠它）。不变式：`computeEffect` 看得到的变化，`compute` 必能看到 | `README.md:86`、`README.md:426`（复用条件） |
| `LocalDecisionEngine.decide`（`app/src/main/java/com/phoneagent/domain/rules/LocalDecisionEngine.kt:65`） | 空元素树时先等一拍，而不是立刻升级到完整视觉链：最多连等 3 拍（每拍 1.2s 的 `wait`，不触任何控件），仍为空才交还云端；计数只在读到非空元素树或新任务开始时清零，所以从不暴露元素的页面（WebView/自绘/游戏）只在每串空树开头多等几拍，不会循环空转 | （docs 未描述） |
| `VisionRouting.needsFreshOnDeviceDetect`（`app/src/main/java/com/phoneagent/core/ai/VisionRouting.kt:116`） | 视觉定位要不要再跑一次端侧识别：端侧「定位」门面内部是「重识别 + 匹配」，若本步控件表就是用当前这一帧识别出来的，重跑得到同一份表、白付一次 20s 级推理，此时直接落到云端定位 | （docs 未描述） |
| `VisionWarmup.shouldStart`（`app/src/main/java/com/phoneagent/device/vision/VisionWarmup.kt:25`） | 端侧 3B 什么时候预热：只有「本步路由确实要用端侧 3B」才开始，同一任务只启动一次、已加载则跳过——用不到 3B 的任务一次都不加载 | （docs 未描述） |
| `InputCommitRules.isCommitAction` / `nextPending`（`app/src/main/java/com/phoneagent/engine/execution/InputCommitRules.kt:49`、`:68`） | **「填了」不等于「交了」**：输入框里的字在提交前对页面没有意义，所以完成前会拦未提交的输入；导航类动作会让待提交输入作废，最多拒绝 2 次防死循环 | （docs 未描述；见 `app/src/main/java/com/phoneagent/engine/execution/InputCommitRules.kt:3-13` 的注释） |
| `CompletionEvidence.vanishedCitedEvidence`（`app/src/main/java/com/phoneagent/engine/execution/CompletionEvidence.kt:60`） | 完成说明里引用的那段文字要再观察一次：**瞬时提示消失就不算完成依据**，持久状态才放行（最多拒绝 1 次防死循环） | （docs 未描述；见 `app/src/main/java/com/phoneagent/engine/execution/CompletionEvidence.kt:1-13` 的注释） |

| `ModelTrust.resolve`（`app/src/main/java/com/phoneagent/core/ai/ModelTrust.kt:65`） | 主模型处于四态里的哪一态（未配置 / 未验证 / 已验证 / 验证失败）。判据是「配置指纹 + 验证时刻 + 验证结果」三件事：指纹对不上（改了 Key / URL / 模型名）就退回未验证，**未验证与失败是两种处置**——失败拦在起跑前，未验证只提醒一次 | （docs 未描述；`CHANGELOG.md` 今日 UX 条目） |
| `AppSettings.Settings.configProblem`（`app/src/main/java/com/phoneagent/data/prefs/AppSettings.kt:324`） | 当前配置能不能拿去发请求：缺 Key / URL 非法 / http 端点未放行 / **主模型上次连接测试失败**，都从这里出一句可直接照做的话；执行侧与规划侧共用同一份口径 | （docs 未描述；`CHANGELOG.md` 今日 UX 条目） |
| `AgentPreflight.ask`（`app/src/main/java/com/phoneagent/ui/agent/AgentPreflight.kt:41`） | 起跑前要不要先问一句「无障碍没开，只能靠截图猜位置，确定吗」：**无障碍不可用、且本轮没选过截图模式，就必须问一次**；模型配置问题（`configProblem`）不参与这条判断，只决定对话框里多说的一句 | （docs 未描述；见 `app/src/main/java/com/phoneagent/ui/agent/AgentEmptyState.kt:226` 的能力边界文案） |
| `AgentTerminal.headline` / `cause`（`app/src/main/java/com/phoneagent/ui/agent/AgentTerminal.kt:20`、`:48`） | 终态卡的标题与可行动原因各自怎么说：失败原因里**配置缺失排在无障碍未开之前**（这一轮根本没起跑，出口是「去配置」）；`headline` 与 `cause` 必须同序，否则卡片会一句话指向两个出口 | （docs 未描述） |
| `AgentTimelineMap.terminal`（`app/src/main/java/com/phoneagent/ui/agent/AgentTimelineMap.kt:506`） | 这次任务到终态时产出哪一张终态卡：成功 / 用户停止 / 失败三种，失败再按原因分档（能力缺失 / 配置问题 / 决策异常），决定用户看到的是「去修配置」还是「去开权限」 | （docs 未描述；`CHANGELOG.md` 今日失败恢复条目） |
| `DeviceWorkspace.resolveWithin`（`app/src/main/java/com/phoneagent/coding/device/DeviceWorkspace.kt:50`） | 工作区以内的路径与 workspace 之外的文件读写的边界：**先用 `Path.toRealPath()` 归一真实路径、再比真实前缀**（Windows 的 junction 只解析真实路径才拦得住），越界一律拒绝 | （docs 未描述；见该文件 `:38-44` 的注释） |
| `HostBridgeAuth.allows`（`app/src/main/java/com/phoneagent/coding/device/HostBridgeAuth.kt:22`） | 宿主桥的 RPC 能不能进：只认与本地生成的 token 相等，**密钥为空时一律拒绝**（fail-closed），不做「没配就不校验」的兜底 | （docs 未描述；见 `app/src/main/java/com/phoneagent/coding/device/HostBridgeTokenProvider.kt:23` 的注释） |
| `AgentClient` 审批字段（`approvalDanger`）（`app/src/main/java/com/phoneagent/coding/agent/AgentClient.kt:43`） | 审批卡在用户点「允许」**之前**就要带上危险原因：`danger` / `why` 随 `approval_request` 一起下发，等 `tool_approval` 再带就晚了；丢了这个字段，卡上只剩一条命令 | （docs 未描述；`CHANGELOG.md` 今日审批卡条目） |

## 怎么用

- **动了判据层的代码**（`domain/rules`、`engine/execution`、`engine/runtime`、`feature/entry`、`device/provider`）：先在本表搜函数名，看它的行为有没有被文档描述过。
- **发现文档里某句话可疑**：反查这张表，找到它对应哪个判据，再去读那个函数——比顺着 README 猜「这句话是哪里保证的」快。
- **做文档审计时**：第三列是「（docs 未描述）」的行，就是下次补文档的候选清单。

## 不在本表的判据

本表**只收跨模块被引用、且行为对用户可见**的判据。以下情况有意不列：

- 纯展示层的格式化（`StepTexts`、`actionLabel`、各类提示文案拼装）——它们不判定行为，只决定措辞。
- 单点私有的守卫（某个 `private fun` 只在同一文件内用），行为边界由它所在的公开函数表达。
- 已被更上层判据完整覆盖的分支（例如 `DangerousTargetGate` 内部的 `labelsOf`，取值顺序由 `check` 这一行表达）。
