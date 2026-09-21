import fs from 'node:fs'
import path from 'node:path'
import { config } from '../config.js'

/**
 * 内容存储：每个集合一个 JSON 文件。
 *
 * 为什么不用数据库：官网内容是「少量、结构化、由单人维护」的，JSON 文件可以直接
 * 进版本库、可以手工 diff、迁移零成本。写入走「临时文件 + rename」保证原子性，
 * 避免进程中断留下半个文件。
 */

const COLLECTIONS = {
  site: { file: 'site.json', kind: 'object' },
  features: { file: 'features.json', kind: 'array' },
  releases: { file: 'releases.json', kind: 'array' },
  changelog: { file: 'changelog.json', kind: 'array' },
  docs: { file: 'docs.json', kind: 'array' },
  faq: { file: 'faq.json', kind: 'array' },
  stats: { file: 'stats.json', kind: 'object' },
  audit: { file: 'audit.json', kind: 'array' }
}

const EMPTY = {
  site: () => ({}),
  features: () => [],
  releases: () => [],
  changelog: () => [],
  docs: () => [],
  faq: () => [],
  stats: () => ({ totalDownloads: 0, byVersion: {}, byDay: {}, lastDownloadAt: null }),
  audit: () => []
}

const cache = new Map()

function fileOf(name) {
  const meta = COLLECTIONS[name]
  if (!meta) throw new Error(`未知集合：${name}`)
  return path.join(config.dataDir, meta.file)
}

export function read(name) {
  if (cache.has(name)) return cache.get(name)
  const file = fileOf(name)
  let value
  if (!fs.existsSync(file)) {
    value = EMPTY[name]()
  } else {
    try {
      value = JSON.parse(fs.readFileSync(file, 'utf8'))
    } catch (err) {
      // 内容损坏时不要静默重置——把坏文件留档，方便人工找回。
      const backup = `${file}.broken-${Date.now()}`
      fs.copyFileSync(file, backup)
      console.error(`[store] ${name} 解析失败，已备份到 ${backup}：${err.message}`)
      value = EMPTY[name]()
    }
  }
  cache.set(name, value)
  return value
}

export function write(name, value) {
  const file = fileOf(name)
  const tmp = `${file}.${process.pid}.tmp`
  fs.writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  fs.renameSync(tmp, file)
  cache.set(name, value)
  return value
}

/** 读改写一体化：调用方拿到当前值，改完返回新值即可 */
export function update(name, mutator) {
  const current = read(name)
  const next = mutator(current)
  return write(name, next === undefined ? current : next)
}

export function invalidate(name) {
  if (name) cache.delete(name)
  else cache.clear()
}

/** 内容集合的元信息，供 API 文档与后台自动生成表单使用 */
export const collectionMeta = COLLECTIONS
