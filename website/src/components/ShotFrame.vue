<script setup>
/**
 * ShotFrame —— 真实截图 + 说明。
 *
 * 截图带用「一张图 + 一句解释它在证明什么」，不是一堆缩略图拼图：
 * 图片本身说明不了机制，说明文字才是信息。
 */
defineProps({
  src: { type: String, required: true },
  alt: { type: String, required: true },
  label: { type: String, default: '' },
  caption: { type: String, default: '' },
  ratio: { type: String, default: '9 / 20' },
  tone: { type: String, default: 'light' }
})
</script>

<template>
  <figure :class="['shot', `shot--${tone}`]">
    <div class="shot__screen" :style="{ aspectRatio: ratio }">
      <img :src="src" :alt="alt" loading="lazy" decoding="async" />
    </div>
    <figcaption class="shot__cap">
      <span v-if="label" class="shot__label mono">{{ label }}</span>
      <span v-if="caption" class="shot__text">{{ caption }}</span>
    </figcaption>
  </figure>
</template>

<style scoped>
.shot {
  margin: 0;
  display: grid;
  gap: 14px;
}

/* 深色界面的截图放在深色底板上，浅色界面用浅底板——截图边缘不会和页面底部打架 */
.shot__screen {
  overflow: hidden;
  border-radius: var(--r-lg);
  border: 1px solid var(--line);
  background: var(--paper-sunken);
}

.shot--dark .shot__screen {
  background: #0b0d0b;
  border-color: #23281f;
}

.shot__screen img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top center;
}

.shot__cap {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-2);
}

.shot__label {
  flex: none;
  font-size: var(--t-xs);
  color: var(--ink-3);
  letter-spacing: 0.04em;
}

.shot__text {
  text-wrap: pretty;
}
</style>
