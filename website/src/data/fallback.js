/* 由 scripts/snapshot.mjs 生成于 2026-09-22T14:15:32.330Z，请勿手工编辑。
   静态导出（GitHub Pages）用这份快照替代后端 API。 */
export const snapshot = {
  "builtAt": "2026-09-22T14:15:32.330Z",
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
    "channelLabels": {
      "stable": "稳定版",
      "beta": "测试版",
      "nightly": "每日构建",
      "dev": "开发版"
    },
    "sectionTones": {
      "新增": "ok",
      "优化": "brand",
      "变更": "brand",
      "修复": "amber",
      "性能": "brand",
      "测试": "mist",
      "文档": "mist",
      "移除": "danger",
      "安全": "danger"
    },
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
      "slug": "model-services",
      "title": "常见模型服务配置示例",
      "group": "入门",
      "order": 3,
      "summary": "OLLAMA、LM Studio、one-api、vLLM 等本地与中转服务，地址和模型名该怎么填。",
      "body": "## 三类模型\n\n| 类型 | 负责 | 必需 | 默认 |\n| --- | --- | --- | --- |\n| 主模型 | 每步执行决策 | 是 | glm-4.7-flash |\n| 视觉模型 | 截图描述 + 定位坐标 | 否 | glm-4.6v-flash |\n| 思考模型 | 规划 / 重规划 | 否 | 回退主模型密钥 |\n\n三者都是 OpenAI 兼容端点，共用 Chat Completions 协议，换服务商不用改请求代码。主模型必须能返回结构化 JSON，视觉模型必须支持图片输入；主模型默认不处理图片。\n\n## API 地址怎么写\n\n地址末尾的斜杠会被去掉，不写协议时按主机推断：\n\n```text\nlocalhost:11434/v1   → http://localhost:11434/v1\napi.example.com/v1   → https://api.example.com/v1\n```\n\n判定内网依据：localhost、私有网段（10.x、192.168.x、172.16~31.x）、单段主机名、.local / .lan 等后缀。已写 `http://` 的原样保留，误填完整端点也不会重复拼接。\n\n## 自建与中转服务\n\n- Ollama：`http://localhost:11434/v1`\n- LM Studio：`http://<本机IP>:1234/v1`（局域网填本机 IP）\n- vLLM：`http://<主机>:8000/v1`\n- one-api / 中转：`http://<主机>:3000/v1`，模型名按网关配置\n\n这些地址都在内网，未写协议时自动补 `http://`。应用已放行明文流量，无需反向代理。\n\n## 模型名怎么填\n\n填服务端注册的模型名，大小写敏感；部分服务要求带前缀，如硅基流动的 `Qwen/Qwen3-32B`。模型名下线很快，报「模型不存在」时以控制台列表为准。设置页提供快捷预设（智谱 GLM、DeepSeek、Kimi、通义千问、火山方舟、腾讯混元、硅基流动），点一下填好地址与主模型。\n\n## 测试连接是保存的前提\n\n保存按钮做的是「先测试、再保存」：只有测试连接成功，配置才写入 DataStore。测试请求固定为一句「请只回复：OK」。页面上另有「测试所有 API」，按主 / 视觉 / 思考模型逐项验证。视觉、思考模型的密钥留空时回退主模型密钥，地址或模型名留空时回退默认值。\n\n失败信息是「HTTP 状态码 + 实际请求地址 + 服务端返回正文」——不少网关把原因放在 error.type 或顶层 message，只截 message 会丢诊断依据。\n\n## 超时与重试\n\n连接 / 读 / 写超时 30s / 120s / 60s。429 按 2s、4s、8s、16s 退避，最多 5 次；其他失败重试 1 次（800ms）。决策走流式（SSE），返回空正文时回退非流式 + 结构化输出。"
    },
    {
      "slug": "execution-channels",
      "title": "执行通道",
      "group": "进阶",
      "order": 4,
      "summary": "无线 ADB、Shizuku、Termux、无障碍四者的能力边界与选择建议。",
      "body": "## 四条通道，能力完全不同\n\n| 通道 | 权限级别 | 能做什么 |\n| --- | --- | --- |\n| 无障碍 | 普通应用权限 | 读控件树、点击、滑动、输入文字 |\n| 无线 ADB | shell（adb） | `am` / `pm` / `settings` 等系统命令 |\n| Shizuku | shell（adb） | 同上，通过 Shizuku 授权获得 |\n| Termux | 普通应用权限 | curl / python / 文本处理，**不能**执行系统命令 |\n\n## 默认策略：无线 ADB 为主\n\n设置里的执行通道是三态偏好：`AUTO` / `ADB` / `SHIZUKU`（Termux 作为补充通道参与 AUTO 降级）。\n\n`AUTO` 下的顺序是：**无线 ADB → Shizuku → Termux → 无障碍**。\n\n无线 ADB 是主通道的理由很实际：Android 11+ 自带无线调试，配对一次即可长期使用，拿到的是货真价实的 shell 权限，且不需要额外安装任何应用。Shizuku 退居可选增强——拉起失败或超时都不会阻塞主流程。\n\n## 为什么启动应用要走 shell\n\nAgent 启动目标应用一律用 `launch`（底层是 `monkey -p <包名> 1`），而不是回到桌面点图标。原因有两条：\n\n1. 桌面图标的位置和数量随时会变，坐标点击不稳定；\n2. 点图标会引入「桌面是不是在前台」这个额外状态判断。\n\nAI 若只给了裸包名（例如 `com.tencent.mm`），端侧会自动补全为完整的 launch 动作。\n\n## Termux 的前置条件\n\n三项缺一不可：\n\n1. 已安装 Termux；\n2. 已授予 `com.termux.permission.RUN_COMMAND`；\n3. Termux 侧 `allow-external-apps=true`。\n\n结果回传走 **PendingIntent**，不走结果目录文件——后者在 `allow-external-apps` 未开启时会永久挂起。"
    },
    {
      "slug": "intent-layer",
      "title": "意图转译层",
      "group": "进阶",
      "order": 5,
      "summary": "为什么 AI 永远不输出坐标和命令，以及 15 个语义意图是怎么落地的。",
      "body": "## 设计前提\n\n让语言模型直接输出坐标和 shell 命令，会同时引入两类问题：\n\n- **不稳定**：同一个控件在不同分辨率、不同主题下的坐标不同；\n- **不可控**：模型可以输出任意命令，安全边界形同虚设。\n\n所以本项目把 AI 的输出面收窄成 15 个**高层语义意图**，由端侧 IntentTranslator 实时转译成已有的 ActionType。\n\n## 15 个语义意图\n\n```text\nback  home  refresh  search  send  confirm  close\nshare  collect  copy  delete  download  add  switch  clear_input\n```\n\n每个意图对应一个策略类（SemanticActionStrategy / BackStrategy / HomeStrategy），本地执行，零网络往返。\n\n## 定位优先级\n\n1. **id / label** —— 无障碍元素树里有精确标识时优先使用，执行层自动算坐标；\n2. **scroll_to** —— 当前屏幕找不到目标控件时先滚动查找；\n3. **coordinate** —— 只有图片、图表这类元素树里读不到的控件才允许用坐标；\n4. **abort** —— 以上都不成立就中止，并说明当前前台应用与缺失的控件名。\n\n报错信息里必须带**当前前台应用**，而不是笼统的「无法定位控件」——否则 AI 下一轮仍然不知道该做什么。\n\n## 一步一个动作\n\n同一轮只做一个明确动作，不叠加小动作，同一控件不反复操作。规划阶段的步数动态收敛到 3~8 步，宁可少而准。"
    },
    {
      "slug": "safety",
      "title": "安全与隐私",
      "group": "进阶",
      "order": 6,
      "summary": "哪些页面只读、哪些数据会被脱敏、哪些操作必须你点头。",
      "body": "## 敏感页面只读\n\nSensitivePageDetector 识别支付、个人信息、密码等敏感页面，命中后由 EngineRules 横切为**只读**：Agent 可以看、可以描述，但不能提交。\n\n## 数据脱敏\n\n进模型上下文之前，以下数据会被遮蔽：\n\n| 类型 | 规则 |\n| --- | --- |\n| 手机号 | 11 位连续数字 |\n| 身份证号 | 18 位（含末位 X） |\n| 银行卡号 | 长位数字串 |\n\n脱敏发生在构造提示词的环节，不是事后过滤——被遮蔽的内容从未离开过设备。\n\n## 硬约束规则引擎\n\nEngineRules 在动作真正下发**之前**拦截：\n\n- 敏感页上的写操作；\n- 结构化错误的命令（未知命令、空命令、参数非法）——直接跳过 3 次无效重试并立即失败，同时把错误原因与可用命令列表注入下一轮决策上下文。\n\n## 不确定时的行为\n\n系统提示词里明确禁止「不确定就输出 task_done」。模型必须：\n\n1. 先尝试可行解法；\n2. 卡住时输出 `abort` 并说明卡点；\n3. **不得编造不存在的命令或动作。**\n\n## 完成任务的门槛\n\nAI 只有在**当前页面上亲眼看到任务完成的明确证据**时才允许输出 `task_done`，且总结里必须写清证据是什么。执行层还会做二次把关：至少执行 3 步、且达到规划步数的 60%，才接受完成判定；连续 3 次过早完成才强制收尾，防止死循环。"
    },
    {
      "slug": "architecture",
      "title": "架构总览",
      "group": "进阶",
      "order": 7,
      "summary": "感知、决策、执行、视觉、持久化、展示六层怎么协作。",
      "body": "## 执行循环\n\n```text\n观察屏幕 → 页面标注 → 端侧/云端决策 → 双通道执行 → 带验证 → 记录 → 循环\n```\n\n1. **观察** —— 无障碍服务取回屏幕全部可交互元素。\n2. **标注** —— PageAnnotator 为 26 类常见控件分配语义 ID（`dlg_allow`、`ad_skip`、`search_box`、`send_btn` …），直接注入 AI 上下文，让模型能按 id 选控件。\n3. **决策** —— LocalDecisionEngine 先处理高频场景，其余交给模型。\n4. **执行** —— 通道降级执行，动作前后做页面指纹比对。\n5. **记录** —— 落盘供多轮对话与诊断导出使用。\n\n## 分层\n\n```text\ncore/       跨层基础设施（AI 客户端、脱敏、通知、人话翻译）\ndomain/     纯领域模型与规则（动作、意图、规则引擎、端侧决策）\ndata/       持久化（DataStore 配置 + 五处 Store）\ndevice/     设备能力（无障碍、shell、截图、视觉）\nengine/     Agent 编排（ReAct 主循环、转译层、感知、提示词）\noverlay/    悬浮窗\nfeature/    功能域（任务、技能、MCP、文档、跳广告、边缘光效、测试）\nui/         界面层（页面 = 入口 + 单一职责拆分文件）\n```\n\n## 持久化\n\n| 存储 | 内容 |\n| --- | --- |\n| AppSettings | DataStore 配置（模型、通道、语言、开关） |\n| MemoryStore | 任务记忆（目标 / 用户要求 / 已验证方法 / 状态） |\n| TaskStore | 检查点 + 任务模板库 |\n| DebugRecordsStore | 日志 / 轨迹 / 历史 / 对话 |\n| McpStore | MCP 服务与技能配置 |\n| PromptTemplateStore | 提示词模板 |\n\n## 上下文卫生\n\n每次 `run()` 开头会彻底清空 AI 上下文：对话消息、失败计数、无效命令计数、上一条 shell 输出、上一页截图、上一任务计划、工作记忆。任务之间不串味。"
    },
    {
      "slug": "overlay-and-takeover",
      "title": "悬浮窗与接管",
      "group": "进阶",
      "order": 8,
      "summary": "浮窗上能看到什么、能做什么，以及它什么时候会停下来等你。",
      "body": "## 窗口构成\n\n悬浮窗不是一个窗口，而是几个各司其职的窗口：\n\n| 窗口 | 形态 | 作用 |\n| --- | --- | --- |\n| 顶栏 | 全宽跑马灯，贴屏幕物理顶 | 任务期间常驻，滚动显示当前状态 |\n| 任务卡片 | 300dp 宽、可拖动 | 标题、步骤、详情与交互 |\n| 悬浮球 | 小圆形「AI」按钮 | 面板收起后屏幕上唯一入口 |\n| 底部选项卡 | 从底边拉起 | AI 提问 / 求助时的选项与输入框 |\n\n任务执行期间系统状态栏会被隐藏（可在设置里关掉），顶栏此时承担状态栏的角色。顶栏单独拆成全宽窗口，是为了能真正贴到屏幕顶并铺满整宽，而不是被卡片拖走。\n\n## 卡片上有什么\n\n- 头部：状态呼吸点、任务名、「详情」开关、「隐藏」、「✕」。仅头部可拖动，松手后靠近左右边缘会自动贴边。\n- 状态行：阶段徽章 + 「第 N 步 · 状态」。阶段取值是观察中 / 思考中 / 执行中 / 已完成 / 出错 / 待命。\n- 详情区：分「发送给 AI」「AI 返回」「审核结论」三块，默认折叠，展开后返回内容最多显示 3000 字。\n\n## 什么时候会停下来等你\n\n| 触发 | 面板 | 可选操作 |\n| --- | --- | --- |\n| 计划已生成（含模板复用） | 执行计划 | 批准并开始 / 取消 |\n| 规划发现歧义 | 需要澄清 | 各选项按钮 / 我想自己说 |\n| 检测到敏感页面 | 敏感页面保护 | 输入框 + 已手动处理 / 指导 AI |\n| 动作连续 3 次未生效 | 需要你的协助 | 同上 |\n| 任务成功完成 | 保存执行模板 | 保存为模板 / 不保存 |\n\n「连续 3 次未生效」是刻意放宽的：单次点击失败不打断，避免频繁弹面板。\n\n## 「已手动处理」是什么意思\n\n它不是「结束任务」。点它会发送一个语义信号，让 Agent 重新观察当前页面并重新决策下一步——适用于用户已经手动点掉了弹窗、或自己完成了这一步的场景。\n\n- 输入框留空时点「指导 AI」等价于「已手动处理」。\n- 输入框非空时，这段话会作为新的用户要求写进任务记忆，并注入后续每一轮决策。\n\n## 关闭与隐藏的区别\n\n- **✕**：停止任务并关闭浮窗。\n- **隐藏**：收起卡片与顶栏，只留悬浮球，同时把任务搁置。当前这一步会正常走完，之后不再观察与决策；点悬浮球即原样唤出并从当前步继续。\n- 底部选项卡不随面板一起收起——它承载的是 AI 正在等用户回答的问题，藏掉会让任务永久挂住。\n- 若在设置里关掉悬浮窗开关，任务照常执行，只是看不到浮窗。\n\n## 权限\n\n浮窗需要「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）。没有该权限时悬浮球建不出来，此时点「隐藏」不会真的收起面板——否则用户就再也叫不回来了。部分厂商系统还需要在「后台弹出界面」里单独放行。"
    },
    {
      "slug": "vision-modes",
      "title": "视觉三态与端侧模型",
      "group": "进阶",
      "order": 9,
      "summary": "CLOUD / LOCAL / AUTO 三种视觉模式各自什么时候生效，元素树读不出来时怎么兜底。",
      "body": "## 三种模式\n\n设置里是三态分段按钮：云端 / 本地OCR / 自动，对应 `CLOUD` / `LOCAL` / `AUTO`。\n\n| 模式 | 含义 |\n| --- | --- |\n| CLOUD | 只走云端视觉模型 |\n| LOCAL | 只走本地（外挂端侧）识别 |\n| AUTO | 优先云端，失败或未配置时回退本地 |\n\n## 实际调用顺序\n\n元素树读不到内容时，视觉链路按下面的顺序取第一个能出结果的一步：\n\n1. **外挂端侧视觉**（设置里的「外挂视觉 Agent」）——跨进程调用端侧 3B，返回控件类型、用途与归一化坐标；\n2. **云端视觉模型**——把截图交给视觉模型描述，`CLOUD` 与 `AUTO` 都会走；\n3. **本地兜底**——`LOCAL` 模式，或 `AUTO` 下云端失败 / 未配置时，再试一次本地识别。\n\n任一步拿到结果就停，不会重复消耗后面的来源。整条链路有看门狗兜底：超时即放弃本轮视觉描述，只用无障碍元素树继续决策，主循环不会被打死。\n\n## 混合路由\n\n「混合路由」默认开启，按页面复杂度分流：\n\n- 简单页面（元素树可读）交给端侧 3B 框选，快且省云端额度；\n- 复杂页面（元素稀疏，如游戏 / WebView / 小程序）跳过 3B，直接走云端视觉做语义理解。\n\n关掉它则不做分流，端侧 3B 与云端按上面的固定顺序参与。\n\n## 元素树稀疏时会怎样\n\n端侧把「元素数 ≤ 3」视为稀疏：\n\n- 即使关掉了「附送屏幕截图」，也会自动截图交给视觉链路；\n- 日志里会注明「元素稀疏，自动启用视觉模型」；\n- 该步的追踪记录会标出视觉来源与模型名，便于在调试页核对。\n\n## 云端视觉默认模型\n\n云端视觉模型默认是 `glm-4.6v-flash`。视觉模型的密钥留空时回退主模型密钥，地址留空时回退默认端点；只有当视觉模型开关关闭、模型名为空、或最终取不到密钥时，云端视觉才完全不参与。\n\n## 定位与兜底\n\n视觉模型除了描述页面，还负责定位：给定目标文字，返回它在截图上的比例坐标（0~1），端侧再乘屏幕宽高换算成像素。外挂端侧定位不准时，会退回「在已识别控件里做本地文字匹配」的结果。\n\n调试页的追踪里，`visionSource` 取值是 外挂3B / 云端 / 本地OCR / 无，可以直接看出这一步靠谁完成。注意本地路径的模型标签沿用了「ML Kit 中文OCR」的旧文案，实际执行体已是外挂端侧视觉——主程序不再内置 OCR。"
    },
    {
      "slug": "task-memory",
      "title": "任务记忆与检查点",
      "group": "进阶",
      "order": 10,
      "summary": "跨任务记住了什么、存在哪里、怎么清空。",
      "body": "## 任务记忆里存了什么\n\n一条任务记忆对应一次任务执行：\n\n| 字段 | 内容 | 上限 |\n| --- | --- | --- |\n| goal | 既定目标，即任务原文 | — |\n| requirements | 用户要求：任务原文 + 执行中补充的指导 | 8 条 |\n| methods | 已验证有效的做法（步骤摘要） | 10 条 |\n| conclusion | 完成时 AI 给出的完成说明 | — |\n| status | running / success / failed / aborted | — |\n| completedSteps | 已验证完成的步数 | — |\n\n超过上限时丢弃最旧的一条。requirements 用「去掉空白标点后完全一致」去重，不做模糊去重——用户补充的指令常与原文相近，但那确实是一条新要求，不能静默丢掉。\n\n## 什么时候写入\n\n- **任务开始**：goal 与 requirements 首条写入任务原文，状态置 running，先落库再执行——中途中断时记忆页也能看到任务目标。\n- **每成功一步**（动作经验证生效）：追加做法摘要，并更新已完成步数。\n- **用户补充指导**：写入 requirements。\n- **收尾**：写入终态与结论。状态只允许从 running 单向推进到终态，重复收尾不覆盖已定结果。\n\n## 下次任务怎么用上\n\n任务记忆是「不随上下文压缩而丢失」的锚点，每一轮决策都会完整注入：目标、用户要求、执行进度与已验证做法。长线任务跑到后半程，即便历史对话已被截断，模型仍看得到最初目标与用户中途提的要求。\n\n## 存在哪里、能存多少\n\n任务记忆存在应用内部 DataStore（库名 `agent_memory`，键 `task_memory`），总量上限 30 条。超出时优先淘汰已结束的任务，进行中的留到最后，避免正在跑的任务被自己累积的历史挤掉。\n\n## 检查点\n\n除任务记忆外，每成功一步还存一个检查点：任务原文、计划 JSON、已完成步数、总步数。\n\n- 位置：设置 → 长线任务 → 断点续传，显示上次任务与已完成步数，点「续传此任务」即可重启。\n- 检查点只有一个，任务成功完成时清空。\n- 续传复用原计划，计划步骤是原子动作且带幂等保护，重复的副作用操作会被跳过。\n\n## 和「对话上下文」的区别\n\n| | 任务记忆 | 对话上下文 |\n| --- | --- | --- |\n| 内容 | 目标 / 要求 / 做法 / 结论 | AI 与端侧的聊天消息 |\n| 保留 | 按任务整条保存，上限 30 条 | 内存最多 200 条，提示词只带最近 6 轮 |\n| 注入 | 每轮完整注入 | 只注入最近几轮 |\n\n异常经验、用户画像与 AI 记忆属另一类跨任务长期记忆，与任务记忆分开存放。\n\n## 怎么清空\n\n在记忆图谱页：单条任务记忆右侧的删除图标可删单条，「任务记忆」卡片标题旁的清空可清空全部。清空只影响任务记忆，不动异常经验、用户画像与检查点。"
    },
    {
      "slug": "skills-and-mcp",
      "title": "技能与 MCP 接入",
      "group": "进阶",
      "order": 11,
      "summary": "内置技能有哪些、MCP 技能怎么注册，以及参数校验和超时规则。",
      "body": "## 技能是什么\n\n技能（Skill）把原来的硬编码命令升级为「有名字、有参数、可复用」的能力单元。每个内置技能通过 `legacyIntent` 指回一条旧命令意图，由 SkillCompat 兼容层保证「旧命令 → 内置技能 → 原有转译逻辑」这条链路不变，因此 AI 叫旧意图名或技能名都可达。\n\n内置技能共 36 个，分 7 类：\n\n| 类别 | 技能（意图名） |\n| --- | --- |\n| 导航 | 打开应用 open_app、打开链接/文件 open、返回 back、回桌面 home、刷新 refresh |\n| 操作 | 点击 tap、长按 long_press、输入文字 input、滑动 swipe、按键 press、等待 wait、滚动查找 scroll_to、搜索 search、发送 send、确认 confirm、关闭 close、分享 share、收藏 collect、复制 copy、删除 delete、下载 download、新增 add、切换开关 switch、清空输入 clear_input |\n| 创作 | 写入文档 write_doc |\n| 记忆 | 记住信息 remember |\n| 取数 | 查询本机信息 device_query、取网页正文 fetch |\n| 浏览器 | 打开网页 browse_open、抓取网页内容 browse_read、点击网页元素 browse_click、网页表单输入 browse_input、网页滚动 browse_scroll、网页后退 browse_back |\n| 控制 | 完成 finish、放弃 give_up |\n\n提示词里不会逐个罗列内置技能（它们与意图表一一对应），只说明等价关系；真正需要单独讲的是 MCP 技能——参数名 AI 猜不到。\n\n## intent 字段的三种写法\n\nAI 输出的 `intent` 字段在端侧统一收口，接受三种写法：\n\n1. **标准意图名**（tap / open_app / swipe …）——原样放行；若其对应内置技能已被用户停用则拒绝。\n2. **内置技能 id 或技能名**（skill_open_app / 打开应用）——归一化为等价的旧意图，其余字段原样保留。\n3. **MCP 技能 id**（如 mcp_filesystem_read_file）——不产生设备动作，交 MCP 客户端就地调用，参数取 `args`。\n\n技能参数有两种等价写法：放进 `args` 对象，或直接写成意图的扁平字段。\n\n```json\n{\"intent\":\"skill_swipe\",\"args\":{\"direction\":\"up\"}}\n{\"intent\":\"swipe\",\"direction\":\"up\"}\n```\n\n两者效果相同；回填只覆盖 `args` 里真正给出的字段，AI 写在扁平字段上的值不受影响。\n\n## MCP 接入\n\nMCP 服务器在「技能与能力 → MCP」页配置，字段与校验规则：\n\n| 字段 | 规则 |\n| --- | --- |\n| 名称 | 非空、不含空格、与已有服务器不重名 |\n| 地址 | 必须以 http:// 或 https:// 开头，且含有效主机名 |\n| Token | 可选，非空时以 Authorization: Bearer 下发 |\n| 协议版本 | 默认 2025-03-26，目前仅支持该版本 |\n\n内置市场提供快捷填充：经典工具类（文件系统、SQLite、GitHub、Notion，需自配 URL / Token）与公共开放 API（天气、汇率、新闻、加密货币行情，默认 URL 可直接用）。添加前会做连接测试，握手 initialize 与 tools/list 都成功才算可用。\n\n服务器连通后，在 MCP 页点「绑定为技能」，把枚举到的工具注册为 `source=MCP` 的技能：技能 id 形如 `mcp_<服务器名>_<工具名>`，参数由工具的 JSON Schema 解析而来（type / required / enum 归并为 select / default / description）。只有已启用的 MCP 技能会注入提示词，AI 只能调用这些。\n\n## 参数校验与失败处理\n\n归一化阶段会拦下三类问题，并把中文原因回注给 AI 纠正：\n\n- **未知技能** —— 提示改用系统提示里列出的意图或已启用技能。\n- **技能已停用** —— 技能页的启停开关对运行时真正生效；停用技能也会在提示词里列为「不可调用」，避免 AI 白试。\n- **缺必填参数** —— 报出缺失的参数名，并提示用 `args` 对象补全。\n\nMCP 调用前还会校验工具名（仅允许常见标识符字符），参数按 `argsTemplate` 模板替换后再下发。\n\n## 超时与熔断\n\n| 项 | 值 |\n| --- | --- |\n| MCP 单次调用超时 | 20 秒（超时按失败回注，主循环不干等） |\n| MCP 传输层连接超时 | 8 秒 |\n| MCP 传输层读 / 写超时 | 90 秒 |\n| MCP 返回值注入上下文上限 | 1200 字符 |\n| 连续「技能调用被拒」上限 | 3 次，达到即停止任务 |\n\n此外，动作连续失败 3 次会请求用户介入，决策链路连续异常 5 次会收尾。这些计数器都是异常隔离的护栏，避免任务在坏链路上无限打转。"
    },
    {
      "slug": "built-in-browser",
      "title": "内置浏览器",
      "group": "进阶",
      "order": 12,
      "summary": "browse_* 系列技能负责什么，网页是怎么被读成 Markdown 的。",
      "body": "## 为什么要内置浏览器\n\n网页元素（下拉、弹层、登录框）在无障碍树里往往拿不到可靠的 id 或文字，坐标更是换个分辨率就错。而网页自己有 DOM，读正文、按文字点元素、往输入框填字都能精确完成。所以内置浏览器把网页读写统一交给注入的 DOM 脚本，AI 只表达「点哪个字 / 填什么」。\n\n它有三条边界：\n\n1. **可见** —— 浏览器是本 App 的一个二级页，需要上网时把 App 切到该页，于是每步截图里就是真实网页，AI 能亲眼看到页面，而不是只拿一段文本凭空判断。\n2. **端侧自足** —— 网页读写走 DOM 脚本，不依赖无障碍、Shizuku、Termux，这些能力缺失时浏览器照常可用。\n3. **只操作浏览器里的页** —— 所有操作都作用于 WebView 里当前这一页；没有打开过网页时一律返回可判定的中文原因，引导 AI 先打开网页，而不是静默无动作。\n\n## 六个 browse_* 技能\n\n| 技能 | 意图名 | 作用 | 参数 |\n| --- | --- | --- | --- |\n| 打开网页 | browse_open | 在内置浏览器打开网址，页面切到前台并出现在之后每步截图里 | uri |\n| 抓取网页内容 | browse_read | 抓当前页正文与表单按钮，正文以 Markdown 返回 | 无 |\n| 点击网页元素 | browse_click | 按元素文字（优先）或 CSS 选择器点击 | target |\n| 网页表单输入 | browse_input | 往网页输入框填字 | target、text |\n| 网页滚动 | browse_scroll | 滚动网页，方向 up / down / top / bottom | direction |\n| 网页后退 | browse_back | 网页内后退，不是系统返回，不会退出浏览器 | 无 |\n\nbrowse_read 的结果里，正文是 Markdown（标题层级 / 列表 / 表格 / 代码块 / 内联链接），链接已内联成 `[文字](网址)`，所以不再单独给「可点链接」清单——AI 要点击时直接取正文里的链接文字。输入框与按钮仍单独列出，因为它们是浏览通道特有的可操作面，AI 靠它们决定 browse_input 的 target 与 browse_click 的文字。\n\n## 网页是怎么被读成 Markdown 的\n\n转换器 HtmlToMarkdown 是自研的（项目没有引入 jsoup），分四段：\n\n1. **stripNoise** —— 单遍扫描，删注释、DOCTYPE、CDATA，按丢弃表整棵删除 script / style / nav / footer / head 等噪声子树，同时把 title / base / canonical / og:url 抽出来。head 整棵会被丢掉，元信息必须在丢之前扫一遍，否则永远拿不到网页标题。\n2. **tokenize** —— 手写标签扫描器，属性走小状态机，引号内的 `>` 不当标签结束（`<a title=\"a > b\">` 是常见坑）。\n3. **Converter** —— 显式标签栈，全程无递归，畸形页面或千层 div 都不会栈溢出。它把标题、列表、引用、代码块、表格、链接、加粗等映射成 Markdown，并把相对链接绝对化；被属性或 class 藏起来的元素整棵静音，不产出任何文本。\n4. **MdBuilder** —— 块累积、空白折叠、CJK 之间不插空格、Markdown 特殊字符转义，超过生成上限即早停。\n\n浏览器侧的注入脚本与 Kotlin 侧**同源**：丢弃表 / 块级表 / 行内标记表 / 隐藏类名表 / 转义字符表都由 HtmlToMarkdown 的常量插值生成，改规则只会改一处，两侧不可能漂移。\n\n## 长度上限\n\n| 项 | 值 |\n| --- | --- |\n| AI 可见正文预算 | 4000 字符（按块边界截断，绝不产生半个链接） |\n| 生成上限 | 8000 字符 |\n| 输入 HTML 切片上限 | 1,500,000 字符 |\n| 标签嵌套深度上限 | 256 |\n| 表格保留行数 / 列数 / 单元格字符 | 30 / 12 / 120 |\n| 浏览器侧脚本：节点 / 深度 / 输出 / 单次耗时 | 15000 / 120 / 8000 / 1500ms |\n\n截断统一在块边界执行（优先切空行，退而切换行，落点若在链接中间则继续回退），保证 fetch 链路与浏览器链路行为一致。\n\n## 四类「打开」的分工\n\n| 意图 | 什么时候用 |\n| --- | --- |\n| open_app | 打开某个应用本身，如「打开设置」「打开微信」 |\n| open | 把链接 / 文件交给系统应用打开，或深链直达 App 内页 / 系统页；只是把网址打开给用户看时用它 |\n| fetch | 用命令行取纯文本 / JSON 接口正文（需本机已装并授权 Termux）；返回 HTML 时端侧自动转成 Markdown |\n| browse_open | 需要读或操作网页内容时用内置浏览器打开 |\n\n判断顺序很简单：要读 / 操作网页内容就走 browse_*；只是把网址展示给用户就用 open + uri 交系统浏览器；目标只是纯文本接口且本机有 Termux 通道可以用 fetch，但 fetch 终究只是文字快照，看不到网页长什么样、有哪些按钮，不能拿它代替 browse_*。\n\n边界：browse_click / browse_input / browse_scroll / browse_back 都要求浏览器里已有打开的那一页，没有就先 browse_open；要离开浏览器回 App，用 `press key=BACK`。"
    },
    {
      "slug": "troubleshooting",
      "title": "疑难排查",
      "group": "支持",
      "order": 13,
      "summary": "连不上模型、点了没反应、浮窗不出现、任务老是中止。",
      "body": "## 测试连接失败\n\n- **地址不完整** —— 现在会自动补协议。内网写 `192.168.1.5:8000/v1` 即可。\n- **明文被拦** —— 应用已放行 `usesCleartextTraffic`，若仍失败请检查路由器是否隔离了客户端。\n- **模型名不对** —— 部分服务要求带前缀，例如 `openai/gpt-4o`。\n- **不支持结构化输出** —— 主模型必须能返回 JSON，纯对话模型会持续报解析失败。\n\n## 点了没反应\n\n先看调试页的**步骤**面板，那里会写清每一步的判定结果：\n\n- 「控件不在当前元素树」—— 说明 AI 想点的东西不在前台页面上，报错会带上当前前台应用名；\n- 「点击未生效」—— 页面指纹前后一致，说明点空了，Agent 会重试或改用其它定位方式。\n\n## 悬浮窗不出现\n\n1. 检查**显示在其他应用上层**权限是否授予；\n2. 检查设置里悬浮窗开关是否被关掉（关掉后任务仍会执行，只是没有浮窗）；\n3. 部分厂商系统需要在「后台弹出界面」里单独放行。\n\n## 任务反复中止\n\n常见原因与对策：\n\n| 现象 | 原因 | 对策 |\n| --- | --- | --- |\n| 连续 3 次结构化错误 | AI 用了不存在的命令 | 检查主模型能力，或换更强的模型 |\n| 找不到控件后中止 | 页面没加载完 / 需要滚动 | 在浮窗里补充说明，或改用更具体的入口描述 |\n| 过早判定完成 | 页面证据不足 | 已由执行层拦截；仍出现请导出诊断报告提 Issue |\n\n## 导出诊断\n\n**调试页 → 导出**，会打包日志、步骤轨迹、历史对话与能力状态。提 Issue 时附上它，比描述现象有用得多。"
    },
    {
      "slug": "diagnostics",
      "title": "诊断包怎么读",
      "group": "支持",
      "order": 14,
      "summary": "调试页导出的诊断包里有什么，出问题时该看哪几项。",
      "body": "## 调试页的四个视角\n\n| 视角 | 内容 |\n| --- | --- |\n| 执行流 | 把决策追踪、执行记录与截图按 (taskId, step) 合成一条时间轴 |\n| 指标 | 请求数、Token、耗时、视觉与执行的平均耗时、生成速度 |\n| 对话 | 与 AI 的完整往返消息 |\n| 日志 | 分级日志，可按级别与关键词筛选 |\n\n页头的「更多」菜单里还有：查看能力状态、用外挂视觉画框、导出诊断报告、导出 JSON（分任务）、清空调试数据。\n\n## 诊断报告里有什么\n\n导出的是 `hpa_diagnostic_<时间戳>.txt`，落在「下载/HappyPhoneAgent」。分两大块：\n\n**一、人话摘要区（用户视角）**\n\n- 按任务列出每一步，用 HumanTranslator 把原始决策文本概括成一句人话；\n- 列出全部 ERROR / WARN 并翻译成人话。\n\n**二、原始数据区（开发者视角）**\n\n- 执行追踪：每一步的 SENT（发给模型的完整文本）、GOT（模型原始返回）、视觉来源与模型、是否思考、Token、耗时；\n- 系统日志：含 API 请求与响应原文；\n- 对话：每条消息按角色列出，内容截断到 500 字。\n\n## 导出前是否脱敏\n\n原始数据区逐处脱敏：SENT / GOT、日志正文与详情、对话内容在写出前都经过 DataSanitizer，手机号、身份证号、银行卡号会被掩码。人话摘要区由 HumanTranslator 从追踪与日志文本生成。\n\n另有「导出 JSON（分任务）」：每个任务一个 `hpa_logs_<id>_<任务名>_<时间戳>.json`，字段为 ts / time / level / message / detail；无归属的系统日志单独成文件。\n\n## 容量上限与滚动\n\n记录都是内存环形缓冲，超出后只保留最近部分：\n\n| 记录 | 上限 |\n| --- | --- |\n| 日志 | 800 条 |\n| 决策追踪 | 300 条 |\n| 执行历史 | 400 条 |\n| 对话 | 200 条 |\n| 记忆事件 | 20 条 |\n| 任务会话 | 30 条 |\n\n这些记录会落盘到应用内部目录，重启后回载；调试页「清空调试数据」会同时清掉内存与落盘内容。\n\n## 拿到包之后按什么顺序看\n\n1. **执行流**：先看第几步开始偏离预期，重点核对那一步的 SENT（模型看到的页面）与 GOT（模型给出的意图）；\n2. **日志筛 ERROR / WARN**：API 错误原文、降级与回退都记在这里；\n3. **指标**：请求数、Token 与耗时是否异常，视觉平均耗时能看出是端侧还是云端拖时间；\n4. **对话**：需要还原完整上下文时再看。\n\n## 提 Issue 时该附什么\n\n- 诊断报告（人话摘要 + 原始数据）；\n- 导出 JSON（分任务），便于按任务定位；\n- 复现步骤、机型与系统版本；\n- 模型配置：API 地址与模型名（脱敏后的即可）。"
    },
    {
      "slug": "local-dev",
      "title": "本地开发环境",
      "group": "支持",
      "order": 15,
      "summary": "从 clone 到跑起 App 和官网，以及测试与打包命令。",
      "body": "## 环境要求\n\n| 项目 | 要求 | 依据 |\n| --- | --- | --- |\n| JDK | 17 | compileOptions 与 kotlinOptions 的 jvmTarget 均为 17 |\n| Android SDK | 35 | compileSdk / targetSdk = 35，minSdk = 26 |\n| Gradle | 8.14.2 | gradle/wrapper/gradle-wrapper.properties |\n| Node | >= 18 | website/package.json 的 engines |\n| Android Studio | Hedgehog 或更新 | 可选，命令行构建不需要 |\n\n## 项目结构\n\n仓库分两部分：根目录是 Android 工程，`website/` 是官网。App 代码在 `app/src/main/java/com/phoneagent/`，按层组织：\n\n| 目录 | 职责 |\n| --- | --- |\n| core/ | 跨层基础设施：模型客户端、敏感页检测与脱敏、人话翻译、通知 |\n| domain/ | 纯领域模型与规则：动作 / 意图 / 状态、硬约束规则引擎、命令解析、端侧决策 |\n| data/ | 持久化：DataStore 配置，以及记忆 / 任务 / 调试记录 / MCP / 提示词模板各 Store |\n| engine/ | Agent 编排：ReAct 主循环、提示词、执行转译、感知标注、页面指纹 |\n| overlay/ | 悬浮窗服务与绘制 |\n| feature/ | 功能域：技能、MCP、文档、跳广告、边缘光效、自测 |\n| ui/ | 界面层：首页 / Agent / 记忆 / 调试 / 技能 / 设置 / 自测各页 |\n| di/ | Koin 依赖注入模块 |\n\n官网这边，`src/` 是 Vue3 前端，`server/` 是 Express 内容 API，`server/data/*.json` 存放全部内容，`scripts/` 放快照与种子脚本。\n\n## 跑起 App\n\n仓库根目录就是 Android 工程，`gradlew` 与 `gradlew.bat` 都在根目录：\n\n```bash\n# Windows 用 gradlew.bat，macOS / Linux 用 ./gradlew\ngradlew.bat assembleDebug\n\n# 产物：app/build/outputs/apk/debug/app-debug.apk\n\n# 安装到已连接的设备\nadb install app/build/outputs/apk/debug/app-debug.apk\n```\n\n版本号来自根目录 `version.properties` 的 `BUILD_NUMBER`：执行 assemble / bundle 任务时自增，`versionCode` 取该值，`versionName` 为 `0.1.<BUILD_NUMBER>`。配置阶段不会改动它。也可以直接用 Android Studio 打开根目录运行；Debug 构建不混淆，适合本地调试。\n\n## 跑起官网\n\n官网在 `website/`，是 Vue3 前端加 Express 内容 API：\n\n```bash\ncd website\nnpm install\nnpm run dev\n```\n\n`npm run dev` 用 concurrently 同时起两个进程：\n\n| 进程 | 命令 | 端口 |\n| --- | --- | --- |\n| API | node --watch server/index.js | 5180（可用环境变量 PORT 覆盖） |\n| 前端 | vite | 5173 |\n\n前端的 `/api` 与 `/apk` 请求代理到 127.0.0.1:5180，所以只需访问前端端口。只想起其中一端时用 `npm run dev:api` 或 `npm run dev:web`；生产模式用 `npm start` 只起 API，由 Express 托管已构建的前端。内容全部是 `server/data/*.json`，没有数据库。\n\n## 构建 APK\n\n```bash\ngradlew.bat assembleDebug     # Debug，不混淆\ngradlew.bat assembleRelease   # Release，开启 minify 与资源压缩\n```\n\nRelease 构建额外做了两件事：`isMinifyEnabled` 与 `isShrinkResources` 打开，ABI 只保留 arm64-v8a，用于减小包体。签名从 `app/upload-signing.properties` 读取，该文件不存在时跳过 release 签名配置。这两个文件都是本地文件，不应提交到仓库。\n\n## 官网静态导出\n\n给没有后端的部署（如 GitHub Pages / Gitee Pages）用：\n\n```bash\ncd website\nnpm run snapshot       # 把 server/data 烘成前端快照 src/data/fallback.js\nnpm run build:static   # 等价于 snapshot + STATIC_EXPORT=1 vite build，产物 dist-static/\n```\n\n`build:static` 会先跑 snapshot 再构建，产物为 hash 路由加相对 base，所有内容从快照读取；快照由 `scripts/snapshot.mjs` 生成，不要手工编辑。快照里不含管理端点，也不含任何令牌。\n\n## 测试\n\n```bash\n# Android 单元测试（JVM 运行，android.util.Log 等返回默认值）\ngradlew.bat test\n```\n\n测试覆盖 HtmlToMarkdown、SkillCompat、SkillRegistry、McpClient、IntentTranslator、EngineRules、页面指纹、无线 ADB 状态机等纯逻辑模块。仓库根目录 `tests/` 下另有一组 `.mjs` 脚本，用于提示词的联调与回归测试。"
    },
    {
      "slug": "privacy",
      "title": "隐私政策",
      "group": "合规",
      "order": 16,
      "summary": "哪些数据留在设备上、哪些会发出去、发给了谁。",
      "body": "## 设备上存了什么\n\n应用把数据放在应用私有空间，其它应用读不到。主要落点：\n\n| 位置 | 内容 |\n| --- | --- |\n| DataStore `agent_settings` | 模型配置（API 地址、Key、模型名、温度、提示词语言、执行通道偏好、各类开关） |\n| DataStore `agent_memory` | AI 记忆（ai_memory）、用户画像（user_profile）、异常经验（anomaly_memory）、任务记忆（task_memory） |\n| DataStore `agent_tasks` | 任务检查点与任务模板库 |\n| DataStore `mcp_servers` | MCP 服务器配置与 Token |\n| 应用私有目录 `hpa_debug_hist/` | 调试记录 `records.json` 与每步截图缩略图 `shot_*.png` |\n| 外部私有目录 `documents/` | `write_doc` 生成的文档正文 |\n\n模型 API Key 与 MCP Token 保存在 DataStore 的 Preferences 中，未做额外的加密处理——它们依赖 Android 应用沙箱隔离，而不是独立密钥库。\n\n## 数据会发出去吗\n\n应用只有一个网络出口：**你自己配置的模型服务端点**（OpenAI 兼容的 chat/completions），请求头带上你填的 Key。每次决策会把这些内容发过去：\n\n- 你输入的任务描述与补充说明；\n- 当前页面的元素树（控件的文本、描述等结构化字段）；\n- 决策对话历史；\n- 截图（base64 图像）——仅在你开启视觉能力并配置了视觉模型时。\n\n此外：MCP 调用只会发给你主动配置并启用的 MCP 服务器；内置浏览器加载网页时的网络请求由 WebView 直接发出，与你用系统浏览器访问该网址是同一回事。\n\n## 脱敏规则\n\n进模型上下文之前，DataSanitizer 会遮蔽三类数字串：\n\n| 类型 | 匹配 | 结果 |\n| --- | --- | --- |\n| 身份证号 | 18 位，末位可为 X | 前 6 位 + ******** + 后 4 位 |\n| 手机号 | 1[3-9] 开头的 11 位连续数字 | 前 3 位 + **** + 后 4 位 |\n| 银行卡号 | 16~19 位连续数字 | 前 4 位 + **** + 后 4 位 |\n\n处理顺序是先脱敏最长的身份证号，再手机号，最后银行卡号——否则 18 位身份证号会被 16~19 位的银行卡正则先吃掉，留下残段。脱敏发生在构造提示词的环节，不是事后过滤：被遮蔽的内容从未离开设备。\n\n## 敏感页面只读\n\nSensitivePageDetector 在支付、金融类应用页面（命中包名特征）上进一步检查关键词（支付密码、验证码、转账、银行卡、余额等）。命中后该页面被横切为**只读**：Agent 可以看、可以描述，但不能提交。\n\n## 是否采集遥测或崩溃上报\n\n**不采集。** 依据有三条：\n\n1. **依赖层面** —— `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 中没有 Firebase、Crashlytics、Sentry、BugSnag、友盟等任何统计或崩溃上报 SDK。\n2. **代码层面** —— 全仓库没有向第三方上报的调用；检索到的「埋点」字样只是调试页用于本地展示的耗时聚合。\n3. **数据出口层面** —— 网络请求只发生在 AI 客户端、MCP 客户端与 WebView 三处，目标分别是你配置的模型端点、你配置的 MCP 服务器、你访问的网页。\n\n调试页的导出（日志 / 诊断报告）是**本地文件**，写到「下载/HappyPhoneAgent/」，是否交给他人完全由你决定；诊断报告在导出前还会再跑一次脱敏。\n\n## 权限清单与用途\n\n| 权限 | 用途 |\n| --- | --- |\n| INTERNET / ACCESS_NETWORK_STATE | 访问模型服务与网页 |\n| POST_NOTIFICATIONS | 前台服务与任务进度通知 |\n| FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PROJECTION | 屏幕录制前台服务 |\n| FOREGROUND_SERVICE_SPECIAL_USE | 悬浮窗前台服务 |\n| SYSTEM_ALERT_WINDOW | 显示悬浮窗进度 |\n| QUERY_ALL_PACKAGES | 按名称查包名、列出已安装应用 |\n| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 长任务在后台持续运行 |\n| com.termux.permission.RUN_COMMAND | Termux 命令行通道（需已安装 Termux） |\n| 无障碍服务 | 读取屏幕控件并执行点击 / 滑动 / 输入，在系统设置里手动开启 |\n| Shizuku | 通过 Shizuku 获得 shell 权限，可选 |\n\n应用还放开了 `usesCleartextTraffic`，用于把 API 地址指向内网 http 服务（如 `http://192.168.1.5:8000/v1`）；不打算用内网服务时可以忽略这一点。\n\n## 如何彻底清除数据\n\n应用内没有「一键清除全部数据」的入口，彻底清除走系统路径：\n\n1. 系统设置 → 应用 → Happy Agent → 存储 → 清除数据，或直接卸载应用。这会删掉模型 Key、记忆、检查点、MCP 配置、调试记录与文档。\n2. 手动删除导出文件：「下载/HappyPhoneAgent/」下的日志与诊断报告不在应用私有空间，清除应用数据不会动它们。\n3. 如已授权，到系统设置里关闭无障碍服务与悬浮窗权限。"
    },
    {
      "slug": "disclaimer",
      "title": "免责声明",
      "group": "合规",
      "order": 17,
      "summary": "无障碍权限与自动化操作的能力边界和责任划分。",
      "body": "## 以什么状态分发\n\n当前提供的安装包是 **Debug 构建，用于内测与自用**。这意味着：\n\n- 它不经过应用商店的审核流程，安装时可能需要你手动允许「安装未知来源应用」；\n- 安装后需要你逐项手动授予无障碍、悬浮窗、通知等权限；\n- 版本号形如 `0.1.<BUILD_NUMBER>`，随每次构建自增，下载页给出的 SHA-256 可用于核对包体。\n\n它面向愿意自己动手、能看懂日志与诊断报告的用户，而不是普通消费者成品软件。\n\n## 无障碍与自动化操作的固有风险\n\n无障碍服务是 Agent 的手和眼：授予后，应用可以读取当前屏幕上的一切控件文本，并模拟点击、滑动、文字输入。这等于把「看屏幕 + 操作屏幕」的能力交给了这个应用，风险是固有的：\n\n- 屏幕上出现的任何内容都可能被读取，包括通知、聊天、表单；\n- 模拟操作可能落在非预期控件上；\n- 系统会在无障碍开启期间给出提示，你可以随时在系统设置里关闭它，关闭后 Agent 立即失效。\n\n如果你对某个页面不放心，最稳妥的做法是先关闭无障碍，等需要时再开。\n\n## AI 决策可能出错\n\n决策由你配置的语言模型做出，模型可能误解页面、把 A 按钮当成 B 按钮、或对当前状态做出错误判断。项目为此加了多重护栏：\n\n- 一步一个动作，同一控件不反复操作；\n- 动作前后做页面指纹比对，确认是否真的生效；\n- 连续失败 3 次会请求用户介入；\n- 敏感页面只读，不提交；\n- 只有在当前页面看到明确证据时才接受「任务完成」，且执行步数不足时会被打回。\n\n但这些护栏只能降低出错概率，不能消除。涉及真实数据的任务，请人工复核结果，不要无人值守地跑。\n\n## 不可逆操作的责任划分\n\n对支付、转账、删除数据、发送消息这类不可逆操作：\n\n- `delete` 会标记为不可逆并自动触发用户确认；\n- 敏感页面（支付密码、验证码、转账、银行卡等关键词命中）会被横切为只读，Agent 无法提交；\n- 但确认之后是否执行、以及执行的后果，由你承担。\n\n建议先在测试账号、测试数据或空数据上验证任务流程，确认行为符合预期后再用到真实场景。不要把它用在涉及真实资金的自动操作上。\n\n## 按原样提供\n\n本项目基于 **MIT 协议**开源，按「按原样提供」分发，不提供任何明示或默示的担保，包括但不限于对适销性、特定用途适用性和非侵权性的担保。作者与贡献者不对因使用或无法使用本软件而产生的任何索赔、损害或其它责任负责。\n\n## 你的责任\n\n- 你对在本设备上执行的每一次操作负责，包括 Agent 代表你做出的操作；\n- 请遵守当地法律，以及你所操作应用的服务条款——自动化操作在某些平台上可能被禁止；\n- 你自行配置的模型服务与 MCP 服务器由你选择与部署，它们的可用性、计费与数据处理由对应服务商负责；\n- 如用于生产或对外分发，请自行完成相应的合规与安全评估。\n\n如果你不同意以上任一条，请不要安装或使用本应用。"
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
    },
    {
      "id": "power-heat",
      "order": 11,
      "group": "性能",
      "q": "跑任务时手机发烫、掉电快，正常吗？",
      "a": "正常。每一步都会读屏并按需截图，元素树读不到控件时还会调用视觉模型，悬浮窗与跑马光效也会持续绘制，负载因此偏高。可在设置里关掉自动截图、视觉识别与跑马光效来降低负载；关掉悬浮窗不影响任务执行，只是看不到实时进度。见 [执行通道](/docs/execution-channels)。"
    },
    {
      "id": "token-usage",
      "order": 12,
      "group": "使用",
      "q": "一次任务大概消耗多少模型额度？",
      "a": "没有固定值：每步都会向主模型发一次决策请求，开启视觉时会再叠加一次视觉模型调用，步数越多消耗越多。开启执行审核会让部分步骤多一次复核调用。调试页的指标面板可以看到请求数与输入、输出 token 统计。见 [模型配置](/docs/model-config)。"
    },
    {
      "id": "concurrency",
      "order": 13,
      "group": "使用",
      "q": "能同时跑两个任务吗？",
      "a": "不能。引擎同一时刻只允许一个任务在执行，后提交的任务进入待执行队列，界面会提示「还有 N 条任务排队等待执行」，当前任务结束后自动开始下一条。运行中仍可继续输入下一条，不必等当前任务结束。"
    },
    {
      "id": "a11y-killed",
      "order": 14,
      "group": "故障",
      "q": "无障碍服务过一会儿被系统自动关掉怎么办？",
      "a": "这是厂商的后台限制所致。先把应用加入省电白名单并允许自启动，主页权限雷达里的「自启动与后台」会按机型跳到对应的厂商自启动管理页。服务被系统杀掉时应用会发通知提醒你重新开启。见 [疑难排查](/docs/troubleshooting)。"
    },
    {
      "id": "offline",
      "order": 15,
      "group": "故障",
      "q": "断网了会怎样，配置会不会丢？",
      "a": "任务会中断：云端请求失败会先按退避重试，连续多次异常后引擎停止任务并给出提示。模型配置、记忆与技能都存在本地，断网不会丢失。每成功一步都会保存检查点，恢复网络后可在长线任务页续传。见 [疑难排查](/docs/troubleshooting)。"
    },
    {
      "id": "sensitive-actions",
      "order": 16,
      "group": "安全",
      "q": "AI 会不会误删数据或误转账？",
      "a": "不会自动执行。银行、支付类应用以及含「转账 / 支付密码 / 验证码」等文字的页面会被判定为敏感页，自动切为只读并请你手动操作。删除等不可逆动作在执行前也会标记为需要用户确认。见 [安全与隐私](/docs/safety)。"
    },
    {
      "id": "logs",
      "order": 17,
      "group": "故障",
      "q": "日志在哪里看，能留多久？",
      "a": "调试页有日志面板，可按级别与关键词筛选，最近最多保留 800 条。日志会写入应用内部目录，重启后自动回载，不会被清空。需要归档时可导出到「下载/HappyPhoneAgent」，支持文本、分任务 JSON 与诊断报告。见 [疑难排查](/docs/troubleshooting)。"
    },
    {
      "id": "upgrade",
      "order": 18,
      "group": "下载",
      "q": "怎么升级或降级，配置会不会丢？",
      "a": "从下载页取新版 APK 覆盖安装即可，当前版本号显示在主页底部。模型配置、记忆、技能都保存在应用内部存储，覆盖安装不会清空，只有卸载重装才会丢。降级需先卸载再装旧版，配置会随之清空。见 [下载页](/download)。"
    },
    {
      "id": "custom-skill-mcp",
      "order": 19,
      "group": "开发",
      "q": "能自己加技能或接自己的 MCP 服务吗？",
      "a": "可以。技能管理页支持新建、编辑、启停自定义技能，并能以 JSON 清单导入导出。MCP 页可添加 HTTP 地址与 Bearer Token，把服务器的工具绑定为技能后与内置技能一起被调用。内置技能不可删改，只能启停。见 [架构总览](/docs/architecture)。"
    },
    {
      "id": "plan-steps",
      "order": 20,
      "group": "使用",
      "q": "为什么同一任务的规划步数会变？",
      "a": "规划每次都按当前页面与任务重新拆分，提示词要求拆成 3 到 8 步，遇到受阻会重新规划，所以步数不固定。执行策略也影响步数：命中模板时按脚本复用固定计划，逐步模式则每步都走云端决策。设置里「限制最大步数」达到后会自动停止。"
    },
    {
      "id": "builtin-browser",
      "order": 21,
      "group": "使用",
      "q": "内置浏览器和系统浏览器有什么区别？",
      "a": "内置浏览器是本应用里的 WebView 页面，AI 通过 DOM 脚本读写网页，不依赖无障碍、Shizuku 或 Termux，上网类任务默认走它。系统浏览器只在你要把某个网址打开给自己看时用，AI 只负责把链接交给系统打开，不读取页面内容。见 [执行通道](/docs/execution-channels)。"
    },
    {
      "id": "language",
      "order": 22,
      "group": "使用",
      "q": "支持哪些语言，AI 回复是中文吗？",
      "a": "界面为简体中文。设置里的提示词语言可切换中文或英文，决定发给模型的提示词语种。AI 若输出了非中文内容，界面展示前会自动翻译成简体中文，译文带缓存，同一段不会重复调用。"
    }
  ],
  "scenarios": [
    {
      "id": "screen-brightness-half",
      "order": 1,
      "category": "系统设置",
      "title": "把屏幕亮度调到一半",
      "goal": "打开设置，把屏幕亮度调到一半。",
      "channel": "无线 ADB",
      "steps": [
        {
          "title": "确认通道就绪",
          "detail": "先确认无线 ADB 已连接、拿到 shell 权限；没有 shell 时这一步会降级到无障碍，改去设置页手动拖滑杆。"
        },
        {
          "title": "读取当前亮度",
          "detail": "经 shell 读一次当前亮度值，据此算出「一半」具体是多少，而不是凭空填一个数字。"
        },
        {
          "title": "下发亮度命令",
          "detail": "用 brightness 友好命令写入目标值，端侧 ShellCommands 会把它转成 settings put system screen_brightness。"
        },
        {
          "title": "回屏确认结果",
          "detail": "命令返回成功不等于完成，还要重新观察屏幕，确认亮度确实变了才算这一步生效。"
        }
      ],
      "note": "屏幕亮度上限是 255，一半即 128；部分机型开了自动亮度会覆盖手动值。"
    },
    {
      "id": "mute-app-notification",
      "order": 2,
      "category": "系统设置",
      "title": "关掉某个应用的通知",
      "goal": "把抖音的通知全部关掉。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "启动设置页",
          "detail": "用 launch 走包名直接拉起系统设置，不点桌面图标——图标位置会变，点空还得重来。"
        },
        {
          "title": "定位目标应用",
          "detail": "在应用列表里找到目标应用；列表长就先滚动查找，找不到就中止并说明当前前台页面，不做盲点。"
        },
        {
          "title": "进入通知设置",
          "detail": "点开应用详情后进入「通知」这一级，总开关只在这里，应用详情首页看不到它。"
        },
        {
          "title": "关闭通知开关",
          "detail": "把总开关切到关闭状态；开关类控件走 switch 语义意图，不按坐标去戳开关边缘。"
        },
        {
          "title": "回屏确认状态",
          "detail": "切换后重新读一次页面，确认开关已经处于关闭态；点击成功不算完成，要看页面状态。"
        }
      ],
      "note": "各厂商设置层级不完全一致，找不到「通知」入口时应中止并说明卡点，而不是继续乱点。"
    },
    {
      "id": "wechat-moments",
      "order": 3,
      "category": "应用操作",
      "title": "打开微信并进入朋友圈",
      "goal": "打开微信，进入朋友圈。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "启动微信",
          "detail": "用 launch 按包名 com.tencent.mm 拉起微信，启动后先确认前台应用确实切过来了。"
        },
        {
          "title": "处理开屏广告",
          "detail": "若出现开屏广告，端侧广告过滤器会识别并点掉跳过按钮，广告内容不会进入决策上下文。"
        },
        {
          "title": "切到发现页",
          "detail": "在底部导航里点「发现」，按控件文字定位，不依赖底栏图标的位置和排列顺序。"
        },
        {
          "title": "进入朋友圈",
          "detail": "在发现页点「朋友圈」进入，进入后再观察一次，确认已经加载出动态流而不是空白页。"
        }
      ],
      "note": "微信版本不同，底部导航文案可能变化，定位一律以控件文字为准。"
    },
    {
      "id": "store-search-install",
      "order": 4,
      "category": "应用操作",
      "title": "在应用商店搜索并安装一个应用",
      "goal": "在应用商店里搜索并安装一个笔记应用。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "启动应用商店",
          "detail": "先拉起本机的应用商店，同名多个时优先系统自带；启动后再定位搜索入口，不猜详情页在哪。"
        },
        {
          "title": "输入搜索词",
          "detail": "点搜索框获焦后写入关键词，输入走 input 语义意图，由端侧转成文字输入动作。"
        },
        {
          "title": "打开目标结果",
          "detail": "在结果列表里按名称匹配点进目标应用详情页；多个同名结果时参考开发者和安装量再选。"
        },
        {
          "title": "点击安装",
          "detail": "点详情页的「安装」按钮，随后系统会弹出安装确认框，这一步会停下来等你处理。"
        },
        {
          "title": "等待安装完成",
          "detail": "确认后等待下载安装，详情页出现「打开」按钮即为安装完成的页面证据，据此判定结束。"
        }
      ],
      "note": "需要该应用商店具备安装权限；系统确认框属于人工环节，Agent 不会替你代签。"
    },
    {
      "id": "skip-splash-ad",
      "order": 5,
      "category": "应用操作",
      "title": "打开应用时跳过开屏广告",
      "goal": "打开淘宝，跳过开屏广告后进入首页。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "启动目标应用",
          "detail": "用 launch 按包名拉起淘宝；启动瞬间通常就是开屏广告，先别急着做后续动作。"
        },
        {
          "title": "识别广告形态",
          "detail": "端侧广告过滤器扫描当前页面，识别含「广告/推广」字样、且带跳过或关闭按钮的区域。"
        },
        {
          "title": "点掉跳过按钮",
          "detail": "命中后直接点击跳过或关闭按钮，并跳过本轮模型决策；单字符叉号只在图片类控件上才点。"
        },
        {
          "title": "回屏确认进入",
          "detail": "点击后重新观察，确认已经落到首页；广告还在就继续识别，不会反复戳同一个按钮。"
        }
      ],
      "note": "广告过滤只在任务执行过程中生效，它不是常驻的自动跳广告服务。"
    },
    {
      "id": "weather-tomorrow",
      "order": 6,
      "category": "信息查询",
      "title": "查一下明天本地的天气",
      "goal": "查一下明天这里的天气，告诉我温度和有没有雨。",
      "channel": "内置浏览器",
      "steps": [
        {
          "title": "打开天气页面",
          "detail": "用 browse_open 在内置浏览器打开天气网址，App 会切到浏览器页，之后每步截图就是真实网页。"
        },
        {
          "title": "读取网页正文",
          "detail": "用 browse_read 抓取当前页，正文以 Markdown 返回，标题层级与表格结构都保留，直接喂给决策。"
        },
        {
          "title": "翻到目标日期",
          "detail": "首屏没有明天的数据就用 browse_scroll 往下滚，再读一次，直到出现明天的温度与降水。"
        },
        {
          "title": "汇总并作答",
          "detail": "从正文里摘出明天的温度和降水情况作为结论；只报页面上真实出现的内容，读不到就说明。"
        }
      ],
      "note": "天气页多为动态渲染，读完正文若为空，可以先 wait 一下再读一次。"
    },
    {
      "id": "express-tracking",
      "order": 7,
      "category": "信息查询",
      "title": "查一个快递单号的物流进度",
      "goal": "查一下这个快递单号现在到哪了。",
      "channel": "内置浏览器",
      "steps": [
        {
          "title": "打开查询页",
          "detail": "用 browse_open 打开快递查询页，能带查询参数就带上，减少后面手动填写的步骤。"
        },
        {
          "title": "填写单号",
          "detail": "页面需要手填时用 browse_input 写入单号，目标以输入框提示文字或 id 指定，不靠位置猜。"
        },
        {
          "title": "提交查询",
          "detail": "用 browse_click 点查询按钮；点击后浏览器桥会稍等页面更新，再进入下一步读取。"
        },
        {
          "title": "读取物流轨迹",
          "detail": "用 browse_read 抓取结果页正文，从 Markdown 列表里取出最新一条物流节点。"
        },
        {
          "title": "汇报当前状态",
          "detail": "把最新节点和更新时间作为结论输出；查不到单号就如实说明，不编造进度。"
        }
      ],
      "note": "部分查询页有验证码或会跳转到 App，遇到时中止并说明卡点，不硬闯。"
    },
    {
      "id": "storage-usage",
      "order": 8,
      "category": "信息查询",
      "title": "看一下手机的存储占用",
      "goal": "帮我看看这台手机还剩多少存储空间。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "调用本机查询",
          "detail": "直接下发 device_query，kind=storage；这类查询纯本地读取，不触碰设备，也不花模型往返。"
        },
        {
          "title": "读取分区容量",
          "detail": "端侧用 StatFs 读数据分区的可用与总容量，换算成 GB 后作为「上一步结果」回注下一轮决策。"
        },
        {
          "title": "换算并汇报",
          "detail": "把可用、总量和占比整理成一句人话结论；哪一项读不到就直说，不给近似值。"
        }
      ],
      "note": "device_query 不走任何执行通道，这里列无障碍，是因为它是必须授予的基础能力。"
    },
    {
      "id": "gallery-screenshots-archive",
      "order": 9,
      "category": "内容整理",
      "title": "把相册里的截图按月份归档",
      "goal": "把相册里的截图按月份整理到不同文件夹。",
      "channel": "无线 ADB",
      "steps": [
        {
          "title": "定位截图目录",
          "detail": "先确认截图所在目录，通常是 Pictures/Screenshots；列出文件看清命名规律，再决定怎么分组。"
        },
        {
          "title": "规划归档结构",
          "detail": "按文件名里的日期前缀分组，先规划出每个月份的目标子目录，避免边移动边改方案。"
        },
        {
          "title": "建目录并移动",
          "detail": "经 shell 通道创建目标目录并移入文件；文件移动不可逆，动手前先确认目标目录没有同名文件。"
        },
        {
          "title": "核对移动结果",
          "detail": "重新列一次源目录和目标目录，确认文件数量与名称都对得上，再判定任务完成。"
        }
      ],
      "note": "移动后需要相册重新扫描媒体库才会看到新目录；没有 shell 通道时这一步无法执行。"
    },
    {
      "id": "translate-screen-text",
      "order": 10,
      "category": "内容整理",
      "title": "把屏幕上的英文段落翻译成中文",
      "goal": "把当前屏幕上的这段英文翻译成中文，存成一篇文档。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "读取屏幕文字",
          "detail": "先用无障碍取回当前页面的控件文字，拼出待翻译的英文段落，并确认内容取全了。"
        },
        {
          "title": "脱敏后送模型",
          "detail": "段落进模型前统一脱敏；手机号、身份证号、银行卡号会被遮蔽，被遮蔽的内容从未离开设备。"
        },
        {
          "title": "生成中文译文",
          "detail": "把原文交给模型翻译，要求只输出中文译文，不做解释和扩写，方便直接落盘。"
        },
        {
          "title": "落盘成文档",
          "detail": "用 write_doc 把译文写进应用私有目录，结果在 Agent 页内嵌预览，不在屏幕上假装打字。"
        }
      ],
      "note": "文档类任务必须直接输出 write_doc，不会打开备忘录逐字输入。"
    },
    {
      "id": "read-article-to-doc",
      "order": 11,
      "category": "网页与文档",
      "title": "用内置浏览器读一篇网页正文并存成文档",
      "goal": "打开这篇文章，把正文整理成一篇文档存下来。",
      "channel": "内置浏览器",
      "steps": [
        {
          "title": "打开文章页",
          "detail": "用 browse_open 在内置浏览器打开文章网址，等加载完成再进下一步，避免读到白屏。"
        },
        {
          "title": "抓取正文",
          "detail": "用 browse_read 抓取正文，结果已是 Markdown，标题层级、列表、代码块和链接都保留下来。"
        },
        {
          "title": "补齐长文内容",
          "detail": "正文被截断时用 browse_scroll 往下滚再抓一次，把后续段落接上，保证文档完整。"
        },
        {
          "title": "写入文档",
          "detail": "把整理好的 Markdown 交给 write_doc 落盘，并在 Agent 页的预览区核对内容无误。"
        }
      ],
      "note": "正文回注有字符预算，超长文章只会截到块边界，不会切出半个链接。"
    },
    {
      "id": "web-form-submit",
      "order": 12,
      "category": "网页与文档",
      "title": "在网页表单里填写信息并提交",
      "goal": "打开这个报名页面，帮我把表单填好并提交。",
      "channel": "内置浏览器",
      "steps": [
        {
          "title": "打开表单页",
          "detail": "用 browse_open 打开报名页，加载完成后先读一次页面，确认表单真实存在再动手。"
        },
        {
          "title": "识别输入项",
          "detail": "从 browse_read 返回的输入框清单里确认每项的提示文字和 id，再一一对应要填的内容。"
        },
        {
          "title": "逐项填写",
          "detail": "用 browse_input 按提示文字或 id 定位，逐项填入；定位优先文字，不靠位置猜测。"
        },
        {
          "title": "提交并确认",
          "detail": "填完用 browse_click 点提交按钮；提交属于不可逆操作，会先请你确认再下发。"
        },
        {
          "title": "回屏核对结果",
          "detail": "提交后重新读一次页面，确认出现成功提示或跳转到结果页，才判定任务完成。"
        }
      ],
      "note": "涉及支付或个人敏感信息的表单会被横切为只读，Agent 只读不提交。"
    },
    {
      "id": "map-search-place",
      "order": 13,
      "category": "日常事务",
      "title": "打开地图搜索一个地点",
      "goal": "在地图里搜一下附近的地铁站。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "启动地图应用",
          "detail": "用 launch 拉起本机地图应用，同名多个时优先系统自带；启动后确认前台已经切到地图。"
        },
        {
          "title": "进入搜索",
          "detail": "点搜索框获焦，走 search 语义意图，而不是按坐标点搜索按钮，避免改版后点空。"
        },
        {
          "title": "输入关键词",
          "detail": "写入「地铁站」这类关键词并触发搜索，等结果列表出现再继续，不在加载中乱点。"
        },
        {
          "title": "查看首个结果",
          "detail": "在结果列表里点开第一条，进入详情页确认名称和距离，再把结果作为结论汇报。"
        }
      ],
      "note": "定位权限未授予时地图会先弹权限框，需要处理掉弹窗再继续。"
    },
    {
      "id": "send-message-chat",
      "order": 14,
      "category": "日常事务",
      "title": "把一段文字发到聊天窗口",
      "goal": "打开微信，把「会议改到下午三点」发给张三。",
      "channel": "无障碍",
      "steps": [
        {
          "title": "启动并进会话",
          "detail": "用 launch 拉起微信，在会话列表里按联系人名字点开目标聊天，定位以控件文字为准。"
        },
        {
          "title": "定位输入框",
          "detail": "找到底部输入框并获焦；找不到就滚动或退回上一级重进，不在错误的页面上硬点。"
        },
        {
          "title": "输入消息内容",
          "detail": "用 input 语义意图把文字写进输入框，输入由端侧转译，模型不直接输出输入命令。"
        },
        {
          "title": "发送并确认",
          "detail": "发送属于不可逆操作，会先请你确认；确认后点发送，再回屏确认消息已经出现在会话里。"
        }
      ],
      "note": "发送前若发现输入框里已有草稿，先清空再写，避免两条内容粘在一起。"
    }
  ],
  "roadmap": {
    "updatedAt": "2026-09-22",
    "note": "按主题分组，不承诺具体时间；已交付项以仓库代码与 CHANGELOG 为准。",
    "phases": [
      {
        "id": "shipped",
        "title": "已交付",
        "state": "done",
        "summary": "从决策、执行到安全与展示的主链路已经跑通：一句话任务能被拆成 3~8 步并逐步执行，每一步都回到屏幕本身验证。",
        "items": [
          {
            "title": "ReAct 主循环与动态规划",
            "detail": "观察屏幕、规划 3~8 步、逐步执行、每步回屏校验；目标控件不在当前页面就不允许点击，找不到先滚动再中止。"
          },
          {
            "title": "15 个高层语义意图",
            "detail": "back/home/refresh/search/send/confirm 等意图由端侧 IntentTranslator 实时转译，模型永远不输出坐标与命令。"
          },
          {
            "title": "四通道执行体系",
            "detail": "无线 ADB 为主通道，Shizuku 与 Termux 作为可选与补充，无障碍兜底；AUTO 模式按序降级，失败不阻塞主流程。"
          },
          {
            "title": "页面指纹验证点击",
            "detail": "VerifiedClickExecutor 在动作前后各取一次页面指纹，把「AI 以为点了」和「页面真的变了」区分开。"
          },
          {
            "title": "敏感页只读与数据脱敏",
            "detail": "支付、个人信息等页面被横切为只读；手机号、身份证号、银行卡号在构造提示词时就被遮蔽。"
          },
          {
            "title": "内置浏览器网页读写",
            "detail": "browse_open/read/click/input/scroll/back 六个意图走 App 自己的 WebView，正文转成 Markdown 后回注决策。"
          },
          {
            "title": "文档结果直出",
            "detail": "write_doc 把内容落盘到应用私有目录，并在 Agent 页内嵌 Markdown 预览，不在屏幕上假装打字。"
          },
          {
            "title": "环境上下文与按需取数",
            "detail": "每步注入时间、前台应用、网络、电量等事实；device_query 支持 apps/time/battery/network/storage 查询。"
          },
          {
            "title": "液态玻璃悬浮窗与随时接管",
            "detail": "执行时只占三行、详情默认折叠；遇到歧义弹出澄清选项，可随时补话或用「已手动处理」让任务继续。"
          },
          {
            "title": "任务记忆与多任务队列",
            "detail": "任务记忆按 taskId 归属持久化；多任务顺序执行，TaskStore 保存检查点与任务模板库。"
          }
        ]
      },
      {
        "id": "doing",
        "title": "正在做",
        "state": "doing",
        "summary": "依据是最近若干版本的 CHANGELOG：以下主题在 v0.1.310 到 v0.1.415 之间反复出现，每次只推进一段，尚未收尾。",
        "items": [
          {
            "title": "中英双版提示词持续对齐",
            "detail": "每新增一个意图、每收窄一条边界，都要同步改中英两版系统提示词。v0.1.310 起连续多个版本都在做，目前仍是手工同步。"
          },
          {
            "title": "内置浏览器的网页交互覆盖",
            "detail": "v0.1.406 让 browse_read 正文改为 Markdown，v0.1.413 才理清「打开链接/文件」的分工；网页表单与多步流程的覆盖还在补。"
          },
          {
            "title": "打开目标与通道语义细化",
            "detail": "v0.1.413 新增本地路径归一化与 MIME 推断、同名应用优先系统自带；v0.1.415 又隔离了端侧 shell 的上下文污染，仍在迭代。"
          },
          {
            "title": "悬浮窗与执行期视觉收敛",
            "detail": "v0.1.331 让点击光标只属于执行中，v0.1.332 给玻璃背景做减法，v0.1.415 在执行期隐藏状态栏并修掉光标坐标偏移。"
          }
        ]
      },
      {
        "id": "next",
        "title": "接下来",
        "state": "next",
        "summary": "都是从现有架构自然延伸出去的方向，不引入新的权限模型或数据出口。",
        "items": [
          {
            "title": "任务模板的沉淀与管理",
            "detail": "TemplateMatcher 与 TaskStore 已有模板库，但模板目前只能由软件预置或经用户确认入库；下一步让模板可查看、编辑与导入导出。"
          },
          {
            "title": "任务中断后的检查点续跑",
            "detail": "TaskStore 已经在存检查点，但中断后仍是从头重跑；下一步支持从最后一个检查点接着执行。"
          },
          {
            "title": "端侧取数能力的扩展",
            "detail": "device_query 已覆盖时间、电量、网络、存储与应用清单；下一步把剪贴板、通知、外接设备这类只读事实也纳入按需查询。"
          },
          {
            "title": "端侧决策覆盖更多高频流程",
            "detail": "LocalDecisionEngine 现在处理弹窗、加载与广告；下一步把常见应用内的重复小流程也收进本地决策，减少模型往返。"
          }
        ]
      },
      {
        "id": "later",
        "title": "暂不计划",
        "state": "later",
        "summary": "这些方向都认真考虑过，但和现有的取舍或安全约束冲突，暂时不做。",
        "items": [
          {
            "title": "Root 提权",
            "detail": "与「不需要 Root」的取舍直接冲突：基础能力靠无障碍即可运行，系统命令由无线 ADB 或 Shizuku 覆盖，引入 Root 只会抬高安装门槛。"
          },
          {
            "title": "云端账号与任务云同步",
            "detail": "现有设计是内容只发给你自己配置的模型服务；再引入云端账号，等于把屏幕内容与任务记录留存在第三方，与本地优先的取向冲突。"
          },
          {
            "title": "自动完成支付、下单等不可逆操作",
            "detail": "敏感页只读、不可逆操作需用户确认是硬约束；让 Agent 自动提交支付会直接推翻这条安全边界，收益不足以抵消风险。"
          },
          {
            "title": "端侧内置大模型做离线推理",
            "detail": "当前架构是自配 OpenAI 兼容端点；端侧推理要背模型体积与内存开销，还要重做视觉链路，复杂度远高于收益。"
          }
        ]
      }
    ]
  },
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
        "path": "/scenarios",
        "name": "scenarios",
        "title": "场景示例",
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
        "path": "/roadmap",
        "name": "roadmap",
        "title": "路线图",
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
        "desc": "版本列表，支持 ?channel=stable|beta|nightly|dev|all &limit="
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
        "path": "/api/scenarios",
        "desc": "场景示例，支持 ?category="
      },
      {
        "method": "GET",
        "path": "/api/roadmap",
        "desc": "路线图：阶段与条目"
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
        "desc": "读取原始集合（site / features / releases / changelog / docs / faq / scenarios / roadmap）"
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
        "method": "PUT",
        "path": "/api/admin/scenarios",
        "desc": "整包替换场景示例"
      },
      {
        "method": "POST",
        "path": "/api/admin/scenarios",
        "desc": "新增场景"
      },
      {
        "method": "PUT",
        "path": "/api/admin/scenarios/:id",
        "desc": "更新场景"
      },
      {
        "method": "DELETE",
        "path": "/api/admin/scenarios/:id",
        "desc": "删除场景"
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
      "channelLabels": {
        "stable": "稳定版",
        "beta": "测试版",
        "nightly": "每日构建",
        "dev": "开发版"
      },
      "sectionTones": {
        "新增": "ok",
        "优化": "brand",
        "变更": "brand",
        "修复": "amber",
        "性能": "brand",
        "测试": "mist",
        "文档": "mist",
        "移除": "danger",
        "安全": "danger"
      },
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
        "slug": "model-services",
        "title": "常见模型服务配置示例",
        "group": "入门",
        "order": 3,
        "summary": "OLLAMA、LM Studio、one-api、vLLM 等本地与中转服务，地址和模型名该怎么填。"
      },
      {
        "slug": "execution-channels",
        "title": "执行通道",
        "group": "进阶",
        "order": 4,
        "summary": "无线 ADB、Shizuku、Termux、无障碍四者的能力边界与选择建议。"
      },
      {
        "slug": "intent-layer",
        "title": "意图转译层",
        "group": "进阶",
        "order": 5,
        "summary": "为什么 AI 永远不输出坐标和命令，以及 15 个语义意图是怎么落地的。"
      },
      {
        "slug": "safety",
        "title": "安全与隐私",
        "group": "进阶",
        "order": 6,
        "summary": "哪些页面只读、哪些数据会被脱敏、哪些操作必须你点头。"
      },
      {
        "slug": "architecture",
        "title": "架构总览",
        "group": "进阶",
        "order": 7,
        "summary": "感知、决策、执行、视觉、持久化、展示六层怎么协作。"
      },
      {
        "slug": "overlay-and-takeover",
        "title": "悬浮窗与接管",
        "group": "进阶",
        "order": 8,
        "summary": "浮窗上能看到什么、能做什么，以及它什么时候会停下来等你。"
      },
      {
        "slug": "vision-modes",
        "title": "视觉三态与端侧模型",
        "group": "进阶",
        "order": 9,
        "summary": "CLOUD / LOCAL / AUTO 三种视觉模式各自什么时候生效，元素树读不出来时怎么兜底。"
      },
      {
        "slug": "task-memory",
        "title": "任务记忆与检查点",
        "group": "进阶",
        "order": 10,
        "summary": "跨任务记住了什么、存在哪里、怎么清空。"
      },
      {
        "slug": "skills-and-mcp",
        "title": "技能与 MCP 接入",
        "group": "进阶",
        "order": 11,
        "summary": "内置技能有哪些、MCP 技能怎么注册，以及参数校验和超时规则。"
      },
      {
        "slug": "built-in-browser",
        "title": "内置浏览器",
        "group": "进阶",
        "order": 12,
        "summary": "browse_* 系列技能负责什么，网页是怎么被读成 Markdown 的。"
      },
      {
        "slug": "troubleshooting",
        "title": "疑难排查",
        "group": "支持",
        "order": 13,
        "summary": "连不上模型、点了没反应、浮窗不出现、任务老是中止。"
      },
      {
        "slug": "diagnostics",
        "title": "诊断包怎么读",
        "group": "支持",
        "order": 14,
        "summary": "调试页导出的诊断包里有什么，出问题时该看哪几项。"
      },
      {
        "slug": "local-dev",
        "title": "本地开发环境",
        "group": "支持",
        "order": 15,
        "summary": "从 clone 到跑起 App 和官网，以及测试与打包命令。"
      },
      {
        "slug": "privacy",
        "title": "隐私政策",
        "group": "合规",
        "order": 16,
        "summary": "哪些数据留在设备上、哪些会发出去、发给了谁。"
      },
      {
        "slug": "disclaimer",
        "title": "免责声明",
        "group": "合规",
        "order": 17,
        "summary": "无障碍权限与自动化操作的能力边界和责任划分。"
      }
    ]
  }
}
