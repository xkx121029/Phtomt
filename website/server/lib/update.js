import { compareVersion, normalizeVersion } from './helpers.js'
import { latestRelease, listReleases } from './releaseFeed.js'

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
  // 去掉预发布后缀再取最后一段：`v0.2.9195-beta` 的构建号是 9195，不是 NaN。
  const parts = normalizeVersion(release?.version).replace(/^v/i, '').split('-')[0].split('.')
  const last = Number(parts[parts.length - 1])
  return Number.isFinite(last) ? last : 0
}

/**
 * 已废弃的「渠道偏移号段」下界，与根 build.gradle.kts 的 LEGACY_CHANNEL_SEGMENT_FLOOR 同值。
 *
 * 2026-11-05 曾用 versionCode 高位编渠道（dev = 1000000+N、beta = 2000000+N），当天就改回
 * 单序列计数器，但那批包已经装到设备上了。这些客户端上报的 0.2.2000096 之类版本号在
 * 纯数字比较下永远「比新版本大」，会让它们永远收不到更新（实测 hasUpdate=false）。
 * 所以号段内的版本视为无效客户端版本：不参与比较，直接给该频道最新版。
 */
const LEGACY_CHANNEL_SEGMENT_FLOOR = 1_000_000

/** 客户端上报的版本号是否落在已废弃号段（不可信，需要强制升级） */
function isLegacyVersion(version) {
  const parts = String(version || '').replace(/^v/i, '').split('-')[0].split('.')
  return parts.some((p) => Number(p) >= LEGACY_CHANNEL_SEGMENT_FLOOR)
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
  let currentBuild = Number(build) || 0
  let currentVersion = normalizeVersion(version)
  // 废弃号段的客户端：版本号不可比（永远显得“更新”），按「没有版本」处理，
  // 于是候选集不做版本过滤，直接选出该频道可见范围内最新的一版把它拉回来。
  if (isLegacyVersion(currentVersion)) {
    currentVersion = ''
    currentBuild = 0
  }
  const candidates = listReleases()
    .filter((r) => channels.includes(normalizeChannel(r.channel)))
    // 候选版本本身也不能落在废弃号段：那段包（v0.2.2000093-beta 等）还在 Releases 里，
    // 纯数字排序会把它们顶到最前，结果「更新」到另一个同样的坏包，等于没修。
    .filter((r) => !isLegacyVersion(r.version))
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
  // 升序排序，最大的一版在末尾。废弃号段已被排除，所以末位就是该频道可见范围内的最高版本。
  return candidates[candidates.length - 1] || null
}

/** 未指定版本时回退到该频道可见范围内的最新一版（按发布时间） */
export function latestVisible(clientChannel) {
  const channels = visibleChannels(clientChannel)
  // 排除废弃号段：那些包在 Releases 里还在，但不能再推给任何客户端
  const visible = listReleases().filter((r) => !isLegacyVersion(r.version))
  return visible.find((r) => channels.includes(normalizeChannel(r.channel))) || visible[0] || latestRelease()
}
