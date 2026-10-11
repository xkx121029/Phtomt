import fs from 'node:fs'
import path from 'node:path'
import { config, originOf } from '../config.js'
import { contentRevision } from './store.js'
import { buildBootstrap } from '../routes/public.js'

/**
 * HTML 外壳注入。
 *
 * 原先是 `res.sendFile(dist/index.html)` —— 一份 1.5KB 的空壳，首屏要等 JS 把内容拉回来
 * 才有像素。这里把「当前内容快照」直接烘进 HTML（window.__PHT_BOOTSTRAP__），前端
 * client.js 命中它就不再走 /api/bootstrap 往返；后台一保存（store.write 自增版本号），
 * 下一次请求即重建，无需重启、无需重新构建。
 *
 * 全程只在内存里拼字符串，不落盘：dist/ 是构建产物，往里写文件迟早被下次构建冲掉。
 */

const TEMPLATE_FILE = path.join(config.distDir, 'index.html')
// Vite 注入的模块脚本标签。首屏数据是普通内联脚本，排在它前面即可保证先于模块执行。
const MODULE_MARK = '<script type="module"'

let template = null
let templateStamp = -1
let cached = { key: null, html: '' }

/** dist/index.html 按 mtime 惰性读入；重新构建后自动取到新模板 */
function readTemplate() {
  const stamp = fs.statSync(TEMPLATE_FILE).mtimeMs
  if (template === null || stamp !== templateStamp) {
    template = fs.readFileSync(TEMPLATE_FILE, 'utf8')
    templateStamp = stamp
  }
  return template
}

function inject(templateHtml, payload) {
  const tag = `<script>window.__PHT_BOOTSTRAP__=${payload}</script>`
  const at = templateHtml.indexOf(MODULE_MARK)
  const withScript =
    at !== -1
      ? templateHtml.slice(0, at) + `${tag}\n    ` + templateHtml.slice(at)
      : // 模板里找不到模块脚本时（构建配置被改过）退到 </body> 之前，别把注入整个丢掉
        (() => {
          const end = templateHtml.lastIndexOf('</body>')
          return end === -1
            ? `${templateHtml}\n${tag}`
            : templateHtml.slice(0, end) + `${tag}\n` + templateHtml.slice(end)
        })()
  return inlineStyles(withScript)
}

/* 入口样式表在 <head> 里，是渲染阻塞资源：浏览器要等它下完才肯画第一帧，
   我们内联的骨架因此也被推迟。把它整段并入 HTML，省掉一次往返——CSS 不大
   （gzip 后约 7KB），并进去后零额外请求，FCP 直接对齐 TTFB。 */
const CSS_LINK = /<link\s+rel="stylesheet"[^>]*href="([^"]+\.css)"[^>]*>/g

function inlineStyles(html) {
  if (!html.includes('rel="stylesheet"')) return html
  return html.replace(CSS_LINK, (tag, href) => {
    try {
      const css = fs.readFileSync(path.join(config.distDir, href.replace(/^\//, '')), 'utf8')
      return `<style>${css}</style>`
    } catch {
      // 读不到就原样保留外链，绝不让页面裸奔
      return tag
    }
  })
}

/**
 * 返回可直接 send 的 HTML 字符串。
 * 缓存键 = 内容版本 + 模板 mtime + 站点源；三者都没变就直接复用上一次拼好的整份 HTML。
 * 模板 mtime 也要进键：重新构建会换掉资源哈希，只比值内容版本会端出指向旧文件的旧壳。
 */
export function renderHtml(req) {
  const templateHtml = readTemplate()
  const origin = originOf(req)
  // 首屏数据只有首页渲染时才读（client.js 的 content.bootstrap 全站只被 HomeView 调用）。
  // 无差别注入等于把约 30KB 的 JSON 塞进每一个页面，那些页面根本用不上。
  const withBootstrap = req.path === '/'
  const key = `${contentRevision()}|${templateStamp}|${origin}|${withBootstrap ? 1 : 0}`
  if (cached.key === key) return cached.html

  const html = withBootstrap
    ? // 序列化时转义 `<`，否则内容里出现 `</script>` 会提前闭合内联脚本
      inject(templateHtml, JSON.stringify(buildBootstrap(req)).replace(/</g, '\\u003c'))
    : inlineStyles(templateHtml)
  cached = { key, html }
  return cached.html
}
