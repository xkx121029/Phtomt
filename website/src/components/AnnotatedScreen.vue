<script setup>
/**
 * AnnotatedScreen —— 手机屏幕里的「标注演示」。
 *
 * 整站的主视觉母题就是设计工具的 inspect 模式：把界面上的控件逐个框出来，
 * 亮出它被赋予的语义 ID。这正是产品在做的事——把屏幕翻译成模型能读懂的结构。
 *
 * 所有 ID 都取自真实的 26 类语义控件，不编造。
 * 画面是纯 DOM 画的，不是截图：缩放不糊，也不会随 App 版本过期。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/** 演示序列：每一步点亮一个控件；最后两步弹出权限框 */
const STEPS = [
  'back_btn',
  'more_btn',
  'search_box',
  'switch_toggle',
  'add_btn',
  'send_btn',
  'dlg_dismiss',
  'dlg_allow'
]

const DIALOG_FROM = 6

const host = ref(null)
const step = ref(0)

let timer = 0
let observer = null
let running = false

const dialog = computed(() => step.value >= DIALOG_FROM)

function at(name) {
  return STEPS[step.value] === name
}

function start() {
  if (running) return
  running = true
  timer = window.setInterval(() => {
    step.value = (step.value + 1) % STEPS.length
  }, 1800)
}

function stop() {
  running = false
  window.clearInterval(timer)
}

onMounted(() => {
  const reduced =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
  // 减弱动态效果下停在第一个控件，保留「被框住」的静态信息，不做逐帧变化
  if (reduced) return

  // 滚出视口就停表，别让一个看不见的定时器一直跑
  observer = new IntersectionObserver(
    ([entry]) => {
      if (entry.isIntersecting && document.visibilityState === 'visible') start()
      else stop()
    },
    { threshold: 0.2 }
  )
  observer.observe(host.value)
})

onBeforeUnmount(() => {
  stop()
  observer?.disconnect()
})
</script>

<template>
  <div ref="host" class="app">
    <!-- 状态栏 -->
    <div class="app__status">
      <span class="app__time">9:41</span>
      <span class="app__status-icons" aria-hidden="true">
        <i class="bar bar--1"></i>
        <i class="bar bar--2"></i>
        <i class="bar bar--3"></i>
      </span>
    </div>

    <!-- 标题栏 -->
    <header class="app__head">
      <span class="anno anno--icon" :class="{ 'anno--on': at('back_btn') }" data-id="back_btn">
        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="M12 5 7 10l5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </span>
      <span class="app__title">Happy Agent</span>
      <span class="anno anno--icon" :class="{ 'anno--on': at('more_btn') }" data-id="more_btn">
        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <circle cx="5" cy="10" r="1.4" fill="currentColor" />
          <circle cx="10" cy="10" r="1.4" fill="currentColor" />
          <circle cx="15" cy="10" r="1.4" fill="currentColor" />
        </svg>
      </span>
    </header>

    <!-- 搜索 / 输入目标 -->
    <div class="anno anno--field" :class="{ 'anno--on': at('search_box') }" data-id="search_box">
      <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
        <circle cx="9" cy="9" r="4.6" stroke="currentColor" stroke-width="1.5" />
        <path d="m12.6 12.6 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      </svg>
      <span>说一句，让它去做</span>
    </div>

    <!-- 任务列表 -->
    <div class="app__list">
      <div class="app__task">
        <div class="app__task-body">
          <p class="app__task-title">打开设置，把屏幕亮度调到一半</p>
          <p class="app__task-meta">执行中 · 第 3 步</p>
        </div>
        <span class="anno anno--switch" :class="{ 'anno--on': at('switch_toggle') }" data-id="switch_toggle">
          <i class="app__switch"></i>
        </span>
      </div>

      <div class="app__task app__task--idle">
        <div class="app__task-body">
          <p class="app__task-title">给妈妈发消息「到家了」</p>
          <p class="app__task-meta">已完成 · 4 步</p>
        </div>
      </div>
    </div>

    <!-- 底部输入条 -->
    <div class="app__bar">
      <span class="anno anno--icon anno--round" :class="{ 'anno--on': at('add_btn') }" data-id="add_btn">
        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="M10 5.5v9M5.5 10h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
      </span>
      <span class="app__bar-field">输入指令…</span>
      <span class="anno anno--icon anno--round anno--send" :class="{ 'anno--on': at('send_btn') }" data-id="send_btn">
        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="M10 15.5v-11m0 0L5.8 8.7M10 4.5l4.2 4.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </span>
    </div>

    <!-- 权限弹窗：演示 Agent 触发系统授权后如何定位按钮 -->
    <Transition name="dlg">
      <div v-if="dialog" class="app__dlg">
        <div class="app__dlg-card">
          <p class="app__dlg-title">允许「Happy Agent」控制你的手机？</p>
          <p class="app__dlg-desc">用于读取屏幕上的控件，并代你完成点按与输入。</p>
          <div class="app__dlg-actions">
            <span class="anno anno--dlg" :class="{ 'anno--on': at('dlg_dismiss') }" data-id="dlg_dismiss">取消</span>
            <span class="anno anno--dlg anno--primary" :class="{ 'anno--on': at('dlg_allow') }" data-id="dlg_allow">允许</span>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.app {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0 1.15em;
  background: var(--paper);
}

/* ---------- 状态栏 ---------- */

.app__status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 2.9em;
  padding-inline: 0.5em;
  font-size: 0.86em;
  color: var(--ink-2);
}

.app__time {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.app__status-icons {
  display: flex;
  align-items: flex-end;
  gap: 0.28em;
  height: 0.9em;
}

.bar {
  width: 0.22em;
  border-radius: 1px;
  background: var(--ink-2);
}

.bar--1 {
  height: 40%;
}

.bar--2 {
  height: 70%;
}

.bar--3 {
  height: 100%;
}

/* ---------- 标题栏 ---------- */

.app__head {
  display: flex;
  align-items: center;
  gap: 0.6em;
  height: 3.1em;
}

.app__title {
  flex: 1;
  font-size: 1.08em;
  font-weight: 600;
  letter-spacing: -0.02em;
}

/* ---------- 输入框 ---------- */

.app__field {
  display: flex;
  align-items: center;
  gap: 0.55em;
  margin-top: 0.5em;
  padding: 0.72em 0.95em;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
  font-size: 0.94em;
  color: var(--ink-3);
}

.app__field svg {
  width: 1.1em;
  height: 1.1em;
  flex: none;
  color: var(--ink-3);
}

/* ---------- 任务列表 ---------- */

.app__list {
  display: grid;
  gap: 0.75em;
  margin-top: 1.1em;
}

.app__task {
  display: flex;
  align-items: center;
  gap: 0.8em;
  padding: 0.95em 1em;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--paper-raised);
}

.app__task--idle {
  opacity: 0.66;
}

.app__task-body {
  flex: 1;
  min-width: 0;
}

.app__task-title {
  font-size: 0.95em;
  font-weight: 500;
  line-height: 1.4;
  letter-spacing: -0.01em;
}

.app__task-meta {
  margin-top: 0.35em;
  font-size: 0.8em;
  color: var(--ink-3);
}

.app__switch {
  display: block;
  width: 2.2em;
  height: 1.3em;
  border-radius: var(--r-pill);
  background: var(--brand);
  position: relative;
}

.app__switch::after {
  content: "";
  position: absolute;
  top: 0.16em;
  right: 0.16em;
  width: 0.98em;
  height: 0.98em;
  border-radius: 50%;
  background: #fff;
}

/* ---------- 底部输入条 ---------- */

.app__bar {
  display: flex;
  align-items: center;
  gap: 0.7em;
  margin-top: auto;
  margin-bottom: 1.5em;
  padding: 0.6em 0.7em;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
}

.app__bar-field {
  flex: 1;
  font-size: 0.9em;
  color: var(--ink-3);
}

/* ---------- 语义标注母题 ---------- */

/* 未点亮时不占视觉；点亮后出现虚线框与左上角的 ID 角标 */
.anno {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: outline-color var(--dur-ui) var(--ease-out);
}

.anno--icon {
  width: 2.1em;
  height: 2.1em;
  border-radius: var(--r-sm);
  color: var(--ink-2);
}

.anno--round {
  border-radius: 50%;
}

.anno--send {
  background: var(--brand);
  color: #fff;
}

.anno--icon svg {
  width: 1.25em;
  height: 1.25em;
}

.anno--switch {
  border-radius: var(--r-pill);
}

.anno--dlg {
  padding: 0.5em 1.1em;
  border-radius: var(--r-pill);
  font-size: 0.92em;
  font-weight: 500;
  color: var(--ink-2);
}

.anno--dlg.anno--primary {
  background: var(--brand);
  color: #fff;
}

.anno--on {
  outline: 1px dashed var(--brand);
  outline-offset: 3px;
}

.anno--on::after {
  content: attr(data-id);
  position: absolute;
  top: 0;
  left: -2px;
  translate: 0 -50%;
  z-index: 4;
  padding: 0.12em 0.42em;
  border-radius: 4px;
  background: var(--brand);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 0.72em;
  font-weight: 500;
  letter-spacing: -0.01em;
  line-height: 1.5;
  white-space: nowrap;
}

/* ---------- 权限弹窗 ---------- */

.app__dlg {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.4em;
  background: rgba(20, 23, 21, 0.32);
  backdrop-filter: blur(2px);
}

.app__dlg-card {
  width: 100%;
  padding: 1.25em 1.15em 1.1em;
  border-radius: var(--r-xl);
  background: var(--paper-raised);
  box-shadow: var(--shadow-3);
  text-align: center;
}

.app__dlg-title {
  font-size: 1em;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: -0.02em;
}

.app__dlg-desc {
  margin-top: 0.5em;
  font-size: 0.85em;
  line-height: 1.6;
  color: var(--ink-2);
}

.app__dlg-actions {
  display: flex;
  justify-content: center;
  gap: 0.7em;
  margin-top: 1.1em;
}

.dlg-enter-active,
.dlg-leave-active {
  transition: opacity var(--dur-ui) var(--ease-out);
}

.dlg-enter-from,
.dlg-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .dlg-enter-active,
  .dlg-leave-active {
    transition: none;
  }
}
</style>
