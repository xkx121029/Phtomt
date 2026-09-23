<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, setToken } from '../../api/client.js'
import { formatDate } from '../../lib/format.js'

/**
 * 修改管理口令。
 *
 * 这里要回答的核心问题是「改完之后到底以哪个为准」，所以状态区和表单一样重要：
 * 环境变量 ADMIN_PASSWORD 与后台设置的值会同时存在，不写清楚会让人以为改了个寂寞。
 */
const status = ref(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })

const minLength = computed(() => status.value?.minLength ?? 8)

const mismatch = computed(
  () => Boolean(form.confirmPassword) && form.newPassword !== form.confirmPassword
)

const canSubmit = computed(
  () =>
    !saving.value &&
    Boolean(form.currentPassword) &&
    form.newPassword.length >= minLength.value &&
    form.newPassword === form.confirmPassword
)

async function reloadStatus() {
  loading.value = true
  try {
    status.value = await api.adminGet('/api/admin/password')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取口令状态失败'
  } finally {
    loading.value = false
  }
}

function reset() {
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}

async function submit() {
  error.value = ''
  notice.value = ''
  saving.value = true
  try {
    const res = await api.post(
      '/api/admin/password',
      { currentPassword: form.currentPassword, newPassword: form.newPassword },
      true
    )
    // 服务端换了凭据，旧令牌当场作废；把补发的新令牌存下来，
    // 否则下一次保存内容就会「令牌无效」被踢回登录页。
    if (res?.token) setToken(res.token)
    reset()
    notice.value = '口令已更新。其他设备上已登录的会话会立即失效，需要重新登录。'
    await reloadStatus()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '修改失败'
  } finally {
    saving.value = false
  }
}

onMounted(reloadStatus)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">修改密码</h1>
      <p class="muted small">
        管理后台只有一个账号，口令即全部凭据。修改后所有已签发的登录令牌立即作废。
      </p>
    </header>

    <p v-if="loading" class="muted">正在读取…</p>

    <template v-else>
      <p v-if="error" class="msg msg--error">{{ error }}</p>
      <p v-else-if="notice" class="msg msg--ok">{{ notice }}</p>

      <section v-if="status" class="card block">
        <h2 class="block__title">当前状态</h2>

        <dl class="facts">
          <dt>口令来源</dt>
          <dd>
            <template v-if="status.source === 'file'">
              后台设置的值<span class="muted">（已覆盖环境变量）</span>
            </template>
            <template v-else>
              环境变量 <code>ADMIN_PASSWORD</code>
            </template>
          </dd>

          <template v-if="status.source === 'file'">
            <dt>上次修改</dt>
            <dd>{{ formatDate(status.updatedAt) }}</dd>
          </template>
        </dl>

        <p v-if="status.signingKeyIsDefault" class="msg msg--warn">
          登录令牌的签名密钥 <code>ADMIN_SECRET</code> 仍是代码里的默认值，而仓库是公开的——
          知道它的人可以伪造一张登录令牌，绕过口令。建议在 <code>.env</code> 里配一个随机字符串
          （例如 <code>openssl rand -base64 32</code> 的输出）并重启容器。
        </p>

        <p v-if="status.envPasswordSet && status.source === 'file'" class="msg msg--info">
          <code>.env</code> 里的 <code>ADMIN_PASSWORD</code> 仍在，但已被这里设置的口令盖住。
          留着它是有用的：哪天忘了新口令，删掉 <code>server/data/admin.json</code> 重启，
          就会退回用环境变量里那个口令登录。
        </p>
      </section>

      <section class="card block">
        <h2 class="block__title">设置新口令</h2>

        <form class="fields" @submit.prevent="submit">
          <label class="field">
            <span>当前口令</span>
            <input
              v-model="form.currentPassword"
              class="input"
              type="password"
              autocomplete="current-password"
            />
          </label>

          <label class="field">
            <span>新口令</span>
            <input
              v-model="form.newPassword"
              class="input"
              type="password"
              autocomplete="new-password"
            />
            <span class="hint">至少 {{ minLength }} 位。</span>
          </label>

          <label class="field">
            <span>确认新口令</span>
            <input
              v-model="form.confirmPassword"
              class="input"
              type="password"
              autocomplete="new-password"
            />
            <span v-if="mismatch" class="hint hint--bad">两次输入不一致。</span>
          </label>

          <div class="row">
            <button class="btn btn--primary" type="submit" :disabled="!canSubmit">
              {{ saving ? '保存中…' : '保存新口令' }}
            </button>
            <button class="btn" type="button" :disabled="saving" @click="reset">清空</button>
          </div>
        </form>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 760px;
}

.page__head {
  display: grid;
  gap: 6px;
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

.facts {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 8px 20px;
  margin: 0;
  font-size: var(--t-sm);
}

.facts dt {
  color: var(--ink-3);
}

.facts dd {
  margin: 0;
  color: var(--ink);
}

.fields {
  display: grid;
  gap: 16px;
}

.field > span:first-child {
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--ink-2);
}

.hint--bad {
  color: var(--danger);
}

.msg {
  padding: 10px 14px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  line-height: 1.75;
}

.msg--error {
  background: var(--danger-wash);
  color: var(--danger);
}

.msg--ok {
  background: var(--ok-wash);
  color: var(--ok);
}

.msg--warn {
  background: var(--warn-wash);
  color: var(--warn);
}

.msg--info {
  background: var(--paper-sunken);
  color: var(--ink-2);
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--paper-sunken);
  font-family: var(--font-mono);
  font-size: 0.82em;
}
</style>