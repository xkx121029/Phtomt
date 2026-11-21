<script setup>
/**
 * StepRail —— 编号步骤。
 *
 * 步骤之间用细线连起来，序号在左侧独立成列：手机上扫读时，视线沿左列往下走，
 * 不会被正文的长段落带偏。
 */
defineProps({
  items: { type: Array, required: true },
  dense: { type: Boolean, default: false }
})
</script>

<template>
  <ol :class="['rail', { 'rail--dense': dense }]">
    <li v-for="(item, index) in items" :key="item.title || index" v-reveal="index * 50" class="rail__item">
      <span class="rail__no mono">{{ String(index + 1).padStart(2, '0') }}</span>
      <div class="rail__body">
        <h3 class="rail__title">{{ item.title }}</h3>
        <p v-if="item.line" class="rail__line">{{ item.line }}</p>
        <p v-if="item.desc" class="rail__desc">{{ item.desc }}</p>
        <p v-if="item.detail" class="rail__desc">{{ item.detail }}</p>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.rail {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--line);
  /* 作为 grid 子项时默认 min-width:auto 会让整列按 min-content 撑开，
     375px 下会把页面顶出横向滚动条。显式归零后由父级的 1fr 决定宽度。 */
  min-width: 0;
}

.rail__item {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 16px;
  padding: 20px 0;
  border-bottom: 1px solid var(--line);
}

.rail__no {
  font-size: var(--t-xs);
  color: var(--amber);
  padding-top: 3px;
  letter-spacing: 0.06em;
}

.rail__title {
  font-size: var(--t-h3);
}

.rail__line {
  margin-top: 6px;
  font-size: var(--t-sm);
  color: var(--ink);
  font-weight: 500;
}

.rail__desc {
  margin-top: 6px;
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 68ch;
}

.rail--dense .rail__item {
  padding: 14px 0;
}

@media (min-width: 720px) {
  .rail__item {
    grid-template-columns: 64px minmax(0, 1fr);
    gap: 24px;
    padding: 24px 0;
  }
}
</style>
