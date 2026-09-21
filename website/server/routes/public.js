import { Router } from 'express'
import * as store from '../lib/store.js'
import {
  compareVersion,
  findRelease,
  humanSize,
  latestRelease,
  normalizeVersion,
  apkInfo
} from '../lib/helpers.js'
import { originOf } from '../config.js'
import { PUBLIC_ENDPOINTS, SITE_ROUTES, ADMIN_ENDPOINTS } from '../lib/endpoints.js'

export { SITE_ROUTES, PUBLIC_ENDPOINTS }

export const publicRouter = Router()

/** 给版本对象补上「绝对下载地址 / 展示用大小」等派生字段，前端不必自己拼 */
function decorate(req, release) {
  if (!release) return null
  const apk = release.apk && release.apk.file ? apkInfo(release.apk.file) : null
  const origin = originOf(req)
  const stats = store.read('stats')
  return {
    ...release,
    apk: apk
      ? {
          ...apk,
          url: `${origin}/apk/${encodeURIComponent(apk.file)}`,
          downloadUrl: `${origin}/api/download/${encodeURIComponent(release.version)}`
        }
      : null,
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
      features: store.read('features').length
    }
  })
})

publicRouter.get('/site', (req, res) => {
  const site = store.read('site')
  res.json({
    ...site,
    latest: publicRelease(req, latestRelease()),
    stats: summarizeStats()
  })
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

publicRouter.get('/stats', (_req, res) => {
  res.json(summarizeStats())
})

/**
 * 首屏聚合接口：SPA 启动只需要一次往返就能拿到站点元信息、特性、最新版本、统计与 FAQ 摘要。
 */
publicRouter.get('/bootstrap', (req, res) => {
  const site = store.read('site')
  res.json({
    site: { ...site, latest: publicRelease(req, latestRelease()), stats: summarizeStats() },
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
  })
})

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
  const releases = store.read('releases')
  const apkBytes = releases.reduce((sum, r) => {
    const info = r.apk?.file ? apkInfo(r.apk.file) : null
    return sum + (info?.size || 0)
  }, 0)
  return {
    totalDownloads: stats.totalDownloads || 0,
    lastDownloadAt: stats.lastDownloadAt || null,
    byVersion: stats.byVersion || {},
    recent,
    apkTotalSize: apkBytes,
    apkTotalSizeText: humanSize(apkBytes)
  }
}

