#!/usr/bin/env node
/**
 * 静态导出收尾：把首屏数据与入口 CSS 直接写进 dist-static/index.html。
 *
 * 为什么要这一步：Express 版由 server/lib/htmlShell.js 在响应时注入
 * `window.__PHT_BOOTSTRAP__` 并内联 CSS，GitHub Pages 没有这个中间层，
 * 不补上的话首屏要等 JS 加载完、再动态 import 216KB 快照才出内容。
 *
 * 只处理导出目录，不改构建产物以外的任何东西。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { snapshot } from '../src/data/fallback.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const dist = path.join(root, 'dist-static')
const indexFile = path.join(dist, 'index.html')

if (!fs.existsSync(indexFile)) {
  console.error('  ! dist-static/index.html 不存在，先跑 STATIC_EXPORT=1 vite build')
  process.exit(1)
}

let html = fs.readFileSync(indexFile, 'utf8')

/** 与 server/lib/htmlShell.js 一致：转义 `<`，避免内容里的 </script> 提前闭合内联脚本 */
function injectBootstrap(source) {
  if (source.includes('window.__PHT_BOOTSTRAP__')) return source
  const payload = JSON.stringify(snapshot.bootstrap).replace(/</g, '\\u003c')
  const tag = `<script>window.__PHT_BOOTSTRAP__=${payload}</script>`
  const mark = '<script type="module"'
  const at = source.indexOf(mark)
  if (at !== -1) return source.slice(0, at) + `${tag}\n    ` + source.slice(at)
  const end = source.lastIndexOf('</body>')
  return end === -1 ? `${source}\n${tag}` : source.slice(0, end) + `${tag}\n` + source.slice(end)
}

/** 入口 CSS 是渲染阻塞资源，内联后省掉一次往返（base 为 ./，href 形如 ./assets/index-xxx.css） */
const CSS_LINK = /<link\s+rel="stylesheet"[^>]*href="([^"]+\.css)"[^>]*>/g

function inlineStyles(source) {
  return source.replace(CSS_LINK, (tag, href) => {
    try {
      const rel = href.replace(/^\.\//, '').replace(/^\//, '')
      const css = fs.readFileSync(path.join(dist, rel), 'utf8')
      return `<style>${css}</style>`
    } catch {
      // 读不到就保留外链，页面照常可用
      return tag
    }
  })
}

html = inlineStyles(injectBootstrap(html))
fs.writeFileSync(indexFile, html, 'utf8')

/**
 * GitHub Pages 对不存在的路径回 404.html。带上 <base href="/"> 后，
 * 里面的 ./assets/... 仍按站点根解析，不会在深路径下拼成 404。
 */
const notFound = html.replace(/<head>/, '<head>\n    <base href="/" />')
fs.writeFileSync(path.join(dist, '404.html'), notFound, 'utf8')

const kb = (n) => `${(n / 1024).toFixed(0)} KB`
console.log(
  `  ✓ 静态站收尾：index.html ${kb(Buffer.byteLength(html))}（已内联 CSS 与首屏数据）+ 404.html`
)
