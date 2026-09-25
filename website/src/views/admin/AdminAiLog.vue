<script setup>
import { computed, onMounted, ref } from 'vue'
import { ApiError, api } from '../../api/client.js'
import { formatDate, relativeTime } from '../../lib/format.js'

/**
 * 问答留痕。
 *
 * 和「操作留痕」是两个用途：那个记后台做了什么，是追责用的；这个记访客问了什么，
 * 用来发现「大家都卡在同一个问题上」——那通常意味着 FAQ 或文档缺了一页。
 *
 * 所以这里的重点不是审计，而是**可读**：提问要一眼看全，回答默认收起来
 * （一份回答动辄上千字，全展开会把列表冲垮），引导目标单独标出来。
 */

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const query = ref('')
const applied = ref('')
const loading = ref(true)
const clearing = ref(false)
const error = ref('')
const notice = ref('')

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const hasPrev = computed(() => page.value > 1)
const hasNext = computed(() => page.value < pages.value)

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const qs = new URLSearchParams({ page: String(target), size: String(size) })
    if (applied.value) qs.set('q', applied.value)
    const data = await api.adminGet(`/api/admin/ai/logs?${qs}`)
    rows.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total) || 0
    page.value = Number(data?.page) || target
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取问答留痕失败'
  } finally {
    loading.value = false
  }
}

function search() {
  applied.value = query.value.trim()
  load(1)
}

function go(delta) {
  const next = page.value + delta
  if (next < 1 || next > pages.value) return
  load(next)
}

async function clearAll() {
  if (!window.confirm(`确定清空全部 ${total.value} 条问答留痕？此操作不可撤销。`)) return
  clearing.value = true
  error.value = ''
  notice.value = ''
  try {
    const res = await api.del('/api/admin/ai/logs', true)
    notice.value = `已清空 ${res?.cleared ?? 0} 条留痕。`
    page.value = 1
    await load(1)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '清空失败'
  } finally {
    clearing.value = false
  }
}

/** 来源分组用的是内部英文键，列给运营看要换成中文 */
const GROUP_LABEL = {
  docs: '文档',
  changelog: '更新日志',
  features: '功能特性',
  scenarios: '场景示例',
  roadmap: '路线图'
}

onMounted(() => load(1))
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">问答留痕</h1>
      <p class="muted small">
        访客在官网问答里问了什么、AI 答了什么，都在这里。IP 只保留到网段，
        原始地址不会落盘。翻这份记录的主要用途是发现「很多人卡在同一个问题上」——
        那说明 FAQ 或文档该补一页了。
      </p>
    </header>

    <div class="bar">
      <label class="field bar__q">
        <input
          v-model="query"
          class="input"
          placeholder="搜提问、回答或引导路径…"
          @keydown.enter="search"
        />
      </label>
      <button class="btn btn--sm" type="button" @click="search">搜索</button>
      <button class="btn btn--sm" type="button" :disabled="loading" @click="load(page)">刷新</button>
      <button
        class="btn btn--sm btn--ghost bar__clear"
        type="button"
        :disabled="clearing || !total"
        @click="clearAll"
      >
        {{ clearing ? '清空中…' : '清空全部' }}
      </button>
    </div>

    <p v-if="error" class="msg msg--error">{{ error }}</p>
    <p v-else-if="notice" class="msg msg--ok">{{ notice }}</p>

    <p v-if="loading" class="muted">正在读取…</p>
    <p v-else-if="!rows.length" class="muted small">
      {{ applied ? `没有匹配「${applied}」的记录。` : '还没有问答记录。' }}
    </p>

    <template v-else>
      <p class="muted small">
        共 <span class="mono">{{ total }}</span> 条，第 {{ page }} / {{ pages }} 页
      </p>

      <ol class="log">
        <li v-for="(row, i) in rows" :key="row.id || `${row.at}-${i}`" v-reveal="Math.min(i, 8) * 24" class="log__item">
          <div class="log__top">
            <span v-if="row.nav" class="tag tag--brand">带路 {{ row.navLabel || row.nav }}</span>
            <span v-else class="tag">纯答疑</span>
            <span v-if="row.error" class="tag tag--danger">生成失败</span>
            <span class="muted small log__time" :title="formatDate(row.at)">{{ relativeTime(row.at) }}</span>
          </div>

          <p class="log__ask">{{ row.question }}</p>

          <details v-if="row.answer" class="log__more">
            <summary>看回答（{{ row.chars }} 字）</summary>
            <p class="log__answer">{{ row.answer }}</p>
          </details>
          <p v-else-if="row.error" class="log__failed">{{ row.error }}</p>

          <p class="log__meta mono">
            <span v-if="row.nav">{{ row.nav }}</span>
            <span v-if="row.sources?.length">
              命中：{{ row.sources.map((s) => GROUP_LABEL[s] || s).join('、') }}
            </span>
            <span v-if="row.ms">{{ (row.ms / 1000).toFixed(1) }}s</span>
            <span v-if="row.ua">{{ row.ua }}</span>
            <span v-if="row.ip">{{ row.ip }}</span>
          </p>
        </li>
      </ol>

      <div v-if="pages > 1" class="pager">
        <button class="btn btn--sm" type="button" :disabled="!hasPrev || loading" @click="go(-1)">上一页</button>
        <span class="muted small mono">{{ page }} / {{ pages }}</span>
        <button class="btn btn--sm" type="button" :disabled="!hasNext || loading" @click="go(1)">下一页</button>
      </div>
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

.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.bar__q {
  flex: 1;
  min-width: 200px;
}

.bar__clear {
  margin-left: auto;
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
  gap: 8px;
  padding: 14px 16px;
  background: var(--paper-raised);
}

.log__top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.log__time {
  margin-left: auto;
  flex: none;
}

/* 访客的原话是这个页面真正的主角，给它正常字号与最深的一档颜色 */
.log__ask {
  font-size: var(--t-sm);
  line-height: 1.75;
  color: var(--ink);
  word-break: break-word;
  white-space: pre-wrap;
}

.log__more summary {
  font-size: var(--t-xs);
  color: var(--ink-3);
  cursor: pointer;
}

.log__more summary:hover {
  color: var(--brand);
}

.log__answer {
  margin-top: 8px;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  background: var(--paper-sunken);
  font-size: var(--t-xs);
  line-height: 1.8;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}

.log__failed {
  font-size: var(--t-xs);
  color: var(--danger);
}

.log__meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 0.7rem;
  color: var(--ink-3);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
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

.msg--ok {
  background: var(--brand-wash);
  color: var(--brand-strong);
}

[data-theme="dark"] .msg--ok {
  color: var(--brand);
}
</style>
