import fs from 'node:fs'
import path from 'node:path'
import express from 'express'
import compression from 'compression'
import { config, originOf } from './config.js'
import { publicRouter, SITE_ROUTES } from './routes/public.js'
import { downloadRouter } from './routes/download.js'
import { updateRouter } from './routes/update.js'
import { agentDocsRouter } from './routes/agentDocs.js'
import { adminRouter } from './routes/admin.js'
import { aiRouter, aiAdminRouter } from './routes/ai.js'
import { feedbackRouter, feedbackAdminRouter } from './routes/feedback.js'
import { openApiDocument } from './lib/openapi.js'
import { renderHtml } from './lib/htmlShell.js'
import { init as initReleaseFeed } from './lib/releaseFeed.js'
import { initGiteeAssets } from './lib/externalAssets.js'

// 版本数据以 GitHub Releases 为准：先把首份数据拉回来再对外服务，
// 拉到就全站按它走；拉不到自动降级到 releases.json，接口里会标明 degraded。
const feed = await initReleaseFeed()

// Gitee 的 Release 列表在后台预热 + 定时刷新：下载 302 只读内存，
// 不再让用户点一次下载先等 Gitee 接口（接口超时是 6 秒）。
initGiteeAssets()

const app = express()

app.disable('x-powered-by')
app.set('trust proxy', true)
app.use(compression())
app.use(express.json({ limit: '4mb' }))
app.use(express.urlencoded({ extended: false }))

if (config.corsOrigins.length) {
  app.use((req, res, next) => {
    const origin = req.get('origin')
    if (origin && config.corsOrigins.includes(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin)
      res.setHeader('Vary', 'Origin')
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization')
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS')
    }
    if (req.method === 'OPTIONS') return res.sendStatus(204)
    next()
  })
}

// ---------- 内容 API ----------

app.use('/api', publicRouter)
app.use('/api/ai', aiRouter)
app.use('/api/download', downloadRouter)
app.use('/api/update', updateRouter)
app.use('/api/agent-docs', agentDocsRouter)
app.use('/api/feedback', feedbackRouter)
app.use('/api/admin/feedback', feedbackAdminRouter)
app.use('/api/admin/ai', aiAdminRouter)
app.use('/api/admin', adminRouter)

app.get('/api/openapi', (req, res) => {
  res.json(openApiDocument(originOf(req)))
})

// ---------- 安装包 ----------
// 官网不再托管 APK：下载接口只做 302（见 routes/download.js 与 routes/update.js），
// 磁盘上也不再保留 public/apk 目录。旧的静态地址明确返回 410，避免被前端路由
// 兜底成一张 HTML（调用方会以为下载成功了）。

app.use('/apk', (_req, res) => {
  res.status(410).json({
    error: 'gone',
    message: '安装包已迁移到 GitHub / Gitee 的 Release，请通过 /api/download/:version 获取'
  })
})

// ---------- SEO 辅助 ----------

app.get('/robots.txt', (req, res) => {
  const origin = originOf(req)
  res.type('text/plain').send(`User-agent: *\nAllow: /\nDisallow: /admin\nSitemap: ${origin}/sitemap.xml\n`)
})

app.get('/sitemap.xml', (req, res) => {
  const origin = originOf(req)
  const urls = SITE_ROUTES.filter((r) => !r.path.includes(':') && !r.path.startsWith('/admin'))
  const body = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls.map((r) => `  <url><loc>${origin}${r.path}</loc><changefreq>weekly</changefreq></url>`).join('\n')}
</urlset>`
  res.type('application/xml').send(body)
})

// ---------- 前端托管 ----------

const distDir = config.distDir
const hasBuild = fs.existsSync(path.join(distDir, 'index.html'))

if (hasBuild) {
  app.use(express.static(distDir, { index: false, maxAge: '1h' }))
  app.get('*', (req, res, next) => {
    // 只有 /api 和 /api/xxx 才算 API 前缀，别把别的路径也吞掉
    if (req.path === '/api' || req.path.startsWith('/api/')) return next()
    // HTML 里烘了当前内容（见 lib/htmlShell.js）。max-age=0 让浏览器每次校验，
    // s-maxage 允许 Cloudflare 在边缘短存 60s——源站（NAS）回源一次要 1.1s，
    // 而原先 Cache-Control 是 max-age=0 且无 s-maxage，边缘一律 DYNAMIC、每请求都回源。
    res.setHeader('Cache-Control', 'public, max-age=0, s-maxage=60, stale-while-revalidate=30')
    res.type('html').send(renderHtml(req))
  })
} else {
  app.get('/', (_req, res) => {
    res
      .status(200)
      .type('text/html')
      .send(
        `<!doctype html><meta charset="utf-8"><title>Phtomt Site</title>
<body style="font:15px/1.6 system-ui;padding:48px;max-width:640px;margin:auto">
<h1>API 已就绪</h1>
<p>前端还没构建。开发时请另开一个终端跑 <code>npm run dev:web</code>（Vite 会把 /api 代理到这里），
或先执行 <code>npm run build</code> 让本服务直接托管前端。</p>
<p>试试 <a href="/api/health">/api/health</a> · <a href="/api/bootstrap">/api/bootstrap</a> · <a href="/api/openapi">/api/openapi</a></p>
</body>`
      )
  })
}

// 兜底错误处理：任何未捕获异常都返回结构化 JSON，前端统一按 message 提示
app.use((err, _req, res, _next) => {
  console.error('[server] 未处理异常：', err)
  res.status(err.status || 500).json({ error: 'internal_error', message: err.message || '服务内部错误' })
})

app.listen(config.port, () => {
  const base = `http://localhost:${config.port}`
  console.log(`\n  Phtomt 官网服务已启动`)
  console.log(`  ├─ 站点      ${base}${hasBuild ? '' : '  （前端未构建，仅 API 可用）'}`)
  console.log(`  ├─ API 文档  ${base}/api/openapi`)
  console.log(`  └─ 管理后台  ${base}/admin`)
  console.log(
    `  版本数据    ${feed.source === 'github' ? `GitHub（${feed.list[0]?.version || '—'}）` : `本地兜底（GitHub 不可达：${feed.error}）`}`
  )
  if (config.generatedPassword) {
    console.log(`\n  本次启动的临时管理口令：${config.generatedPassword}`)
    console.log(`  （设置 .env 里的 ADMIN_PASSWORD 可固定口令）\n`)
  }
})
