import fs from 'node:fs'
import path from 'node:path'
import { Router } from 'express'
import multer from 'multer'
import { config } from '../config.js'
import * as store from '../lib/store.js'
import { checkPassword, issueToken, requireAdmin } from '../lib/auth.js'
import {
  apkInfo,
  apkPath,
  audit,
  bodyItems,
  compareVersion,
  fail,
  findRelease,
  humanSize,
  listApkFiles,
  normalizeVersion
} from '../lib/helpers.js'

export const adminRouter = Router()

// ---------- 登录 ----------

adminRouter.post('/login', (req, res) => {
  const { password } = req.body || {}
  if (!checkPassword(password)) {
    return fail(res, 401, 'bad_password', '口令不正确')
  }
  const { token, expiresAt } = issueToken()
  audit(req, 'login', 'admin', '管理后台登录')
  res.json({ token, expiresAt })
})

adminRouter.get('/session', requireAdmin, (req, res) => {
  res.json({ ok: true, expiresAt: Number((req.get('authorization') || '').slice(7).split('.')[0]) })
})

// ---------- 概览与留痕 ----------

adminRouter.get('/overview', requireAdmin, (_req, res) => {
  const releases = store.read('releases')
  const apkFiles = listApkFiles()
  const totalBytes = apkFiles.reduce((sum, f) => sum + f.size, 0)
  res.json({
    counts: {
      releases: releases.length,
      published: releases.filter((r) => r.published !== false).length,
      changelog: store.read('changelog').length,
      docs: store.read('docs').length,
      faq: store.read('faq').length,
      features: store.read('features').length,
      apkFiles: apkFiles.length
    },
    latest: releases.slice().sort((a, b) => compareVersion(a.version, b.version)).pop() || null,
    stats: store.read('stats'),
    storage: { apkBytes: totalBytes, apkText: humanSize(totalBytes) },
    apkFiles,
    recent: store.read('audit').slice(0, 12),
    dataDir: path.relative(config.dataDir, config.dataDir) || '.',
    apkDir: path.relative(process.cwd(), config.apkDir)
  })
})

adminRouter.get('/audit', requireAdmin, (_req, res) => {
  res.json(store.read('audit'))
})

// ---------- 原始集合读写（后台「高级」页与脚本化更新都用它） ----------

const WRITABLE = ['site', 'features', 'releases', 'changelog', 'docs', 'faq']

adminRouter.get('/content/:name', requireAdmin, (req, res) => {
  if (!WRITABLE.includes(req.params.name)) {
    return fail(res, 400, 'bad_collection', `可读集合：${WRITABLE.join(', ')}`)
  }
  res.json(store.read(req.params.name))
})

adminRouter.put('/content/:name', requireAdmin, (req, res) => {
  const { name } = req.params
  if (!WRITABLE.includes(name)) {
    return fail(res, 400, 'bad_collection', `可写集合：${WRITABLE.join(', ')}`)
  }
  const value = req.body
  const isArray = Array.isArray(value)
  if (name === 'site' ? isArray : !isArray) {
    return fail(res, 400, 'bad_shape', name === 'site' ? 'site 必须是对象' : `${name} 必须是数组`)
  }
  store.write(name, value)
  audit(req, 'replace', name, `整包替换，共 ${isArray ? value.length : Object.keys(value).length} 项`)
  res.json({ ok: true, name, count: isArray ? value.length : 1 })
})

// ---------- 站点元信息 ----------

adminRouter.put('/site', requireAdmin, (req, res) => {
  if (!req.body || Array.isArray(req.body)) return fail(res, 400, 'bad_shape', 'site 必须是对象')
  store.write('site', req.body)
  audit(req, 'update', 'site', '整包更新站点元信息')
  res.json(store.read('site'))
})

adminRouter.patch('/site', requireAdmin, (req, res) => {
  if (!req.body || Array.isArray(req.body)) return fail(res, 400, 'bad_shape', 'site 必须是对象')
  const next = deepMerge(store.read('site'), req.body)
  store.write('site', next)
  audit(req, 'update', 'site', `局部更新：${Object.keys(req.body).join(', ')}`)
  res.json(next)
})

// ---------- 特性 ----------

function listRouter(name, idOf, label) {
  const router = Router()

  router.put('/', requireAdmin, (req, res) => {
    const items = bodyItems(req.body)
    if (!items) return fail(res, 400, 'bad_shape', `${name} 必须是数组或 { items: [] }`)
    store.write(name, items)
    audit(req, 'replace', name, `整包替换，共 ${items.length} 条`)
    res.json({ ok: true, count: items.length })
  })

  router.post('/', requireAdmin, (req, res) => {
    const item = req.body
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return fail(res, 400, 'bad_shape', `新增${label}需要一个对象`)
    }
    if (!idOf(item)) return fail(res, 400, 'missing_id', `${label}缺少标识字段`)
    const list = store.read(name)
    if (list.some((it) => idOf(it) === idOf(item))) {
      return fail(res, 409, 'duplicate', `${label} ${idOf(item)} 已存在`)
    }
    store.write(name, [...list, item])
    audit(req, 'create', `${name}/${idOf(item)}`, `新增${label}`)
    res.status(201).json(item)
  })

  router.put('/:id', requireAdmin, (req, res) => {
    const id = req.params.id
    const list = store.read(name)
    const index = list.findIndex((it) => idOf(it) === id)
    if (index === -1) return fail(res, 404, 'not_found', `未找到${label} ${id}`)
    const next = [...list]
    // 标识字段以 URL 为准，防止请求体里改掉主键导致「更新变新增」
    next[index] = { ...next[index], ...req.body, [idOf.key]: id }
    store.write(name, next)
    audit(req, 'update', `${name}/${id}`, `更新${label}`)
    res.json(next[index])
  })

  router.delete('/:id', requireAdmin, (req, res) => {
    const id = req.params.id
    const list = store.read(name)
    const next = list.filter((it) => idOf(it) !== id)
    if (next.length === list.length) return fail(res, 404, 'not_found', `未找到${label} ${id}`)
    store.write(name, next)
    audit(req, 'delete', `${name}/${id}`, `删除${label}`)
    res.json({ ok: true, removed: id })
  })

  return router
}

const idOf = (key) => Object.assign((item) => item?.[key], { key })

adminRouter.use('/features', listRouter('features', idOf('id'), '特性'))
adminRouter.use('/docs', listRouter('docs', idOf('slug'), '文档'))
adminRouter.use('/faq', listRouter('faq', idOf('id'), 'FAQ'))

// ---------- 版本 ----------

adminRouter.post('/releases', requireAdmin, (req, res) => {
  const item = req.body
  if (!item || !item.version) return fail(res, 400, 'missing_version', '缺少 version 字段')
  const version = normalizeVersion(item.version)
  const list = store.read('releases')
  if (list.some((r) => normalizeVersion(r.version) === version)) {
    return fail(res, 409, 'duplicate', `版本 ${version} 已存在，请用 PUT /api/admin/releases/${version} 更新`)
  }
  const record = {
    channel: 'stable',
    published: true,
    date: new Date().toISOString().slice(0, 10),
    ...item,
    version
  }
  if (record.apk?.file) {
    const info = apkInfo(record.apk.file)
    if (!info) return fail(res, 400, 'apk_missing', `APK 文件 ${record.apk.file} 不在磁盘上`)
    record.apk = { ...info }
  }
  store.write('releases', [...list, record])
  audit(req, 'create', `releases/${version}`, `发布版本 ${version}`)
  res.status(201).json(record)
})

adminRouter.put('/releases/:version', requireAdmin, (req, res) => {
  const target = normalizeVersion(req.params.version)
  const list = store.read('releases')
  const index = list.findIndex((r) => normalizeVersion(r.version) === target)
  if (index === -1) return fail(res, 404, 'not_found', `未找到版本 ${target}`)
  const merged = { ...list[index], ...req.body, version: target }
  if (merged.apk?.file) {
    const info = apkInfo(merged.apk.file)
    if (!info) return fail(res, 400, 'apk_missing', `APK 文件 ${merged.apk.file} 不在磁盘上`)
    merged.apk = { ...info }
  }
  const next = [...list]
  next[index] = merged
  store.write('releases', next)
  audit(req, 'update', `releases/${target}`, '更新版本信息')
  res.json(merged)
})

adminRouter.delete('/releases/:version', requireAdmin, (req, res) => {
  const target = normalizeVersion(req.params.version)
  const list = store.read('releases')
  const next = list.filter((r) => normalizeVersion(r.version) !== target)
  if (next.length === list.length) return fail(res, 404, 'not_found', `未找到版本 ${target}`)
  store.write('releases', next)
  audit(req, 'delete', `releases/${target}`, '删除版本')
  res.json({ ok: true, removed: target })
})

// ---------- 更新日志 ----------

adminRouter.post('/changelog', requireAdmin, (req, res) => {
  const item = req.body
  if (!item || !item.version) return fail(res, 400, 'missing_version', '缺少 version 字段')
  const version = normalizeVersion(item.version)
  const list = store.read('changelog')
  if (list.some((c) => normalizeVersion(c.version) === version)) {
    return fail(res, 409, 'duplicate', `${version} 的更新日志已存在`)
  }
  const record = { date: new Date().toISOString().slice(0, 10), sections: [], ...item, version }
  store.write('changelog', [record, ...list])
  audit(req, 'create', `changelog/${version}`, '新增更新日志')
  res.status(201).json(record)
})

adminRouter.put('/changelog', requireAdmin, (req, res) => {
  const items = bodyItems(req.body)
  if (!items) return fail(res, 400, 'bad_shape', 'changelog 必须是数组或 { items: [] }')
  store.write('changelog', items)
  audit(req, 'replace', 'changelog', `整包替换，共 ${items.length} 条`)
  res.json({ ok: true, count: items.length })
})

adminRouter.put('/changelog/:version', requireAdmin, (req, res) => {
  const target = normalizeVersion(req.params.version)
  const list = store.read('changelog')
  const index = list.findIndex((c) => normalizeVersion(c.version) === target)
  if (index === -1) return fail(res, 404, 'not_found', `未找到 ${target} 的更新记录`)
  const next = [...list]
  next[index] = { ...next[index], ...req.body, version: target }
  store.write('changelog', next)
  audit(req, 'update', `changelog/${target}`, '更新更新日志')
  res.json(next[index])
})

adminRouter.delete('/changelog/:version', requireAdmin, (req, res) => {
  const target = normalizeVersion(req.params.version)
  const list = store.read('changelog')
  const next = list.filter((c) => normalizeVersion(c.version) !== target)
  if (next.length === list.length) return fail(res, 404, 'not_found', `未找到 ${target} 的更新记录`)
  store.write('changelog', next)
  audit(req, 'delete', `changelog/${target}`, '删除更新日志')
  res.json({ ok: true, removed: target })
})

// ---------- CHANGELOG.md 导入 ----------

adminRouter.post('/import/changelog', requireAdmin, (req, res) => {
  const markdown = typeof req.body?.markdown === 'string' ? req.body.markdown : ''
  if (!markdown.trim()) return fail(res, 400, 'empty', '请在 markdown 字段里提供 CHANGELOG.md 内容')
  const parsed = parseChangelog(markdown)
  if (!parsed.length) return fail(res, 400, 'no_entries', '没有解析出任何版本条目，请检查格式')
  const mode = req.body?.mode === 'replace' ? 'replace' : 'merge'
  const existing = store.read('changelog')
  let next
  if (mode === 'replace') {
    next = parsed
  } else {
    const byVersion = new Map(existing.map((c) => [normalizeVersion(c.version), c]))
    for (const entry of parsed) byVersion.set(normalizeVersion(entry.version), entry)
    next = [...byVersion.values()]
  }
  next.sort((a, b) => compareVersion(b.version, a.version))
  store.write('changelog', next)
  audit(req, 'import', 'changelog', `导入 ${parsed.length} 条（${mode}）`)
  res.json({ ok: true, imported: parsed.length, mode, total: next.length })
})

// ---------- APK 文件 ----------

const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, config.apkDir),
    filename: (_req, file, cb) => {
      const base = path.basename(file.originalname).replace(/[^\w.\-]/g, '_')
      cb(null, base.toLowerCase().endsWith('.apk') ? base : `${base}.apk`)
    }
  }),
  limits: { fileSize: config.maxApkBytes, files: 1 },
  fileFilter: (_req, file, cb) => {
    const ok = /\.apk$/i.test(file.originalname)
    cb(ok ? null : new Error('只接受 .apk 文件'), ok)
  }
})

adminRouter.get('/apk', requireAdmin, (_req, res) => {
  res.json(listApkFiles())
})

adminRouter.post('/apk', requireAdmin, (req, res) => {
  upload.single('file')(req, res, (err) => {
    if (err) {
      const tooBig = err.code === 'LIMIT_FILE_SIZE'
      return fail(
        res,
        tooBig ? 413 : 400,
        tooBig ? 'too_large' : 'upload_failed',
        tooBig ? `文件超过 ${config.maxApkBytes / 1024 / 1024} MB 上限` : err.message
      )
    }
    if (!req.file) return fail(res, 400, 'no_file', '请求里没有 file 字段')
    const info = apkInfo(req.file.filename)
    audit(req, 'upload', `apk/${req.file.filename}`, `上传 APK（${info?.sizeText}）`)
    res.status(201).json(info)
  })
})

adminRouter.delete('/apk/:file', requireAdmin, (req, res) => {
  const full = apkPath(req.params.file)
  if (!full || !fs.existsSync(full)) return fail(res, 404, 'not_found', '文件不存在')
  const usedBy = store
    .read('releases')
    .filter((r) => r.apk?.file === path.basename(full))
    .map((r) => r.version)
  if (usedBy.length && req.query.force !== '1') {
    return fail(res, 409, 'in_use', `该文件被版本 ${usedBy.join(', ')} 引用，加 ?force=1 强制删除`, { usedBy })
  }
  fs.unlinkSync(full)
  audit(req, 'delete', `apk/${path.basename(full)}`, '删除 APK 文件')
  res.json({ ok: true })
})

// ---------- 工具 ----------

function deepMerge(base, patch) {
  if (Array.isArray(patch)) return patch
  if (patch === null || typeof patch !== 'object') return patch
  const out = { ...(base && typeof base === 'object' && !Array.isArray(base) ? base : {}) }
  for (const [key, value] of Object.entries(patch)) {
    out[key] = key in out ? deepMerge(out[key], value) : value
  }
  return out
}

/**
 * 拼接折行：中文之间不该凭空多出一个空格，英文单词之间又必须有。
 * 判据就是上一段末尾是不是中日韩字符或中文标点。
 */
const CJK_TAIL = /[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]$/
function joinWrapped(prev, next) {
  return CJK_TAIL.test(prev) ? prev + next : `${prev} ${next}`
}

/**
 * 解析项目根目录的 CHANGELOG.md。
 * 兼容 `## [v0.1.333] — 2026-09-20` 与 `## v0.1.333 (2026-09-20)` 两种写法，
 * 小节按 `### 新增 / 优化 / 修复 / 测试` 归类。
 */
export function parseChangelog(markdown) {
  const lines = markdown.split(/\r?\n/)
  const entries = []
  let current = null
  let section = null
  let summaryBuffer = []

  const flushSummary = () => {
    if (!current) return
    const text = summaryBuffer.join(' ').replace(/\s+/g, ' ').trim()
    if (text) current.summary = text.slice(0, 400)
    summaryBuffer = []
  }

  for (const line of lines) {
    const head = line.match(/^##\s+\[?(v?[\d.]+)\]?\s*[—\-–(（]?\s*(\d{4}-\d{2}-\d{2})?/)
    if (head && line.startsWith('## ')) {
      flushSummary()
      current = { version: normalizeVersion(head[1]), date: head[2] || '', summary: '', sections: [] }
      section = null
      entries.push(current)
      continue
    }
    if (!current) continue
    const sub = line.match(/^###\s+(.+?)\s*$/)
    if (sub) {
      flushSummary()
      section = { type: sub[1].trim(), items: [] }
      current.sections.push(section)
      continue
    }
    const bullet = line.match(/^[-*]\s+(.*)$/)
    if (bullet && section) {
      const text = bullet[1].trim()
      if (text) section.items.push(text)
      continue
    }
    // CHANGELOG 里的条目经常折行，续行以缩进开头——要接回上一条，否则句子会被截断
    if (section && section.items.length && /^\s+\S/.test(line)) {
      const last = section.items.length - 1
      section.items[last] = joinWrapped(section.items[last], line.trim())
      continue
    }
    if (!section && line.trim() && !line.startsWith('#')) summaryBuffer.push(line.trim())
  }
  flushSummary()

  return entries
    .filter((e) => e.version && e.version !== 'v')
    .map((e) => ({
      ...e,
      sections: e.sections.filter((s) => s.items.length || s.type)
    }))
}
