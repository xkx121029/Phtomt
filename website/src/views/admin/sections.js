/**
 * 更新日志的「小节」在数据里是 [{ type, items: [] }]，在后台里用文本编辑更顺手。
 * 文本格式与项目根目录的 CHANGELOG.md 一致，直接粘一段过来就能用。
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
