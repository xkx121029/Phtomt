<script setup>
/**
 * 步骤轨：真序列才编号（观察、标注、决策、执行、验证是有先后的）。
 *
 * 序号用等宽小字挂在左侧，正文右对齐到同一条基线；步骤之间只用一条细线，
 * 不做卡片、不做连接的圆点。
 */
defineProps({
  items: { type: Array, required: true },
  dense: { type: Boolean, default: false }
})
</script>

<template>
  <ol :class="['rail', { 'rail--dense': dense }]">
    <li v-for="(item, index) in items" :key="item.title || index" v-reveal="index * 40" class="rail__item">
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
  min-width: 0;
}

.rail__item {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 16px;
  padding: 20px 0;
  border-bottom: 1px solid var(--line-soft);
}

.rail__no {
  font-size: var(--t-xs);
  color: var(--signal);
  padding-top: 3px;
}

.rail__title {
  font-size: var(--t-h3);
  font-weight: 600;
}

.rail__line {
  margin-top: 5px;
  font-size: var(--t-sm);
  color: var(--ink);
}

.rail__desc {
  margin-top: 6px;
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 40em;
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

  .rail__desc {
    max-width: 46em;
  }
}
</style>
