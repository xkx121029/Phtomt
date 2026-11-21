<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const active = ref('')
const open = ref('')

/** 标题里的条数跟着数据走，别手写死——功能每加一条就漏改一次 */
const heroTitle = computed(() =>
  all.value.length ? `${all.value.length} 项能力，逐条说明它在解决什么` : '能力清单'
)

const tags = computed(() => [...new Set(all.value.map((f) => f.tag).filter(Boolean))])

const groups = computed(() => {
  const list = active.value ? all.value.filter((f) => f.tag === active.value) : all.value
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
      label="能力清单"
      :title="heroTitle"
      lead="每一条都对应代码里的一个模块。写得具体，是为了让你在装之前就知道它能做什么、不能做什么。点开任意一条看做法。"
    >
      <div class="filter">
        <button type="button" :class="['filter__i', { 'filter__i--on': !active }]" @click="active = ''; open = ''">
          全部
        </button>
        <button
          v-for="tag in tags"
          :key="tag"
          type="button"
          :class="['filter__i', { 'filter__i--on': active === tag }]"
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
          <h2 v-reveal class="group__t">{{ group.tag }}</h2>

          <div class="list">
            <article
              v-for="(item, index) in group.items"
              :key="item.id"
              v-reveal="index * 25"
              :class="['row', { 'row--open': open === item.id }]"
            >
              <button class="row__head" type="button" :aria-expanded="open === item.id" @click="toggle(item.id)">
                <span class="row__title">{{ item.title }}</span>
                <span class="row__sum">{{ item.summary }}</span>
                <span class="row__mark" aria-hidden="true"></span>
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
.filter {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
}

.filter__i {
  min-height: 44px;
  padding: 6px 2px;
  border: none;
  border-bottom: 1px solid transparent;
  background: transparent;
  font-size: var(--t-sm);
  color: var(--ink-2);
  cursor: pointer;
  transition:
    color var(--dur-ui) ease,
    border-color var(--dur-ui) ease;
}

.filter__i--on {
  color: var(--ink);
  border-bottom-color: var(--signal);
}

@media (hover: hover) and (pointer: fine) {
  .filter__i:not(.filter__i--on):hover {
    color: var(--ink);
  }
}

.group + .group {
  margin-top: clamp(30px, 3.6vw, 48px);
}

.group__t {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--ink-3);
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line);
  letter-spacing: 0.02em;
}

.list {
  border-bottom: 1px solid var(--line-soft);
}

.row {
  border-top: 1px solid var(--line-soft);
}

.row__head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 22px;
  gap: 8px 14px;
  width: 100%;
  padding: 14px 2px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.row__title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--ink);
}

.row__sum {
  grid-column: 1;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-2);
}

/* 展开态只用一个小方块旋转表示，不用加号箭头 */
.row__mark {
  grid-row: 1 / span 2;
  grid-column: 2;
  align-self: start;
  margin-top: 7px;
  width: 7px;
  height: 7px;
  border-right: 1.5px solid var(--ink-3);
  border-bottom: 1.5px solid var(--ink-3);
  transform: rotate(45deg);
  transition:
    transform var(--dur-ui) var(--ease-out),
    border-color var(--dur-ui) ease;
}

.row--open .row__mark {
  transform: rotate(225deg);
  border-color: var(--signal);
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

.row__detail {
  padding: 2px 0 18px 14px;
  border-left: 1px solid var(--line);
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
  max-width: 44em;
}

@media (hover: hover) and (pointer: fine) {
  .row__head:hover .row__title {
    color: var(--signal);
  }
}

@media (min-width: 760px) {
  .row__head {
    grid-template-columns: minmax(0, 16rem) minmax(0, 1fr) 22px;
    align-items: baseline;
    gap: 20px;
    padding: 13px 2px;
  }

  .row__sum {
    grid-column: 2;
  }

  .row__mark {
    grid-row: 1;
    grid-column: 3;
    margin-top: 0;
    align-self: center;
  }

  .row__detail {
    padding: 2px 6rem 18px 2px;
    border-left: none;
    max-width: none;
  }
}

/* 栅格子项默认 min-width:auto，一列长文本会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.row__head > *,
.list > * {
  min-width: 0;
}
</style>
