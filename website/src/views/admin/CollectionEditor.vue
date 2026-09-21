<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useCollection } from './useCollection.js'
import { sectionsToText, textToSections } from './sections.js'

/**
 * 集合编辑器：左侧条目列表，右侧表单。
 *
 * 为什么做成通用组件而不是四个页面各写一遍：四个集合的接口形状完全一致，
 * 差别只在字段定义上。把差异收敛成 `fields` 描述，页面就只剩十几行配置。
 */
const props = defineProps({
  collection: { type: String, required: true },
  idKey: { type: String, required: true },
  label: { type: String, required: true },
  fields: { type: Array, required: true },
  /** 新建时的初始值 */
  blank: { type: Object, default: () => ({}) },
  /** 列表里显示的副标题字段 */
  subtitleKey: { type: String, default: '' }
})

const { items, loading, busy, error, notice, load, create, update, remove } = useCollection(
  props.collection,
  props.idKey
)

const selectedId = ref('')
const query = ref('')
const mode = ref('edit') // edit | create
const form = reactive({})
const showRaw = ref(false)
const rawText = ref('')

const MULTI = props.fields.filter((f) => f.type === 'list' || f.type === 'sections').map((f) => f.key)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return items.value
  return items.value.filter((item) =>
    JSON.stringify(item).toLowerCase().includes(q)
  )
})

const selected = computed(() => items.value.find((it) => String(it[props.idKey]) === selectedId.value) || null)
const isNew = computed(() => mode.value === 'create')

function titleOf(item) {
  return item?.title || item?.q || item?.[props.idKey] || '未命名'
}

function toForm(item) {
  const out = {}
  for (const field of props.fields) {
    const value = item?.[field.key]
    if (field.type === 'list') out[field.key] = Array.isArray(value) ? value.join('\n') : ''
    else if (field.type === 'sections') out[field.key] = sectionsToText(value)
    else if (field.type === 'switch') out[field.key] = value !== false
    else out[field.key] = value ?? ''
  }
  return out
}

function fromForm() {
  const out = {}
  for (const field of props.fields) {
    const value = form[field.key]
    if (field.type === 'list') out[field.key] = String(value || '').split('\n').map((s) => s.trim()).filter(Boolean)
    else if (field.type === 'sections') out[field.key] = textToSections(value)
    else if (field.type === 'number') out[field.key] = value === '' ? undefined : Number(value)
    else if (field.type === 'switch') out[field.key] = Boolean(value)
    else out[field.key] = typeof value === 'string' ? value : value
  }
  return out
}

function fill(item) {
  const next = toForm(item)
  for (const key of Object.keys(form)) delete form[key]
  Object.assign(form, next)
  rawText.value = JSON.stringify(item ?? {}, null, 2)
}

function pick(item) {
  mode.value = 'edit'
  selectedId.value = String(item[props.idKey])
  fill(item)
}

function startCreate() {
  mode.value = 'create'
  selectedId.value = ''
  fill({ ...props.blank })
}

async function save() {
  const payload = fromForm()
  if (!String(payload[props.idKey] || '').trim()) {
    return
  }
  const ok = isNew.value
    ? await create(payload)
    : await update(selectedId.value, payload)
  if (ok) {
    mode.value = 'edit'
    selectedId.value = String(payload[props.idKey])
    const fresh = items.value.find((it) => String(it[props.idKey]) === selectedId.value)
    if (fresh) fill(fresh)
  }
}

async function destroy() {
  if (!selected.value) return
  const id = selectedId.value
  if (!window.confirm(`确定删除「${titleOf(selected.value)}」？此操作不可撤销。`)) return
  const ok = await remove(id)
  if (ok) {
    selectedId.value = ''
    fill({ ...props.blank })
  }
}

async function saveRaw() {
  try {
    const parsed = JSON.parse(rawText.value)
    const ok = await update(selectedId.value, parsed)
    if (ok) {
      const fresh = items.value.find((it) => String(it[props.idKey]) === selectedId.value)
      if (fresh) fill(fresh)
    }
  } catch {
    // 直接改 JSON 时语法错误很常见，用 alert 明确说清楚，不要静默失败
    window.alert('JSON 解析失败，请检查括号与逗号')
  }
}

watch(showRaw, (open) => {
  if (open && selected.value) rawText.value = JSON.stringify(selected.value, null, 2)
})

onMounted(async () => {
  await load()
  if (items.value.length) pick(items.value[0])
  else startCreate()
})

// 外部批量写入（如导入 CHANGELOG.md）后需要刷新列表，暴露一个 reload 给父组件
defineExpose({ reload: load })
</script>

<template>
  <div class="editor">
    <!-- 左：列表 -->
    <aside class="list">
      <div class="list__head">
        <input v-model="query" class="input" type="search" :placeholder="`搜索${label}…`" />
        <button class="btn btn--primary btn--sm list__new" type="button" @click="startCreate">
          + 新建
        </button>
      </div>

      <p v-if="loading" class="muted small list__state">正在读取…</p>
      <p v-else-if="!filtered.length" class="muted small list__state">没有条目。</p>

      <ul class="list__items">
        <li v-for="item in filtered" :key="item[props.idKey]">
          <button
            type="button"
            :class="['list__item', { 'list__item--on': !isNew && String(item[props.idKey]) === selectedId }]"
            @click="pick(item)"
          >
            <span class="list__title">{{ titleOf(item) }}</span>
            <span class="mono list__id">{{ item[props.idKey] }}</span>
            <span v-if="subtitleKey && item[subtitleKey]" class="list__sub">{{ item[subtitleKey] }}</span>
          </button>
        </li>
      </ul>
    </aside>

    <!-- 右：表单 -->
    <section class="form">
      <header class="form__head">
        <h2 class="form__title">
          <template v-if="isNew">新建{{ label }}</template>
          <template v-else>{{ titleOf(selected) }}</template>
        </h2>
        <div class="form__actions">
          <button
            v-if="!isNew"
            class="btn btn--sm"
            type="button"
            @click="showRaw = !showRaw"
          >{{ showRaw ? '表单编辑' : 'JSON 编辑' }}</button>
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

      <!-- JSON 直改：字段没覆盖到的内容不用改代码 -->
      <template v-if="showRaw">
        <label class="field">
          <span>原始 JSON</span>
          <textarea v-model="rawText" class="textarea textarea--tall" spellcheck="false" />
        </label>
        <div class="row">
          <button class="btn btn--primary btn--sm" type="button" @click="saveRaw">写入这段 JSON</button>
          <span class="hint">会与现有字段合并，未写的字段保持原值。</span>
        </div>
      </template>

      <!-- 表单 -->
      <div v-else class="fields">
        <label v-for="field in props.fields" :key="field.key" class="field">
          <span>{{ field.label }}</span>

          <select v-if="field.type === 'select'" v-model="form[field.key]" class="select">
            <option v-for="opt in field.options" :key="opt.value ?? opt" :value="opt.value ?? opt">
              {{ opt.label ?? opt }}
            </option>
          </select>

          <label v-else-if="field.type === 'switch'" class="switch">
            <input v-model="form[field.key]" type="checkbox" />
            <span>{{ field.hint || '开启' }}</span>
          </label>

          <textarea
            v-else-if="field.type === 'textarea' || field.type === 'list' || field.type === 'sections'"
            v-model="form[field.key]"
            class="textarea"
            :class="{ 'textarea--tall': field.type === 'sections' || field.rows > 8 }"
            :rows="field.rows || 5"
            spellcheck="false"
          />

          <input
            v-else
            v-model="form[field.key]"
            class="input"
            :class="{ mono: field.mono }"
            :type="field.type === 'number' ? 'number' : 'text'"
            :placeholder="field.placeholder || ''"
            :disabled="field.readonly && !isNew"
          />

          <span v-if="field.hint" class="hint">{{ field.hint }}</span>
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped>
.editor {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

/* ---------- 列表 ---------- */

.list {
  position: sticky;
  top: 20px;
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--paper-raised);
}

.list__head {
  display: grid;
  gap: 8px;
}

.list__new {
  width: 100%;
}

.list__state {
  padding: 8px 2px;
}

.list__items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 2px;
  max-height: calc(100vh - 300px);
  overflow-y: auto;
}

.list__item {
  display: grid;
  gap: 2px;
  width: 100%;
  padding: 9px 10px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .list__item:hover {
    background: var(--paper-sunken);
  }
}

.list__item--on {
  background: var(--brand-wash);
}

.list__title {
  font-size: var(--t-sm);
  font-weight: 500;
  line-height: 1.5;
}

.list__id {
  font-size: 0.7rem;
  color: var(--ink-3);
}

.list__sub {
  font-size: var(--t-xs);
  color: var(--ink-3);
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 表单 ---------- */

.form {
  display: grid;
  gap: 16px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--paper-raised);
  min-width: 0;
}

.form__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.form__title {
  font-size: 1.05rem;
  font-weight: 600;
}

.form__actions {
  display: flex;
  gap: 8px;
}

.fields {
  display: grid;
  gap: 16px;
  max-width: 760px;
}

.field > span:first-child {
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--ink-2);
}

.textarea--tall {
  min-height: 280px;
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  cursor: pointer;
}

.switch input {
  width: 16px;
  height: 16px;
  accent-color: var(--brand);
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

@media (max-width: 900px) {
  .editor {
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
