<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import MarkdownBody from '../components/MarkdownBody.vue'
import { content } from '../api/client.js'

const route = useRoute()
const doc = ref(null)
const index = ref([])
const headings = ref([])
const active = ref('')
const loading = ref(true)

const siblings = computed(() => {
  const list = index.value
  const i = list.findIndex((d) => d.slug === route.params.slug)
  return {
    prev: i > 0 ? list[i - 1] : null,
    next: i >= 0 && i < list.length - 1 ? list[i + 1] : null
  }
})

let observer = null

function watchHeadings() {
  observer?.disconnect()
  if (!headings.value.length) return
  observer = new IntersectionObserver(
    (entries) => {
      const hit = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]
      if (hit) active.value = hit.target.id
    },
    { rootMargin: '-88px 0px -70% 0px', threshold: [0, 1] }
  )
  for (const h of headings.value) {
    const el = document.getElementById(h.id)
    if (el) observer.observe(el)
  }
}

async function load(slug) {
  loading.value = true
  headings.value = []
  try {
    const [detail, list] = await Promise.all([content.doc(slug), content.docs()])
    doc.value = detail || null
    index.value = list || []
  } catch {
    doc.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => load(route.params.slug))
watch(() => route.params.slug, (v) => v && load(v))
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div>
    <section class="head">
      <div class="page">
        <RouterLink to="/docs" class="back">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          文档中心
        </RouterLink>

        <p v-if="loading" class="muted head__state">正在读取…</p>
        <p v-else-if="!doc" class="muted head__state">未找到文档「{{ route.params.slug }}」。</p>

        <template v-else>
          <p v-if="doc.group" class="eyebrow head__group">{{ doc.group }}</p>
          <h1 class="h1 head__title">{{ doc.title }}</h1>
          <p v-if="doc.summary" class="lead head__lead">{{ doc.summary }}</p>
        </template>
        <hr class="head__rule" />
      </div>
    </section>

    <section v-if="doc" class="section section--tight">
      <div class="page layout">
        <article class="content">
          <MarkdownBody :source="doc.body || ''" anchor-headings @rendered="(h) => { headings = h; watchHeadings() }" />
        </article>

        <aside v-if="headings.length" class="side">
          <p class="side__label eyebrow">本页目录</p>
          <nav class="toc">
            <a
              v-for="h in headings"
              :key="h.id"
              :href="`#${h.id}`"
              :data-level="h.level"
              :class="{ 'is-active': active === h.id }"
              @click="active = h.id"
            >{{ h.text }}</a>
          </nav>
        </aside>
      </div>

      <div class="page">
        <nav class="pager" aria-label="相邻文档">
          <RouterLink v-if="siblings.prev" :to="`/docs/${siblings.prev.slug}`" class="pager__card">
            <span class="muted small">← 上一篇</span>
            <span class="pager__title">{{ siblings.prev.title }}</span>
          </RouterLink>
          <span v-else class="pager__spacer" />

          <RouterLink v-if="siblings.next" :to="`/docs/${siblings.next.slug}`" class="pager__card pager__card--right">
            <span class="muted small">下一篇 →</span>
            <span class="pager__title">{{ siblings.next.title }}</span>
          </RouterLink>
        </nav>
      </div>
    </section>
  </div>
</template>

<style scoped>
.head {
  padding-top: clamp(28px, 4vw, 48px);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-sm);
  color: var(--ink-3);
  transition: color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .back:hover {
    color: var(--ink);
  }
}

.head__state {
  margin-top: 20px;
}

.head__group {
  margin-top: 26px;
}

.head__title {
  margin-top: 8px;
  max-width: 28ch;
}

.head__lead {
  margin-top: 14px;
  max-width: 56ch;
}

.head__rule {
  height: 2px;
  border: none;
  margin: 28px 0 0;
  background: var(--ink);
  opacity: 0.86;
}

.layout {
  display: grid;
  gap: clamp(28px, 4vw, 52px);
}

.content {
  min-width: 0;
}

.side {
  display: none;
}

.side__label {
  margin-bottom: 10px;
}

.pager {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: clamp(32px, 4vw, 52px);
  padding-top: 22px;
  border-top: 1px solid var(--line);
}

.pager__card {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel);
  transition: border-color var(--dur-ui) ease;
}

.pager__card--right {
  text-align: right;
}

.pager__title {
  font-size: var(--t-sm);
  font-weight: 500;
}

.pager__spacer {
  display: block;
}

@media (hover: hover) and (pointer: fine) {
  .pager__card:hover {
    border-color: var(--line-strong);
  }
}

@media (min-width: 720px) {
  .side {
    display: block;
  }
}

@media (min-width: 1024px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 196px;
    align-items: start;
  }

  .side {
    position: sticky;
    top: 84px;
    max-height: calc(100vh - 120px);
    overflow-y: auto;
  }
}


/* 栅格子项默认 min-width:auto，一列长文本会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.layout > * {
  min-width: 0;
}
</style>
