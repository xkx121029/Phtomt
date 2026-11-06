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

/**
 * 版本数据以 GitHub Releases 为准（见 lib/releaseFeed.js），
 * `releases.json` 只在拉不到时兜底；这两个函数保留旧名字，避免各处调用点跟着改。
 */
export { findRelease, latestRelease } from './releaseFeed.js'

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
