<script setup>
/**
 * 节标题：一行小字 + 一句标题 + 一段导语。
 *
 * 不再给每节编 01/02/03——编号只有在内容真是序列时才成立，这里是并列的几段
 * 论述，编号是把序列的外壳套在非序列上。
 */
defineProps({
  label: { type: String, default: '' },
  title: { type: String, default: '' },
  lead: { type: String, default: '' }
})
</script>

<template>
  <header class="sec">
    <p v-if="label" v-reveal class="eyebrow sec__label">{{ label }}</p>
    <div class="sec__main">
      <div class="sec__text">
        <h2 v-if="title" v-reveal="30" class="sec__title">{{ title }}</h2>
        <p v-if="lead" v-reveal="60" class="lead sec__lead">{{ lead }}</p>
      </div>
      <div v-if="$slots.default" v-reveal="80" class="sec__actions">
        <slot />
      </div>
    </div>
  </header>
</template>

<style scoped>
.sec {
  margin-bottom: clamp(24px, 3vw, 40px);
}

.sec__main {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-top: 8px;
}

.sec__text {
  max-width: 40em;
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
  padding-bottom: 2px;
}

@media (max-width: 719px) {
  .sec__main {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
</style>
