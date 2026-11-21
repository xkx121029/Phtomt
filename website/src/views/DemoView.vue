<script setup>
/**
 * 界面试用页。
 *
 * 嵌的是 `public/demo/app.html`——从仓库 `promo/app.html` 原样拷来的自包含成品：
 * 自带一套 `:root` 设计令牌（逐条对应 Color.kt / Theme.kt / Type.kt / Motion.kt）、
 * 自带状态机，以及 434×914 的整机夹具与「按自身视口等比缩放」的 fitDevice。
 *
 * 三条约束决定了它只能以 iframe 存在，换一种嵌法都会坏：
 *  - 它的 `:root` 和全站同名令牌会互相覆盖，合并等于两边都改坏；
 *  - 它的缩放基准是 window，不是某个容器，塞进别的高度里会算错；
 *  - 它有一整套自己的指针与键盘处理，并进主文档会和全站滚动抢事件。
 *
 * 所以这一页只做三件事：给足一整屏、装载期间说清在等什么、留一条回官网的路。
 * 282 KB 不进首页：路由懒加载，iframe 由浏览器单独请求，首屏一个字节都不多付。
 */
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

// base 在静态导出时是 './'（hash 路由），开发与 Express 托管时是 '/'
const demoUrl = `${import.meta.env.BASE_URL}demo/app.html`
const loaded = ref(false)
const shown = computed(() => loaded.value)
</script>

<template>
  <div class="demo">
    <h1 class="demo__title">界面试用 · Happy Phone Agent</h1>

    <div v-if="!shown" class="demo__wait" role="status">
      <p class="mono demo__wait-label">载入界面</p>
      <p class="demo__wait-text">
        正在取这份 282 KB 的自包含页面。它跑在浏览器里，不连你的手机，也不消耗任何模型额度。
      </p>
    </div>

    <iframe
      :src="demoUrl"
      :class="['demo__frame', { 'is-loaded': shown }]"
      title="Happy Phone Agent 界面复刻，可直接操作"
      @load="loaded = true"
    ></iframe>

    <RouterLink to="/" class="demo__back">← 回官网</RouterLink>
  </div>
</template>

<style scoped>
.demo {
  position: fixed;
  inset: 0;
  background: var(--stage, #0a0c0b);
}

/* 页面上唯一的标题，视觉上不需要——这一屏全是别人的界面 */
.demo__title {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

.demo__frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  opacity: 0;
  transition: opacity var(--t-slow, 280ms) var(--ease, ease);
}

.demo__frame.is-loaded {
  opacity: 1;
}

.demo__wait {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  gap: 10px;
  max-width: 26rem;
  margin: 0 auto;
  padding: 0 24px;
  text-align: center;
}

.demo__wait-label {
  color: var(--brand);
  font-size: 0.8125rem;
  letter-spacing: 0.08em;
}

.demo__wait-text {
  color: var(--ink-2);
  font-size: 0.9375rem;
  line-height: 1.7;
  text-wrap: pretty;
}

.demo__back {
  position: fixed;
  top: 14px;
  left: 14px;
  z-index: 2;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--stage, #0a0c0b) 86%, transparent);
  color: var(--ink-2);
  font-size: 0.8125rem;
  text-decoration: none;
  backdrop-filter: blur(8px);
}

.demo__back:hover {
  color: var(--ink);
  border-color: var(--brand);
}
</style>
