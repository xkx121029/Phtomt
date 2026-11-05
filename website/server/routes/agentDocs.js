import { Router } from 'express'
import { fail } from '../lib/helpers.js'
import { getDoc, listDocs } from '../lib/agentDocs.js'

/**
 * Agent 执行文档接口（给 App 内部的 AI 用，不是官网页面内容）。
 *
 *   GET /api/agent-docs/index.json   索引：id / 标题 / 匹配条件 / 版本 / ETag
 *   GET /api/agent-docs/doc/:id.md   正文：按需取，客户端只拉命中的那几篇
 *
 * 语义是「实时更新」：改磁盘上的 .md 立刻生效，不需要重启、不需要重新发布。
 * 客户端带 If-None-Match 时回 304，避免每次规划都重复下载正文。
 */
export const agentDocsRouter = Router()

function etagMatches(req, etag) {
  const header = req.get('if-none-match')
  if (!header) return false
  return header
    .split(',')
    .map((s) => s.trim().replace(/^W\//, '').replace(/"/g, ''))
    .some((s) => s === etag)
}

agentDocsRouter.get(['/index.json', '/index'], (req, res) => {
  const index = listDocs()
  res.setHeader('Cache-Control', 'public, max-age=30, s-maxage=60, stale-while-revalidate=60')
  res.setHeader('ETag', `"idx-${index.version}"`)
  res.setHeader('X-Docs-Count', String(index.count))
  if (etagMatches(req, `idx-${index.version}`)) return res.status(304).end()
  res.json(index)
})

agentDocsRouter.get('/doc/:id', (req, res) => {
  const doc = getDoc(req.params.id)
  if (!doc) {
    return fail(res, 404, 'not_found', `未找到执行文档 ${req.params.id}`)
  }
  res.setHeader('Content-Type', 'text/markdown; charset=utf-8')
  res.setHeader('Cache-Control', 'public, max-age=30, s-maxage=60, stale-while-revalidate=60')
  res.setHeader('ETag', `"${doc.etag}"`)
  res.setHeader('X-Doc-Id', doc.id)
  res.setHeader('X-Doc-Version', doc.version)
  res.setHeader('X-Doc-Title', encodeURIComponent(doc.title))
  if (etagMatches(req, doc.etag)) return res.status(304).end()
  res.send(doc.body)
})
