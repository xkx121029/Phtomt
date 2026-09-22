/**
 * 卡片悬停跟随鼠标的微倾斜。
 *
 * 为什么用文档级代理而不是 v-tilt 指令：全站卡片大多由 v-for 在数据回来之后才渲染，
 * 逐个挂指令要在每个模板里加一遍、还要跟着重渲染补挂；一个 pointermove 监听配
 * closest('.card') 就能覆盖全部卡片，包括后渲染出来的。
 *
 * 只对「能 hover + 细指针」的设备生效，触屏上指针位置没有意义。
 * 后台（.main--admin）整块跳过：那是填表和改数据的工具，卡片跟着指针晃会干扰操作。
 */

const MAX_DEG = 4 // 再大就从「微倾」变成玩具
const PERSPECTIVE = 900

export function useCardTilt() {
  if (!matchMedia('(hover: hover) and (pointer: fine)').matches) return
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return

  let current = null

  function onMove(e) {
    const card = e.target instanceof Element ? e.target.closest('.card') : null
    const next = card && !card.closest('.main--admin') ? card : null

    if (next !== current) {
      // 换卡先把上一张的角度撤掉，.tilt 上的过渡会把它送回原位
      if (current) current.style.removeProperty('transform')
      current = next
      if (current) current.classList.add('tilt')
    }
    if (!current) return

    const rect = current.getBoundingClientRect()
    if (!rect.width || !rect.height) return

    // 指针所在的那半边「按进去」：右半边右缘后退，下半边下缘后退
    const x = (e.clientX - rect.left) / rect.width - 0.5
    const y = (e.clientY - rect.top) / rect.height - 0.5
    current.style.transform =
      `perspective(${PERSPECTIVE}px) ` +
      `rotateX(${(-y * 2 * MAX_DEG).toFixed(2)}deg) ` +
      `rotateY(${(x * 2 * MAX_DEG).toFixed(2)}deg)`
  }

  function reset() {
    if (current) current.style.removeProperty('transform')
    current = null
  }

  window.addEventListener('pointermove', onMove, { passive: true })
  // 指针移出窗口、页面滚动、窗口失焦都要回正，否则角度会僵在半路
  document.addEventListener('mouseleave', reset)
  window.addEventListener('scroll', reset, { passive: true })
  window.addEventListener('blur', reset)
}
