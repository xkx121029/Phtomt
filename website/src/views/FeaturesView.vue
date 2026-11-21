<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const active = ref('全部')
const open = ref('')

/** 标题里的条数跟着数据走，别手写死——功能每加一条就漏改一次 */
const heroTitle = computed(() =>
  all.value.length ? `${all.value.length} 项能力，逐条说明它在解决什么` : '能力清单'
)

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
      no="01 / 能力"
      eyebrow="能力清单"
      :title="heroTitle"
      lead="每一条都对应代码里的一个模块。写得具体，是为了让你在装之前就知道它能做什么、不能做什么。点开任意一条看做法。"
    >
      <div class="chips" role="group" aria-label="按分组筛选">
        <button
          v-for="tag in tags"
          :key="tag"
          type="button"
          :class="['chip', { 'chip--on': active === tag }]"
          @click="active = tag; open = ''"
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
            <span class="mono group__count">{{ String(group.items.length).padStart(2, '0') }}</span>
          </h2>

          <div class="list">
            <article
              v-for="(item, index) in group.items"
              :key="item.id"
              v-reveal="index * 30"
              :class="['row', { 'row--open': open === item.id }]"
            >
              <button class="row__head" type="button" :aria-expanded="open === item.id" @click="toggle(item.id)">
                <span class="row__no mono">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="row__text">
                  <span class="row__title">{{ item.title }}</span>
                  <span class="row__summary">{{ item.summary }}</span>
                </span>
                <span class="row__caret" aria-hidden="true">
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <path d="M4 6.5 8 10.5l4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </span>
              </button>
              <div class="row__body">
                <div class="row__inner">
                  <p class="row__detail">{{ item.detail }}</p>
                </div>
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
  gap: 6px;
}

.chip {
  padding: 5px 12px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-pill);
  background: transparent;
  font-size: var(--t-sm);
  color: var(--ink-2);
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.chip--on {
  background: var(--ink);
  border-color: var(--ink);
  color: var(--paper);
}

[data-theme="dark"] .chip--on {
  background: var(--brand);
  border-color: var(--brand);
  color: var(--brand-ink);
}

@media (hover: hover) and (pointer: fine) {
  .chip:not(.chip--on):hover {
    border-color: var(--ink);
    color: var(--ink);
  }
}

.group + .group {
  margin-top: clamp(32px, 4vw, 52px);
}

.group__title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 2px solid var(--ink);
  font-size: var(--t-h2);
}

.group__count {
  font-size: var(--t-sm);
  color: var(--ink-3);
  letter-spacing: 0.06em;
}

.list {
  border-bottom: 1px solid var(--line);
}

.row {
  border-top: 1px solid var(--line);
}

.row__head {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 20px;
  gap: 12px;
  width: 100%;
  padding: 14px 2px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.row__no {
  font-size: var(--t-xs);
  color: var(--ink-3);
  padding-top: 3px;
}

.row__text {
  display: grid;
  gap: 3px;
}

.row__title {
  font-size: 0.98rem;
  font-weight: 600;
  color: var(--ink);
}

.row__summary {
  font-size: var(--t-sm);
  line-height: 1.75;
  color: var(--ink-2);
}

.row__caret {
  display: flex;
  justify-content: center;
  padding-top: 5px;
  color: var(--ink-3);
  transition: transform var(--dur-ui) var(--ease-out);
}

.row--open .row__caret {
  transform: rotate(180deg);
}

/* 折叠用 grid-template-rows 0fr → 1fr：高度自动、可过渡，不用测高 */
.row__body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--dur-ui) var(--ease-out);
}

.row--open .row__body {
  grid-template-rows: 1fr;
}

.row__inner {
  overflow: hidden;
}

.row__detail {
  padding: 2px 32px 18px 40px;
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
  max-width: 78ch;
  border-left: 2px solid var(--brand-wash);
  margin-left: 12px;
}

@media (hover: hover) and (pointer: fine) {
  .row__head:hover .row__title {
    color: var(--brand);
  }
}

@media (min-width: 720px) {
  .row__head {
    grid-template-columns: 36px minmax(0, 11.2rem) minmax(0, 1fr) 22px;
    align-items: baseline;
    gap: 18px;
    padding: 15px 2px;
  }

  .row__text {
    display: contents;
  }

  .row__title {
    font-size: 0.95rem;
  }

  .row__detail {
    padding-left: 56px;
    margin-left: 18px;
  }
}

/* 栅格子项默认 min-width:auto，一列长文本就会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.row__text > * {
  min-width: 0;
}
</style>
