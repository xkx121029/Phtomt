<script setup>
import { onBeforeUnmount, ref } from 'vue'

const props = defineProps({
  text: { type: String, required: true },
  label: { type: String, default: '复制' }
})

const copied = ref(false)
let timer = 0

async function copy() {
  try {
    await navigator.clipboard.writeText(props.text)
  } catch {
    // 非 HTTPS 环境下 clipboard API 不可用，退回 execCommand
    const area = document.createElement('textarea')
    area.value = props.text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    document.execCommand('copy')
    area.remove()
  }
  copied.value = true
  clearTimeout(timer)
  timer = setTimeout(() => {
    copied.value = false
  }, 1600)
}

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <button class="copy" type="button" :aria-label="label" @click="copy">
    <span class="copy__label">{{ copied ? '已复制' : label }}</span>
    <svg v-if="copied" width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M3.5 8.5l3 3 6-7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <svg v-else width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <rect x="5.5" y="5.5" width="8" height="8" rx="2" stroke="currentColor" stroke-width="1.4" />
      <path d="M10.5 3.5A1.5 1.5 0 0 0 9 2.5H4.5A2 2 0 0 0 2.5 4.5V9a1.5 1.5 0 0 0 1 1.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
    </svg>
  </button>
</template>

<style scoped>
.copy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
  color: var(--ink-2);
  font-size: var(--t-xs);
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    color var(--dur-ui) ease,
    border-color var(--dur-ui) ease;
}

.copy:active {
  transform: scale(0.96);
}

@media (hover: hover) and (pointer: fine) {
  .copy:hover {
    color: var(--ink);
    border-color: var(--line-2);
  }
}
</style>
