# HPA 系统级集成可发展项目清单

> 整理自两轮调研：系统级架构集成可能性 + 系统级 AI 助手联动点。
> 现状基线：无障碍（元素树/手势/API 30+ 截屏）、Shizuku 内嵌 server（仅 `sh -c`）、MediaProjection 截屏兜底、Termux RUN\_COMMAND 通道、悬浮窗/光标/页面标记三 Overlay、`assistant` 意图单向唤醒厂商助手并传话（无障碍传话 + shell 兜底）。
>
> **实施状态（2026-10-20）**：除「13. 远程下发任务」（用户指定删除）外，全部 14 项已落地，详见 CHANGELOG「Unreleased — 2026-10-20」。各条目标题后以 ✅ 标注。

***

## 一、入口类集成（低成本高价值，第一梯队）

### 1. TileService 快速设置磁贴 ✅

- 下拉通知栏一键唤起/收起悬浮窗、启停任务
- Manifest 目前无任何 TileService；悬浮窗与权限体系现成
- 缺口最明显的入口

### 2. App Shortcuts（长按图标） ✅

- 静态 + 动态 shortcut：「新建任务 / 继续上次任务 / 打开记忆页」
- 直接预填 MainViewModel 任务输入

### 3. 分享目标（ACTION\_SEND / ACTION\_PROCESS\_TEXT） ✅

- 任何 App 选中文字 →「问 Happy Agent」
- 分享链接 → 交给 Agent 走 browse\_\*（浏览器通道已有，零新增能力白赚入口）

### 4. 桌面 Widget ✅

- Glance (Compose) 显示当前任务状态/最近任务
- 数据源复用进度通知

### 5. 端侧语音输入闭环（联动点 4） ✅

- Agent 页输入框接 `SpeechRecognizer`（系统 RecognitionService，国产 ROM 内置，无需 GMS）
- 现状纯文字输入；加麦克风后「说话→任务」不经过任何外部助手
- 是数字助理注册的低成本替代：不抢助理槽位也能语音用

***

## 二、上下文感知类（喂给 AI 的感知维度）

### 6. NotificationListenerService ✅

- AI 感知目前只有元素树 + 截图，通知是盲区
- ① 通知内容注入决策上下文（验证码、消息提醒）
- ② 新增「读通知/清理通知」技能进 SkillCatalog
- ③ 任务执行期间可静默低优先级通知

### 7. UsageStatsManager ✅

- 前台应用感知交叉验证（现依赖无障碍窗口事件）
- 页面记忆补充「什么时候用过这个页面」的时间维度

### 8. 数字助理注册 / VoiceInteractionService（联动点 1，战略级） ✅

- `VoiceInteractionService` + `VoiceInteractionSessionService`，经 `RoleManager.ROLE_ASSISTANT` 请求角色（系统授权框）
- 三件事：
  - **电源键长按/边缘手势唤起 HPA**，悬浮窗面板可直接复用为 Session UI
  - **`onHandleAssist`** **免费感知**：系统主动给 `AssistStructure`（完整元素树，无需无障碍）、截图、`AssistContent`（网页标题/URL）；无障碍被杀时仍可用
  - **与现有 assistant 意图对等闭环**：现在是「HPA 叫别人」，注册后「系统叫 HPA」，任务上下文随系统带入，省感知冷启动
- 代价：占用默认助理槽位（小爱/小艺退出该槽，但仍有专属触发方式）；做成设置页开关并明示

### 9. Google App Actions / shortcuts.xml（联动点 5，海外路径） ✅

- `shortcuts.xml` 声明 capability（`actions.intent.GET_THING` 等）
- Google 助理支持「Hey Google, 用 Happy Agent 打开微信」直达
- 国内无 GMS 价值有限，海外版本收益大，伴生项

***

## 三、双向协议类（战略级）

### 10. Deep Link（`hpa://task?text=...`） ✅

- MainActivity 目前只有 MAIN/LAUNCHER
- 加深链后：website 分享任务卡片点开直达手机端、更新推送携带预填指令
- 与对外控制接口共用同一套 Normalized 意图归一化入口

### 11. 厂商助手反向承接（联动点 2，依赖 Deep Link） ✅

- **小爱同学「训练计划」/ 小艺技能**：用户配置自定义指令 → 厂商助手执行深链 → HPA 接收任务全文启动
- 目前唯一稳定的「厂商助手 → HPA」官方通道，不需要任何权限
- 悬浮窗已有排队模式，深链到达时正在跑任务可自动追加进队列

### 12. 对外控制接口（ContentProvider 或 AIDL） ✅

- 暴露「下发任务 / 查询任务状态」
- Tasker、MacroDroid、自动化工具甚至 coda 子工程都能驱动 HPA
- 外挂视觉 APK 的 IPC 经验现成（后已内并）

### 14. Shizuku 深化 ✅

- 目前只做 `sh -c`；binder 直连还能做：
  - `pm` 多用户管理
  - `appops` 策略读写
  - 授予运行时权限
  - `dumpsys` 结构化读取
- 自由模式档位直接受益

### 15. Shizuku 助理槽位检测/切换（联动点 3） ✅

- `WRITE_SECURE_SETTINGS` 已持有（Manifest L26）
- `settings get secure assistant` / `voice_interaction_service` 精确检测当前默认助理，`VoiceAssistantDetector` 多一条依据（现在靠 queryIntentActivities 猜）
- 写入切换可做，但**必须经设置页明示开关**，AI 擅自执行不可（不可逆词表应收录）

***

## 四、既有单向链路的定位说明

现有 `assistant` 意图链路（唤醒厂商助手 + 无障碍传话 + shell 兜底）是**单向且脆弱**的（传话依赖「找输入框→填字→点发送」，厂商助手改版即断）。定位：保留为兜底通道；联动点 8（数字助理注册）+ 11（深链承接）补齐反向后形成完整闭环。

***

## 五、建议实施顺序

| 顺位 | 项目                                        | 理由                                  |
| -- | ----------------------------------------- | ----------------------------------- |
| 1  | 端侧语音输入（#5）                                | 半天量级，零权限冲突，体验立现                     |
| 2  | TileService + 分享目标 + Deep Link（#1 #3 #10） | 入口层改动一次出版本                          |
| 3  | NotificationListenerService（#6）           | 涉及 SkillCatalog 与上下文注入链路，单独一批       |
| 4  | 数字助理注册（#8）                                | 战略级：电源键入口 + AssistStructure 感知 + 闭环 |
| 5  | App Shortcuts + Widget（#2 #4）             | 顺势补齐入口                              |
| 6  | Shizuku 深化（#14 #15）                       | 跟随自由模式档位迭代                          |
| 7  | 对外控制接口 + 远程下发（#12 #13）                    | v3 立项，产品形态升级                        |
| 8  | UsageStats / App Actions（#7 #9）           | 伴生项，随海外化与感知迭代推进                     |

