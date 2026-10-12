import { compareVersion, latestRelease, normalizeVersion } from './helpers.js'
import * as store from './store.js'

/**
 * 客户端自动更新的频道判定。
 *
 * 站点已有 4 个发布频道，按「激进程度」从低到高排：stable < beta < dev < nightly。
 * 客户端在设置里选一个「接收频道」，就能收到**同档以及更稳定档**的版本——
 * 也就是「稳定版只收稳定版、测试版还能收到稳定版、开发版能收到稳定版+测试版、每日构建全收」。
 *
 * 这条矩阵是产品约定，只在这里定义一次：接口判定、提示文案、后台展示都从这里读，
 * 避免「接口放行了但文案说不行」这类只有用户能撞到的矛盾。
 */
export const CHANNEL_ORDER = ['stable', 'beta', 'dev', 'nightly']

export const CHANNEL_LABELS = {
  stable: '稳定版',
  beta: '测试版',
  dev: '开发版',
  nightly: '每日构建'
}

/** 归一化频道名：未知/空一律按稳定版处理，避免脏参数把接口带偏 */
export function normalizeChannel(input) {
  const v = String(input || '').trim().toLowerCase()
  return CHANNEL_ORDER.includes(v) ? v : 'stable'
}

/** 该接收频道能看到的发布频道集合（含自身与更稳定档） */
export function visibleChannels(clientChannel) {
  const rank = CHANNEL_ORDER.indexOf(normalizeChannel(clientChannel))
  return CHANNEL_ORDER.slice(0, rank + 1)
}

export function isChannelVisible(clientChannel, releaseChannel) {
  return visibleChannels(clientChannel).includes(normalizeChannel(releaseChannel))
}

/** 发布记录里的构建号：优先用显式 build，其次从版本号末段推断 */
export function buildOf(release) {
  if (release && Number.isFinite(Number(release.build))) return Number(release.build)
  const parts = normalizeVersion(release?.version).replace(/^v/i, '').split('.')
  const last = Number(parts[parts.length - 1])
  return Number.isFinite(last) ? last : 0
}

/**
 * 挑选客户端应当升级到的版本。
 *
 * 判据有两层，缺一不可：
 *  1. 频道可见（见 [visibleChannels]）——客户端不该收到被自己屏蔽的渠道；
 *  2. 确实更新——版本号更高，或版本号相同但构建号更高（同版本号补包场景）。
 * 返回 null 表示「已是最新」。
 */
export function pickUpdate({ clientChannel = 'stable', version = '', build = 0 } = {}) {
  const channels = visibleChannels(clientChannel)
  const currentBuild = Number(build) || 0
  const currentVersion = normalizeVersion(version)
  const candidates = store
    .read('releases')
    .filter((r) => r.published !== false)
    .filter((r) => r.apk && r.apk.file)
    .filter((r) => channels.includes(normalizeChannel(r.channel)))
    .filter((r) => {
      const diff = compareVersion(r.version, currentVersion)
      if (diff > 0) return true
      if (diff < 0) return false
      // 版本号相同就看构建号，构建号也同样（或客户端没报）则不算更新
      return buildOf(r) > currentBuild
    })
    .sort((a, b) => {
      const diff = compareVersion(a.version, b.version)
      return diff !== 0 ? diff : buildOf(a) - buildOf(b)
    })
  return candidates.pop() || null
}

/** 未指定版本时回退到该频道可见范围内的最新一版 */
export function latestVisible(clientChannel) {
  const channels = visibleChannels(clientChannel)
  return (
    store
      .read('releases')
      .filter((r) => r.published !== false && r.apk && r.apk.file)
      .filter((r) => channels.includes(normalizeChannel(r.channel)))
      .sort((a, b) => compareVersion(a.version, b.version))
      .pop() || latestRelease()
  )
}
