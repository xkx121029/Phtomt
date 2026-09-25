<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError, api } from '../../api/client.js'

/**
 * AI 问答配置。
 *
 * 两件事这里负责：把模型接口配好，以及让运营方看得见「AI 到底拿到了多少项目资料」。
 *
 * 密钥永不回显——服务端只给指纹与首尾几位，所以「留空」在这里的语义是「沿用已存的那把」，
 * 而不是「清空」。要清空得显式勾选，且必须先关掉总开关：没有密钥的接口是测不通的。
 *
 * 接口三要素（地址 / 模型 / 密钥）改了就必须重新测一次才让保存（与 App 端
 * 「设置仅在模型测试通过后才能保存」同一条规矩）：配错的接口在前台表现为
 * 「入口在、问了没反应」，比入口不出现更难排查。
 */

const KNOWN = [
  { key: 'docs', label: '文档' },
  { key: 'changelog', label: '更新日志' },
  { key: 'features', label: '功能特性' },
  { key: 'scenarios', label: '场景示例' },
  { key: 'roadmap', label: '路线图' },
  { key: 'faq', label: '常见问题' },
  { key: 'site', label: '站点信息' }
]

const form = reactive({
  enabled: false,
  baseUrl: '',
  model: '',
  apiKey: '',
  temperature: 0.3,
  maxTokens: 1024,
  contextChars: 24000,
  historyTurns: 6,
  rateLimitPerHour: 30,
  assistantName: '项目助手',
  greeting: '',
  suggestions: '',
  extraPrompt: ''
})

const base = ref(null)
const defaults = ref({ suggestions: [], greeting: '', contextChars: 24000 })
const knowledge = ref({})
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const clearKey = ref(false)
const error = ref('')
const notice = ref('')
const testResult = ref(null)

/** 上次测试通过时，表单里那三样长什么样；任一项变了就得重测 */
const testSnapshot = ref('')

const triple = computed(() => `${form.baseUrl.trim()}|${form.model.trim()}|${form.apiKey.trim()}`)

/** 接口三要素相对「已保存的配置」有没有变 */
const endpointDirty = computed(() => {
  if (!base.value) return true
  return (
    form.baseUrl.trim() !== (base.value.baseUrl || '') ||
    form.model.trim() !== (base.value.model || '') ||
    Boolean(form.apiKey.trim())
  )
})

const testPassed = computed(() => Boolean(testResult.value?.ok) && testSnapshot.value === triple.value)

const canSave = computed(() => {
  if (saving.value) return false
  // 清空密钥必然让接口不可用，只允许在关掉总开关的前提下保存
  if (clearKey.value) return !form.enabled
  if (!endpointDirty.value) return true
  return testPassed.value
})

const saveBlockReason = computed(() => {
  if (clearKey.value && form.enabled) return '要清空密钥，请先关闭上方总开关——没有密钥的接口无法工作。'
  if (!clearKey.value && endpointDirty.value && !testPassed.value) {
    return '接口地址 / 模型 / 密钥有改动，请先点「测试连接」通过后再保存。'
  }
  return ''
})

const totalChars = computed(() =>
  Object.values(knowledge.value).reduce((n, item) => n + (item.chars || 0), 0)
)

function fill(config) {
  for (const key of Object.keys(form)) {
    if (key === 'apiKey') continue
    if (config[key] !== undefined && config[key] !== null) form[key] = config[key]
  }
  form.apiKey = ''
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const payload = await api.adminGet('/api/admin/ai')
    base.value = payload.config
    defaults.value = payload.defaults
    knowledge.value = payload.knowledge || {}
    fill(payload.config)
    clearKey.value = false
    testResult.value = null
    testSnapshot.value = ''
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取 AI 配置失败'
  } finally {
    loading.value = false
  }
}

async function test() {
  testing.value = true
  testResult.value = null
  notice.value = ''
  error.value = ''
  try {
    const body = {
      baseUrl: form.baseUrl,
      model: form.model,
      temperature: Number(form.temperature) || 0
    }
    if (form.apiKey.trim()) body.apiKey = form.apiKey.trim()
    const res = await api.post('/api/admin/ai/test', body, true)
    testResult.value = res
    testSnapshot.value = res.ok ? triple.value : ''
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '测试失败'
  } finally {
    testing.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload = {
      enabled: form.enabled,
      baseUrl: form.baseUrl,
      model: form.model,
      temperature: Number(form.temperature),
      maxTokens: Number(form.maxTokens),
      contextChars: Number(form.contextChars),
      historyTurns: Number(form.historyTurns),
      rateLimitPerHour: Number(form.rateLimitPerHour),
      assistantName: form.assistantName,
      greeting: form.greeting,
      suggestions: form.suggestions,
      extraPrompt: form.extraPrompt
    }
    if (form.apiKey.trim()) payload.apiKey = form.apiKey.trim()
    if (clearKey.value) payload.clearKey = true

    const next = await api.put('/api/admin/ai', payload, true)
    base.value = next
    fill(next)
    clearKey.value = false
    testSnapshot.value = ''
    notice.value = next.warning || '已保存。前台下次打开页面即为新配置。'
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '保存失败'
  } finally {
    saving.value = false
  }
}

/* ---------- 知识库预览 ---------- */

const probe = reactive({ question: '手机需要 Root 吗？', contextChars: 24000 })
const preview = ref(null)
const previewing = ref(false)

async function runPreview() {
  previewing.value = true
  error.value = ''
  try {
    preview.value = await api.post(
      '/api/admin/ai/preview',
      { question: probe.question, contextChars: Number(probe.contextChars) },
      true
    )
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '预览失败'
  } finally {
    previewing.value = false
  }
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <div>
        <h1 class="h2">AI 问答</h1>
        <p class="muted small head__sub">
          配好一个 OpenAI 兼容端点，官网右下角就会出现问答入口，并多出一个
          <RouterLink to="/ask" class="link">/ask</RouterLink> 独立页。
          每次提问会自动带上站内的功能、文档、常见问题与更新日志。
        </p>
      </div>
      <div class="row">
        <RouterLink to="/ask" class="btn btn--sm">打开前台</RouterLink>
        <button class="btn btn--sm" type="button" @click="reload">重新读取</button>
        <button
          class="btn btn--primary btn--sm"
          type="button"
          :disabled="!canSave"
          :title="saveBlockReason"
          @click="save"
        >
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="msg msg--error">{{ error }}</p>
    <p v-else-if="notice" class="msg msg--ok">{{ notice }}</p>
    <p v-if="saveBlockReason && !saving" class="msg msg--warn">{{ saveBlockReason }}</p>

    <p v-if="loading" class="muted">正在读取…</p>

    <template v-else>
      <!-- 1. 开关与外观 -->
      <section class="card block">
        <h2 class="block__title">开关与外观</h2>

        <label class="switch">
          <input v-model="form.enabled" type="checkbox" />
          <span class="switch__track" aria-hidden="true"></span>
          <span class="switch__text">
            <strong>在官网上启用问答</strong>
            <em>关掉后前台不显示任何入口，接口也会直接拒绝提问。</em>
          </span>
        </label>

        <div class="fields">
          <label class="field">
            <span>助手名字</span>
            <input v-model="form.assistantName" class="input" />
            <span class="hint">浮窗标题与回答署名用它，最多 40 字。</span>
          </label>

          <label class="field">
            <span>开场白 <span class="muted small">留空则用「我是 XX 的答疑助手…」</span></span>
            <textarea v-model="form.greeting" class="textarea textarea--short" rows="3" />
          </label>

          <label class="field">
            <span>
              推荐提问
              <span class="muted small">一行一条，最多显示 6 条；留空用默认四条</span>
            </span>
            <textarea v-model="form.suggestions" class="textarea textarea--short" rows="6" />
          </label>
        </div>
      </section>

      <!-- 2. 接口 -->
      <section class="card block">
        <h2 class="block__title">模型接口</h2>
        <p class="muted small">
          任意 OpenAI 兼容端点：官方 API、自建 Ollama、LM Studio、one-api 都行。地址填到
          <span class="mono">/v1</span> 即可，服务端会自动补上
          <span class="mono">/chat/completions</span>。
        </p>

        <div class="fields">
          <label class="field">
            <span>接口地址</span>
            <input v-model="form.baseUrl" class="input mono" type="url" placeholder="https://api.example.com/v1" />
            <span v-if="base?.chatUrl" class="hint mono">实际请求：{{ base.chatUrl }}</span>
          </label>

          <div class="two">
            <label class="field">
              <span>模型名称</span>
              <input v-model="form.model" class="input mono" placeholder="gpt-4o-mini" />
            </label>

            <label class="field">
              <span>
                API 密钥
                <span v-if="base?.hasKey && !clearKey" class="muted small mono">已保存 {{ base.keyHint }}</span>
              </span>
              <input
                v-model="form.apiKey"
                class="input mono"
                type="password"
                autocomplete="off"
                :disabled="clearKey"
                :placeholder="base?.hasKey ? '留空表示沿用已保存的密钥' : 'sk-…'"
              />
            </label>
          </div>

          <label class="switch">
            <input v-model="clearKey" type="checkbox" :disabled="!base?.hasKey" />
            <span class="switch__track" aria-hidden="true"></span>
            <span class="switch__text">
              <strong>清空已保存的密钥</strong>
              <em>只在停用问答时才允许：保存后前台入口随之消失。</em>
            </span>
          </label>

          <div class="row row--wrap">
            <button class="btn btn--sm" type="button" :disabled="testing" @click="test">
              {{ testing ? '测试中…' : '测试连接' }}
            </button>
            <span v-if="base?.updatedAt" class="muted small">上次保存 {{ base.updatedAt.slice(0, 19).replace('T', ' ') }}</span>
          </div>

          <p v-if="testResult" :class="['msg', testResult.ok ? 'msg--ok' : 'msg--error']">
            <template v-if="testResult.ok">
              连接正常（{{ testResult.latencyMs }} ms）：{{ testResult.reply }}
              <span class="mono test__fp">指纹 {{ testResult.endpointFingerprint }}</span>
            </template>
            <template v-else>{{ testResult.message }}</template>
          </p>
        </div>
      </section>

      <!-- 3. 生成参数 -->
      <section class="card block">
        <h2 class="block__title">生成与限流</h2>
        <div class="three">
          <label class="field">
            <span>温度</span>
            <input v-model.number="form.temperature" class="input mono" type="number" step="0.1" min="0" max="2" />
            <span class="hint">0~2。问答场景不需要发挥，0.2~0.4 足够。</span>
          </label>

          <label class="field">
            <span>单次最大输出</span>
            <input v-model.number="form.maxTokens" class="input mono" type="number" min="128" max="8192" step="128" />
            <span class="hint">token，128~8192。</span>
          </label>

          <label class="field">
            <span>带回历史轮数</span>
            <input v-model.number="form.historyTurns" class="input mono" type="number" min="0" max="20" />
            <span class="hint">0 表示每问都是新话题。</span>
          </label>

          <label class="field">
            <span>每人每小时上限</span>
            <input v-model.number="form.rateLimitPerHour" class="input mono" type="number" min="0" max="1000" />
            <span class="hint">按 IP 计，0 表示不限。</span>
          </label>
        </div>
      </section>

      <!-- 4. 知识注入 -->
      <section class="card block">
        <h2 class="block__title">知识注入</h2>
        <p class="muted small">
          站点信息、功能标题、常见问题全文与场景 / 路线图骨架是「每次都带」的常驻部分；
          文档正文、更新日志条目与各条细节会在提问时按关键词检索后追加。
        </p>

        <div class="fields">
          <label class="field">
            <span>知识注入预算</span>
            <input v-model.number="form.contextChars" class="input mono" type="number" min="2000" max="120000" step="1000" />
            <span class="hint">
              每次提问最多注入多少字符，2000~120000。当前站内资料合计约
              {{ Math.round(totalChars / 1000) }}k 字符，预算越大越准，也越贵。
            </span>
          </label>

          <label class="field">
            <span>补充提示词 <span class="muted small">追加在通用规则之后，优先级更高</span></span>
            <textarea v-model="form.extraPrompt" class="textarea" rows="4" />
          </label>
        </div>

        <div class="stat">
          <div v-for="item in KNOWN" :key="item.key" class="stat__cell">
            <span class="stat__label">{{ item.label }}</span>
            <span class="stat__value mono">
              {{ knowledge[item.key]?.count ?? '—' }}
              <em>条</em>
            </span>
            <span class="stat__chars mono">{{ Math.round((knowledge[item.key]?.chars || 0) / 1000) }}k 字</span>
          </div>
        </div>

        <div class="probe">
          <label class="field probe__q">
            <span>拿一句话看看实际会注入什么</span>
            <input v-model="probe.question" class="input" />
          </label>
          <label class="field probe__n">
            <span>预算</span>
            <input v-model.number="probe.contextChars" class="input mono" type="number" step="1000" />
          </label>
          <button class="btn btn--sm probe__go" type="button" :disabled="previewing" @click="runPreview">
            {{ previewing ? '生成中…' : '预览' }}
          </button>
        </div>

        <template v-if="preview">
          <p class="muted small">
            实际注入 <span class="mono">{{ preview.chars }}</span> 字符
            <template v-if="preview.truncated">（已截断，建议调大预算）</template>
            <template v-if="preview.sources?.length">
              ，按需检索命中：{{ preview.sources.map((s) => s.title).join('、') }}
            </template>
          </p>
          <pre class="code preview">{{ preview.text }}</pre>
        </template>
      </section>
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

.link {
  color: var(--brand);
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

.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.three {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.textarea--short {
  min-height: 0;
}

/* ---------- 开关行 ---------- */

.switch {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  cursor: pointer;
}

.switch input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.switch__track {
  position: relative;
  width: 40px;
  height: 24px;
  flex: none;
  margin-top: 2px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-pill);
  background: var(--paper-sunken);
  transition:
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease;
}

.switch__track::after {
  content: "";
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--paper-raised);
  box-shadow: var(--shadow-1);
  transition: transform var(--dur-ui) var(--ease-out);
}

.switch input:checked + .switch__track {
  border-color: var(--brand);
  background: var(--brand);
}

.switch input:checked + .switch__track::after {
  transform: translateX(16px);
}

.switch input:focus-visible + .switch__track {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.switch__text {
  display: grid;
  gap: 2px;
}

.switch__text strong {
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--ink);
}

.switch__text em {
  font-style: normal;
  font-size: var(--t-xs);
  color: var(--ink-3);
  line-height: 1.7;
}

/* ---------- 知识库规模 ---------- */

.stat {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 1px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--line);
}

.stat__cell {
  display: grid;
  gap: 2px;
  padding: 12px 14px;
  background: var(--paper-raised);
}

.stat__label {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.stat__value {
  font-size: 1.1rem;
  font-weight: 600;
}

.stat__value em {
  font-style: normal;
  font-size: var(--t-xs);
  font-weight: 400;
  color: var(--ink-3);
  margin-left: 3px;
}

.stat__chars {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

/* ---------- 预览 ---------- */

.probe {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.probe__q {
  flex: 1;
  min-width: 240px;
}

.probe__n {
  width: 140px;
  flex: none;
}

.probe__go {
  flex: none;
}

.preview {
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-size: 0.78rem;
  line-height: 1.75;
}

/* ---------- 消息条 ---------- */

.msg {
  padding: 10px 14px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  line-height: 1.7;
}

.msg--error {
  background: var(--danger-wash);
  color: var(--danger);
}

.msg--warn {
  background: var(--warn-wash);
  color: var(--warn);
}

.msg--ok {
  background: var(--ok-wash);
  color: var(--ok);
}

.test__fp {
  display: inline-block;
  margin-left: 8px;
  opacity: 0.7;
}
</style>
