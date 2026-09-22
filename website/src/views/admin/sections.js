/**
 * 结构化字段与纯文本之间的互转。
 *
 * 后台的输入控件只有单行、多行和下拉三种，遇到嵌套结构就得给一层文本编码。
 * 编码放在这里而不是视图里：格式一旦定下来，就是数据契约的一部分，
 * 将来写脚本批量导入也要按同一套规则来。
 *
 * 两套编码：
 *  - 更新日志的「小节」是 [{ type, items: [] }]，文本格式与项目根目录的 CHANGELOG.md 一致，
 *    直接粘一段过来就能用；
 *  - 场景的「步骤」是 [{ title, detail }]，一行一步，标题与说明之间用第一个竖线分隔。
 */

export function sectionsToText(sections) {
  if (!Array.isArray(sections) || !sections.length) return ''
  return sections
    .map((sec) => {
      const head = `## ${sec.type || '变更'}`
      const body = (sec.items || []).map((item) => `- ${item}`).join('\n')
      return body ? `${head}\n${body}` : head
    })
    .join('\n\n')
}

export function textToSections(text) {
  const sections = []
  let current = null

  for (const raw of String(text || '').split(/\r?\n/)) {
    const head = raw.match(/^#{2,3}\s+(.+?)\s*$/)
    if (head) {
      current = { type: head[1].trim(), items: [] }
      sections.push(current)
      continue
    }
    const bullet = raw.match(/^[-*]\s+(.*)$/)
    if (bullet) {
      if (!current) {
        current = { type: '变更', items: [] }
        sections.push(current)
      }
      if (bullet[1].trim()) current.items.push(bullet[1].trim())
      continue
    }
    // 折行续接：更新日志里的条目经常换行，续行要接回上一条，否则句子会被截断
    if (current?.items.length && /^\s+\S/.test(raw)) {
      const last = current.items.length - 1
      const prev = current.items[last]
      const next = raw.trim()
      current.items[last] = /[\u4e00-\u9fff]$/.test(prev) ? prev + next : `${prev} ${next}`
    }
  }

  return sections.filter((sec) => sec.items.length)
}

/** 步骤 → 文本。一行一步，形如「启动设置 | 用 launch 走包名直接拉起」 */
export function stepsToText(steps) {
  if (!Array.isArray(steps) || !steps.length) return ''
  return steps
    .map((step) => {
      const title = String(step?.title || '').trim()
      const detail = String(step?.detail || '').trim()
      return detail ? `${title} | ${detail}` : title
    })
    .filter(Boolean)
    .join('\n')
}

/**
 * 文本 → 步骤。
 * 只按**第一个**竖线切分：说明文字里出现竖线是常事（写命令行管道、写表格行），
 * 按全部竖线切会把后半句吞掉。
 */
export function textToSteps(text) {
  return String(text || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const at = line.indexOf('|')
      if (at === -1) return { title: line, detail: '' }
      return { title: line.slice(0, at).trim(), detail: line.slice(at + 1).trim() }
    })
    .filter((step) => step.title)
}
