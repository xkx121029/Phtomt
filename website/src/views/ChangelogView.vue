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

/** 每条版本里各类变更的条数，用来在卡片头部给出概览 */
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
      eyebrow="更新日志"
      :title="all.length ? `${all.length} 个版本，逐条记录改了什么` : '更新日志'"
      lead="每一条都写明动机和做法，而不是「优化体验、修复若干问题」。想快速找某个版本，用左侧索引。"
    >
      <div class="row row--wrap">
        <RouterLink to="/download" class="btn btn--sm">下载最新版</RouterLink>
        <a
          v-if="route.query.page"
          class="btn btn--sm"
          href="#"
          @click.prevent="page = 1"
        >回到最新</a>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page layout">
        <!-- 版本索引 -->
        <aside class="index">
          <p class="index__label">版本索引</p>
          <ol class="index__list">
            <li v-for="entry in all" :key="entry.version">
              <button type="button" class="index__item mono" @click="go(entry.version)">
                {{ entry.version }}
              </button>
            </li>
          </ol>
        </aside>

        <!-- 正文 -->
        <div class="entries">
          <p v-if="loading" class="muted">正在读取更新日志…</p>
          <p v-else-if="!all.length" class="muted">暂时没有更新记录。</p>

          <article
            v-for="(entry, index) in items"
            :id="`v-${entry.version}`"
            :key="entry.version"
            v-reveal="index * 50"
            class="card entry"
          >
            <header class="entry__head">
              <div class="entry__title">
                <RouterLink :to="`/changelog/${entry.version}`" class="mono entry__ver">
                  {{ entry.version }}
                </RouterLink>
                <span v-if="entry.date" class="muted small">{{ formatDate(entry.date) }} · {{ relativeTime(entry.date) }}</span>
              </div>
              <div class="entry__tags">
                <span
                  v-for="c in counts(entry)"
                  :key="c.type"
                  :class="['tag', `tag--${c.tone}`]"
                >{{ c.type }} {{ c.count }}</span>
              </div>
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

          <!-- 分页 -->
          <nav v-if="totalPages > 1" class="pager" aria-label="分页">
            <button
              class="btn btn--sm"
              type="button"
              :disabled="page <= 1"
              @click="page = page - 1"
            >上一页</button>
            <span class="pager__info mono">{{ page }} / {{ totalPages }}</span>
            <button
              class="btn btn--sm"
              type="button"
              :disabled="page >= totalPages"
              @click="page = page + 1"
            >下一页</button>
          </nav>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  gap: clamp(24px, 4vw, 52px);
  align-items: start;
}

.index {
  position: sticky;
  top: 88px;
}

.index__label {
  font-size: var(--t-xs);
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-3);
  margin-bottom: 10px;
}

.index__list {
  list-style: none;
  margin: 0;
  padding: 0 4px 0 0;
  display: grid;
  gap: 1px;
  max-height: calc(100vh - 160px);
  overflow-y: auto;
}

.index__item {
  width: 100%;
  padding: 5px 8px;
  border: none;
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--ink-3);
  font-size: 0.74rem;
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .index__item:hover {
    background: var(--paper-sunken);
    color: var(--brand);
  }
}

.entries {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.entry {
  display: grid;
  gap: 16px;
  scroll-margin-top: 88px;
}

.entry__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.entry__title {
  display: grid;
  gap: 4px;
}

.entry__ver {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--brand);
  letter-spacing: -0.01em;
}

.entry__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.entry__summary {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.82;
  padding-left: 14px;
  border-left: 2px solid var(--line-2);
}

.sec {
  display: grid;
  gap: 8px;
}

.sec__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
  font-weight: 600;
}

.sec__mark {
  width: 3px;
  height: 14px;
  border-radius: var(--r-pill);
  background: var(--line-2);
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
  font-weight: 400;
}

.sec__list {
  margin: 0;
  padding-left: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}

.sec__item {
  position: relative;
  padding-left: 16px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.8;
}

.sec__item::before {
  content: "";
  position: absolute;
  left: 2px;
  top: 0.72em;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--line-2);
}

.entry__foot {
  padding-top: 4px;
  border-top: 1px solid var(--line);
  padding-top: 14px;
}

.link-arrow {
  position: relative;
  padding-right: 16px;
  font-size: var(--t-sm);
  color: var(--brand);
}

.link-arrow::after {
  content: "→";
  position: absolute;
  right: 0;
  transition: transform var(--dur-ui) var(--ease-out);
}

@media (hover: hover) and (pointer: fine) {
  .link-arrow:hover::after {
    transform: translateX(3px);
  }
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding-top: 12px;
}

.pager__info {
  font-size: var(--t-sm);
  color: var(--ink-3);
}

@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .index {
    position: static;
  }

  .index__list {
    display: flex;
    flex-wrap: wrap;
    max-height: none;
    gap: 6px;
  }

  .index__item {
    width: auto;
    border: 1px solid var(--line);
    border-radius: var(--r-pill);
    padding: 4px 10px;
  }
}
</style>
