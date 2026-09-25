import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { config } from '../config.js'

/**
 * 站点 AI 问答的后台配置。
 *
 * 为什么不放进 store.js 的内容集合：里面有一把模型 API Key。内容集合会经
 * `/api/admin/content/:name` 原样吐给浏览器，也会被 snapshot 脚本烤进静态站——
 * 密钥混进去等于直接发到公网。所以这里单独一个文件、单独一套读写。
 * （口令哈希出于同样的理由也是单独存放，见 credentials.js。）
 *
 * 读的时候一律返回「打过码」的视图（see [masked]）：密钥只回指纹与首尾几位。
 * 回显明文没有任何用处，却会让它出现在浏览器 devtools、响应缓存和任何中间代理里。
 *
 * 这个文件落在 DATA_DIR（容器里是 bind 挂载的运行时目录），按密钥对待：
 * 别提交进版本库、别打进镜像。
 */

const FILE_NAME = 'ai.json'

export const DEFAULT_SUGGESTIONS = [
  '这个项目是做什么的？跟普通自动化脚本有什么不同？',
  '手机需要 Root 吗？该怎么开始用？',
  '支持哪些系统版本和机型？',
  '最新版本更新了什么？'
]

export const DEFAULTS = {
  /** 总开关。关掉后官网不显示任何问答入口，/api/ai/chat 直接拒绝 */
  enabled: false,
  /** OpenAI 兼容端点根地址，不含 /chat/completions */
  baseUrl: '',
  model: '',
  apiKey: '',
  /** 越低越稳。这个场景要的是「照着资料答」，不需要发挥 */
  temperature: 0.3,
  maxTokens: 1024,
  /** 每次提问注入的项目资料上限（字符）。资料越大越准，也越贵 */
  contextChars: 24000,
  /** 带回多少轮历史对话 */
  historyTurns: 6,
  /** 每个 IP 每小时提问上限，0 表示不限 */
  rateLimitPerHour: 30,
  assistantName: '项目助手',
  /** 面板打开时的开场白 */
  greeting: '',
  /** 推荐提问，一行一条 */
  suggestions: '',
  /** 追加在人设规则之后的站点级补充要求 */
  extraPrompt: '',
  updatedAt: null
}

/** 数值字段的合法区间：越界一律夹回来，不让表单里的手滑变成运行时异常 */
const NUMERIC = {
  temperature: [0, 2],
  maxTokens: [128, 8192],
  contextChars: [2000, 120000],
  historyTurns: [0, 20],
  rateLimitPerHour: [0, 1000]
}

const MAX_TEXT = {
  baseUrl: 300,
  model: 120,
  assistantName: 40,
  greeting: 400,
  suggestions: 1200,
  extraPrompt: 4000
}

let cache

function file() {
  return path.join(config.dataDir, FILE_NAME)
}

/** 读一次就缓存；写入时同步更新缓存，避免每个请求都读盘 */
function load() {
  if (cache !== undefined) return cache
  const target = file()
  if (!fs.existsSync(target)) {
    cache = null
    return cache
  }
  try {
    cache = JSON.parse(fs.readFileSync(target, 'utf8'))
  } catch (err) {
    // 与 store.js、credentials.js 同一套思路：文件坏了不静默重置，留档再说
    const backup = `${target}.broken-${Date.now()}`
    fs.copyFileSync(target, backup)
    console.error(`[ai] ${FILE_NAME} 解析失败，已备份到 ${backup}：${err.message}`)
    cache = null
  }
  return cache
}

function save(value) {
  const target = file()
  const tmp = `${target}.${process.pid}.tmp`
  fs.writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  fs.renameSync(tmp, target)
  cache = value
  return value
}

/** 当前生效的配置（含明文密钥，仅服务端内部使用） */
export function read() {
  return { ...DEFAULTS, ...(load() || {}) }
}

function coerce(key, value) {
  const range = NUMERIC[key]
  if (range) {
    const n = Number(value)
    if (!Number.isFinite(n)) return DEFAULTS[key]
    return Math.min(range[1], Math.max(range[0], n))
  }
  if (key === 'enabled') return value === true || value === 'true'
  return String(value ?? '')
    .trim()
    .slice(0, MAX_TEXT[key] || 500)
}

/**
 * 写入配置。
 *
 * 密钥的三态刻意分开，因为表单里回显不出明文，无法区分「没改」和「清空」：
 *  - 不给 apiKey（或为空串）→ 保留原值；
 *  - 给了非空的 apiKey → 换新值；
 *  - clearKey: true → 显式清空。
 */
export function write(patch = {}) {
  const next = { ...read() }

  for (const key of Object.keys(DEFAULTS)) {
    if (key === 'apiKey' || key === 'updatedAt') continue
    if (patch[key] === undefined) continue
    next[key] = coerce(key, patch[key])
  }

  if (patch.clearKey) next.apiKey = ''
  else if (typeof patch.apiKey === 'string' && patch.apiKey.trim()) next.apiKey = patch.apiKey.trim()

  next.updatedAt = new Date().toISOString()
  return save(next)
}

/** 端点地址：允许直接填到 /chat/completions，也允许只填到 /v1 */
export function chatUrl(cfg = read()) {
  const base = String(cfg.baseUrl || '').trim().replace(/\/+$/, '')
  if (!base) return ''
  return /\/chat\/completions$/.test(base) ? base : `${base}/chat/completions`
}

/** 配置齐全才可用。缺一项就当没配好，前端据此隐藏入口 */
export function isReady(cfg = read()) {
  return Boolean(cfg.enabled && cfg.baseUrl && cfg.model && cfg.apiKey && /^https?:\/\//i.test(cfg.baseUrl))
}

/** 接口三要素的指纹：改了地址/模型/密钥就得重新测一次 */
export function fingerprint(cfg = read()) {
  const material = `${cfg.baseUrl}|${cfg.model}|${cfg.apiKey}`
  return crypto.createHash('sha256').update(material).digest('hex').slice(0, 12)
}

function hintOf(key) {
  const value = String(key || '')
  if (!value) return ''
  if (value.length <= 8) return '••••'
  return `${value.slice(0, 4)}…${value.slice(-4)}`
}

/** 给后台的视图：密钥换成指纹与首尾，明文一个字符都不出去 */
export function masked() {
  const cfg = read()
  return {
    ...cfg,
    apiKey: '',
    hasKey: Boolean(cfg.apiKey),
    keyHint: hintOf(cfg.apiKey),
    endpointFingerprint: fingerprint(cfg),
    ready: isReady(cfg),
    chatUrl: chatUrl(cfg)
  }
}

export function suggestionsOf(cfg = read()) {
  const list = String(cfg.suggestions || '')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
  return (list.length ? list : DEFAULT_SUGGESTIONS).slice(0, 6)
}

/** 仅供测试或人工恢复：抹掉文件里的密钥 */
export function clear() {
  const target = file()
  if (fs.existsSync(target)) fs.unlinkSync(target)
  cache = null
}
