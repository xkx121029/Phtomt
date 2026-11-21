<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { content } from '../api/client.js'
import { formatDate, relativeTime, sectionTone } from '../lib/format.js'

const route = useRoute()
const entry = ref(null)
const list = ref([])
const loading = ref(true)

const index = computed(() =>
  list.value.findIndex((e) => e.version === entry.value?.version)
)
const newer = computed(() => (index.value > 0 ? list.value[index.value - 1] : null))
const older = computed(() =>
  index.value >= 0 && index.value < list.value.length - 1 ? list.value[index.value + 1] : null
)

const counts = computed(() =>
  (entry.value?.sections || []).map((sec) => ({
    type: sec.type,
    count: sec.items?.length || 0,
    tone: sectionTone(sec.type)
  }))
)

async function load(version) {
  loading.value = true
  try {
    const [detail, all] = await Promise.all([
      content.changelogOf(version),
      content.changelog({ page: 1, size: 200 })
    ])
    entry.value = detail || null
    list.value = all.items || []
  } catch {
    entry.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => load(route.params.version))
watch(() => route.params.version, (v) => v && load(v))
</script>

<template>
  <div>
    <section class="head">
      <div class="page">
        <RouterLink to="/changelog" class="back">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          全部更新记录
        </RouterLink>

        <p v-if="loading" class="muted head__state">正在读取…</p>
        <p v-else-if="!entry" class="muted head__state">未找到 {{ route.params.version }} 的更新记录。</p>

        <template v-else>
          <p class="eyebrow head__label">版本</p>
          <h1 class="h1 head__ver mono">{{ entry.version }}</h1>
          <div class="head__meta">
            <span v-if="entry.date" class="muted small">{{ formatDate(entry.date) }}，{{ relativeTime(entry.date) }}</span>
            <span v-for="c in counts" :key="c.type" :class="['tag', `tag--${c.tone}`]">{{ c.type }} {{ c.count }}</span>
          </div>
        </template>
        <hr class="head__rule" />
      </div>
    </section>

    <section v-if="entry" class="section section--tight">
      <div class="page body">
        <p v-if="entry.summary" v-reveal class="summary">{{ entry.summary }}</p>

        <div v-for="sec in entry.sections || []" :key="sec.type" class="sec">
          <h2 v-reveal class="sec__title">
            <span :class="['sec__mark', `sec__mark--${sectionTone(sec.type)}`]" aria-hidden="true"></span>
            {{ sec.type }}
            <span class="muted mono sec__count">{{ sec.items?.length || 0 }}</span>
          </h2>
          <ul class="sec__list">
            <li v-for="(item, i) in sec.items" :key="i" v-reveal="i * 25" class="sec__item">{{ item }}</li>
          </ul>
        </div>

        <nav class="nav" aria-label="相邻版本">
          <RouterLink v-if="older" :to="`/changelog/${older.version}`" class="nav__card">
            <span class="nav__dir muted small">← 更早</span>
            <span class="mono nav__ver">{{ older.version }}</span>
            <span v-if="older.date" class="muted small">{{ formatDate(older.date) }}</span>
          </RouterLink>
          <span v-else class="nav__spacer" />

          <RouterLink v-if="newer" :to="`/changelog/${newer.version}`" class="nav__card nav__card--right">
            <span class="nav__dir muted small">更新 →</span>
            <span class="mono nav__ver">{{ newer.version }}</span>
            <span v-if="newer.date" class="muted small">{{ formatDate(newer.date) }}</span>
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

.head__label {
  margin-top: 26px;
}

.head__ver {
  margin-top: 8px;
  letter-spacing: var(--track-num);
}

.head__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.head__rule {
  height: 2px;
  border: none;
  margin: 28px 0 0;
  background: var(--ink);
  opacity: 0.86;
}

.body {
  max-width: 78ch;
}

.summary {
  font-size: clamp(1rem, 0.96rem + 0.3vw, 1.12rem);
  line-height: 1.9;
  color: var(--ink);
  padding-bottom: 20px;
  border-bottom: 1px solid var(--line);
}

.sec {
  margin-top: 26px;
}

.sec__title {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 1.02rem;
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

.sec__mark--signal {
  background: var(--signal);
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
  margin: 10px 0 0;
  padding-left: 20px;
  display: grid;
  gap: 6px;
}

.sec__item {
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
}

.sec__item::marker {
  color: var(--line-strong);
}

.nav {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: clamp(32px, 4vw, 48px);
  padding-top: 22px;
  border-top: 1px solid var(--line);
}

.nav__card {
  display: grid;
  gap: 3px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel);
  transition: border-color var(--dur-ui) ease;
}

.nav__card--right {
  text-align: right;
}

.nav__dir {
  font-size: var(--t-xs);
}

.nav__ver {
  font-weight: 600;
}

.nav__spacer {
  display: block;
}

@media (hover: hover) and (pointer: fine) {
  .nav__card:hover {
    border-color: var(--line-strong);
  }
}
</style>
