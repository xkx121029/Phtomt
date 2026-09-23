package com.phoneagent.feature.browser.script

import com.phoneagent.core.text.HtmlToMarkdown

/**
 * 抓取当前网页：标题/网址/滚动 + **Markdown 正文** + **可操作元素清单**。
 *
 * 正文为什么给 Markdown：AI 极熟 Markdown（write_doc 产出的就是它），标题层级、列表、
 * 表格、代码块、内联链接都能带上；压平成一坨纯文本时这些都丢了，AI 只能瞎猜页面结构。
 * 链接**内联**在正文里（`[文字](网址)`），AI 要点击时直接取链接文字，或取下方清单里的元素文字——
 * 两者可寻址，且"清单里显示的文字"与 [InteractScripts] 的查找用的是同一份 `labelOf`（见 [BrowserJs]）。
 *
 * 四重上限防低端机卡死：节点 15000 / 深度 120 / 输出 8000 / 单次 1500ms。
 * 这里的 8000 只是**安全上限**，给 AI 的 4000 预算截断统一由 Kotlin 的
 * [HtmlToMarkdown.takeBlocks] 执行，保证与 fetch 链路行为一致。
 *
 * **[READ] 的规则表与 `core/text/HtmlToMarkdown.kt` 同源**：
 * 丢弃表 / 块级表 / 自闭合表 / 行内标记表 / 隐藏类名表 / 转义字符表 / 围栏字面量
 * 全部由 [HtmlToMarkdown] 的常量插值生成，改规则只会改一处，两侧不可能漂移。
 * 与 Kotlin 侧**唯一允许的两处差异**（其余逐条一致）：
 * 1. 这里能用 `getComputedStyle` 看到被样式表藏起来的元素，Kotlin 只能看属性；
 * 2. 这里用浏览器原生能力解码实体（是所有命名实体的超集），Kotlin 只认 32 个命名实体。
 */
internal object ReadScript {

    /** 清单上限：再多 AI 也读不完，只徒增上下文 */
    private const val OPS_CAP = 30

    val READ = """
(function(){
  try {
    // ===== 规则表：与 core/text/HtmlToMarkdown.kt 同源（插值生成，改一处即两处生效）=====
    var DROP = ${HtmlToMarkdown.jsSet(HtmlToMarkdown.DROP_RULE)};
    var BLOCK = ${HtmlToMarkdown.jsSet(HtmlToMarkdown.BLOCK_RULE)};
    var VOID = ${HtmlToMarkdown.jsSet(HtmlToMarkdown.VOID_RULE)};
    var HIDDEN_CLASS = ${HtmlToMarkdown.jsSet(HtmlToMarkdown.HIDDEN_CLASS_RULE)};
    var ESCAPE = ${HtmlToMarkdown.jsArr(HtmlToMarkdown.ESCAPE_RULE)};
    var FENCE = ${HtmlToMarkdown.jsStr(HtmlToMarkdown.FENCE)};
    var MARK = {};
    var MP = ${HtmlToMarkdown.jsMarkPairs(HtmlToMarkdown.MARK_RULE)};
    for (var mi = 0; mi < MP.length; mi++) { MARK[MP[mi][0]] = MP[mi][1]; }

    // ===== 共享 helper：与 InteractScripts 同源（清单显示的文字 = 按文字能找到的元素）=====
    var LIST_SELECTOR = ${HtmlToMarkdown.jsStr(BrowserJs.LIST_SELECTOR)};
${BrowserJs.HELPERS}

    var HARD = 8000, NODE_CAP = 15000, DEPTH_CAP = 120, MS_CAP = 1500;
    var NODES = 0, T0 = Date.now();
    var out = [], listBuf = [], listDepth = 0, cur = [], marks = [];
    var lists = [], items = [];
    var preBuf = null, preLang = '';
    var rows = null, row = null, cellOpen = false, cellExtras = [];
    var quoteDepth = 0, truncated = false, pendSpace = false;

    // ===== 基础工具 =====
    function rep(s, n){ var r = ''; for (var i = 0; i < n; i++) r += s; return r; }
    function isCjk(c){ var x = c.charCodeAt(0); return (x >= 0x2E80 && x <= 0x9FFF) || (x >= 0x3000 && x <= 0x303F) || (x >= 0xFF00 && x <= 0xFFEF); }
    function needSpace(a, b){ return !(isCjk(a) && isCjk(b)); }
    function outLen(){ return out.join('').length + listBuf.join('').length; }
    function overCap(){
      if (truncated) return true;
      if (NODES > NODE_CAP) return true;
      if ((NODES & 63) === 0) {
        if (outLen() > HARD) { truncated = true; return true; }
        if ((Date.now() - T0) > MS_CAP) { truncated = true; return true; }
      }
      return false;
    }
    function curText(){ return cur.join(''); }
    function resetInline(){ cur.length = 0; marks.length = 0; pendSpace = false; }

    // 待定空格：文本以空白结尾时不能立刻加空格（后面可能还有空白），但遇到行内标记 / 图片 /
    // 换行这类非文本片段就说明空白确实该保留。少了它 `你好<strong>加粗</strong>` 会输出
    // `你好**加粗**`，相邻两字被粘成一个词（与 Kotlin 侧逐条一致）
    function flushPend(){
      if (!pendSpace) return;
      pendSpace = false;
      if (cur.length && cur[cur.length - 1] !== '\n') cur.push(' ');
    }

    // ===== 行内：空白折叠 + CJK 不插空格 + 转义（与 Kotlin 侧逐条一致）=====
    function pushChar(ch, s, i){
      if (ESCAPE.indexOf(ch) >= 0) { cur.push('\\' + ch); return; }
      var ls = (cur.length === 0) || cur[cur.length - 1] === '\n';
      if (ls) {
        if ('#-+>~'.indexOf(ch) >= 0) { cur.push('\\' + ch); return; }
        if (ch >= '0' && ch <= '9' && /^\d+\.(\s|${'$'})/.test(s.slice(i))) { cur.push('\\' + ch); return; }
      }
      cur.push(ch);
    }
    function pushText(s){
      for (var i = 0; i < s.length; i++){
        var ch = s.charAt(i);
        if (/\s/.test(ch) || ch === '\u00a0' || ch === '\u200b') { pendSpace = true; continue; }
        if (pendSpace) {
          if (cur.length && cur[cur.length - 1] !== '\n' && needSpace(cur[cur.length - 1], ch)) cur.push(' ');
          pendSpace = false;
        }
        pushChar(ch, s, i);
      }
    }
    function markOpen(o, c){ flushPend(); marks.push({o:o, c:c, a:cur.length}); cur.push(o); }
    function markClose(){
      if (!marks.length) return;
      var m = marks.pop();
      if (cur.length > m.a + m.o.length) cur.push(m.c); else cur.length = m.a;
    }
    function rawInline(s){ flushPend(); cur.push(s); }
    function brk(){
      flushPend();
      if (preBuf) { preBuf.push('\n'); return; }
      if (listDepth > 0) { if (cur.length && cur[cur.length - 1] !== ' ') cur.push(' '); return; }
      while (cur.length && cur[cur.length - 1] === ' ') cur.pop();
      if (cur.length) cur.push('\n');
    }

    // ===== 块：列表缓冲 + 引用前缀 + 块边界截断语义 =====
    function emit(block){
      var b = String(block).replace(/\s+${'$'}/, '');
      if (!b.replace(/\s/g, '')) return;
      if (listDepth > 0) {
        if (listBuf.length) listBuf.push('\n');
        listBuf.push(b);
      } else {
        if (out.length) out.push('\n\n');
        out.push(b);
      }
      if (outLen() > HARD) truncated = true;
    }
    function flushList(){
      if (!listBuf.length) return;
      var b = listBuf.join('');
      listBuf.length = 0;
      if (out.length) out.push('\n\n');
      out.push(b);
    }
    // 单元格内的"落块"（`<td><p>x</p></td>` 的 p）不能直接 emit，否则文字会被甩到表格外面
    function emitBlock(block){
      if (!cellExtras.length) { emit(block); return; }
      cellExtras[cellExtras.length - 1].push(String(block));
    }
    function quotePrefix(){ return rep('> ', quoteDepth); }
    function itemPrefix(it){
      var L = it.list;
      if (!L) return quotePrefix();
      var pad = rep('  ', Math.min(L.indent, 5));
      if (L.ordered) { L.n++; return pad + L.n + '. '; }
      return pad + '- ';
    }
    function flushInline(prefix){
      var t = curText().replace(/^\s+/, '').replace(/\s+${'$'}/, '');
      resetInline();
      if (!t) return;
      // 单元格内不加列表/引用前缀：那些前缀属于正文，不属于单元格
      if (cellExtras.length) { emitBlock(t); return; }
      emit((prefix === undefined || prefix === null ? prefixOf() : prefix) + t);
    }
    function prefixOf(){
      var it = items.length ? items[items.length - 1] : null;
      if (it && !it.used) { it.used = true; return itemPrefix(it); }
      return quotePrefix();
    }
    function closeItem(){
      var it = items.length ? items[items.length - 1] : null;
      if (!it) { flushInline(); return; }
      if (!it.used) { it.used = true; flushInline(itemPrefix(it)); } else { flushInline(); }
      items.pop();
    }

    // ===== 表格：GFM 管道表（忽略 colspan/rowspan，列数以首行单元格数为准）=====
    function cellText(s){ return String(s || '').replace(/\s+/g, ' ').replace(/\|/g, '\\|').slice(0, 120); }
    function openCell(){ if (cellOpen) closeCell(); cellOpen = true; cellExtras.push([]); resetInline(); }
    function closeCell(){
      if (!cellOpen) return;
      cellOpen = false;
      var extra = cellExtras.length ? cellExtras.pop().join(' ') : '';
      var t = curText().replace(/^\s+/, '').replace(/\s+${'$'}/, '');
      resetInline();
      if (row) row.push(extra ? (t ? extra + ' ' + t : extra) : t);
    }
    function closeRow(){
      if (cellOpen) closeCell();
      // 全空行没有信息量，不进表格
      if (row && row.length && rows && row.some(function(c){ return String(c).replace(/\s/g, ''); })) rows.push(row);
      row = null;
    }
    function closeTable(){
      closeRow();
      var rs = rows;
      rows = null;
      if (!rs || !rs.length) return;
      var cols = Math.max(1, Math.min(rs[0].length, 12));
      var buf = ['|'];
      for (var c = 0; c < cols; c++){ var h = cellText(rs[0][c]); if (!h) h = '列' + (c + 1); buf.push(' ' + h + ' |'); }
      buf.push('\n|');
      for (var s = 0; s < cols; s++) buf.push(' --- |');
      var lim = Math.min(rs.length, 30);
      for (var r = 1; r < lim; r++){
        buf.push('\n|');
        for (var c2 = 0; c2 < cols; c2++) buf.push(' ' + cellText(rs[r][c2]) + ' |');
      }
      if (rs.length > 30) {
        buf.push('\n| ...（表格过长，仅保留前 30 行） |');
        for (var c3 = 1; c3 < cols; c3++) buf.push('  |');
      }
      emitBlock(buf.join(''));
    }

    // ===== 代码块：pre 原样收集（textContent 而非 innerText，避免行号装饰）=====
    function closePre(){
      var buf = preBuf;
      var lang = preLang;
      preBuf = null;
      preLang = '';
      if (!buf) return;
      var body = buf.join('').replace(/\r\n?/g, '\n').replace(/^\n+/, '').replace(/\n+${'$'}/, '');
      if (!body.replace(/\s/g, '')) return;
      var fence = FENCE;
      while (body.indexOf(fence) >= 0) fence += '`';
      emitBlock(fence + lang + '\n' + body + '\n' + fence);
    }

    // ===== 链接绝对化：绝对/协议相对/根相对/相对；丢 javascript: 与 data: =====
    function abs(u){
      if (!u) return null;
      u = String(u).trim();
      if (!u || u.charAt(0) === '#') return null;
      var l = u.toLowerCase();
      if (l.indexOf('javascript:') === 0 || l.indexOf('data:') === 0 || l.indexOf('about:') === 0) return null;
      if (l.indexOf('http://') === 0 || l.indexOf('https://') === 0 || l.indexOf('mailto:') === 0 || l.indexOf('tel:') === 0) return u;
      try { return new URL(u, location.href).href; } catch (e) { return u; }
    }

    // ===== 隐藏判定：属性 / class token / 计算样式 =====
    function hiddenEl(el){
      if (el.getAttribute('hidden') !== null) return true;
      if (el.getAttribute('aria-hidden') === 'true') return true;
      var ty = el.getAttribute('type');
      if (ty && String(ty).toLowerCase() === 'hidden') return true;
      var st = String(el.getAttribute('style') || '').replace(/\s/g, '').toLowerCase();
      if (st.indexOf('display:none') >= 0 || st.indexOf('visibility:hidden') >= 0 || st.indexOf('opacity:0') >= 0) return true;
      var cls = String(el.getAttribute('class') || '').toLowerCase().split(/\s+/);
      for (var i = 0; i < cls.length; i++){ if (cls[i] && HIDDEN_CLASS[cls[i]]) return true; }
      try {
        var cs = window.getComputedStyle(el);
        if (cs && (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0')) return true;
      } catch (e) {}
      return false;
    }

    function imgMd(el){
      var u = abs(el.getAttribute('src'));
      if (!u) return;
      var alt = String(el.getAttribute('alt') || '').replace(/\[/g, '\\[').replace(/\]/g, '\\]');
      rawInline('![' + alt + '](' + u + ')');
    }

    function walk(el, depth){
      if (overCap() || depth > DEPTH_CAP) return;
      NODES++;
      var tag = String(el.nodeName || '').toLowerCase();
      if (DROP[tag] || hiddenEl(el)) return;
      var headLv = 0;
      if (/^h[1-6]${'$'}/.test(tag)) headLv = parseInt(tag.charAt(1), 10);
      var isList = (tag === 'ul' || tag === 'ol');
      if (BLOCK[tag]) flushInline();

      if (isList) {
        lists.push({indent: lists.length, ordered: tag === 'ol', n: (parseInt(el.getAttribute('start'), 10) || 1) - 1});
        listDepth++;
      } else if (tag === 'li') {
        closeItem();
        items.push({list: lists.length ? lists[lists.length - 1] : null, used: false});
      } else if (tag === 'blockquote') {
        quoteDepth++;
      } else if (tag === 'pre') {
        preBuf = []; preLang = '';
      } else if (tag === 'table') {
        closeRow(); rows = [];
      } else if (tag === 'caption') {
        resetInline();
      } else if (tag === 'tr') {
        closeRow(); row = [];
      } else if (tag === 'td' || tag === 'th') {
        openCell();
      } else if (tag === 'a') {
        var u = abs(el.getAttribute('href'));
        if (u) markOpen('[', '](' + u + ')'); else markOpen('', '');
      } else if (MARK[tag]) {
        markOpen(MARK[tag], MARK[tag]);
      } else if (tag === 'img') {
        imgMd(el);
      } else if (tag === 'br') {
        brk();
      } else if (tag === 'hr') {
        emitBlock('---');
      }

      var kids = el.childNodes;
      for (var i = 0; i < kids.length; i++){
        if (overCap()) break;
        var nd = kids[i];
        if (nd.nodeType === 3) {
          var v = nd.nodeValue || '';
          if (preBuf) preBuf.push(v); else pushText(v);
        } else if (nd.nodeType === 1) {
          walk(nd, depth + 1);
        }
      }

      if (headLv) flushInline(rep('#', headLv) + ' ');
      else if (tag === 'li') closeItem();
      else if (isList) { flushInline(); lists.pop(); listDepth--; if (listDepth <= 0) { listDepth = 0; flushList(); } }
      else if (tag === 'blockquote') { flushInline(); quoteDepth--; }
      else if (tag === 'pre') closePre();
      else if (tag === 'td' || tag === 'th') closeCell();
      else if (tag === 'tr') closeRow();
      else if (tag === 'table') closeTable();
      else if (tag === 'caption') resetInline();
      else if (tag === 'a' || MARK[tag]) markClose();
      else if (BLOCK[tag]) flushInline();
    }

    // ===== 可操作元素清单：AI 靠它决定 browse_click 的 target 与 browse_input 的目标 =====
    // 采集用的 labelOf/vis/kindOf/stateOf 与 InteractScripts 的查找同源，
    // 因此"这里列出来的文字"拿去 browse_click 一定找得到那个元素。
    var body = document.body || document.documentElement;
    if (!body) return {ok:false, error:'页面尚无内容'};
    walk(body, 0);
    flushInline();
    if (listDepth > 0) { listDepth = 0; flushList(); }
    var markdown = out.join('');
    if (markdown.length > HARD) { markdown = markdown.slice(0, HARD); truncated = true; }

    var ops = [];
    var els = document.querySelectorAll(LIST_SELECTOR);
    for (var j = 0; j < els.length && ops.length < ${OPS_CAP}; j++){
      var el2 = els[j];
      if (!vis(el2)) continue;
      if (el2.disabled && String(el2.tagName).toLowerCase() === 'button') continue;
      var nm = labelOf(el2).slice(0, 60);
      if (!nm) continue;
      var item = {label: nm, kind: kindOf(el2)};
      var stt = stateOf(el2);
      if (stt) item.state = stt;
      var ph = String(el2.getAttribute('placeholder') || '').trim();
      if (ph) item.hint = ph.slice(0, 40);
      ops.push(item);
    }
    var st = window.scrollY || document.documentElement.scrollTop || 0;
    var sh = Math.max(document.documentElement.scrollHeight || 0, body.scrollHeight || 0);
    return {
      ok: true,
      title: String(document.title || ''),
      url: String(location.href),
      ready: String(document.readyState),
      can_back: !!(window.history && window.history.length > 1),
      scroll: {y: Math.round(st), height: Math.round(sh)},
      markdown: markdown,
      truncated: truncated,
      ops: ops
    };
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
}