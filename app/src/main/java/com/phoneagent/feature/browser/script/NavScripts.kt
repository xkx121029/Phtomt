package com.phoneagent.feature.browser.script

/**
 * 导航类脚本：新窗口钩子 / 滚动 / 网页后退。
 * 三者都不改变页面内容之外的东西，只影响"现在停在哪一页、看到哪一段"。
 */
internal object NavScripts {

    /**
     * 页面加载完成后跑一次：把 target=_blank 的链接与表单改成同窗打开。
     *
     * 不开多窗口（WebView 的 `setSupportMultipleWindows(false)`），别处的 `_blank` 会被
     * 静默吞掉——AI 看起来就是"点了没反应"，然后反复重试同一个动作。表单同理：
     * `<form target="_blank">` 提交也会没反应，故一并去掉 target。
     */
    val UNBLANK = """
(function(){
  try {
    var as = document.querySelectorAll('a[target]');
    for (var i = 0; i < as.length; i++) { as[i].removeAttribute('target'); }
    var fs = document.querySelectorAll('form[target]');
    for (var j = 0; j < fs.length; j++) { fs[j].removeAttribute('target'); }
    return {ok:true};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()

    /** 网页滚动：up/down/top/bottom（up/down 按一屏的 80%，留出上一屏的重叠便于对照） */
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

    /** 网页后退（历史里退一页，不是系统返回，不会退出浏览器） */
    val BACK = """
(function(){
  try {
    if(!(window.history && window.history.length > 1)) return {ok:false, error:'当前网页没有上一页可后退'};
    window.history.back();
    return {ok:true};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()

    /** 网页状态（browse_info）：只回元信息，不搬正文，代价小、可频繁调用 */
    val INFO = """
(function(){
  try {
    var d = document;
    return {ok:true,
      title: d.title || '',
      url: location.href || '',
      ready: d.readyState || '',
      scroll_y: Math.round(window.scrollY || 0),
      scroll_height: Math.max(d.documentElement.scrollHeight || 0, (d.body ? d.body.scrollHeight : 0)),
      links: d.querySelectorAll('a[href]').length,
      controls: d.querySelectorAll('input,textarea,select,button').length};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()

    /**
     * browse_wait 的单轮探测：只查"元素在不在"，轮询节奏由 Kotlin 侧控制（300ms 一轮）。
     * byText=true 时按元素文字包含匹配（限短文本节点，避免命中整页容器）；
     * 否则把 value 当 CSS 选择器。
     */
    fun probe(selector: String, byText: Boolean): String {
        val want = com.phoneagent.core.text.HtmlToMarkdown.jsStr(selector)
        val textMode = if (byText) "true" else "false"
        return """
(function(){
  try {
    var WANT = $want;
    var BY_TEXT = $textMode;
    var el = null;
    if (BY_TEXT) {
      var nodes = document.querySelectorAll('body *');
      for (var i = 0; i < nodes.length; i++) {
        var t = (nodes[i].innerText || '').trim();
        if (t && t.length <= 40 && t.indexOf(WANT) >= 0) { el = nodes[i]; break; }
      }
    } else {
      el = document.querySelector(WANT);
    }
    return {ok:true, found: !!el, text: el ? ((el.innerText || el.value || '').trim().slice(0, 60)) : ''};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
    }
}