import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import express from 'express'
import { config } from '../config.js'
import { requireAdmin } from '../lib/auth.js'

/**
 * 客户端执行数据反馈接收端。
 *
 * 客户端（Happy Phone Agent）在用户主动开启「云端反馈」后，每次任务结束上报一条
 * 脱敏记录。服务端不信任客户端，所有字段按白名单重建：只保留动作类型、成败、
 * 耗时与 token 用量；不接收任务文本、输入内容、截图、包名、设备型号。
 * 访客 IP 只存加盐哈希前缀，既不能还原，也够用来做去重与滥用排查。
 */
export const feedbackRouter = express.Router()

const MAX_BODY_BYTES = 64 * 1024
const MAX_STEPS = 120
const MAX_ACTIONS = 16
const RATE_LIMIT_PER_HOUR = 120
const RATE_LIMIT_PER_DAY = 600

const STATUSES = new Set(['done', 'aborted', 'failed', 'running'])
const SEND_RESULTS = new Set(['sent', 'send_failed', 'unknown'])
const VERIFY_RESULTS = new Set(['verified_success', 'unverified', 'failed', 'unknown'])

const feedbackDir = path.join(config.dataDir, 'feedback')
fs.mkdirSync(feedbackDir, { recursive: true })

const rateBuckets = new Map()

function monthKey(date = new Date()) {
  return date.toISOString().slice(0, 7)
}

function fileOf(month) {
  return path.join(feedbackDir, `${month}.jsonl`)
}

function text(value, max = 64) {
  if (typeof value !== 'string') return ''
  return value.replace(/[^\w.\-]/g, '').slice(0, max)
}

function integer(value, min = 0, max = 1_000_000) {
  const n = Number(value)
  if (!Number.isFinite(n)) return 0
  return Math.min(max, Math.max(min, Math.trunc(n)))
}

function actionName(value) {
  const clean = text(value, 24).toLowerCase()
  return clean || 'unknown'
}

function sendName(value) {
  return SEND_RESULTS.has(value) ? value : 'unknown'
}

function verifyName(value) {
  return VERIFY_RESULTS.has(value) ? value : 'unknown'
}

/** 限流按哈希后的 IP 计，桶内只保留时间戳；过期桶顺手清掉，避免 Map 无限增长 */
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

function hashIp(ip) {
  return crypto
    .createHash('sha256')
    .update(`${config.adminSecret}:${ip || ''}`)
    .digest('hex')
    .slice(0, 12)
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

/** 按白名单重建记录，客户端发来什么多余字段都不会落盘 */
function normalizeRecord(body, ipHash) {
  const app = body.app && typeof body.app === 'object' ? body.app : {}
  const task = body.task && typeof body.task === 'object' ? body.task : {}
  const status = text(task.status, 16).toLowerCase()
  const clientHash = text(body.installId, 64)
  return {
    id: crypto.randomUUID(),
    receivedAt: new Date().toISOString(),
    schema: integer(body.schema, 1, 100),
    event: 'task_finished',
    // 客户端安装 ID 是随机 UUID，不含系统标识；缺失时用 IP 哈希兜底做设备计数
    installId: clientHash || `ip-${ipHash}`,
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

function appendRecord(record) {
  fs.appendFileSync(fileOf(record.receivedAt.slice(0, 7)), `${JSON.stringify(record)}\n`, 'utf8')
}

feedbackRouter.post('/', (req, res) => {
  const length = Number(req.get('content-length') || 0)
  if (length > MAX_BODY_BYTES) {
    return res.status(413).json({ error: 'payload_too_large', message: '反馈数据超过 64KB 上限' })
  }
  const body = req.body
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return res.status(400).json({ error: 'invalid_body', message: '反馈数据必须是 JSON 对象' })
  }
  if (JSON.stringify(body).length > MAX_BODY_BYTES) {
    return res.status(413).json({ error: 'payload_too_large', message: '反馈数据超过 64KB 上限' })
  }

  const ipHash = hashIp(req.ip)
  if (!allowRequest(ipHash)) {
    return res.status(429).json({ error: 'rate_limited', message: '上报过于频繁，请稍后再试' })
  }

  const record = normalizeRecord(body, ipHash)
  try {
    appendRecord(record)
  } catch (err) {
    console.error('[feedback] 写入失败：', err)
    return res.status(500).json({ error: 'storage_failed', message: '反馈暂存失败，请稍后重试' })
  }
  // 回 202：接收端只负责落盘，后续分析离线进行；id 供客户端日志排障
  res.status(202).json({ ok: true, id: record.id })
})

// ---------- 管理端：查看最近反馈 ----------

function recentMonths(count = 2) {
  const months = []
  const now = new Date()
  // 文件名用 receivedAt 的 UTC 月份（monthKey），这里也必须按 UTC 回推，
  // 否则在东八区月初会把上个月算成这个月，读不到刚写入的文件
  for (let i = 0; i < count; i += 1) {
    months.push(monthKey(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1))))
  }
  return months
}

function readRecords(limit) {
  const rows = []
  for (const month of recentMonths()) {
    const file = fileOf(month)
    if (!fs.existsSync(file)) continue
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
    for (let i = lines.length - 1; i >= 0 && rows.length < limit; i -= 1) {
      const line = lines[i].trim()
      if (!line) continue
      try {
        rows.push(JSON.parse(line))
      } catch {
        // 半行写入（进程被杀）直接跳过，不影响其余记录
      }
    }
    if (rows.length >= limit) break
  }
  return rows
}

function statsOf(records) {
  const dayAgo = Date.now() - 24 * 3600 * 1000
  const byStatus = {}
  const byVersion = {}
  let last24h = 0
  for (const item of records) {
    byStatus[item.task?.status || 'unknown'] = (byStatus[item.task?.status || 'unknown'] || 0) + 1
    const version = item.app?.version || 'unknown'
    byVersion[version] = (byVersion[version] || 0) + 1
    if (Date.parse(item.receivedAt) >= dayAgo) last24h += 1
  }
  return {
    total: records.length,
    last24h,
    byStatus,
    byVersion,
    months: recentMonths()
  }
}

export const feedbackAdminRouter = express.Router()

feedbackAdminRouter.get('/', requireAdmin, (req, res) => {
  const limit = integer(req.query.limit || 100, 1, 500)
  const records = readRecords(limit)
  res.json({ records, stats: statsOf(records) })
})

feedbackAdminRouter.delete('/', requireAdmin, (req, res) => {
  const month = text(req.query.month, 7)
  const months = month ? [month] : recentMonths(12)
  let removed = 0
  for (const key of months) {
    const file = fileOf(key)
    if (!fs.existsSync(file)) continue
    removed += fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).length
    fs.rmSync(file, { force: true })
  }
  res.json({ ok: true, removed })
})
