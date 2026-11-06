import { Router } from 'express'
import { fail, findRelease, normalizeVersion, recordDownload } from '../lib/helpers.js'
import { buildOf, normalizeChannel, pickUpdate, latestVisible } from '../lib/update.js'
import { preferredAsset, releasePage } from '../lib/externalAssets.js'
import { originOf } from '../config.js'

/**
 * 客户端自动更新接口。
 *
 * 与 /api/releases 的区别：那是给官网页面看的，这个接口是给**装好的 App 自己**用的。
 * 安装包只在 GitHub / Gitee 的 Release 里，所以 `download.url` 是本站的跳转入口，
 * 服务端 302 到外部资产；官网上不保存任何 APK 字节。
 */
export const updateRouter = Router()

function downloadInfo(req, release) {
  const apk = release.apk?.file ? release.apk : null
  if (!apk) return null
  const origin = originOf(req)
  return {
    url: `${origin}/api/download/${encodeURIComponent(release.version)}`,
    file: apk.file,
    size: apk.size,
    sizeText: apk.sizeText,
    sha256: apk.sha256,
    // 安装包来自 Release，不再是蓝奏云镜像；字段保留只为兼容旧客户端的解析
    mirror: 'release',
    shareUrl: null,
    password: ''
  }
}

function releaseSummary(release) {
  if (!release) return null
  return {
    version: release.version,
    build: buildOf(release),
    channel: normalizeChannel(release.channel),
    date: release.date || '',
    title: release.title || '',
    summary: release.summary || '',
    notes: Array.isArray(release.notes) ? release.notes : []
  }
}

updateRouter.get('/check', (req, res) => {
  const channel = normalizeChannel(req.query.channel)
  const version = normalizeVersion(req.query.version)
  const build = Number(req.query.build) || 0

  // 客户端没报版本时（如刚打开设置页手动检查）直接给该频道可见范围内的最新一版
  const target = version
    ? pickUpdate({ clientChannel: channel, version, build })
    : latestVisible(channel)

  if (!target) {
    return res.json({
      channel,
      clientVersion: version,
      clientBuild: build,
      hasUpdate: false,
      latest: releaseSummary(latestVisible(channel)),
      download: null
    })
  }

  res.json({
    channel,
    clientVersion: version,
    clientBuild: build,
    hasUpdate: Boolean(version),
    latest: releaseSummary(target),
    download: downloadInfo(req, target)
  })
})

/**
 * 旧客户端可能直接持有这个下载地址，因此保留路由：同样只做 302，不再经本站传字节。
 */
updateRouter.get('/download/:version', async (req, res) => {
  const release = findRelease(req.params.version)
  if (!release || release.published === false || !release.apk?.file) {
    return fail(res, 404, 'not_found', `未找到可下载的版本 ${req.params.version}`)
  }
  const asset = await preferredAsset(release)
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('X-Release-Version', release.version)
  res.setHeader('X-Update-Source', asset?.id || 'releases-page')
  const sha = String(release.apk?.sha256 || '').replace(/[^a-fA-F0-9]/g, '')
  if (sha) res.setHeader('X-Checksum-Sha256', sha)
  if (asset) recordDownload(release.version)
  return res.redirect(302, asset?.url || releasePage())
})
