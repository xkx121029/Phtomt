<script setup>
/**
 * 图版：一张真机截图 + 一行等宽图注。
 *
 * 截图是介绍 App 的页面里最重要的素材——只用文字描述产品的页面不算完成。
 * 图注写成机器记录的格式（界面名 · 版本 · 分辨率），因为它本来就是这个页面的证据。
 */
defineProps({
  src: { type: String, required: true },
  alt: { type: String, required: true },
  meta: { type: String, default: '' },
  caption: { type: String, default: '' },
  tone: { type: String, default: 'dark' }
})
</script>

<template>
  <figure :class="['shot', `shot--${tone}`]">
    <div class="shot__frame">
      <img :src="src" :alt="alt" loading="lazy" decoding="async" />
    </div>
    <figcaption class="shot__cap">
      <span v-if="meta" class="mono shot__meta">{{ meta }}</span>
      <span v-if="caption" class="shot__text">{{ caption }}</span>
    </figcaption>
  </figure>
</template>

<style scoped>
.shot {
  margin: 0;
  display: grid;
  gap: 12px;
  min-width: 0;
}

.shot__frame {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: #050807;
}

.shot--light .shot__frame {
  background: var(--panel-2);
}

.shot__frame img {
  width: 100%;
  height: auto;
}

.shot__cap {
  display: grid;
  gap: 2px;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--ink-2);
}

.shot__meta {
  color: var(--ink-2);
  font-size: 0.8125rem;
}

.shot__text {
  text-wrap: pretty;
}
</style>
