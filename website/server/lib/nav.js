import { SITE_ROUTES } from './endpoints.js'
import * as store from './store.js'

/**
 * 站内引导协议。
 *
 * AI 想带用户去某个站内页面时，在回答里单独占一行写：
 *   [[go:/download|下载最新版]]
 * 前端解析到完整标记就**立刻执行路由跳转**——不需要用户再点一次。
 * 「直接引导」是这套设计的前提：AI 说带你去，页面就真的过去了，
 * 标记本身会被抹掉，用户看不到这行「代码」。
 *
 * 标记语法与前端 src/lib/navMark.js 是同一套约定，改一处必须同时改另一处。
 * 页面清单则只在这里定义：以 SITE_ROUTES 为唯一来源，避免「AI 被教会了一个不存在的路径」。
 */

export const NAV_OPEN = '[[go:'
export const NAV_CLOSE = ']]'

/** 不能作为引导目标：/admin 是给自己用的（访客没令牌），/ask 是当前这个助手本身 */
const SKIP = new Set(['/admin', '/ask'])

/** 每次提问最多带路几次。AI 连环跳会把用户彻底带晕 */
export const NAV_MAX_PER_TURN = 1

/** 静态页面清单（不含动态段的路由） */
function staticPages() {
  return SITE_ROUTES.filter((r) => !SKIP.has(r.path) && !r.path.includes(':'))
}

/**
 * 可引导的目标全集。
 *
 * 动态路由要给出**真实存在的** slug 与版本号，否则 AI 只能编一个出来，
 * 编出来的路径前端校验过不了，表现就是「AI 说了要带你去却哪儿也没去」。
 */
export function navCatalog() {
  const docs = store
    .read('docs')
    .filter((d) => d.slug)
    .map((d) => ({ slug: d.slug, title: d.title }))

  const versions = store
    .read('releases')
    .filter((r) => r.published !== false && r.version)
    .map((r) => r.version)

  return { pages: staticPages(), docs, versions }
}

/** 渲染成给模型看的那一段。放在「回答要求」之后，读起来是一项能力而不是一份资料 */
export function navPrompt(catalog = navCatalog()) {
  const { pages, docs, versions } = catalog

  return [
    '### 带路能力（这是你区别于普通问答的地方）',
    '除了回答问题，你还可以直接把用户带到官网的对应页面。可去的地方只有这些：',
    staticPages.length ? pages.map((p) => `- ${p.path}｜${p.title}`).join('\n') : '',
    docs.length ? `- /docs/<slug>｜单篇文档。可用 slug：${docs.map((d) => d.slug).join('、')}` : '',
    versions.length
      ? `- /changelog/<version>｜某版本详情。可用版本号：${versions.slice(0, 12).join('、')}`
      : '',
    '',
    '用法：',
    `- 需要带用户去看某个页面时，在回答里**单独占一行**写 ${NAV_OPEN}<路径>|<去处名>${NAV_CLOSE}`,
    `  例如 ${NAV_OPEN}/download|下载最新版${NAV_CLOSE}。「|」后是这次去处的名字，2~6 个字。`,
    '- 标记会被识别并**立即跳转**，用户看不到它、也不需要点任何按钮。',
    '- 所以正文要顺着说（「这就带你去下载页，那一页可以挑通道」），**不要**说「点击下方」',
    '  「点这里」「请自行打开」，也不要用 Markdown 链接去替代标记——链接不会跳转。',
    `- 一次回答最多写 ${NAV_MAX_PER_TURN} 个标记。只在确实该带用户离开当前页面时才用，纯答疑不要用。`,
    '- 标记必须完整、必须独立成行。写完照常把回答讲完，不要因为跳转就戛然而止。'
  ]
    .filter(Boolean)
    .join('\n')
}

/** 从回答里扫出所有引导标记。同一个正则每次新建，避免 lastIndex 状态残留 */
export function extractNavs(text) {
  const re = new RegExp(`\\[\\[go:\\s*([^|\\]\\s]+)\\s*\\|\\s*([^\\]]{0,24})\\s*\\]\\]`, 'g')
  const out = []
  let hit
  while ((hit = re.exec(String(text || '')))) {
    out.push({ to: hit[1], label: hit[2] || '' })
  }
  return out
}
