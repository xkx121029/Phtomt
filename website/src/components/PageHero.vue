<script setup>
/**
 * 内页刊头：一行小字 + 标题 + 导语 + 动作区，下方一条粗线收口。
 * 不高喊、不加编号——页名本身已经是导航状态。
 */
defineProps({
  label: { type: String, default: '' },
  title: { type: String, required: true },
  lead: { type: String, default: '' }
})
</script>

<template>
  <header class="ph">
    <div class="page">
      <p v-if="label" v-reveal class="eyebrow">{{ label }}</p>
      <h1 v-reveal="20" class="h1 ph__title">{{ title }}</h1>
      <p v-if="lead" v-reveal="50" class="lead ph__lead">{{ lead }}</p>
      <div v-if="$slots.default" v-reveal="80" class="ph__extra">
        <slot />
      </div>
    </div>
    <div class="page"><hr class="ph__rule" /></div>
  </header>
</template>

<style scoped>
.ph {
  padding-top: clamp(30px, 4.4vw, 56px);
}

.ph__title {
  margin-top: 10px;
  max-width: 20em;
}

.ph__lead {
  margin-top: 14px;
  max-width: 34em;
}

.ph__extra {
  margin-top: 24px;
}

.ph__rule {
  height: 3px;
  border: none;
  margin: 30px 0 0;
  background: var(--ink);
}
</style>
