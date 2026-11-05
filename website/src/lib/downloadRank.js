const TOLERANCE = 1.15

/**
 * 下载源排序（纯函数，无副作用，方便单独验证各种网络情形）。
 *
 * 输入两份 HEAD 探测结果：{ status: 'ok' | 'slow' | 'down', ttfbMs? }
 * 规则：GitHub 首字节不慢于镜像的 1.15 倍（外加 100ms 抖动余量）就用 GitHub；
 * 明显更慢或不通就让位给镜像；两条线都不通返回 degraded，调用方退回默认直链。
 */
export function decideSource({ githubProbe, originProbe, githubUrl = '', originUrl = '' }) {
  const githubOk = githubProbe.status === 'ok'
  const originOk = originProbe.status === 'ok'

  if (githubOk && !originOk) return { source: 'github', url: githubUrl, degraded: false }
  if (githubOk && originOk && githubProbe.ttfbMs <= originProbe.ttfbMs * TOLERANCE + 100) {
    return { source: 'github', url: githubUrl, degraded: false }
  }
  if (githubProbe.status !== 'down' && originProbe.status === 'down') {
    return { source: 'github', url: githubUrl, degraded: false }
  }
  if (githubProbe.status === 'down' && originProbe.status === 'down') {
    return { source: 'origin', url: '', degraded: true }
  }
  return { source: 'origin', url: originUrl, degraded: false }
}
