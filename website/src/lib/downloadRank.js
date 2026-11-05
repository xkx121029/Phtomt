const TOLERANCE = 1.15
/** 两边都量到首字节时的抖动余量（毫秒） */
const JITTER_MS = 100

/**
 * 下载源排序（纯函数，无副作用，方便单独验证各种网络情形）。
 *
 * 输入两份 HEAD 探测结果：{ status: 'ok' | 'slow' | 'down', ttfbMs? }
 * 「down」= 超时或连不上，该源不可用；「ok / slow」都算量到了首字节，只是后者慢于阈值，
 * 仍按实测值参与比较——排序只看谁更快，不看谁被贴了什么标签。
 * 规则：两边都可用时，GitHub 首字节不慢于镜像的 1.15 倍（外加 100ms 抖动余量）就用 GitHub；
 * 只有一边可用就用那一边；两边都不可用返回 degraded，调用方退回默认直链。
 */
export function decideSource({ githubProbe, originProbe, githubUrl = '', originUrl = '' }) {
  const githubUp = githubProbe.status !== 'down' && Boolean(githubUrl)
  const originUp = originProbe.status !== 'down'

  if (githubUp && originUp) {
    const faster = githubProbe.ttfbMs <= originProbe.ttfbMs * TOLERANCE + JITTER_MS
    return faster
      ? { source: 'github', url: githubUrl, degraded: false }
      : { source: 'origin', url: originUrl, degraded: false }
  }
  if (githubUp) return { source: 'github', url: githubUrl, degraded: false }
  if (originUp) return { source: 'origin', url: originUrl, degraded: false }
  return { source: 'origin', url: '', degraded: true }
}
