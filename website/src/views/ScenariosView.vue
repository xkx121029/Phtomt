<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const active = ref('')
const open = ref('')

const categories = computed(() => [...new Set(all.value.map((s) => s.category).filter(Boolean))])

const groups = computed(() => {
  const list = active.value ? all.value.filter((s) => s.category === active.value) : all.value
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
      label="场景示例"
      title="这些事，说一句话就够了"
      lead="每一条都能直接粘进输入框，后面写着它实际会怎么走。写出来是为了让你在装之前就知道边界：哪一步靠无障碍、哪一步要 shell、哪一步会停下来问你。"
    >
      <div class="filter">
        <button type="button" :class="['filter__i', { 'filter__i--on': !active }]" @click="active = ''; open = ''">
          全部
        </button>
        <button
          v-for="cat in categories"
          :key="cat"
          type="button"
          :class="['filter__i', { 'filter__i--on': active === cat }]"
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
          <h2 v-reveal class="group__t">{{ group.category }}</h2>

          <div class="list">
            <article
              v-for="(item, index) in group.items"
              :key="item.id"
              v-reveal="index * 25"
              :class="['row', { 'row--open': open === item.id }]"
            >
              <button class="row__head" type="button" :aria-expanded="open === item.id" @click="toggle(item.id)">
                <span class="row__title">
                  {{ item.title }}
                  <span v-if="item.channel" class="tag row__ch">{{ item.channel }}</span>
                </span>
                <span class="row__goal">{{ item.goal }}</span>
                <span class="row__mark" aria-hidden="true"></span>
              </button>

              <div class="row__body">
                <div class="row__inner">
                  <ol class="steps">
                    <li v-for="(step, i) in item.steps || []" :key="i" class="steps__i">
                      <span class="mono steps__n">{{ String(i + 1).padStart(2, '0') }}</span>
                      <span class="steps__t">
                        <strong>{{ step.title }}</strong>
                        <span>{{ step.detail }}</span>
                      </span>
                    </li>
                  </ol>
                  <p v-if="item.note" class="note">{{ item.note }}</p>
                </div>
              </div>
            </article>
          </div>
        </div>

        <div class="more">
          <div>
            <p class="more__t">没找到你要的那件事？</p>
            <p class="muted small">把目标写成一句人话就行，不必迁就固定句式。</p>
          </div>
          <div class="more__a">
            <RouterLink to="/docs/getting-started" class="btn btn--sm">快速开始</RouterLink>
            <RouterLink to="/docs/execution-channels" class="btn btn--sm">执行通道</RouterLink>
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
  gap: 6px 14px;
  width: 100%;
  padding: 15px 2px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.row__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
  font-size: 1rem;
  font-weight: 600;
  color: var(--ink);
}

.row__ch {
  font-weight: 500;
}

.row__goal {
  grid-column: 1;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-2);
}

.row__mark {
  grid-row: 1 / span 2;
  grid-column: 2;
  align-self: start;
  margin-top: 8px;
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

.steps {
  list-style: none;
  margin: 0;
  padding: 4px 0 14px;
  display: grid;
  gap: 0;
  max-width: 46em;
}

.steps__i {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 12px;
  padding: 9px 0;
  border-top: 1px solid var(--line-soft);
}

.steps__n {
  font-size: var(--t-xs);
  color: var(--signal);
  padding-top: 3px;
}

.steps__t {
  display: grid;
  gap: 2px;
  font-size: var(--t-sm);
  line-height: 1.75;
  color: var(--ink-2);
}

.steps__t strong {
  color: var(--ink);
  font-weight: 600;
}

.note {
  margin-bottom: 18px;
  padding: 10px 13px;
  border-left: 2px solid var(--warn);
  background: var(--warn-wash);
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 46em;
}

.more {
  display: grid;
  gap: 16px;
  margin-top: clamp(28px, 3.6vw, 44px);
  padding-top: 18px;
  border-top: 1px solid var(--line);
}

.more__t {
  font-weight: 600;
}

.more__a {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

@media (hover: hover) and (pointer: fine) {
  .row__head:hover .row__title {
    color: var(--signal);
  }
}

@media (min-width: 720px) {
  .more {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }
}

/* 栅格子项默认 min-width:auto，一列长文本会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.row__head > *,
.more > *,
.steps > * {
  min-width: 0;
}
</style>
