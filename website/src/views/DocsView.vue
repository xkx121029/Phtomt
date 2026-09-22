<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const docs = ref([])
const query = ref('')

const groups = computed(() => {
  const q = query.value.trim().toLowerCase()
  const list = q
    ? docs.value.filter((d) =>
        [d.title, d.summary, d.group, d.slug].filter(Boolean).join(' ').toLowerCase().includes(q)
      )
    : docs.value
  const map = new Map()
  for (const doc of list) {
    const key = doc.group || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(doc)
  }
  return [...map.entries()].map(([group, items]) => ({ group, items }))
})

const total = computed(() => docs.value.length)

onMounted(async () => {
  try {
    docs.value = await content.docs()
  } catch {
    docs.value = []
  }
})
</script>

<template>
  <div>
    <PageHero
      eyebrow="文档中心"
      :title="total ? `${total} 篇文档，从装好到改源码` : '文档中心'"
      lead="按「入门 → 进阶 → 支持 → 合规」组织。每篇都写清了设计动机——为什么这么做，以及不这么做会踩什么坑。"
    >
      <div class="search">
        <svg width="16" height="16" viewBox="0 0 18 18" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="5.2" stroke="currentColor" stroke-width="1.6" />
          <path d="m12.2 12.2 3 3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <input v-model="query" class="search__input" type="search" placeholder="搜索文档标题或摘要…" aria-label="搜索文档" />
        <button v-if="query" class="search__clear" type="button" aria-label="清空搜索" @click="query = ''">×</button>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!docs.length" class="muted">正在读取文档索引…</p>
        <p v-else-if="!groups.length" class="muted">没有匹配「{{ query }}」的文档。</p>

        <div v-for="group in groups" :key="group.group" class="group">
          <h2 v-reveal class="group__title">
            {{ group.group }}
            <span class="muted mono group__count">{{ group.items.length }}</span>
          </h2>

          <div class="grid">
            <RouterLink
              v-for="(doc, index) in group.items"
              :key="doc.slug"
              v-reveal="index * 50"
              :to="`/docs/${doc.slug}`"
              class="card doc"
            >
              <h3 class="doc__title">{{ doc.title }}</h3>
              <p v-if="doc.summary" class="doc__summary">{{ doc.summary }}</p>
              <span class="doc__more">
                阅读
                <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M3.5 8h9m0 0L9 4.5M12.5 8 9 11.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
            </RouterLink>
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
  width: min(420px, 100%);
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
  margin-top: clamp(36px, 5vw, 56px);
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
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
}

.doc {
  display: grid;
  gap: 8px;
  align-content: start;
  transition:
    transform var(--dur-press) var(--ease-out),
    border-color var(--dur-ui) ease;
}

.doc:active {
  transform: scale(0.985);
}

@media (hover: hover) and (pointer: fine) {
  .doc:hover {
    border-color: var(--line-2);
  }

  .doc:hover .doc__more svg {
    transform: translateX(3px);
  }
}

.doc__title {
  font-size: 1.02rem;
  font-weight: 600;
}

.doc__summary {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.75;
}

.doc__more {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 4px;
  font-size: var(--t-xs);
  color: var(--brand);
}

.doc__more svg {
  transition: transform var(--dur-ui) var(--ease-out);
}
</style>
