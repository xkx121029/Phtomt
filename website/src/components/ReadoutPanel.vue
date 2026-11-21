<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 首屏的主角：真机截图旁边挂着「它在这块屏幕上读到了什么」。
 *
 * 这不是装饰性的假仪表盘——每一行都是引擎里真实存在的阶段
 * （observe / annotate / intent / translate / verify），值也取自真实实现
 * （无障碍取回控件树、语义 ID、IntentTranslator、页面指纹比对）。
 * 轮播只是让「正在发生」这件事可见；鼠标移上去或点一下就停在他看的那一行。
 */
const props = defineProps({
  src: { type: String, required: true },
  alt: { type: String, required: true },
  steps: { type: Array, required: true },
  foot: { type: Object, default: () => ({ left: '', right: '' }) },
  screen: { type: String, default: '' }
})

const active = ref(0)
const pinned = ref(false)
let timer = null

const rows = computed(() => props.steps || [])

function start() {
  stop()
  if (pinned.value) return
  const reduce = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduce) return
  timer = setInterval(() => {
    active.value = (active.value + 1) % Math.max(1, rows.value.length)
  }, 2200)
}

function stop() {
  if (timer) clearInterval(timer)
  timer = null
}

function pick(index) {
  active.value = index
  pinned.value = true
  stop()
}

onMounted(start)
onBeforeUnmount(stop)
</script>

<template>
  <div class="read">
    <div class="read__screen">
      <div class="read__phone">
        <img :src="src" :alt="alt" decoding="async" />
      </div>
    </div>

    <div class="read__body">
      <div class="read__head">
        <span class="mono">屏幕读数</span>
        <span class="mono read__dim">{{ screen }}</span>
      </div>

      <ul class="read__rows">
        <li
          v-for="(step, index) in rows"
          :key="step.key"
          :class="['row', { 'row--on': active === index }]"
          @mouseenter="pick(index)"
        >
          <span class="mono row__key">{{ step.key }}</span>
          <span class="row__val">{{ step.text }}</span>
        </li>
      </ul>

      <div class="read__foot">
        <span class="mono">{{ foot.left }}</span>
        <span class="mono read__ok">{{ foot.right }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.read {
  display: grid;
  grid-template-columns: minmax(0, 0.56fr) minmax(0, 0.44fr);
  gap: 0;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--panel);
  overflow: hidden;
  box-shadow: var(--shadow-2);
}

.read__screen {
  padding: 14px;
  border-right: 1px solid var(--line-soft);
  background: linear-gradient(180deg, var(--panel-3), var(--panel));
  display: grid;
  place-items: center;
}

.read__phone {
  width: 100%;
  max-width: 268px;
  border-radius: 18px;
  overflow: hidden;
  background: #050807;
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow-3);
}

.read__phone img {
  width: 100%;
  height: auto;
}

.read__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.read__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 0.8125rem;
  color: var(--ink-2);
}

.read__dim {
  color: var(--ink-2);
  opacity: 0.85;
}

.read__rows {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  flex: 1;
}

.row {
  display: grid;
  grid-template-columns: 5.6rem minmax(0, 1fr);
  gap: 10px;
  padding: 10px 14px;
  border-left: 2px solid transparent;
  font-size: 0.875rem;
  line-height: 1.65;
  color: var(--ink-2);
  transition:
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.row__key {
  color: var(--ink-3);
}

.row--on {
  border-left-color: var(--signal);
  background: var(--signal-wash);
  color: var(--ink);
}

.row--on .row__key {
  color: var(--signal);
}

.read__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 14px;
  border-top: 1px solid var(--line-soft);
  font-size: 0.8125rem;
  color: var(--ink-2);
}

.read__ok {
  color: var(--signal);
}

@media (max-width: 979px) {
  .read {
    grid-template-columns: minmax(0, 1fr);
  }

  .read__screen {
    border-right: none;
    border-bottom: 1px solid var(--line-soft);
    padding: 12px;
  }

  .read__phone {
    max-width: 232px;
  }

  .row {
    font-size: var(--t-sm);
  }
}
</style>
