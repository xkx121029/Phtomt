import { computed, ref } from 'vue'
import { ApiError, api, getToken, isStaticBuild, setToken } from '../../api/client.js'

/**
 * 后台登录态。
 *
 * 令牌存在 localStorage，页面刷新后先用 /api/admin/session 验一次——
 * 直接信任本地值会在令牌过期时给出一屏「保存失败」，不如一开始就退回登录页。
 */
export const authed = ref(Boolean(getToken()))
export const checking = ref(authed.value)
export const loginError = ref('')

export const staticBlocked = isStaticBuild

export const canWrite = computed(() => authed.value && !isStaticBuild)

export async function ensureSession() {
  if (isStaticBuild) {
    checking.value = false
    return
  }
  if (!getToken()) {
    authed.value = false
    checking.value = false
    return
  }
  try {
    await api.adminGet('/api/admin/session')
    authed.value = true
  } catch {
    setToken('')
    authed.value = false
  } finally {
    checking.value = false
  }
}

export async function login(password) {
  loginError.value = ''
  try {
    const res = await api.post('/api/admin/login', { password })
    setToken(res.token)
    authed.value = true
    return true
  } catch (err) {
    loginError.value = err instanceof ApiError ? err.message : '登录失败，请重试'
    return false
  }
}

export function logout() {
  setToken('')
  authed.value = false
}
