package com.phoneagent.feature.browser.script

import com.phoneagent.core.text.HtmlToMarkdown
import com.phoneagent.feature.browser.BrowserGuard
import kotlinx.serialization.json.JsonPrimitive

/**
 * 网页交互脚本：点击元素 / 填写表单。
 *
 * 与 [ReadScript] 的"可操作元素清单"**同源**：清单里的文字由 [BrowserJs.HELPERS] 的 `labelOf` 产出，
 * 这里的查找也用同一个 `labelOf`，所以"清单里列出来的文字"拿去 [click] 一定找得到那个元素。
 * 以前两侧各写一份取字逻辑，导致"只有 aria-label 的图标按钮"清单里看得见、点下去却找不到。
 *
 * 三处比旧实现更贴近真实网页：
 * 1. **候选扩面**：先按 [BrowserJs.LIST_SELECTOR]（真控件）找，再退到页面文字那层宽网；
 * 2. **下拉**：WebView 里 `select.click()` 不会弹出原生下拉，改为按选项文字直接设 `selectedIndex`
 *    并派发 `input`/`change`（见 [BrowserJs.HELPERS] 的 `pickOption`）；
 * 3. **只读探针**：只读模式下 [click] 传 `guard=true` 时，脚本对"真正被定位到的元素"再探一次
 *    [BrowserGuard] 词表，命中即**拒绝且不派发点击**（`by=id` 时 AI 给的文字未必等于元素真实文字，
 *    这是执行前的最后一道兜底）。
 */
internal object InteractScripts {

    /**
     * 点击网页元素。
     * @param by `id`=CSS 选择器，其余（text/hint）=元素文字（精确优先，其次最短的包含匹配）
     * @param guard 是否启用不可逆词表探针（只读模式传 true）
     */
    fun click(by: String, value: String, guard: Boolean): String {
        val want = JsonPrimitive(value).toString()
        val css = if (by == "id") "true" else "false"
        val onGuard = if (guard) "true" else "false"
        return """
(function(){
  try {
    var WANT = $want;
    var CSS = $css;
    var GUARD = $onGuard;
    var GUARD_WORDS = ${BrowserGuard.jsWords()};
    var CLICK_SELECTOR = ${HtmlToMarkdown.jsStr(BrowserJs.CLICK_SELECTOR)};
${BrowserJs.HELPERS}

    // 只读探针：把元素身上所有能"说明它是什么"的文字凑成一串，命中词表即拒
    function guardHit(el, extra){
      if(!GUARD) return '';
      var probe = labelOf(el) + ' ' + String(el.value || '') + ' ' + String(el.id || '') + ' ' +
        String(el.getAttribute('name') || '') + ' ' + String(el.getAttribute('title') || '') + ' ' +
        String(el.getAttribute('aria-label') || '') + (extra ? ' ' + extra : '');
      var low = probe.toLowerCase();
      for (var i = 0; i < GUARD_WORDS.length; i++){
        if(low.indexOf(String(GUARD_WORDS[i]).toLowerCase()) >= 0) return GUARD_WORDS[i];
      }
      return '';
    }
    function refuse(name, w){
      return {ok:false, refused:true, error:'只读模式下「' + name + '」命中不可逆词表（' + w + '），已拒绝点击（没有真正点下去）'};
    }

    var el = null;
    if(CSS){
      try { el = document.querySelector(WANT); }
      catch (e) { return {ok:false, error:'CSS 选择器无效：' + WANT}; }
    } else {
      var cands = document.querySelectorAll(CLICK_SELECTOR);
      var exact = null, partial = null, partLen = 1e9;
      for (var i = 0; i < cands.length; i++){
        var c = cands[i];
        if(!vis(c)) continue;
        var t = labelOf(c);
        if(!t) continue;
        if(t === WANT){ exact = c; break; }
        // 包含匹配取"文字最短"的那个：页面上 div/body 的文字往往长得多，取最短才不会点到整块容器
        if(t.indexOf(WANT) >= 0 && t.length <= WANT.length + 30 && t.length < partLen){ partial = c; partLen = t.length; }
      }
      el = exact || partial;
    }

    if(!el){
      // 兜底：目标可能是下拉里的某个选项（option 不在候选选择器里，且点 select 不会展开下拉）
      var sels = document.querySelectorAll('select');
      for (var si = 0; si < sels.length; si++){
        var sel = sels[si];
        if(!vis(sel)) continue;
        var w1 = guardHit(sel, WANT);
        if(w1) return refuse(WANT, w1);
        var hit = pickOption(sel, WANT);
        if(hit) return {ok:true, clicked:labelOf(sel).slice(0,60) + ' → ' + hit, tag:'SELECT', url_after:String(location.href)};
      }
      return {ok:false, error:'页面上没找到「' + WANT + '」这个可点元素，请先 browse_read 看当前页正文里的链接文字与可操作元素清单'};
    }
    if(!vis(el)) { try { el.scrollIntoView({block:'center'}); } catch (e) {} }

    var label = labelOf(el).slice(0,60) || String(el.tagName || '');
    var w = guardHit(el, WANT);
    if(w) return refuse(label, w);

    var tag = String(el.tagName || '').toLowerCase();
    if(tag === 'select'){
      var cur = (el.options && el.selectedIndex >= 0) ? String(el.options[el.selectedIndex].text || '').trim() : '';
      return {ok:false, error:'「' + label + '」是下拉框（当前：' + (cur || '未选') + '），点它不会展开选项；' +
        '请改用 browse_input：target={"by":"text","value":"' + label + '"}，text 填要选的选项文字'};
    }
    var a = el.closest ? el.closest('a[href]') : null;
    if(a && a.getAttribute('target') === '_blank'){ location.href = a.href; }
    else { fire(el); }
    return {ok:true, clicked: label, tag: String(el.tagName || ''), url_after: String(location.href)};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
    }

    /**
     * 填写/选择表单控件。按 `label/placeholder/aria-label/title/value/id` 的文字或 CSS 选择器定位。
     * 目标是下拉框时按选项文字直接选中（同 [BrowserJs.HELPERS] 的 `pickOption`）。
     */
    fun input(by: String, value: String, text: String): String {
        val want = JsonPrimitive(value).toString()
        val fill = JsonPrimitive(text).toString()
        val css = if (by == "id") "true" else "false"
        return """
(function(){
  try {
    var WANT = $want;
    var FILL = $fill;
    var CSS = $css;
${BrowserJs.HELPERS}

    var el = null;
    if(CSS){
      try { el = document.querySelector(WANT); }
      catch (e) { return {ok:false, error:'CSS 选择器无效：' + WANT}; }
    } else {
      var cands = document.querySelectorAll('input,textarea,select');
      var exact = null, partial = null, partLen = 1e9;
      for (var i = 0; i < cands.length; i++){
        var c = cands[i];
        if(!vis(c)) continue;
        var t = labelOf(c);
        if(!t) continue;
        if(t === WANT){ exact = c; break; }
        if(t.indexOf(WANT) >= 0 && t.length <= WANT.length + 30 && t.length < partLen){ partial = c; partLen = t.length; }
      }
      el = exact || partial;
    }
    if(!el) return {ok:false, error:'页面上没找到「' + WANT + '」这个输入框，请先 browse_read 看当前页的可操作元素清单'};
    if(!vis(el)) { try { el.scrollIntoView({block:'center'}); } catch (e) {} }

    var label = labelOf(el).slice(0,60) || WANT;
    if(String(el.tagName || '').toLowerCase() === 'select'){
      var hit = pickOption(el, FILL);
      if(!hit) return {ok:false, error:'下拉框「' + label + '」里没有「' + FILL + '」这个选项，请先 browse_read 看它的选项清单'};
      return {ok:true, filled: hit, into: label};
    }
    setValue(el, FILL);
    return {ok:true, filled: FILL.slice(0,60), into: label};
  } catch (e) { return {ok:false, error:String(e)}; }
})()
""".trimIndent()
    }
}