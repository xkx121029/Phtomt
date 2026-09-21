/**
 * v-reveal —— 元素进入视口时自下而上淡入。
 *
 * 为什么用指令而不是组件：需要淡入的往往是页面里已有的结构（标题、卡片、表格行），
 * 包一层组件会凭空多出 DOM 层级，也会打乱 grid/flex 的子元素关系。
 *
 * 用法：
 *   <div v-reveal>…</div>
 *   <div v-reveal="80">…</div>        // 延迟 80ms，用于列表错峰
 */

const SUPPORTED = typeof IntersectionObserver !== 'undefined'

let observer = null
const pending = new WeakMap()

function ensureObserver() {
  if (observer || !SUPPORTED) return observer
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const el = entry.target
        const delay = pending.get(el) || 0
        if (delay) {
          setTimeout(() => {
            el.dataset.shown = 'true'
          }, delay)
        } else {
          el.dataset.shown = 'true'
        }
        observer.unobserve(el)
      }
    },
    // 提前 60px 触发，滚动到位时动画已经跑完，不会有「追上内容」的滞后感
    { rootMargin: '0px 0px -60px 0px', threshold: 0.01 }
  )
  return observer
}

export const vReveal = {
  mounted(el, binding) {
    el.classList.add('reveal')
    const reduceMotion =
      typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    if (!SUPPORTED || reduceMotion) {
      el.dataset.shown = 'true'
      return
    }
    pending.set(el, Number(binding.value) || 0)
    el.dataset.shown = 'false'
    ensureObserver()?.observe(el)
  },
  unmounted(el) {
    observer?.unobserve(el)
    pending.delete(el)
  }
}
