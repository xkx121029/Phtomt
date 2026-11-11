import { Router } from 'express'
import { fail, recordDownload } from '../lib/helpers.js'
import { downloadTarget, findRelease } from '../lib/releaseFeed.js'
import { SOURCES_WAIT, externalAssets, preferredAsset, releasePage } from '../lib/externalAssets.js'

/**
 * 下载入口只做跳转。
 *
 * 安装包放在 GitHub / Gitee 的 Release 里，官网不再保存 APK 文件，也不再透传字节：
 * 这里选一个当前最可能拿到的外部资产地址，302 过去，并把版本号与校验值放在响应头，
 * 客户端可以据此核对。两边都定位不到资产时跳到 Release 页面，绝不返回一个死链。
 */
export const downloadRouter = Router()

async function redirectTo(req, res, release) {
  const asset = await preferredAsset(release)
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('X-Release-Version', release.version)
  res.setHeader('X-Download-Source', asset?.id || 'releases-page')
  // 只放纯十六进制：数据里偶尔带换行/空格，直接塞进响应头会让 Node 抛错
  const sha = String(release.apk?.sha256 || '').replace(/[^a-fA-F0-9]/g, '')
  if (sha) res.setHeader('X-Checksum-Sha256', sha)
  if (asset) recordDownload(release.version)
  return res.redirect(302, asset?.url || releasePage())
}

/** 走 GitHub / Gitee 直链时补一条下载计数（页面点击时用 sendBeacon 调它） */
downloadRouter.post('/hit/:version', (req, res) => {
  const release = findRelease(req.params.version)
  if (release) recordDownload(release.version)
  res.status(204).end()
})

/** 浏览器测速用：返回该版本在两个平台上的候选资产地址（不再有本站直链） */
downloadRouter.get('/sources/:version', async (req, res) => {
  const release = findRelease(req.params.version)
  if (!release || release.published === false) {
    return fail(res, 404, 'not_found', `未找到版本 ${req.params.version}`)
  }
  const candidates = await externalAssets(release, { wait: SOURCES_WAIT })
  res.setHeader('Cache-Control', 'no-store')
  return res.json({
    version: release.version,
    file: release.apk?.file || '',
    candidates,
    page: releasePage()
  })
})

downloadRouter.get('/latest', (req, res) => {
  // 与首页同一个判据：优先最新稳定版，没有稳定版就退最新测试包
  const { release } = downloadTarget()
  if (!release) return fail(res, 404, 'not_found', '暂无可下载的版本')
  if (req.query.redirect === '0') {
    return res.json({ version: release.version, channel: release.channel, file: release.apk.file })
  }
  return redirectTo(req, res, release)
})

downloadRouter.get('/:version', (req, res) => {
  const release = findRelease(req.params.version)
  if (!release || release.published === false) {
    return fail(res, 404, 'not_found', `未找到版本 ${req.params.version}`)
  }
  return redirectTo(req, res, release)
})
