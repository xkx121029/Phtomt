<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const active = ref('全部')
const open = ref('')

const tags = computed(() => ['全部', ...new Set(all.value.map((f) => f.tag).filter(Boolean))])

const groups = computed(() => {
  const list = active.value === '全部' ? all.value : all.value.filter((f) => f.tag === active.value)
  const map = new Map()
  for (const item of list) {
    const key = item.tag || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(item)
  }
  return [...map.entries()].map(([tag, items]) => ({ tag, items }))
})

function toggle(id) {
  open.value = open.value === id ? '' : id
}

onMounted(async () => {
  try {
    all.value = await content.features()
  } catch {
    all.value = []
  }
})
</script>

<template>
  <div>
    <PageHero
      eyebrow="能力清单"
      title="18 项能力，逐条说明它在解决什么"
      lead="每一条都对应真实代码里的一个模块。写得具体，是为了让你在装之前就知道它能做什么、不能做什么。"
    >
      <div class="chips">
        <button
          v-for="tag in tags"
          :key="tag"
          type="button"
          :class="['chip', { 'chip--on': active === tag }]"
          @click="active = tag"
        >
          {{ tag }}
        </button>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!all.length" class="muted">正在读取能力清单…</p>

        <div v-for="group in groups" :key="group.tag" class="group">
          <h2 v-reveal class="group__title">
            {{ group.tag }}
            <span class="muted mono group__count">{{ group.items.length }}</span>
          </h2>

          <div class="grid">
            <article
              v-for="(item, index) in group.items"
              :key="item.id"
              v-reveal="index * 40"
              :class="['card', 'item', { 'item--open': open === item.id }]"
            >
              <button class="item__head" type="button" :aria-expanded="open === item.id" @click="toggle(item.id)">
                <span class="item__text">
                  <span class="item__title">{{ item.title }}</span>
                  <span class="item__summary">{{ item.summary }}</span>
                </span>
                <svg class="item__caret" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M4 6.5 8 10.5l4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>
              <div class="item__body">
                <p class="item__detail">{{ item.detail }}</p>
              </div>
            </article>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  padding: 6px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
  color: var(--ink-2);
  font-size: var(--t-xs);
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.chip:active {
  transform: scale(0.96);
}

.chip--on {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}

[data-theme="dark"] .chip--on {
  color: var(--brand-ink);
}

@media (hover: hover) and (pointer: fine) {
  .chip:hover {
    border-color: var(--line-2);
    color: var(--ink);
  }

  .chip--on:hover {
    color: #fff;
  }

  [data-theme="dark"] .chip--on:hover {
    color: var(--brand-ink);
  }
}

.group + .group {
  margin-top: clamp(40px, 5vw, 64px);
}

.group__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 18px;
  font-size: var(--t-h3);
}

.group__count {
  font-size: var(--t-xs);
  font-weight: 400;
}

.grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
}

.item {
  padding: 0;
  overflow: hidden;
}

.item__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 20px 22px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

/* 桌面端悬停即展开，省掉一次点击；移动端没有 hover，仍走按钮的点击切换（aria-expanded 照旧）。
   不再给头部叠悬停底色：卡片已经是亚克力，再加一层 --paper-sunken 会把材质糊掉，
   展开本身就是足够的反馈。 */
@media (hover: hover) and (pointer: fine) {
  .item:hover .item__body {
    grid-template-rows: 1fr;
  }

  .item:hover .item__caret {
    transform: rotate(180deg);
  }
}

.item__text {
  display: grid;
  gap: 6px;
}

.item__title {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: -0.018em;
}

.item__summary {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.72;
}

.item__caret {
  flex: none;
  margin-top: 4px;
  color: var(--ink-3);
  transition: transform var(--dur-ui) var(--ease-out);
}

.item--open .item__caret {
  transform: rotate(180deg);
}

/* 展开用 grid-template-rows 过渡：高度未知时也能平滑，且可被打断 */
.item__body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--dur-enter) var(--ease-out);
}

.item--open .item__body {
  grid-template-rows: 1fr;
}

/* overflow: hidden 同时把 grid 项的自动最小尺寸压成 0，否则 0fr 收不拢 */
.item__detail {
  overflow: hidden;
  padding: 0 22px 22px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.8;
}

@media (prefers-reduced-motion: reduce) {
  .item__body {
    transition: none;
  }

  .item__caret {
    transition: none;
  }
}
</style>
