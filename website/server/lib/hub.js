import fs from 'node:fs'
import path from 'node:path'
import { Readable } from 'node:stream'
import { config } from '../config.js'

/**
 * happy-hub 文件中枢客户端。
 *
 * 官网只保留 APK 元数据，安装包实体统一存到中枢：后台上传时同步一份，
 * 访客下载时直接 302 到中枢的签名直链，几百兆流量不再经过本服务。
 * 未配置 HUB_URL / HUB_TOKEN 时所有函数按「未启用」处理，路由继续走本地磁盘。
 */

const API_TIMEOUT = 15000
const UPLOAD_TIMEOUT = 30 * 60 * 1000

export function hubEnabled() {
  const h = config.hub
  return Boolean(h.baseUrl && h.token)
}

/** 供后台概览展示连接状态，不回显令牌 */
export function hubSummary() {
  const h = config.hub
  return {
    enabled: hubEnabled(),
    baseUrl: h.baseUrl,
    bucket: h.bucket,
    prefix: h.prefix
  }
}

function authHeaders() {
  return { Authorization: `Bearer ${config.hub.token}` }
}

/** 对象键固定用「前缀 + 文件名」，同一文件重新上传会覆盖而不是堆积 */
function objectKey(fileName) {
  return `${config.hub.prefix}${path.basename(fileName)}`
}

async function request(urlPath, options = {}, timeout = API_TIMEOUT) {
  const h = config.hub
  if (!h.baseUrl || !h.token) throw new Error('未配置 happy-hub 地址或令牌')
  let res
  try {
    res = await fetch(`${h.baseUrl}${urlPath}`, { ...options, signal: AbortSignal.timeout(timeout) })
  } catch (err) {
    const wrapped = new Error(`无法连接文件中枢 ${h.baseUrl}（${err?.cause?.code || err?.message || '网络错误'}）`)
    wrapped.status = 0
    throw wrapped
  }
  const text = await res.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : {}
  } catch {
    data = null
  }
  if (!res.ok || data?.ok === false) {
    const err = new Error(data?.error || `文件中枢返回 ${res.status}`)
    err.status = res.status
    err.code = data?.code || ''
    throw err
  }
  return data || {}
}

/** 桶不存在时中枢对上传返回 404，建一次再重试即可 */
async function ensureBucket() {
  try {
    await request('/api/v1/buckets', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: config.hub.bucket, slug: config.hub.bucket, read_public: false })
    })
  } catch (err) {
    if (err?.status !== 409) throw err
  }
}

/** 把本地 APK 流式上传到中枢，返回中枢对象（id / sha256 / size） */
export async function hubPutApk(absPath, fileName) {
  const stat = await fs.promises.stat(absPath)
  const url = `/api/v1/objects?bucket=${encodeURIComponent(config.hub.bucket)}&key=${encodeURIComponent(objectKey(fileName))}`

  const put = async () => {
    const stream = fs.createReadStream(absPath)
    try {
      const data = await request(
        url,
        {
          method: 'PUT',
          headers: {
            ...authHeaders(),
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Length': String(stat.size)
          },
          body: Readable.toWeb(stream),
          duplex: 'half'
        },
        UPLOAD_TIMEOUT
      )
      return data.object
    } catch (err) {
      stream.destroy()
      throw err
    }
  }

  try {
    return await put()
  } catch (err) {
    if (err?.status !== 404) throw err
    await ensureBucket()
    return await put()
  }
}

/** 按对象键查中枢里是否已有这个 APK；桶不存在等情况统一当作「没有」 */
export async function hubFindApk(fileName) {
  const url = `/api/v1/objects?bucket=${encodeURIComponent(config.hub.bucket)}&key=${encodeURIComponent(objectKey(fileName))}`
  try {
    const data = await request(url, { headers: authHeaders() })
    return (data.items || []).find((item) => !item.is_dir) || null
  } catch (err) {
    if (err?.status === 404) return null
    throw err
  }
}

/** 生成签名直链，默认 15 分钟有效，足够浏览器完成下载 */
export async function hubSign(objectId, expiresSec = 900) {
  const data = await request(`/api/v1/objects/${encodeURIComponent(objectId)}/sign`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ expires_sec: expiresSec })
  })
  if (!data.url) throw new Error('文件中枢未返回下载地址')
  return data.url
}

/**
 * 给下载路由用的组合查询：中枢未启用或对象不存在时返回 null，交由调用方回落本地磁盘。
 * 网络/权限错误会抛出，调用方可以据此写日志。
 */
export async function hubDownloadUrl(fileName, expiresSec = 900) {
  if (!hubEnabled()) return null
  const object = await hubFindApk(fileName)
  if (!object) return null
  return hubSign(object.id, expiresSec)
}

/** 删除中枢对象；对象本来就不存在时视为成功 */
export async function hubDeleteApk(fileName) {
  if (!hubEnabled()) return false
  const object = await hubFindApk(fileName)
  if (!object) return false
  try {
    await request(`/api/v1/objects/${encodeURIComponent(object.id)}`, {
      method: 'DELETE',
      headers: authHeaders()
    })
    return true
  } catch (err) {
    if (err?.status === 404) return false
    throw err
  }
}
