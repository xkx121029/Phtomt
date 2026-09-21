import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { config } from '../config.js'
import * as store from './store.js'

export function fail(res, status, code, message, extra = {}) {
  return res.status(status).json({ error: code, message, ...extra })
}

export function humanSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

export function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

/** APK 文件名白名单：只允许站点自己写进磁盘的名字，杜绝路径穿越 */
export function apkPath(fileName) {
  const safe = path.basename(String(fileName || ''))
  if (!/^[A-Za-z0-9._-]+\.apk$/i.test(safe)) return null
  const full = path.join(config.apkDir, safe)
  if (!full.startsWith(config.apkDir)) return null
  return full
}

export function apkInfo(fileName) {
  const full = apkPath(fileName)
  if (!full || !fs.existsSync(full)) return null
  const stat = fs.statSync(full)
  return {
    file: path.basename(full),
    size: stat.size,
    sizeText: humanSize(stat.size),
    sha256: sha256File(full),
    updatedAt: stat.mtime.toISOString()
  }
}

export function listApkFiles() {
  if (!fs.existsSync(config.apkDir)) return []
  return fs
    .readdirSync(config.apkDir)
    .filter((f) => /\.apk$/i.test(f))
    .map((f) => {
      const stat = fs.statSync(path.join(config.apkDir, f))
      return { file: f, size: stat.size, sizeText: humanSize(stat.size), updatedAt: stat.mtime.toISOString() }
    })
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

/** 版本号排序：0.1.371 > 0.1.9（逐段数值比较，不做字符串比较） */
export function compareVersion(a, b) {
  const pa = String(a).replace(/^v/i, '').split('.').map((n) => Number(n) || 0)
  const pb = String(b).replace(/^v/i, '').split('.').map((n) => Number(n) || 0)
  const len = Math.max(pa.length, pb.length)
  for (let i = 0; i < len; i += 1) {
    const diff = (pa[i] || 0) - (pb[i] || 0)
    if (diff !== 0) return diff
  }
  return 0
}

export function normalizeVersion(input) {
  const v = String(input || '').trim()
  if (!v) return ''
  return v.startsWith('v') ? v : `v${v}`
}

export function findRelease(version) {
  const target = normalizeVersion(version)
  const releases = store.read('releases')
  return releases.find((r) => normalizeVersion(r.version) === target) || null
}

export function latestRelease({ channel = 'stable', includeUnpublished = false } = {}) {
  const releases = store.read('releases')
  return (
    releases
      .filter((r) => (includeUnpublished ? true : r.published !== false))
      .filter((r) => (channel && channel !== 'all' ? (r.channel || 'stable') === channel : true))
      .filter((r) => r.apk && r.apk.file)
      .sort((a, b) => compareVersion(a.version, b.version))
      .pop() || null
  )
}

/** 操作留痕：后台所有写操作都会落一条，便于回溯「谁在什么时候改了内容」 */
export function audit(req, action, target, detail = '') {
  store.update('audit', (list) => {
    const next = Array.isArray(list) ? list.slice() : []
    next.unshift({
      at: new Date().toISOString(),
      action,
      target,
      detail,
      ip: req.ip || req.socket?.remoteAddress || ''
    })
    return next.slice(0, 300)
  })
}

/** 下载计数：总量 + 分版本 + 分天，用于官网展示真实热度 */
export function recordDownload(version) {
  const today = new Date().toISOString().slice(0, 10)
  return store.update('stats', (s) => {
    const stats = { totalDownloads: 0, byVersion: {}, byDay: {}, lastDownloadAt: null, ...s }
    stats.totalDownloads += 1
    stats.byVersion[version] = (stats.byVersion[version] || 0) + 1
    stats.byDay[today] = (stats.byDay[today] || 0) + 1
    stats.lastDownloadAt = new Date().toISOString()
    // 只留最近 90 天，防止文件无限膨胀
    const days = Object.keys(stats.byDay).sort()
    for (const day of days.slice(0, Math.max(0, days.length - 90))) delete stats.byDay[day]
    return stats
  })
}

/** 统一把请求体里的 id 集合规整为数组，兼容 { items: [...] } 与裸数组两种提交形态 */
export function bodyItems(body) {
  if (Array.isArray(body)) return body
  if (body && Array.isArray(body.items)) return body.items
  return null
}
