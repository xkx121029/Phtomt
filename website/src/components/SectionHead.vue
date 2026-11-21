<script setup>
/**
 * 节标题：编号 + 小标签 + 标题 + 导语。
 *
 * 一个页面里的每一节都用它起头，序号从 01 排下去——说明书的目录感来自一致的
 * 节奏，而不是来自每个区块各自发明一套标题样式。
 */
defineProps({
  no: { type: String, default: '' },
  eyebrow: { type: String, default: '' },
  title: { type: String, default: '' },
  lead: { type: String, default: '' }
})
</script>

<template>
  <header class="sec">
    <div class="sec__meta">
      <span v-if="no" class="no">{{ no }}</span>
      <span v-if="eyebrow" class="eyebrow">{{ eyebrow }}</span>
      <slot name="meta" />
    </div>

    <div v-if="title || $slots.default" class="sec__main">
      <div class="sec__text">
        <h2 v-if="title" v-reveal="40" class="sec__title">{{ title }}</h2>
        <p v-if="lead" v-reveal="70" class="lead sec__lead">{{ lead }}</p>
      </div>
      <div v-if="$slots.default" v-reveal="90" class="sec__actions">
        <slot />
      </div>
    </div>
  </header>
</template>

<style scoped>
.sec {
  margin-bottom: clamp(26px, 3.4vw, 44px);
}

.sec__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 18px;
}

.sec__main {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-top: 12px;
}

.sec__text {
  max-width: 62ch;
}

.sec__title {
  font-size: var(--t-h2);
}

.sec__lead {
  margin-top: 12px;
}

.sec__actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 4px;
}

@media (max-width: 719px) {
  .sec__main {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
</style>
