/**
 * 极简 Markdown 渲染器。
 *
 * 为什么自己写而不是引 marked：内容全部来自本站自己的数据文件，语法只用到
 * 标题 / 列表 / 表格 / 代码 / 粗体 / 链接这一小撮。自己写的好处是**默认安全**——
 * 先转义再匹配，HTML 在渲染前就已经不是 HTML 了，不存在需要靠净化器兜底的面。
 */

const escapeHtml = (text) =>
  text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')

/** 只放行明确安全的协议，挡住 javascript: 与 data: 这类可执行地址 */
function safeUrl(url) {
  const value = String(url || '').trim()
  if (/^(https?:|mailto:|tel:)/i.test(value)) return value
  if (value.startsWith('/') || value.startsWith('#') || value.startsWith('.')) return value
  return '#'
}

function inline(text) {
  const codes = []
  // 行内代码先抽出来占位，避免其中的 * 和 [ 被后续规则误伤
  let out = text.replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code)
    return `\u0000${codes.length - 1}\u0000`
  })

  out = out
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) => {
      const href = safeUrl(url)
      const external = /^https?:/i.test(href)
      return `<a href="${href}"${external ? ' target="_blank" rel="noopener noreferrer"' : ''}>${label}</a>`
    })
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<em>$2</em>')

  return out.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[Number(i)]}</code>`)
}

const isTableRow = (line) => /^\s*\|.*\|\s*$/.test(line)
const isTableDivider = (line) => /^\s*\|?[\s:-]*-[\s|:-]*\|?\s*$/.test(line) && line.includes('-')

function splitRow(line) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

export function renderMarkdown(source) {
  if (!source) return ''
  const lines = escapeHtml(String(source).replace(/\r\n/g, '\n')).split('\n')
  const html = []
  let i = 0

  const flushParagraph = (buffer) => {
    if (!buffer.length) return
    html.push(`<p>${inline(buffer.join(' '))}</p>`)
    buffer.length = 0
  }

  const paragraph = []

  while (i < lines.length) {
    const line = lines[i]

    // 代码块
    const fence = line.match(/^\s*```(\w*)\s*$/)
    if (fence) {
      flushParagraph(paragraph)
      const lang = fence[1]
      const body = []
      i += 1
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) {
        body.push(lines[i])
        i += 1
      }
      i += 1
      html.push(
        `<pre class="code code--dark"${lang ? ` data-lang="${lang}"` : ''}><code>${body.join('\n')}</code></pre>`
      )
      continue
    }

    // 标题
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      flushParagraph(paragraph)
      const level = Math.min(heading[1].length + 1, 5)
      html.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      i += 1
      continue
    }

    // 分隔线
    if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) {
      flushParagraph(paragraph)
      html.push('<hr class="divider" />')
      i += 1
      continue
    }

    // 表格
    if (isTableRow(line) && isTableDivider(lines[i + 1] || '')) {
      flushParagraph(paragraph)
      const head = splitRow(line)
      i += 2
      const rows = []
      while (i < lines.length && isTableRow(lines[i])) {
        rows.push(splitRow(lines[i]))
        i += 1
      }
      html.push(
        `<div class="table-wrap"><table><thead><tr>${head
          .map((c) => `<th>${inline(c)}</th>`)
          .join('')}</tr></thead><tbody>${rows
          .map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`)
          .join('')}</tbody></table></div>`
      )
      continue
    }

    // 引用
    if (/^\s*>\s?/.test(line)) {
      flushParagraph(paragraph)
      const body = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        body.push(lines[i].replace(/^\s*>\s?/, ''))
        i += 1
      }
      html.push(`<blockquote>${inline(body.join(' '))}</blockquote>`)
      continue
    }

    // 列表（含续行：缩进且不是新条目时接回上一项）
    const bullet = line.match(/^\s*([-*]|\d+\.)\s+(.*)$/)
    if (bullet) {
      flushParagraph(paragraph)
      const ordered = /\d+\./.test(bullet[1])
      const items = []
      while (i < lines.length) {
        const item = lines[i].match(/^\s*([-*]|\d+\.)\s+(.*)$/)
        if (item) {
          items.push(item[2])
          i += 1
          continue
        }
        if (items.length && /^\s+\S/.test(lines[i])) {
          items[items.length - 1] += ` ${lines[i].trim()}`
          i += 1
          continue
        }
        break
      }
      const tag = ordered ? 'ol' : 'ul'
      html.push(`<${tag}>${items.map((it) => `<li>${inline(it)}</li>`).join('')}</${tag}>`)
      continue
    }

    // 空行
    if (!line.trim()) {
      flushParagraph(paragraph)
      i += 1
      continue
    }

    paragraph.push(line.trim())
    i += 1
  }

  flushParagraph(paragraph)
  return html.join('\n')
}

/** 从正文里抽出标题，用于文档页右侧目录 */
export function extractHeadings(source) {
  const out = []
  for (const line of String(source || '').split(/\r?\n/)) {
    const match = line.match(/^(#{2,3})\s+(.*)$/)
    if (match) {
      out.push({
        level: match[1].length,
        text: match[2].replace(/[`*]/g, '').trim(),
        id: slugify(match[2].replace(/[`*]/g, '').trim())
      })
    }
  }
  return out
}

export function slugify(text) {
  return String(text)
    .trim()
    .toLowerCase()
    .replace(/[^\w\u4e00-\u9fff]+/g, '-')
    .replace(/^-|-$/g, '')
}
