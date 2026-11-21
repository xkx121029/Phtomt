#!/usr/bin/env node
/**
 * 把 server/data 里的内容烘成前端快照，供静态导出（GitHub Pages）使用。
 *
 * 静态站没有后端，所以：
 *  - 所有内容来自这份快照；
 *  - 安装包不在仓库里，快照只保留版本记录中的资产名/体积/校验值，
 *    下载页直接指向 GitHub / Gitee 的 Release 页。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PUBLIC_ENDPOINTS, SITE_ROUTES, ADMIN_ENDPOINTS } from '../server/lib/endpoints.js'
import { refresh as refreshReleases } from '../server/lib/releaseFeed.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const dataDir = path.join(root, 'server', 'data')
const outFile = path.join(root, 'src', 'data', 'fallback.js')

const readJSON = (name, fallback) => {
  const file = path.join(dataDir, `${name}.json`)
  if (!fs.existsSync(file)) return fallback
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    console.warn(`  ! ${name}.json 解析失败，用空值兜底`)
    return fallback
  }
}

// 剥掉 `-beta` / `-dev` 预发布后缀再逐段比较，理由同 server/lib/helpers.js 的 compareVersion。
const compareVersion = (a, b) => {
  const segments = (v) => String(v).replace(/^v/i, '').split('-')[0].split('.').map(Number)
  const pa = segments(a)
  const pb = segments(b)
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const d = (pa[i] || 0) - (pb[i] || 0)
    if (d) return d
  }
  return 0
}

/**
 * 「哪个是最新」按发布时间倒序，不按版本号数字大小。
 *
 * 这个项目的号段回拨过一次（`v0.2.1278` → `v0.2.91xx` → 试过 `v0.2.20000xx` 又退回），
 * 纯数字比较会把已停用的号段永远顶在最前面：`v0.2.2000095` 按数字大于 `v0.2.9197`。
 * 判据与 `server/lib/releaseFeed.js` 的 sortByTime 完全一致——静态导出和实时服务必须
 * 用同一把尺子，否则同一个仓库会产出两个「最新版本」。
 */
const sortByTime = (a, b) => {
  const ta = Date.parse(a.publishedAt || '') || 0
  const tb = Date.parse(b.publishedAt || '') || 0
  if (tb !== ta) return tb - ta
  return compareVersion(b.version, a.version)
}

const site = readJSON('site', {})
const features = readJSON('features', [])
const changelog = readJSON('changelog', [])
const docs = readJSON('docs', [])
const faq = readJSON('faq', [])
const scenarios = readJSON('scenarios', [])
const roadmap = readJSON('roadmap', { updatedAt: null, note: '', phases: [] })
const stats = readJSON('stats', { totalDownloads: 0, byVersion: {}, byDay: {} })

// 版本记录以 GitHub Releases 为准（`server/lib/releaseFeed.js` 里既有的约定：
// 「本地 releases.json 只在拉不到时兜底」）。静态导出以前直接烘本地 JSON，
// 一旦这份缓存漏同步，官网就把两个月前的包当「最新」发出去——正是这次的事故。
// 拉不到（离线、GitHub 限额）才回退本地，构建不会因此失败。
// 注意 server/data/releases.json 是**手工维护**的数据，可能与真实 git tag 不一致
// （实测含 v0.2.2000096 / v0.2.9214 / v0.2.9201 三条在 git tag 里找不到的记录），
// 它只用来在拉不到 GitHub 时兜底，不要拿它判断「哪个版本更新」。
let releasesRaw = null
let releaseSource = 'local'
try {
  const feed = await refreshReleases({ force: true })
  if (feed.source === 'github' && feed.list.length) {
    releasesRaw = feed.list
    releaseSource = 'github'
  }
} catch {
  /* 交给下面的本地兜底 */
}
if (!releasesRaw) releasesRaw = readJSON('releases', [])

const releases = releasesRaw
  .filter((r) => r.published !== false)
  .map((r) => ({
    ...r,
    // 只保留登记信息（资产名/体积/校验值）；实体在 GitHub / Gitee 的 Release 里
    apk: r.apk?.file ? { ...r.apk } : null,
    downloads: stats.byVersion?.[r.version] || 0
  }))
  .sort(sortByTime)

const byDay = stats.byDay || {}
const recent = Object.keys(byDay)
  .sort()
  .slice(-14)
  .map((day) => ({ day, count: byDay[day] }))

// 安装包不在本站，不再汇总「本站占用」——那组数字早已没有对应的磁盘
const summary = {
  totalDownloads: stats.totalDownloads || 0,
  lastDownloadAt: stats.lastDownloadAt || null,
  byVersion: stats.byVersion || {},
  recent
}

// 站点版本号从版本记录派生，不在 site.json 里手写——手写那份会停在旧号段。
const siteWithLatest = {
  ...site,
  version: releases[0] ? String(releases[0].version).replace(/^v/i, '') : site.version,
  buildNumber: releases[0]?.build ?? site.buildNumber,
  latest: releases[0] || null,
  stats: summary
}

// 路由与端点直接复用服务端的唯一来源，静态站的 API 参考页才不会与实现漂移
const routes = {
  routes: SITE_ROUTES,
  endpoints: [...PUBLIC_ENDPOINTS, ...ADMIN_ENDPOINTS]
}


const snapshot = {
  builtAt: new Date().toISOString(),
  static: true,
  site: siteWithLatest,
  features,
  releases,
  changelog: [...changelog].sort((a, b) => compareVersion(b.version, a.version)),
  docs: [...docs].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
  faq: [...faq].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
  scenarios: [...scenarios].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
  roadmap,
  routes,
  bootstrap: {
    site: siteWithLatest,
    features,
    releases: releases.slice(0, 10),
    changelog: [...changelog].sort((a, b) => compareVersion(b.version, a.version)).slice(0, 5),
    faq: [...faq].slice(0, 6),
    docs: [...docs].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).map(({ body, ...rest }) => rest)
  }
}

fs.mkdirSync(path.dirname(outFile), { recursive: true })
fs.writeFileSync(
  outFile,
  `/* 由 scripts/snapshot.mjs 生成于 ${snapshot.builtAt}，请勿手工编辑。\n   静态导出（GitHub Pages）用这份快照替代后端 API。 */\nexport const snapshot = ${JSON.stringify(snapshot, null, 2)}\n`,
  'utf8'
)

console.log(`  ✓ 快照已写入 src/data/fallback.js`)
console.log(`    版本来源 ${releaseSource === 'github' ? 'GitHub Releases' : '本地 releases.json 兜底'} · 最新 ${releases[0]?.version || '—'}`)
console.log(`    版本 ${releases.length} · 更新日志 ${changelog.length} · 文档 ${docs.length} · FAQ ${faq.length} · 场景 ${scenarios.length}`)
