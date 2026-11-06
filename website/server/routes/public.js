import { Router } from 'express'
import * as store from '../lib/store.js'
import {
  compareVersion,
  findRelease,
  latestRelease,
  normalizeVersion
} from '../lib/helpers.js'
import { originOf } from '../config.js'
import { PUBLIC_ENDPOINTS, SITE_ROUTES, ADMIN_ENDPOINTS } from '../lib/endpoints.js'

export { SITE_ROUTES, PUBLIC_ENDPOINTS }

export const publicRouter = Router()

/**
 * 给版本对象补上下载入口。安装包本身不在本站：`/api/download/:version` 只做一次
 * 302（Gitee / GitHub Release），大小与校验值直接用版本记录里已发布的数据，
 * 不再读磁盘——服务器上没有 APK 文件可读。
 */
function decorate(req, release) {
  if (!release) return null
  const origin = originOf(req)
  const stats = store.read('stats')
  const file = release.apk?.file
  const downloadUrl = `${origin}/api/download/${encodeURIComponent(release.version)}`
  return {
    ...release,
    apk: file ? { ...release.apk, url: downloadUrl, downloadUrl } : null,
    downloads: stats.byVersion?.[release.version] || 0
  }
}

function publicRelease(req, release) {
  if (!release || release.published === false) return null
  return decorate(req, release)
}

publicRouter.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    uptime: Math.round(process.uptime()),
    time: new Date().toISOString(),
    counts: {
      releases: store.read('releases').length,
      changelog: store.read('changelog').length,
      docs: store.read('docs').length,
      faq: store.read('faq').length,
      features: store.read('features').length,
      scenarios: store.read('scenarios').length
    }
  })
})

/**
 * 站点元信息里的「最新版本」。
 *
 * 规则与 bootstrap.releases 完全一致：全渠道、已发布、有包体，版本号最大的一条。
 * 原来这里取的是「最新稳定版」，于是首页（site.latest）和下载页（releases[0]）
 * 指向两个不同版本；version 也一直沿用 site.json 里的手写旧值。这里统一由 release
 * 数据推导，`site.json` 的 version 只在一条 release 都没有时兜底。
 */
function siteMeta(req) {
  const raw = store.read('site')
  const newest = latestRelease({ channel: 'all' })
  return {
    ...raw,
    version: newest ? String(newest.version).replace(/^v/i, '') : raw.version,
    latest: publicRelease(req, newest),
    stats: summarizeStats()
  }
}

publicRouter.get('/site', (req, res) => {
  res.json(siteMeta(req))
})

publicRouter.get('/features', (_req, res) => {
  const list = store.read('features')
  res.json([...list].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)))
})

publicRouter.get('/releases', (req, res) => {
  const { channel = 'all', limit } = req.query
  let list = store
    .read('releases')
    .filter((r) => r.published !== false)
    .filter((r) => (channel === 'all' ? true : (r.channel || 'stable') === channel))
    .sort((a, b) => compareVersion(b.version, a.version))
  if (limit) list = list.slice(0, Math.max(0, Number(limit) || 0))
  res.json(list.map((r) => decorate(req, r)))
})

publicRouter.get('/releases/latest', (req, res) => {
  const release = latestRelease({ channel: req.query.channel || 'stable' })
  if (!release) {
    return res.status(404).json({ error: 'not_found', message: '暂无可下载的版本' })
  }
  res.json(decorate(req, release))
})

publicRouter.get('/releases/:version', (req, res) => {
  const release = publicRelease(req, findRelease(req.params.version))
  if (!release) {
    return res.status(404).json({ error: 'not_found', message: `未找到版本 ${req.params.version}` })
  }
  res.json(release)
})

publicRouter.get('/changelog', (req, res) => {
  const { limit, page = '1', size = '20' } = req.query
  let list = store.read('changelog').slice()
  list.sort((a, b) => compareVersion(b.version, a.version))
  const total = list.length
  if (limit) {
    list = list.slice(0, Number(limit) || 0)
    return res.json({ total, page: 1, size: list.length, items: list })
  }
  const pageNum = Math.max(1, Number(page) || 1)
  const pageSize = Math.max(1, Math.min(100, Number(size) || 20))
  const start = (pageNum - 1) * pageSize
  res.json({ total, page: pageNum, size: pageSize, items: list.slice(start, start + pageSize) })
})

publicRouter.get('/changelog/:version', (req, res) => {
  const target = normalizeVersion(req.params.version)
  const entry = store.read('changelog').find((c) => normalizeVersion(c.version) === target)
  if (!entry) {
    return res.status(404).json({ error: 'not_found', message: `未找到 ${target} 的更新记录` })
  }
  res.json(entry)
})

publicRouter.get('/docs', (_req, res) => {
  const list = store.read('docs')
  res.json(
    [...list]
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
      .map(({ body, ...rest }) => ({ ...rest, hasBody: Boolean(body) }))
  )
})

publicRouter.get('/docs/:slug', (req, res) => {
  const doc = store.read('docs').find((d) => d.slug === req.params.slug)
  if (!doc) {
    return res.status(404).json({ error: 'not_found', message: `未找到文档 ${req.params.slug}` })
  }
  res.json(doc)
})

publicRouter.get('/faq', (req, res) => {
  const list = store.read('faq')
  const { group } = req.query
  res.json(
    [...list]
      .filter((f) => (group ? f.group === group : true))
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
  )
})

publicRouter.get('/scenarios', (req, res) => {
  const list = store.read('scenarios')
  const { category } = req.query
  res.json(
    [...list]
      .filter((s) => (category ? s.category === category : true))
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
  )
})

publicRouter.get('/roadmap', (_req, res) => {
  const roadmap = store.read('roadmap')
  // 阶段顺序由数据里的 phases 数组决定，服务端不重排——「暂不计划」放最后是有意的
  res.json(roadmap)
})

publicRouter.get('/stats', (_req, res) => {
  res.json(summarizeStats())
})

/**
 * 首屏聚合接口：SPA 启动只需要一次往返就能拿到站点元信息、特性、最新版本、统计与 FAQ 摘要。
 */
publicRouter.get('/bootstrap', (req, res) => {
  res.json(buildBootstrap(req))
})

/**
 * 首屏聚合数据的唯一构造点。
 *
 * 公开端点 /api/bootstrap 与「把内容烘进 HTML 外壳」两条路共用它——两处若各写一份，
 * 迟早漂移出「接口有、首屏无」这类只有用户能发现的差异。
 */
export function buildBootstrap(req) {
  return {
    site: siteMeta(req),
    features: [...store.read('features')].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
    releases: store
      .read('releases')
      .filter((r) => r.published !== false)
      .sort((a, b) => compareVersion(b.version, a.version))
      .slice(0, 10)
      .map((r) => decorate(req, r)),
    changelog: [...store.read('changelog')]
      .sort((a, b) => compareVersion(b.version, a.version))
      .slice(0, 5),
    faq: [...store.read('faq')].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).slice(0, 6),
    docs: [...store.read('docs')]
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
      .map(({ body, ...rest }) => rest)
  }
}

/**
 * 站点路由表：前端路由与后端端点都在这里声明一次，
 * 后台「API 参考」页与 sitemap 都从这里读，避免文档和实现两头维护。
 */
publicRouter.get('/routes', (_req, res) => {
  res.json({ routes: SITE_ROUTES, endpoints: [...PUBLIC_ENDPOINTS, ...ADMIN_ENDPOINTS] })
})

function summarizeStats() {
  const stats = store.read('stats')
  const byDay = stats.byDay || {}
  const recent = Object.keys(byDay)
    .sort()
    .slice(-14)
    .map((day) => ({ day, count: byDay[day] }))
  return {
    totalDownloads: stats.totalDownloads || 0,
    lastDownloadAt: stats.lastDownloadAt || null,
    byVersion: stats.byVersion || {},
    recent
  }
}

