import {
  generatedAt,
  releaseSource,
  site as siteData,
  features,
  releases,
  changelog,
  docs,
  faq,
  scenarios,
  roadmap,
  stats as statsSnapshot,
  agentDocs,
  routes as routeTable
} from './data.generated.js'

/**
 * Happy Phone Agent 官网 API —— Cloudflare Worker 版。
 *
 * 静态页面在 GitHub Pages，Worker 只接管 `hpa.linshuxu.us.kg/api/*`。
 * 与 Express 版（website/server）保持同一组契约，因为已经装出去的 App 写死了这些地址：
 *
 *   GET  /api/update/check             客户端检查更新
 *   GET  /api/agent-docs/index.json    执行文档索引（ETag / 304）
 *   GET  /api/agent-docs/doc/:id.md    执行文档正文（Markdown）
 *   POST /api/feedback                 客户端脱敏执行数据
 *   GET  /api/download/*               只做 302，到 GitHub / Gitee 的 Release
 *
 * 官网读接口（/api/site、/api/bootstrap 等）也在这里实现，内容来自构建期数据；
 * 管理后台与站内 AI 问答没有搬到 Worker：前者要写文件，后者要保管模型密钥，
 * 都不适合放在无状态的边缘函数里，页面会按「未启用」处理。
 */

const CHANNEL_ORDER = ['stable', 'beta', 'dev', 'nightly']

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' }
const NO_STORE = { 'cache-control': 'no-store' }
const DOC_CACHE = { 'cache-control': 'public, max-age=30, s-maxage=60, stale-while-revalidate=60' }

const MAX_FEEDBACK_BYTES = 64 * 1024
const MAX_STEPS = 120
const MAX_ACTIONS = 16
const RATE_LIMIT_PER_HOUR = 120
const RATE_LIMIT_PER_DAY = 600

const GITEE_API = 'https://gitee.com/api/v5'
const GITEE_TTL = 5 * 60 * 1000
const GITEE_TIMEOUT = 4000
const SOURCES_WAIT = 800

// ---------- 响应工具 ----------

function json(body, { status = 200, headers = {} } = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...JSON_HEADERS, ...headers }
  })
}

function fail(status, code, message, extra = {}) {
  return json({ error: code, message, ...extra }, { status })
}

function redirect(location, headers = {}) {
  return new Response(null, {
    status: 302,
    headers: { ...NO_STORE, location, ...headers }
  })
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ---------- 版本 ----------

const normalizeVersion = (input) => {
  const v = String(input || '').trim()
  if (!v) return ''
  return /^v/i.test(v) ? v : `v${v}`
}

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

const buildOf = (release) => {
  if (release && Number.isFinite(Number(release.build))) return Number(release.build)
  const parts = normalizeVersion(release?.version).replace(/^v/i, '').split('-')[0].split('.')
  const last = Number(parts[parts.length - 1])
  return Number.isFinite(last) ? last : 0
}

const normalizeChannel = (input) => {
  const v = String(input || '').trim().toLowerCase()
  return CHANNEL_ORDER.includes(v) ? v : 'stable'
}

const visibleChannels = (clientChannel) =>
  CHANNEL_ORDER.slice(0, CHANNEL_ORDER.indexOf(normalizeChannel(clientChannel)) + 1)

/** 构建期已按发布时间倒序，这里只做过滤，不再重排 */
function listReleases(channel = 'all') {
  return channel && channel !== 'all' ? releases.filter((r) => r.channel === channel) : releases
}

function findRelease(version) {
  const target = normalizeVersion(version)
  return releases.find((r) => normalizeVersion(r.version) === target) || null
}

/** 与 Express 的 downloadTarget 一致：优先最新稳定版，没有就退最新测试包 */
function downloadTarget() {
  const stable = listReleases('stable')[0]
  if (stable) return { release: stable, stable: true }
  return { release: releases[0] || null, stable: false }
}

function latestVisible(clientChannel) {
  const channels = visibleChannels(clientChannel)
  return releases.find((r) => channels.includes(normalizeChannel(r.channel))) || releases[0] || null
}

/** 频道可见（客户端不该收到自己屏蔽的渠道）+ 确实更新（版本号或构建号更高） */
function pickUpdate({ clientChannel = 'stable', version = '', build = 0 } = {}) {
  const channels = visibleChannels(clientChannel)
  const currentBuild = Number(build) || 0
  const currentVersion = normalizeVersion(version)
  const candidates = releases
    .filter((r) => channels.includes(normalizeChannel(r.channel)))
    .filter((r) => {
      const diff = compareVersion(r.version, currentVersion)
      if (diff > 0) return true
      if (diff < 0) return false
      return buildOf(r) > currentBuild
    })
    .sort((a, b) => {
      const diff = compareVersion(a.version, b.version)
      return diff !== 0 ? diff : buildOf(a) - buildOf(b)
    })
  return candidates.pop() || null
}

// ---------- 数据加工 ----------

const siteLinks = siteData?.links || {}

function repoOf(url, host) {
  const m = String(url || '').match(new RegExp(`${host}/([^/]+)/([^/?#]+)`))
  return m ? `${m[1]}/${m[2]}` : ''
}

const githubRepo = repoOf(siteLinks.github, 'github\\.com')
const giteeRepo = repoOf(siteLinks.gitee, 'gitee\\.com')

const githubAsset = (release) => {
  const file = release?.apk?.file
  const version = String(release?.version || '').trim()
  if (!githubRepo || !file || !version) return null
  const tag = /^v/i.test(version) ? version : `v${version}`
  return {
    id: 'github',
    label: 'GitHub Release',
    tag,
    asset: file,
    url: `https://github.com/${githubRepo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(file)}`
  }
}

/**
 * Gitee 的附件是 CI 逐个 attach 的，可能没传成功，所以要查一次 Release 列表。
 * 列表在 isolate 内缓存 5 分钟，302 路径只读缓存，绝不在这里等接口。
 */
const giteeCache = { list: null, at: 0, inflight: null }

async function giteeReleaseList() {
  if (!giteeRepo) return null
  if (giteeCache.list && Date.now() - giteeCache.at < GITEE_TTL) return giteeCache.list
  if (giteeCache.inflight) return giteeCache.inflight
  giteeCache.inflight = (async () => {
    try {
      const res = await fetch(`${GITEE_API}/repos/${giteeRepo}/releases?page=1&per_page=50`, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(GITEE_TIMEOUT),
        cf: { cacheTtl: 300, cacheEverything: true }
      })
      if (!res.ok) throw new Error(`Gitee 返回 ${res.status}`)
      const list = await res.json()
      giteeCache.list = Array.isArray(list) ? list : []
      giteeCache.at = Date.now()
      return giteeCache.list
    } catch (err) {
      console.warn(`[externalAssets] Gitee 列表刷新失败，沿用上次结果：${err?.message || err}`)
      return giteeCache.list
    } finally {
      giteeCache.inflight = null
    }
  })()
  return giteeCache.inflight
}

const normalizeTag = (value) =>
  String(value || '')
    .trim()
    .replace(/^v/i, '')
    .toLowerCase()

function matchGitee(list, release) {
  const file = release?.apk?.file
  const want = normalizeTag(release?.version)
  if (!file || !want || !Array.isArray(list)) return null

  const hit = list.find((r) => {
    if (!r) return false
    if (normalizeTag(r.tag_name) === want || normalizeTag(r.name) === want) return true
    return (r.assets || []).some((a) => a?.name === file)
  })
  if (!hit) return null

  const assets = (hit.assets || []).filter((a) => a?.browser_download_url)
  const asset = assets.find((a) => a.name === file) || assets.find((a) => /\.apk$/i.test(a.name || ''))
  if (!asset) return null

  return {
    id: 'gitee',
    label: 'Gitee Release',
    tag: hit.tag_name,
    asset: asset.name,
    url: asset.browser_download_url
  }
}

const giteeAsset = (release) => matchGitee(giteeCache.list, release)

/** 302 用的地址：Gitee（已确认附件存在）优先，其次 GitHub 直链 */
function preferredAsset(release) {
  return giteeAsset(release) || githubAsset(release)
}

function releasePage() {
  return siteLinks.releases || siteLinks.github || 'https://github.com'
}

function decorate(release, origin, stats) {
  if (!release) return null
  const file = release.apk?.file
  const downloadUrl = `${origin}/api/download/${encodeURIComponent(release.version)}`
  return {
    ...release,
    apk: file ? { ...release.apk, url: downloadUrl, downloadUrl } : null,
    downloads: stats.byVersion?.[release.version] || 0
  }
}

function summarizeStats(stats) {
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

function feedInfo() {
  return {
    source: releaseSource.source,
    fetchedAt: releaseSource.fetchedAt,
    degraded: releaseSource.degraded,
    repo: releaseSource.repo,
    error: releaseSource.degraded ? releaseSource.error : '',
    unknownChannels: releaseSource.unknownChannels || []
  }
}

function siteMeta(origin, stats) {
  const target = downloadTarget()
  const newest = target.release
  return {
    ...siteData,
    version: newest ? String(newest.version).replace(/^v/i, '') : siteData.version,
    buildNumber: newest ? buildOf(newest) : siteData.buildNumber,
    latest: decorate(newest, origin, stats),
    downloadTarget: newest
      ? { version: newest.version, channel: newest.channel, stable: target.stable }
      : null,
    releaseSource: feedInfo(),
    stats: summarizeStats(stats)
  }
}

// ---------- 下载统计（可选 KV） ----------

const EMPTY_STATS = { totalDownloads: 0, byVersion: {}, byDay: {}, lastDownloadAt: null }

/**
 * KV 里没有记录时用构建期快照（官网上的「累计下载」不会因为没配 KV 变成 0）。
 * cacheTtl 60 秒：KV 边缘缓存能显著减少每次读的延迟，计数晚一分钟无感。
 */
async function resolveStats(env) {
  const base = { ...EMPTY_STATS, ...statsSnapshot }
  if (!env?.STATS_KV) return base
  try {
    const stored = await env.STATS_KV.get('stats', { type: 'json', cacheTtl: 60 })
    return stored ? { ...base, ...stored } : base
  } catch {
    return base
  }
}

/** 下载计数：总量 + 分版本 + 分天，与 server/lib/helpers.js 的 recordDownload 同口径 */
async function recordDownload(env, version) {
  if (!env?.STATS_KV) return
  try {
    const current = (await env.STATS_KV.get('stats', { type: 'json' })) || { ...EMPTY_STATS }
    const stats = { ...EMPTY_STATS, ...current }
    const today = new Date().toISOString().slice(0, 10)
    stats.totalDownloads = (stats.totalDownloads || 0) + 1
    stats.byVersion = { ...(stats.byVersion || {}) }
    stats.byVersion[version] = (stats.byVersion[version] || 0) + 1
    stats.byDay = { ...(stats.byDay || {}) }
    stats.byDay[today] = (stats.byDay[today] || 0) + 1
    stats.lastDownloadAt = new Date().toISOString()
    // 只留最近 90 天，防止 KV 值无限增长
    const days = Object.keys(stats.byDay).sort()
    for (const day of days.slice(0, Math.max(0, days.length - 90))) delete stats.byDay[day]
    await env.STATS_KV.put('stats', JSON.stringify(stats))
  } catch (err) {
    // 计数失败不能影响下载本身
    console.warn(`[stats] 写入失败：${err?.message || err}`)
  }
}

// ---------- 客户端反馈 ----------

const STATUSES = new Set(['done', 'aborted', 'failed', 'running'])
const SEND_RESULTS = new Set(['sent', 'send_failed', 'unknown'])
const VERIFY_RESULTS = new Set(['verified_success', 'unverified', 'failed', 'unknown'])

const rateBuckets = new Map()

const text = (value, max = 64) =>
  typeof value === 'string' ? value.replace(/[^\w.\-]/g, '').slice(0, max) : ''

const integer = (value, min = 0, max = 1_000_000) => {
  const n = Number(value)
  if (!Number.isFinite(n)) return 0
  return Math.min(max, Math.max(min, Math.trunc(n)))
}

const actionName = (value) => text(value, 24).toLowerCase() || 'unknown'
const sendName = (value) => (SEND_RESULTS.has(value) ? value : 'unknown')
const verifyName = (value) => (VERIFY_RESULTS.has(value) ? value : 'unknown')

function allowRequest(ipHash) {
  const now = Date.now()
  const bucket = (rateBuckets.get(ipHash) || []).filter((t) => now - t < 24 * 3600 * 1000)
  const lastHour = bucket.filter((t) => now - t < 3600 * 1000).length
  if (lastHour >= RATE_LIMIT_PER_HOUR || bucket.length >= RATE_LIMIT_PER_DAY) {
    rateBuckets.set(ipHash, bucket)
    return false
  }
  bucket.push(now)
  rateBuckets.set(ipHash, bucket)
  if (rateBuckets.size > 5000) {
    for (const [key, hits] of rateBuckets) {
      if (!hits.length || now - hits[hits.length - 1] > 24 * 3600 * 1000) rateBuckets.delete(key)
    }
  }
  return true
}

async function hashIp(ip, env) {
  const payload = new TextEncoder().encode(`${env?.FEEDBACK_SALT || 'phtomt-feedback'}:${ip || ''}`)
  const digest = await crypto.subtle.digest('SHA-256', payload)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 12)
}

function normalizeSteps(input) {
  if (!Array.isArray(input)) return []
  return input.slice(-MAX_STEPS).map((step) => {
    const item = step && typeof step === 'object' ? step : {}
    return {
      type: actionName(item.type),
      send: sendName(item.send),
      verify: verifyName(item.verify),
      ok: item.ok === true,
      ms: integer(item.ms, 0, 3_600_000)
    }
  })
}

function normalizeCounts(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return {}
  const entries = Object.entries(input)
    .map(([key, value]) => [actionName(key), integer(value, 0, 1_000_000)])
    .filter(([key]) => key !== 'unknown')
    .sort((a, b) => b[1] - a[1])
    .slice(0, MAX_ACTIONS)
  return Object.fromEntries(entries)
}

function normalizeMetrics(input) {
  const src = input && typeof input === 'object' ? input : {}
  return {
    totalTokens: integer(src.totalTokens, 0, 100_000_000),
    promptTokens: integer(src.promptTokens, 0, 100_000_000),
    completionTokens: integer(src.completionTokens, 0, 100_000_000),
    requestCount: integer(src.requestCount),
    avgLatencyMs: integer(src.avgLatencyMs, 0, 3_600_000),
    visionCount: integer(src.visionCount),
    visionTotalMs: integer(src.visionTotalMs, 0, 3_600_000),
    execCount: integer(src.execCount),
    execTotalMs: integer(src.execTotalMs, 0, 3_600_000)
  }
}

/** 按白名单重建记录，客户端发来什么多余字段都不会落盘（同 server/routes/feedback.js） */
function normalizeFeedback(body, ipHash) {
  const app = body.app && typeof body.app === 'object' ? body.app : {}
  const task = body.task && typeof body.task === 'object' ? body.task : {}
  const status = text(task.status, 16).toLowerCase()
  const installId = text(body.installId, 64)
  return {
    id: crypto.randomUUID(),
    receivedAt: new Date().toISOString(),
    schema: integer(body.schema, 1, 100),
    event: 'task_finished',
    installId: installId || `ip-${ipHash}`,
    ipHash,
    app: {
      version: text(app.version, 32),
      build: integer(app.build),
      android: integer(app.android, 0, 100)
    },
    task: {
      status: STATUSES.has(status) ? status : 'unknown',
      durationMs: integer(task.durationMs, 0, 86_400_000),
      steps: integer(task.steps),
      okSteps: integer(task.okSteps),
      sendFailed: integer(task.sendFailed),
      verifyFailed: integer(task.verifyFailed),
      unverified: integer(task.unverified),
      actionCounts: normalizeCounts(task.actionCounts),
      stepTrace: normalizeSteps(task.stepTrace)
    },
    metrics: normalizeMetrics(body.metrics)
  }
}

async function handleFeedback(request, env) {
  if (!env?.STATS_KV) {
    // 宁可明确报错，也不要回 202 然后把记录丢掉
    return fail(503, 'storage_unavailable', '反馈暂存未配置，请先绑定 STATS_KV')
  }

  const length = Number(request.headers.get('content-length') || 0)
  if (length > MAX_FEEDBACK_BYTES) {
    return fail(413, 'payload_too_large', '反馈数据超过 64KB 上限')
  }

  const raw = await request.text()
  if (raw.length > MAX_FEEDBACK_BYTES) {
    return fail(413, 'payload_too_large', '反馈数据超过 64KB 上限')
  }

  let body
  try {
    body = JSON.parse(raw)
  } catch {
    return fail(400, 'invalid_body', '反馈数据必须是 JSON 对象')
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return fail(400, 'invalid_body', '反馈数据必须是 JSON 对象')
  }

  const ip = request.headers.get('cf-connecting-ip') || ''
  const ipHash = await hashIp(ip, env)
  if (!allowRequest(ipHash)) {
    return fail(429, 'rate_limited', '上报过于频繁，请稍后再试')
  }

  const record = normalizeFeedback(body, ipHash)
  try {
    await env.STATS_KV.put(
      `fb:${record.receivedAt.slice(0, 7)}:${record.id}`,
      JSON.stringify(record)
    )
  } catch (err) {
    console.error('[feedback] 写入失败：', err)
    return fail(500, 'storage_failed', '反馈暂存失败，请稍后重试')
  }
  return json({ ok: true, id: record.id }, { status: 202 })
}

// ---------- Agent 执行文档 ----------

const docMap = new Map(agentDocs.docs.map((doc) => [doc.id, doc]))
const docsIndex = {
  version: agentDocs.version,
  generatedAt: agentDocs.generatedAt,
  count: agentDocs.docs.length,
  docs: agentDocs.docs.map(({ body, ...rest }) => rest)
}
const DOC_ID_RE = /^[A-Za-z0-9._-]+$/

/** 客户端存的是去掉引号的 etag，传回来时可能带 W/ 前缀，两种都要认 */
function etagMatches(header, etag) {
  if (!header) return false
  return header
    .split(',')
    .map((s) => s.trim().replace(/^W\//, '').replace(/"/g, ''))
    .some((s) => s === etag)
}

const INDEX_ETAG = `"idx-${agentDocs.version}"`

function agentDocResponse(request, idInput) {
  const id = String(idInput || '').replace(/\.md$/i, '')
  const doc = DOC_ID_RE.test(id) ? docMap.get(id) : null
  if (!doc) return fail(404, 'not_found', `未找到执行文档 ${idInput}`)

  const headers = {
    ...DOC_CACHE,
    'content-type': 'text/markdown; charset=utf-8',
    etag: `"${doc.etag}"`,
    'x-doc-id': doc.id,
    'x-doc-version': doc.version,
    'x-doc-title': encodeURIComponent(doc.title)
  }
  if (etagMatches(request.headers.get('if-none-match'), doc.etag)) {
    return new Response(null, { status: 304, headers })
  }
  return new Response(doc.body, { headers })
}

// ---------- 路由 ----------

const METHOD_NOT_ALLOWED = () =>
  json(
    { error: 'method_not_allowed', message: '该端点只接受特定请求方法' },
    { status: 405, headers: { allow: 'GET, POST' } }
  )

async function handle(request, env, ctx, url) {
  const origin = String(env?.SITE_ORIGIN || '').replace(/\/$/, '') || url.origin
  // 允许带尾斜杠（/api/site/ 与 /api/site 等价）
  const path = url.pathname.length > 1 ? url.pathname.replace(/\/+$/, '') : url.pathname
  const method = request.method

  // ---- 客户端更新 ----

  if (path === '/api/update/check') {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    const channel = normalizeChannel(url.searchParams.get('channel'))
    const version = normalizeVersion(url.searchParams.get('version'))
    const build = Number(url.searchParams.get('build')) || 0

    // 客户端没报版本时（如刚打开设置页手动检查）直接给该频道可见范围内的最新一版
    const target = version
      ? pickUpdate({ clientChannel: channel, version, build })
      : latestVisible(channel)

    const summary = (release) =>
      release
        ? {
            version: release.version,
            build: buildOf(release),
            channel: normalizeChannel(release.channel),
            date: release.date || '',
            title: release.title || '',
            summary: release.summary || '',
            notes: Array.isArray(release.notes) ? release.notes : []
          }
        : null

    const downloadInfo = (release) =>
      release?.apk?.file
        ? {
            url: `${origin}/api/download/${encodeURIComponent(release.version)}`,
            file: release.apk.file,
            size: release.apk.size,
            sizeText: release.apk.sizeText,
            sha256: release.apk.sha256,
            mirror: 'release',
            shareUrl: null,
            password: ''
          }
        : null

    if (!target) {
      return json({
        channel,
        clientVersion: version,
        clientBuild: build,
        hasUpdate: false,
        latest: summary(latestVisible(channel)),
        download: null
      })
    }

    return json({
      channel,
      clientVersion: version,
      clientBuild: build,
      hasUpdate: Boolean(version),
      latest: summary(target),
      download: downloadInfo(target)
    })
  }

  if (path.startsWith('/api/update/download/')) {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    const release = findRelease(decodeURIComponent(path.slice('/api/update/download/'.length)))
    if (!release || release.published === false || !release.apk?.file) {
      return fail(404, 'not_found', '未找到可下载的版本')
    }
    const asset = preferredAsset(release)
    const sha = String(release.apk?.sha256 || '').replace(/[^a-fA-F0-9]/g, '')
    if (asset) ctx.waitUntil(recordDownload(env, release.version))
    return redirect(asset?.url || releasePage(), {
      'x-release-version': release.version,
      'x-update-source': asset?.id || 'releases-page',
      ...(sha ? { 'x-checksum-sha256': sha } : {})
    })
  }

  // ---- 下载 ----

  if (path === '/api/download/latest') {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    const { release } = downloadTarget()
    if (!release) return fail(404, 'not_found', '暂无可下载的版本')
    if (url.searchParams.get('redirect') === '0') {
      return json({ version: release.version, channel: release.channel, file: release.apk.file })
    }
    const asset = preferredAsset(release)
    const sha = String(release.apk?.sha256 || '').replace(/[^a-fA-F0-9]/g, '')
    if (asset) ctx.waitUntil(recordDownload(env, release.version))
    return redirect(asset?.url || releasePage(), {
      'x-release-version': release.version,
      'x-download-source': asset?.id || 'releases-page',
      ...(sha ? { 'x-checksum-sha256': sha } : {})
    })
  }

  if (path.startsWith('/api/download/sources/')) {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    const want = decodeURIComponent(path.slice('/api/download/sources/'.length))
    const release = findRelease(want)
    if (!release || release.published === false) {
      return fail(404, 'not_found', `未找到版本 ${want}`)
    }
    // 首次预热没完成时最多等一小段；302 路径不走这里
    if (!giteeCache.list && giteeRepo) {
      await Promise.race([giteeReleaseList(), sleep(SOURCES_WAIT)])
    }
    const candidates = [giteeAsset(release), githubAsset(release)].filter(Boolean)
    return json(
      {
        version: release.version,
        file: release.apk?.file || '',
        candidates,
        page: releasePage()
      },
      { headers: NO_STORE }
    )
  }

  if (path.startsWith('/api/download/hit/')) {
    if (method !== 'POST') return METHOD_NOT_ALLOWED()
    const release = findRelease(decodeURIComponent(path.slice('/api/download/hit/'.length)))
    if (release) ctx.waitUntil(recordDownload(env, release.version))
    return new Response(null, { status: 204, headers: NO_STORE })
  }

  if (path.startsWith('/api/download/')) {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    const release = findRelease(decodeURIComponent(path.slice('/api/download/'.length)))
    if (!release || release.published === false) {
      return fail(404, 'not_found', '未找到版本')
    }
    const asset = preferredAsset(release)
    const sha = String(release.apk?.sha256 || '').replace(/[^a-fA-F0-9]/g, '')
    if (asset) ctx.waitUntil(recordDownload(env, release.version))
    return redirect(asset?.url || releasePage(), {
      'x-release-version': release.version,
      'x-download-source': asset?.id || 'releases-page',
      ...(sha ? { 'x-checksum-sha256': sha } : {})
    })
  }

  // ---- Agent 执行文档 ----

  if (path === '/api/agent-docs/index.json' || path === '/api/agent-docs/index') {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    const headers = {
      ...DOC_CACHE,
      etag: INDEX_ETAG,
      'x-docs-count': String(docsIndex.count)
    }
    if (etagMatches(request.headers.get('if-none-match'), `idx-${agentDocs.version}`)) {
      return new Response(null, { status: 304, headers })
    }
    return json(docsIndex, { headers })
  }

  if (path.startsWith('/api/agent-docs/doc/')) {
    if (method !== 'GET') return METHOD_NOT_ALLOWED()
    return agentDocResponse(request, decodeURIComponent(path.slice('/api/agent-docs/doc/'.length)))
  }

  // ---- 客户端反馈 ----

  if (path === '/api/feedback') {
    if (method !== 'POST') return METHOD_NOT_ALLOWED()
    return handleFeedback(request, env)
  }

  // ---- 官网读接口 ----

  if (method !== 'GET') return fail(404, 'not_found', `未找到 ${path}`)

  if (path === '/api/health') {
    return json({
      status: 'ok',
      runtime: 'worker',
      time: new Date().toISOString(),
      counts: {
        releases: releases.length,
        changelog: changelog.length,
        docs: docs.length,
        faq: faq.length,
        features: features.length,
        scenarios: scenarios.length
      }
    })
  }

  // 站内 AI 问答没有搬到 Worker（需要保管模型密钥），前端拿到 enabled:false 会隐藏入口
  if (path === '/api/ai/status') {
    return json({
      enabled: false,
      name: '项目助手',
      greeting: '',
      suggestions: [],
      guide: false,
      reason: 'worker_no_ai'
    })
  }

  if (path === '/api/bootstrap') {
    const stats = await resolveStats(env)
    return json({
      site: siteMeta(origin, stats),
      features,
      releases: releases.slice(0, 10).map((r) => decorate(r, origin, stats)),
      changelog: changelog.slice(0, 5),
      faq: faq.slice(0, 6),
      docs: docs.map(({ body, ...rest }) => rest)
    })
  }

  if (path === '/api/site') {
    return json(siteMeta(origin, await resolveStats(env)))
  }

  if (path === '/api/features') {
    return json(features)
  }

  if (path === '/api/releases') {
    const channel = url.searchParams.get('channel') || 'all'
    const limit = url.searchParams.get('limit')
    let list = listReleases(channel)
    if (limit) list = list.slice(0, Math.max(0, Number(limit) || 0))
    const stats = await resolveStats(env)
    return json(list.map((r) => decorate(r, origin, stats)), {
      headers: { 'x-release-source': releaseSource.source }
    })
  }

  if (path === '/api/releases/latest') {
    const release = listReleases(url.searchParams.get('channel') || 'all')[0]
    if (!release) return fail(404, 'not_found', '暂无可下载的版本')
    return json(decorate(release, origin, await resolveStats(env)), {
      headers: { 'x-release-source': releaseSource.source }
    })
  }

  if (path.startsWith('/api/releases/')) {
    const release = findRelease(decodeURIComponent(path.slice('/api/releases/'.length)))
    if (!release || release.published === false) {
      return fail(404, 'not_found', '未找到该版本')
    }
    return json(decorate(release, origin, await resolveStats(env)))
  }

  if (path === '/api/changelog') {
    const limit = url.searchParams.get('limit')
    const total = changelog.length
    if (limit) {
      const items = changelog.slice(0, Number(limit) || 0)
      return json({ total, page: 1, size: items.length, items })
    }
    const page = Math.max(1, Number(url.searchParams.get('page')) || 1)
    const size = Math.max(1, Math.min(100, Number(url.searchParams.get('size')) || 20))
    const start = (page - 1) * size
    return json({ total, page, size, items: changelog.slice(start, start + size) })
  }

  if (path.startsWith('/api/changelog/')) {
    const target = normalizeVersion(decodeURIComponent(path.slice('/api/changelog/'.length)))
    const entry = changelog.find((c) => normalizeVersion(c.version) === target)
    if (!entry) return fail(404, 'not_found', `未找到 ${target} 的更新记录`)
    return json(entry)
  }

  if (path === '/api/docs') {
    return json(docs.map(({ body, ...rest }) => ({ ...rest, hasBody: Boolean(body) })))
  }

  if (path.startsWith('/api/docs/')) {
    const slug = decodeURIComponent(path.slice('/api/docs/'.length))
    const doc = docs.find((d) => d.slug === slug)
    if (!doc) return fail(404, 'not_found', `未找到文档 ${slug}`)
    return json(doc)
  }

  if (path === '/api/faq') {
    const group = url.searchParams.get('group')
    return json(group ? faq.filter((f) => f.group === group) : faq)
  }

  if (path === '/api/scenarios') {
    const category = url.searchParams.get('category')
    return json(category ? scenarios.filter((s) => s.category === category) : scenarios)
  }

  if (path === '/api/roadmap') {
    return json(roadmap)
  }

  if (path === '/api/stats') {
    return json(summarizeStats(await resolveStats(env)))
  }

  if (path === '/api/routes') {
    return json({ routes: routeTable.routes, endpoints: routeTable.endpoints })
  }

  return fail(404, 'not_found', `未找到 ${path}`)
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await handle(request, env, ctx, new URL(request.url))
    } catch (err) {
      console.error('[worker] 未处理异常：', err)
      return fail(500, 'internal_error', err?.message || '服务内部错误')
    }
  }
}

// 构建时间戳只用于排查部署的是哪一版数据
export const buildInfo = { generatedAt }
