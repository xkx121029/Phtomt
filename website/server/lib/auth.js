import crypto from 'node:crypto'
import { config } from '../config.js'
import * as credentials from './credentials.js'

/**
 * 管理鉴权：无状态签名令牌。
 *
 * 为什么不用 session：官网后台只有一个管理员，引入会话存储只会多一份要持久化和清理的状态。
 * 令牌格式 `expiresAt.fingerprint.hmac`，密钥来自 ADMIN_SECRET，服务端不存任何东西。
 *
 * 中间那段 fingerprint 是当前凭据的摘要。无状态令牌有个绕不开的问题：
 * 签出去就收不回来——改了口令，旧令牌照样能用到过期。
 * 「改密码」这个动作本身就意味着旧凭据可能已经泄露，所以必须能一次性作废全部旧令牌。
 * 把凭据摘要写进被签名的载荷，改密后摘要变、校验自然失败，不用维护吊销清单。
 */

function sign(payload) {
  return crypto.createHmac('sha256', config.adminSecret).update(payload).digest('base64url')
}

/** 凭据摘要：存储的哈希优先，没有则用环境变量口令 */
function fingerprint() {
  return crypto.createHash('sha256').update(credentials.fingerprint()).digest('base64url').slice(0, 16)
}

export function issueToken() {
  const expiresAt = Date.now() + config.adminTokenTtlHours * 3600 * 1000
  const payload = `${expiresAt}.${fingerprint()}`
  return { token: `${payload}.${sign(payload)}`, expiresAt }
}

export function verifyToken(token) {
  if (typeof token !== 'string') return false
  const parts = token.split('.')
  if (parts.length !== 3) return false
  const [expiresAt, tokenPrint, mac] = parts
  if (!expiresAt || !tokenPrint || !mac) return false

  // 先从载荷里取过期时间：格式不对就不必算 MAC 了
  const expires = Number(expiresAt)
  if (!Number.isFinite(expires) || Date.now() >= expires) return false

  // 凭据换过之后，旧令牌的摘要在这一刻已经对不上
  if (tokenPrint !== fingerprint()) return false

  const expected = sign(`${expiresAt}.${tokenPrint}`)
  // 长度不等时 timingSafeEqual 会抛错，先挡掉
  if (mac.length !== expected.length) return false
  return crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(expected))
}

/**
 * 校验口令。
 * 改过密码就用文件里的 scrypt 哈希，没改过才回落到 ADMIN_PASSWORD。
 * 顺序不能反：反了就成了「环境变量永远压过后台改的值」，界面上改了也没用。
 */
export function checkPassword(input) {
  const plain = String(input ?? '')
  const stored = credentials.verify(plain)
  if (stored !== null) return stored

  const expected = config.adminPassword || ''
  if (!expected) return false
  const a = crypto.createHash('sha256').update(plain).digest()
  const b = crypto.createHash('sha256').update(expected).digest()
  return crypto.timingSafeEqual(a, b)
}

/** Express 中间件：要求 `Authorization: Bearer <token>` */
export function requireAdmin(req, res, next) {
  const header = req.get('authorization') || ''
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : ''
  if (!verifyToken(token)) {
    return res.status(401).json({
      error: 'unauthorized',
      message: '管理令牌无效或已过期，请重新登录'
    })
  }
  next()
}
