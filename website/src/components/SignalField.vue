<script setup>
/**
 * SignalField —— 首屏背景：一片点阵「信号场」。
 *
 * 隐喻取自产品本身：手机屏幕上的可交互控件，被 Agent 逐个点亮。
 * 指针附近点亮，离开后缓慢回落；另有一条很弱的斜向波在无操作时缓慢扫过，
 * 让画面在静止时也不是死的。
 *
 * 性能取向：
 *  - 点阵本身是 O(n) 的半径/透明度调制，n ≈ 1000；
 *  - 连线只对「已点亮」的点做局部邻域查找（右/下四个邻居），不做全量 O(n²)；
 *  - dpr 上限 2，4K 屏上也不会因为像素量翻倍而掉帧；
 *  - 离开视口、标签页隐藏、系统开启「减弱动态效果」时都停掉循环。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const host = ref(null)

let ctx = null
let canvas = null
let dots = []
let raf = 0
let cols = 0
let rows = 0
let width = 0
let height = 0
let pointer = { x: -9999, y: -9999, active: false }
let colors = { base: '#b7bdb4', active: '#0e7c66' }
/* 渐变量化档数：够密（肉眼看不出分档）又够省（取色不用再算） */
let palette = []
let visible = true
let reduced = false
let dpr = 1

const GAP = 26
const INFLUENCE = 148
const PALETTE_STEPS = 24
/* 点亮阈值：低于它的点算「暗」，不参与连线，也不画出来 */
const LIT = 0.15
/* 连线只认 1.6 格以内的邻居——刚好覆盖 8 邻域，再远就成蜘蛛网了 */
const REACH2 = (GAP * 1.6) ** 2

function readColors() {
  const style = getComputedStyle(document.documentElement)
  colors = {
    base: style.getPropertyValue('--line-2').trim() || '#b7bdb4',
    active: style.getPropertyValue('--brand').trim() || '#0e7c66'
  }
  palette = buildPalette(colors.base, colors.active)
}

function build() {
  const rect = host.value.getBoundingClientRect()
  width = Math.max(1, Math.round(rect.width))
  height = Math.max(1, Math.round(rect.height))
  dpr = Math.min(window.devicePixelRatio || 1, 2)

  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  cols = Math.ceil(width / GAP) + 1
  rows = Math.ceil(height / GAP) + 1
  dots = new Array(cols * rows)
  for (let y = 0; y < rows; y += 1) {
    for (let x = 0; x < cols; x += 1) {
      dots[y * cols + x] = {
        x: x * GAP,
        y: y * GAP,
        a: 0
      }
    }
  }
}

function parseHex(hex) {
  const value = String(hex).replace('#', '')
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value
  return [
    parseInt(full.slice(0, 2), 16) || 0,
    parseInt(full.slice(2, 4), 16) || 0,
    parseInt(full.slice(4, 6), 16) || 0
  ]
}

/**
 * 点亮强度 → 颜色。
 *
 * 曾是每帧对每个点现场算一遍：一次十六进制解析 + 六次 parseInt + 拼字符串。
 * n≈1000、60fps，就是每秒六万次字符串分配，GC 压力直接反映在 Speed Index 上。
 * 颜色只有「底色 → 品牌色」一条直线渐变，量化成定长表后取色退化成一次数组索引。
 */
function buildPalette(base, active) {
  const a = parseHex(base)
  const b = parseHex(active)
  const out = new Array(PALETTE_STEPS)
  for (let i = 0; i < PALETTE_STEPS; i += 1) {
    const t = i / (PALETTE_STEPS - 1)
    out[i] = `rgb(${Math.round(a[0] + (b[0] - a[0]) * t)},${Math.round(
      a[1] + (b[1] - a[1]) * t
    )},${Math.round(a[2] + (b[2] - a[2]) * t)})`
  }
  return out
}

/**
 * 连线：只对已点亮的点找邻居。
 * 每条线只从「编号小的点」画一次——候选邻居限定右/下四格，
 * 跨行的绕回用列号差 >1 挡掉，否则每行末尾会和下一行行首连出一条斜线。
 */
function drawLinks() {
  ctx.lineWidth = 1
  ctx.strokeStyle = colors.active
  for (let i = 0; i < dots.length; i += 1) {
    const a = dots[i]
    if (a.a < LIT) continue
    const ax = i % cols
    for (let k = 0; k < 4; k += 1) {
      const j = k === 0 ? i + 1 : k === 1 ? i + cols - 1 : k === 2 ? i + cols : i + cols + 1
      if (j < 0 || j >= dots.length) continue
      if (Math.abs((j % cols) - ax) > 1) continue
      const b = dots[j]
      if (b.a < LIT) continue
      const dx = b.x - a.x
      const dy = b.y - a.y
      if (dx * dx + dy * dy > REACH2) continue
      ctx.globalAlpha = Math.min(a.a, b.a) * 0.5
      ctx.beginPath()
      ctx.moveTo(a.x, a.y)
      ctx.lineTo(b.x, b.y)
      ctx.stroke()
    }
  }
}

function frame(time) {
  raf = requestAnimationFrame(frame)
  if (!visible || !ctx) return

  ctx.clearRect(0, 0, width, height)

  // 先连线后画点：让点压在线上，接点处才干净
  drawLinks()

  const phase = time * 0.0006
  for (let i = 0; i < dots.length; i += 1) {
    const dot = dots[i]

    let target = 0
    if (pointer.active) {
      const dx = dot.x - pointer.x
      const dy = dot.y - pointer.y
      const dist = Math.hypot(dx, dy)
      if (dist < INFLUENCE) {
        const t = 1 - dist / INFLUENCE
        target = t * t
      }
    }

    // 无操作时也有极弱的斜向波扫过，画面保持「活着」
    const ambient = 0.1 * (0.5 + 0.5 * Math.sin((dot.x + dot.y) * 0.008 - phase))
    target = Math.max(target, ambient)

    dot.a += (target - dot.a) * 0.12
    if (dot.a < 0.004) continue

    const r = 1 + dot.a * 2
    ctx.beginPath()
    ctx.arc(dot.x, dot.y, r, 0, Math.PI * 2)
    ctx.fillStyle = palette[Math.min(PALETTE_STEPS - 1, Math.round(dot.a * (PALETTE_STEPS - 1)))]
    ctx.globalAlpha = 0.18 + dot.a * 0.72
    ctx.fill()
  }

  ctx.globalAlpha = 1
}

function onPointerMove(event) {
  const rect = host.value.getBoundingClientRect()
  pointer = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top,
    active: true
  }
}

function onPointerLeave() {
  pointer = { ...pointer, active: false }
}

let resizeObserver = null
let intersectionObserver = null
let themeObserver = null

function onVisibility() {
  visible = document.visibilityState === 'visible'
}

onMounted(() => {
  canvas = document.createElement('canvas')
  canvas.setAttribute('aria-hidden', 'true')
  canvas.style.cssText = 'position:absolute;inset:0;pointer-events:none'
  host.value.appendChild(canvas)
  ctx = canvas.getContext('2d')

  reduced =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

  readColors()
  build()

  if (reduced) {
    // 减弱动态效果下只画一层静态点阵，不做任何逐帧变化
    for (const dot of dots) {
      ctx.beginPath()
      ctx.arc(dot.x, dot.y, 1.2, 0, Math.PI * 2)
      ctx.fillStyle = colors.base
      ctx.globalAlpha = 0.26
      ctx.fill()
    }
    ctx.globalAlpha = 1
    return
  }

  window.addEventListener('pointermove', onPointerMove, { passive: true })
  host.value.addEventListener('pointerleave', onPointerLeave)

  resizeObserver = new ResizeObserver(() => {
    build()
  })
  resizeObserver.observe(host.value)

  intersectionObserver = new IntersectionObserver(
    ([entry]) => {
      visible = entry.isIntersecting && document.visibilityState === 'visible'
    },
    { threshold: 0 }
  )
  intersectionObserver.observe(host.value)

  document.addEventListener('visibilitychange', onVisibility)

  // 切换深浅色时点阵颜色要跟着换，否则深色底上会出现一片灰点
  themeObserver = new MutationObserver(readColors)
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme']
  })

  // 挂载期就开跑会和 LCP 抢主线程：等首帧画完（两帧）再起循环。
  // 只是延后 2 帧，观感上仍然是「一进来就在动」。
  raf = requestAnimationFrame(() => {
    raf = requestAnimationFrame(frame)
  })
})

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  window.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('visibilitychange', onVisibility)
  host.value?.removeEventListener('pointerleave', onPointerLeave)
  resizeObserver?.disconnect()
  intersectionObserver?.disconnect()
  themeObserver?.disconnect()
  canvas?.remove()
})
</script>

<template>
  <div ref="host" class="field" aria-hidden="true">
    <!-- canvas 由脚本 appendChild 到 host，因此这一层永远在点阵下方 -->
    <span class="field__glow"></span>
  </div>
</template>

<style scoped>
.field {
  position: absolute;
  inset: 0;
  overflow: hidden;
  /* 底部渐隐到页面底色，让点阵「溶」进正文，而不是被一条硬边切断 */
  mask-image: radial-gradient(120% 90% at 50% 0%, #000 38%, transparent 82%);
}

/* 点阵下面垫一层品牌色光晕：没有它，再亮的点也像浮在空气里 */
.field__glow {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(58% 52% at 22% 2%, var(--brand-glow) 0%, transparent 70%),
    radial-gradient(46% 44% at 88% 22%, rgba(63, 114, 146, 0.16) 0%, transparent 72%);
}
</style>
