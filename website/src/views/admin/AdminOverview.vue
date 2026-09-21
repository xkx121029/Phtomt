<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError, api } from '../../api/client.js'
import { formatDate, relativeTime } from '../../lib/format.js'

const data = ref(null)
const error = ref('')
const loading = ref(true)

const CARDS = computed(() => {
  const c = data.value?.counts || {}
  return [
    { label: '版本', value: c.releases ?? 0, extra: `已发布 ${c.published ?? 0}`, to: '/admin/releases' },
    { label: '更新日志', value: c.changelog ?? 0, to: '/admin/changelog' },
    { label: '功能特性', value: c.features ?? 0, to: '/admin/features' },
    { label: '文档', value: c.docs ?? 0, to: '/admin/docs' },
    { label: '常见问题', value: c.faq ?? 0, to: '/admin/faq' },
    { label: 'APK 文件', value: c.apkFiles ?? 0, extra: data.value?.storage?.apkText || '0 B', to: '/admin/releases' }
  ]
})

const ACTION_LABEL = {
  login: '登录',
  create: '新增',
  update: '更新',
  replace: '整包写入',
  delete: '删除',
  upload: '上传',
  import: '导入'
}

onMounted(async () => {
  try {
    data.value = await api.adminGet('/api/admin/overview')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取概览失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="overview">
    <header class="head">
      <div>
        <h1 class="h2">概览</h1>
        <p class="muted small head__sub">内容集合的规模、磁盘占用与最近的操作。</p>
      </div>
      <div class="row">
        <RouterLink to="/admin/releases" class="btn btn--primary btn--sm">上传 APK</RouterLink>
        <RouterLink to="/admin/changelog" class="btn btn--sm">写更新日志</RouterLink>
      </div>
    </header>

    <p v-if="error" class="msg msg--error">{{ error }}</p>
    <p v-else-if="loading" class="muted">正在读取…</p>

    <template v-else-if="data">
      <div class="cards">
        <RouterLink v-for="card in CARDS" :key="card.label" :to="card.to" class="card stat">
          <span class="stat__label">{{ card.label }}</span>
          <span class="stat__value mono">{{ card.value }}</span>
          <span v-if="card.extra" class="stat__extra">{{ card.extra }}</span>
        </RouterLink>
      </div>

      <div class="two">
        <!-- 最新版本 -->
        <section class="card block">
          <h2 class="block__title">最新版本</h2>
          <template v-if="data.latest">
            <p class="mono block__version">{{ data.latest.version }}</p>
            <dl class="kv">
              <div class="kv__row">
                <dt>渠道</dt>
                <dd>{{ data.latest.channel || 'stable' }}</dd>
              </div>
              <div class="kv__row">
                <dt>日期</dt>
                <dd>{{ formatDate(data.latest.date) }}</dd>
              </div>
              <div class="kv__row">
                <dt>APK</dt>
                <dd class="mono">{{ data.latest.apk?.file || '未绑定' }}</dd>
              </div>
              <div class="kv__row">
                <dt>下载次数</dt>
                <dd class="mono">{{ data.stats?.byVersion?.[data.latest.version] ?? 0 }}</dd>
              </div>
            </dl>
          </template>
          <p v-else class="muted small">还没有版本记录。</p>
        </section>

        <!-- 下载统计 -->
        <section class="card block">
          <h2 class="block__title">下载统计</h2>
          <dl class="kv">
            <div class="kv__row">
              <dt>累计下载</dt>
              <dd class="mono">{{ data.stats?.totalDownloads ?? 0 }}</dd>
            </div>
            <div class="kv__row">
              <dt>最近一次</dt>
              <dd>{{ data.stats?.lastDownloadAt ? relativeTime(data.stats.lastDownloadAt) : '还没有人下载' }}</dd>
            </div>
            <div class="kv__row">
              <dt>APK 占用</dt>
              <dd class="mono">{{ data.storage?.apkText || '0 B' }}</dd>
            </div>
          </dl>

          <div v-if="data.apkFiles?.length" class="files">
            <p class="files__label">磁盘上的文件</p>
            <ul class="files__list">
              <li v-for="f in data.apkFiles" :key="f.file" class="files__item">
                <span class="mono files__name">{{ f.file }}</span>
                <span class="muted mono files__size">{{ f.sizeText }}</span>
              </li>
            </ul>
          </div>
          <p v-else class="muted small files__empty">
            磁盘上还没有 APK 文件。到「版本与 APK」页上传。
          </p>
        </section>
      </div>

      <!-- 最近操作 -->
      <section class="card block">
        <div class="block__head">
          <h2 class="block__title">最近操作</h2>
          <RouterLink to="/admin/audit" class="btn btn--sm">全部留痕</RouterLink>
        </div>

        <ul v-if="data.recent?.length" class="audit">
          <li v-for="(item, i) in data.recent" :key="i" class="audit__item">
            <span class="tag">{{ ACTION_LABEL[item.action] || item.action }}</span>
            <span class="mono audit__target">{{ item.target }}</span>
            <span class="muted small audit__time">{{ relativeTime(item.at) }}</span>
          </li>
        </ul>
        <p v-else class="muted small">还没有操作记录。</p>
      </section>

      <p class="paths muted small">
        数据目录 <span class="mono">{{ data.dataDir }}</span> · APK 目录 <span class="mono">{{ data.apkDir }}</span>
      </p>
    </template>
  </div>
</template>

<style scoped>
.overview {
  display: grid;
  gap: 22px;
  max-width: 1120px;
}

.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.head__sub {
  margin-top: 6px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.stat {
  display: grid;
  gap: 2px;
  align-content: start;
  transition:
    transform var(--dur-press) var(--ease-out),
    border-color var(--dur-ui) ease;
}

.stat:active {
  transform: scale(0.98);
}

@media (hover: hover) and (pointer: fine) {
  .stat:hover {
    border-color: var(--line-2);
  }
}

.stat__label {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.stat__value {
  font-size: 1.7rem;
  font-weight: 600;
  letter-spacing: -0.03em;
  line-height: 1.2;
}

.stat__extra {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}

.block {
  display: grid;
  gap: 14px;
  align-content: start;
}

.block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.block__title {
  font-size: 1rem;
  font-weight: 600;
}

.block__version {
  font-size: 1.3rem;
  font-weight: 600;
  color: var(--brand);
}

.kv {
  display: grid;
  gap: 1px;
  margin: 0;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.kv__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  background: var(--paper-raised);
  font-size: var(--t-sm);
}

.kv__row dt {
  color: var(--ink-3);
}

.kv__row dd {
  margin: 0;
  text-align: right;
  word-break: break-all;
}

.files__label {
  font-size: var(--t-xs);
  color: var(--ink-3);
  margin-bottom: 8px;
}

.files__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
}

.files__item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  font-size: var(--t-xs);
}

.files__name {
  color: var(--ink-2);
  word-break: break-all;
}

.files__empty {
  margin-top: 4px;
}

.audit {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.audit__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--paper-raised);
  font-size: var(--t-sm);
}

.audit__target {
  flex: 1;
  color: var(--ink-2);
  word-break: break-all;
}

.audit__time {
  flex: none;
}

.paths {
  word-break: break-all;
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
