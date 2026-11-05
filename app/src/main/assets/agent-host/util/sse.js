// Minimal Server-Sent Events parser for fetch() response streams.
//
// 兼容面（这些情况在真实网关上都见过，任何一种没兜住都会表现为「模型没输出」）：
//   - `data:{...}` 与 `data: {...}`（冒号后有无空格）
//   - 跨多行的 data（SSE 规范允许，多行按 \n 拼成一个 payload）
//   - CRLF / 只有 \r 的换行、UTF-8 BOM、注释/keep-alive 行
//   - `event:` 事件名（Anthropic 的 error 事件、部分网关的 ping）
//   - `[DONE]`（大小写/空白变体）
//   - 块式（空行分隔）与「一行一个 JSON」的非规范网关
// 输出统一为 JSON 对象；带事件名时附加 `__event` 字段，不污染原有 `type`。

export async function* parseSSE(body, { signal } = {}) {
  if (!body) throw new Error('parseSSE: response has no body');
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let dataLines = [];
  let eventName = '';

  /** 把已收集的 data 行解析成对象数组；解析失败的行直接跳过（SSE 天生有损）。 */
  const flush = (out) => {
    if (!dataLines.length) {
      eventName = '';
      return;
    }
    const joined = dataLines.join('\n');
    dataLines = [];
    const name = eventName;
    eventName = '';
    if (!joined.trim()) return;
    const decorate = (obj) => (name ? { ...obj, __event: name } : obj);

    try {
      out.push(decorate(JSON.parse(joined)));
      return;
    } catch {
      /* 非规范网关：多行其实是多个独立 JSON，逐行兜底 */
    }
    for (const line of joined.split('\n')) {
      const t = line.trim();
      if (!t) continue;
      try {
        out.push(decorate(JSON.parse(t)));
      } catch {
        /* 半行/坏行：交给调用方按「没有事件」处理 */
      }
    }
  };

  try {
    while (true) {
      if (signal?.aborted) return;
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // 首块可能带 BOM：不剥掉的话第一行永远不是合法的 `data:`。
      if (buffer.charCodeAt(0) === 0xfeff) buffer = buffer.slice(1);
      buffer = buffer.replace(/\r\n?/g, '\n');

      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);

        if (!line) {
          // 空行 = 一个事件结束（规范行为）
          const out = [];
          flush(out);
          for (const ev of out) yield ev;
          continue;
        }
        if (line.startsWith(':')) continue; // comment / keep-alive
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim();
          continue;
        }
        if (line.startsWith('id:') || line.startsWith('retry:')) continue;
        if (!line.startsWith('data:')) continue;

        const payload = line.slice(5).replace(/^ /, '');
        if (/^\[DONE\]$/i.test(payload.trim())) return;
        dataLines.push(payload);

        // 非规范网关常常「一行一个完整 JSON、但不发空行」：能单独解析就先吐，
        // 免得非要等到空行才把整轮输出憋住（这正是流式失效的典型表现）。
        if (dataLines.length === 1 && payload.trim().startsWith('{')) {
          try {
            const obj = JSON.parse(payload);
            dataLines = [];
            const name = eventName;
            eventName = '';
            yield name ? { ...obj, __event: name } : obj;
          } catch {
            /* 还没拼完整，继续等服务端发下一行 */
          }
        }
      }
    }

    // 流结束时缓冲区里可能还有最后一行（服务端没补换行）。
    if (buffer) {
      const out = [];
      if (buffer.startsWith('data:')) {
        const payload = buffer.slice(5).replace(/^ /, '');
        if (!/^\[DONE\]$/i.test(payload.trim())) dataLines.push(payload);
      }
      flush(out);
      for (const ev of out) yield ev;
    }
    const tail = [];
    flush(tail);
    for (const ev of tail) yield ev;
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* already closed */
    }
  }
}
