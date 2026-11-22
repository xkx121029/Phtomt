#!/usr/bin/env node
/**
 * 把 website/server 里的内容与版本数据烘成 Worker 可直接 import 的 ESM 模块。
 *
 * Worker 运行时没有文件系统，也不该在每次请求里打 GitHub / Gitee 接口，
 * 所以内容在构建期固化成 `src/data.generated.js`：
 *
 *   - 版本列表复用 `server/lib/releaseFeed.js`（GitHub Releases 为准，拉不到回退本地），
 *     判据与 Express / 静态快照完全一致；
 *   - Agent 执行文档复用 `server/lib/agentDocs.js`，front-matter、ETag 只有一套解析；
 *   - 路由与端点清单复用 `server/lib/endpoints.js`，避免文档和实现两头维护。
 *
 * 运行：node scripts/build-data.mjs（在 website/worker 下，或 npm run build）
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { refresh as refreshReleases } from '../../server/lib/releaseFeed.js'
import { listDocs, getDoc } from '../../server/lib/agentDocs.js'
import { PUBLIC_ENDPOINTS, SITE_ROUTES, ADMIN_ENDPOINTS } from '../../server/lib/endpoints.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const workerRoot = path.resolve(here, '..')
const siteRoot = path.resolve(workerRoot, '..')
const dataDir = path.join(siteRoot, 'server', 'data')
const outFile = path.join(workerRoot, 'src', 'data.generated.js')

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

// 与 server/lib/helpers.js 的 compareVersion 保持一致：先剥掉 -beta / -dev 再逐段比数字
const compareVersion = (a, b) => {
  const segments = (v) =>
    String(v)
      .replace(/^v/i, '')
      .split('-')[0]
      .split('.')
      .map((n) => Number(n) || 0)
  const pa = segments(a)
  const pb = segments(b)
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const diff = (pa[i] || 0) - (pb[i] || 0)
    if (diff) return diff
  }
  return 0
}

const byOrder = (a, b) => (a.order ?? 0) - (b.order ?? 0)

const site = readJSON('site', {})
const features = readJSON('features', []).slice().sort(byOrder)
const changelog = readJSON('changelog', []).slice().sort((a, b) => compareVersion(b.version, a.version))
const docs = readJSON('docs', []).slice().sort(byOrder)
const faq = readJSON('faq', []).slice().sort(byOrder)
const scenarios = readJSON('scenarios', []).slice().sort(byOrder)
const roadmap = readJSON('roadmap', { updatedAt: null, note: '', phases: [] })
const stats = readJSON('stats', { totalDownloads: 0, byVersion: {}, byDay: {}, lastDownloadAt: null })

// 版本：GitHub 拿不到时 refresh() 内部已回退本地 releases.json，这里不做二次判断
const feed = await refreshReleases({ force: true })
const releases = feed.list
  .filter((r) => r.published !== false && r.apk && r.apk.file)
  .map((r) => ({ ...r, apk: { ...r.apk }, notes: Array.isArray(r.notes) ? r.notes : [] }))

// Agent 执行文档：索引 + 正文一起烘进来，Worker 里只读内存
const docIndex = listDocs()
const agentDocs = {
  version: docIndex.version,
  generatedAt: docIndex.generatedAt,
  docs: docIndex.docs.map((item) => ({ ...item, body: getDoc(item.id)?.body || '' }))
}

const endpoints = {
  routes: SITE_ROUTES,
  endpoints: [...PUBLIC_ENDPOINTS, ...ADMIN_ENDPOINTS]
}

const releaseSource = {
  source: feed.source,
  fetchedAt: feed.fetchedAt,
  degraded: feed.degraded,
  repo: feed.repo,
  error: feed.degraded ? feed.error : '',
  unknownChannels: feed.unknownChannels || []
}

const generatedAt = new Date().toISOString()
const banner = `/* 由 worker/scripts/build-data.mjs 生成于 ${generatedAt}，请勿手工编辑。
   数据源：website/server/data + GitHub Releases；改内容后重跑 npm run build。 */\n`

const emit = (name, value) => `export const ${name} = ${JSON.stringify(value)}\n`

const source =
  banner +
  emit('generatedAt', generatedAt) +
  emit('releaseSource', releaseSource) +
  emit('site', site) +
  emit('features', features) +
  emit('releases', releases) +
  emit('changelog', changelog) +
  emit('docs', docs) +
  emit('faq', faq) +
  emit('scenarios', scenarios) +
  emit('roadmap', roadmap) +
  emit('stats', stats) +
  emit('agentDocs', agentDocs) +
  emit('routes', endpoints)

fs.mkdirSync(path.dirname(outFile), { recursive: true })
fs.writeFileSync(outFile, source, 'utf8')

const kb = (n) => `${(n / 1024).toFixed(0)} KB`
console.log(`  ✓ Worker 数据已写入 ${path.relative(siteRoot, outFile)}（${kb(Buffer.byteLength(source))}）`)
console.log(
  `    版本来源 ${releaseSource.source === 'github' ? 'GitHub Releases' : '本地 releases.json 兜底'} · 最新 ${releases[0]?.version || '—'}`
)
console.log(
  `    版本 ${releases.length} · 更新日志 ${changelog.length} · 文档 ${docs.length} · FAQ ${faq.length} · 场景 ${scenarios.length} · 执行文档 ${agentDocs.docs.length}`
)
