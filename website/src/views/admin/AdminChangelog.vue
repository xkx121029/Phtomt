<script setup>
import { ref } from 'vue'
import CollectionEditor from './CollectionEditor.vue'
import { ApiError, api } from '../../api/client.js'

/**
 * 更新日志。
 *
 * `sections` 在存储里是 [{ type, items[] }]，在表单里是一段纯文本——
 * 用「## 小节名 / - 条目」的写法，和项目根的 CHANGELOG.md 完全一致，
 * 这样从仓库粘过来不用改格式，反过来也能直接贴回仓库。
 */
const FIELDS = [
  { key: 'version', label: '版本号', mono: true, readonly: true, hint: '主键，写成 v0.1.384 或 0.1.384 都行' },
  { key: 'date', label: '日期', mono: true, placeholder: '2026-09-20' },
  { key: 'summary', label: '概述', type: 'textarea', rows: 5, hint: '一两句话说清这一版解决了什么问题' },
  {
    key: 'sections',
    label: '分类明细',
    type: 'sections',
    hint: '## 新增 / ## 优化 / ## 修复 / ## 测试 各起一段，条目用 - 开头，续行缩进两格'
  }
]

const editor = ref(null)
const importing = ref(false)
const importError = ref('')
const importNotice = ref('')
const markdown = ref('')
const mode = ref('merge')
const fileInput = ref(null)

async function runImport() {
  const text = markdown.value.trim()
  if (!text) {
    importError.value = '请先粘贴 CHANGELOG.md 内容，或选择一个文件'
    return
  }
  importing.value = true
  importError.value = ''
  importNotice.value = ''
  try {
    const res = await api.post('/api/admin/import/changelog', { markdown: text, mode: mode.value }, true)
    importNotice.value = `已导入 ${res.imported} 条，当前共 ${res.total} 条（${res.mode === 'replace' ? '整包替换' : '按版本合并'}）`
    markdown.value = ''
    if (fileInput.value) fileInput.value.value = ''
    await editor.value?.reload()
  } catch (err) {
    importError.value = err instanceof ApiError ? err.message : '导入失败'
  } finally {
    importing.value = false
  }
}

async function pickFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  markdown.value = await file.text()
  importError.value = ''
}
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">更新日志</h1>
      <p class="muted small">
        官网「更新日志」页与首页的「最近更新」都读这份数据。同一版本号只保留一条，重复写入即覆盖。
      </p>
    </header>

    <CollectionEditor
      ref="editor"
      collection="changelog"
      id-key="version"
      label="版本记录"
      subtitle-key="date"
      :fields="FIELDS"
      :blank="{ version: '', date: '', summary: '', sections: '' }"
    />

    <!-- 从仓库同步：手工敲 34 条历史记录不现实，这里留一条正经的导入通道 -->
    <section class="card importer">
      <header class="importer__head">
        <h2 class="importer__title">从 CHANGELOG.md 导入</h2>
        <p class="muted small">
          解析 <span class="mono">## v0.1.384 - 2026-09-20</span> 形式的标题与小节，
          格式与仓库根目录的 CHANGELOG.md 一致，直接整份粘贴即可。
        </p>
      </header>

      <label class="field">
        <span>CHANGELOG.md 内容</span>
        <textarea
          v-model="markdown"
          class="textarea textarea--tall"
          spellcheck="false"
          placeholder="## v0.1.384 - 2026-09-20&#10;### 新增&#10;- ..."
        />
      </label>

      <div class="importer__row">
        <label class="btn btn--sm importer__file">
          选择文件
          <input ref="fileInput" type="file" accept=".md,.markdown,text/markdown" hidden @change="pickFile" />
        </label>

        <label class="importer__mode">
          <span class="muted small">导入方式</span>
          <select v-model="mode" class="select">
            <option value="merge">按版本合并（保留未提及的旧记录）</option>
            <option value="replace">整包替换（清空后写入）</option>
          </select>
        </label>

        <button class="btn btn--primary btn--sm" type="button" :disabled="importing" @click="runImport">
          {{ importing ? '导入中…' : '开始导入' }}
        </button>
      </div>

      <p v-if="importError" class="msg msg--error">{{ importError }}</p>
      <p v-else-if="importNotice" class="msg msg--ok">{{ importNotice }}</p>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 20px;
  max-width: 1180px;
}

.page__head {
  display: grid;
  gap: 6px;
}

.importer {
  display: grid;
  gap: 16px;
  max-width: 860px;
}

.importer__head {
  display: grid;
  gap: 6px;
}

.importer__title {
  font-size: 1rem;
  font-weight: 600;
}

.textarea--tall {
  min-height: 200px;
}

.importer__row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.importer__file {
  cursor: pointer;
}

.importer__mode {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
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
</style>
