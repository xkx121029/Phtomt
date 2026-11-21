# 判据索引

这张表是给**维护者**用的，不进 README（README 面向用户，函数名对用户是噪音，还会因为改名多一处要同步）。

它解决一个具体的缺口：**行为判据改在代码里，而描述它的句子躺在 README 表格或 `docs/` 里，两处没有任何机械联系。**今天出过一次真实事故——`EngineRules` 的幂等判据改了实现，README 里描述它的那一行没人动，文档继续宣称一个已经不成立的行为。

## 维护约定

1. **改判据函数 → 查本表 → 更新「文档里描述它的位置」列出的每一处。**列出的位置全都要看，不是看完第一个就算。
2. **新增判据 → 加一行。**先加「（docs 未描述）」，等它有对用户可见的影响时再补文档位置。
3. **不确定就留空这行，宁可少列不可错列。**本表的价值来自每一条都能点开对得上；写错函数名或行号会把维护者导向不存在的锚点，比缺一行更坏。
4. **`文件:行号` 会漂，函数名才是稳定锚点。**行号以写入时的 `main` 为准，重命名函数时请顺手更新本表。
5. 第三列写「（docs 未描述）」**不是遗漏，是结论**：这个判据目前没有对用户可见的说明，改它时不需要同步文档。
6. 同一格里出现多个行号时，省略文件名的 `:NNN` 指代**本格前面最近的那个文件**。

## 判据表

按一次动作的流经顺序排列：入口 → 授权 → 确认 → 判据 → 执行 → 验证。

| 判据函数 | 它决定什么行为 | 文档里描述它的位置 |
|---|---|---|
| `TaskEntry.fromDeepLink` / `fromGoogleAction` / `fromShareIntent` / `fromExtras`（`app/src/main/java/com/phoneagent/feature/entry/TaskEntry.kt:49`、`:67`、`:93`、`:137`） | 四类外部入口各自解析成什么任务目标（任务文本 / 续跑 / 纯导航 / 分享文件），`Target` 形状见 `app/src/main/java/com/phoneagent/feature/entry/TaskEntry.kt:36-45` | `README.md:573-584`（入口一览）、`CHANGELOG.md:44` |
| `EntryTrust.isProvenInternal`（`feature/entry/EntryTrust.kt:34`） | 调用方 uid 能否证明这次请求来自本应用自己（API 34+ 且 callerUid == selfUid）；证明不了的**一律按外部处理** | `README.md:511`、`CHANGELOG.md:44` |
| `EntryTrust.needsUserConfirmation`（`feature/entry/EntryTrust.kt:49`） | 外部来源里哪些要先过确认：任务文本 / 续跑要确认，打开记忆页与分享文件不确认 | `README.md:511`、`CHANGELOG.md:44` |
| `ActionPolicy.allows`（`engine/execution/ActionMode.kt:119`） | 三档动作模式各放行哪些意图；**未知意图一律放行**，交给转译层按「未知意图」失败（不抢着报越权，误导 AI 去换档） | `README.md:309-313`（三档表） |
| `EngineRules.isFinalSubmit`（`domain/rules/EngineRules.kt:38`） | 这个动作有没有副作用、要不要进幂等保护（提交 / 发送 / 下单 / 支付 / 删除 / 发布） | `README.md:512` |
| `EngineRules.isIrreversible`（`domain/rules/EngineRules.kt:48`） | 这个动作做错了能不能收回（删除 / 支付 / 退款 / 购买 / 转账），决定「跳过」和「重放」都必须先问用户 | `README.md:509`、`CHANGELOG.md:39` |
| `EngineRules.targetLabelOf`（`domain/rules/EngineRules.kt:165`） | 幂等与不可逆判据拿哪个文字当目标标签：活节点文字 > 选择器 > 模型自报的 `target.value`（后者是最弱信号） | `README.md:512` |
| `EngineRules.idempotencyVerdict`（`domain/rules/EngineRules.kt:99`） | `Skip` / `NeedsConfirm` / `Proceed` 三态。跳过要求**本任务内确实下发过同一动作 + 页面仍是完成态**；页面文字单独不构成跳过理由，只触发一次确认 | `README.md:512`、`docs/作品说明-评委版.md:31` |
| `EngineRules.normalizeSwipeDirection`（`domain/rules/EngineRules.kt:240`） | 滑动方向归一化（`up` / `UP` / `向上` / `上滑`）；认不出来返回 `null`，调用方必须当失败——绝不能把「没方向」当成「原地不动」 | `README.md:398`（`su`/`sd`/`sl`/`sr` 的语义） |
| `EngineRules.authoredCommandOf`（`domain/rules/EngineRules.kt:287`） | 哪些动作算「AI 亲写命令、端侧原样执行」：`shell`（`aiAuthored`）+ `terminal_op op=exec`。判据不看 AI 自报标记，默认 fail-closed | `README.md:510`、`CHANGELOG.md:45` |
| `DangerousTargetWords.hit`（`domain/rules/DangerousTargetWords.kt:115`） | 目标标签是不是不可逆的：中文不可逆 / 批量抹除 / 提交三档 + 各自的语境豁免，再叠英文左词边界那半边；返回命中的词 | `CHANGELOG.md:40` |
| `DangerousTargetGate.check`（`engine/execution/DangerousTargetGate.kt:122`） | 派发前的最后一问，**三态**：看得见且安全→放行，看得见且命中→确认，**看不见→也确认**（不猜安全）。只对 `TAP`/`CLICK`/长按/滑动生效；滑动落点读不到时放行（滑动多半只是滚动） | `README.md:509`、`CHANGELOG.md:39` |
| `UserGate.awaitActionConfirmation`（`engine/runtime/UserGate.kt:153`） | 不可逆动作的确认等待：置起待确认状态、**120 秒超时视为取消**、同一页面同一动作 **60 秒内不重复问**，跨页面必重新问（去重键见 `app/src/main/java/com/phoneagent/engine/runtime/UserGate.kt:212`） | `README.md:509`、`CHANGELOG.md:43` |
| `IntentResolver.coordinateToPixel` / `endpointPoint`（`engine/execution/IntentResolver.kt:280`、`:272`） | 坐标串 → 像素的**唯一换算点**：只接受 0~1 比例，越界返回 `null` 由上层报「坐标非法」，不静默夹到屏幕边缘；`a11y_call` 的 `click`/`long_click` 走同一个函数 | `README.md:313`、`CHANGELOG.md:49` |
| `ProviderContract.judge` / `mismatchReason`（`device/provider/ProviderContract.kt:44`、`:57`） | 服务组件能不能调：只认 AIDL `apiVersion` **相等**，不按 versionName 排序；不相等时按方向给「更新组件」或「升级本应用」，并**明确拒绝调用**而不是让 binder 吞成 null | `CHANGELOG.md:51`、`docs/project-structure.md:107` |
| `LaunchLadderPolicy.verdict`（`engine/execution/LaunchLadderPolicy.kt:36`） | 一次启动尝试的三态：`true`=已确认（唯一能报成功的分支）、`null`=已交付未确认（不记成功、也不换通道重跑）、`false`=没交付（交给通道阶梯换下一级） | `README.md:86`、`CHANGELOG.md:52` |
| `VerifyRules.shouldRetry`（`engine/execution/VerifyRules.kt:57`） | 这一步要不要重试：只看结构化字段（`delivery` / `structural`），不看失败文案——改文案不会悄悄改变重试行为 | `README.md:86` |
| `VerifyRules.historyStatus` / `deviceOpsStatus`（`engine/execution/VerifyRules.kt:152`、`:168`） | 这一步在执行历史里记成什么状态（`verified_success` / `unverified` / `no_effect` / `failed`），以及系统直连操作的「成功 / 已下发读不到回执 / 没执行」三态 | `README.md:86`、`CHANGELOG.md:52` |
| `PageFingerprint.compute` / `computeMeaningful` / `computeEffect`（`engine/perception/PageFingerprint.kt:42`、`:48`、`:64`） | 页面指纹的三种口径：`compute` 判页面有没有推进、`computeMeaningful` 判语义变化、`computeEffect` 判动作有没有生效（**含控件状态**，开关类只能靠它）。不变式：`computeEffect` 看得到的变化，`compute` 必能看到 | `README.md:86`、`README.md:426`（复用条件） |
| `InputCommitRules.isCommitAction` / `nextPending`（`engine/execution/InputCommitRules.kt:49`、`:68`） | **「填了」不等于「交了」**：输入框里的字在提交前对页面没有意义，所以完成前会拦未提交的输入；导航类动作会让待提交输入作废，最多拒绝 2 次防死循环 | （docs 未描述；见 `app/src/main/java/com/phoneagent/engine/execution/InputCommitRules.kt:3-13` 的注释） |
| `CompletionEvidence.vanishedCitedEvidence`（`engine/execution/CompletionEvidence.kt:60`） | 完成说明里引用的那段文字要再观察一次：**瞬时提示消失就不算完成依据**，持久状态才放行（最多拒绝 1 次防死循环） | （docs 未描述；见 `app/src/main/java/com/phoneagent/engine/execution/CompletionEvidence.kt:1-13` 的注释） |

## 怎么用

- **动了判据层的代码**（`domain/rules`、`engine/execution`、`engine/runtime`、`feature/entry`、`device/provider`）：先在本表搜函数名，看它的行为有没有被文档描述过。
- **发现文档里某句话可疑**：反查这张表，找到它对应哪个判据，再去读那个函数——比顺着 README 猜「这句话是哪里保证的」快。
- **做文档审计时**：第三列是「（docs 未描述）」的行，就是下次补文档的候选清单。

## 不在本表的判据

本表**只收跨模块被引用、且行为对用户可见**的判据。以下情况有意不列：

- 纯展示层的格式化（`StepTexts`、`actionLabel`、各类提示文案拼装）——它们不判定行为，只决定措辞。
- 单点私有的守卫（某个 `private fun` 只在同一文件内用），行为边界由它所在的公开函数表达。
- 已被更上层判据完整覆盖的分支（例如 `DangerousTargetGate` 内部的 `labelsOf`，取值顺序由 `check` 这一行表达）。
