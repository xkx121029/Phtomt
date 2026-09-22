<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api } from '../../api/client.js'
import { stepsToText, textToSteps } from './sections.js'

/**
 * 路线图。
 *
 * 这份数据是对象而不是数组（`{ updatedAt, note, phases: [] }`），
 * phases 里还嵌着 items，所以走不了通用的 CollectionEditor——那个组件只认「集合 + 单条主键」。
 * 整包 PUT，不做单条增删。
 *
 * items 复用了场景步骤那套文本编码（`标题 | 说明`）：
 * 两者形状一模一样，没必要再造一种写法。
 */
const STATES = [
  { value: 'done', label: '已交付' },
  { value: 'doing', label: '正在做' },
  { value: 'next', label: '接下来' },
  { value: 'later', label: '暂不计划' }
]

const form = reactive({ updatedAt: '', note: '' })
const phases = ref([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')

function fill(data) {
  form.updatedAt = data?.updatedAt ?? ''
  form.note = data?.note ?? ''
  phases.value = (data?.phases ?? []).map((p) => ({
    id: p.id ?? '',
    title: p.title ?? '',
    state: p.state ?? 'next',
    summary: p.summary ?? '',
    itemsText: stepsToText(p.items)
  }))
}

function addPhase() {
  phases.value.push({ id: '', title: '', state: 'next', summary: '', itemsText: '' })
}

function removePhase(index) {
  const phase = phases.value[index]
  if (!window.confirm(`确定删除「${phase.title || phase.id || '这一档'}」？此操作不可撤销。`)) return
  phases.value.splice(index, 1)
}

/** 顺序就是页面上的顺序，「暂不计划」放最后是有意的，所以只做相邻交换 */
function move(index, delta) {
  const to = index + delta
  if (to < 0 || to >= phases.value.length) return
  const list = phases.value
  ;[list[index], list[to]] = [list[to], list[index]]
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    fill(await api.adminGet('/api/admin/content/roadmap'))
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取路线图失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  error.value = ''
  notice.value = ''
  const blank = phases.value.find((p) => !String(p.id).trim() || !String(p.title).trim())
  if (blank) {
    error.value = '每一档都需要标识和标题'
    return
  }
  saving.value = true
  try {
    await api.put(
      '/api/admin/content/roadmap',
      {
        updatedAt: form.updatedAt,
        note: form.note,
        phases: phases.value.map((p) => ({
          id: p.id.trim(),
          title: p.title.trim(),
          state: p.state,
          summary: p.summary,
          items: textToSteps(p.itemsText)
        }))
      },
      true
    )
    await reload()
    notice.value = '已保存。路线图页下次加载即为新内容。'
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <div>
        <h1 class="h2">路线图</h1>
        <p class="muted small head__sub">
          四档按数组顺序展示。条目的写法是「标题 | 说明」，只按第一个竖线切分。
          说明里写依据——已交付项以仓库代码与 CHANGELOG 为准，别写还没落地的东西。
        </p>
      </div>
      <div class="row">
        <button class="btn btn--sm" type="button" @click="reload">重新读取</button>
        <button class="btn btn--primary btn--sm" type="button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="msg msg--error">{{ error }}</p>
    <p v-else-if="notice" class="msg msg--ok">{{ notice }}</p>
    <p v-if="loading" class="muted">正在读取…</p>

    <template v-else>
      <section class="card block">
        <h2 class="block__title">抬头</h2>
        <div class="fields">
          <label class="field">
            <span>更新日期</span>
            <input v-model="form.updatedAt" class="input mono" type="date" />
            <span class="hint">页面上显示为「更新于 …」。改内容时顺手改一下。</span>
          </label>

          <label class="field">
            <span>说明</span>
            <textarea v-model="form.note" class="textarea" rows="3" />
            <span class="hint">写在档位上方的一句话，讲清楚这份路线图的口径。</span>
          </label>
        </div>
      </section>

      <section v-for="(phase, index) in phases" :key="index" class="card block">
        <header class="block__head">
          <h2 class="block__title">{{ phase.title || '未命名档位' }}</h2>
          <div class="row">
            <button class="btn btn--sm" type="button" :disabled="index === 0" @click="move(index, -1)">↑</button>
            <button
              class="btn btn--sm"
              type="button"
              :disabled="index === phases.length - 1"
              @click="move(index, 1)"
            >↓</button>
            <button class="btn btn--sm btn--danger" type="button" @click="removePhase(index)">删除</button>
          </div>
        </header>

        <div class="fields">
          <label class="field">
            <span>标识</span>
            <input v-model="phase.id" class="input mono" placeholder="shipped" />
          </label>

          <label class="field">
            <span>标题</span>
            <input v-model="phase.title" class="input" placeholder="已交付" />
          </label>

          <label class="field">
            <span>状态</span>
            <select v-model="phase.state" class="select">
              <option v-for="s in STATES" :key="s.value" :value="s.value">{{ s.label }}</option>
            </select>
            <span class="hint">决定卡片左侧竖线的颜色，四档各不相同。</span>
          </label>

          <label class="field">
            <span>档位说明</span>
            <textarea v-model="phase.summary" class="textarea" rows="3" />
          </label>

          <label class="field">
            <span>条目</span>
            <textarea
              v-model="phase.itemsText"
              class="textarea textarea--tall"
              rows="8"
              spellcheck="false"
            />
            <span class="hint">一行一条：「标题 | 说明」。只写标题也可以。</span>
          </label>
        </div>
      </section>

      <div class="row">
        <button class="btn btn--sm" type="button" @click="addPhase">+ 新增档位</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 1080px;
}

.page__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.head__sub {
  margin-top: 6px;
  max-width: 62ch;
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
  flex-wrap: wrap;
}

.block__title {
  font-size: 1rem;
  font-weight: 600;
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
  min-height: 180px;
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
