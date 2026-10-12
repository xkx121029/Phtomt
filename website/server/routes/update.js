import fs from 'node:fs'
import { Router } from 'express'
import {
  apkInfo,
  apkPath,
  fail,
  findRelease,
  normalizeVersion,
  recordDownload
} from '../lib/helpers.js'
import { buildOf, normalizeChannel, pickUpdate, latestVisible } from '../lib/update.js'
import * as lanzou from '../lib/lanzou.js'
import { originOf } from '../config.js'

/**
 * 客户端自动更新接口。
 *
 * 与 /api/releases 的区别：那是给官网页面看的（返回列表、统计、装饰字段），
 * 这个接口是给**装好的 App 自己**用的——按客户端所在频道判定该不该升级，
 * 并给出一个「拿得到就能装」的下载地址（优先走蓝奏云镜像，失败自动回落官网直链）。
 */
export const updateRouter = Router()

function downloadInfo(req, release) {
  const apk = release.apk && release.apk.file ? apkInfo(release.apk.file) : null
  if (!apk) return null
  const cfg = lanzou.loadConfig()
  const mirror = lanzou.getMirror(apk.file)
  const origin = originOf(req)
  return {
    url: `${origin}/api/update/download/${encodeURIComponent(release.version)}`,
    file: apk.file,
    size: apk.size,
    sizeText: apk.sizeText,
    sha256: apk.sha256,
    // mirror=lanzou 时客户端下载的字节来自蓝奏云镜像；解析失败服务端会静默回落 origin
    mirror: cfg.enabled && mirror?.shareUrl ? 'lanzou' : 'origin',
    shareUrl: mirror?.shareUrl || null,
    password: mirror?.password || ''
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
 * 下载接口：优先经服务端从蓝奏云取回并解包出 APK 直出，失败回落到官网磁盘上的那份。
 * 对客户端而言永远是「一个能直接下到 APK 的地址」，不必理解蓝奏云的页面与提取码。
 */
updateRouter.get('/download/:version', async (req, res) => {
  const release = findRelease(req.params.version)
  if (!release || release.published === false || !release.apk?.file) {
    return fail(res, 404, 'not_found', `未找到可下载的版本 ${req.params.version}`)
  }
  const full = apkPath(release.apk.file)
  if (!full || !fs.existsSync(full)) {
    return fail(res, 404, 'apk_missing', `版本 ${release.version} 的安装包尚未上传`)
  }
  recordDownload(release.version)

  const fileName = `HappyAgent-${release.version.replace(/^v/i, '')}.apk`
  const headers = (info) => {
    res.setHeader('Content-Type', 'application/vnd.android.package-archive')
    res.setHeader('Content-Disposition', `attachment; filename="${fileName}"`)
    res.setHeader('X-Release-Version', release.version)
    res.setHeader('X-Update-Source', info)
    if (release.apk?.sha256) res.setHeader('X-Checksum-Sha256', release.apk.sha256)
  }

  const cfg = lanzou.loadConfig()
  const mirror = lanzou.getMirror(release.apk.file)
  if (cfg.enabled && cfg.preferMirror && mirror?.shareUrl) {
    try {
      const { content } = await lanzou.fetchMirrorApk(release.apk.file, full)
      headers('lanzou')
      return res.end(content)
    } catch (err) {
      // 蓝奏云接口极不稳定，解析失败是常态：记一笔日志就好，用户侧无感回落
      console.warn(`[update] 蓝奏云镜像不可用，回落官网直链：${err.message}`)
    }
  }

  headers('origin')
  return res.sendFile(full)
})
