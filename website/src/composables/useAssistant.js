import { computed, ref } from 'vue'
import { ApiError, assistant, isStaticBuild } from '../api/client.js'

/**
 * 站点问答的共享会话。
 *
 * 浮窗和 /ask 页是同一段对话——两个入口来回切不该丢上下文，所以状态放在模块作用域，
 * 组件只负责渲染与派发动作。
 */

const messages = ref([])
const status = ref({ enabled: false, name: '项目助手', greeting: '', suggestions: [] })
const sending = ref(false)
const probed = ref(false)
const lastError = ref('')

let controller = null
let seq = 0

const available = computed(() => !isStaticBuild && status.value.enabled)

async function loadStatus(force = false) {
  if (isStaticBuild || (probed.value && !force)) return status.value
  try {
    const next = await assistant.status()
    status.value = {
      enabled: Boolean(next?.enabled),
      name: next?.name || '项目助手',
      greeting: next?.greeting || '',
      suggestions: Array.isArray(next?.suggestions) ? next.suggestions : []
    }
  } catch {
    // 探测失败按「未启用」处理：拿不到状态时不该凭空长出一个入口
    status.value = { ...status.value, enabled: false, suggestions: [] }
  } finally {
    probed.value = true
  }
  return status.value
}

async function ask(text) {
  const question = String(text ?? '').trim()
  if (!question || sending.value || !available.value) return

  // 发给服务端的历史只保留「有内容的问答」，失败的重复提问不该污染上下文
  const history = messages.value
    .filter((m) => m.role === 'user' || m.state === 'done')
    .map((m) => ({ role: m.role, content: m.content }))
  history.push({ role: 'user', content: question })

  messages.value.push({ id: ++seq, role: 'user', content: question, state: 'done', sources: [] })
  messages.value.push({ id: ++seq, role: 'assistant', content: '', state: 'streaming', sources: [] })
  const idx = messages.value.length - 1

  sending.value = true
  lastError.value = ''
  controller = new AbortController()

  try {
    const result = await assistant.chat(history, {
      signal: controller.signal,
      onDelta: (chunk) => {
        // 必须经 messages.value[idx] 写：直接改裸对象不会触发响应式更新
        messages.value[idx].content += chunk
      }
    })
    const reply = messages.value[idx]
    reply.content = result.text || reply.content
    reply.sources = result.sources || []
    reply.state = 'done'
  } catch (err) {
    const reply = messages.value[idx]
    if (err?.name === 'AbortError') {
      // 主动停止：已经流出来的留着，一个字都没有就把这条空壳收掉
      if (reply.content) reply.state = 'done'
      else messages.value = messages.value.filter((m) => m.id !== reply.id)
    } else {
      reply.state = 'error'
      reply.error = err instanceof ApiError ? err.message : err?.message || '生成失败'
      lastError.value = reply.error
    }
  } finally {
    sending.value = false
    controller = null
  }
}

function stop() {
  controller?.abort()
}

function reset() {
  stop()
  messages.value = []
  lastError.value = ''
}

export function useAssistant() {
  return { messages, status, sending, probed, lastError, available, ask, stop, reset, loadStatus }
}

export { loadStatus }
