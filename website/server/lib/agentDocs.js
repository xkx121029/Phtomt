import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { config } from '../config.js'

/**
 * Agent 执行文档库。
 *
 * 给装好的 App 里的 AI 用的「操作手册」：一篇文档 = 一个 Markdown 文件，
 * 文件头用极简 front-matter 描述匹配条件（包名 / 关键词 / 意图），正文是执行步骤。
 *
 * 为什么按文件而不是塞进 docs.json：文档要能实时更新、能被 diff、能被非程序员直接改；
 * 服务端只做目录扫描 + 哈希版本，不缓存进 store（store 是给官网页面用的内容集合）。
 */

export const DOC_DIR = path.join(config.dataDir, 'agent-docs')

const ID_RE = /^[A-Za-z0-9._-]+$/

function ensureDir() {
  fs.mkdirSync(DOC_DIR, { recursive: true })
}

/** front-matter 里的数组既支持 [a, b] 也支持 a, b；两者都没写时给空数组 */
function toList(value) {
  if (value === undefined || value === null) return []
  const raw = Array.isArray(value) ? value : String(value).replace(/^\[|\]$/g, '').split(',')
  return raw
    .map((s) => String(s).trim().replace(/^['"]|['"]$/g, ''))
    .filter(Boolean)
}

function toText(value) {
  if (value === undefined || value === null) return ''
  return String(value).trim().replace(/^['"]|['"]$/g, '')
}

function toNumber(value, fallback) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

/**
 * 极简 front-matter：只认 `key: value` 单行，不引 yaml 依赖。
 * 解析失败不报错——坏了就当没有头，用文件名兜底，至少不会让整篇文档消失。
 */
export function splitFrontMatter(raw) {
  const text = String(raw).replace(/^\uFEFF/, '')
  const match = text.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/)
  if (!match) return { meta: {}, body: text.trim() }
  const meta = {}
  for (const line of match[1].split(/\r?\n/)) {
    const idx = line.indexOf(':')
    if (idx === -1) continue
    const key = line.slice(0, idx).trim()
    if (!key || key.startsWith('#')) continue
    meta[key] = line.slice(idx + 1).trim()
  }
  return { meta, body: text.slice(match[0].length).trim() }
}

function titleOf(body, fallback) {
  const heading = body.match(/^#\s+(.+)$/m)
  return heading ? heading[1].trim() : fallback
}

function summaryOf(body) {
  const lines = body.split(/\r?\n/)
  for (const line of lines) {
    const text = line.trim()
    if (!text || text.startsWith('#') || text.startsWith('---')) continue
    return text.replace(/^[-*>]\s*/, '').slice(0, 120)
  }
  return ''
}

function buildDoc(fileName, raw, stat) {
  const id = path.basename(fileName, '.md')
  const { meta, body } = splitFrontMatter(raw)
  const title = toText(meta.title) || titleOf(body, id)
  return {
    id,
    title,
    summary: toText(meta.summary) || summaryOf(body),
    version: toText(meta.version) || String(stat.mtimeMs),
    priority: toNumber(meta.priority, 50),
    enabled: toText(meta.enabled).toLowerCase() !== 'false',
    intents: toList(meta.intents ?? meta.intent),
    packages: toList(meta.packages ?? meta.package),
    keywords: toList(meta.keywords ?? meta.keyword),
    apps: toList(meta.apps ?? meta.app),
    tags: toList(meta.tags ?? meta.tag),
    chars: body.length,
    updatedAt: stat.mtime.toISOString(),
    etag: crypto.createHash('sha1').update(raw).digest('hex').slice(0, 16),
    body
  }
}

/**
 * 目录扫描缓存：按「文件名 + mtime + size」判脏。
 * 文档数量在几十篇量级，每次请求 stat 一遍目录完全够快；这样改文件即刻生效，不用重启。
 */
const docCache = new Map()
let dirStamp = ''

function scan() {
  ensureDir()
  const names = fs
    .readdirSync(DOC_DIR)
    .filter((f) => /\.md$/i.test(f) && !f.startsWith('.'))
    .sort()

  const stamp = names.join('|')
  if (stamp !== dirStamp) {
    for (const key of [...docCache.keys()]) {
      if (!names.includes(key)) docCache.delete(key)
    }
    dirStamp = stamp
  }

  for (const name of names) {
    const full = path.join(DOC_DIR, name)
    let stat
    try {
      stat = fs.statSync(full)
    } catch {
      continue
    }
    const cached = docCache.get(name)
    if (cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) continue
    try {
      const raw = fs.readFileSync(full, 'utf8')
      docCache.set(name, {
        mtimeMs: stat.mtimeMs,
        size: stat.size,
        doc: buildDoc(name, raw, stat)
      })
    } catch (err) {
      console.warn(`[agent-docs] 读取 ${name} 失败：${err.message}`)
    }
  }
}

/** 索引里的单篇元信息（不含正文，正文按需单独 GET） */
function indexItem(doc) {
  return {
    id: doc.id,
    title: doc.title,
    summary: doc.summary,
    version: doc.version,
    priority: doc.priority,
    intents: doc.intents,
    packages: doc.packages,
    keywords: doc.keywords,
    apps: doc.apps,
    tags: doc.tags,
    chars: doc.chars,
    updatedAt: doc.updatedAt,
    etag: doc.etag
  }
}

/** 全量索引；索引自身的 ETag 由所有文档 etag 拼出，任何一篇更新都会换版本 */
export function listDocs() {
  scan()
  const docs = [...docCache.values()]
    .map((entry) => entry.doc)
    .filter((doc) => doc.enabled)
    .sort((a, b) => b.priority - a.priority || a.id.localeCompare(b.id))
  const items = docs.map(indexItem)
  const version = crypto
    .createHash('sha1')
    .update(items.map((d) => `${d.id}:${d.etag}`).join('|'))
    .digest('hex')
    .slice(0, 16)
  return {
    version,
    generatedAt: new Date().toISOString(),
    count: items.length,
    docs: items
  }
}

/** 取单篇；id 必须与磁盘上的文件名严格一致，杜绝路径穿越 */
export function getDoc(id) {
  scan()
  const key = path.basename(String(id || '').replace(/\.md$/i, ''))
  if (!ID_RE.test(key)) return null
  const entry = docCache.get(`${key}.md`)
  if (!entry || !entry.doc.enabled) return null
  return entry.doc
}
