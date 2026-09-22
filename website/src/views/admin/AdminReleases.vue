<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, content } from '../../api/client.js'
import { formatDate, channelLabel } from '../../lib/format.js'
import { channelLabels } from '../../lib/siteMeta.js'
import { useCollection } from './useCollection.js'

const { items, loading, busy, error, notice, load, create, update, remove } = useCollection('releases', 'version')

const files = ref([])
const uploadError = ref('')
const uploading = ref(false)
const fileInput = ref(null)
const selectedVersion = ref('')
const mode = ref('edit')

const form = reactive({
  version: '',
  channel: 'stable',
  date: '',
  published: true,
  title: '',
  summary: '',
  apkFile: '',
  notesText: ''
})

const sorted = computed(() =>
  [...items.value].sort((a, b) => String(b.version).localeCompare(String(a.version), undefined, { numeric: true }))
)

const isNew = computed(() => mode.value === 'create')

/** 已被某个版本占用的文件，绑定下拉里标出来，避免两个版本指同一个包 */
const usedBy = computed(() => {
  const map = new Map()
  for (const item of items.value) {
    if (item.apk?.file) map.set(item.apk.file, item.version)
  }
  return map
})

async function loadFiles() {
  try {
    files.value = await api.adminGet('/api/admin/apk')
  } catch {
    files.value = []
  }
}

function fill(item) {
  form.version = item?.version || ''
  form.channel = item?.channel || 'stable'
  form.date = item?.date || new Date().toISOString().slice(0, 10)
  form.published = item?.published !== false
  form.title = item?.title || ''
  form.summary = item?.summary || ''
  form.apkFile = item?.apk?.file || ''
  form.notesText = Array.isArray(item?.notes) ? item.notes.join('\n') : ''
}

function pick(item) {
  mode.value = 'edit'
  selectedVersion.value = item.version
  fill(item)
}

function startCreate() {
  mode.value = 'create'
  selectedVersion.value = ''
  fill(null)
}

function payload() {
  const notes = form.notesText.split('\n').map((s) => s.trim()).filter(Boolean)
  return {
    version: form.version.trim(),
    channel: form.channel,
    date: form.date,
    published: form.published,
    title: form.title,
    summary: form.summary,
    apk: form.apkFile ? { file: form.apkFile } : null,
    notes
  }
}

async function save() {
  if (!form.version.trim()) {
    uploadError.value = '版本号不能为空'
    return
  }
  const body = payload()
  const ok = isNew.value
    ? await create(body)
    : await update(selectedVersion.value, body)
  if (ok) {
    mode.value = 'edit'
    selectedVersion.value = body.version
    const fresh = items.value.find((it) => it.version === body.version)
    if (fresh) fill(fresh)
  }
}

async function destroy() {
  if (!selectedVersion.value) return
  if (!window.confirm(`确定删除版本 ${selectedVersion.value}？APK 文件不会被删除。`)) return
  const ok = await remove(selectedVersion.value)
  if (ok) startCreate()
}

async function onUpload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  uploadError.value = ''
  uploading.value = true
  try {
    const info = await api.upload('/api/admin/apk', file)
    await loadFiles()
    form.apkFile = info.file
  } catch (err) {
    uploadError.value = err instanceof ApiError ? err.message : '上传失败'
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function deleteFile(file) {
  const owner = usedBy.value.get(file)
  if (owner && !window.confirm(`文件被版本 ${owner} 引用，仍要强制删除？`)) return
  if (!owner && !window.confirm(`删除磁盘上的 ${file}？`)) return
  uploadError.value = ''
  try {
    await api.del(`/api/admin/apk/${encodeURIComponent(file)}${owner ? '?force=1' : ''}`, true)
    await loadFiles()
  } catch (err) {
    uploadError.value = err instanceof ApiError ? err.message : '删除失败'
  }
}

function copySha(text) {
  navigator.clipboard?.writeText(text).catch(() => {})
}

onMounted(async () => {
  // 渠道下拉的可选项来自站点元信息 site.channelLabels（content.site 会把它并入注册表）
  await Promise.all([load(), loadFiles(), content.site().catch(() => {})])
  if (sorted.value.length) pick(sorted.value[0])
  else startCreate()
})
</script>

<template>
  <div class="releases">
    <header class="head">
      <div>
        <h1 class="h2">版本与 APK</h1>
        <p class="muted small head__sub">
          上传安装包，再把版本指向它。下载页会自动用最新的已发布版本。
        </p>
      </div>
      <button class="btn btn--primary btn--sm" type="button" @click="startCreate">+ 新建版本</button>
    </header>

    <div class="layout">
      <!-- 左：版本列表 -->
      <aside class="list card">
        <p class="list__label">版本列表</p>
        <p v-if="loading" class="muted small">正在读取…</p>
        <p v-else-if="!sorted.length" class="muted small">还没有版本。</p>
        <ul class="list__items">
          <li v-for="item in sorted" :key="item.version">
            <button
              type="button"
              :class="['list__item', { 'list__item--on': !isNew && item.version === selectedVersion }]"
              @click="pick(item)"
            >
              <span class="mono list__ver">{{ item.version }}</span>
              <span class="list__meta">
                <span>{{ channelLabel(item.channel) }}</span>
                <span v-if="item.published === false" class="tag tag--warn">未发布</span>
                <span v-if="!item.apk?.file" class="tag tag--danger">无包</span>
              </span>
              <span class="muted small">{{ formatDate(item.date) }}</span>
            </button>
          </li>
        </ul>
      </aside>

      <!-- 右：表单 + 文件 -->
      <div class="main">
        <section class="card block">
          <header class="block__head">
            <h2 class="block__title">
              <template v-if="isNew">新建版本</template>
              <template v-else>{{ selectedVersion }}</template>
            </h2>
            <div class="row">
              <button
                v-if="!isNew"
                class="btn btn--sm btn--danger"
                type="button"
                :disabled="busy === 'remove'"
                @click="destroy"
              >删除</button>
              <button class="btn btn--primary btn--sm" type="button" :disabled="Boolean(busy)" @click="save">
                {{ busy ? '保存中…' : '保存' }}
              </button>
            </div>
          </header>

          <p v-if="error" class="msg msg--error">{{ error }}</p>
          <p v-else-if="notice" class="msg msg--ok">{{ notice }}</p>

          <div class="grid">
            <label class="field">
              <span>版本号</span>
              <input v-model="form.version" class="input mono" placeholder="0.1.385" :disabled="!isNew" />
              <span class="hint">不带 v 前缀；保存时会自动归一化为 <code>v0.1.385</code>。</span>
            </label>

            <label class="field">
              <span>渠道</span>
              <select v-model="form.channel" class="select">
                <option v-for="(name, id) in channelLabels" :key="id" :value="id">{{ name }}</option>
              </select>
            </label>

            <label class="field">
              <span>日期</span>
              <input v-model="form.date" class="input mono" type="date" />
            </label>

            <label class="field">
              <span>发布状态</span>
              <span class="switch">
                <input v-model="form.published" type="checkbox" />
                <span>{{ form.published ? '已发布（官网可见）' : '未发布（隐藏）' }}</span>
              </span>
            </label>

            <label class="field grid__wide">
              <span>标题</span>
              <input v-model="form.title" class="input" placeholder="例如：当前开发版" />
            </label>

            <label class="field grid__wide">
              <span>摘要</span>
              <textarea v-model="form.summary" class="textarea" rows="4" />
              <span class="hint">下载页会显示这段文字，建议写清这一版解决了什么。</span>
            </label>

            <label class="field grid__wide">
              <span>注意事项</span>
              <textarea v-model="form.notesText" class="textarea" rows="3" placeholder="一行一条" />
            </label>
          </div>
        </section>

        <!-- APK -->
        <section class="card block">
          <header class="block__head">
            <h2 class="block__title">安装包</h2>
            <label class="btn btn--sm upload">
              {{ uploading ? '上传中…' : '上传 APK' }}
              <input ref="fileInput" type="file" accept=".apk" :disabled="uploading" @change="onUpload" />
            </label>
          </header>

          <p v-if="uploadError" class="msg msg--error">{{ uploadError }}</p>

          <label class="field">
            <span>绑定到本版本的文件</span>
            <select v-model="form.apkFile" class="select mono">
              <option value="">（不绑定，下载页退回 GitHub Releases）</option>
              <option v-for="f in files" :key="f.file" :value="f.file">
                {{ f.file }} · {{ f.sizeText }}{{ usedBy.get(f.file) && usedBy.get(f.file) !== selectedVersion ? ` · 已被 ${usedBy.get(f.file)} 引用` : '' }}
              </option>
            </select>
          </label>

          <div v-if="files.length" class="files">
            <p class="files__label">磁盘上的文件（{{ files.length }}）</p>
            <ul class="files__list">
              <li v-for="f in files" :key="f.file" class="files__item">
                <div class="files__info">
                  <span class="mono files__name">{{ f.file }}</span>
                  <span class="muted small">{{ f.sizeText }} · {{ formatDate(f.updatedAt) }}</span>
                  <span v-if="f.sha256" class="mono files__sha" :title="f.sha256" @click="copySha(f.sha256)">
                    {{ f.sha256.slice(0, 16) }}…
                  </span>
                </div>
                <div class="files__actions">
                  <span v-if="usedBy.get(f.file)" class="tag">{{ usedBy.get(f.file) }}</span>
                  <button class="btn btn--sm btn--danger" type="button" @click="deleteFile(f.file)">删除</button>
                </div>
              </li>
            </ul>
          </div>
          <p v-else class="muted small">还没有上传过 APK。上限 200 MB，只接受 <code>.apk</code>。</p>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.releases {
  display: grid;
  gap: 20px;
  max-width: 1180px;
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

.layout {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.list {
  position: sticky;
  top: 20px;
  display: grid;
  gap: 10px;
}

.list__label {
  font-size: var(--t-xs);
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-3);
}

.list__items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 2px;
  max-height: calc(100vh - 240px);
  overflow-y: auto;
}

.list__item {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: 9px 10px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .list__item:hover {
    background: var(--paper-sunken);
  }
}

.list__item--on {
  background: var(--brand-wash);
}

.list__ver {
  font-size: var(--t-sm);
  font-weight: 600;
}

.list__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.main {
  display: grid;
  gap: 16px;
  min-width: 0;
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
  gap: 14px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.block__title {
  font-size: 1.05rem;
  font-weight: 600;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.grid__wide {
  grid-column: 1 / -1;
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  cursor: pointer;
  padding-top: 6px;
}

.switch input {
  width: 16px;
  height: 16px;
  accent-color: var(--brand);
}

.upload {
  position: relative;
  overflow: hidden;
}

.upload input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
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
  gap: 8px;
}

.files__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--paper-sunken);
}

.files__info {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.files__name {
  font-size: var(--t-xs);
  word-break: break-all;
}

.files__sha {
  font-size: 0.68rem;
  color: var(--ink-3);
  cursor: copy;
}

.files__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
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
  background: var(--ok-wash);
  color: var(--ok);
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .list {
    position: static;
  }

  .list__items {
    max-height: 260px;
  }
}
</style>
