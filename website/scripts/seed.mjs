#!/usr/bin/env node
/**
 * 把 Android 工程里的真实内容灌进官网数据目录。
 *
 * 数据来源：
 *   - ../CHANGELOG.md            → server/data/changelog.json（32 个版本条目）
 *   - ../version.properties      → 站点当前版本号
 *   - ../app/build/outputs/apk/  → public/apk/（仅在传 --apk 时复制）
 *   - 内置文案                    → site / features / docs / faq
 *
 * 默认不覆盖已存在的数据文件，避免把后台里改过的内容冲掉；加 --force 才强制重写。
 */
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const projectRoot = path.resolve(root, '..')
const dataDir = path.join(root, 'server', 'data')
const apkDir = path.join(root, 'public', 'apk')

const argv = process.argv.slice(2)
const force = argv.includes('--force')
const withApk = argv.includes('--apk') || force

fs.mkdirSync(dataDir, { recursive: true })
fs.mkdirSync(apkDir, { recursive: true })

function writeIfNeeded(name, value) {
  const file = path.join(dataDir, name)
  if (fs.existsSync(file) && !force) {
    console.log(`  · ${name} 已存在，跳过（--force 可覆盖）`)
    return
  }
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  console.log(`  ✓ ${name}`)
}

// ---------- 1. 版本号 ----------

function readBuildNumber() {
  const file = path.join(projectRoot, 'version.properties')
  if (!fs.existsSync(file)) return { build: 0, version: '0.1.0' }
  const match = fs.readFileSync(file, 'utf8').match(/BUILD_NUMBER\s*=\s*(\d+)/)
  const build = match ? Number(match[1]) : 0
  return { build, version: `0.1.${build}` }
}

const { build: buildNumber, version: currentVersion } = readBuildNumber()

// ---------- 2. 解析 CHANGELOG.md ----------

function normalizeVersion(input) {
  const v = String(input || '').trim()
  if (!v) return ''
  return v.startsWith('v') ? v : `v${v}`
}

/**
 * 拼接折行：中文之间不该凭空多出一个空格，英文单词之间又必须有。
 * 判据就是上一段末尾是不是中日韩字符或中文标点。
 */
const CJK_TAIL = /[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]$/
function joinWrapped(prev, next) {
  return CJK_TAIL.test(prev) ? prev + next : `${prev} ${next}`
}

function parseChangelog(markdown) {
  const entries = []
  let current = null
  let section = null
  let summaryBuffer = []

  const flushSummary = () => {
    if (!current) return
    const text = summaryBuffer.join(' ').replace(/\s+/g, ' ').trim()
    if (text) current.summary = text.slice(0, 400)
    summaryBuffer = []
  }

  for (const line of markdown.split(/\r?\n/)) {
    if (line.startsWith('## ')) {
      const head = line.match(/^##\s+\[?(v?[\d.]+)\]?\s*[—\-–(（]?\s*(\d{4}-\d{2}-\d{2})?/)
      if (head) {
        flushSummary()
        current = { version: normalizeVersion(head[1]), date: head[2] || '', summary: '', sections: [] }
        section = null
        entries.push(current)
        continue
      }
    }
    if (!current) continue
    const sub = line.match(/^###\s+(.+?)\s*$/)
    if (sub) {
      flushSummary()
      section = { type: sub[1].trim(), items: [] }
      current.sections.push(section)
      continue
    }
    const bullet = line.match(/^[-*]\s+(.*)$/)
    if (bullet && section) {
      const text = bullet[1].trim()
      if (text) section.items.push(text)
      continue
    }
    // CHANGELOG 里的条目经常折行，续行以缩进开头——要接回上一条，否则句子会被截断
    if (section && section.items.length && /^\s+\S/.test(line)) {
      const last = section.items.length - 1
      section.items[last] = joinWrapped(section.items[last], line.trim())
      continue
    }
    if (!section && line.trim() && !line.startsWith('#')) summaryBuffer.push(line.trim())
  }
  flushSummary()
  return entries.filter((e) => /^v\d/.test(e.version))
}

const changelogFile = path.join(projectRoot, 'CHANGELOG.md')
let changelog = []
if (fs.existsSync(changelogFile)) {
  changelog = parseChangelog(fs.readFileSync(changelogFile, 'utf8'))
  console.log(`  解析到 ${changelog.length} 个版本条目`)
} else {
  console.warn(`  ! 未找到 ${changelogFile}`)
}

// ---------- 3. 版本记录 ----------

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

function humanSize(bytes) {
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

function findBuiltApk() {
  const candidates = [
    path.join(projectRoot, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    path.join(projectRoot, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk')
  ]
  return candidates.find((f) => fs.existsSync(f)) || null
}

let apkFileName = null
if (withApk) {
  const built = findBuiltApk()
  if (!built) {
    console.warn('  ! 没找到已构建的 APK，先跑 ./gradlew assembleDebug 再执行 npm run seed:apk')
  } else {
    apkFileName = `HappyAgent-${currentVersion}.apk`
    fs.copyFileSync(built, path.join(apkDir, apkFileName))
    console.log(`  ✓ APK 已复制为 public/apk/${apkFileName}（${humanSize(fs.statSync(built).size)}）`)
  }
}

const apkMeta = apkFileName
  ? (() => {
      const full = path.join(apkDir, apkFileName)
      const stat = fs.statSync(full)
      return {
        file: apkFileName,
        size: stat.size,
        sizeText: humanSize(stat.size),
        sha256: sha256(full),
        updatedAt: stat.mtime.toISOString()
      }
    })()
  : null

const releases = [
  {
    version: `v${currentVersion}`,
    build: buildNumber,
    channel: 'stable',
    date: new Date().toISOString().slice(0, 10),
    published: true,
    title: '当前开发版',
    summary: changelog[0]?.summary || '',
    apk: apkMeta,
    notes: ['Debug 构建，用于内测与自用', '首次安装需手动授予无障碍、悬浮窗与通知权限']
  }
]

// ---------- 4. 站点元信息 ----------

const site = {
  name: 'Happy Phone Agent',
  shortName: 'Happy Agent',
  slug: 'phtomt',
  tagline: '让手机自己动手',
  description:
    'Happy Phone Agent 是一款运行在 Android 上的 AI 智能体。你用一句自然语言描述目标，它自己观察屏幕、拆解步骤、动手执行——点按、滑动、输入、跳转，全程可见可接管。',
  version: currentVersion,
  buildNumber,
  updatedAt: new Date().toISOString(),
  links: {
    github: 'https://github.com/xkx121029/Phtomt',
    gitee: 'https://gitee.com/xkx1029/Phtomt',
    issues: 'https://github.com/xkx121029/Phtomt/issues',
    releases: 'https://github.com/xkx121029/Phtomt/releases',
    changelog: 'https://github.com/xkx121029/Phtomt/blob/main/CHANGELOG.md'
  },
  requirements: {
    minAndroid: '8.0',
    minApi: 26,
    targetApi: 35,
    arch: 'arm64-v8a / armeabi-v7a',
    packageName: 'com.phoneagent'
  },
  techStack: [
    { name: 'Kotlin', version: '2.0.21' },
    { name: 'Jetpack Compose', version: '2024.12.01' },
    { name: 'Material 3', version: '1.3.1' },
    { name: 'Koin', version: '4.0.0' },
    { name: 'OkHttp', version: '4.12.0' },
    { name: 'DataStore', version: '1.1.1' }
  ],
  // 发布通道 id → 页面显示名。放数据里而不是前端常量：加一个渠道只要 PATCH 站点元信息，
  // 不用改代码、不用重新构建镜像。前端在没有这两张表时会用内置兜底值。
  channelLabels: {
    stable: '稳定版',
    beta: '测试版',
    nightly: '每日构建',
    dev: '开发版'
  },
  // 更新日志小节名 → 配色 token（ok / brand / amber / mist / danger）
  sectionTones: {
    新增: 'ok',
    优化: 'brand',
    变更: 'brand',
    修复: 'amber',
    性能: 'brand',
    测试: 'mist',
    文档: 'mist',
    移除: 'danger',
    安全: 'danger'
  },
  channels: [
    {
      id: 'accessibility',
      name: '无障碍通道',
      level: '普通应用权限',
      desc: '通过 AccessibilityService 读取控件树并执行点击、滑动、输入，兼容性最好，无需额外安装。',
      default: true
    },
    {
      id: 'adb',
      name: '无线 ADB 通道',
      level: 'shell 权限',
      desc: '手机自带无线调试，配对后即可执行 am / pm / settings 等系统命令，应用启动走真实包名而非模拟点按。',
      default: false
    },
    {
      id: 'shizuku',
      name: 'Shizuku 通道',
      level: 'shell 权限',
      desc: '可选增强，借助 Shizuku 拿到 ADB 级权限；未安装或授权失败不影响无线 ADB 主通道。',
      default: false
    },
    {
      id: 'termux',
      name: 'Termux 通道',
      level: '普通应用权限',
      desc: '把命令交给 Termux 执行并回读输出，为「图形界面做不到」的取数场景补一条 Linux 工具链。',
      default: false
    }
  ]
}

// ---------- 5. 功能特性 ----------

const features = [
  {
    id: 'decision',
    order: 1,
    tag: '决策',
    title: 'AI 驱动决策',
    summary: 'ReAct 主循环：观察 → 规划 → 执行 → 校验，每一步都基于当前真实页面。',
    detail:
      '接入任意 OpenAI 兼容端点（自建 Ollama / LM Studio / one-api 都行）。规划时步数动态收敛到 3~8 步，执行中每一步都以当前页面元素树为唯一依据——目标控件不在当前页面就不允许点击，找不到先滚动查找，仍找不到则中止并说明原因。'
  },
  {
    id: 'intent',
    order: 2,
    tag: '执行',
    title: '15 个高层语义意图',
    summary: 'AI 只说「做什么」，端侧转译层负责「怎么做」，模型永远不输出坐标和命令。',
    detail:
      'back / home / refresh / search / send / confirm / close / share / collect / copy / delete / download / add / switch / clear_input 全部由 IntentTranslator 实时转译为端侧动作。有精确 id/label 就用它，只有图片、图表这类元素树里没有的控件才退到坐标。'
  },
  {
    id: 'vision',
    order: 3,
    tag: '感知',
    title: '双引擎视觉理解',
    summary: '元素树可读时走端侧，稀疏时自动切云端视觉模型补位。',
    detail:
      '无障碍能读到的控件（元素数 > 3）视为简单页面，优先端侧处理；元素树稀疏（≤ 3）视为复杂页面，自动启用云端视觉模型做截图描述与坐标定位，即使截图开关是关的也会兜底。'
  },
  {
    id: 'chain',
    order: 4,
    tag: '决策',
    title: '多模型链路聚合',
    summary: '主模型决策 + 视觉模型定位 + 思考模型规划，按需组合，默认关闭。',
    detail:
      '默认只跑主模型（加可选视觉模型），省额度、省延迟。开启链路聚合后，思考模型负责复杂规划、歧义检测与重规划，视觉模型只负责截图描述与坐标定位。三者的地址、模型名、密钥都在设置页图形化配置。'
  },
  {
    id: 'channel',
    order: 5,
    tag: '执行',
    title: '四通道执行体系',
    summary: '无线 ADB 为主，Shizuku 可选，Termux 补命令行，无障碍兜底。',
    detail:
      'AUTO 模式下按「无线 ADB → Shizuku → Termux」降级。无线 ADB 连接成功即就绪，可直接执行 shell；Shizuku 失败或超时不阻塞主流程。AI 给的裸包名会被自动补全为 launch action，不会因为少写参数而空转。'
  },
  {
    id: 'shell',
    order: 6,
    tag: '执行',
    title: 'AI 友好命令解析',
    summary: 'tap / sw / key / text 这类短命令，坐标支持像素、比例、百分比三种写法。',
    detail:
      'ShellCommands 把 AI 的自然短命令翻译成真实 ADB 命令，模型不用记 ADB 语法。命令输出会被截取（最多 1200 字）回灌到下一轮决策上下文里，让 AI 看得到自己刚刚做了什么。'
  },
  {
    id: 'safety',
    order: 7,
    tag: '安全',
    title: '安全防护与脱敏',
    summary: '敏感页面自动只读，手机号 / 身份证 / 银行卡号自动遮蔽。',
    detail:
      'EngineRules 作为硬约束规则引擎，在动作真正下发前拦截危险操作；SensitivePageDetector 识别支付、个人信息等页面并切换为只读横切；DataSanitizer 对 11 位手机号、18 位身份证号、银行卡号做脱敏后再进模型上下文。'
  },
  {
    id: 'verify',
    order: 8,
    tag: '执行',
    title: '页面指纹验证',
    summary: '动作执行前后比对控件特征，确认「点下去了」还是「点空了」。',
    detail:
      'VerifiedClickExecutor 在点击前后各取一次页面指纹，不一致才算生效。这一步把「AI 以为点了」和「页面真的变了」区分开，是长线任务不跑偏的关键。'
  },
  {
    id: 'local',
    order: 9,
    tag: '性能',
    title: '端侧快速决策',
    summary: '弹窗、加载、广告这类高频场景本地直接处理，不花云端额度。',
    detail:
      'LocalDecisionEngine 覆盖高频重复场景，命中即本地决策，省掉一次往返。配合 AdSkipperCore 的冷却机制，既不会漏跳也不会反复戳同一个按钮。'
  },
  {
    id: 'adskip',
    order: 10,
    tag: '能力',
    title: '内置跳广告',
    summary: '独立于 Agent 运行：青少年模式弹窗 > 跳过按钮 > 倒计时角标 > 关闭按钮。',
    detail:
      'AdSkipperCore 按优先级识别四类广告形态，配合精确匹配、位置约束与控件类型约束降低误触，内置冷却防止重复点击死循环。'
  },
  {
    id: 'overlay',
    order: 11,
    tag: '交互',
    title: '液态玻璃悬浮窗',
    summary: '半透明白玻璃，宽 300dp，高度随内容自适应，跑马灯贴屏幕顶边。',
    detail:
      '执行时只占「跑马灯 + 标题 + 状态」三行，AI 详情默认折叠，展开可看「发送给 AI / AI 返回 / 审核结论」三段原文。截图时自动隐藏、截完立刻恢复，全过程 ≤ 0.3 秒。任务完成显示打勾动画并停在屏幕上，不自动消失。'
  },
  {
    id: 'takeover',
    order: 12,
    tag: '交互',
    title: '随时接管与纠偏',
    summary: 'AI 拿不准会停下来问，你也能随时插话改方向。',
    detail:
      '遇到歧义时悬浮窗弹出澄清选项；执行中可随时输入补充指令，会被追加进任务记忆的「用户要求」而不是覆盖原始目标。「已手动处理」按钮让人工接管后任务继续，而不是直接退出。'
  },
  {
    id: 'memory',
    order: 13,
    tag: '记忆',
    title: '任务记忆',
    summary: '记住目标、用户要求、已验证方法与进度，长线任务不丢上下文。',
    detail:
      '任务记忆独立持久化，按 taskId 归属判定，避免旧任务的收尾逻辑误标新任务。用户中途的每一条补充要求按精确去重后追加保留，不会因为语义相近被合并掉。'
  },
  {
    id: 'queue',
    order: 14,
    tag: '任务',
    title: '多任务队列与检查点',
    summary: '顺序执行多个任务，随时取消、重规划，模板库沉淀可复用流程。',
    detail:
      'TaskStore 保存检查点与任务模板库；TemplateMatcher 命中模板时直接复用验证过的路径。模板必须由软件预置或经用户明确确认后才入库，AI 不能自行创建模板。'
  },
  {
    id: 'skill',
    order: 15,
    tag: '扩展',
    title: '技能与 MCP',
    summary: '内置技能目录 + 远程 MCP 工具，参数校验与失败原因都是中文。',
    detail:
      'SkillCatalog 与 IntentType.ALL 一一对应，被禁用的技能在归一化阶段直接拒绝。MCP 技能调用 20 秒超时、输出截断 1200 字，连续 3 次被拒即停止任务，避免无限循环。'
  },
  {
    id: 'doc',
    order: 16,
    tag: '输出',
    title: '文档结果直出',
    summary: '需要写文档的任务直接产出结果页，不在屏幕上假装打字。',
    detail:
      'write_doc 意图由 DocumentEngine 落盘到应用私有目录，并推给 Agent 页内嵌 Markdown 预览（可展开 / 收起 / 关闭）。提示词里有铁律约束：文档类任务必须直接输出 write_doc，不许打开备忘录打字或用 shell 写文件。'
  },
  {
    id: 'i18n',
    order: 17,
    tag: '体验',
    title: '中英双语与自动翻译',
    summary: '提示词可手动切换中英；AI 的英文输出自动翻成中文再展示。',
    detail:
      'AI 回复若以非中文为主（中文字符占比低于 30%）会自动翻译后再展示，翻译失败则回退原文。翻译结果带缓存去重，不会为同一句话重复付费。'
  },
  {
    id: 'design',
    order: 18,
    tag: '体验',
    title: 'Material 3 Expressive',
    summary: '玄青 · 流萤配色，深色模式、高对比度无障碍、边缘光效全适配。',
    detail:
      '语义颜色令牌集中管理，浅色 / 深色 / 高对比度四套主题；入场动画统一为自下而上淡入位移，全局触觉反馈，动效曲线统一收敛在 Motion 模块。'
  }
]

// ---------- 6. 文档 ----------

const docs = [
  {
    slug: 'getting-started',
    title: '快速开始',
    group: '入门',
    order: 1,
    summary: '从下载安装到跑通第一个任务。',
    body: `## 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 8.0（API 26）及以上 |
| 架构 | arm64-v8a / armeabi-v7a |
| 网络 | 需要能访问你配置的模型服务 |
| 可选 | Shizuku（高权限执行）、Termux（命令行取数） |

## 安装

1. 到 [下载页](/download) 取最新 APK，直接安装（Debug 包需允许「安装未知来源应用」）。
2. 打开应用，按首页的**权限雷达**逐项授权。

## 必须授予的权限

- **无障碍服务** —— 读取屏幕控件并执行点击、滑动、输入。这是 Agent 的手和眼，不授予则完全无法工作。
- **悬浮窗** —— 显示实时进度与接管面板。可在设置里关掉，关掉后任务照常执行，只是看不到浮窗。
- **通知权限** —— 前台服务与进度通知。
- **屏幕录制**（可选） —— 仅当需要云端视觉模型截图时使用。

## 配置模型

进入**设置 → 模型配置**，填写：

- **API 地址**：任意 OpenAI 兼容端点，例如 \`http://192.168.1.5:8000/v1\`、\`http://localhost:11434/v1\`。不写协议会自动补全：内网地址、localhost、单段主机名补 \`http://\`，公网域名补 \`https://\`。
- **API Key**
- **模型名**

填完点**测试连接**，通过后才允许保存——避免把错误配置写进 DataStore 之后再花时间排查。

## 跑通第一个任务

回到首页，在输入框里写一句人话，比如「打开设置，把屏幕亮度调到一半」。点击开始后：

1. 悬浮窗出现，跑马灯滚动显示当前步骤。
2. Agent 会先规划 3~8 步，然后逐步执行。
3. 遇到需要确认的操作，浮窗会停下来等你点。

如果某一步卡住，直接在浮窗输入框里补充说明即可，任务不会中断。`
  },
  {
    slug: 'model-config',
    title: '模型配置',
    group: '入门',
    order: 2,
    summary: '主模型、视觉模型、思考模型各自负责什么，以及怎么填。',
    body: `## 三类模型

| 类型 | 负责 | 是否必需 | 默认 |
| --- | --- | --- | --- |
| 主模型 | 决策、规划、意图理解 | 必需 | — |
| 视觉模型 | 截图描述、坐标定位 | 可选 | glm-4.6v-flash |
| 思考模型 | 复杂规划、歧义检测、重规划 | 可选 | 回退主模型密钥 |

主模型必须支持结构化输出（JSON）。视觉模型必须支持图像输入——主模型不处理图片。

## 链路聚合开关

**默认关闭**。关闭时只跑「主模型 + 可选视觉模型」，速度快、额度省。

开启后：

- 简单页面（元素树可读）仍然跳过云端视觉，直接用元素树决策；
- 复杂页面（元素树稀疏）交给视觉模型描述与定位；
- 规划与重规划阶段交给思考模型，主模型只负责单步决策。

三者都可在设置页图形化配置（地址 / 模型名 / 密钥）。思考模型的密钥留空时自动回退主模型密钥。

## API 地址写法

支持 http://、内网地址与 localhost，不写协议时自动补全：

\`\`\`text
192.168.1.5:8000/v1        → http://192.168.1.5:8000/v1
localhost:11434/v1         → http://localhost:11434/v1
myserver:8080/v1           → http://myserver:8080/v1
api.example.com/v1         → https://api.example.com/v1
\`\`\`

应用已放行明文流量（\`usesCleartextTraffic\`），内网 http:// 服务可以直接用，不必套反向代理加 TLS。

## 保存规则

**只有测试连接通过才允许保存。** 这条规则是硬性的：模型配置一旦写错，后续所有任务都会以「AI 返回无法解析」的形式失败，排查成本远高于当场拦住。`
  },
  {
    slug: 'execution-channels',
    title: '执行通道',
    group: '进阶',
    order: 3,
    summary: '无线 ADB、Shizuku、Termux、无障碍四者的能力边界与选择建议。',
    body: `## 四条通道，能力完全不同

| 通道 | 权限级别 | 能做什么 |
| --- | --- | --- |
| 无障碍 | 普通应用权限 | 读控件树、点击、滑动、输入文字 |
| 无线 ADB | shell（adb） | \`am\` / \`pm\` / \`settings\` 等系统命令 |
| Shizuku | shell（adb） | 同上，通过 Shizuku 授权获得 |
| Termux | 普通应用权限 | curl / python / 文本处理，**不能**执行系统命令 |

## 默认策略：无线 ADB 为主

设置里的执行通道是三态偏好：\`AUTO\` / \`ADB\` / \`SHIZUKU\`（Termux 作为补充通道参与 AUTO 降级）。

\`AUTO\` 下的顺序是：**无线 ADB → Shizuku → Termux → 无障碍**。

无线 ADB 是主通道的理由很实际：Android 11+ 自带无线调试，配对一次即可长期使用，拿到的是货真价实的 shell 权限，且不需要额外安装任何应用。Shizuku 退居可选增强——拉起失败或超时都不会阻塞主流程。

## 为什么启动应用要走 shell

Agent 启动目标应用一律用 \`launch\`（底层是 \`monkey -p <包名> 1\`），而不是回到桌面点图标。原因有两条：

1. 桌面图标的位置和数量随时会变，坐标点击不稳定；
2. 点图标会引入「桌面是不是在前台」这个额外状态判断。

AI 若只给了裸包名（例如 \`com.tencent.mm\`），端侧会自动补全为完整的 launch 动作。

## Termux 的前置条件

三项缺一不可：

1. 已安装 Termux；
2. 已授予 \`com.termux.permission.RUN_COMMAND\`；
3. Termux 侧 \`allow-external-apps=true\`。

结果回传走 **PendingIntent**，不走结果目录文件——后者在 \`allow-external-apps\` 未开启时会永久挂起。`
  },
  {
    slug: 'intent-layer',
    title: '意图转译层',
    group: '进阶',
    order: 4,
    summary: '为什么 AI 永远不输出坐标和命令，以及 15 个语义意图是怎么落地的。',
    body: `## 设计前提

让语言模型直接输出坐标和 shell 命令，会同时引入两类问题：

- **不稳定**：同一个控件在不同分辨率、不同主题下的坐标不同；
- **不可控**：模型可以输出任意命令，安全边界形同虚设。

所以本项目把 AI 的输出面收窄成 15 个**高层语义意图**，由端侧 IntentTranslator 实时转译成已有的 ActionType。

## 15 个语义意图

\`\`\`text
back  home  refresh  search  send  confirm  close
share  collect  copy  delete  download  add  switch  clear_input
\`\`\`

每个意图对应一个策略类（SemanticActionStrategy / BackStrategy / HomeStrategy），本地执行，零网络往返。

## 定位优先级

1. **id / label** —— 无障碍元素树里有精确标识时优先使用，执行层自动算坐标；
2. **scroll_to** —— 当前屏幕找不到目标控件时先滚动查找；
3. **coordinate** —— 只有图片、图表这类元素树里读不到的控件才允许用坐标；
4. **abort** —— 以上都不成立就中止，并说明当前前台应用与缺失的控件名。

报错信息里必须带**当前前台应用**，而不是笼统的「无法定位控件」——否则 AI 下一轮仍然不知道该做什么。

## 一步一个动作

同一轮只做一个明确动作，不叠加小动作，同一控件不反复操作。规划阶段的步数动态收敛到 3~8 步，宁可少而准。`
  },
  {
    slug: 'safety',
    title: '安全与隐私',
    group: '进阶',
    order: 5,
    summary: '哪些页面只读、哪些数据会被脱敏、哪些操作必须你点头。',
    body: `## 敏感页面只读

SensitivePageDetector 识别支付、个人信息、密码等敏感页面，命中后由 EngineRules 横切为**只读**：Agent 可以看、可以描述，但不能提交。

## 数据脱敏

进模型上下文之前，以下数据会被遮蔽：

| 类型 | 规则 |
| --- | --- |
| 手机号 | 11 位连续数字 |
| 身份证号 | 18 位（含末位 X） |
| 银行卡号 | 长位数字串 |

脱敏发生在构造提示词的环节，不是事后过滤——被遮蔽的内容从未离开过设备。

## 硬约束规则引擎

EngineRules 在动作真正下发**之前**拦截：

- 敏感页上的写操作；
- 结构化错误的命令（未知命令、空命令、参数非法）——直接跳过 3 次无效重试并立即失败，同时把错误原因与可用命令列表注入下一轮决策上下文。

## 不确定时的行为

系统提示词里明确禁止「不确定就输出 task_done」。模型必须：

1. 先尝试可行解法；
2. 卡住时输出 \`abort\` 并说明卡点；
3. **不得编造不存在的命令或动作。**

## 完成任务的门槛

AI 只有在**当前页面上亲眼看到任务完成的明确证据**时才允许输出 \`task_done\`，且总结里必须写清证据是什么。执行层还会做二次把关：至少执行 3 步、且达到规划步数的 60%，才接受完成判定；连续 3 次过早完成才强制收尾，防止死循环。`
  },
  {
    slug: 'architecture',
    title: '架构总览',
    group: '进阶',
    order: 6,
    summary: '感知、决策、执行、视觉、持久化、展示六层怎么协作。',
    body: `## 执行循环

\`\`\`text
观察屏幕 → 页面标注 → 端侧/云端决策 → 双通道执行 → 带验证 → 记录 → 循环
\`\`\`

1. **观察** —— 无障碍服务取回屏幕全部可交互元素。
2. **标注** —— PageAnnotator 为 26 类常见控件分配语义 ID（\`dlg_allow\`、\`ad_skip\`、\`search_box\`、\`send_btn\` …），直接注入 AI 上下文，让模型能按 id 选控件。
3. **决策** —— LocalDecisionEngine 先处理高频场景，其余交给模型。
4. **执行** —— 通道降级执行，动作前后做页面指纹比对。
5. **记录** —— 落盘供多轮对话与诊断导出使用。

## 分层

\`\`\`text
core/       跨层基础设施（AI 客户端、脱敏、通知、人话翻译）
domain/     纯领域模型与规则（动作、意图、规则引擎、端侧决策）
data/       持久化（DataStore 配置 + 五处 Store）
device/     设备能力（无障碍、shell、截图、视觉）
engine/     Agent 编排（ReAct 主循环、转译层、感知、提示词）
overlay/    悬浮窗
feature/    功能域（任务、技能、MCP、文档、跳广告、边缘光效、测试）
ui/         界面层（页面 = 入口 + 单一职责拆分文件）
\`\`\`

## 持久化

| 存储 | 内容 |
| --- | --- |
| AppSettings | DataStore 配置（模型、通道、语言、开关） |
| MemoryStore | 任务记忆（目标 / 用户要求 / 已验证方法 / 状态） |
| TaskStore | 检查点 + 任务模板库 |
| DebugRecordsStore | 日志 / 轨迹 / 历史 / 对话 |
| McpStore | MCP 服务与技能配置 |
| PromptTemplateStore | 提示词模板 |

## 上下文卫生

每次 \`run()\` 开头会彻底清空 AI 上下文：对话消息、失败计数、无效命令计数、上一条 shell 输出、上一页截图、上一任务计划、工作记忆。任务之间不串味。`
  },
  {
    slug: 'troubleshooting',
    title: '疑难排查',
    group: '支持',
    order: 7,
    summary: '连不上模型、点了没反应、浮窗不出现、任务老是中止。',
    body: `## 测试连接失败

- **地址不完整** —— 现在会自动补协议。内网写 \`192.168.1.5:8000/v1\` 即可。
- **明文被拦** —— 应用已放行 \`usesCleartextTraffic\`，若仍失败请检查路由器是否隔离了客户端。
- **模型名不对** —— 部分服务要求带前缀，例如 \`openai/gpt-4o\`。
- **不支持结构化输出** —— 主模型必须能返回 JSON，纯对话模型会持续报解析失败。

## 点了没反应

先看调试页的**步骤**面板，那里会写清每一步的判定结果：

- 「控件不在当前元素树」—— 说明 AI 想点的东西不在前台页面上，报错会带上当前前台应用名；
- 「点击未生效」—— 页面指纹前后一致，说明点空了，Agent 会重试或改用其它定位方式。

## 悬浮窗不出现

1. 检查**显示在其他应用上层**权限是否授予；
2. 检查设置里悬浮窗开关是否被关掉（关掉后任务仍会执行，只是没有浮窗）；
3. 部分厂商系统需要在「后台弹出界面」里单独放行。

## 任务反复中止

常见原因与对策：

| 现象 | 原因 | 对策 |
| --- | --- | --- |
| 连续 3 次结构化错误 | AI 用了不存在的命令 | 检查主模型能力，或换更强的模型 |
| 找不到控件后中止 | 页面没加载完 / 需要滚动 | 在浮窗里补充说明，或改用更具体的入口描述 |
| 过早判定完成 | 页面证据不足 | 已由执行层拦截；仍出现请导出诊断报告提 Issue |

## 导出诊断

**调试页 → 导出**，会打包日志、步骤轨迹、历史对话与能力状态。提 Issue 时附上它，比描述现象有用得多。`
  }
]

// ---------- 7. FAQ ----------

const faq = [
  {
    id: 'need-root',
    order: 1,
    group: '使用',
    q: '需要 Root 吗？',
    a: '不需要。基础能力靠无障碍服务即可运行。想要系统级命令（am / pm / settings）时，用 Android 11+ 自带的无线调试配对，或安装 Shizuku——两者都不需要 Root。'
  },
  {
    id: 'which-model',
    order: 2,
    group: '使用',
    q: '必须用哪家的模型？',
    a: '任意 OpenAI 兼容端点都可以，包括自建的 Ollama、LM Studio、one-api。主模型需要支持结构化输出；如果要开视觉能力，另配一个支持图像输入的模型（默认 glm-4.6v-flash）。'
  },
  {
    id: 'privacy',
    order: 3,
    group: '安全',
    q: '我的屏幕内容会上传到哪？',
    a: '只发给你自己配置的模型服务。手机号、身份证号、银行卡号在构造提示词时就已脱敏，被遮蔽的内容从未离开设备。支付等敏感页面会被自动切换为只读。'
  },
  {
    id: 'free',
    order: 4,
    group: '使用',
    q: '收费吗？',
    a: '应用本身基于 MIT 协议开源，不收费。你只需要为自己使用的模型服务付费（如果用云端 API）。'
  },
  {
    id: 'apk-source',
    order: 5,
    group: '下载',
    q: '下载页的 APK 是什么构建？',
    a: '当前提供的是 Debug 构建，用于内测与自用，需要手动授予权限。每个版本的 SHA-256 校验值都写在下载页上，安装前可自行核对。'
  },
  {
    id: 'install-fail',
    order: 6,
    group: '下载',
    q: '安装提示「应用未安装」怎么办？',
    a: '多数情况是签名冲突：先卸载旧版本再装。也可能是下载不完整——对照下载页的 SHA-256 校验一下文件。'
  },
  {
    id: 'not-working',
    order: 7,
    group: '故障',
    q: '任务总是执行一半就停。',
    a: '先看调试页的步骤面板，那里会给出每一步的判定原因（控件不在当前页面、点击未生效等）。最常见的原因是页面还没加载完，或者描述的目标不够具体。在悬浮窗里补充一句说明通常就能继续。'
  },
  {
    id: 'overlay-missing',
    order: 8,
    group: '故障',
    q: '悬浮窗不显示。',
    a: '检查三处：是否授予「显示在其他应用上层」权限；设置里悬浮窗开关是否被关掉；厂商系统是否需要在「后台弹出界面」单独放行。关掉悬浮窗不影响任务执行，只是看不到实时进度。'
  },
  {
    id: 'contribute',
    order: 9,
    group: '开发',
    q: '怎么参与开发？',
    a: '项目在 GitHub 与 Gitee 同步开源。Fork 后开特性分支，提交信息遵循 Conventional Commits，UI 变更需符合 Material 3 标准，然后发 PR。'
  },
  {
    id: 'api-update',
    order: 10,
    group: '开发',
    q: '能自动更新官网内容吗？',
    a: '可以。官网提供完整的 REST API，包括版本发布、APK 上传、CHANGELOG 导入与文案更新，全部可用脚本或 CI 调用。见「官网内容 API」文档。'
  }
]

// ---------- 落盘 ----------

console.log('\n写入官网数据：')
writeIfNeeded('site.json', site)
writeIfNeeded('features.json', features)

// releases.json 只在「首次生成」和「带 --apk 刷新绑定」两种情况下写。
// 后者用 upsert 而不是覆盖：后台里手工加过的版本记录不能被一次种子跑掉。
const releasesFile = path.join(dataDir, 'releases.json')
if (withApk && fs.existsSync(releasesFile)) {
  const existing = JSON.parse(fs.readFileSync(releasesFile, 'utf8'))
  const target = normalizeVersion(currentVersion)
  const index = existing.findIndex((r) => normalizeVersion(r.version) === target)
  if (index >= 0) existing[index] = { ...existing[index], ...releases[0] }
  else existing.push(releases[0])
  fs.writeFileSync(releasesFile, `${JSON.stringify(existing, null, 2)}\n`, 'utf8')
  console.log(`  ✓ releases.json（${target} 的 APK 绑定已刷新）`)
} else {
  writeIfNeeded('releases.json', releases)
}

writeIfNeeded('changelog.json', changelog)
writeIfNeeded('docs.json', docs)
writeIfNeeded('faq.json', faq)

const statsFile = path.join(dataDir, 'stats.json')
if (!fs.existsSync(statsFile)) {
  fs.writeFileSync(
    statsFile,
    `${JSON.stringify({ totalDownloads: 0, byVersion: {}, byDay: {}, lastDownloadAt: null }, null, 2)}\n`,
    'utf8'
  )
  console.log('  ✓ stats.json')
}

console.log('\n完成。执行 npm run dev 启动开发环境，或 npm start 启动生产服务。\n')
