package com.phoneagent.feature.browser.script

/**
 * 注入脚本的公共片段。
 *
 * 为什么要有这一层：[ReadScript] 的"可操作元素清单"与 [InteractScripts] 的"按文字找元素"
 * 必须**同源**——清单里列出的文字，AI 拿去点就必须点得到。以前两侧各写一份 `txt()`，
 * 清单用 `innerText || value || aria-label`、点击只用 `innerText`，于是"只有 aria-label 的
 * 图标按钮"清单里看得见、点下去却找不到。现在两侧共用这里的 [HELPERS] 与选择器常量。
 */
internal object BrowserJs {

    /**
     * 清单采集选择器：真正"可操作"的控件（清单只列这些，避免把正文段落也列进去）。
     */
    const val LIST_SELECTOR =
        "a[href],button,[role=button],[role=tab],[role=switch],[role=menuitem]," +
            "input,textarea,select,summary"

    /**
     * 点击候选选择器：先按 [LIST_SELECTOR] 精确找，再退到"页面文字"这层宽网。
     * 宽网是必要的——很多网页把"下一页"做成 span/div/td，不在控件选择器里，
     * 但它就是 AI 在正文里看到的可点文字。
     */
    const val CLICK_SELECTOR =
        LIST_SELECTOR + ",a,button,[role=button],input[type=submit],input[type=button]," +
            "[onclick],li,span,div,p,td,label,h1,h2,h3"

    /**
     * 共享函数：可见判定 / 文字取用 / 点击派发 / 设值 / 选中下拉项。
     * 由 [ReadScript] 与 [InteractScripts] 插值进各自脚本，两侧行为必然一致。
     */
    val HELPERS = """
function vis(el){
  if(!el) return false;
  var r = el.getBoundingClientRect();
  if(r.width <= 0 || r.height <= 0) return false;
  var s = window.getComputedStyle(el);
  return !(s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0');
}
function rawText(el){ return String((el.innerText || el.textContent || '')).replace(/\s+/g,' ').trim(); }
// 元素的可读名字：关联 label → 自身文字 → aria-label → title → placeholder → value → id。
// 顺序固定，"清单怎么显示"与"按文字怎么找"用的是同一份结果。
// placeholder 必须在 value 之前：输入框填过字后 value 是用户内容，拿它当名字会越用越歪。
function labelOf(el){
  if(!el) return '';
  try {
    if(el.labels && el.labels.length){ var lt = rawText(el.labels[0]); if(lt) return lt; }
  } catch(e){}
  var t = rawText(el);
  if(t) return t;
  t = String(el.getAttribute('aria-label') || '').trim();
  if(t) return t;
  t = String(el.getAttribute('title') || '').trim();
  if(t) return t;
  t = String(el.getAttribute('placeholder') || '').trim();
  if(t) return t;
  t = String(el.value || '').trim();
  if(t) return t;
  return String(el.id || '');
}
function fire(el){
  try {
    el.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
    el.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
  } catch (e) {}
  el.click();
}
// 用原生 setter 写 value 再派发 input/change：React/Vue 这类受控组件只认这条路，
// 直接 el.value = x 会被框架的 diff 覆盖回原值，表现为"填了但没生效"
function setValue(el, val){
  el.focus();
  var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  var d = Object.getOwnPropertyDescriptor(proto, 'value');
  if(d && d.set){ d.set.call(el, val); } else { el.value = val; }
  el.dispatchEvent(new Event('input', {bubbles:true}));
  el.dispatchEvent(new Event('change', {bubbles:true}));
}
// 下拉选项：WebView 里 select.click() 不会弹出原生下拉，只能按选项文字直接选中
function pickOption(sel, want){
  var os = sel.options || [];
  for (var i = 0; i < os.length; i++){
    var t = String(os[i].text || '').replace(/\s+/g,' ').trim();
    if(!t) continue;
    if(t === want || t.indexOf(want) >= 0){
      sel.selectedIndex = i;
      sel.dispatchEvent(new Event('input', {bubbles:true}));
      sel.dispatchEvent(new Event('change', {bubbles:true}));
      return t;
    }
  }
  return '';
}
function kindOf(el){
  var tag = String(el.tagName || '').toLowerCase();
  var ty = String(el.type || '').toLowerCase();
  if(tag === 'a') return '链接';
  if(tag === 'button' || ty === 'submit' || ty === 'button' || el.getAttribute('role') === 'button') return '按钮';
  if(tag === 'select') return '下拉';
  if(tag === 'textarea') return '输入框';
  if(tag === 'input'){
    if(ty === 'checkbox') return '复选框';
    if(ty === 'radio') return '单选';
    return '输入框';
  }
  if(tag === 'summary') return '折叠';
  var role = String(el.getAttribute('role') || '').toLowerCase();
  if(role === 'tab') return '标签页';
  if(role === 'switch') return '开关';
  if(role === 'menuitem') return '菜单项';
  return tag;
}
// 状态：勾选/选中/禁用 —— AI 靠它判断"这一步到底要不要点"（已勾上的复选框再点会取消）
function stateOf(el){
  var tag = String(el.tagName || '').toLowerCase();
  var ty = String(el.type || '').toLowerCase();
  var parts = [];
  if(tag === 'input' && (ty === 'checkbox' || ty === 'radio')) parts.push(el.checked ? '已选' : '未选');
  if(tag === 'select'){
    var cur = el.options && el.selectedIndex >= 0 ? String(el.options[el.selectedIndex].text || '').trim() : '';
    if(cur) parts.push('当前=' + cur);
    var os = el.options || [];
    var names = [];
    for (var i = 0; i < os.length && names.length < 8; i++){
      var t = String(os[i].text || '').replace(/\s+/g,' ').trim();
      if(t) names.push(t);
    }
    if(names.length) parts.push('选项=' + names.join(' | '));
  }
  if(el.disabled) parts.push('禁用');
  return parts.join('，');
}
""".trimIndent()
}