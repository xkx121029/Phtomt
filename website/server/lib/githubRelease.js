import fs from 'node:fs'
import path from 'node:path'
import { config } from '../config.js'

/**
 * GitHub Releases 同步。
 *
 * 后台发布版本时把安装包再传一份到 GitHub Releases：同一版本复用 Release，
 * 同名附件先删后传，避免重复上传时 422。未配置 GITHUB_REPO / GITHUB_TOKEN 时
 * 所有函数按「未启用」处理，后台照常发布，只是不产生 Release。
 */

const API = 'https://api.github.com'
const UPLOAD = 'https://uploads.github.com'
const API_TIMEOUT = 30000
const UPLOAD_TIMEOUT = 30 * 60 * 1000

export function githubEnabled() {
  const g = config.github
  return Boolean(g.repo && g.token)
}

/** 供后台概览展示同步状态，不回显令牌 */
export function githubSummary() {
  return {
    enabled: githubEnabled(),
    repo: config.github.repo,
    prereleaseChannels: config.github.prereleaseChannels
  }
}

function authHeaders() {
  return {
    Authorization: `Bearer ${config.github.token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'phtomt-site'
  }
}

async function request(url, options = {}, timeout = API_TIMEOUT) {
  let res
  try {
    res = await fetch(url, {
      ...options,
      headers: { ...authHeaders(), ...(options.headers || {}) },
      signal: AbortSignal.timeout(timeout)
    })
  } catch (err) {
    const wrapped = new Error(
      `无法连接 GitHub（${err?.cause?.code || err?.message || '网络错误'}）`
    )
    wrapped.status = 0
    throw wrapped
  }
  const text = await res.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = null
  }
  if (!res.ok) {
    const err = new Error(data?.message || `GitHub 返回 ${res.status}`)
    err.status = res.status
    err.detail = Array.isArray(data?.errors) ? data.errors.map((e) => e.message || e.code).join('；') : ''
    throw err
  }
  return data
}

/** Release 正文：摘要 + 要点列表，空内容时退回归档说明 */
function releaseBody({ summary, notes, channel }) {
  const lines = []
  if (summary) lines.push(summary.trim(), '')
  const list = Array.isArray(notes) ? notes.filter(Boolean) : []
  if (list.length) lines.push(...list.map((n) => `- ${String(n).trim()}`), '')
  lines.push(`渠道：${channel || 'stable'}`, '', '安装包由官网后台自动同步。')
  return lines.join('\n')
}

async function findRelease(repo, tag) {
  try {
    return await request(`${API}/repos/${repo}/releases/tags/${encodeURIComponent(tag)}`)
  } catch (err) {
    if (err.status === 404) return null
    throw err
  }
}

async function ensureRelease(repo, tag, meta) {
  const existing = await findRelease(repo, tag)
  if (existing) return existing
  const payload = {
    tag_name: tag,
    name: meta.title ? `${tag} ${meta.title}` : tag,
    body: releaseBody(meta),
    prerelease: config.github.prereleaseChannels.includes(meta.channel || 'stable'),
    draft: false
  }
  try {
    return await request(`${API}/repos/${repo}/releases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
  } catch (err) {
    // 标签已存在但还没有 Release 时 GitHub 会回 422，重查一次直接复用
    if (err.status === 422) {
      const again = await findRelease(repo, tag)
      if (again) return again
    }
    throw err
  }
}

async function removeAsset(repo, release, fileName) {
  const same = (release.assets || []).find((a) => a.name === fileName)
  if (!same) return
  await request(`${API}/repos/${repo}/releases/assets/${same.id}`, { method: 'DELETE' })
}

/**
 * 把安装包同步到 GitHub Release。返回 { tag, releaseUrl, assetUrl, replaced }。
 * 失败抛错，由调用方决定是否只记日志。
 */
export async function publishReleaseApk({
  version,
  title = '',
  summary = '',
  notes = [],
  channel = 'stable',
  filePath,
  fileName,
  force = false
}) {
  if (!githubEnabled()) throw new Error('未配置 GITHUB_REPO 或 GITHUB_TOKEN')
  if (!filePath || !fs.existsSync(filePath)) throw new Error(`安装包不存在：${fileName || filePath}`)

  const repo = config.github.repo
  const tag = String(version).startsWith('v') ? String(version) : `v${version}`
  const name = path.basename(filePath)
  const meta = { title, summary, notes, channel }

  const release = await ensureRelease(repo, tag, meta)
  const replaced = (release.assets || []).some((a) => a.name === name)
  if (replaced) await removeAsset(repo, release, name)

  const size = fs.statSync(filePath).size
  const asset = await request(
    `${UPLOAD}/repos/${repo}/releases/${release.id}/assets?name=${encodeURIComponent(name)}`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': String(size)
      },
      body: fs.createReadStream(filePath),
      duplex: 'half'
    },
    UPLOAD_TIMEOUT
  )

  return {
    tag,
    releaseUrl: release.html_url,
    assetUrl: asset.browser_download_url,
    replaced
  }
}
