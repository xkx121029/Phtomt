import fs from 'node:fs'
import path from 'node:path'
import express from 'express'
import compression from 'compression'
import { config, originOf } from './config.js'
import { publicRouter, SITE_ROUTES } from './routes/public.js'
import { downloadRouter } from './routes/download.js'
import { adminRouter } from './routes/admin.js'
import { aiRouter, aiAdminRouter } from './routes/ai.js'
import { openApiDocument } from './lib/openapi.js'

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
app.use('/api/admin/ai', aiAdminRouter)
app.use('/api/admin', adminRouter)

app.get('/api/openapi', (req, res) => {
  res.json(openApiDocument(originOf(req)))
})

// ---------- APK 静态直出 ----------

// 运行时上传的包写在 public/apk，与构建产物解耦：重新构建前端不会丢包。
app.use(
  '/apk',
  express.static(config.apkDir, {
    maxAge: '7d',
    setHeaders(res, filePath) {
      if (filePath.toLowerCase().endsWith('.apk')) {
        res.setHeader('Content-Type', 'application/vnd.android.package-archive')
      }
    }
  })
)

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
    res.sendFile(path.join(distDir, 'index.html'))
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
  if (config.generatedPassword) {
    console.log(`\n  本次启动的临时管理口令：${config.generatedPassword}`)
    console.log(`  （设置 .env 里的 ADMIN_PASSWORD 可固定口令）\n`)
  }
})
