<script setup>
defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  lead: { type: String, default: '' }
})
</script>

<template>
  <header class="hero">
    <div class="page">
      <p v-if="eyebrow" v-reveal class="eyebrow">{{ eyebrow }}</p>
      <h1 v-reveal="40" class="h1 hero__title">{{ title }}</h1>
      <p v-if="lead" v-reveal="80" class="lead hero__lead">{{ lead }}</p>
      <div v-if="$slots.default" v-reveal="120" class="hero__extra">
        <slot />
      </div>
    </div>
  </header>
</template>

<style scoped>
.hero {
  padding-block: clamp(56px, 7vw, 88px) clamp(28px, 4vw, 40px);
  border-bottom: 1px solid var(--line);
}

.hero__title {
  margin-top: 14px;
  max-width: 22ch;
}

.hero__lead {
  margin-top: 18px;
  max-width: 60ch;
}

.hero__extra {
  margin-top: 26px;
}
</style>
