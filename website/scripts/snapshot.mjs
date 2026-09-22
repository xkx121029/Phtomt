#!/usr/bin/env node
/**
 * 把 server/data 里的内容烘成前端快照，供静态导出（GitHub Pages）使用。
 *
 * 静态站没有后端，所以：
 *  - 所有内容来自这份快照；
 *  - APK 只在文件确实存在于 public/apk 时才写进快照，否则标记为无包，
 *    下载页会退回 GitHub Releases 链接，而不是给出一个必然 404 的地址。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PUBLIC_ENDPOINTS, SITE_ROUTES, ADMIN_ENDPOINTS } from '../server/lib/endpoints.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const dataDir = path.join(root, 'server', 'data')
const apkDir = path.join(root, 'public', 'apk')
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

const compareVersion = (a, b) => {
  const pa = String(a).replace(/^v/i, '').split('.').map(Number)
  const pb = String(b).replace(/^v/i, '').split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const d = (pa[i] || 0) - (pb[i] || 0)
    if (d) return d
  }
  return 0
}

const humanSize = (bytes) => {
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes
  let u = 0
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024
    u += 1
  }
  return `${v >= 10 || u === 0 ? Math.round(v) : v.toFixed(1)} ${units[u]}`
}

const site = readJSON('site', {})
const features = readJSON('features', [])
const releasesRaw = readJSON('releases', [])
const changelog = readJSON('changelog', [])
const docs = readJSON('docs', [])
const faq = readJSON('faq', [])
const scenarios = readJSON('scenarios', [])
const roadmap = readJSON('roadmap', { updatedAt: null, note: '', phases: [] })
const stats = readJSON('stats', { totalDownloads: 0, byVersion: {}, byDay: {} })

let bundledApk = 0
const releases = releasesRaw
  .filter((r) => r.published !== false)
  .map((r) => {
    const file = r.apk?.file
    const onDisk = file && fs.existsSync(path.join(apkDir, file))
    if (onDisk) bundledApk += 1
    return {
      ...r,
      apk: onDisk
        ? {
            file,
            size: fs.statSync(path.join(apkDir, file)).size,
            sizeText: humanSize(fs.statSync(path.join(apkDir, file)).size),
            sha256: r.apk.sha256 || ''
          }
        : null,
      downloads: stats.byVersion?.[r.version] || 0
    }
  })
  .sort((a, b) => compareVersion(b.version, a.version))

const byDay = stats.byDay || {}
const recent = Object.keys(byDay)
  .sort()
  .slice(-14)
  .map((day) => ({ day, count: byDay[day] }))

const apkBytes = releases.reduce((sum, r) => sum + (r.apk?.size || 0), 0)
const summary = {
  totalDownloads: stats.totalDownloads || 0,
  lastDownloadAt: stats.lastDownloadAt || null,
  byVersion: stats.byVersion || {},
  recent,
  apkTotalSize: apkBytes,
  apkTotalSizeText: humanSize(apkBytes)
}

const siteWithLatest = { ...site, latest: releases[0] || null, stats: summary }

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
console.log(`    版本 ${releases.length} · 更新日志 ${changelog.length} · 文档 ${docs.length} · FAQ ${faq.length} · 场景 ${scenarios.length} · 打包 APK ${bundledApk}`)
if (!bundledApk) {
  console.log('    （public/apk 下没有 APK，静态站下载按钮会指向 GitHub Releases）')
}
