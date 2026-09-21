/* 由 scripts/snapshot.mjs 生成于 2026-09-20T14:38:06.176Z，请勿手工编辑。
   静态导出（GitHub Pages）用这份快照替代后端 API。 */
export const snapshot = {
  "builtAt": "2026-09-20T14:38:06.176Z",
  "static": true,
  "site": {
    "name": "Happy Phone Agent",
    "shortName": "Happy Agent",
    "slug": "phtomt",
    "tagline": "让手机自己动手",
    "description": "Happy Phone Agent 是一款运行在 Android 上的 AI 智能体。你用一句自然语言描述目标，它自己观察屏幕、拆解步骤、动手执行——点按、滑动、输入、跳转，全程可见可接管。",
    "version": "0.1.384",
    "buildNumber": 384,
    "updatedAt": "2026-09-20T13:40:27.690Z",
    "links": {
      "github": "https://github.com/xkx121029/Phtomt",
      "gitee": "https://gitee.com/xkx1029/Phtomt",
      "issues": "https://github.com/xkx121029/Phtomt/issues",
      "releases": "https://github.com/xkx121029/Phtomt/releases",
      "changelog": "https://github.com/xkx121029/Phtomt/blob/main/CHANGELOG.md"
    },
    "requirements": {
      "minAndroid": "8.0",
      "minApi": 26,
      "targetApi": 35,
      "arch": "arm64-v8a / armeabi-v7a",
      "packageName": "com.phoneagent"
    },
    "techStack": [
      {
        "name": "Kotlin",
        "version": "2.0.21"
      },
      {
        "name": "Jetpack Compose",
        "version": "2024.12.01"
      },
      {
        "name": "Material 3",
        "version": "1.3.1"
      },
      {
        "name": "Koin",
        "version": "4.0.0"
      },
      {
        "name": "OkHttp",
        "version": "4.12.0"
      },
      {
        "name": "DataStore",
        "version": "1.1.1"
      }
    ],
    "channels": [
      {
        "id": "accessibility",
        "name": "无障碍通道",
        "level": "普通应用权限",
        "desc": "通过 AccessibilityService 读取控件树并执行点击、滑动、输入，兼容性最好，无需额外安装。",
        "default": true
      },
      {
        "id": "adb",
        "name": "无线 ADB 通道",
        "level": "shell 权限",
        "desc": "手机自带无线调试，配对后即可执行 am / pm / settings 等系统命令，应用启动走真实包名而非模拟点按。",
        "default": false
      },
      {
        "id": "shizuku",
        "name": "Shizuku 通道",
        "level": "shell 权限",
        "desc": "可选增强，借助 Shizuku 拿到 ADB 级权限；未安装或授权失败不影响无线 ADB 主通道。",
        "default": false
      },
      {
        "id": "termux",
        "name": "Termux 通道",
        "level": "普通应用权限",
        "desc": "把命令交给 Termux 执行并回读输出，为「图形界面做不到」的取数场景补一条 Linux 工具链。",
        "default": false
      }
    ],
    "latest": {
      "version": "v0.1.384",
      "build": 384,
      "channel": "stable",
      "date": "2026-09-20",
      "published": true,
      "title": "当前开发版",
      "summary": "AI 此前是在\"真空\"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住 上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。 本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、 **会话承接**（上一轮任务的目标与结论）。",
      "apk": {
        "file": "HappyAgent-0.1.384.apk",
        "size": 14959209,
        "sizeText": "14 MB",
        "sha256": "b486f578d860219bd11a9ff969360f55446524dbd584bf7fb83bd376a96ffb23"
      },
      "notes": [
        "Debug 构建，用于内测与自用",
        "首次安装需手动授予无障碍、悬浮窗与通知权限"
      ],
      "downloads": 2
    },
    "stats": {
      "totalDownloads": 2,
      "lastDownloadAt": "2026-09-20T14:15:16.573Z",
      "byVersion": {
        "v0.1.384": 2
      },
      "recent": [
        {
          "day": "2026-09-20",
          "count": 2
        }
      ],
      "apkTotalSize": 14959209,
      "apkTotalSizeText": "14 MB"
    }
  },
  "features": [
    {
      "id": "decision",
      "order": 1,
      "tag": "决策",
      "title": "AI 驱动决策",
      "summary": "ReAct 主循环：观察 → 规划 → 执行 → 校验，每一步都基于当前真实页面。",
      "detail": "接入任意 OpenAI 兼容端点（自建 Ollama / LM Studio / one-api 都行）。规划时步数动态收敛到 3~8 步，执行中每一步都以当前页面元素树为唯一依据——目标控件不在当前页面就不允许点击，找不到先滚动查找，仍找不到则中止并说明原因。"
    },
    {
      "id": "intent",
      "order": 2,
      "tag": "执行",
      "title": "15 个高层语义意图",
      "summary": "AI 只说「做什么」，端侧转译层负责「怎么做」，模型永远不输出坐标和命令。",
      "detail": "back / home / refresh / search / send / confirm / close / share / collect / copy / delete / download / add / switch / clear_input 全部由 IntentTranslator 实时转译为端侧动作。有精确 id/label 就用它，只有图片、图表这类元素树里没有的控件才退到坐标。"
    },
    {
      "id": "vision",
      "order": 3,
      "tag": "感知",
      "title": "双引擎视觉理解",
      "summary": "元素树可读时走端侧，稀疏时自动切云端视觉模型补位。",
      "detail": "无障碍能读到的控件（元素数 > 3）视为简单页面，优先端侧处理；元素树稀疏（≤ 3）视为复杂页面，自动启用云端视觉模型做截图描述与坐标定位，即使截图开关是关的也会兜底。"
    },
    {
      "id": "chain",
      "order": 4,
      "tag": "决策",
      "title": "多模型链路聚合",
      "summary": "主模型决策 + 视觉模型定位 + 思考模型规划，按需组合，默认关闭。",
      "detail": "默认只跑主模型（加可选视觉模型），省额度、省延迟。开启链路聚合后，思考模型负责复杂规划、歧义检测与重规划，视觉模型只负责截图描述与坐标定位。三者的地址、模型名、密钥都在设置页图形化配置。"
    },
    {
      "id": "channel",
      "order": 5,
      "tag": "执行",
      "title": "四通道执行体系",
      "summary": "无线 ADB 为主，Shizuku 可选，Termux 补命令行，无障碍兜底。",
      "detail": "AUTO 模式下按「无线 ADB → Shizuku → Termux」降级。无线 ADB 连接成功即就绪，可直接执行 shell；Shizuku 失败或超时不阻塞主流程。AI 给的裸包名会被自动补全为 launch action，不会因为少写参数而空转。"
    },
    {
      "id": "shell",
      "order": 6,
      "tag": "执行",
      "title": "AI 友好命令解析",
      "summary": "tap / sw / key / text 这类短命令，坐标支持像素、比例、百分比三种写法。",
      "detail": "ShellCommands 把 AI 的自然短命令翻译成真实 ADB 命令，模型不用记 ADB 语法。命令输出会被截取（最多 1200 字）回灌到下一轮决策上下文里，让 AI 看得到自己刚刚做了什么。"
    },
    {
      "id": "safety",
      "order": 7,
      "tag": "安全",
      "title": "安全防护与脱敏",
      "summary": "敏感页面自动只读，手机号 / 身份证 / 银行卡号自动遮蔽。",
      "detail": "EngineRules 作为硬约束规则引擎，在动作真正下发前拦截危险操作；SensitivePageDetector 识别支付、个人信息等页面并切换为只读横切；DataSanitizer 对 11 位手机号、18 位身份证号、银行卡号做脱敏后再进模型上下文。"
    },
    {
      "id": "verify",
      "order": 8,
      "tag": "执行",
      "title": "页面指纹验证",
      "summary": "动作执行前后比对控件特征，确认「点下去了」还是「点空了」。",
      "detail": "VerifiedClickExecutor 在点击前后各取一次页面指纹，不一致才算生效。这一步把「AI 以为点了」和「页面真的变了」区分开，是长线任务不跑偏的关键。"
    },
    {
      "id": "local",
      "order": 9,
      "tag": "性能",
      "title": "端侧快速决策",
      "summary": "弹窗、加载、广告这类高频场景本地直接处理，不花云端额度。",
      "detail": "LocalDecisionEngine 覆盖高频重复场景，命中即本地决策，省掉一次往返。配合 AdSkipperCore 的冷却机制，既不会漏跳也不会反复戳同一个按钮。"
    },
    {
      "id": "adskip",
      "order": 10,
      "tag": "能力",
      "title": "内置跳广告",
      "summary": "独立于 Agent 运行：青少年模式弹窗 > 跳过按钮 > 倒计时角标 > 关闭按钮。",
      "detail": "AdSkipperCore 按优先级识别四类广告形态，配合精确匹配、位置约束与控件类型约束降低误触，内置冷却防止重复点击死循环。"
    },
    {
      "id": "overlay",
      "order": 11,
      "tag": "交互",
      "title": "液态玻璃悬浮窗",
      "summary": "半透明白玻璃，宽 300dp，高度随内容自适应，跑马灯贴屏幕顶边。",
      "detail": "执行时只占「跑马灯 + 标题 + 状态」三行，AI 详情默认折叠，展开可看「发送给 AI / AI 返回 / 审核结论」三段原文。截图时自动隐藏、截完立刻恢复，全过程 ≤ 0.3 秒。任务完成显示打勾动画并停在屏幕上，不自动消失。"
    },
    {
      "id": "takeover",
      "order": 12,
      "tag": "交互",
      "title": "随时接管与纠偏",
      "summary": "AI 拿不准会停下来问，你也能随时插话改方向。",
      "detail": "遇到歧义时悬浮窗弹出澄清选项；执行中可随时输入补充指令，会被追加进任务记忆的「用户要求」而不是覆盖原始目标。「已手动处理」按钮让人工接管后任务继续，而不是直接退出。"
    },
    {
      "id": "memory",
      "order": 13,
      "tag": "记忆",
      "title": "任务记忆",
      "summary": "记住目标、用户要求、已验证方法与进度，长线任务不丢上下文。",
      "detail": "任务记忆独立持久化，按 taskId 归属判定，避免旧任务的收尾逻辑误标新任务。用户中途的每一条补充要求按精确去重后追加保留，不会因为语义相近被合并掉。"
    },
    {
      "id": "queue",
      "order": 14,
      "tag": "任务",
      "title": "多任务队列与检查点",
      "summary": "顺序执行多个任务，随时取消、重规划，模板库沉淀可复用流程。",
      "detail": "TaskStore 保存检查点与任务模板库；TemplateMatcher 命中模板时直接复用验证过的路径。模板必须由软件预置或经用户明确确认后才入库，AI 不能自行创建模板。"
    },
    {
      "id": "skill",
      "order": 15,
      "tag": "扩展",
      "title": "技能与 MCP",
      "summary": "内置技能目录 + 远程 MCP 工具，参数校验与失败原因都是中文。",
      "detail": "SkillCatalog 与 IntentType.ALL 一一对应，被禁用的技能在归一化阶段直接拒绝。MCP 技能调用 20 秒超时、输出截断 1200 字，连续 3 次被拒即停止任务，避免无限循环。"
    },
    {
      "id": "doc",
      "order": 16,
      "tag": "输出",
      "title": "文档结果直出",
      "summary": "需要写文档的任务直接产出结果页，不在屏幕上假装打字。",
      "detail": "write_doc 意图由 DocumentEngine 落盘到应用私有目录，并推给 Agent 页内嵌 Markdown 预览（可展开 / 收起 / 关闭）。提示词里有铁律约束：文档类任务必须直接输出 write_doc，不许打开备忘录打字或用 shell 写文件。"
    },
    {
      "id": "i18n",
      "order": 17,
      "tag": "体验",
      "title": "中英双语与自动翻译",
      "summary": "提示词可手动切换中英；AI 的英文输出自动翻成中文再展示。",
      "detail": "AI 回复若以非中文为主（中文字符占比低于 30%）会自动翻译后再展示，翻译失败则回退原文。翻译结果带缓存去重，不会为同一句话重复付费。"
    },
    {
      "id": "design",
      "order": 18,
      "tag": "体验",
      "title": "Material 3 Expressive",
      "summary": "玄青 · 流萤配色，深色模式、高对比度无障碍、边缘光效全适配。",
      "detail": "语义颜色令牌集中管理，浅色 / 深色 / 高对比度四套主题；入场动画统一为自下而上淡入位移，全局触觉反馈，动效曲线统一收敛在 Motion 模块。"
    }
  ],
  "releases": [
    {
      "version": "v0.1.384",
      "build": 384,
      "channel": "stable",
      "date": "2026-09-20",
      "published": true,
      "title": "当前开发版",
      "summary": "AI 此前是在\"真空\"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住 上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。 本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、 **会话承接**（上一轮任务的目标与结论）。",
      "apk": {
        "file": "HappyAgent-0.1.384.apk",
        "size": 14959209,
        "sizeText": "14 MB",
        "sha256": "b486f578d860219bd11a9ff969360f55446524dbd584bf7fb83bd376a96ffb23"
      },
      "notes": [
        "Debug 构建，用于内测与自用",
        "首次安装需手动授予无障碍、悬浮窗与通知权限"
      ],
      "downloads": 2
    }
  ],
  "changelog": [
    {
      "version": "v0.1.334",
      "date": "2026-09-20",
      "summary": "AI 此前是在\"真空\"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住 上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。 本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、 **会话承接**（上一轮任务的目标与结论）。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**环境上下文默认注入**（`AgentPrompts.environment`）：每次规划与每步决策都带上端侧实时采集的事实—— 当前时间（`yyyy-MM-dd 周几 HH:mm`）、前台应用（`应用名(包名)`）、网络状态、电量（含是否充电）、已安装可启动应用数；缺项整行省略，不出现空标签",
            "**`device_query` 技能：本机信息按需查询**（`IntentType.DEVICE_QUERY` → `ActionType.DEVICE_QUERY`）- 参数 `kind = apps|time|battery|network|storage|all`，`filter` 仅 `kind=apps` 时生效（如「相机」）- 纯本地读取、不触碰设备：只读模式下同样放行（与 remember/wait 同列）- 查询结果作为「上一步结果」回注下一轮决策（截断 1500 字符），AI 拿到事实再决定下一步- `kind` 非法直接失败并列出可选值，不会拿着无效参数空转- **应用清单不给全量**：环境上下文只给个数，要清单必须走 device_query，避免每步都往提示词里塞一长串包名",
            "**多轮对话承接**（`SessionContext` + `AgentPrompts.sessionContext`）- 判定追问：含「接着/刚才/这个/改成/换成…」等强指代词直接认定；弱承接词「再」只在短句（≤12 字）里认定，避免把「打开微信」这类自带完整目标的短指令误判为追问- 注入最近 3 条已完成任务（目标 + 状态 + 结论，按时间倒序，进行中的不取——那条正是当前任务自己）- 是追问时明确写「必须以『上一轮任务』为目标主体」；不是追问时提示按相关性参考、无关就独立执行",
            "**任务结论落库**（`TaskMemoryEntry.conclusion`）：任务成功收尾时把完成摘要写进结论，作为下一轮承接的\"上次结果\"；结论为空则回退取最后一条完成方法"
          ]
        },
        {
          "type": "优化",
          "items": [
            "**性能**：已安装应用数每任务只查一次 `PackageManager`；会话承接块每任务只构建一次，随任务开始、规划、执行入口三处作废缓存，避免串轮",
            "**提示词双语同步**：中英两版系统提示词都补上 device_query 意图表行、独占路由规则（需要本机事实用 device_query，不要翻设置页）与决策阶段的「本机信息提醒」"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 `SessionContextTest`（4 例）：强指代词追问、弱词「再」仅在短句成立、自带完整目标不误判、空白输入",
            "新增 `AgentPromptsContextTest`（6 例）：中/英环境上下文内容、缺项不渲染空标签、无历史不注入承接、追问强调承接、非追问提示独立",
            "`SkillCompatTest` 增 2 例：按技能名调用回填 kind/filter、标准意图名原样放行",
            "`IntentTranslatorStrategyTest` 增 4 例：转译字段、缺 kind 默认 all、非法 kind 失败、只读模式放行"
          ]
        }
      ]
    },
    {
      "version": "v0.1.334",
      "date": "2026-09-20",
      "summary": "本轮只做加固、不改产品行为：把四类会让任务「卡死」或「状态残留」的缺陷堵掉—— 异常穿透、协作事件丢失、队列竞态、主循环单点故障。任务该怎么做还是怎么做， 区别在于出问题时它降级、重试、请求用户介入，而不是无声停摆。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**异常不再穿透整个任务**（`AgentEngine.run`）- `runInner` 里的未捕获异常此前会顺着协程传到 `processQueue`，把队列 worker 一起带走：界面永久停在「运行中」，之后排队的任务也不再执行。现由 `run()` 统一收敛，并按 taskId 兜底复位运行状态- `approvePlan()` 原先用 `runCatching{ run() }.onFailure{}` 包住任务启动，会把`CancellationException` 一并吞掉 —— 「停止」按钮再也打不断已批准的计划，而且「用户停止」还会被误报成「执行异常」",
            "**兜底复位加归属守卫**（`EngineRules.shouldFallbackReset`）- `stop()` 取消协程后收尾是异步跑的：用户立刻发起新任务时，旧任务的收尾会把新任务的`agentRunning` 置 false、状态打回空闲，还会关掉新任务的悬浮窗。现按 taskId + 终态双重守卫",
            "**多任务队列改原子操作**（`PendingTaskQueue`）- 原先是对 `MutableStateFlow` 做 `_taskQueue.value + task` 的非原子「读—改—写」，并发提交时后写的会覆盖先写的，任务被静默吞掉；worker 启动与退出之间还存在「入队方既看不到活着的 worker、也看不到非空队列」的窗口，任务就此漏跑- 现由独立锁保证原子性，退出时以 `job === self` 只清自己的引用，避免误清入队方刚启动的新 worker 导致双 worker 重复执行同一任务",
            "**用户协作事件不再丢失**（AgentEngine 协作信箱）- 「敏感页保护 / 动作连续失败」的原因此前走 `MutableSharedFlow.tryEmit`，无订阅者时值被静默丢弃，界面上只能看到一个没有任何说明的协作面板。现改为 `Channel(CONFLATED)` 单槽信箱，用户抢在订阅之前输入的内容也能被缓冲住- 原因同步写入 `agentState.message`，界面才有东西可显示；非等待状态下的误触投递由 `_needsUser` 门控拦下，避免残留值让下一次等待被立刻满足而跳过等待"
          ]
        },
        {
          "type": "优化",
          "items": [
            "**主循环高风险调用点单点隔离**：`observe()` 读元素树、截图、端侧决策、云端决策、技能归一化、意图转译、动作执行、步骤留档全部包上异常兜底。无障碍服务被系统回收、截图权限被回收、Shizuku/Termux 通道断开时，只降级当前这一步（走既有的「连续 3 次失败 → 请求用户介入」链路），不再终止整个任务",
            "**决策链路连续异常护栏**：连续 5 次决策抛异常（如 API 地址错误、网络完全不可达）时收尾并提示「AI 决策链路持续异常，已停止任务」，不再无限空转；`WATCHDOG` 超时保持既有「等待后重试」语义不变"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 `PendingTaskQueueTest`（5 例）：FIFO 顺序、空队列取出返回 null、并发入队不丢任务、边入队边取出不重复不丢任务",
            "`EngineRulesTest` 补 `shouldFallbackReset` 4 例：归属一致且非终态允许复位、`DONE` 终态不复位、taskId 不一致不复位、无归属 id 只靠终态守卫"
          ]
        }
      ]
    },
    {
      "version": "v0.1.333",
      "date": "2026-09-20",
      "summary": "让 API 地址不再被「公网 HTTPS」绑架：自建推理服务（Ollama、LM Studio、one-api 等）多跑在内网， 地址常是 `192.168.1.5:8000/v1` 或 `localhost:11434/v1`。此前这类配置走不通有两条原因—— 系统的明文流量策略会直接拦下 http:// 请求，漏写协议时 OkHttp 也会因地址不完整而抛异常。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**明文 HTTP 放行**（`AndroidManifest.xml`）：`android:usesCleartextTraffic=\"true\"`。targetSdk 28 起系统默认禁止明文流量，不放开则 http:// 的 API 地址一律失败于\"CLEARTEXT communication to xxx not permitted by network security policy\"",
            "**地址自动补协议**（`AiClient.normalizeBaseUrl`）：未写协议时按主机推断——localhost、私有网段（127/10/172.16-31/192.168）、单段主机名（nas、myserver）与 .local/.lan/.internal 等内网后缀补 `http://`，公网域名补 `https://`；已写 `http://` 的地址原样保留，不会被改写成 https",
            "设置页「API 地址」下补一行说明：支持 http://、内网地址与 localhost，不写协议时自动补全"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 `ApiEndpointNormalizeTest`（5 例）：协议保留、本机/私有网段/内网后缀补 http、公网域名补 https、空串处理"
          ]
        }
      ]
    },
    {
      "version": "v0.1.332",
      "date": "2026-09-20",
      "summary": "给悬浮窗做减法：任务执行时它以小窗形态长时间贴在屏幕上，此前却把 AI 的发送/返回/审核三段 内容全摊在体内，窗口被撑成一块\"屏幕补丁\"；玻璃背景又叠了八层光学效果（菲涅尔四边反射、 动态光斑、棱镜虹彩色散…），热闹得不像系统组件；再加上窗口可被拖出屏幕、拖丢后任务还在跑 却看不见状态。本次从信息密度、视觉、交互、性能四方面收敛。",
      "sections": [
        {
          "type": "优化",
          "items": [
            "**AI 详情默认折叠**（`FloatingWindowService`）- 详情区（发送给 AI / AI 返回 / 审核结论）高 112dp，此前每来一段流式文本就自动弹出，窗口在任务执行中反复变高变大 —— 正是遮挡屏幕的主要来源- 现默认只占「跑马灯 + 头部 + 状态行」三行；内容照常在后台累积，点头部或头部新增的「详情/收起」按钮即可展开查看，展开状态在任务内保持、任务结束复位- 一并去掉详情区自带的「AI 徽章 + 思考中」标题行：顶部阶段徽章已经说明了当前处于思考中，再叠一行只是重复信息、白占高度；分栏标题 SENT/RESPONSE/REVIEW 改为中文",
            "**玻璃背景降为三层**（`LiquidGlassDrawable`）- 去掉菲涅尔四边反射、动态光斑、底部阴影渐变、棱镜虹彩色散，只留半透明白底 + 顶部折射高光 + 一道左上斜向柔光，外侧由发丝描边与细边框收边；虹彩是最显\"脏\"的一层，白玻璃上叠三原色渐变会让整体发浑- 投影高度 18dp → 12dp（M3 柔和浮起，不需要夸张阴影来证明\"浮起\"）",
            "**交互：边缘吸附与越界回收**（`FloatingWindowService.settlePosition`）- 窗口用 `FLAG_LAYOUT_NO_LIMITS`，本可被拖到屏幕外；松手时若贴近左右边缘则吸附贴边（留 8dp 边距），纵向越界则回收进屏幕 —— 不再出现\"窗口拖丢了、任务还在跑\"的情况- 惯性滑行的活动范围同样收进屏内，并保留跑马灯贴顶的负 y 上限",
            "**交互：轻点头部展开详情**：按触摸阈值区分\"轻点\"与\"拖动\"（此前手指的微小抖动也算位移，拖动与点击无法共存）；拖动时不再跟随手指做光斑重绘"
          ]
        },
        {
          "type": "性能",
          "items": [
            "**Shader 缓存**（`LiquidGlassDrawable`）：光学层改为仅在尺寸变化时重建，绘制期间零分配。悬浮窗在任务执行期间每帧重绘，此前每帧要新建十来个 `LinearGradient`/`RadialGradient`",
            "**跑马灯不可见时停帧**（`MarqueeView`）：截图隐藏、任务结束隐藏时不再每帧请求重绘（GONE 的视图仍会把 Choreographer 帧回调与遍历持续拉起来），恢复可见后从当前相位续滚",
            "**通知节流**（`FloatingWindowService`）：AI 思考同步到通知栏限制为最小间隔 700ms —— 流式增量每秒数次，逐条 `notify` 是跨进程调用，此前是白烧的固定开销"
          ]
        },
        {
          "type": "变更",
          "items": [
            "悬浮窗尺寸与位置常量（宽度、投影、贴边留白、吸附阈值、详情区高度）集中到`FloatingUi` 令牌，不再散落在窗口创建、惯性滑行、吸附各处",
            "面板入场动画改为「淡入 + 自下而上 12dp 位移」，去掉缩放（缩放与位移叠加会让视觉重心漂移，且入场方向应统一为自下而上）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.331",
      "date": "2026-09-20",
      "summary": "让点击光标只属于「任务执行中」：此前任务成功跑完后从不撤下光标，它就停在最后一次点击的位置 一直挂在屏幕上；服务还声明了 `START_STICKY`，被杀后系统用空 intent 重建时也会凭空挂出一个光标。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**任务正常完成不撤光标**（`AgentEngine.run`）- 只在该路径补 `hide()` 是治标：主循环有多个 `return` / 异常出口，逐个补容易漏- 现统一放在 `run()` 的 `finally` 里，成功 / 失败 / 用户停止 / 异常一律撤下；并按 `taskId` 判定归属——停止协程后 `finally` 是异步跑的，用户若立刻发起新任务，旧任务的收尾不能把新任务刚挂上的光标一并撤掉",
            "**服务被重建后凭空出现光标**（`CursorOverlayService`）- `START_STICKY` 让系统在服务被杀后用 `null` intent 重建它，重建即走「显示」分支挂出光标，而此时并没有任何任务在执行；现改为 `START_NOT_STICKY` - 新增「任务执行中」这一唯一可见性凭据（`wanted`，由 `show` / `hide` 驱动）：`show` 的启动请求与 `hide` 抢跑（请求姗姗来迟）时，`onStartCommand` 直接 `stopSelf`，不再补挂光标",
            "**撤下光标改走进程内直连**：原来靠 `startService(ACTION_HIDE)` 通知服务自撤，而任务大多在 App 处于后台时结束，后台启动服务可能被系统拒绝（异常被 `runCatching` 吞掉），光标就留在屏幕上；服务实例本就在同一进程，现直接在主线程撤下视图并停掉服务，撤下前再确认一次 `wanted`，避免误撤新任务刚挂上的光标"
          ]
        }
      ]
    },
    {
      "version": "v0.1.330",
      "date": "2026-09-20",
      "summary": "修「悬浮窗在的时候整个手机都点不动」：底部选项卡窗口被内容撑成整屏高，成了一层看不见的全屏 可触摸层，屏幕上任何点击都落在它身上；而顶部跑马灯是另一个窗口，照旧滚动 —— 于是看上去 只有跑马灯在动、手机点哪儿都没反应。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**选项卡窗口被撑成整屏高** - 根因：交互面板的内容滚动区用「高度 0 + weight 1」占满剩余空间，而选项卡窗口是`WRAP_CONTENT`；LinearLayout 在 `AT_MOST` 下会把「沿高度的剩余空间」——也就是整块屏幕—— 全分给权重子视图，面板因此被撑到整屏高。窗口本身又是 `MATCH_PARENT` 宽、贴底，合起来就是一张盖住全屏的透明层- 现改为固定限高的滚动区：面板始终是一张内容大小的卡片，短文案不留大片空白，超长文案在卡内滚动",
            "**面板隐藏时窗口仍占一条透明可触摸区域** - 容器的左右/底边留白挪到**面板自己的外边距**上：容器是窗口根视图，它的内边距即使面板`GONE` 也照样把窗口撑出一段高度，零内容却可触摸，会持续吃掉屏幕底部的操作- `hideSheet()` 收起动画结束后同步把面板置 `GONE`：窗口根即使自身 `GONE` 仍会被测量，面板留在 `VISIBLE` 会继续撑出同样高度的可触摸窗口",
            "**四类交互的用户操作此前全都点不到** - 交互面板早已不挂在顶部窗口（改由底部选项卡承载），但 `showInteraction` 对`approve` / `savetemplate` 仍走\"顶部渲染\"分支：只把面板设成 `VISIBLE`、不调 `showSheet()`，父容器默认 `GONE`，面板永远不可见 —— 需要批准计划时用户点什么都没反应，任务一直挂着- 反过来 `clarify` / `guide` 只调 `showSheet()` 而不把面板设成 `VISIBLE`，滑出来的是空容器- 现统一为「面板置 `VISIBLE` + 重建按钮 + `showSheet()`」，批准 / 保存模板 / 澄清 / 指导都能正常看到并点到；顺带去掉了面板上叠加的缩放动画，入场方向统一为自下而上"
          ]
        }
      ]
    },
    {
      "version": "v0.1.329",
      "date": "2026-09-20",
      "summary": "继续修 Agent 页点输入框后「输入区浮得比键盘顶更高、中间空出一块」：v0.1.325 让导航栏给键盘 让位的方向是对的，但判定本身读错了位置，加上 Manifest 没有声明输入法模式，两条路各自多顶了一截。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**键盘判定改在内容层读**：`WindowInsets.ime` 原先写在 `Scaffold` 的 `bottomBar` lambda 里，而 bottomBar 是 `SubcomposeLayout` 的子组合，在其中读 insets 不保证随键盘弹出而重组 —— 读到旧值 `false` 时导航栏不让位，`Scaffold` 的底部内边距仍带着整条导航栏高度，输入区又整段 `imePadding()` 避让 IME，两者相加就把输入区顶到键盘顶之上一条导航栏的高度。现上移到 `ActivityContent` 组合体内读一次，导航栏与输入区必定同一帧让位- 顺带核实：Material3 1.3.1 的 `Scaffold` **不会**消费自己算出的 innerPadding （`ScaffoldKt` 各内部类里没有 `consumeWindowInsets` 调用），所以\"脚手架内边距 + 输入区 imePadding\"确实是各自独立相加的，两处都必须为 0",
            "**显式声明 `android:windowSoftInputMode=\"adjustResize\"`**：此前 Manifest 完全没有声明，系统按 `adjustUnspecified` 自行判定，Compose 根视图不是 ScrollView 时可能判成 `adjustPan` —— 系统把整个窗口内容往上平移，与页面内的 `imePadding()` 再叠加一次同样的抬升。edge-to-edge（`enableEdgeToEdge` + targetSdk 35）下 `adjustResize` 不会压缩窗口，IME 只通过 `WindowInsets.ime` 上报，输入区位置完全由页面内的 `imePadding()` 决定"
          ]
        }
      ]
    },
    {
      "version": "v0.1.328",
      "date": "2026-09-20",
      "summary": "给长线任务一个不会丢的「任务记忆」：此前 AI 只知道最近 3 步做过什么，且这 3 条纯内存、轮转即丢， 而决策历史还会被压缩到最近几轮 —— 任务跑到后半程，最初的目标与用户中途的交待就看不见了。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**任务记忆（`TaskMemoryEntry` + DataStore key `task_memory`）**：一次任务执行期间持续维护「目标 / 用户要求 / 已验证做法」，随每一步落库，任务中断后记忆页仍可查看- 目标 = 任务原文；用户要求 = 任务原文 + 执行中用户在悬浮窗「指导输入」里说的话- 每步验证生效的动作摘要记为「已验证有效的做法」，上限 10 条（超量丢最旧）- 用户要求上限 8 条；只跳过「去掉空白标点后完全相同」的重复项 —— 用户中途的补充指令往往与任务原文措辞相近（\"帮我在美团点一份黄焖鸡\" → \"帮我再点一份黄焖鸡\"，bigram 相似度恰好 0.5），用模糊去重会被静默丢掉，等于没记住用户需求，故这里不用 `AiMemoryDedupe.isSame`",
            "**每轮决策注入完整任务记忆**（`AgentEngine.taskMemoryText`）：固定注入「目标 + 用户要求」，已完成步数 > 0 时再附「执行进度 + 阶段 X/Y + 已验证有效的做法」，让 AI 不会重复执行已完成步骤",
            "**记忆页新增「任务记忆」分区**（`TaskMemoryList`）：按任务卡片展示任务名、中文状态徽标（进行中 / 已完成 / 未完成 / 已中断）、目标、用户要求、完成方法，支持单条删除与整区清空；统计概览扩为三列（任务记忆 / 异常经验 / 用户画像）"
          ]
        },
        {
          "type": "变更",
          "items": [
            "**任务记忆取代易失的进度摘要**：删除 `progressNotes` / `MAX_PROGRESS_NOTES`（最近 3 条内存队列）与`progressSummaryText()`，改由任务记忆承担「已完成什么」的记忆职责",
            "**任何退出路径都不会把状态停在「进行中」**：成功 → 已完成、步数耗尽 / 决策为空 / 技能连续被拒 / 用户拒绝协助 → 未完成、用户主动停止 → 已中断；`run()` 外层加 `try/finally` 兜底，防漏改- 兜底收尾按 `taskId` 判定归属：`stop()` 取消协程后 finally 是异步执行的，用户若立刻发起新任务，旧任务的 finally 会在新任务已经开始之后才跑到，不加判断会把新任务的记忆误标为「未完成」，且新任务随后成功时会被终态守卫挡住而永远写不进去",
            "**乱序落库保护**（`upsertTaskMemory`）：引擎是「内存快照 + fire-and-forget 落库」，旧快照可能后到，传入条目的 `updatedAt` 早于库中记录时直接丢弃，不覆盖新进度；库容量 30 条，超量优先淘汰已结束的任务"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 `TaskMemoryEntryTest`：用户要求追加 / 去重 / 超 8 条裁剪 / 空白忽略、完成方法去重与超 10 条裁剪、`withStatus` 更新状态与步数、四种状态的文案；含一条回归用例锁定「与任务原文措辞相近的追加指令必须保留」，防模糊去重再次吃掉用户指令"
          ]
        }
      ]
    },
    {
      "version": "v0.1.327",
      "date": "2026-09-19",
      "summary": "让技能声明的参数真正驱动执行：此前内置技能只是「一个名字」，技能页展示的参数（app / target / direction / key / wait_ms …）在运行时被静默丢弃，等于空壳。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**技能参数被丢弃**（`SkillCompat.normalize`）- 归一化只重写了意图名，完全没读 `args` → `{\"intent\":\"skill_swipe\",\"args\":{\"direction\":\"up\"}}` 归一化出的意图里 `direction` 仍是 `null`，转译后滑不动；`skill_press` 丢 `key` 后变成空按键- 现新增 `applyArgs()`：把 `args` 里**真正给出**的字段回填到意图字段（app / target / uri / page / text / summary / reason / direction / key / wait_ms / duration_ms），AI 直接写在扁平字段上的值不受影响- 两种写法从此等价：`{\"intent\":\"skill_swipe\",\"args\":{\"direction\":\"up\"}}` 与 `{\"intent\":\"swipe\",\"direction\":\"up\"}`",
            "**`target` 写成 `by:` 前缀会崩溃**（`SkillCompat.parseTarget`）- `raw.startsWith(\"by:\")` 时用 `indexOf(\":\")` 拿到的是前缀自身的冒号（下标 2），再 `substring(3, 2)` 直接抛 `StringIndexOutOfBoundsException` - 现改为 `indexOf(':', 3)` 并校验 by/value 非空，非法时回退 `by=text`"
          ]
        },
        {
          "type": "新增",
          "items": [
            "**高层语义技能补上可选 `target` 参数**：刷新/搜索/发送/确认/关闭/分享/收藏/复制/删除/下载/新增/ 切换开关/清空输入共 13 个技能，其转译策略本就支持「语义控件未命中时用 AI 给的 target 兜底定位」，但技能声明里没有这个参数，AI 无从提供 —— 现统一声明为可选参数，接口与能力对齐",
            "提示词技能区块补充说明：用技能名调用时 `args` 与扁平字段两种写法等价（中英双语）"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 4 条用例：`args` 回填到意图字段（swipe/open_app/press/wait/remember）、`target` 三种写法解析（含 `by:` 不再崩溃）、`args` 缺失时不清空扁平字段、高层语义技能可用 `args` 传 target"
          ]
        }
      ]
    },
    {
      "version": "v0.1.326",
      "date": "2026-09-19",
      "summary": "打通「技能（Skill）」到执行链路的最后一环：此前技能体系只有声明与 UI，AI 提示词里没有技能清单、 AI 也用不了技能名，技能页的启停开关对运行时完全没有影响。现在技能真正可被 AI 调用。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**技能归一化收口点**（`SkillCompat.normalize` + `SkillExecutionGateway.normalize`）- AI 的 `intent` 字段现在有三种合法写法：标准意图名（`tap`）、内置技能 id / 技能名（`skill_open_app` / `打开应用`）、MCP 技能 id - 内置技能 → 归一化为等价意图，**继续走原有 IntentTranslator 链路（原逻辑一字不改）** - MCP 技能 → 解析为 `Normalized.Mcp`，由端侧就地调用远端工具- 未知 / 已停用 / 缺必填参数 → 返回中文原因",
            "**AI 提示词注入技能区块**（`AgentPrompts.skillSection`）- 说明「意图名 / 技能 id / 技能名」三者等价，给出 MCP 技能的确切调用格式- 只列出**真正可调用**的 MCP 技能（含参数名与必填标记），并声明已停用技能不可调用- 旧实现罗列服务器全部工具，但端侧并无对应调用路径 —— 提示词与可执行能力现在严格一一对应",
            "**AgentEngine 接入技能执行链路** - 主循环在「转译」之前做归一化：内置技能放行、MCP 技能就地调用、错误回注给 AI 纠正- MCP 调用结果作为「上一步结果」注入下一轮决策上下文，AI 据此判断目标是否达成- MCP 调用带 20s 超时兜底、输出截断 1200 字；连续 3 次被拒（未知 / 停用 / 缺参）则停止任务，防死循环- 新增动作类型 `ActionType.MCP_CALL`（中文标签「调用技能」）",
            "**补齐内置技能目录**：`SkillCatalog` 补上缺失的 `remember`（记住信息）/ `fetch`（取网页正文），目录与 `IntentType.ALL` 从此一一对应，并同步补 `SkillCompat.toIntent` 映射"
          ]
        },
        {
          "type": "修复",
          "items": [
            "**技能启停开关此前不生效**：现在停用技能会被归一化直接拒绝，并告知 AI 改用其它方式或请用户启用",
            "**`remember` / `fetch` 在技能页查不到**：两者早已在意图全集与转译策略表里，只是目录漏登记"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增归一化用例：标准意图名放行、技能 id / 中文名翻回等价意图、`remember`/`fetch` 可映射、未知与已停用拒绝、MCP 分流与缺参拒绝",
            "新增「内置目录与意图全集一一对应」用例，防止目录再次漏项"
          ]
        }
      ]
    },
    {
      "version": "v0.1.325",
      "date": "2026-09-19",
      "summary": "修 Agent 页点输入框弹出键盘后，输入区「升得太高、中间留白」的问题。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**键盘上方空出一整条底部导航栏** - 根因：`Scaffold` 的 `bottomBar` 用 `WindowInsets.isImeVisible` 判断键盘是否弹出，而该可见位在部分机型 / ROM 上不可靠（键盘已经弹出，它仍是 `false`）→ 底部导航栏没有收起- 于是 `Scaffold` 的 innerPadding 里仍带着导航栏高度，输入区又用 `imePadding()` 整段避让 IME，两者相加 = **导航栏高度 + 键盘高度** —— 输入区被多抬起一截，多出来的那块正是那条空白- 现改用 **IME 实际占位高度**判断：`WindowInsets.ime.getBottom(LocalDensity.current) > 0` - 它与输入区的 `imePadding()` 同源：只要输入区被抬起，导航栏在同一帧必定让位，不会再出现「抬了却没让位」的错位- 连带修复协助浮层（`AgentAssistSheet`）：它同样在 `Scaffold` 内容里做 IME 避让，一并受益"
          ]
        }
      ]
    },
    {
      "version": "v0.1.324",
      "date": "2026-09-19",
      "summary": "修复「Agent 一直卡在观察屏幕」：观察这一步里藏着两处没有上限的阻塞调用，卡住时界面既不动也不报错。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**视觉分析没有看门狗**：视觉链路（截图 → 端侧 3B / 云端视觉）全部发生在 `cloudDecide` 里，而状态切到「正在思考下一步」是在视觉分析**之后** —— 于是视觉一慢，界面就一直停在「观察屏幕」- 云端视觉 `visionDescribe/visionLocate` 走 OkHttp 同步请求（读超时 120s，且带 2~5 次重试）- 端侧 3B `detectControls` 单次 20s，同一步原本可能被调用**两次**（第 1 步失败后第 3 步又重试同一服务、同一张图）- 新增 `withVisionWatchdog(WATCHDOG_VISION_MS = 25s)`：视觉调用放到独立协程 await，超时立刻放弃视觉描述、只用无障碍元素树继续决策；`visionLocate`（hint 目标定位）同样纳入（20s）- 这一步开始前先把状态切到「正在识别屏幕内容」，用户能看见 AI 在做什么，而不是干等「观察屏幕」",
            "**同一步不重复调用外挂视觉**：`triedOnDevice3b` 记录本步是否已经找过外挂，避免第 3 步兜底再白等 20s",
            "**无障碍截图可能永不回调**：`takeScreenshot` 若被系统限流/内部错误，协程会永久挂起（悬浮窗已隐藏、循环停在观察阶段）→ 加 `SCREENSHOT_TIMEOUT_MS = 2.5s` 超时兜底，超时按「无截图」继续"
          ]
        },
        {
          "type": "说明",
          "items": [
            "看门狗超时是**降级**而非失败：本步没有视觉描述，仍按元素树决策，任务不会中断。"
          ]
        }
      ]
    },
    {
      "version": "v0.1.323",
      "date": "2026-09-19",
      "summary": "修跑马灯色带没贴到屏幕物理顶边（落在状态栏下方）的问题。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**色带顶部落在状态栏下沿** - 根因：`statusBarHeight()` 只依赖 `resources.getIdentifier(\"status_bar_height\", \"dimen\", \"android\")`，该资源名在相当一部分 ROM / 高版本系统上取不到，**返回 0** - 返回 0 时 `y = -statusBarHeight()` 退化成 `y = 0`，窗口顶正好落在状态栏下沿 —— 色带自然就到不了屏幕边- 现取值顺序：**WindowInsets**（API 30+，最可靠）→ 系统资源名 → 兜底 `dp(24)` （宁可多覆盖一点，也不能返回 0）"
          ]
        },
        {
          "type": "修复 · 连带",
          "items": [
            "**跑马灯文字会被状态栏压住**（此前被上面那个 0 掩盖）- `MarqueeView` 高度 = `marqueeHeightDp + statusBarHeight`，而文字原本在**整个高度**里居中；状态栏高度修正为真实值后，文字中心正好落进状态栏区域- `MarqueeView` 新增 `setTopInset(px)`：色带背景仍从屏幕物理顶铺下来，**文字只在状态栏以下的可用高度里居中**；悬浮窗在创建与尺寸变化时传入"
          ]
        }
      ]
    },
    {
      "version": "v0.1.322",
      "date": "2026-09-19",
      "summary": "优化悬浮窗顶部跑马灯（`MarqueeView`），修掉三处一直存在的显示缺陷。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**文字回到入场位时抽动**：文字画在 `x = offset`，完全离开左侧应满足 `offset < -(textWidth)`，原判据是 `offset < -w` —— 文字比屏幕窄时会提前回位，于是文字**还在屏内就跳回入场位** - 现改为 `offset < -(textWidth + gap)`，保证完全滚出后才接上下一段",
            "**相位色从未生效**：`Paint` 中 shader 优先级高于 color，原先给文字设了渐变 shader 后，`setText(text, marqueeColor(phase))` 传入的相位色被无声覆盖- 现文字改用相位色纯色（底色渐变继续承担彩色视觉），相位色这才真正随状态变化",
            "**状态更新把文字钉在原地**：AI 状态每秒更新数次，原 `setText` 每次都 `offset = 0f`，文字反复从头开始，看上去像卡住不动- 现更新文字不重置滚动相位；内容与颜色都没变时直接跳过，不做无谓重绘"
          ]
        },
        {
          "type": "优化",
          "items": [
            "**短文本居中静止**：文字窄于可视区时不再空转一整圈，同时停掉每帧重绘（省电）",
            "**单帧最大推进时长** 限制为 0.1 秒：视图不可见一段时间后恢复时，文字不会瞬移一大段",
            "清理死代码：未使用的 `bitmap` / `shader` 字段与 `buildGradient()`、未使用的 `kotlin.math.min` 导入"
          ]
        }
      ]
    },
    {
      "version": "v0.1.321",
      "date": "2026-09-19",
      "summary": "需要协助 / 答疑时改为从页面下方浮入协助浮层。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**`AgentAssistSheet`**：底部协助浮层，承载「选项 + 输入框」的完整交互- 从页面下方浮入（`slideInVertically` 由下向上 + 淡入，与全站出现方向一致），退场反向- 面板加 `animateContentSize()`，文案换行 / 内容增减时高度平滑过渡，不「跳一下」- 两种场景共用同一副骨架，仅出口不同：- **答疑**（计划有歧义）：问题 + 选项（点一下即答）+ 自由输入 + 「提交」- **协助**（敏感页只读 / 动作连续未生效）：原因 + 「已手动处理」+ 指导输入 + 「告诉 AI」- 浮层出现时输入区让位（`if (shownAssist == null)`），否则同屏会出现两个输入框- 退场动画期间 `assist` 已为 null，缓存最后一次内容（`shownAssist`），避免面板先空掉再滑走- 视觉遵循三层圆角口径：浮层容器 = `Card`，输入框与选项 = `Tile`，出口按钮 = `Chip`"
          ]
        },
        {
          "type": "变更",
          "items": [
            "**`PlanClarifyItem` 去掉选项列表与手写输入**：这两处职责已由浮层承担，卡片只保留「问题原文 + 一句指路」- 同屏出现两套入口（卡片里一套、浮层里一套）会让人不知道该点哪个- 连带移除其 `onAnswer` 参数，调用点同步更新"
          ]
        },
        {
          "type": "说明",
          "items": [
            "悬浮窗（桌面覆盖层）侧的 assist 交互未改动：`FloatingWindowService` 的 `clarify` / `guide` 面板仍独立工作，与 App 内浮层是两条并行入口（一个在桌面、一个在应用内）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.320",
      "date": "2026-09-19",
      "summary": "修三个界面问题：顶部渐隐遮挡内容、无障碍提示重复。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**顶部渐隐遮罩常驻导致遮挡内容** - `TopFadeScrim` 高 56dp 且叠在列表区上层，而 `LazyColumn` 的 `top` padding 只有约 8dp，于是列表首项（空态图标、第一张卡的上半截）一出现就被压掉 56dp - 它本意是\"内容滚到顶部被截断时柔化边界\"，未滚动时不该存在：现由滚动位置驱动（`firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0`），经 `animateFloatAsState` 淡入淡出，静止在顶部时完全不绘制- `TopFadeScrim` 新增 `alpha` 参数（0 时直接不组合，不占布局）",
            "**无障碍未开启提示重复** - 空态判据 `showEmpty` 不含 `items`（只看 `traces.isEmpty()` 等），因此 `AgentEmptyState` 的引导文案与 `AgentTimelineMapper` 产出的 `Notice` 会同屏出现- 修复：空态时过滤掉任务流里的 `Notice`（空态的引导更完整，保留它）；非空态仍由 `Notice` 提示- 自动跟随滚动改用过滤后的列表，避免索引越界"
          ]
        }
      ]
    },
    {
      "version": "v0.1.319",
      "date": "2026-09-19",
      "summary": "统一 Agent 页的出现动效方向与卡片口径。",
      "sections": [
        {
          "type": "变更 · 动效方向",
          "items": [
            "**出现动效全站统一为「由下向上」** - `animateListItem` 去掉缩放（原 0.97 → 1），只保留淡入 + 纵向位移 24dp → 0 - 理由：纵向位移与缩放松缩是两种观感，叠在同一元素上会互相打架- 4 处展开 / 收起（顶栏菜单、步骤卡「原始数据」、文档卡、助手文本卡）改为从底部展开：`expandVertically(expandFrom = Alignment.Bottom)` / `shrinkVertically(shrinkTowards = Alignment.Bottom)` - 步骤卡状态徽标（未生效 / 已生效）由纯淡入淡出改为「由下向上」滑入 + 淡入- 运行状态条入场同样改为「由下向上」（位移 it / 3）"
          ]
        },
        {
          "type": "变更 · 协调性",
          "items": [
            "**任务流条目统一圆角与描边**：同一列里此前混用 `AppRadii.Item`(20dp) 与 `AppRadii.Card`(24dp)，且部分卡片无描边- 统一为 `Item` + `1dp outlineSoft`：完成卡、失败卡、需协助卡、计划卡、空态警告块- 口径固定为三层：**任务流条目 = `Item`(20dp)**、**嵌套块 = `Tile`(14dp)**、**浮层 = `Card`(24dp)** - 顶栏下拉菜单属浮层，保持 `Card` 不变"
          ]
        }
      ]
    },
    {
      "version": "v0.1.318",
      "date": "2026-09-18",
      "summary": "补齐意图回显覆盖：此前有一整类意图在 Agent 页是隐形的。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "**端侧决策的意图在 Agent 页完全不可见**（真实缺口，非罕见路径）- `localDecision.decide()` 命中时走直通分支（`fromLocal = true`），**不经过 `cloudDecide`**，因而不写 `StepTrace` - 而任务流以 trace 为骨架（`AgentTimelineMapper.buildRuns` 首行即 `if (traces.isEmpty()) return emptyList()`），于是这一步只留下 `StepRecord`、却挂不上列表 —— 用户体感是\"AI 没动，页面自己变了\" - 新增 `recordLocalStepTrace()`：端侧决策同样留档，以 `visionSource = \"端侧决策\"` 标注来源（`LOCAL_DECISION_SOURCE`）"
          ]
        },
        {
          "type": "新增",
          "items": [
            "**失败原因回显**：`StepRecord` 新增 `detail`（执行 / 转译结果说明），三处 `recordStep` 调用点均传入 `verify.detail` - 步骤卡片在「未生效」之外补上「原因：…」（红色），成功时弱化为执行层说明- 与 `shellOutput` 内容相同时不重复展示",
            "卡片新增「端侧」徽标，区分\"AI 想的\"与\"端侧规则直接做的\""
          ]
        },
        {
          "type": "变更",
          "items": [
            "**状态徽标平滑过渡**：`未生效 / 已生效` 改用 `AnimatedContent` + 淡入淡出切换，不再是瞬间跳变"
          ]
        }
      ]
    },
    {
      "version": "v0.1.317",
      "date": "2026-09-18",
      "summary": "参考 Aether 的回显手法，重做「进行中」状态的观感：把过程与结果在视觉上分开。",
      "sections": [
        {
          "type": "变更",
          "items": [
            "**进行中状态去卡片化**（`LiveStatusItem`）：不再铺卡片底色与边框，改为「一行弱化文字 + 1dp 细分线」- 借鉴点：Aether 的 `AgentWorkingStatusHeader` 用次要色文字 + 一条 1dp 细分线，让「还在进行的过程」不与「已完成的结果卡片」争夺视觉权重- 步骤卡（结果）保持卡片形态不变，两者层次因此拉开",
            "**新增「已工作 N 秒」**：`AgentState` 新增 `startedAtMillis`（任务启动时写入），经 `LiveStatus` 透出，UI 用 `produceState` 每秒刷新- 不足 1 秒不显示；超过 1 分钟显示「N 分 N 秒」",
            "**思考中改用微光扫过文字**（`ShimmerStatusText`）：取代原先的常驻脉冲圆点- 借鉴点：Aether 的 `ShimmerStatusText` 用 `Brush.linearGradient` 扫过文字表达\"正在生成\"，行程随文字长度伸缩、扫完停顿再重来- 仅在 `THINKING` 阶段启用；`reduceMotion` 时降级为静态文字（其余阶段本就是弱化静态文字）"
          ]
        },
        {
          "type": "移除",
          "items": [
            "`LiveStatusItem` 的脉冲圆点与按阶段取色的 `dotColor`（\"正在活动\"的语义已由微光承担）",
            "连带清理失效 import（`CircleShape` / `EaseInOut` / `tween`）"
          ]
        },
        {
          "type": "说明",
          "items": [
            "未逐行照搬 Aether（GPL-3.0）：只借鉴「过程弱化、结果成卡」的层次处理与微光手法，颜色、间距、动效参数全部走本项目已有令牌（`onSurfaceRaised` / `outlineSoft` / `AppSpacing`）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.316",
      "date": "2026-09-18",
      "summary": "Agent 页回显增强：把 AI 正在生成的内容、以及命令的真实执行证据都展示到任务流里，并重排步骤卡片的信息层级。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**实时思考回显**：AI 决策的流式输出此前只推给悬浮窗，现在同步累积到 `AgentEngine.decisionStream` 并在 Agent 页展示- `AgentTimelineItem.LiveStatus` 新增 `streaming` 字段，实时状态卡下方贴出正在生成的内容（取尾部 10 行，像终端 tail）- 重试时同步清零（避免两遍文本叠加）；决策完成后清空，正文已落 `StepTrace` 的「原始数据」可回看- `AgentScreen` 对 `decisionStream` 做 150ms 节流，避免逐字重建 LazyColumn",
            "**命令输出回显**：`StepRecord` 新增 `shellOutput`，`AgentEngine` 以 `pendingShellOutput` 把本步输出精确关联到本步记录- 步骤卡片新增「命令与输出」块：上为实际执行的命令（`action.command`），下为 stdout / stderr；失败时显示可读原因- 此前 `lastShellOutput` 只回传给 AI，用户完全看不到 AI 跑了什么、拿到了什么"
          ]
        },
        {
          "type": "变更",
          "items": [
            "**步骤卡片信息层级重排**（`StepCallItem`）：一级「动作人话 + 生效状态」→ 二级「依据」→ 三级「命令与输出」→ 四级「元信息 + 原始数据」- 置信度从顶行 `StatusPill` 下移进元信息行（`耗时 · tokens · 置信度`），顶行只留\"做了什么、成没成\""
          ]
        }
      ]
    },
    {
      "version": "v0.1.315",
      "date": "2026-09-18",
      "summary": "界面精简：Agent 升为主界面，App 内去掉玻璃材质，悬浮窗降为可选能力。",
      "sections": [
        {
          "type": "变更",
          "items": [
            "**主界面改为 Agent**：底部导航顺序调整为 Agent / 主页 / 记忆 / 设置，启动后默认进入 Agent - `HomeScreen` 保留为第二个 Tab，其快捷入口下标同步（Agent → 0）- 全局返回手势：非 Agent Tab 一律回到 Agent（原为回主页）",
            "**悬浮窗降为可选能力**（不再当作缺失权限来提示）- 设置页「运行参数」新增「启用桌面悬浮窗」开关（`floatingWindowEnabled`，默认开）- `AgentEngine.maybeStartFloating` 同时校验「开关开启 + 已授权」- Agent 页移除三处悬浮窗引导：任务流顶部 Notice、空态警告卡片、顶栏菜单的权限状态与「去授权」按钮- `AgentTimelineItem.NoticeKind` 只保留 `ACCESSIBILITY`（真正的硬前置）；顶栏菜单现只展示待执行队列"
          ]
        },
        {
          "type": "移除",
          "items": [
            "**App 内玻璃材质**：底部导航栏与记忆图谱容器的 `liquidGlass` 改为实色 / 半透明底- 删除 `ui/components/LiquidGlass.kt`（`liquidGlass` / `LiquidGlassCard` / `liquidGlassSurface`，改后全项目零引用）- `overlay/LiquidGlassDrawable.kt` **保留**：桌面悬浮窗视觉不变",
            "连带清理无用 import（`LocalContext` / `liquidGlass` / `Color` 等）"
          ]
        },
        {
          "type": "说明",
          "items": [
            "悬浮窗与边缘光效是两个独立开关：关闭悬浮窗不影响边缘光效（后者仍需悬浮窗权限，因为它是系统悬浮层绘制）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.314",
      "date": "2026-09-18",
      "summary": "接入 Termux 作为执行通道，并把它\"图形界面做不到\"的命令行能力纳入意图转译层。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**`device/shell/TermuxBridge.kt`**：Termux 执行通道- 目标组件 `com.termux/.app.RunCommandService`（action `com.termux.RUN_COMMAND`），结果走 **PendingIntent 回传**（Termux 官方源码注释指出：结果目录文件方式在 `allow-external-apps` 未开时会永久挂起）- 暴露 `isInstalled()` / `hasRunCommandPermission()` / `isAvailable()` / `probe()` / `executeShell()` - 回传 Bundle 按键名子串防御性解析，兼容 Termux 版本间的 key 差异",
            "**`TermuxResultReceiver` + `TermuxResultRelay`**：结果回传接收器与挂起请求登记表（自收自发，`exported=false`）",
            "**`fetch` 意图（纳入转译层）**：AI 只说\"取哪个地址的正文\"，命令由端侧拼装- `IntentType.FETCH` + `TermuxFetchStrategy`：`uri` → `raw curl -sL --max-time 20 -- <url>`，地址字符白名单过滤- Termux 不可用时转译层直接给出可读失败，引导 AI 改走 UI 意图",
            "**执行通道四态**：`AUTO` / `ADB` / `SHIZUKU` / `TERMUX`；AUTO 顺序为 无线 ADB → Shizuku → Termux（第三顺位兜底）",
            "**能力页 Termux 卡片**：三项前置条件（已安装 / 已授权 / 已开启 allow-external-apps）+ 分步引导与实跑探测"
          ]
        },
        {
          "type": "变更",
          "items": [
            "`executeShellAction` 新增 Termux 工具链判定：`curl`/`python`/`jq` 等命令在 adb shell 中通常不存在，按命令名直接走 Termux，不受通道偏好影响",
            "抽出 `finishShellResult`，shell 输出回传逻辑单点收口（原 `runRealShell` 尾部内联）",
            "提示词（中英同步）：意图表与规划提示词新增 `fetch`；`situationalExtras` 在\"任务需从网络取数 + 本机有 Termux\"时按需注入 fetch 用法与边界，其余任务不增加任何篇幅"
          ]
        },
        {
          "type": "说明",
          "items": [
            "Termux 是**普通应用权限**的 Linux 环境，与无线 ADB / Shizuku 的系统级 shell 不同：可跑 curl/python/文本处理，**不能**执行 `am` / `pm` / `settings`",
            "前置条件：Termux 侧 `~/.termux/termux.properties` 设 `allow-external-apps=true`，并授予本 App `com.termux.permission.RUN_COMMAND` 权限",
            "本环境无法跑 Gradle / 无真机，需真机验证 Termux 回传链路（`probe()` 返回 `__HPA_TERMUX_OK__` 即通）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.313",
      "date": "2026-09-18",
      "summary": "砍掉工作区模块，AI 的 `write_doc` 能力保留，生成结果改为在 Agent 页任务流内嵌预览。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**`feature/document/DocumentEngine.kt`**：承接原 `WorkAreaEngine` 中真正需要的那部分- 只做两件事：文档正文落盘到私有目录 `documents/`；把最近一次结果推成 `StateFlow<DocResult>` - 暴露 `writeDocument(content, fileName)` / `result` / `dismiss()` / `error`",
            "**Agent 页文档预览**：`AgentTimelineItem.DocPreview` + `AgentTimelineMapper` 新增 `doc` 入参- `AgentItems.kt` 新增 `DocPreviewItem`：文件名 + 默认展开的 Markdown 正文（限高 420dp 内滚、可展开/收起/关闭）- `AgentScreen` 的 `when(item)` 穷尽分支同步补齐"
          ]
        },
        {
          "type": "移除",
          "items": [
            "**工作区 UI 全量删除**：底部「工作区」Tab、`ExtrasPage.FileList` / `FileEditor` 二级页及其返回栏、`ui/workspace/` 8 个文件（WorkAreaScreen / FileListScreen / FileEditorScreen / WorkDisplayPanel / WorkFilesEntry / WorkGenerateCard / WorkLogStream / WorkPreviewCard）",
            "**`feature/workspace/WorkAreaEngine.kt`**：文件列表、编辑器、流式生成、AI 改写、操作日志等纯 UI 状态一并删除",
            "主页「工作区」快捷入口；Tab 下标重排为 主页 0 / Agent 1 / 记忆 2 / 设置 3"
          ]
        },
        {
          "type": "变更",
          "items": [
            "`AgentEngine.executeWriteDoc`：落盘后提示语改为「文档已生成，可在 Agent 页查看」；`run()` 开头新增 `documentEngine?.dismiss()`，新任务不带上一份文档残留",
            "提示词（中英同步）：意图表、独占路由铁律、规划/决策提示词中的「工作区」措辞统一改为「生成文档，结果在 Agent 页预览」",
            "同步更新 README 目录树与模块说明、`杂项/项目完整流程说明.md` 的包表与 UI 结构图"
          ]
        }
      ]
    },
    {
      "version": "v0.1.310",
      "date": "2026-09-16",
      "summary": "对应提交 `74efefb`（重构区间 `2e906f3..74efefb`）。 本轮为纯结构调整：行为零变化（UI 参数、控制流、提示词、导出文案均未改动），目标是「文件职责单一、包名即层级」。",
      "sections": [
        {
          "type": "重构",
          "items": [
            "**UI 超大页面瘦身**：7 个 Compose 页面（537~1119 行）拆为约 30 个职责单一文件，根页面只保留入口与 Tab 定义- `ui/skill/SkillManagerScreen.kt` 1119 → 133 行：拆出 `SkillsTab` / `SkillEditorDialog` / `McpTab` / `WirelessAdbTab` / `PromptsTab` - `ui/debug/DebugScreen.kt` 1098 → 234 行：拆出 `DebugTabs` / `CapabilityStrip` / `DebugFormatters` + `panels/`（StepShot / Metrics / Chat / Log / History / Steps / Timeline）- `ui/workspace/WorkAreaScreen.kt` 688 → 171 行：拆出 `WorkFilesEntry` / `WorkGenerateCard` / `WorkPreviewCard` / `WorkLogStream` / `WorkDisplayPanel` - `ui/memory/MemoryGraphScreen.kt` → 184 行：拆出 `MemoryGraphCanvas` / `MemoryStats` / `MemoryLists` - `ui/home/HomeScreen.kt` 580 → 298 行：拆出 `HomeHero` / `PermissionRadarCard` / `HomeCards` - `ui/agent/AgentScreen.kt` 577 → 121 行：拆出 `AgentText` / `PlanPanel` / `AgentCards` - `ui/test/TestScreen.kt` 556 → 332 行：拆出 `TestPresetCard` / `TestResultViews`",
            "**UI 重复实现收敛** - `EmptyHint` 三份 → 统一到 `ui/components/Components.kt`（Debug 侧改名 `DebugEmptyHint`，规避同包顶层重名）- `MetricCard` / `StatCard` 结构同构 → 合并为 `StatTile(title, value, unit, accent)` - 手搓 `Card(shape = …)` → 收敛到 `Components.kt` 的 `SectionCard(title, count, countColor, onClear)` - 新增 `ui/components/Formatters.kt`：`formatClock` / `formatFileTime` / `formatSize` 三处同构实现归一- `ui/theme/Theme.kt` 新增 `AppSpacing`（4 / 8 / 12 / 16dp），替换页面内硬编码间距- `ui/agent/AgentScreen.kt` 的 `isMostlyChinese` 副本删除，改调 `domain.rules.EngineRules.isMostlyChinese`",
            "**全量搬包重分层**：148 个主源文件 `package` 与所在目录 100% 对齐，顶层包即未来 Gradle 模块边界- `core/`：`ai`(4) · `security`(1) · `text`(1，HumanTranslator) · `notify`(3) - `domain/`：`model`(9) · `rules`(3，EngineRules / ShellCommands / LocalDecisionEngine) - `data/`：`prefs`(1) · `store`(5，原 `memory` / `task` / `debug` / `mcp` / `prompt` 五处持久化收敛) · `export`(1) - `device/`：`a11y`(2) · `shell`(14，原 `shizuku` + `shizuku/adb` 扁平化) · `screen`(2) · `vision`(2) - `engine/`：根(2，AgentEngine / AgentPrompts) · `execution`(5) · `perception`(3) · `network`(1) · `prompt`(1) - `overlay/`：原 `floating` 全部 5 个- `feature/`：`task`(1) · `skill`(5) · `mcp`(6) · `workspace`(1) · `adskip`(2) · `edge`(2) · `test`(4) - `ui/`：原结构 + 新增 `ui/model`（PermissionRadar）",
            "**日志导出逻辑外迁**：新增 `data/export/LogExporter.kt` - 自 `MainViewModel` 迁出约 160 行文件导出实现（文本日志 / 分任务 JSON / 诊断报告）- `MainViewModel` 仅保留 3 个薄委托方法，UI 调用点零改动- 导出文案、字段、排版逐字保留；落盘参数抽为 `downloadValues()` 统一- `formatLogTimestamp` 随之迁入数据层，避免 `data → ui` 反向依赖"
          ]
        },
        {
          "type": "移除",
          "items": [
            "`engine/AgentPrompt.kt`（原 `agent/AgentPrompt.kt`）：全项目零引用死代码",
            "`app/build.gradle.kts`：未使用的 `androidx.navigation.compose` 依赖（全项目零引用）"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 `data/export/LogExporterTest.kt`（10 个用例）：空日志、指定任务无匹配、系统日志归组、多任务批量、诊断报告翻译 + 脱敏路径",
            "测试源集补齐跨包 import（23 个测试类）"
          ]
        },
        {
          "type": "兼容性",
          "items": [
            "`AndroidManifest.xml` 5 处组件路径同步：`.device.a11y.AgentAccessibilityService` / `.device.screen.ScreenSharingService` / `.overlay.FloatingWindowService` / `.feature.edge.EdgeLightingService` / `.device.shell.AdbPairingReceiver`",
            "跨仓库与跨组件的不透明标识符一律未改：`com.phoneagent.floating.STOP`、`com.phoneagent.edge.*`、`com.phoneagent.action.RECEIVE_PAIRING_CODE`、`com.phoneagent.agent_progress_overlay`，以及外挂 APK 契约包名 `com.phoneagent.ondevice`",
            "`proguard-rules.pro` 使用 `com.phoneagent.**` 通配符，keep 规则不受搬包影响",
            "`res/xml/accessibility_service_config.xml` 的 `settingsActivity` 指向 `com.phoneagent.ui.MainActivity`，`ui/` 位置未变"
          ]
        },
        {
          "type": "基础设施",
          "items": [
            "单测基线不下滑：24 suites / 242 tests / 0 failed / 3 skipped（重构前 232 tests）",
            "`:app:lintDebug` / `:app:assembleDebug` / `:app:assembleRelease` 全部通过",
            "打 tag `refactor-baseline` 锚定搬包前状态"
          ]
        }
      ]
    },
    {
      "version": "v0.1.265",
      "date": "2026-09-13",
      "summary": "",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**Release APK 签名构建** - 添加 `upload-keystore.jks`（自签名，有效期 10000 天）- `app/build.gradle.kts` 支持从 `upload-signing.properties` 加载签名配置- `upload-signing.properties` 加入 `.gitignore`（不提交到仓库）- Release 配置：R8 混淆 + arm64-v8a 单 ABI，包体大幅缩减",
            "**APK 上传 Release**：v0.1.265 release APK（约 10MB）发布到 Gitee / GitHub Release"
          ]
        },
        {
          "type": "新增",
          "items": [
            "**MCP 验证与使用规则** `app/src/main/java/com/phoneagent/mcp/McpRules.kt` - 服务器名（非空、无空格）、URL（须 http/https 且含有效主机）校验- 协议版本白名单（`2025-03-26`）、工具名合法性、参数必填/类型校验",
            "**MCP 服务器持久化** `app/src/main/java/com/phoneagent/mcp/McpStore.kt` - 基于 DataStore 存取服务器配置列表，配置变更后自动落盘",
            "**内置 MCP 市场** `app/src/main/java/com/phoneagent/mcp/McpMarketplace.kt` - 免费第三方源预设（经典工具类：filesystem/sqlite/github/notion；公共开放 API：天气/汇率/新闻/币价）- 支持分类筛选、关键词搜索、一键\"选用\"回填新增表单",
            "**MCP 信息解析增强** `app/src/main/java/com/phoneagent/mcp/McpClient.kt` - `describe()`：initialize 握手 + tools/list 枚举，返回 capabilities / serverInfo / 工具及结构化参数- `parseParamsFromSchema()`：从 JSON Schema 提取 type / required / enum / default / description - 记录最近一次请求/响应原文（JSON-RPC），供 UI 展示",
            "**MCP 信息结构化注入 Agent 提示词** `app/src/main/java/com/phoneagent/agent/AgentEngine.kt` - `mcpToolsPromptText()`：把已启用服务器的工具、参数、使用规则动态注入系统提示，无工具则不增加负担",
            "**MCP 模块 UI** `app/src/main/java/com/phoneagent/ui/skill/SkillManagerScreen.kt` - McpTab：市场选用、新增表单（含可选 Token）、请求信息 JSON 预览- 服务器卡片：启停开关、测试有效性、绑定为技能、删除、详情展开（协议版本/服务器元信息/能力/工具及参数）- 详情区展示最近请求/响应 JSON（等宽字体预览）",
            "**Agent 运行时广告过滤** `app/src/main/java/com/phoneagent/adskip/AdContentFilter.kt` - 识别\"控件内含广告信息 + 跳过/关闭/× 按钮\"的广告，直接返回可点击关闭按钮- 剔除广告相关元素，保证广告信息不回传给 AI",
            "**完整技能编辑器** `SkillEditorDialog` 全屏化- 支持名称 / ID（新建自动生成、编辑只读）/ 分类 / 说明 / 兼容旧命令意图- 结构化参数编辑：参数名 / 标签 / 类型（text·number·boolean·select）/ 必填 / 候选项 / 默认值 / 说明，可动态增删"
          ]
        },
        {
          "type": "增强",
          "items": [
            "`McpManager`：改为可变服务器列表（StateFlow 观察），新增 add/remove/setEnabled/replaceAll/describe/bindToolsToSkills",
            "`MainViewModel`：MCP 服务器观察、增删、启停、持久化、描述、请求/响应原文获取、市场条目查询",
            "`AppModule`：Koin 依赖注入接入 `McpStore`",
            "`AgentEngine`：决策循环集成广告过滤——识别到广告直接点击关闭并跳过本轮 AI 决策；无关闭按钮时以干净快照喂给 AI",
            "`AppModule` / `AgentEngine`：MCP 工具清单在任务开始时一次枚举并注入系统提示"
          ]
        }
      ]
    },
    {
      "version": "v0.1.264",
      "date": "2026-09-10",
      "summary": "对应提交 `c01b589`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**ADB 无线配对通知栏向导** `notify/AdbPairingNotifier.kt`（121 行）- 搜索中 → 已发现设备 → 请求配对码（通知内联输入框）→ 配对中 → 成功/失败，全程通知栏驱动- `RemoteInput` 内联输入：用户无需回到 App 即可输入 6 位配对码- `POST_NOTIFICATIONS` 权限适配（Android 13+）",
            "**AdbPairingReceiver** `shizuku/adb/AdbPairingReceiver.kt`（22 行）- 接收通知内联配对码，委托给 `WirelessAdbPairingFlow.onPairingCode()`",
            "**WirelessAdbPairingFlow** `shizuku/adb/WirelessAdbPairingFlow.kt`（118 行）- Nsd 服务发现 → 配对码校验 → 网络配对 → Shizuku 拉起，完整状态机编排",
            "`AndroidManifest`：注册 `AdbPairingReceiver`，新增 `POST_NOTIFICATIONS` 权限"
          ]
        },
        {
          "type": "增强",
          "items": [
            "`AdbWirelessTransport`：`discoverService()` 改为公开方法",
            "`WirelessAdbModels`：`AdbPhase` 枚举扩展",
            "`MainViewModel`：接入配对流状态",
            "`SkillManagerScreen`：无线 ADB Tab 交互优化",
            "`AppModule`：Koin 注册新模块"
          ]
        }
      ]
    },
    {
      "version": "v0.1.140",
      "date": "2026-09-02",
      "summary": "对应提交 `c132122`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**Skill 技能系统**（`app/src/main/java/com/phoneagent/skill/`）- `SkillCatalog`：内置技能库（系统级能力 + 实用脚本）- `SkillRegistry`：技能注册 / 查询 / 启用禁用管理- `SkillCompat`：旧 `legacyIntent` → 新 Skill 的兼容桥接- `SkillExecutionGateway`：统一执行入口（内部 intent / MCP 工具分发）- `SkillModels`：`Skill` / `SkillParam` / `SkillSource` / `SkillCategory` 数据模型",
            "**MCP 协议客户端**（`app/src/main/java/com/phoneagent/mcp/`）- `McpManager`：服务器配置 / 健康检查 / 绑定工具为 Skill - `McpClient`：通用 MCP 协议实现（initialize / tools/list / tools/call）- `OkHttpMcpTransportFactory`：HTTP + SSE 双模式传输- `McpModels`：MCP 协议数据模型",
            "**无线 ADB 配对**（`app/src/main/java/com/phoneagent/shizuku/adb/`）- `AdbWirelessTransport`：6 位配对码配对、无线调试连接- `WirelessAdbStateMachine`：状态机驱动（UNPAIRED → PAIRING → BOOTING → READY）- `ShizukuBootstrap`：配对成功后自动拉起 Shizuku - `AdbBootstrapTransport`：回退到 Shizuku provider 启动- `WirelessAdbModels`：阶段 / 错误 / 状态数据类",
            "**提示词模板引擎**（`app/src/main/java/com/phoneagent/prompt/`）- `PromptTemplate`：支持 `{task}{skills}{mcpTools}{controls}{currentApp}{lastResult}{goal}{auditRejection}{situational}` 占位符- `PromptTemplateStore`：DataStore 持久化，内置 + 自定义混合管理",
            "**控件树感知**（`app/src/main/java/com/phoneagent/perception/ControlTreeBuilder.kt` + `model/ControlNode.kt`）- 将无障碍节点压缩为结构化控件树供 LLM 消费",
            "**UI**（`app/src/main/java/com/phoneagent/ui/skill/SkillManagerScreen.kt`，651 行）- 技能 / MCP / 无线 ADB / 提示词 四段式 Tab 管理页- 技能卡片列表 + 批量选择 + 导出（JSON 到剪贴板）+ 导入- MCP 服务器增删改查 + 有效性测试 + 一键绑定为 Skill - 无线 ADB 配对引导（配对码输入 / 状态提示 / 错误恢复）- 提示词模板编辑 + 占位符说明"
          ]
        },
        {
          "type": "增强",
          "items": [
            "`MainViewModel`：新增 skills / mcp / adb / template 相关 StateFlow 与操作方法（+133 行）",
            "`IntentTranslator`：适配 Skill 系统，支持从 Skill 到 Intent 的扩展转译（+125 行）",
            "`AgentIntent` / `UiElement`：模型字段扩展",
            "`AppModule`：Koin 依赖注入更新，接入 SkillRegistry / McpManager / PromptTemplateStore"
          ]
        },
        {
          "type": "修复",
          "items": [
            "`SkillManagerScreen`：`selected` 状态改用 `mutableStateOf(mutableSetOf())` 加委托，确保 UI 正确重组",
            "`SkillManagerScreen`：`LocalContext.current` 在 `onClick` lambda 中非法调用 → 提升到 Composable 顶层一次性读取",
            "`HomeScreen`：补充 `Icons.Rounded.Devices` 图标导入"
          ]
        },
        {
          "type": "测试",
          "items": [
            "新增 12 个单元测试：- `skill/SkillRegistryTest` / `SkillCompatTest` / `SkillExecutionGatewayTest` - `mcp/McpClientTest` - `shizuku/adb/ShizukuBootstrapTest` - `prompt/PromptTemplateEngineTest` - `perception/ControlTreeBuilderTest` - `execution/LongRunModelTest` / `RealModelDecisionTest` / `SimulatedTaskRunTest` - `execution/IntentTranslatorTest` / `IntentTranslatorStrategyTest`"
          ]
        }
      ]
    },
    {
      "version": "v0.1.138",
      "date": "2026-09-01",
      "summary": "对应提交 `f6caefa`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**统一截图入口** `app/src/main/java/com/phoneagent/screen/ScreenCapture.kt` - 优先使用无障碍服务 `takeScreenshotBitmap()`（API 30+，无需 MediaProjection 前台服务）- 回退到 `ScreenSharingService`（MediaProjection 路径，兼容 API < 30）- 截图前自动隐藏悬浮窗，结束后恢复，避免污染画面",
            "**调试记录持久化** `app/src/main/java/com/phoneagent/debug/DebugRecordsStore.kt` - 日志 / 步骤轨迹 / 执行历史 / 对话写入 app 内部目录 `hpa_debug_hist/` - AgentEngine 启动时回载，重启不丢- 截图缩略图统一压缩到 360px 宽，降低内存与磁盘占用",
            "单元测试：`ShellCommandsTest`（282 行）、`IntentTranslatorStrategyTest`（124 行）"
          ]
        },
        {
          "type": "重构",
          "items": [
            "`IntentTranslator`：`internal fun interface IntentTranslationStrategy` 改为 `private fun interface` - 解决 `TranslationContext`（文件内类型）被 `internal` 接口方法签名暴露导致的 Kotlin 编译错误- 对外暴露的仅保留 `class IntentTranslator` 本身，策略实现类变为文件内私有细节",
            "`AgentEngine`：接入 `ScreenCapture` 与 `DebugRecordsStore`",
            "`EngineRules`：规则引擎硬约束增强（+24 行）",
            "`AgentAccessibilityService`：截图能力扩展（+54 行）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.137",
      "date": "2026-08-31",
      "summary": "对应提交 `fc567c2`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**IntentTranslator 转译层** `app/src/main/java/com/phoneagent/execution/IntentTranslator.kt` - 将 AI 决策 `AgentIntent` → 端侧执行指令 `AgentAction` 的独立转译层- 策略模式：`OpenAppStrategy` / `TargetLocationStrategy` / `ScrollToStrategy`，每种意图一颗策略- `TranslationContext`：通道模式 / 页面快照 / 视觉坐标 hint / 公共动作基线- 新增意图只需实现 `IntentTranslationStrategy` 并注册进分派表",
            "**IntentResolver** `execution/IntentResolver.kt`：目标控件定位分发路由",
            "**AppNameResolver** `execution/AppNameResolver.kt`：App 名称 → 包名解析",
            "**CapabilityManager** `execution/CapabilityManager.kt`：通道能力探测（无障碍 / Shizuku / MediaProjection）",
            "**EngineRules** `agent/EngineRules.kt`：硬约束规则引擎（拦截危险动作 / 只读模式横切约束）",
            "**FloatingUi** `floating/FloatingUi.kt`：悬浮窗 UI 独立组件，从 `FloatingWindowService` 解耦",
            "**TemplateMatcher** `task/TemplateMatcher.kt`：长线任务模板匹配引擎",
            "**AgentIntent** `model/AgentIntent.kt`：标准化意图数据模型"
          ]
        },
        {
          "type": "重构",
          "items": [
            "`AgentEngine`：±673 行大重构，职责拆分，接入转译层 + 规则引擎",
            "`AgentPrompts`：提示词大更新",
            "`FloatingWindowService`：+307 行，悬浮窗增强",
            "`AiClient` / `ActionExecutor` / `LocalDecisionEngine` / `CloudAgent`：适配新架构"
          ]
        },
        {
          "type": "基础设施",
          "items": [
            "GitHub Actions CI：`.github/workflows/android-ci.yml`",
            "6 个单元测试类初始入库"
          ]
        }
      ]
    },
    {
      "version": "v0.1.136",
      "date": "2026-08-22",
      "summary": "对应提交 `6248dd1`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**长线任务系统** `app/src/main/java/com/phoneagent/task/TaskStore.kt` - 检查点持久化（DataStore）+ 模板库 + 执行策略- 中断恢复 / 任务分支 / 策略切换",
            "**AI 日志翻译** `app/src/main/java/com/phoneagent/debug/HumanTranslator.kt` - 将调试页的 AI 决策信息翻译为中文，方便非技术用户阅读",
            "**执行状态主动通知** `app/src/main/java/com/phoneagent/debug/ActiveNotifier.kt`",
            "**长线任务设置页** `app/src/main/java/com/phoneagent/ui/settings/SettingsLongRun.kt`",
            "**视觉控件数据模型** `app/src/main/java/com/phoneagent/vision/DetectedControl.kt`（统一外挂视觉 Agent 入口，替换原 LocalUiDetector / LocalVisionEngine）"
          ]
        },
        {
          "type": "增强",
          "items": [
            "`AgentEngine`：+374 行（长线任务逻辑 / 视觉回退 / 温度策略）",
            "`DebugScreen`：+392 行（双语 / 对比 / 翻译面板）",
            "`PageAnnotator`：+244 行（页面标注增强）"
          ]
        }
      ]
    },
    {
      "version": "v0.1.134",
      "date": "2026-08-22",
      "summary": "对应提交 `76c93c9`。",
      "sections": [
        {
          "type": "修复",
          "items": [
            "`AgentEngine`：元素树稀疏时（游戏 / WebView）自动截图并交给视觉模型处理，作为无障碍回退",
            "视觉回退配置项在设置页的接入"
          ]
        }
      ]
    },
    {
      "version": "v0.1.133",
      "date": "2026-08-22",
      "summary": "对应提交 `4e50e33`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**外部视觉服务 AIDL** `app/src/main/aidl/com/phoneagent/ondevice/IVisionService.aidl` - 跨进程视觉识别接口，允许外挂视觉 Agent 接入",
            "**外挂视觉 Provider** `app/src/main/java/com/phoneagent/vision/ExternalVisionProvider.kt`",
            "**Markdown 预览组件** `app/src/main/java/com/phoneagent/ui/workspace/MarkdownPreview.kt`"
          ]
        }
      ]
    },
    {
      "version": "v0.1.132",
      "date": "2026-08-20",
      "summary": "对应提交 `da76725`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "**ShellCommands** `app/src/main/java/com/phoneagent/agent/ShellCommands.kt` - AI 友好 ADB 命令解析器：`tap` / `swipe` / `key` / `launch` / `input` / `back` / `home` / `enter` 等- 支持比例 / 百分比 / 像素坐标多格式",
            "**ShizukuManager**（原模块增强）- 三态状态管理（UNAVAILABLE / PERMISSION_DENIED / READY）- 高权限 Shell 执行通道",
            "**AdSkipperCore** `app/src/main/java/com/phoneagent/adskip/AdSkipperCore.kt` - 自动识别并跳过广告弹窗 / 开屏广告",
            "**AppPageIndex**：App 页面直达索引（深链）",
            "**WorkAreaEngine**：工作区文件管理",
            "**TaskProgressNotifier**：系统通知进度",
            "**EdgeLighting**：曲面边缘光效"
          ]
        },
        {
          "type": "修复",
          "items": [
            "`.gitignore` 添加 `BUGS/` 与 `.trae/` 排除规则",
            "移除 `BUGS/` 目录（改用 GitHub Issues 跟踪）"
          ]
        },
        {
          "type": "文档",
          "items": [
            "`README.md` 全面更新，补充所有新模块说明与版本历史"
          ]
        }
      ]
    },
    {
      "version": "v0.1.130",
      "date": "2026-08-13",
      "summary": "对应提交 `4eb400d`。",
      "sections": [
        {
          "type": "新增",
          "items": [
            "Happy Phone Agent (Phtomt) 首次开源",
            "基于 Jetpack Compose + Material 3 Expressive 的 Android Agent 框架",
            "多模型链路聚合（主模型 + 视觉模型 + 思考模型）",
            "ReAct 执行循环（观察 → 决策 → 执行 → 验证）",
            "无障碍服务权限申请 / 悬浮窗权限管理",
            "敏感页面检测 + 数据脱敏",
            "页面指纹验证（防止执行过程中 App 跳转）",
            "Koin 依赖注入",
            "版本号自动递增（`version.properties` + Gradle 构建钩子）",
            "MIT License"
          ]
        },
        {
          "type": "基础设施",
          "items": [
            "Git 初始化 + Gitee / GitHub 双远程推送",
            "`README.md` / `LICENSE` / `.gitignore`"
          ]
        }
      ]
    }
  ],
  "docs": [
    {
      "slug": "getting-started",
      "title": "快速开始",
      "group": "入门",
      "order": 1,
      "summary": "从下载安装到跑通第一个任务。",
      "body": "## 环境要求\n\n| 项目 | 要求 |\n| --- | --- |\n| 系统 | Android 8.0（API 26）及以上 |\n| 架构 | arm64-v8a / armeabi-v7a |\n| 网络 | 需要能访问你配置的模型服务 |\n| 可选 | Shizuku（高权限执行）、Termux（命令行取数） |\n\n## 安装\n\n1. 到 [下载页](/download) 取最新 APK，直接安装（Debug 包需允许「安装未知来源应用」）。\n2. 打开应用，按首页的**权限雷达**逐项授权。\n\n## 必须授予的权限\n\n- **无障碍服务** —— 读取屏幕控件并执行点击、滑动、输入。这是 Agent 的手和眼，不授予则完全无法工作。\n- **悬浮窗** —— 显示实时进度与接管面板。可在设置里关掉，关掉后任务照常执行，只是看不到浮窗。\n- **通知权限** —— 前台服务与进度通知。\n- **屏幕录制**（可选） —— 仅当需要云端视觉模型截图时使用。\n\n## 配置模型\n\n进入**设置 → 模型配置**，填写：\n\n- **API 地址**：任意 OpenAI 兼容端点，例如 `http://192.168.1.5:8000/v1`、`http://localhost:11434/v1`。不写协议会自动补全：内网地址、localhost、单段主机名补 `http://`，公网域名补 `https://`。\n- **API Key**\n- **模型名**\n\n填完点**测试连接**，通过后才允许保存——避免把错误配置写进 DataStore 之后再花时间排查。\n\n## 跑通第一个任务\n\n回到首页，在输入框里写一句人话，比如「打开设置，把屏幕亮度调到一半」。点击开始后：\n\n1. 悬浮窗出现，跑马灯滚动显示当前步骤。\n2. Agent 会先规划 3~8 步，然后逐步执行。\n3. 遇到需要确认的操作，浮窗会停下来等你点。\n\n如果某一步卡住，直接在浮窗输入框里补充说明即可，任务不会中断。"
    },
    {
      "slug": "model-config",
      "title": "模型配置",
      "group": "入门",
      "order": 2,
      "summary": "主模型、视觉模型、思考模型各自负责什么，以及怎么填。",
      "body": "## 三类模型\n\n| 类型 | 负责 | 是否必需 | 默认 |\n| --- | --- | --- | --- |\n| 主模型 | 决策、规划、意图理解 | 必需 | — |\n| 视觉模型 | 截图描述、坐标定位 | 可选 | glm-4.6v-flash |\n| 思考模型 | 复杂规划、歧义检测、重规划 | 可选 | 回退主模型密钥 |\n\n主模型必须支持结构化输出（JSON）。视觉模型必须支持图像输入——主模型不处理图片。\n\n## 链路聚合开关\n\n**默认关闭**。关闭时只跑「主模型 + 可选视觉模型」，速度快、额度省。\n\n开启后：\n\n- 简单页面（元素树可读）仍然跳过云端视觉，直接用元素树决策；\n- 复杂页面（元素树稀疏）交给视觉模型描述与定位；\n- 规划与重规划阶段交给思考模型，主模型只负责单步决策。\n\n三者都可在设置页图形化配置（地址 / 模型名 / 密钥）。思考模型的密钥留空时自动回退主模型密钥。\n\n## API 地址写法\n\n支持 http://、内网地址与 localhost，不写协议时自动补全：\n\n```text\n192.168.1.5:8000/v1        → http://192.168.1.5:8000/v1\nlocalhost:11434/v1         → http://localhost:11434/v1\nmyserver:8080/v1           → http://myserver:8080/v1\napi.example.com/v1         → https://api.example.com/v1\n```\n\n应用已放行明文流量（`usesCleartextTraffic`），内网 http:// 服务可以直接用，不必套反向代理加 TLS。\n\n## 保存规则\n\n**只有测试连接通过才允许保存。** 这条规则是硬性的：模型配置一旦写错，后续所有任务都会以「AI 返回无法解析」的形式失败，排查成本远高于当场拦住。"
    },
    {
      "slug": "execution-channels",
      "title": "执行通道",
      "group": "进阶",
      "order": 3,
      "summary": "无线 ADB、Shizuku、Termux、无障碍四者的能力边界与选择建议。",
      "body": "## 四条通道，能力完全不同\n\n| 通道 | 权限级别 | 能做什么 |\n| --- | --- | --- |\n| 无障碍 | 普通应用权限 | 读控件树、点击、滑动、输入文字 |\n| 无线 ADB | shell（adb） | `am` / `pm` / `settings` 等系统命令 |\n| Shizuku | shell（adb） | 同上，通过 Shizuku 授权获得 |\n| Termux | 普通应用权限 | curl / python / 文本处理，**不能**执行系统命令 |\n\n## 默认策略：无线 ADB 为主\n\n设置里的执行通道是三态偏好：`AUTO` / `ADB` / `SHIZUKU`（Termux 作为补充通道参与 AUTO 降级）。\n\n`AUTO` 下的顺序是：**无线 ADB → Shizuku → Termux → 无障碍**。\n\n无线 ADB 是主通道的理由很实际：Android 11+ 自带无线调试，配对一次即可长期使用，拿到的是货真价实的 shell 权限，且不需要额外安装任何应用。Shizuku 退居可选增强——拉起失败或超时都不会阻塞主流程。\n\n## 为什么启动应用要走 shell\n\nAgent 启动目标应用一律用 `launch`（底层是 `monkey -p <包名> 1`），而不是回到桌面点图标。原因有两条：\n\n1. 桌面图标的位置和数量随时会变，坐标点击不稳定；\n2. 点图标会引入「桌面是不是在前台」这个额外状态判断。\n\nAI 若只给了裸包名（例如 `com.tencent.mm`），端侧会自动补全为完整的 launch 动作。\n\n## Termux 的前置条件\n\n三项缺一不可：\n\n1. 已安装 Termux；\n2. 已授予 `com.termux.permission.RUN_COMMAND`；\n3. Termux 侧 `allow-external-apps=true`。\n\n结果回传走 **PendingIntent**，不走结果目录文件——后者在 `allow-external-apps` 未开启时会永久挂起。"
    },
    {
      "slug": "intent-layer",
      "title": "意图转译层",
      "group": "进阶",
      "order": 4,
      "summary": "为什么 AI 永远不输出坐标和命令，以及 15 个语义意图是怎么落地的。",
      "body": "## 设计前提\n\n让语言模型直接输出坐标和 shell 命令，会同时引入两类问题：\n\n- **不稳定**：同一个控件在不同分辨率、不同主题下的坐标不同；\n- **不可控**：模型可以输出任意命令，安全边界形同虚设。\n\n所以本项目把 AI 的输出面收窄成 15 个**高层语义意图**，由端侧 IntentTranslator 实时转译成已有的 ActionType。\n\n## 15 个语义意图\n\n```text\nback  home  refresh  search  send  confirm  close\nshare  collect  copy  delete  download  add  switch  clear_input\n```\n\n每个意图对应一个策略类（SemanticActionStrategy / BackStrategy / HomeStrategy），本地执行，零网络往返。\n\n## 定位优先级\n\n1. **id / label** —— 无障碍元素树里有精确标识时优先使用，执行层自动算坐标；\n2. **scroll_to** —— 当前屏幕找不到目标控件时先滚动查找；\n3. **coordinate** —— 只有图片、图表这类元素树里读不到的控件才允许用坐标；\n4. **abort** —— 以上都不成立就中止，并说明当前前台应用与缺失的控件名。\n\n报错信息里必须带**当前前台应用**，而不是笼统的「无法定位控件」——否则 AI 下一轮仍然不知道该做什么。\n\n## 一步一个动作\n\n同一轮只做一个明确动作，不叠加小动作，同一控件不反复操作。规划阶段的步数动态收敛到 3~8 步，宁可少而准。"
    },
    {
      "slug": "safety",
      "title": "安全与隐私",
      "group": "进阶",
      "order": 5,
      "summary": "哪些页面只读、哪些数据会被脱敏、哪些操作必须你点头。",
      "body": "## 敏感页面只读\n\nSensitivePageDetector 识别支付、个人信息、密码等敏感页面，命中后由 EngineRules 横切为**只读**：Agent 可以看、可以描述，但不能提交。\n\n## 数据脱敏\n\n进模型上下文之前，以下数据会被遮蔽：\n\n| 类型 | 规则 |\n| --- | --- |\n| 手机号 | 11 位连续数字 |\n| 身份证号 | 18 位（含末位 X） |\n| 银行卡号 | 长位数字串 |\n\n脱敏发生在构造提示词的环节，不是事后过滤——被遮蔽的内容从未离开过设备。\n\n## 硬约束规则引擎\n\nEngineRules 在动作真正下发**之前**拦截：\n\n- 敏感页上的写操作；\n- 结构化错误的命令（未知命令、空命令、参数非法）——直接跳过 3 次无效重试并立即失败，同时把错误原因与可用命令列表注入下一轮决策上下文。\n\n## 不确定时的行为\n\n系统提示词里明确禁止「不确定就输出 task_done」。模型必须：\n\n1. 先尝试可行解法；\n2. 卡住时输出 `abort` 并说明卡点；\n3. **不得编造不存在的命令或动作。**\n\n## 完成任务的门槛\n\nAI 只有在**当前页面上亲眼看到任务完成的明确证据**时才允许输出 `task_done`，且总结里必须写清证据是什么。执行层还会做二次把关：至少执行 3 步、且达到规划步数的 60%，才接受完成判定；连续 3 次过早完成才强制收尾，防止死循环。"
    },
    {
      "slug": "architecture",
      "title": "架构总览",
      "group": "进阶",
      "order": 6,
      "summary": "感知、决策、执行、视觉、持久化、展示六层怎么协作。",
      "body": "## 执行循环\n\n```text\n观察屏幕 → 页面标注 → 端侧/云端决策 → 双通道执行 → 带验证 → 记录 → 循环\n```\n\n1. **观察** —— 无障碍服务取回屏幕全部可交互元素。\n2. **标注** —— PageAnnotator 为 26 类常见控件分配语义 ID（`dlg_allow`、`ad_skip`、`search_box`、`send_btn` …），直接注入 AI 上下文，让模型能按 id 选控件。\n3. **决策** —— LocalDecisionEngine 先处理高频场景，其余交给模型。\n4. **执行** —— 通道降级执行，动作前后做页面指纹比对。\n5. **记录** —— 落盘供多轮对话与诊断导出使用。\n\n## 分层\n\n```text\ncore/       跨层基础设施（AI 客户端、脱敏、通知、人话翻译）\ndomain/     纯领域模型与规则（动作、意图、规则引擎、端侧决策）\ndata/       持久化（DataStore 配置 + 五处 Store）\ndevice/     设备能力（无障碍、shell、截图、视觉）\nengine/     Agent 编排（ReAct 主循环、转译层、感知、提示词）\noverlay/    悬浮窗\nfeature/    功能域（任务、技能、MCP、文档、跳广告、边缘光效、测试）\nui/         界面层（页面 = 入口 + 单一职责拆分文件）\n```\n\n## 持久化\n\n| 存储 | 内容 |\n| --- | --- |\n| AppSettings | DataStore 配置（模型、通道、语言、开关） |\n| MemoryStore | 任务记忆（目标 / 用户要求 / 已验证方法 / 状态） |\n| TaskStore | 检查点 + 任务模板库 |\n| DebugRecordsStore | 日志 / 轨迹 / 历史 / 对话 |\n| McpStore | MCP 服务与技能配置 |\n| PromptTemplateStore | 提示词模板 |\n\n## 上下文卫生\n\n每次 `run()` 开头会彻底清空 AI 上下文：对话消息、失败计数、无效命令计数、上一条 shell 输出、上一页截图、上一任务计划、工作记忆。任务之间不串味。"
    },
    {
      "slug": "troubleshooting",
      "title": "疑难排查",
      "group": "支持",
      "order": 7,
      "summary": "连不上模型、点了没反应、浮窗不出现、任务老是中止。",
      "body": "## 测试连接失败\n\n- **地址不完整** —— 现在会自动补协议。内网写 `192.168.1.5:8000/v1` 即可。\n- **明文被拦** —— 应用已放行 `usesCleartextTraffic`，若仍失败请检查路由器是否隔离了客户端。\n- **模型名不对** —— 部分服务要求带前缀，例如 `openai/gpt-4o`。\n- **不支持结构化输出** —— 主模型必须能返回 JSON，纯对话模型会持续报解析失败。\n\n## 点了没反应\n\n先看调试页的**步骤**面板，那里会写清每一步的判定结果：\n\n- 「控件不在当前元素树」—— 说明 AI 想点的东西不在前台页面上，报错会带上当前前台应用名；\n- 「点击未生效」—— 页面指纹前后一致，说明点空了，Agent 会重试或改用其它定位方式。\n\n## 悬浮窗不出现\n\n1. 检查**显示在其他应用上层**权限是否授予；\n2. 检查设置里悬浮窗开关是否被关掉（关掉后任务仍会执行，只是没有浮窗）；\n3. 部分厂商系统需要在「后台弹出界面」里单独放行。\n\n## 任务反复中止\n\n常见原因与对策：\n\n| 现象 | 原因 | 对策 |\n| --- | --- | --- |\n| 连续 3 次结构化错误 | AI 用了不存在的命令 | 检查主模型能力，或换更强的模型 |\n| 找不到控件后中止 | 页面没加载完 / 需要滚动 | 在浮窗里补充说明，或改用更具体的入口描述 |\n| 过早判定完成 | 页面证据不足 | 已由执行层拦截；仍出现请导出诊断报告提 Issue |\n\n## 导出诊断\n\n**调试页 → 导出**，会打包日志、步骤轨迹、历史对话与能力状态。提 Issue 时附上它，比描述现象有用得多。"
    }
  ],
  "faq": [
    {
      "id": "need-root",
      "order": 1,
      "group": "使用",
      "q": "需要 Root 吗？",
      "a": "不需要。基础能力靠无障碍服务即可运行。想要系统级命令（am / pm / settings）时，用 Android 11+ 自带的无线调试配对，或安装 Shizuku——两者都不需要 Root。"
    },
    {
      "id": "which-model",
      "order": 2,
      "group": "使用",
      "q": "必须用哪家的模型？",
      "a": "任意 OpenAI 兼容端点都可以，包括自建的 Ollama、LM Studio、one-api。主模型需要支持结构化输出；如果要开视觉能力，另配一个支持图像输入的模型（默认 glm-4.6v-flash）。"
    },
    {
      "id": "privacy",
      "order": 3,
      "group": "安全",
      "q": "我的屏幕内容会上传到哪？",
      "a": "只发给你自己配置的模型服务。手机号、身份证号、银行卡号在构造提示词时就已脱敏，被遮蔽的内容从未离开设备。支付等敏感页面会被自动切换为只读。"
    },
    {
      "id": "free",
      "order": 4,
      "group": "使用",
      "q": "收费吗？",
      "a": "应用本身基于 MIT 协议开源，不收费。你只需要为自己使用的模型服务付费（如果用云端 API）。"
    },
    {
      "id": "apk-source",
      "order": 5,
      "group": "下载",
      "q": "下载页的 APK 是什么构建？",
      "a": "当前提供的是 Debug 构建，用于内测与自用，需要手动授予权限。每个版本的 SHA-256 校验值都写在下载页上，安装前可自行核对。"
    },
    {
      "id": "install-fail",
      "order": 6,
      "group": "下载",
      "q": "安装提示「应用未安装」怎么办？",
      "a": "多数情况是签名冲突：先卸载旧版本再装。也可能是下载不完整——对照下载页的 SHA-256 校验一下文件。"
    },
    {
      "id": "not-working",
      "order": 7,
      "group": "故障",
      "q": "任务总是执行一半就停。",
      "a": "先看调试页的步骤面板，那里会给出每一步的判定原因（控件不在当前页面、点击未生效等）。最常见的原因是页面还没加载完，或者描述的目标不够具体。在悬浮窗里补充一句说明通常就能继续。"
    },
    {
      "id": "overlay-missing",
      "order": 8,
      "group": "故障",
      "q": "悬浮窗不显示。",
      "a": "检查三处：是否授予「显示在其他应用上层」权限；设置里悬浮窗开关是否被关掉；厂商系统是否需要在「后台弹出界面」单独放行。关掉悬浮窗不影响任务执行，只是看不到实时进度。"
    },
    {
      "id": "contribute",
      "order": 9,
      "group": "开发",
      "q": "怎么参与开发？",
      "a": "项目在 GitHub 与 Gitee 同步开源。Fork 后开特性分支，提交信息遵循 Conventional Commits，UI 变更需符合 Material 3 标准，然后发 PR。"
    },
    {
      "id": "api-update",
      "order": 10,
      "group": "开发",
      "q": "能自动更新官网内容吗？",
      "a": "可以。官网提供完整的 REST API，包括版本发布、APK 上传、CHANGELOG 导入与文案更新，全部可用脚本或 CI 调用。见「官网内容 API」文档。"
    }
  ],
  "routes": {
    "routes": [
      {
        "path": "/",
        "name": "home",
        "title": "首页",
        "group": "主要"
      },
      {
        "path": "/features",
        "name": "features",
        "title": "功能特性",
        "group": "主要"
      },
      {
        "path": "/how-it-works",
        "name": "how",
        "title": "工作原理",
        "group": "主要"
      },
      {
        "path": "/download",
        "name": "download",
        "title": "下载",
        "group": "主要"
      },
      {
        "path": "/changelog",
        "name": "changelog",
        "title": "更新日志",
        "group": "内容"
      },
      {
        "path": "/changelog/:version",
        "name": "changelog-detail",
        "title": "版本详情",
        "group": "内容"
      },
      {
        "path": "/docs",
        "name": "docs",
        "title": "文档中心",
        "group": "内容"
      },
      {
        "path": "/docs/:slug",
        "name": "docs-detail",
        "title": "文档详情",
        "group": "内容"
      },
      {
        "path": "/faq",
        "name": "faq",
        "title": "常见问题",
        "group": "内容"
      },
      {
        "path": "/about",
        "name": "about",
        "title": "关于项目",
        "group": "其他"
      },
      {
        "path": "/admin",
        "name": "admin",
        "title": "管理后台",
        "group": "其他"
      }
    ],
    "endpoints": [
      {
        "method": "GET",
        "path": "/api/health",
        "desc": "服务健康检查与内容集合计数"
      },
      {
        "method": "GET",
        "path": "/api/site",
        "desc": "站点元信息 + 最新版本 + 下载统计"
      },
      {
        "method": "GET",
        "path": "/api/bootstrap",
        "desc": "首屏聚合：站点 / 特性 / 版本 / 更新 / FAQ / 文档索引"
      },
      {
        "method": "GET",
        "path": "/api/features",
        "desc": "功能特性列表"
      },
      {
        "method": "GET",
        "path": "/api/releases",
        "desc": "版本列表，支持 ?channel=stable|beta|all &limit="
      },
      {
        "method": "GET",
        "path": "/api/releases/latest",
        "desc": "最新可下载版本，支持 ?channel="
      },
      {
        "method": "GET",
        "path": "/api/releases/:version",
        "desc": "指定版本详情"
      },
      {
        "method": "GET",
        "path": "/api/changelog",
        "desc": "更新日志，支持 ?limit= 或 ?page=&size="
      },
      {
        "method": "GET",
        "path": "/api/changelog/:version",
        "desc": "指定版本的更新记录"
      },
      {
        "method": "GET",
        "path": "/api/docs",
        "desc": "文档索引（不含正文）"
      },
      {
        "method": "GET",
        "path": "/api/docs/:slug",
        "desc": "单篇文档（含 Markdown 正文）"
      },
      {
        "method": "GET",
        "path": "/api/faq",
        "desc": "常见问题，支持 ?group="
      },
      {
        "method": "GET",
        "path": "/api/stats",
        "desc": "下载统计：总量 / 分版本 / 近 14 天"
      },
      {
        "method": "GET",
        "path": "/api/routes",
        "desc": "站点路由表与端点清单"
      },
      {
        "method": "GET",
        "path": "/api/openapi",
        "desc": "OpenAPI 3.1 规范（机器可读）"
      },
      {
        "method": "GET",
        "path": "/api/download/latest",
        "desc": "重定向到最新版 APK"
      },
      {
        "method": "GET",
        "path": "/api/download/:version",
        "desc": "下载指定版本 APK（计入统计）"
      },
      {
        "method": "GET",
        "path": "/apk/:file",
        "desc": "APK 静态直出（带 Range 与缓存）"
      },
      {
        "method": "POST",
        "path": "/api/admin/login",
        "desc": "口令换取管理令牌"
      },
      {
        "method": "GET",
        "path": "/api/admin/session",
        "desc": "校验当前令牌是否有效"
      },
      {
        "method": "GET",
        "path": "/api/admin/overview",
        "desc": "后台概览：计数 / 存储占用 / 最近操作"
      },
      {
        "method": "GET",
        "path": "/api/admin/audit",
        "desc": "操作留痕（最近 300 条）"
      },
      {
        "method": "GET",
        "path": "/api/admin/apk",
        "desc": "磁盘上的 APK 文件清单"
      },
      {
        "method": "POST",
        "path": "/api/admin/apk",
        "desc": "上传 APK（multipart，字段名 file）"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/apk/:file",
        "desc": "删除 APK 文件"
      },
      {
        "method": "GET",
        "path": "/api/admin/content/:name",
        "desc": "读取原始集合（site / features / releases / changelog / docs / faq）"
      },
      {
        "method": "PUT",
        "path": "/api/admin/content/:name",
        "desc": "整包写入原始集合"
      },
      {
        "method": "PUT",
        "path": "/api/admin/site",
        "desc": "整包更新站点元信息"
      },
      {
        "method": "PATCH",
        "path": "/api/admin/site",
        "desc": "局部更新站点元信息（深合并）"
      },
      {
        "method": "PUT",
        "path": "/api/admin/features",
        "desc": "整包替换功能特性列表"
      },
      {
        "method": "POST",
        "path": "/api/admin/features",
        "desc": "新增一条特性"
      },
      {
        "method": "PUT",
        "path": "/api/admin/features/:id",
        "desc": "更新指定特性"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/features/:id",
        "desc": "删除指定特性"
      },
      {
        "method": "POST",
        "path": "/api/admin/releases",
        "desc": "发布新版本（可带 apk 文件名绑定）"
      },
      {
        "method": "PUT",
        "path": "/api/admin/releases/:version",
        "desc": "更新版本信息"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/releases/:version",
        "desc": "删除版本"
      },
      {
        "method": "PUT",
        "path": "/api/admin/changelog",
        "desc": "整包替换更新日志"
      },
      {
        "method": "POST",
        "path": "/api/admin/changelog",
        "desc": "新增一条更新日志"
      },
      {
        "method": "PUT",
        "path": "/api/admin/changelog/:version",
        "desc": "更新指定版本日志"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/changelog/:version",
        "desc": "删除指定版本日志"
      },
      {
        "method": "PUT",
        "path": "/api/admin/docs",
        "desc": "整包替换文档"
      },
      {
        "method": "POST",
        "path": "/api/admin/docs",
        "desc": "新增文档"
      },
      {
        "method": "PUT",
        "path": "/api/admin/docs/:slug",
        "desc": "更新文档"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/docs/:slug",
        "desc": "删除文档"
      },
      {
        "method": "PUT",
        "path": "/api/admin/faq",
        "desc": "整包替换 FAQ"
      },
      {
        "method": "POST",
        "path": "/api/admin/faq",
        "desc": "新增 FAQ"
      },
      {
        "method": "PUT",
        "path": "/api/admin/faq/:id",
        "desc": "更新 FAQ"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/faq/:id",
        "desc": "删除 FAQ"
      },
      {
        "method": "POST",
        "path": "/api/admin/import/changelog",
        "desc": "从 CHANGELOG.md 文本导入更新日志"
      }
    ]
  },
  "bootstrap": {
    "site": {
      "name": "Happy Phone Agent",
      "shortName": "Happy Agent",
      "slug": "phtomt",
      "tagline": "让手机自己动手",
      "description": "Happy Phone Agent 是一款运行在 Android 上的 AI 智能体。你用一句自然语言描述目标，它自己观察屏幕、拆解步骤、动手执行——点按、滑动、输入、跳转，全程可见可接管。",
      "version": "0.1.384",
      "buildNumber": 384,
      "updatedAt": "2026-09-20T13:40:27.690Z",
      "links": {
        "github": "https://github.com/xkx121029/Phtomt",
        "gitee": "https://gitee.com/xkx1029/Phtomt",
        "issues": "https://github.com/xkx121029/Phtomt/issues",
        "releases": "https://github.com/xkx121029/Phtomt/releases",
        "changelog": "https://github.com/xkx121029/Phtomt/blob/main/CHANGELOG.md"
      },
      "requirements": {
        "minAndroid": "8.0",
        "minApi": 26,
        "targetApi": 35,
        "arch": "arm64-v8a / armeabi-v7a",
        "packageName": "com.phoneagent"
      },
      "techStack": [
        {
          "name": "Kotlin",
          "version": "2.0.21"
        },
        {
          "name": "Jetpack Compose",
          "version": "2024.12.01"
        },
        {
          "name": "Material 3",
          "version": "1.3.1"
        },
        {
          "name": "Koin",
          "version": "4.0.0"
        },
        {
          "name": "OkHttp",
          "version": "4.12.0"
        },
        {
          "name": "DataStore",
          "version": "1.1.1"
        }
      ],
      "channels": [
        {
          "id": "accessibility",
          "name": "无障碍通道",
          "level": "普通应用权限",
          "desc": "通过 AccessibilityService 读取控件树并执行点击、滑动、输入，兼容性最好，无需额外安装。",
          "default": true
        },
        {
          "id": "adb",
          "name": "无线 ADB 通道",
          "level": "shell 权限",
          "desc": "手机自带无线调试，配对后即可执行 am / pm / settings 等系统命令，应用启动走真实包名而非模拟点按。",
          "default": false
        },
        {
          "id": "shizuku",
          "name": "Shizuku 通道",
          "level": "shell 权限",
          "desc": "可选增强，借助 Shizuku 拿到 ADB 级权限；未安装或授权失败不影响无线 ADB 主通道。",
          "default": false
        },
        {
          "id": "termux",
          "name": "Termux 通道",
          "level": "普通应用权限",
          "desc": "把命令交给 Termux 执行并回读输出，为「图形界面做不到」的取数场景补一条 Linux 工具链。",
          "default": false
        }
      ],
      "latest": {
        "version": "v0.1.384",
        "build": 384,
        "channel": "stable",
        "date": "2026-09-20",
        "published": true,
        "title": "当前开发版",
        "summary": "AI 此前是在\"真空\"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住 上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。 本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、 **会话承接**（上一轮任务的目标与结论）。",
        "apk": {
          "file": "HappyAgent-0.1.384.apk",
          "size": 14959209,
          "sizeText": "14 MB",
          "sha256": "b486f578d860219bd11a9ff969360f55446524dbd584bf7fb83bd376a96ffb23"
        },
        "notes": [
          "Debug 构建，用于内测与自用",
          "首次安装需手动授予无障碍、悬浮窗与通知权限"
        ],
        "downloads": 2
      },
      "stats": {
        "totalDownloads": 2,
        "lastDownloadAt": "2026-09-20T14:15:16.573Z",
        "byVersion": {
          "v0.1.384": 2
        },
        "recent": [
          {
            "day": "2026-09-20",
            "count": 2
          }
        ],
        "apkTotalSize": 14959209,
        "apkTotalSizeText": "14 MB"
      }
    },
    "features": [
      {
        "id": "decision",
        "order": 1,
        "tag": "决策",
        "title": "AI 驱动决策",
        "summary": "ReAct 主循环：观察 → 规划 → 执行 → 校验，每一步都基于当前真实页面。",
        "detail": "接入任意 OpenAI 兼容端点（自建 Ollama / LM Studio / one-api 都行）。规划时步数动态收敛到 3~8 步，执行中每一步都以当前页面元素树为唯一依据——目标控件不在当前页面就不允许点击，找不到先滚动查找，仍找不到则中止并说明原因。"
      },
      {
        "id": "intent",
        "order": 2,
        "tag": "执行",
        "title": "15 个高层语义意图",
        "summary": "AI 只说「做什么」，端侧转译层负责「怎么做」，模型永远不输出坐标和命令。",
        "detail": "back / home / refresh / search / send / confirm / close / share / collect / copy / delete / download / add / switch / clear_input 全部由 IntentTranslator 实时转译为端侧动作。有精确 id/label 就用它，只有图片、图表这类元素树里没有的控件才退到坐标。"
      },
      {
        "id": "vision",
        "order": 3,
        "tag": "感知",
        "title": "双引擎视觉理解",
        "summary": "元素树可读时走端侧，稀疏时自动切云端视觉模型补位。",
        "detail": "无障碍能读到的控件（元素数 > 3）视为简单页面，优先端侧处理；元素树稀疏（≤ 3）视为复杂页面，自动启用云端视觉模型做截图描述与坐标定位，即使截图开关是关的也会兜底。"
      },
      {
        "id": "chain",
        "order": 4,
        "tag": "决策",
        "title": "多模型链路聚合",
        "summary": "主模型决策 + 视觉模型定位 + 思考模型规划，按需组合，默认关闭。",
        "detail": "默认只跑主模型（加可选视觉模型），省额度、省延迟。开启链路聚合后，思考模型负责复杂规划、歧义检测与重规划，视觉模型只负责截图描述与坐标定位。三者的地址、模型名、密钥都在设置页图形化配置。"
      },
      {
        "id": "channel",
        "order": 5,
        "tag": "执行",
        "title": "四通道执行体系",
        "summary": "无线 ADB 为主，Shizuku 可选，Termux 补命令行，无障碍兜底。",
        "detail": "AUTO 模式下按「无线 ADB → Shizuku → Termux」降级。无线 ADB 连接成功即就绪，可直接执行 shell；Shizuku 失败或超时不阻塞主流程。AI 给的裸包名会被自动补全为 launch action，不会因为少写参数而空转。"
      },
      {
        "id": "shell",
        "order": 6,
        "tag": "执行",
        "title": "AI 友好命令解析",
        "summary": "tap / sw / key / text 这类短命令，坐标支持像素、比例、百分比三种写法。",
        "detail": "ShellCommands 把 AI 的自然短命令翻译成真实 ADB 命令，模型不用记 ADB 语法。命令输出会被截取（最多 1200 字）回灌到下一轮决策上下文里，让 AI 看得到自己刚刚做了什么。"
      },
      {
        "id": "safety",
        "order": 7,
        "tag": "安全",
        "title": "安全防护与脱敏",
        "summary": "敏感页面自动只读，手机号 / 身份证 / 银行卡号自动遮蔽。",
        "detail": "EngineRules 作为硬约束规则引擎，在动作真正下发前拦截危险操作；SensitivePageDetector 识别支付、个人信息等页面并切换为只读横切；DataSanitizer 对 11 位手机号、18 位身份证号、银行卡号做脱敏后再进模型上下文。"
      },
      {
        "id": "verify",
        "order": 8,
        "tag": "执行",
        "title": "页面指纹验证",
        "summary": "动作执行前后比对控件特征，确认「点下去了」还是「点空了」。",
        "detail": "VerifiedClickExecutor 在点击前后各取一次页面指纹，不一致才算生效。这一步把「AI 以为点了」和「页面真的变了」区分开，是长线任务不跑偏的关键。"
      },
      {
        "id": "local",
        "order": 9,
        "tag": "性能",
        "title": "端侧快速决策",
        "summary": "弹窗、加载、广告这类高频场景本地直接处理，不花云端额度。",
        "detail": "LocalDecisionEngine 覆盖高频重复场景，命中即本地决策，省掉一次往返。配合 AdSkipperCore 的冷却机制，既不会漏跳也不会反复戳同一个按钮。"
      },
      {
        "id": "adskip",
        "order": 10,
        "tag": "能力",
        "title": "内置跳广告",
        "summary": "独立于 Agent 运行：青少年模式弹窗 > 跳过按钮 > 倒计时角标 > 关闭按钮。",
        "detail": "AdSkipperCore 按优先级识别四类广告形态，配合精确匹配、位置约束与控件类型约束降低误触，内置冷却防止重复点击死循环。"
      },
      {
        "id": "overlay",
        "order": 11,
        "tag": "交互",
        "title": "液态玻璃悬浮窗",
        "summary": "半透明白玻璃，宽 300dp，高度随内容自适应，跑马灯贴屏幕顶边。",
        "detail": "执行时只占「跑马灯 + 标题 + 状态」三行，AI 详情默认折叠，展开可看「发送给 AI / AI 返回 / 审核结论」三段原文。截图时自动隐藏、截完立刻恢复，全过程 ≤ 0.3 秒。任务完成显示打勾动画并停在屏幕上，不自动消失。"
      },
      {
        "id": "takeover",
        "order": 12,
        "tag": "交互",
        "title": "随时接管与纠偏",
        "summary": "AI 拿不准会停下来问，你也能随时插话改方向。",
        "detail": "遇到歧义时悬浮窗弹出澄清选项；执行中可随时输入补充指令，会被追加进任务记忆的「用户要求」而不是覆盖原始目标。「已手动处理」按钮让人工接管后任务继续，而不是直接退出。"
      },
      {
        "id": "memory",
        "order": 13,
        "tag": "记忆",
        "title": "任务记忆",
        "summary": "记住目标、用户要求、已验证方法与进度，长线任务不丢上下文。",
        "detail": "任务记忆独立持久化，按 taskId 归属判定，避免旧任务的收尾逻辑误标新任务。用户中途的每一条补充要求按精确去重后追加保留，不会因为语义相近被合并掉。"
      },
      {
        "id": "queue",
        "order": 14,
        "tag": "任务",
        "title": "多任务队列与检查点",
        "summary": "顺序执行多个任务，随时取消、重规划，模板库沉淀可复用流程。",
        "detail": "TaskStore 保存检查点与任务模板库；TemplateMatcher 命中模板时直接复用验证过的路径。模板必须由软件预置或经用户明确确认后才入库，AI 不能自行创建模板。"
      },
      {
        "id": "skill",
        "order": 15,
        "tag": "扩展",
        "title": "技能与 MCP",
        "summary": "内置技能目录 + 远程 MCP 工具，参数校验与失败原因都是中文。",
        "detail": "SkillCatalog 与 IntentType.ALL 一一对应，被禁用的技能在归一化阶段直接拒绝。MCP 技能调用 20 秒超时、输出截断 1200 字，连续 3 次被拒即停止任务，避免无限循环。"
      },
      {
        "id": "doc",
        "order": 16,
        "tag": "输出",
        "title": "文档结果直出",
        "summary": "需要写文档的任务直接产出结果页，不在屏幕上假装打字。",
        "detail": "write_doc 意图由 DocumentEngine 落盘到应用私有目录，并推给 Agent 页内嵌 Markdown 预览（可展开 / 收起 / 关闭）。提示词里有铁律约束：文档类任务必须直接输出 write_doc，不许打开备忘录打字或用 shell 写文件。"
      },
      {
        "id": "i18n",
        "order": 17,
        "tag": "体验",
        "title": "中英双语与自动翻译",
        "summary": "提示词可手动切换中英；AI 的英文输出自动翻成中文再展示。",
        "detail": "AI 回复若以非中文为主（中文字符占比低于 30%）会自动翻译后再展示，翻译失败则回退原文。翻译结果带缓存去重，不会为同一句话重复付费。"
      },
      {
        "id": "design",
        "order": 18,
        "tag": "体验",
        "title": "Material 3 Expressive",
        "summary": "玄青 · 流萤配色，深色模式、高对比度无障碍、边缘光效全适配。",
        "detail": "语义颜色令牌集中管理，浅色 / 深色 / 高对比度四套主题；入场动画统一为自下而上淡入位移，全局触觉反馈，动效曲线统一收敛在 Motion 模块。"
      }
    ],
    "releases": [
      {
        "version": "v0.1.384",
        "build": 384,
        "channel": "stable",
        "date": "2026-09-20",
        "published": true,
        "title": "当前开发版",
        "summary": "AI 此前是在\"真空\"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住 上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。 本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、 **会话承接**（上一轮任务的目标与结论）。",
        "apk": {
          "file": "HappyAgent-0.1.384.apk",
          "size": 14959209,
          "sizeText": "14 MB",
          "sha256": "b486f578d860219bd11a9ff969360f55446524dbd584bf7fb83bd376a96ffb23"
        },
        "notes": [
          "Debug 构建，用于内测与自用",
          "首次安装需手动授予无障碍、悬浮窗与通知权限"
        ],
        "downloads": 2
      }
    ],
    "changelog": [
      {
        "version": "v0.1.334",
        "date": "2026-09-20",
        "summary": "AI 此前是在\"真空\"里做决策的：它看不到今天几号、当前在哪个应用、有没有网、电量还剩多少，也记不住 上一轮让它做过什么。同一句话「再发一遍」被当成全新任务，「帮我看看装了哪些应用」则只能一步步翻设置页。 本次给它补上三类上下文：**环境事实**（每步自动注入）、**按需取数**（新增 device_query 技能）、 **会话承接**（上一轮任务的目标与结论）。",
        "sections": [
          {
            "type": "新增",
            "items": [
              "**环境上下文默认注入**（`AgentPrompts.environment`）：每次规划与每步决策都带上端侧实时采集的事实—— 当前时间（`yyyy-MM-dd 周几 HH:mm`）、前台应用（`应用名(包名)`）、网络状态、电量（含是否充电）、已安装可启动应用数；缺项整行省略，不出现空标签",
              "**`device_query` 技能：本机信息按需查询**（`IntentType.DEVICE_QUERY` → `ActionType.DEVICE_QUERY`）- 参数 `kind = apps|time|battery|network|storage|all`，`filter` 仅 `kind=apps` 时生效（如「相机」）- 纯本地读取、不触碰设备：只读模式下同样放行（与 remember/wait 同列）- 查询结果作为「上一步结果」回注下一轮决策（截断 1500 字符），AI 拿到事实再决定下一步- `kind` 非法直接失败并列出可选值，不会拿着无效参数空转- **应用清单不给全量**：环境上下文只给个数，要清单必须走 device_query，避免每步都往提示词里塞一长串包名",
              "**多轮对话承接**（`SessionContext` + `AgentPrompts.sessionContext`）- 判定追问：含「接着/刚才/这个/改成/换成…」等强指代词直接认定；弱承接词「再」只在短句（≤12 字）里认定，避免把「打开微信」这类自带完整目标的短指令误判为追问- 注入最近 3 条已完成任务（目标 + 状态 + 结论，按时间倒序，进行中的不取——那条正是当前任务自己）- 是追问时明确写「必须以『上一轮任务』为目标主体」；不是追问时提示按相关性参考、无关就独立执行",
              "**任务结论落库**（`TaskMemoryEntry.conclusion`）：任务成功收尾时把完成摘要写进结论，作为下一轮承接的\"上次结果\"；结论为空则回退取最后一条完成方法"
            ]
          },
          {
            "type": "优化",
            "items": [
              "**性能**：已安装应用数每任务只查一次 `PackageManager`；会话承接块每任务只构建一次，随任务开始、规划、执行入口三处作废缓存，避免串轮",
              "**提示词双语同步**：中英两版系统提示词都补上 device_query 意图表行、独占路由规则（需要本机事实用 device_query，不要翻设置页）与决策阶段的「本机信息提醒」"
            ]
          },
          {
            "type": "测试",
            "items": [
              "新增 `SessionContextTest`（4 例）：强指代词追问、弱词「再」仅在短句成立、自带完整目标不误判、空白输入",
              "新增 `AgentPromptsContextTest`（6 例）：中/英环境上下文内容、缺项不渲染空标签、无历史不注入承接、追问强调承接、非追问提示独立",
              "`SkillCompatTest` 增 2 例：按技能名调用回填 kind/filter、标准意图名原样放行",
              "`IntentTranslatorStrategyTest` 增 4 例：转译字段、缺 kind 默认 all、非法 kind 失败、只读模式放行"
            ]
          }
        ]
      },
      {
        "version": "v0.1.334",
        "date": "2026-09-20",
        "summary": "本轮只做加固、不改产品行为：把四类会让任务「卡死」或「状态残留」的缺陷堵掉—— 异常穿透、协作事件丢失、队列竞态、主循环单点故障。任务该怎么做还是怎么做， 区别在于出问题时它降级、重试、请求用户介入，而不是无声停摆。",
        "sections": [
          {
            "type": "修复",
            "items": [
              "**异常不再穿透整个任务**（`AgentEngine.run`）- `runInner` 里的未捕获异常此前会顺着协程传到 `processQueue`，把队列 worker 一起带走：界面永久停在「运行中」，之后排队的任务也不再执行。现由 `run()` 统一收敛，并按 taskId 兜底复位运行状态- `approvePlan()` 原先用 `runCatching{ run() }.onFailure{}` 包住任务启动，会把`CancellationException` 一并吞掉 —— 「停止」按钮再也打不断已批准的计划，而且「用户停止」还会被误报成「执行异常」",
              "**兜底复位加归属守卫**（`EngineRules.shouldFallbackReset`）- `stop()` 取消协程后收尾是异步跑的：用户立刻发起新任务时，旧任务的收尾会把新任务的`agentRunning` 置 false、状态打回空闲，还会关掉新任务的悬浮窗。现按 taskId + 终态双重守卫",
              "**多任务队列改原子操作**（`PendingTaskQueue`）- 原先是对 `MutableStateFlow` 做 `_taskQueue.value + task` 的非原子「读—改—写」，并发提交时后写的会覆盖先写的，任务被静默吞掉；worker 启动与退出之间还存在「入队方既看不到活着的 worker、也看不到非空队列」的窗口，任务就此漏跑- 现由独立锁保证原子性，退出时以 `job === self` 只清自己的引用，避免误清入队方刚启动的新 worker 导致双 worker 重复执行同一任务",
              "**用户协作事件不再丢失**（AgentEngine 协作信箱）- 「敏感页保护 / 动作连续失败」的原因此前走 `MutableSharedFlow.tryEmit`，无订阅者时值被静默丢弃，界面上只能看到一个没有任何说明的协作面板。现改为 `Channel(CONFLATED)` 单槽信箱，用户抢在订阅之前输入的内容也能被缓冲住- 原因同步写入 `agentState.message`，界面才有东西可显示；非等待状态下的误触投递由 `_needsUser` 门控拦下，避免残留值让下一次等待被立刻满足而跳过等待"
            ]
          },
          {
            "type": "优化",
            "items": [
              "**主循环高风险调用点单点隔离**：`observe()` 读元素树、截图、端侧决策、云端决策、技能归一化、意图转译、动作执行、步骤留档全部包上异常兜底。无障碍服务被系统回收、截图权限被回收、Shizuku/Termux 通道断开时，只降级当前这一步（走既有的「连续 3 次失败 → 请求用户介入」链路），不再终止整个任务",
              "**决策链路连续异常护栏**：连续 5 次决策抛异常（如 API 地址错误、网络完全不可达）时收尾并提示「AI 决策链路持续异常，已停止任务」，不再无限空转；`WATCHDOG` 超时保持既有「等待后重试」语义不变"
            ]
          },
          {
            "type": "测试",
            "items": [
              "新增 `PendingTaskQueueTest`（5 例）：FIFO 顺序、空队列取出返回 null、并发入队不丢任务、边入队边取出不重复不丢任务",
              "`EngineRulesTest` 补 `shouldFallbackReset` 4 例：归属一致且非终态允许复位、`DONE` 终态不复位、taskId 不一致不复位、无归属 id 只靠终态守卫"
            ]
          }
        ]
      },
      {
        "version": "v0.1.333",
        "date": "2026-09-20",
        "summary": "让 API 地址不再被「公网 HTTPS」绑架：自建推理服务（Ollama、LM Studio、one-api 等）多跑在内网， 地址常是 `192.168.1.5:8000/v1` 或 `localhost:11434/v1`。此前这类配置走不通有两条原因—— 系统的明文流量策略会直接拦下 http:// 请求，漏写协议时 OkHttp 也会因地址不完整而抛异常。",
        "sections": [
          {
            "type": "新增",
            "items": [
              "**明文 HTTP 放行**（`AndroidManifest.xml`）：`android:usesCleartextTraffic=\"true\"`。targetSdk 28 起系统默认禁止明文流量，不放开则 http:// 的 API 地址一律失败于\"CLEARTEXT communication to xxx not permitted by network security policy\"",
              "**地址自动补协议**（`AiClient.normalizeBaseUrl`）：未写协议时按主机推断——localhost、私有网段（127/10/172.16-31/192.168）、单段主机名（nas、myserver）与 .local/.lan/.internal 等内网后缀补 `http://`，公网域名补 `https://`；已写 `http://` 的地址原样保留，不会被改写成 https",
              "设置页「API 地址」下补一行说明：支持 http://、内网地址与 localhost，不写协议时自动补全"
            ]
          },
          {
            "type": "测试",
            "items": [
              "新增 `ApiEndpointNormalizeTest`（5 例）：协议保留、本机/私有网段/内网后缀补 http、公网域名补 https、空串处理"
            ]
          }
        ]
      },
      {
        "version": "v0.1.332",
        "date": "2026-09-20",
        "summary": "给悬浮窗做减法：任务执行时它以小窗形态长时间贴在屏幕上，此前却把 AI 的发送/返回/审核三段 内容全摊在体内，窗口被撑成一块\"屏幕补丁\"；玻璃背景又叠了八层光学效果（菲涅尔四边反射、 动态光斑、棱镜虹彩色散…），热闹得不像系统组件；再加上窗口可被拖出屏幕、拖丢后任务还在跑 却看不见状态。本次从信息密度、视觉、交互、性能四方面收敛。",
        "sections": [
          {
            "type": "优化",
            "items": [
              "**AI 详情默认折叠**（`FloatingWindowService`）- 详情区（发送给 AI / AI 返回 / 审核结论）高 112dp，此前每来一段流式文本就自动弹出，窗口在任务执行中反复变高变大 —— 正是遮挡屏幕的主要来源- 现默认只占「跑马灯 + 头部 + 状态行」三行；内容照常在后台累积，点头部或头部新增的「详情/收起」按钮即可展开查看，展开状态在任务内保持、任务结束复位- 一并去掉详情区自带的「AI 徽章 + 思考中」标题行：顶部阶段徽章已经说明了当前处于思考中，再叠一行只是重复信息、白占高度；分栏标题 SENT/RESPONSE/REVIEW 改为中文",
              "**玻璃背景降为三层**（`LiquidGlassDrawable`）- 去掉菲涅尔四边反射、动态光斑、底部阴影渐变、棱镜虹彩色散，只留半透明白底 + 顶部折射高光 + 一道左上斜向柔光，外侧由发丝描边与细边框收边；虹彩是最显\"脏\"的一层，白玻璃上叠三原色渐变会让整体发浑- 投影高度 18dp → 12dp（M3 柔和浮起，不需要夸张阴影来证明\"浮起\"）",
              "**交互：边缘吸附与越界回收**（`FloatingWindowService.settlePosition`）- 窗口用 `FLAG_LAYOUT_NO_LIMITS`，本可被拖到屏幕外；松手时若贴近左右边缘则吸附贴边（留 8dp 边距），纵向越界则回收进屏幕 —— 不再出现\"窗口拖丢了、任务还在跑\"的情况- 惯性滑行的活动范围同样收进屏内，并保留跑马灯贴顶的负 y 上限",
              "**交互：轻点头部展开详情**：按触摸阈值区分\"轻点\"与\"拖动\"（此前手指的微小抖动也算位移，拖动与点击无法共存）；拖动时不再跟随手指做光斑重绘"
            ]
          },
          {
            "type": "性能",
            "items": [
              "**Shader 缓存**（`LiquidGlassDrawable`）：光学层改为仅在尺寸变化时重建，绘制期间零分配。悬浮窗在任务执行期间每帧重绘，此前每帧要新建十来个 `LinearGradient`/`RadialGradient`",
              "**跑马灯不可见时停帧**（`MarqueeView`）：截图隐藏、任务结束隐藏时不再每帧请求重绘（GONE 的视图仍会把 Choreographer 帧回调与遍历持续拉起来），恢复可见后从当前相位续滚",
              "**通知节流**（`FloatingWindowService`）：AI 思考同步到通知栏限制为最小间隔 700ms —— 流式增量每秒数次，逐条 `notify` 是跨进程调用，此前是白烧的固定开销"
            ]
          },
          {
            "type": "变更",
            "items": [
              "悬浮窗尺寸与位置常量（宽度、投影、贴边留白、吸附阈值、详情区高度）集中到`FloatingUi` 令牌，不再散落在窗口创建、惯性滑行、吸附各处",
              "面板入场动画改为「淡入 + 自下而上 12dp 位移」，去掉缩放（缩放与位移叠加会让视觉重心漂移，且入场方向应统一为自下而上）"
            ]
          }
        ]
      },
      {
        "version": "v0.1.331",
        "date": "2026-09-20",
        "summary": "让点击光标只属于「任务执行中」：此前任务成功跑完后从不撤下光标，它就停在最后一次点击的位置 一直挂在屏幕上；服务还声明了 `START_STICKY`，被杀后系统用空 intent 重建时也会凭空挂出一个光标。",
        "sections": [
          {
            "type": "修复",
            "items": [
              "**任务正常完成不撤光标**（`AgentEngine.run`）- 只在该路径补 `hide()` 是治标：主循环有多个 `return` / 异常出口，逐个补容易漏- 现统一放在 `run()` 的 `finally` 里，成功 / 失败 / 用户停止 / 异常一律撤下；并按 `taskId` 判定归属——停止协程后 `finally` 是异步跑的，用户若立刻发起新任务，旧任务的收尾不能把新任务刚挂上的光标一并撤掉",
              "**服务被重建后凭空出现光标**（`CursorOverlayService`）- `START_STICKY` 让系统在服务被杀后用 `null` intent 重建它，重建即走「显示」分支挂出光标，而此时并没有任何任务在执行；现改为 `START_NOT_STICKY` - 新增「任务执行中」这一唯一可见性凭据（`wanted`，由 `show` / `hide` 驱动）：`show` 的启动请求与 `hide` 抢跑（请求姗姗来迟）时，`onStartCommand` 直接 `stopSelf`，不再补挂光标",
              "**撤下光标改走进程内直连**：原来靠 `startService(ACTION_HIDE)` 通知服务自撤，而任务大多在 App 处于后台时结束，后台启动服务可能被系统拒绝（异常被 `runCatching` 吞掉），光标就留在屏幕上；服务实例本就在同一进程，现直接在主线程撤下视图并停掉服务，撤下前再确认一次 `wanted`，避免误撤新任务刚挂上的光标"
            ]
          }
        ]
      }
    ],
    "faq": [
      {
        "id": "need-root",
        "order": 1,
        "group": "使用",
        "q": "需要 Root 吗？",
        "a": "不需要。基础能力靠无障碍服务即可运行。想要系统级命令（am / pm / settings）时，用 Android 11+ 自带的无线调试配对，或安装 Shizuku——两者都不需要 Root。"
      },
      {
        "id": "which-model",
        "order": 2,
        "group": "使用",
        "q": "必须用哪家的模型？",
        "a": "任意 OpenAI 兼容端点都可以，包括自建的 Ollama、LM Studio、one-api。主模型需要支持结构化输出；如果要开视觉能力，另配一个支持图像输入的模型（默认 glm-4.6v-flash）。"
      },
      {
        "id": "privacy",
        "order": 3,
        "group": "安全",
        "q": "我的屏幕内容会上传到哪？",
        "a": "只发给你自己配置的模型服务。手机号、身份证号、银行卡号在构造提示词时就已脱敏，被遮蔽的内容从未离开设备。支付等敏感页面会被自动切换为只读。"
      },
      {
        "id": "free",
        "order": 4,
        "group": "使用",
        "q": "收费吗？",
        "a": "应用本身基于 MIT 协议开源，不收费。你只需要为自己使用的模型服务付费（如果用云端 API）。"
      },
      {
        "id": "apk-source",
        "order": 5,
        "group": "下载",
        "q": "下载页的 APK 是什么构建？",
        "a": "当前提供的是 Debug 构建，用于内测与自用，需要手动授予权限。每个版本的 SHA-256 校验值都写在下载页上，安装前可自行核对。"
      },
      {
        "id": "install-fail",
        "order": 6,
        "group": "下载",
        "q": "安装提示「应用未安装」怎么办？",
        "a": "多数情况是签名冲突：先卸载旧版本再装。也可能是下载不完整——对照下载页的 SHA-256 校验一下文件。"
      }
    ],
    "docs": [
      {
        "slug": "getting-started",
        "title": "快速开始",
        "group": "入门",
        "order": 1,
        "summary": "从下载安装到跑通第一个任务。"
      },
      {
        "slug": "model-config",
        "title": "模型配置",
        "group": "入门",
        "order": 2,
        "summary": "主模型、视觉模型、思考模型各自负责什么，以及怎么填。"
      },
      {
        "slug": "execution-channels",
        "title": "执行通道",
        "group": "进阶",
        "order": 3,
        "summary": "无线 ADB、Shizuku、Termux、无障碍四者的能力边界与选择建议。"
      },
      {
        "slug": "intent-layer",
        "title": "意图转译层",
        "group": "进阶",
        "order": 4,
        "summary": "为什么 AI 永远不输出坐标和命令，以及 15 个语义意图是怎么落地的。"
      },
      {
        "slug": "safety",
        "title": "安全与隐私",
        "group": "进阶",
        "order": 5,
        "summary": "哪些页面只读、哪些数据会被脱敏、哪些操作必须你点头。"
      },
      {
        "slug": "architecture",
        "title": "架构总览",
        "group": "进阶",
        "order": 6,
        "summary": "感知、决策、执行、视觉、持久化、展示六层怎么协作。"
      },
      {
        "slug": "troubleshooting",
        "title": "疑难排查",
        "group": "支持",
        "order": 7,
        "summary": "连不上模型、点了没反应、浮窗不出现、任务老是中止。"
      }
    ]
  }
}
