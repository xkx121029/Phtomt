import { computed, ref, watch } from 'vue'
import { ApiError, assistant, isStaticBuild } from '../api/client.js'
import router from '../router/index.js'
import { splitNavs } from '../lib/navMark.js'

/**
 * 站点问答的共享会话。
 *
 * 浮窗和 /ask 页是同一段对话——两个入口来回切不该丢上下文，所以状态放在模块作用域，
 * 组件只负责渲染与派发动作。
 *
 * 对话还会落到 localStorage 一份：官网是多页应用，用户被 AI 带着跳了两个页面、
 * 手一滑又刷新了一次，回来发现刚聊的内容全没了——这是最伤信任的一种体验。
 * 本地留痕同时覆盖了「AI 带路把自己带丢了」这种情况：跳远之后还能翻回上一轮接着问。
 */

const THREAD_KEY = 'phtomt-ai-thread'
/** 本地最多留多少条。留痕是让人能接着聊，不是归档 */
const MAX_STORED = 40
/** 单条正文上限，与服务端的单条限制对齐 */
const MAX_CHARS = 4000
/** 同一个目标在这个间隔内不重复跳，防止模型把标记吐两遍时连跳两次 */
const NAV_DEDUPE_MS = 4000

const messages = ref([])
const status = ref({ enabled: false, name: '项目助手', greeting: '', suggestions: [], guide: false })
const sending = ref(false)
const probed = ref(false)
const lastError = ref('')

let controller = null
let seq = 0
let restored = false
let saveTimer = null
let lastNav = { path: '', at: 0 }

const available = computed(() => !isStaticBuild && status.value.enabled)

// ---------- 本地留痕 ----------

function storageOk() {
  return typeof localStorage !== 'undefined'
}

function persist() {
  if (!storageOk()) return
  try {
    const rows = messages.value.slice(-MAX_STORED).map((m) => ({
      id: m.id,
      role: m.role,
      content: String(m.content || '').slice(0, MAX_CHARS),
      sources: m.sources || [],
      // 引导结果一起留下：回到这个页面时，「刚才被带到哪去了」还能看见
      nav: m.nav || null,
      navFrom: m.navFrom || '',
      visited: Boolean(m.visited)
    }))
    localStorage.setItem(THREAD_KEY, JSON.stringify({ v: 1, at: Date.now(), messages: rows }))
  } catch {
    /* 隐私模式或配额满：留痕失败不该影响正在进行的对话 */
  }
}

/** 每来一个字符就写一次盘太浪费，攒一下再落 */
function schedulePersist() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(persist, 400)
}

watch(messages, schedulePersist, { deep: true })

/** 把上次的对话接回来。中断在半截的流式回答按已完成处理 */
function restore() {
  if (restored || !storageOk()) return
  restored = true
  try {
    const data = JSON.parse(localStorage.getItem(THREAD_KEY) || 'null')
    if (data?.v !== 1 || !Array.isArray(data.messages)) return

    const rows = data.messages
      .filter((m) => m && (m.role === 'user' || m.role === 'assistant'))
      .map((m) => ({
        id: Number(m.id) || ++seq,
        role: m.role,
        content: String(m.content || ''),
        raw: String(m.content || ''),
        state: 'done',
        pending: false,
        sources: Array.isArray(m.sources) ? m.sources : [],
        nav: m.nav || null,
        navFrom: m.navFrom || '',
        visited: Boolean(m.visited)
      }))
    if (!rows.length) return

    messages.value = rows
    // id 是从存储里带回来的，新消息的序号必须越过它们，否则 Vue 的 key 会撞
    seq = rows.reduce((max, m) => Math.max(max, m.id), seq)
  } catch {
    /* 存储坏了就当没有历史，不影响新对话 */
  }
}

// ---------- 状态 ----------

async function loadStatus(force = false) {
  if (isStaticBuild || (probed.value && !force)) return status.value
  try {
    const next = await assistant.status()
    status.value = {
      enabled: Boolean(next?.enabled),
      name: next?.name || '项目助手',
      greeting: next?.greeting || '',
      suggestions: Array.isArray(next?.suggestions) ? next.suggestions : [],
      guide: Boolean(next?.guide)
    }
  } catch {
    // 探测失败按「未启用」处理：拿不到状态时不该凭空长出一个入口
    status.value = { ...status.value, enabled: false, suggestions: [], guide: false }
  } finally {
    probed.value = true
  }
  return status.value
}

// ---------- 站内带路 ----------

/**
 * 执行一次带路。
 *
 * 路径必须能在真实路由表里解析出来才跳：模型有可能编一个不存在的地址，
 * 那种情况用户会落到 404，比「哪儿也没去」更糟。后台也不能作为目标——
 * 访客跳过去只会看到登录框。
 *
 * @returns {boolean} 是否真的跳了
 */
function navigate(to) {
  const path = String(to || '').trim()
  if (!path.startsWith('/')) return false

  let resolved
  try {
    resolved = router.resolve(path)
  } catch {
    return false
  }
  const matched = resolved.matched || []
  if (!matched.length || resolved.name === 'not-found') return false
  if (matched.some((r) => r.meta?.admin)) return false

  const now = Date.now()
  if (lastNav.path === resolved.fullPath && now - lastNav.at < NAV_DEDUPE_MS) return false
  lastNav = { path: resolved.fullPath, at: now }

  router.push(resolved.fullPath).catch(() => {})
  return true
}

/** 把原始文本重新解析成「可显示的正文 + 已触发的引导」，流式过程中会反复调用 */
function applyParse(msg) {
  const { text, navs, pending } = splitNavs(msg.raw)
  msg.content = text
  msg.pending = pending

  const next = navs[0]
  if (!next || msg.nav) return

  msg.nav = next
  // 「直接引导」是这套设计的前提：解析到就跳，不再让用户点第二次。
  // 先记下原位置，回答下面的回执才有「返回」可点
  msg.navFrom = router.currentRoute.value.fullPath
  msg.visited = status.value.guide ? navigate(next.to) : false
}

/** 回执上的「返回」：回到被带走之前那一页 */
function backFromNav(msg) {
  const to = msg?.navFrom
  if (!to) return
  router.push(to).catch(() => {})
}

// ---------- 提问 ----------

async function ask(text) {
  const question = String(text ?? '').trim()
  if (!question || sending.value || !available.value) return

  // 发给服务端的历史只保留「有内容的问答」，失败的重复提问不该污染上下文
  const history = messages.value
    .filter((m) => m.role === 'user' || m.state === 'done')
    .filter((m) => m.content)
    .map((m) => ({ role: m.role, content: m.content }))
  history.push({ role: 'user', content: question })

  messages.value.push({
    id: ++seq,
    role: 'user',
    content: question,
    raw: question,
    state: 'done',
    pending: false,
    sources: [],
    nav: null,
    visited: false
  })
  messages.value.push({
    id: ++seq,
    role: 'assistant',
    content: '',
    raw: '',
    state: 'streaming',
    pending: false,
    sources: [],
    nav: null,
    navFrom: '',
    visited: false
  })
  const idx = messages.value.length - 1

  sending.value = true
  lastError.value = ''
  controller = new AbortController()

  try {
    const result = await assistant.chat(history, {
      signal: controller.signal,
      onDelta: (chunk) => {
        // 必须经 messages.value[idx] 写：直接改裸对象不会触发响应式更新
        const msg = messages.value[idx]
        if (!msg) return
        msg.raw += chunk
        applyParse(msg)
      }
    })
    const reply = messages.value[idx]
    reply.raw = result.text || reply.raw
    applyParse(reply)
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
    schedulePersist()
  }
}

function stop() {
  controller?.abort()
}

/** 清空对话。本地那一份也要一起清，否则刷新一下又回来了 */
function reset() {
  stop()
  messages.value = []
  lastError.value = ''
  clearTimeout(saveTimer)
  if (storageOk()) {
    try {
      localStorage.removeItem(THREAD_KEY)
    } catch {
      /* 同上，存储不可用不影响清空内存里的对话 */
    }
  }
}

export function useAssistant() {
  return {
    messages,
    status,
    sending,
    probed,
    lastError,
    available,
    ask,
    stop,
    reset,
    loadStatus,
    restore,
    backFromNav
  }
}

export { loadStatus, restore }
