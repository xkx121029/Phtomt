<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api } from '../../api/client.js'
import { formatDate } from '../../lib/format.js'

/**
 * 站点元信息。
 *
 * 这里改的是官网的「身份」——名字、标语、仓库地址、系统要求、技术栈、执行通道。
 * 首页、关于页、下载页的环境要求表都从这份数据派生，所以改一次全局生效。
 *
 * 走 PATCH 而不是 PUT：`updatedAt` 之类的字段由服务端维护，
 * 整包 PUT 会把它一起覆盖掉。
 */
const SIMPLE = [
  { key: 'name', label: '项目全名' },
  { key: 'shortName', label: '简称', hint: '顶栏与悬浮窗用' },
  { key: 'slug', label: '标识', mono: true, hint: '用于 npm 包名、目录名' },
  { key: 'tagline', label: '一句话标语', hint: '首页大标题下方那行' },
  { key: 'description', label: '项目简介', type: 'textarea', rows: 5 }
]

const LINKS = [
  { key: 'github', label: 'GitHub 仓库' },
  { key: 'gitee', label: 'Gitee 仓库' },
  { key: 'issues', label: '问题反馈' },
  { key: 'releases', label: 'Release 页' },
  { key: 'changelog', label: 'CHANGELOG' }
]

const REQS = [
  { key: 'minAndroid', label: '最低 Android' },
  { key: 'minApi', label: '最低 API', type: 'number' },
  { key: 'targetApi', label: '目标 API', type: 'number' },
  { key: 'arch', label: '架构' },
  { key: 'packageName', label: '包名', mono: true }
]

const form = reactive({
  ...Object.fromEntries(SIMPLE.map((f) => [f.key, ''])),
  version: '',
  buildNumber: '',
  links: Object.fromEntries(LINKS.map((f) => [f.key, ''])),
  requirements: Object.fromEntries(REQS.map((f) => [f.key, ''])),
  techStack: '',
  channels: ''
})

const meta = ref(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const techCount = computed(() => {
  try {
    const list = JSON.parse(form.techStack)
    return Array.isArray(list) ? list.length : 0
  } catch {
    return null
  }
})

function fill(site) {
  for (const f of SIMPLE) form[f.key] = site[f.key] ?? ''
  form.version = site.version ?? ''
  form.buildNumber = site.buildNumber ?? ''
  for (const f of LINKS) form.links[f.key] = site.links?.[f.key] ?? ''
  for (const f of REQS) form.requirements[f.key] = site.requirements?.[f.key] ?? ''
  form.techStack = JSON.stringify(site.techStack ?? [], null, 2)
  form.channels = JSON.stringify(site.channels ?? [], null, 2)
}

function parseJSON(text, label) {
  try {
    const value = JSON.parse(text || '[]')
    if (!Array.isArray(value)) throw new Error('not array')
    return value
  } catch {
    throw new Error(`${label} 不是合法的 JSON 数组`)
  }
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload = {
      ...Object.fromEntries(SIMPLE.map((f) => [f.key, form[f.key]])),
      version: form.version,
      buildNumber: form.buildNumber === '' ? undefined : Number(form.buildNumber),
      links: { ...form.links },
      requirements: {
        ...form.requirements,
        minApi: Number(form.requirements.minApi) || undefined,
        targetApi: Number(form.requirements.targetApi) || undefined
      },
      techStack: parseJSON(form.techStack, '技术栈'),
      channels: parseJSON(form.channels, '执行通道')
    }
    const next = await api.patch('/api/admin/site', payload, true)
    meta.value = next
    fill(next)
    notice.value = '已保存。首页与关于页下次加载即为新内容。'
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : err.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function reload() {
  loading.value = true
  try {
    const site = await api.adminGet('/api/admin/content/site')
    meta.value = site
    fill(site)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取站点信息失败'
  } finally {
    loading.value = false
  }
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <div>
        <h1 class="h2">站点信息</h1>
        <p class="muted small head__sub">
          官网的身份信息与系统要求都读这份数据。改完保存，首页、下载页、关于页一起生效。
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
        <h2 class="block__title">基本信息</h2>
        <div class="fields">
          <label v-for="f in SIMPLE" :key="f.key" class="field">
            <span>{{ f.label }}</span>
            <textarea v-if="f.type === 'textarea'" v-model="form[f.key]" class="textarea" :rows="f.rows || 4" />
            <input v-else v-model="form[f.key]" class="input" :class="{ mono: f.mono }" />
            <span v-if="f.hint" class="hint">{{ f.hint }}</span>
          </label>

          <label class="field">
            <span>当前版本号</span>
            <input v-model="form.version" class="input mono" />
            <span class="hint">只做展示。可下载的包由「版本与 APK」页维护。</span>
          </label>

          <label class="field">
            <span>构建号</span>
            <input v-model="form.buildNumber" class="input mono" type="number" />
          </label>
        </div>
      </section>

      <section class="card block">
        <h2 class="block__title">仓库与链接</h2>
        <p class="muted small">
          留空即不显示。静态站（GitHub Pages）没有后端时，下载按钮会回落到这里的 releases 地址。
        </p>
        <div class="fields">
          <label v-for="f in LINKS" :key="f.key" class="field">
            <span>{{ f.label }}</span>
            <input v-model="form.links[f.key]" class="input mono" type="url" placeholder="https://" />
          </label>
        </div>
      </section>

      <section class="card block">
        <h2 class="block__title">环境要求</h2>
        <div class="fields">
          <label v-for="f in REQS" :key="f.key" class="field">
            <span>{{ f.label }}</span>
            <input
              v-model="form.requirements[f.key]"
              class="input"
              :class="{ mono: f.mono || f.type === 'number' }"
              :type="f.type === 'number' ? 'number' : 'text'"
            />
          </label>
        </div>
      </section>

      <section class="card block">
        <h2 class="block__title">技术栈与执行通道</h2>
        <p class="muted small">
          这两项是结构化列表，直接改 JSON。技术栈元素形如
          <span class="mono">{ "name": "Kotlin", "version": "2.0.21" }</span>；
          通道元素需要 <span class="mono">id / name / level / desc / default</span> 五个字段，
          其中 <span class="mono">default: true</span> 的那条会在官网标成默认通道。
        </p>

        <div class="two">
          <label class="field">
            <span>
              技术栈
              <span v-if="techCount !== null" class="muted mono count">{{ techCount }} 项</span>
              <span v-else class="danger-text">JSON 格式有误</span>
            </span>
            <textarea v-model="form.techStack" class="textarea textarea--tall" spellcheck="false" />
          </label>

          <label class="field">
            <span>执行通道</span>
            <textarea v-model="form.channels" class="textarea textarea--tall" spellcheck="false" />
          </label>
        </div>
      </section>

      <p v-if="meta?.updatedAt" class="muted small">
        上次更新 <span class="mono">{{ formatDate(meta.updatedAt) }}</span>
      </p>
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
}

.block {
  display: grid;
  gap: 14px;
  align-content: start;
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

.count {
  margin-left: 6px;
  font-size: var(--t-xs);
  font-weight: 400;
}

.danger-text {
  margin-left: 6px;
  font-size: var(--t-xs);
  color: var(--danger);
}

.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}

.textarea--tall {
  min-height: 240px;
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
