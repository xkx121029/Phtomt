<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'
import { formatDate, relativeTime, sectionTone } from '../lib/format.js'

const route = useRoute()
const router = useRouter()

const all = ref([])
const loading = ref(true)
const PAGE_SIZE = 6

const page = computed({
  get: () => Math.max(1, Number(route.query.page) || 1),
  set: (value) => router.push({ query: value > 1 ? { page: String(value) } : {} })
})

const totalPages = computed(() => Math.max(1, Math.ceil(all.value.length / PAGE_SIZE)))
const items = computed(() => all.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

/** 每条版本里各类变更的条数，用来在条目头部给出概览 */
function counts(entry) {
  return (entry.sections || []).map((sec) => ({
    type: sec.type,
    count: sec.items?.length || 0,
    tone: sectionTone(sec.type)
  }))
}

function go(target) {
  const index = all.value.findIndex((e) => e.version === target)
  if (index === -1) return
  page.value = Math.floor(index / PAGE_SIZE) + 1
  requestAnimationFrame(() => {
    document.getElementById(`v-${target}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  })
}

onMounted(async () => {
  try {
    const res = await content.changelog({ page: 1, size: 200 })
    all.value = res.items || []
  } catch {
    all.value = []
  } finally {
    loading.value = false
  }
})

watch(page, () => {
  window.scrollTo({ top: 0, behavior: 'smooth' })
})
</script>

<template>
  <div>
    <PageHero
      no="06 / 更新"
      eyebrow="更新日志"
      :title="all.length ? `${all.length} 个版本，逐条记录改了什么` : '更新日志'"
      lead="每一条都写明动机和做法，而不是「优化体验、修复若干问题」。想找某个版本，用右侧索引直接跳。"
    >
      <div class="row row--wrap">
        <RouterLink to="/download" class="btn btn--sm">下载最新版</RouterLink>
        <a v-if="route.query.page" class="btn btn--sm" href="#" @click.prevent="page = 1">回到最新</a>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page layout">
        <!-- 正文 -->
        <div class="entries">
          <p v-if="loading" class="muted">正在读取更新日志…</p>
          <p v-else-if="!all.length" class="muted">暂时没有更新记录。</p>

          <article
            v-for="(entry, index) in items"
            :id="`v-${entry.version}`"
            :key="entry.version"
            v-reveal="index * 40"
            class="entry"
          >
            <header class="entry__head">
              <RouterLink :to="`/changelog/${entry.version}`" class="mono entry__ver">
                {{ entry.version }}
              </RouterLink>
              <span v-if="entry.date" class="muted small">{{ formatDate(entry.date) }} · {{ relativeTime(entry.date) }}</span>
              <span v-for="c in counts(entry)" :key="c.type" :class="['tag', `tag--${c.tone}`]">
                {{ c.type }} {{ c.count }}
              </span>
            </header>

            <p v-if="entry.summary" class="entry__summary">{{ entry.summary }}</p>

            <div v-for="sec in entry.sections || []" :key="sec.type" class="sec">
              <h3 class="sec__title">
                <span :class="['sec__mark', `sec__mark--${sectionTone(sec.type)}`]" aria-hidden="true"></span>
                {{ sec.type }}
                <span class="muted mono sec__count">{{ sec.items?.length || 0 }}</span>
              </h3>
              <ul class="sec__list">
                <li v-for="(item, i) in sec.items" :key="i" class="sec__item">{{ item }}</li>
              </ul>
            </div>

            <footer class="entry__foot">
              <RouterLink :to="`/changelog/${entry.version}`" class="link-arrow">单独查看这一版</RouterLink>
            </footer>
          </article>

          <nav v-if="totalPages > 1" class="pager" aria-label="分页">
            <button class="btn btn--sm" type="button" :disabled="page <= 1" @click="page = page - 1">上一页</button>
            <span class="pager__info mono">{{ page }} / {{ totalPages }}</span>
            <button class="btn btn--sm" type="button" :disabled="page >= totalPages" @click="page = page + 1">下一页</button>
          </nav>
        </div>

        <!-- 版本索引 -->
        <aside class="index">
          <p class="index__label eyebrow">版本索引</p>
          <ol class="index__list">
            <li v-for="entry in all" :key="entry.version">
              <button type="button" class="index__item mono" @click="go(entry.version)">
                {{ entry.version }}
              </button>
            </li>
          </ol>
        </aside>
      </div>
    </section>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  gap: clamp(28px, 4vw, 48px);
}

.entries {
  min-width: 0;
}

.entry {
  padding: 22px 0;
  border-top: 1px solid var(--line);
}

.entries > .entry:first-of-type {
  border-top: 2px solid var(--ink);
}

.entry__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.entry__ver {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--brand);
}

.entry__summary {
  margin-top: 10px;
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
  max-width: 70ch;
}

.sec {
  margin-top: 18px;
}

.sec__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--ink);
}

.sec__mark {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: var(--ink-3);
}

.sec__mark--ok {
  background: var(--ok);
}

.sec__mark--brand {
  background: var(--brand);
}

.sec__mark--amber {
  background: var(--amber);
}

.sec__mark--mist {
  background: var(--mist);
}

.sec__mark--danger {
  background: var(--danger);
}

.sec__count {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.sec__list {
  margin: 8px 0 0;
  padding-left: 20px;
  display: grid;
  gap: 5px;
}

.sec__item {
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink-2);
  max-width: 78ch;
}

.sec__item::marker {
  color: var(--line-2);
}

.entry__foot {
  margin-top: 16px;
}

.entry__foot .link-arrow {
  font-size: var(--t-xs);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}

.pager__info {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.index {
  padding-top: 6px;
}

.index__label {
  margin-bottom: 10px;
}

.index__list {
  list-style: none;
  margin: 0;
  padding: 0 0 0 12px;
  border-left: 1px solid var(--line);
  display: grid;
  gap: 1px;
}

.index__item {
  border: none;
  background: transparent;
  padding: 3px 0;
  font-size: var(--t-xs);
  color: var(--ink-3);
  cursor: pointer;
  text-align: left;
  transition: color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .index__item:hover {
    color: var(--brand);
  }
}

@media (min-width: 960px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 168px;
    align-items: start;
  }

  .index {
    position: sticky;
    top: 84px;
    order: 2;
    max-height: calc(100vh - 120px);
    overflow-y: auto;
    padding-right: 4px;
  }
}
</style>
