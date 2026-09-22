import { isStaticBuild } from '../api/client.js'
import { channelLabels, sectionTones } from './siteMeta.js'

export function formatDate(input) {
  if (!input) return '—'
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return String(input)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
    date.getDate()
  ).padStart(2, '0')}`
}

export function relativeTime(input) {
  if (!input) return ''
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return ''
  const diff = Date.now() - date.getTime()
  const day = 86400000
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < day) return `${Math.floor(diff / 3600000)} 小时前`
  if (diff < day * 30) return `${Math.floor(diff / day)} 天前`
  return formatDate(input)
}

export function compactNumber(value) {
  const n = Number(value) || 0
  if (n < 1000) return String(n)
  if (n < 10000) return `${(n / 1000).toFixed(1)}k`
  return `${Math.round(n / 1000)}k`
}

/**
 * 下载地址解析。
 *
 * 有后端时走后端下载端点——那样才会计入下载统计；静态站直接指向随站点一起发布的
 * APK 文件；两者都不可用时退回 GitHub Releases，绝不给出一个必然 404 的地址。
 */
export function downloadUrl(release, site) {
  const fallback = site?.links?.releases || site?.links?.github || '#'
  if (!release?.apk) return fallback
  if (isStaticBuild) return `${import.meta.env.BASE_URL}apk/${release.apk.file}`
  return `/api/download/${encodeURIComponent(release.version)}`
}

export function isExternalDownload(release) {
  return !release?.apk
}

/**
 * 通道显示名与日志分类配色都来自站点元信息（见 lib/siteMeta.js），
 * 后台改数据即生效，不需要动这里的代码。未知取值原样透出，不硬编一个错的名字。
 */
export function channelLabel(channel) {
  return channelLabels[channel] || channel || channelLabels.stable
}

export function sectionTone(type) {
  return sectionTones[type] || 'plain'
}
