<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const active = ref('全部')
const open = ref('')

const categories = computed(() => ['全部', ...new Set(all.value.map((s) => s.category).filter(Boolean))])

const groups = computed(() => {
  const list = active.value === '全部' ? all.value : all.value.filter((s) => s.category === active.value)
  const map = new Map()
  for (const item of list) {
    const key = item.category || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(item)
  }
  return [...map.entries()].map(([category, items]) => ({ category, items }))
})

function toggle(id) {
  open.value = open.value === id ? '' : id
}

onMounted(async () => {
  try {
    all.value = await content.scenarios()
  } catch {
    all.value = []
  }
})
</script>

<template>
  <div>
    <PageHero
      no="03 / 场景"
      eyebrow="场景示例"
      title="这些事，说一句话就够了"
      lead="下面每一条都可以直接粘进输入框，后面写着它实际会怎么走。写出来是为了让你在装之前就知道边界在哪：哪一步靠无障碍、哪一步要 shell、哪一步会停下来问你。"
    >
      <div class="chips" role="group" aria-label="按分类筛选">
        <button
          v-for="cat in categories"
          :key="cat"
          type="button"
          :class="['chip', { 'chip--on': active === cat }]"
          @click="active = cat; open = ''"
        >
          {{ cat }}
        </button>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!all.length" class="muted">正在读取场景…</p>

        <div v-for="group in groups" :key="group.category" class="group">
          <h2 v-reveal class="group__title">
            {{ group.category }}
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
                <span class="row__text">
                  <span class="row__title">
                    {{ item.title }}
                    <span v-if="item.channel" class="tag row__channel">{{ item.channel }}</span>
                  </span>
                  <span class="row__goal">{{ item.goal }}</span>
                </span>
                <span class="row__caret" aria-hidden="true">
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <path d="M4 6.5 8 10.5l4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </span>
              </button>

              <div class="row__body">
                <div class="row__inner">
                  <ol class="steps">
                    <li v-for="(step, i) in item.steps || []" :key="i" class="steps__item">
                      <span class="steps__index mono">{{ String(i + 1).padStart(2, '0') }}</span>
                      <span class="steps__text">
                        <strong class="steps__name">{{ step.title }}</strong>
                        <span class="steps__detail">{{ step.detail }}</span>
                      </span>
                    </li>
                  </ol>
                  <p v-if="item.note" class="note">{{ item.note }}</p>
                </div>
              </div>
            </article>
          </div>
        </div>

        <div v-reveal class="more">
          <div>
            <p class="more__title">没找到你要的那件事？</p>
            <p class="muted small">把目标写成一句人话就行，不必迁就固定句式。执行通道怎么选、模型怎么配，见文档中心。</p>
          </div>
          <div class="more__actions">
            <RouterLink to="/docs/getting-started" class="btn btn--sm">快速开始</RouterLink>
            <RouterLink to="/docs/execution-channels" class="btn btn--sm">执行通道</RouterLink>
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
  grid-template-columns: minmax(0, 1fr) 20px;
  gap: 12px;
  width: 100%;
  padding: 15px 2px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.row__text {
  display: grid;
  gap: 4px;
}

.row__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 0.98rem;
  font-weight: 600;
  color: var(--ink);
}

.row__channel {
  font-weight: 500;
}

.row__goal {
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

.steps {
  list-style: none;
  margin: 0 0 16px;
  padding: 6px 0 0 2px;
  display: grid;
  gap: 0;
  max-width: 78ch;
}

.steps__item {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 12px;
  padding: 9px 0;
  border-top: 1px solid var(--line-3);
}

.steps__index {
  font-size: var(--t-xs);
  color: var(--amber);
  padding-top: 2px;
}

.steps__text {
  display: grid;
  gap: 2px;
  font-size: var(--t-sm);
  line-height: 1.75;
}

.steps__name {
  font-weight: 600;
  color: var(--ink);
}

.steps__detail {
  color: var(--ink-2);
}

.note {
  margin-bottom: 18px;
  padding: 10px 14px;
  border-left: 2px solid var(--amber);
  background: var(--amber-wash);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 78ch;
}

.more {
  display: grid;
  gap: 16px;
  margin-top: clamp(28px, 4vw, 44px);
  padding-top: 20px;
  border-top: 1px solid var(--line-2);
}

.more__title {
  font-weight: 600;
}

.more__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

@media (hover: hover) and (pointer: fine) {
  .row__head:hover .row__title {
    color: var(--brand);
  }
}

@media (min-width: 720px) {
  .more {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }
}

/* 栅格子项默认 min-width:auto，一列长文本就会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.more > *,
.row__text > * {
  min-width: 0;
}
</style>
