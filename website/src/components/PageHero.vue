<script setup>
/**
 * PageHero —— 内页刊头。
 *
 * 五个内页共用它：编号 + 小标签 + 标题 + 导语 + 动作区。下方一条 2px 粗线收口，
 * 把「刊头」和「正文」明确切开，读者一眼知道从哪里开始读。
 */
defineProps({
  no: { type: String, default: '' },
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  lead: { type: String, default: '' }
})
</script>

<template>
  <header class="pherom">
    <div class="page">
      <div class="pherom__meta">
        <span v-if="no" class="no">{{ no }}</span>
        <span v-if="eyebrow" class="eyebrow">{{ eyebrow }}</span>
      </div>

      <h1 v-reveal="30" class="h1 pherom__title">{{ title }}</h1>
      <p v-if="lead" v-reveal="60" class="lead pherom__lead">{{ lead }}</p>

      <div v-if="$slots.default" v-reveal="90" class="pherom__extra">
        <slot />
      </div>
    </div>
    <div class="page">
      <hr class="pherom__rule" />
    </div>
  </header>
</template>

<style scoped>
.pherom {
  padding-top: clamp(32px, 5vw, 60px);
}

.pherom__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 18px;
}

.pherom__title {
  margin-top: 14px;
  max-width: 22ch;
}

.pherom__lead {
  margin-top: 16px;
  max-width: 56ch;
}

.pherom__extra {
  margin-top: 26px;
}

.pherom__rule {
  height: 2px;
  border: none;
  margin: 34px 0 0;
  background: var(--ink);
  opacity: 0.86;
}
</style>
