/**
 * 站内引导标记的解析。
 *
 * AI 想带用户去某个页面时，会在回答里单独占一行写：
 *   [[go:/download|下载最新版]]
 * 这里负责把它从正文里剥出来，剩下的正文才是给人看的。
 * 标记语法与 server/lib/nav.js 是同一套约定，改一处必须同时改另一处。
 *
 * 为什么要区分「完整」和「半截」：流式输出时标记是一点点到达的，
 * `[[go:/dow` 这种状态必须先从显示内容里扣住——否则用户会眼看着一行
 * 「代码」慢慢长出来再突然消失。
 */

export const NAV_OPEN = '[[go:'

const NAV_CLOSE = ']]'

/** 一条回答最多认一个标记，与服务端 NAV_MAX_PER_TURN 对齐 */
export const NAV_MAX = 1

/**
 * 拆出引导标记。
 *
 * @param {string} raw 模型吐出来的原始文本
 * @returns {{text:string, navs:{to:string,label:string}[], pending:boolean}}
 *   text    可直接渲染的正文（完整标记已剔除，半截标记已扣住）
 *   navs    已写完的引导目标
 *   pending 末尾还挂着一个没写完的标记
 */
export function splitNavs(raw) {
  const src = String(raw || '')
  const navs = []
  let text = ''
  let cursor = 0
  let pending = false

  for (;;) {
    const start = src.indexOf(NAV_OPEN, cursor)
    if (start < 0) {
      text += src.slice(cursor)
      break
    }

    const end = src.indexOf(NAV_CLOSE, start + NAV_OPEN.length)
    if (end < 0) {
      // 还没写完：前半截正文照常显示，这半截标记扣住不显示
      text += src.slice(cursor, start)
      pending = true
      break
    }

    text += src.slice(cursor, start)
    const body = src.slice(start + NAV_OPEN.length, end)
    const bar = body.indexOf('|')
    const to = (bar < 0 ? body : body.slice(0, bar)).trim()
    const label = (bar < 0 ? '' : body.slice(bar + 1)).trim()
    if (to) navs.push({ to, label })
    cursor = end + NAV_CLOSE.length
  }

  return {
    // 标记自己占了一整行，抹掉后会留下连续空行
    text: text.replace(/\n{3,}/g, '\n\n').trimEnd(),
    navs: navs.slice(0, NAV_MAX),
    pending
  }
}
