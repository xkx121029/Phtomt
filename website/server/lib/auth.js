import crypto from 'node:crypto'
import { config } from '../config.js'

/**
 * 管理鉴权：无状态签名令牌。
 *
 * 为什么不用 session：官网后台只有一个管理员，引入会话存储只会多一份要持久化和清理的状态。
 * 令牌格式 `expiresAt.hmac`，密钥来自 ADMIN_SECRET，服务端不存任何东西。
 */

function sign(payload) {
  return crypto.createHmac('sha256', config.adminSecret).update(payload).digest('base64url')
}

export function issueToken() {
  const expiresAt = Date.now() + config.adminTokenTtlHours * 3600 * 1000
  const payload = String(expiresAt)
  return { token: `${payload}.${sign(payload)}`, expiresAt }
}

export function verifyToken(token) {
  if (typeof token !== 'string' || !token.includes('.')) return false
  const [payload, mac] = token.split('.')
  if (!payload || !mac) return false
  const expected = sign(payload)
  // 长度不等时 timingSafeEqual 会抛错，先挡掉
  if (mac.length !== expected.length) return false
  if (!crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(expected))) return false
  const expiresAt = Number(payload)
  return Number.isFinite(expiresAt) && Date.now() < expiresAt
}

export function checkPassword(input) {
  const expected = config.adminPassword || ''
  if (!expected) return false
  const a = crypto.createHash('sha256').update(String(input ?? '')).digest()
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
