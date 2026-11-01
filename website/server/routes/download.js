import fs from 'node:fs'
import { Router } from 'express'
import { apkPath, fail, findRelease, latestRelease, recordDownload } from '../lib/helpers.js'
import { hubDownloadUrl, hubEnabled } from '../lib/hub.js'

export const downloadRouter = Router()

async function send(req, res, release) {
  const file = release.apk?.file
  const full = apkPath(file)

  // 包体在中枢：只做 302，下载流量不经过官网服务；签名直链默认 15 分钟有效
  if (hubEnabled() && file) {
    try {
      const url = await hubDownloadUrl(file, 900)
      if (url) {
        recordDownload(release.version)
        res.setHeader('X-Release-Version', release.version)
        if (release.apk?.sha256) res.setHeader('X-Checksum-Sha256', release.apk.sha256)
        return res.redirect(302, url)
      }
    } catch (err) {
      // 中枢故障时静默回落本地文件，保证官网下载可用
      console.warn(`[download] 中枢签名失败，回落本地文件：${err.message}`)
    }
  }

  if (!full || !fs.existsSync(full)) {
    return fail(res, 404, 'apk_missing', `版本 ${release.version} 的安装包尚未上传`)
  }
  recordDownload(release.version)
  const fileName = `HappyAgent-${release.version.replace(/^v/i, '')}.apk`
  res.setHeader('Content-Type', 'application/vnd.android.package-archive')
  res.setHeader('Content-Disposition', `attachment; filename="${fileName}"`)
  res.setHeader('X-Release-Version', release.version)
  if (release.apk?.sha256) res.setHeader('X-Checksum-Sha256', release.apk.sha256)
  // res.sendFile 自带 Range / ETag / Last-Modified，APK 体积大时断点续传很重要
  res.sendFile(full)
}

downloadRouter.get('/latest', (req, res) => {
  const release = latestRelease({ channel: req.query.channel || 'stable' })
  if (!release) return fail(res, 404, 'not_found', '暂无可下载的版本')
  if (req.query.redirect === '0') {
    return res.json({ version: release.version, file: release.apk.file })
  }
  return send(req, res, release)
})

downloadRouter.get('/:version', (req, res) => {
  const release = findRelease(req.params.version)
  if (!release || release.published === false) {
    return fail(res, 404, 'not_found', `未找到版本 ${req.params.version}`)
  }
  return send(req, res, release)
})
