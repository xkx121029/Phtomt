import { isStaticBuild } from '../api/client.js'

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

export const CHANNEL_LABEL = {
  stable: '稳定版',
  beta: '测试版',
  nightly: '每日构建',
  dev: '开发版'
}

export function channelLabel(channel) {
  return CHANNEL_LABEL[channel] || channel || '稳定版'
}

/** 变更条目的分类色：新增/优化/修复 各给一个语义色，其余用中性 */
export const SECTION_TONE = {
  新增: 'ok',
  优化: 'brand',
  变更: 'brand',
  修复: 'amber',
  性能: 'brand',
  测试: 'mist',
  文档: 'mist',
  移除: 'danger',
  安全: 'danger'
}

export function sectionTone(type) {
  return SECTION_TONE[type] || 'plain'
}
