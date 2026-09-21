<script setup>
import { computed, onMounted, ref } from 'vue'
import { ApiError, api } from '../../api/client.js'
import { formatDate, relativeTime } from '../../lib/format.js'

/**
 * 操作留痕。
 *
 * 服务端只保留最近 300 条（见 lib/helpers.js 的 audit()），所以这里不做分页——
 * 一次全量读回来，前端按动作类型过滤即可，比再开一个分页参数简单。
 */
const rows = ref([])
const loading = ref(true)
const error = ref('')
const filter = ref('all')

const ACTION_LABEL = {
  login: '登录',
  create: '新增',
  update: '更新',
  replace: '整包写入',
  delete: '删除',
  upload: '上传',
  import: '导入'
}

const TONE = {
  delete: 'danger',
  upload: 'brand',
  import: 'brand',
  replace: 'warn'
}

const actions = computed(() => {
  const seen = []
  for (const row of rows.value) if (row.action && !seen.includes(row.action)) seen.push(row.action)
  return seen
})

const filtered = computed(() =>
  filter.value === 'all' ? rows.value : rows.value.filter((r) => r.action === filter.value)
)

function label(action) {
  return ACTION_LABEL[action] || action || '未知'
}

onMounted(async () => {
  try {
    const data = await api.adminGet('/api/admin/audit')
    rows.value = Array.isArray(data) ? data : []
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取操作留痕失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">操作留痕</h1>
      <p class="muted small">
        每次登录、内容写入与文件操作都会记一条，保留最近 300 条。这份记录只增不删，
        排查「内容什么时候被改坏的」时以它为准。
      </p>
    </header>

    <p v-if="error" class="msg msg--error">{{ error }}</p>
    <p v-else-if="loading" class="muted">正在读取…</p>

    <template v-else>
      <div class="chips">
        <button
          type="button"
          :class="['chip', { 'chip--on': filter === 'all' }]"
          @click="filter = 'all'"
        >全部 <span class="mono">{{ rows.length }}</span></button>
        <button
          v-for="action in actions"
          :key="action"
          type="button"
          :class="['chip', { 'chip--on': filter === action }]"
          @click="filter = action"
        >{{ label(action) }} <span class="mono">{{ rows.filter((r) => r.action === action).length }}</span></button>
      </div>

      <p v-if="!filtered.length" class="muted small">还没有操作记录。</p>

      <ol v-else class="log">
        <li v-for="(row, i) in filtered" :key="`${row.at}-${i}`" v-reveal="Math.min(i, 8) * 30" class="log__item">
          <div class="log__top">
            <span :class="['tag', TONE[row.action] ? `tag--${TONE[row.action]}` : '']">{{ label(row.action) }}</span>
            <span class="mono log__target">{{ row.target }}</span>
            <span class="muted small log__time" :title="formatDate(row.at)">{{ relativeTime(row.at) }}</span>
          </div>
          <p v-if="row.detail" class="log__detail">{{ row.detail }}</p>
          <p v-if="row.ip" class="mono log__ip">{{ row.ip }}</p>
        </li>
      </ol>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 960px;
}

.page__head {
  display: grid;
  gap: 6px;
}

.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
  color: var(--ink-2);
  font-size: var(--t-xs);
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .chip:hover {
    border-color: var(--line-2);
  }
}

.chip--on {
  background: var(--brand-wash);
  border-color: var(--brand);
  color: var(--brand-strong);
}

[data-theme="dark"] .chip--on {
  color: var(--brand);
}

.log {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.log__item {
  display: grid;
  gap: 6px;
  padding: 12px 16px;
  background: var(--paper-raised);
}

.log__top {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.log__target {
  flex: 1;
  font-size: var(--t-sm);
  color: var(--ink-2);
  word-break: break-all;
}

.log__time {
  flex: none;
}

.log__detail {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.7;
}

.log__ip {
  font-size: 0.7rem;
  color: var(--ink-3);
}

.msg {
  padding: 10px 14px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
}

.msg--error {
  background: var(--danger-wash);
  color: var(--danger);
}
</style>
