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
      eyebrow="常见问题"
      title="先看这里，多数问题已有答案"
      lead="按使用、安全、下载、开发分组。如果这里没有你要的答案，去仓库提 Issue 时请附上诊断导出包。"
    >
      <div class="search">
        <svg width="16" height="16" viewBox="0 0 18 18" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="5.2" stroke="currentColor" stroke-width="1.6" />
          <path d="m12.2 12.2 3 3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <input v-model="query" class="search__input" type="search" placeholder="输入关键词，例如「Root」「脱敏」「APK」" aria-label="搜索问题" />
        <button v-if="query" class="search__clear" type="button" aria-label="清空搜索" @click="query = ''">×</button>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!all.length" class="muted">正在读取问答…</p>
        <p v-else-if="!matched" class="muted">没有匹配「{{ query }}」的问题。</p>

        <div v-for="group in groups" :key="group.group" class="group">
          <h2 v-reveal class="group__title">
            {{ group.group }}
            <span class="muted mono group__count">{{ group.items.length }}</span>
          </h2>

          <div class="list">
            <article
              v-for="(item, index) in group.items"
              :key="item.id"
              v-reveal="index * 40"
              :class="['card', 'qa', { 'qa--open': open === item.id }]"
            >
              <button class="qa__q" type="button" :aria-expanded="open === item.id" @click="toggle(item.id)">
                <span class="qa__text">{{ item.q }}</span>
                <span class="qa__icon" aria-hidden="true">
                  <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
                    <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                  </svg>
                </span>
              </button>
              <div class="qa__body">
                <p class="qa__a">{{ item.a }}</p>
              </div>
            </article>
          </div>
        </div>

        <div v-reveal class="more">
          <div>
            <p class="more__title">没找到答案？</p>
            <p class="muted small">提 Issue 时附上「调试页 → 导出」生成的诊断包，比描述现象有用得多。</p>
          </div>
          <div class="more__actions">
            <RouterLink to="/docs/troubleshooting" class="btn btn--sm">疑难排查</RouterLink>
            <a
              v-if="all.length"
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
  width: min(440px, 100%);
  padding: 9px 14px;
  border: 1px solid var(--line);
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
  border: none;
  background: transparent;
  outline: none;
  font-size: var(--t-sm);
  color: var(--ink);
  min-width: 0;
}

.search__input::-webkit-search-cancel-button {
  display: none;
}

.search__clear {
  border: none;
  background: transparent;
  color: var(--ink-3);
  font-size: 1.1rem;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
}

.group + .group {
  margin-top: clamp(32px, 4vw, 48px);
}

.group__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
  font-size: var(--t-h3);
}

.group__count {
  font-size: var(--t-xs);
  font-weight: 400;
}

.list {
  display: grid;
  gap: 10px;
  max-width: 880px;
}

.qa {
  padding: 0;
  overflow: hidden;
}

.qa__q {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 17px 20px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .qa__q:hover {
    background: var(--paper-sunken);
  }
}

.qa__text {
  font-size: 1rem;
  font-weight: 500;
  letter-spacing: -0.012em;
}

.qa__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: 50%;
  background: var(--paper-sunken);
  color: var(--ink-3);
  transition:
    transform var(--dur-enter) var(--ease-out),
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.qa--open .qa__icon {
  transform: rotate(135deg);
  background: var(--brand-wash);
  color: var(--brand);
}

.qa__body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--dur-enter) var(--ease-out);
}

.qa--open .qa__body {
  grid-template-rows: 1fr;
}

.qa__a {
  overflow: hidden;
  padding: 0 20px 20px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.85;
}

.more {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: clamp(36px, 5vw, 56px);
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--paper-sunken);
}

.more__title {
  font-weight: 600;
  margin-bottom: 4px;
}

.more__actions {
  display: flex;
  gap: 8px;
}

@media (prefers-reduced-motion: reduce) {
  .qa__body,
  .qa__icon {
    transition: none;
  }
}
</style>
