package com.phoneagent.feature.browser

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

    /** 抓取当前网页内容：标题/网址/正文/标题层级/可点链接/输入框/按钮 */
    val READ = """
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
    var body = document.body || document.documentElement;
    if(!body) return {ok:false, error:'页面尚无内容'};
    var main = document.querySelector('main') || document.querySelector('article') || body;
    var text = txt(main);
    if(!text) text = txt(body);
    if(text.length > 3000) text = text.slice(0,3000);
    var links = [];
    var as = document.querySelectorAll('a[href]');
    for(var i = 0; i < as.length && links.length < 30; i++){
      var a = as[i];
      if(!vis(a)) continue;
      var t = txt(a) || a.getAttribute('title') || '';
      if(!t) continue;
      var h = String(a.href || '');
      if(h.indexOf('javascript:') === 0) continue;
      links.push({text: t.slice(0,60), href: h.slice(0,300)});
    }
    var inputs = [];
    var is = document.querySelectorAll('input,textarea,select');
    for(var j = 0; j < is.length && inputs.length < 20; j++){
      var el = is[j];
      if(!vis(el)) continue;
      var hint = el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.getAttribute('name') || el.id || '';
      inputs.push({hint: String(hint).slice(0,40), type: String(el.type || el.tagName).toLowerCase(), id: String(el.id || '')});
    }
    var buttons = [];
    var bs = document.querySelectorAll('button,[role=button],input[type=submit],input[type=button]');
    for(var k = 0; k < bs.length && buttons.length < 20; k++){
      var b = bs[k];
      if(!vis(b)) continue;
      var bt = txt(b) || String(b.value || '') || b.getAttribute('aria-label') || '';
      if(!bt) continue;
      buttons.push(String(bt).slice(0,40));
    }
    var heads = [];
    var hs = document.querySelectorAll('h1,h2,h3');
    for(var m = 0; m < hs.length && heads.length < 10; m++){
      var ht = txt(hs[m]);
      if(ht) heads.push(ht.slice(0,60));
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
      text: text,
      headings: heads,
      links: links,
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
    if(!el) return {ok:false, error:'页面上没找到「' + want + '」这个可点元素，请先 browse_read 看当前页有哪些链接/按钮'};
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