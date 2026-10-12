<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api } from '../../api/client.js'
import { formatDate } from '../../lib/format.js'

/**
 * 蓝奏云镜像与自动更新配置。
 *
 * 自动上传用的是蓝奏云的网页接口（非官方），随时可能失效；因此这一页同时提供
 * 「测试登录 / 手动补传 / 手填分享链接+提取码」三条自救路径，接口挂了也能维持镜像可用。
 */

const config = reactive({
  enabled: false,
  autoMirror: true,
  preferMirror: true,
  username: '',
  password: '',
  folderId: -1,
  domain: 'https://www.lanzoue.com'
})

const passwordSet = ref(false)
const cookieSet = ref(false)
const mirrors = ref({})
const files = ref([])
const folders = ref([])
const loading = ref(true)
const busy = ref('')
const error = ref('')
const notice = ref('')

/** 人机验证弹窗：服务端返回 needCaptcha 时打开，人工照图输入后带 token 续登 */
const captcha = reactive({ open: false, image: '', code: '', token: '', hint: '' })

const manual = reactive({ file: '', shareUrl: '', password: '' })

async function load() {
  loading.value = true
  try {
    const payload = await api.adminGet('/api/admin/lanzou')
    Object.assign(config, {
      enabled: payload.config.enabled,
      autoMirror: payload.config.autoMirror,
      preferMirror: payload.config.preferMirror,
      username: payload.config.username,
      password: '',
      folderId: payload.config.folderId,
      domain: payload.config.domain
    })
    passwordSet.value = payload.config.passwordSet
    cookieSet.value = payload.config.cookieSet
    mirrors.value = payload.mirrors || {}
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '读取配置失败'
  } finally {
    loading.value = false
  }
  try {
    files.value = await api.adminGet('/api/admin/apk')
  } catch {
    files.value = []
  }
}

async function save() {
  busy.value = 'save'
  error.value = ''
  notice.value = ''
  try {
    const payload = await api.put('/api/admin/lanzou', { ...config }, true)
    passwordSet.value = payload.config.passwordSet
    cookieSet.value = payload.config.cookieSet
    config.password = ''
    notice.value = '已保存'
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '保存失败'
  } finally {
    busy.value = ''
  }
}

async function testLogin() {
  busy.value = 'test'
  error.value = ''
  notice.value = ''
  try {
    const payload = await api.post(
      '/api/admin/lanzou/test',
      { ...config, captchaToken: captcha.token, verifycode: captcha.code },
      true
    )
    // 服务端要求人机验证：弹出验证码图，人工输入后再来一次（带 token 续登）
    if (payload.needCaptcha) {
      captcha.image = payload.image
      captcha.token = payload.token
      captcha.hint = payload.hint || '蓝奏云要求人机验证，请照图输入验证码'
      captcha.code = ''
      captcha.open = true
      return
    }
    closeCaptcha()
    folders.value = payload.folders || []
    cookieSet.value = true
    notice.value = folders.value.length ? `登录成功，读取到 ${folders.value.length} 个文件夹` : '登录成功'
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '登录失败'
  } finally {
    busy.value = ''
  }
}

function closeCaptcha() {
  captcha.open = false
  captcha.code = ''
  captcha.token = ''
  captcha.image = ''
}

async function mirror(file) {
  busy.value = `mirror:${file}`
  error.value = ''
  notice.value = ''
  try {
    const info = await api.post('/api/admin/lanzou/mirror', { file }, true)
    mirrors.value = { ...mirrors.value, [file]: info }
    notice.value = `已上传：${info.shareUrl}`
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '镜像失败'
  } finally {
    busy.value = ''
  }
}

async function saveManual() {
  if (!manual.file || !manual.shareUrl) {
    error.value = '请选择文件并填写分享链接'
    return
  }
  busy.value = 'manual'
  error.value = ''
  notice.value = ''
  try {
    const info = await api.put(`/api/admin/lanzou/mirror/${encodeURIComponent(manual.file)}`, {
      shareUrl: manual.shareUrl,
      password: manual.password
    }, true)
    mirrors.value = { ...mirrors.value, [manual.file]: info }
    manual.shareUrl = ''
    manual.password = ''
    notice.value = '已登记'
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '登记失败'
  } finally {
    busy.value = ''
  }
}

async function removeMirror(file) {
  if (!window.confirm(`移除 ${file} 的蓝奏云镜像登记？`)) return
  busy.value = `remove:${file}`
  try {
    await api.del(`/api/admin/lanzou/mirror/${encodeURIComponent(file)}`, true)
    const next = { ...mirrors.value }
    delete next[file]
    mirrors.value = next
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '移除失败'
  } finally {
    busy.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="lanzou">
    <header class="head">
      <div>
        <h1 class="h2">蓝奏云镜像与自动更新</h1>
        <p class="muted small head__sub">
          上传安装包后自动打包成 zip 传到蓝奏云；客户端通过
          <code>/api/update/check</code> 检查更新，下载时优先走镜像、失败回落官网直链。
        </p>
      </div>
      <button class="btn btn--primary btn--sm" type="button" :disabled="busy === 'save'" @click="save">
        {{ busy === 'save' ? '保存中…' : '保存配置' }}
      </button>
    </header>

    <p v-if="error" class="msg msg--error">{{ error }}</p>
    <p v-else-if="notice" class="msg msg--ok">{{ notice }}</p>
    <p v-if="loading" class="muted small">正在读取…</p>

    <section class="card block">
      <header class="block__head">
        <h2 class="block__title">账号与开关</h2>
        <button class="btn btn--sm" type="button" :disabled="busy === 'test'" @click="testLogin">
          {{ busy === 'test' ? '登录中…' : '测试登录' }}
        </button>
      </header>

      <p class="hint">
        蓝奏云没有公开 API，这里走的是网页登录接口，可能随对方改版失效。口令保存后不再回显，
        留空表示不修改。登录成功后服务端会存下会话，日常上传无需重复登录。
      </p>

      <div class="grid">
        <label class="field">
          <span>启用镜像</span>
          <span class="switch">
            <input v-model="config.enabled" type="checkbox" />
            <span>{{ config.enabled ? '已启用' : '已关闭' }}</span>
          </span>
        </label>

        <label class="field">
          <span>上传后自动镜像</span>
          <span class="switch">
            <input v-model="config.autoMirror" type="checkbox" />
            <span>{{ config.autoMirror ? '自动打包上传' : '仅手动触发' }}</span>
          </span>
        </label>

        <label class="field">
          <span>客户端优先走镜像</span>
          <span class="switch">
            <input v-model="config.preferMirror" type="checkbox" />
            <span>{{ config.preferMirror ? '优先镜像，失败回落官网' : '始终走官网直链' }}</span>
          </span>
        </label>

        <label class="field">
          <span>账号</span>
          <input v-model="config.username" class="input" placeholder="手机号 / 用户名" autocomplete="off" />
        </label>

        <label class="field">
          <span>口令</span>
          <input
            v-model="config.password"
            class="input"
            type="password"
            autocomplete="new-password"
            :placeholder="passwordSet ? '已保存，留空则不修改' : '••••••••'"
          />
        </label>

        <label class="field">
          <span>上传目录</span>
          <select v-model.number="config.folderId" class="select">
            <option :value="-1">根目录</option>
            <option v-for="f in folders" :key="f.id" :value="f.id">{{ f.name }}（{{ f.id }}）</option>
          </select>
          <span class="hint">先点「测试登录」拉取文件夹列表。</span>
        </label>

        <label class="field">
          <span>分享页域名</span>
          <input v-model="config.domain" class="input mono" placeholder="https://www.lanzoue.com" />
          <span class="hint">账号不同域名不同，接口回传的域名优先。</span>
        </label>
      </div>

      <p class="hint">
        <template v-if="cookieSet">当前已有可用会话，上传与解析会直接复用；失效后点「测试登录」重新建立即可。</template>
        <template v-else>尚未建立会话，点「测试登录」建立一次，之后可长期复用。</template>
      </p>
    </section>

    <section class="card block">
      <header class="block__head">
        <h2 class="block__title">已登记的镜像（{{ Object.keys(mirrors).length }}）</h2>
      </header>
      <p v-if="!Object.keys(mirrors).length" class="muted small">还没有镜像。上传 APK 会自动生成。</p>
      <ul v-else class="files__list">
        <li v-for="(m, file) in mirrors" :key="file" class="files__item">
          <div class="files__info">
            <span class="mono files__name">{{ file }}</span>
            <a class="mono small" :href="m.shareUrl" target="_blank" rel="noreferrer">{{ m.shareUrl }}</a>
            <span class="muted small">
              {{ m.source === 'manual' ? '手工登记' : '自动上传' }}
              <template v-if="m.password"> · 提取码 {{ m.password }}</template>
              · {{ formatDate(m.uploadedAt) }}
            </span>
          </div>
          <div class="files__actions">
            <button
              class="btn btn--sm"
              type="button"
              :disabled="busy === `mirror:${file}`"
              @click="mirror(file)"
            >{{ busy === `mirror:${file}` ? '上传中…' : '重新镜像' }}</button>
            <button class="btn btn--sm btn--danger" type="button" @click="removeMirror(file)">移除</button>
          </div>
        </li>
      </ul>
    </section>

    <section class="card block">
      <header class="block__head">
        <h2 class="block__title">手工登记镜像</h2>
        <button class="btn btn--primary btn--sm" type="button" :disabled="busy === 'manual'" @click="saveManual">
          登记
        </button>
      </header>
      <p class="hint">自动上传失败时，可去蓝奏云网页手动传 zip，再把分享链接与提取码填在这里。</p>
      <div class="grid">
        <label class="field">
          <span>APK 文件</span>
          <select v-model="manual.file" class="select mono">
            <option value="">（选择磁盘上的文件）</option>
            <option v-for="f in files" :key="f.file" :value="f.file">{{ f.file }}</option>
          </select>
        </label>
        <label class="field">
          <span>分享链接</span>
          <input v-model="manual.shareUrl" class="input mono" placeholder="https://xxx.lanzoue.com/xxxx" />
        </label>
        <label class="field">
          <span>提取码</span>
          <input v-model="manual.password" class="input mono" placeholder="可留空" />
        </label>
      </div>
    </section>

    <div v-if="captcha.open" class="modal" @click.self="closeCaptcha">
      <div class="modal__box" role="dialog" aria-modal="true" aria-label="人机验证">
        <h3 class="modal__title">人机验证</h3>
        <p class="hint">{{ captcha.hint }}</p>
        <img v-if="captcha.image" class="modal__img" :src="captcha.image" alt="验证码" />
        <input
          v-model="captcha.code"
          class="input mono"
          placeholder="输入图中字符"
          autocomplete="off"
          @keyup.enter="testLogin"
        />
        <div class="modal__actions">
          <button class="btn btn--sm" type="button" @click="closeCaptcha">取消</button>
          <button
            class="btn btn--primary btn--sm"
            type="button"
            :disabled="busy === 'test'"
            @click="testLogin"
          >{{ busy === 'test' ? '提交中…' : '提交' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lanzou {
  display: grid;
  gap: 20px;
  max-width: 980px;
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
  line-height: 1.8;
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
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
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

.hint {
  font-size: var(--t-xs);
  color: var(--ink-3);
  line-height: 1.8;
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
  gap: 3px;
  min-width: 0;
}

.files__name {
  font-size: var(--t-xs);
  word-break: break-all;
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

/* 内嵌验证码弹窗：不用系统 alert，保持与后台其它部分一致的观感 */
.modal {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(15, 18, 24, 0.42);
}

.modal__box {
  width: min(360px, 100%);
  display: grid;
  gap: 12px;
  padding: 22px;
  border-radius: var(--r-md, 14px);
  background: var(--paper);
  border: 1px solid var(--line);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.22);
}

.modal__title {
  font-size: 1rem;
  font-weight: 600;
}

.modal__img {
  width: 100%;
  height: 56px;
  object-fit: contain;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: #fff;
}

.modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
