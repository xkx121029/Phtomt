import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { config } from '../config.js'

/**
 * 管理口令的持久化。
 *
 * 为什么不放进 store.js 的内容集合：内容集合会经 `/api/admin/content/:name` 原样吐给浏览器，
 * 也会被 snapshot 脚本烤进静态站。口令哈希一旦混进去，等于把哈希发到公网。
 * 所以这里单独一个文件、单独一套读写，不进 COLLECTIONS，也不进 admin.js 的 WRITABLE。
 *
 * 为什么存哈希不存明文：这个文件落在 server/data 下，是随容器 bind 挂载的运行时目录，
 * 也是会被人备份、复制、翻看的目录。明文口令一旦被读到就是直接可用，哈希至少要多一道破解。
 *
 * 为什么用 scrypt 而不是 sha256：sha256 是「快哈希」，GPU 每秒能试几十亿次，
 * 口令这种低熵输入必须用刻意慢、且吃内存的算法。scrypt 是 Node 内置的，不引依赖。
 */

const KEY_LENGTH = 64
const SALT_LENGTH = 16
const FILE_NAME = 'admin.json'

let cache

function file() {
  return path.join(config.dataDir, FILE_NAME)
}

/** 读一次就缓存；写入时同步更新缓存，避免请求间反复读盘 */
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
    // 与 store.js 同一套思路：文件坏了不静默重置，留档再说，
    // 否则会「悄悄退回环境变量口令」，让人以为改的密码丢了。
    const backup = `${target}.broken-${Date.now()}`
    fs.copyFileSync(target, backup)
    console.error(`[credentials] ${FILE_NAME} 解析失败，已备份到 ${backup}：${err.message}`)
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

/** 当前生效的口令来源：改过就是 file，没改过就是 env */
export function source() {
  return load()?.passwordHash ? 'file' : 'env'
}

export function updatedAt() {
  return load()?.updatedAt ?? null
}

/** 令牌绑定的材料。换口令后这个值变，旧令牌自然失效 */
export function fingerprint() {
  const stored = load()?.passwordHash
  if (stored) return stored
  return config.adminPassword || ''
}

export function setPassword(plain) {
  const salt = crypto.randomBytes(SALT_LENGTH)
  const hash = crypto.scryptSync(plain, salt, KEY_LENGTH)
  return save({
    algorithm: 'scrypt',
    passwordHash: `scrypt$${salt.toString('base64')}$${hash.toString('base64')}`,
    updatedAt: new Date().toISOString()
  })
}

/**
 * 比对口令。
 * 返回 true / false 表示「有存储的口令，且比对结果如此」；
 * 返回 null 表示「没存储过」，调用方据此回落到环境变量。
 * 用 null 而不是 false 区分这两种情况，是为了让「从未改过密码」和「改错了密码」不混为一谈。
 */
export function verify(plain) {
  const stored = load()?.passwordHash
  if (!stored) return null

  const parts = String(stored).split('$')
  if (parts.length !== 3 || parts[0] !== 'scrypt') return null

  const salt = Buffer.from(parts[1], 'base64')
  const expected = Buffer.from(parts[2], 'base64')
  if (!expected.length) return null

  const actual = crypto.scryptSync(String(plain ?? ''), salt, expected.length)
  return crypto.timingSafeEqual(actual, expected)
}

/** 仅供测试或人工恢复：抹掉文件里的口令，回到环境变量 */
export function clear() {
  const target = file()
  if (fs.existsSync(target)) fs.unlinkSync(target)
  cache = null
}