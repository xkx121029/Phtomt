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
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          全部更新记录
        </RouterLink>

        <p v-if="loading" class="muted head__loading">正在读取…</p>
        <p v-else-if="!entry" class="muted head__loading">未找到 {{ route.params.version }} 的更新记录。</p>

        <template v-else>
          <h1 class="h1 head__ver mono">{{ entry.version }}</h1>
          <div class="head__meta">
            <span v-if="entry.date" class="muted small">{{ formatDate(entry.date) }} · {{ relativeTime(entry.date) }}</span>
            <span v-for="c in counts" :key="c.type" :class="['tag', `tag--${c.tone}`]">{{ c.type }} {{ c.count }}</span>
          </div>
        </template>
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
            <li v-for="(item, i) in sec.items" :key="i" v-reveal="i * 30" class="sec__item">{{ item }}</li>
          </ul>
        </div>

        <nav class="nav">
          <RouterLink
            v-if="older"
            :to="`/changelog/${older.version}`"
            class="card nav__card"
          >
            <span class="nav__dir muted small">更早</span>
            <span class="mono nav__ver">{{ older.version }}</span>
            <span v-if="older.date" class="muted small">{{ formatDate(older.date) }}</span>
          </RouterLink>
          <span v-else class="nav__spacer" />

          <RouterLink
            v-if="newer"
            :to="`/changelog/${newer.version}`"
            class="card nav__card nav__card--right"
          >
            <span class="nav__dir muted small">更新</span>
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
  padding-block: clamp(40px, 5vw, 64px) clamp(24px, 3vw, 34px);
  border-bottom: 1px solid var(--line);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--t-sm);
  color: var(--ink-3);
  transition: color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .back:hover {
    color: var(--brand);
  }
}

.head__loading {
  margin-top: 20px;
}

.head__ver {
  margin-top: 20px;
  letter-spacing: -0.03em;
}

.head__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
}

.body {
  max-width: 860px;
}

.summary {
  padding: 18px 22px;
  border-left: 3px solid var(--brand);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--paper-sunken);
  font-size: var(--t-body);
  color: var(--ink-2);
  line-height: 1.85;
}

.sec {
  margin-top: clamp(32px, 4vw, 48px);
}

.sec__title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--t-h3);
  margin-bottom: 14px;
}

.sec__mark {
  width: 4px;
  height: 18px;
  border-radius: var(--r-pill);
  background: var(--line-2);
}

.sec__mark--ok { background: var(--ok); }
.sec__mark--brand { background: var(--brand); }
.sec__mark--amber { background: var(--amber); }
.sec__mark--mist { background: var(--mist); }
.sec__mark--danger { background: var(--danger); }

.sec__count {
  font-size: var(--t-xs);
  font-weight: 400;
}

.sec__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.sec__item {
  position: relative;
  padding: 12px 16px 12px 30px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--paper-raised);
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.82;
}

.sec__item::before {
  content: "";
  position: absolute;
  left: 14px;
  top: 1.32em;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand);
  opacity: 0.55;
}

.nav {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: clamp(40px, 5vw, 64px);
  padding-top: clamp(24px, 3vw, 34px);
  border-top: 1px solid var(--line);
}

.nav__card {
  display: grid;
  gap: 4px;
  transition:
    transform var(--dur-press) var(--ease-out),
    border-color var(--dur-ui) ease;
}

.nav__card:active {
  transform: scale(0.98);
}

@media (hover: hover) and (pointer: fine) {
  .nav__card:hover {
    border-color: var(--line-2);
  }
}

.nav__card--right {
  justify-items: end;
  text-align: right;
}

.nav__dir {
  font-size: var(--t-xs);
}

.nav__ver {
  color: var(--brand);
  font-weight: 600;
}

.nav__spacer {
  display: block;
}

@media (max-width: 640px) {
  .nav {
    grid-template-columns: 1fr;
  }
}
</style>
