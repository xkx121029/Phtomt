import * as store from './store.js'
import { compareVersion, humanSize } from './helpers.js'

/**
 * 项目知识库：把官网自有内容整理成一段可以直接塞进系统提示词的项目资料。
 *
 * 为什么不无脑全量注入：changelog 70KB + docs 50KB，加上特性/场景/路线图，全部拼起来
 * 是 160KB 量级——每次提问都付这个代价，且长文档里真正相关的那两段会被淹没。
 * 所以分两层：
 *  1. 常驻概览（[overview]）：站点身份、特性标题、FAQ 全文、场景与路线图骨架、版本与下载。
 *     这些是「问到什么都会沾边」的事实，体量可控（约 17KB），每次都带。
 *  2. 按需检索（[chunks] + [pick]）：文档正文、更新日志条目、特性细节、场景步骤、路线图细节。
 *     用提问做一次轻量打分，只把最相关的几段补进去，直到用完预算。
 * 预算由后台配置（contextChars），调到很大就是事实上的「全量注入」。
 *
 * 全是纯函数 + store 读缓存，不额外维护索引文件，也就不存在「索引与内容不一致」这种问题。
 */

/** 单组最多挑几条，避免某一组（比如更新日志有 40 多条）把预算吃光 */
const PICK_LIMIT = { docs: 4, changelog: 6, features: 3, scenarios: 2, roadmap: 2 }

const GROUP_TITLE = {
  docs: '相关文档（按提问匹配）',
  changelog: '相关更新记录（按提问匹配）',
  features: '相关功能细节（按提问匹配）',
  scenarios: '相关场景步骤（按提问匹配）',
  roadmap: '相关路线图细节（按提问匹配）'
}

// ---------- 文本工具 ----------

/**
 * 分词：英文按词、中文按二元组。
 * 中文没有空格，用二元组是最省事又不引依赖的做法——「脱敏」能匹配到「脱敏」，
 * 也能匹配到「敏感」「敏的」这类邻接组合，召回足够，误召由打分排序兜住。
 */
function tokenize(text) {
  const set = new Set()
  const lower = String(text || '').toLowerCase()

  const words = lower.match(/[a-z0-9][a-z0-9._+#-]*/g)
  if (words) for (const w of words) if (w.length >= 2) set.add(w)

  const runs = lower.match(/[\u3400-\u4dbf\u4e00-\u9fff]+/g)
  if (runs) {
    for (const run of runs) {
      if (run.length === 1) set.add(run)
      else for (let i = 0; i < run.length - 1; i += 1) set.add(run.slice(i, i + 2))
    }
  }
  return set
}

function overlap(a, b) {
  const [small, big] = a.size <= b.size ? [a, b] : [b, a]
  let hits = 0
  for (const t of small) if (big.has(t)) hits += 1
  return hits
}

/** 命中数按词量开方归一：否则一篇长文档靠「什么都沾一点」永远排第一 */
function relevance(questionTokens, chunk) {
  const hits = overlap(questionTokens, chunk.tokens)
  if (!hits) return 0
  return (hits * chunk.weight) / Math.sqrt(chunk.tokens.size + 1)
}

/** 按行边界截断，绝不切出半行 */
function cut(text, limit) {
  if (text.length <= limit) return text
  const head = text.slice(0, limit)
  const at = head.lastIndexOf('\n')
  return (at > limit * 0.6 ? head.slice(0, at) : head).trimEnd()
}

function bullets(items) {
  return items.filter(Boolean).join('\n')
}

// ---------- 常驻概览 ----------

function overview(origin) {
  const site = store.read('site')
  const req = site.requirements || {}

  const identity = bullets([
    site.name && `- 项目全名：${site.name}`,
    site.shortName && `- 简称：${site.shortName}`,
    site.tagline && `- 一句话定位：${site.tagline}`,
    site.description && `- 项目简介：${site.description}`,
    site.version && `- 官网页面上展示的当前版本：${site.version}`,
    req.packageName && `- 应用包名：${req.packageName}`,
    req.minAndroid && `- 最低系统：Android ${req.minAndroid}（API ${req.minApi || '?'}）`,
    req.targetApi && `- 目标 API：${req.targetApi}`,
    req.arch && `- 支持的架构：${req.arch}`
  ])

  const links = site.links || {}
  const linksText = bullets([
    links.github && `- 代码仓库（GitHub）：${links.github}`,
    links.gitee && `- 代码仓库（Gitee）：${links.gitee}`,
    links.issues && `- 问题反馈：${links.issues}`,
    links.releases && `- 版本发布页：${links.releases}`,
    links.changelog && `- 完整变更记录：${links.changelog}`,
    origin && `- 官网：${origin}`
  ])

  const stack = Array.isArray(site.techStack) && site.techStack.length
    ? bullets([`- 技术栈：${site.techStack.map((t) => `${t.name}${t.version ? ` ${t.version}` : ''}`).join('、')}`])
    : ''

  const channels = Array.isArray(site.channels) && site.channels.length
    ? [
        '- 执行通道（能力由强到弱，可在应用内切换）：',
        ...site.channels.map(
          (c) => `  · ${c.name}（${c.level || '权限级别未标注'}）${c.default ? '［默认］' : ''}：${c.desc || ''}`
        )
      ].join('\n')
    : ''

  const features = store.read('features')
  const featuresText = features.length
    ? [
        '### 功能特性',
        bullets(
          [...features]
            .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
            .map((f) => `- 【${f.tag || '功能'}】${f.title}：${f.summary || ''}`)
        ),
        '（如需某项功能的实现细节，可再点开官网「功能特性」页或查阅对应文档。）'
      ].join('\n')
    : ''

  const faq = store.read('faq')
  const faqText = faq.length
    ? [
        '### 常见问题（官网 FAQ 原文，优先据此回答）',
        [...faq]
          .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
          .map((f) => `问：${f.q}\n答：${f.a}`)
          .join('\n\n')
      ].join('\n')
    : ''

  const scenarios = store.read('scenarios')
  const scenariosText = scenarios.length
    ? [
        '### 场景示例（官网「场景示例」页有完整分步说明）',
        bullets(
          [...scenarios]
            .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
            .map((s) => `- ［${s.category || '通用'}］${s.title}｜目标：${s.goal || ''}｜所用通道：${s.channel || ''}`)
        )
      ].join('\n')
    : ''

  const roadmap = store.read('roadmap')
  const phases = Array.isArray(roadmap.phases) ? roadmap.phases : []
  const roadmapText = phases.length
    ? [
        `### 路线图（更新于 ${roadmap.updatedAt || '未标注'}${roadmap.note ? `；${roadmap.note}` : ''}）`,
        phases
          .map((p) => {
            const items = (p.items || []).map((i) => `  - ${i.title}`).join('\n')
            return `【${p.title}】${p.summary || ''}${items ? `\n${items}` : ''}`
          })
          .join('\n')
      ].join('\n')
    : ''

  const releases = store.read('releases').filter((r) => r.published !== false)
  const releasesText = releases.length
    ? [
        '### 可下载版本',
        bullets(
          [...releases]
            .sort((a, b) => compareVersion(b.version, a.version))
            .slice(0, 12)
            .map((r) => {
              const size = r.apk?.sizeText || (r.apk?.size ? humanSize(r.apk.size) : '')
              const label = (site.channelLabels || {})[r.channel || 'stable'] || r.channel || 'stable'
              const link = origin ? `｜下载：${origin}/api/download/${encodeURIComponent(r.version)}` : ''
              return `- ${r.version}（${label}，${r.date || '日期未标注'}）${size ? `｜${size}` : ''}${link}`
            })
        )
      ].join('\n')
    : '（暂无可用下载版本。）'

  const stats = store.read('stats')
  const statsText = `### 下载热度\n- 官网统计到的累计下载：${stats.totalDownloads || 0} 次`

  return [
    '## 一、项目身份',
    identity,
    linksText && `### 链接\n${linksText}`,
    stack,
    channels,
    featuresText,
    faqText,
    scenariosText,
    roadmapText,
    '## 二、版本与下载',
    releasesText,
    statsText
  ]
    .filter(Boolean)
    .join('\n\n')
}

// ---------- 检索候选 ----------

function chunks() {
  const out = []

  for (const doc of store.read('docs')) {
    const body = `${doc.summary || ''}\n${doc.body || ''}`.trim()
    if (!body) continue
    out.push({
      group: 'docs',
      weight: 1.1,
      text: `### 文档《${doc.title}》（官网路径 /docs/${doc.slug}）\n${body}`,
      // 标题与摘要权重更高：文档正文往往很长，靠它抢分会让所有长文都挤进来
      tokens: tokenize(`${doc.title} ${doc.title} ${doc.summary || ''} ${body}`)
    })
  }

  for (const entry of store.read('changelog')) {
    const body = (entry.sections || [])
      .map((sec) => `${sec.type || '变更'}：\n${(sec.items || []).map((i) => `- ${i}`).join('\n')}`)
      .join('\n')
    const text = `### 更新日志 ${entry.version}（${entry.date || ''}）\n${entry.summary || ''}\n${body}`.trim()
    out.push({
      group: 'changelog',
      weight: 1,
      text,
      tokens: tokenize(`${entry.version} ${entry.summary || ''} ${body}`)
    })
  }

  for (const f of store.read('features')) {
    if (!f.detail) continue
    out.push({
      group: 'features',
      weight: 0.9,
      text: `### 功能「${f.title}」的实现细节\n${f.detail}`,
      tokens: tokenize(`${f.tag || ''} ${f.title} ${f.summary || ''} ${f.detail}`)
    })
  }

  for (const s of store.read('scenarios')) {
    const steps = (s.steps || []).map((st, i) => `${i + 1}. ${st.title}：${st.detail || ''}`).join('\n')
    if (!steps) continue
    out.push({
      group: 'scenarios',
      weight: 0.9,
      text: `### 场景「${s.title}」的实际步骤\n目标：${s.goal || ''}｜通道：${s.channel || ''}\n${steps}`,
      tokens: tokenize(`${s.category || ''} ${s.title} ${s.goal || ''} ${steps}`)
    })
  }

  const roadmap = store.read('roadmap')
  for (const phase of Array.isArray(roadmap.phases) ? roadmap.phases : []) {
    for (const item of phase.items || []) {
      if (!item.detail) continue
      out.push({
        group: 'roadmap',
        weight: 0.8,
        text: `### 路线图条目「${item.title}」（所属阶段：${phase.title}）\n${item.detail}`,
        tokens: tokenize(`${phase.title} ${item.title} ${item.detail}`)
      })
    }
  }

  return out
}

function pick(candidates, questionTokens, limit) {
  const ranked = candidates
    .map((c) => ({ chunk: c, score: relevance(questionTokens, c) }))
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score)

  const picked = []
  const used = new Map()
  for (const r of ranked) {
    const n = used.get(r.chunk.group) || 0
    if (n >= PICK_LIMIT[r.chunk.group]) continue
    used.set(r.chunk.group, n + 1)
    picked.push(r.chunk)
    if (picked.length >= limit) break
  }
  return picked
}

// ---------- 对外 ----------

/** 知识库规模，给后台显示「AI 到底看得到多少东西」 */
export function indexStats() {
  const site = store.read('site')
  const docs = store.read('docs')
  const changelog = store.read('changelog')
  const features = store.read('features')
  const scenarios = store.read('scenarios')
  const roadmap = store.read('roadmap')
  const faq = store.read('faq')
  const phases = Array.isArray(roadmap.phases) ? roadmap.phases : []

  return {
    site: { bytes: JSON.stringify(site).length, fields: Object.keys(site).length },
    docs: { count: docs.length, chars: docs.reduce((n, d) => n + (d.body || '').length, 0) },
    changelog: { count: changelog.length, chars: JSON.stringify(changelog).length },
    features: { count: features.length, chars: features.reduce((n, f) => n + (f.detail || '').length, 0) },
    scenarios: { count: scenarios.length, chars: JSON.stringify(scenarios).length },
    roadmap: {
      count: phases.reduce((n, p) => n + (p.items || []).length, 0),
      chars: JSON.stringify(roadmap).length
    },
    faq: { count: faq.length, chars: JSON.stringify(faq).length }
  }
}

/**
 * 组装一次提问要注入的项目资料。
 *
 * @param {object} options
 * @param {string} options.origin 站点对外地址，用于拼下载链接
 * @param {string} options.question 本次提问，用于检索
 * @param {number} options.contextChars 总预算（字符）
 */
export function buildKnowledge({ origin = '', question = '', contextChars = 24000 } = {}) {
  const budget = Math.min(120000, Math.max(2000, Number(contextChars) || 24000))
  const head = overview(origin)

  // 概览本身就超预算：说明预算设得太小，如实截断并写清楚，而不是悄悄丢东西
  if (head.length >= budget) {
    return {
      text: `${cut(head, budget - 60)}\n\n（项目资料超出本次注入预算，已截断。可在后台调大「知识注入预算」。）`,
      truncated: true,
      sources: [],
      chars: budget
    }
  }

  let text = head
  const sources = []

  const questionTokens = tokenize(question)
  if (questionTokens.size) {
    const all = chunks()
    const grouped = new Map()
    for (const c of all) {
      if (!grouped.has(c.group)) grouped.set(c.group, [])
      grouped.get(c.group).push(c)
    }
    const shortlist = []
    for (const list of grouped.values()) shortlist.push(...pick(list, questionTokens, PICK_LIMIT.docs))

    const ranked = shortlist
      .map((c) => ({ chunk: c, score: relevance(questionTokens, c) }))
      .sort((a, b) => b.score - a.score)

    const byGroup = new Map()
    for (const r of ranked) {
      const n = byGroup.get(r.chunk.group) || 0
      if (n >= PICK_LIMIT[r.chunk.group]) continue
      const remain = budget - text.length - 40
      if (remain < 400) break
      if (r.chunk.text.length > remain) {
        // 只对长文档做截断；短条目塞不下就直接跳过，免得留下半截话
        if (r.chunk.text.length < 1200) continue
        text += `\n\n${GROUP_TITLE[r.chunk.group]}\n${cut(r.chunk.text, remain)}`
      } else {
        text += `\n\n${GROUP_TITLE[r.chunk.group]}\n${r.chunk.text}`
      }
      byGroup.set(r.chunk.group, n + 1)
      sources.push({ group: r.chunk.group, title: r.chunk.text.split('\n')[0].replace(/^#+\s*/, '') })
    }
  }

  return { text, truncated: false, sources, chars: text.length, budget }
}
