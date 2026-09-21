package com.phoneagent.feature.browser

import com.phoneagent.core.text.HtmlToMarkdown
import kotlinx.serialization.json.JsonPrimitive

/**
 * 内置浏览器注入脚本集合。
 *
 * 为什么用 DOM 脚本而不是让 AI 用 tap 猜控件：网页元素（下拉、弹层、登录框）在无障碍树里
 * 往往拿不到可靠的 id/文字，坐标更是一换分辨率就错；而网页自己有 DOM，读正文、按文字点元素、
 * 往输入框填字都能精确完成。所以网页内的读写统一走这里的脚本，AI 只表达"点哪个字/填什么"。
 *
 * 约定：每段脚本都是一个立即执行函数，**返回对象**（不是字符串）。WebView 的
 * `evaluateJavascript` 会把返回的对象序列化成 JSON 交给 Kotlin 侧，因此 [BrowserBridge]
 * 只需解析一次；返回对象统一带 `ok` 字段，失败时给中文 `error`。
 *
 * **[READ] 的正文是 Markdown，规则表与 `core/text/HtmlToMarkdown.kt` 同源**：
 * 丢弃表 / 块级表 / 自闭合表 / 行内标记表 / 隐藏类名表 / 转义字符表 / 围栏字面量
 * 全部由 [HtmlToMarkdown] 的常量插值生成，改规则只会改一处，两侧不可能漂移。
 * 与 Kotlin 侧**唯一允许的两处差异**（其余逐条一致）：
 * 1. 这里能用 `getComputedStyle` 看到被样式表藏起来的元素，Kotlin 只能看属性；
 * 2. 这里用浏览器原生能力解码实体（是所有命名实体的超集），Kotlin 只认 32 个命名实体。
 */
internal object BrowserScripts {

    /** 页面加载完成后跑一次：把 target=_blank 的链接改成同窗打开，否则点击会静默无反应 */
    val UNBLANK = """
(function(){
  try {
    var as = document.querySelectorAll('a[target]');
    for (var i = 0; i < as.length; i++) { as[i].removeAttribute('target'); }
    return {ok:true};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()

    /**
     * 抓取当前网页：标题/网址/滚动 + **Markdown 正文** + 输入框 + 按钮。
     *
     * 正文为什么给 Markdown：AI 极熟 Markdown（write_doc 产出的就是它），标题层级、列表、
     * 表格、代码块、内联链接都能带上；压平成一坨纯文本时这些都丢了，AI 只能瞎猜页面结构。
     * 链接**内联**在正文里（`[文字](网址)`），因此不再单独给链接清单——AI 要点击时直接取链接文字。
     *
     * 四重上限防低端机卡死：节点 15000 / 深度 120 / 输出 8000 / 单次 1500ms。
     * 这里的 8000 只是**安全上限**，给 AI 的 4000 预算截断统一由 Kotlin 的
     * [HtmlToMarkdown.takeBlocks] 执行，保证与 fetch 链路行为一致。
     */
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

    var HARD = 8000, NODE_CAP = 15000, DEPTH_CAP = 120, MS_CAP = 1500;
    var NODES = 0, T0 = Date.now();
    var out = [], listBuf = [], listDepth = 0, cur = [], marks = [];
    var lists = [], items = [];
    var preBuf = null, preLang = '';
    var rows = null, row = null, cellOpen = false, cellExtras = [];
    var quoteDepth = 0, truncated = false;

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
    function resetInline(){ cur.length = 0; marks.length = 0; }

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
      var pend = false;
      for (var i = 0; i < s.length; i++){
        var ch = s.charAt(i);
        if (/\s/.test(ch) || ch === '\u00a0' || ch === '\u200b') { pend = true; continue; }
        if (pend) {
          if (cur.length && cur[cur.length - 1] !== '\n' && needSpace(cur[cur.length - 1], ch)) cur.push(' ');
          pend = false;
        }
        pushChar(ch, s, i);
      }
    }
    function markOpen(o, c){ marks.push({o:o, c:c, a:cur.length}); cur.push(o); }
    function markClose(){
      if (!marks.length) return;
      var m = marks.pop();
      if (cur.length > m.a + m.o.length) cur.push(m.c); else cur.length = m.a;
    }
    function brk(){
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
      cur.push('![' + alt + '](' + u + ')');
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

    // ===== 可操作元素清单（AI 靠它决定 browse_input 的 target 与 browse_click 的文字）=====
    function vis(el){
      if (!el) return false;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return false;
      var s = window.getComputedStyle(el);
      return !(s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0');
    }
    function txt(el){ return String((el.innerText || el.textContent || '')).replace(/\s+/g, ' ').trim(); }

    var body = document.body || document.documentElement;
    if (!body) return {ok:false, error:'页面尚无内容'};
    walk(body, 0);
    flushInline();
    if (listDepth > 0) { listDepth = 0; flushList(); }
    var markdown = out.join('');
    if (markdown.length > HARD) { markdown = markdown.slice(0, HARD); truncated = true; }

    var inputs = [];
    var is = document.querySelectorAll('input,textarea,select');
    for (var j = 0; j < is.length && inputs.length < 20; j++){
      var el2 = is[j];
      if (!vis(el2)) continue;
      var hint = el2.getAttribute('placeholder') || el2.getAttribute('aria-label') || el2.getAttribute('name') || el2.id || '';
      inputs.push({hint: String(hint).slice(0,40), type: String(el2.type || el2.tagName).toLowerCase(), id: String(el2.id || '')});
    }
    var buttons = [];
    var bs = document.querySelectorAll('button,[role=button],input[type=submit],input[type=button]');
    for (var k = 0; k < bs.length && buttons.length < 20; k++){
      var b = bs[k];
      if (!vis(b)) continue;
      var bt = txt(b) || String(b.value || '') || b.getAttribute('aria-label') || '';
      if (!bt) continue;
      buttons.push(String(bt).slice(0,40));
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
      inputs: inputs,
      buttons: buttons
    };
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()

    /**
     * 点击网页元素。
     * @param by text=元素文字（精确优先，其次包含）；id=CSS 选择器；hint 与 text 同义
     */
    fun click(by: String, value: String): String {
        val want = JsonPrimitive(value).toString()
        val css = if (by == "id") "true" else "false"
        return """
(function(){
  try {
    function vis(el){
      if(!el) return false;
      var r = el.getBoundingClientRect();
      if(r.width <= 0 || r.height <= 0) return false;
      var s = window.getComputedStyle(el);
      return !(s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0');
    }
    function txt(el){ return String((el.innerText || el.textContent || '')).replace(/\s+/g,' ').trim(); }
    function fire(el){
      try {
        el.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
        el.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
      } catch (e) {}
      el.click();
    }
    var want = $want;
    var el = null;
    if($css){
      try { el = document.querySelector(want); }
      catch (e) { return {ok:false, error:'CSS 选择器无效：' + want}; }
    } else {
      var cands = document.querySelectorAll('a,button,[role=button],input[type=submit],input[type=button],[onclick],li,span,div,p,td,label,h1,h2,h3');
      var exact = null, partial = null;
      for (var i = 0; i < cands.length; i++){
        var c = cands[i];
        if(!vis(c)) continue;
        var t = txt(c);
        if(!t) continue;
        if(t === want){ exact = c; break; }
        if(!partial && t.indexOf(want) >= 0 && t.length <= want.length + 30) partial = c;
      }
      el = exact || partial;
    }
    if(!el) return {ok:false, error:'页面上没找到「' + want + '」这个可点元素，请先 browse_read 看当前页正文里的链接文字、输入框与按钮'};
    if(!vis(el)) { try { el.scrollIntoView({block:'center'}); } catch (e) {} }
    var label = txt(el).slice(0,40) || String(el.value || '') || el.tagName;
    var a = el.closest ? el.closest('a[href]') : null;
    if(a && a.getAttribute('target') === '_blank'){ location.href = a.href; }
    else { fire(el); }
    return {ok:true, clicked: label, tag: String(el.tagName || ''), url_after: String(location.href)};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
    }

    /** 往网页输入框填字：按 placeholder/aria-label/name/id 文字或 CSS 选择器定位 */
    fun input(by: String, value: String, text: String): String {
        val want = JsonPrimitive(value).toString()
        val fill = JsonPrimitive(text).toString()
        val css = if (by == "id") "true" else "false"
        return """
(function(){
  try {
    function vis(el){
      if(!el) return false;
      var r = el.getBoundingClientRect();
      if(r.width <= 0 || r.height <= 0) return false;
      var s = window.getComputedStyle(el);
      return !(s.display === 'none' || s.visibility === 'hidden');
    }
    function setVal(el, val){
      el.focus();
      var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if(d && d.set){ d.set.call(el, val); } else { el.value = val; }
      el.dispatchEvent(new Event('input', {bubbles:true}));
      el.dispatchEvent(new Event('change', {bubbles:true}));
    }
    var want = $want;
    var fill = $fill;
    var el = null;
    if($css){
      try { el = document.querySelector(want); }
      catch (e) { return {ok:false, error:'CSS 选择器无效：' + want}; }
    } else {
      var is = document.querySelectorAll('input,textarea');
      for (var i = 0; i < is.length; i++){
        var c = is[i];
        if(!vis(c)) continue;
        var t = String(c.getAttribute('placeholder') || c.getAttribute('aria-label') || c.getAttribute('name') || c.id || '').trim();
        if(t && (t === want || t.indexOf(want) >= 0)){ el = c; break; }
      }
    }
    if(!el) return {ok:false, error:'页面上没找到「' + want + '」这个输入框，请先 browse_read 看当前页有哪些输入框'};
    if(!vis(el)) { try { el.scrollIntoView({block:'center'}); } catch (e) {} }
    setVal(el, fill);
    return {ok:true, filled: fill.slice(0,60), into: want};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
    }

    /** 网页滚动：up/down/top/bottom */
    fun scroll(direction: String): String {
        val js = when (direction) {
            "up" -> "window.scrollBy(0, -Math.round(window.innerHeight * 0.8));"
            "top" -> "window.scrollTo(0, 0);"
            "bottom" -> "window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));"
            else -> "window.scrollBy(0, Math.round(window.innerHeight * 0.8));"
        }
        return """
(function(){
  try {
    $js
    var y = Math.round(window.scrollY || document.documentElement.scrollTop || 0);
    var h = Math.max(document.documentElement.scrollHeight || 0, (document.body ? document.body.scrollHeight : 0));
    return {ok:true, scroll_y: y, scroll_height: h, at_bottom: (y + window.innerHeight) >= (h - 4)};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
    }

    /** 网页后退 */
    val BACK = """
(function(){
  try {
    if(!(window.history && window.history.length > 1)) return {ok:false, error:'当前网页没有上一页可后退'};
    window.history.back();
    return {ok:true};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
}