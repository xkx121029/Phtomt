import fs from 'node:fs'
import path from 'node:path'
import { config } from '../config.js'

/**
 * 问答留痕。
 *
 * 与 audit.json（只记「后台做了什么」）分开：这份记的是**访客问了什么**，
 * 用途完全不同——一个是追责，一个是看大家真正关心什么，好在 FAQ 和文档里补。
 *
 * 因为是访客内容，两条边界必须先立好：
 *  - IP 进库前就打码（见 [maskIp]），原文不进文件。留痕要能看出「是不是同一个人」，
 *    但不需要知道他是谁。
 *  - 条数封顶（logLimit，后台可配），满了丢最旧的。这是个环形缓冲，不是档案库。
 *
 * 文件落在 DATA_DIR，与 ai.json 同处：都在运行时目录里，不进版本库、不进镜像。
 */

const FILE_NAME = 'ai-log.json'

let cache

function file() {
  return path.join(config.dataDir, FILE_NAME)
}

function load() {
  if (cache !== undefined) return cache
  const target = file()
  if (!fs.existsSync(target)) {
    cache = []
    return cache
  }
  try {
    const parsed = JSON.parse(fs.readFileSync(target, 'utf8'))
    cache = Array.isArray(parsed) ? parsed : []
  } catch (err) {
    // 与 store.js、aiSettings.js 同一套思路：文件坏了不静默重置，留档再说
    const backup = `${target}.broken-${Date.now()}`
    fs.copyFileSync(target, backup)
    console.error(`[ai-log] ${FILE_NAME} 解析失败，已备份到 ${backup}：${err.message}`)
    cache = []
  }
  return cache
}

function save(list) {
  const target = file()
  const tmp = `${target}.${process.pid}.tmp`
  fs.writeFileSync(tmp, `${JSON.stringify(list, null, 2)}\n`, 'utf8')
  fs.renameSync(tmp, target)
  cache = list
  return list
}

/**
 * IP 打码。
 *
 * 只留到「能区分不同访客」的粒度：IPv4 留前两段，IPv6 留前两组。
 * 反向代理下 Express 常给出 ::ffff:1.2.3.4 这种映射写法，先把前缀摘掉再判断。
 */
export function maskIp(raw) {
  const ip = String(raw || '').trim()
  if (!ip) return ''

  const v4 = ip.replace(/^::ffff:/i, '')
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(v4)) {
    const [a, b] = v4.split('.')
    return `${a}.${b}.*.*`
  }

  const groups = ip.split(':').filter(Boolean)
  return groups.length ? `${groups.slice(0, 2).join(':')}::` : ''
}

// 顺序即优先级：Edge/Opera 的 UA 里也有 Chrome，iOS 上的 Chrome 也带 Safari
const UA_RULES = [
  [/Edg[A-Z]?\//, 'Edge'],
  [/OPR\/|Opera/, 'Opera'],
  [/Firefox\//, 'Firefox'],
  [/CriOS\//, 'Chrome'],
  [/Chrome\//, 'Chrome'],
  [/Safari\//, 'Safari'],
  [/curl\//i, 'curl'],
  [/python-requests/i, 'Python'],
  [/bot|crawler|spider/i, '爬虫']
]

const OS_RULES = [
  [/Windows NT/, 'Windows'],
  [/Android/, 'Android'],
  [/iPhone|iPad|iPod/, 'iOS'],
  [/Mac OS X/, 'macOS'],
  [/Linux/, 'Linux']
]

/** 原始 UA 一百多字符且几乎无信息量，压成「浏览器/系统」两个词就够了 */
export function shortUa(raw) {
  const ua = String(raw || '')
  if (!ua) return ''
  const browser = UA_RULES.find(([re]) => re.test(ua))?.[1] || '其他'
  const os = OS_RULES.find(([re]) => re.test(ua))?.[1] || ''
  return os ? `${browser}/${os}` : browser
}

/** 追加一条，超出 limit 丢最旧的 */
export function append(entry, limit) {
  const cap = Math.min(5000, Math.max(1, Number(limit) || 500))
  const list = load().slice()
  list.unshift(entry)
  return save(list.slice(0, cap))
}

/** 倒序（新的在前）分页 + 关键词过滤 */
export function list({ q = '', page = 1, size = 20 } = {}) {
  const rows = load()
  const needle = String(q || '').trim().toLowerCase()
  const hit = needle
    ? rows.filter((r) =>
        `${r.question || ''} ${r.answer || ''} ${r.nav || ''} ${r.ip || ''}`.toLowerCase().includes(needle)
      )
    : rows

  const per = Math.min(100, Math.max(5, Number(size) || 20))
  const p = Math.max(1, Number(page) || 1)
  return { total: hit.length, page: p, size: per, items: hit.slice((p - 1) * per, p * per) }
}

export function count() {
  return load().length
}

export function clear() {
  return save([])
}
