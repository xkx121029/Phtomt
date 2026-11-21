<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const query = ref('')
const open = ref('')

const groups = computed(() => {
  const q = query.value.trim().toLowerCase()
  const list = q
    ? all.value.filter((f) => `${f.q} ${f.a} ${f.group}`.toLowerCase().includes(q))
    : all.value
  const map = new Map()
  for (const item of list) {
    const key = item.group || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(item)
  }
  return [...map.entries()].map(([group, items]) => ({ group, items }))
})

const matched = computed(() => groups.value.reduce((sum, g) => sum + g.items.length, 0))

function toggle(id) {
  open.value = open.value === id ? '' : id
}

onMounted(async () => {
  try {
    all.value = await content.faq()
  } catch {
    all.value = []
  }
})
</script>

<template>
  <div>
    <PageHero
      no="04 / 问答"
      eyebrow="常见问题"
      title="先看这里，多数问题已有答案"
      lead="按使用、安全、下载、开发分组。这里没有的，去仓库提 Issue 时请附上诊断导出包——比口头描述现象有用得多。"
    >
      <label class="search">
        <svg width="15" height="15" viewBox="0 0 18 18" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="5.2" stroke="currentColor" stroke-width="1.6" />
          <path d="m12.2 12.2 3 3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <input
          v-model="query"
          class="search__input"
          type="search"
          placeholder="输入关键词，例如「Root」「脱敏」「APK」"
          aria-label="搜索问题"
        />
        <button v-if="query" class="search__clear" type="button" aria-label="清空搜索" @click="query = ''">×</button>
      </label>
      <p v-if="query" class="search__hint muted small">匹配 {{ matched }} 条</p>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!all.length" class="muted">正在读取问答…</p>
        <p v-else-if="!matched" class="muted">没有匹配「{{ query }}」的问题。</p>

        <div v-for="group in groups" :key="group.group" class="group">
          <h2 v-reveal class="group__title">
            {{ group.group }}
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
                <span class="row__q">{{ item.q }}</span>
                <span class="row__icon" aria-hidden="true">
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                  </svg>
                </span>
              </button>
              <div class="row__body">
                <div class="row__inner">
                  <p class="row__a">{{ item.a }}</p>
                </div>
              </div>
            </article>
          </div>
        </div>

        <div v-reveal class="more">
          <div>
            <p class="more__title">没找到答案？</p>
            <p class="muted small">提 Issue 时附上「调试页 → 导出」生成的诊断包。</p>
          </div>
          <div class="more__actions">
            <RouterLink to="/docs/troubleshooting" class="btn btn--sm">疑难排查</RouterLink>
            <a
              class="btn btn--sm"
              href="https://github.com/xkx121029/Phtomt/issues"
              target="_blank"
              rel="noopener noreferrer"
            >提 Issue</a>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.search {
  display: flex;
  align-items: center;
  gap: 10px;
  width: min(460px, 100%);
  padding: 10px 14px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
  color: var(--ink-3);
  transition:
    border-color var(--dur-ui) ease,
    box-shadow var(--dur-ui) ease;
}

.search:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-veil);
}

.search__input {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--ink);
  font-size: var(--t-sm);
}

.search__input:focus {
  outline: none;
}

.search__input::-webkit-search-cancel-button {
  display: none;
}

.search__clear {
  border: none;
  background: transparent;
  color: var(--ink-3);
  font-size: 1rem;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
}

.search__hint {
  margin-top: 10px;
}

.group + .group {
  margin-top: clamp(30px, 4vw, 48px);
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
  grid-template-columns: minmax(0, 1fr) 22px;
  gap: 12px;
  width: 100%;
  padding: 15px 2px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.row__q {
  font-size: 0.98rem;
  font-weight: 600;
  line-height: 1.6;
  color: var(--ink);
  max-width: 60ch;
}

.row__icon {
  display: flex;
  justify-content: center;
  padding-top: 5px;
  color: var(--ink-3);
  transition: transform var(--dur-ui) var(--ease-out);
}

.row--open .row__icon {
  transform: rotate(45deg);
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

.row__a {
  padding: 2px 12px 18px 12px;
  border-left: 2px solid var(--brand-wash);
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
  max-width: 76ch;
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
  .row__head:hover .row__q {
    color: var(--brand);
  }

  .search__clear:hover {
    color: var(--ink);
  }
}

@media (min-width: 720px) {
  .more {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }
}
</style>
