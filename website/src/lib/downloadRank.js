/**
 * 在两个外部 Release 源之间择优。
 *
 * 这里没有「默认源」：GitHub 与 Gitee 都是外链，谁实测首字节响应更快就用谁。
 * 之前本站直链与 GitHub 比较时用 1.15 倍容忍（避免几百毫秒的抖动把用户从默认源
 * 赶到外链）；现在两边地位对等，直接取响应更快的一边即可，判据更容易解释。
 *
 * `down` 表示这次探测没通（超时 / 网络不可达），不参与比较；两边都 down 时
 * 返回 degraded，页面继续用默认跳转入口（服务端再做一次选择）。
 */
export function pickFastest(candidates) {
  const usable = candidates.filter((c) => c.probe && c.probe.status !== 'down' && c.url)
  if (!usable.length) {
    return { source: 'default', url: '', degraded: true, reason: '两个源都没测通' }
  }
  const best = usable.reduce((a, b) => (Number(b.probe.ttfbMs) < Number(a.probe.ttfbMs) ? b : a))
  return {
    source: best.id,
    url: best.url,
    degraded: false,
    reason: `${best.label} 首字节 ${best.probe.ttfbMs}ms`
  }
}
