package com.example.android_logininternet.portal

/**
 * 注入到认证页的自动填充脚本。
 *
 * 选择器来自真机 outerHTML：
 *  - 账号 input#nameInput
 *  - 密码 input[type=password]（没有 id）
 *  - 协议 input.ant-checkbox-input（默认未勾选）
 *  - 登录 button#submitBtn（Angular 校验通过前带 disabled 类）
 *
 * 坑：页面是 Angular 写的 SPA，onPageFinished 时表单往往还没渲染出来，
 * 所以脚本自己轮询等表单（最多 25 秒），而不是靠注入时机。
 * 脚本可以重复注入：用 window.__xtuRunning / window.__xtuDone 两个标记保证只填一次、只点一次。
 */
object PortalAutofill {

    /** 用账密生成注入脚本，字符串按 JSON 规则转义。 */
    fun script(account: String, password: String, token: Int): String =
        SCRIPT
            .replace("__ACCOUNT__", escape(account))
            .replace("__PASSWORD__", escape(password))
            .replace("__TOKEN__", token.toString())

    /**
     * 只做"断网文案检查"的轻量脚本。
     *
     * 每次页面加载都会注入，与账密、自动填充进度无关 —— 页面刷新后 window 上的标记全部丢失，
     * 自动填充脚本可能因为守卫提前 return，靠这个脚本兜住"不在认证时段内"的识别。
     */
    fun offHoursScript(): String = OFF_HOURS_SCRIPT

    private fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val OFF_HOURS_SCRIPT = """
(function () {
  var MARK = "不在认证时段内";

  function check() {
    var list = [document];
    try {
      var fs = document.querySelectorAll("iframe");
      for (var i = 0; i < fs.length; i++) {
        try { var d = fs[i].contentDocument; if (d) list.push(d); } catch (e) {}
      }
    } catch (e) {}
    for (var j = 0; j < list.length; j++) {
      var t = ((list[j].body && list[j].body.innerText) || "").replace(/\s+/g, " ").trim();
      if (t.indexOf(MARK) >= 0) {
        try {
          if (window.XtuBridge) { window.XtuBridge.report("offHours", t.slice(0, 120)); }
        } catch (e) {}
        return true;
      }
    }
    return false;
  }

  if (check()) return;
  // 文案可能是后渲染的，接下来 20 秒每 0.5 秒再看一次
  var n = 0;
  var timer = setInterval(function () {
    n++;
    if (check() || n >= 40) { clearInterval(timer); }
  }, 500);
})();
""";

    private const val SCRIPT = """
(function () {
  var ACCOUNT = "__ACCOUNT__";
  var PASSWORD = "__PASSWORD__";
  var TOKEN = "__TOKEN__";

  function report(status, detail) {
    try { if (window.XtuBridge) { window.XtuBridge.report(status, detail || ""); } } catch (e) {}
  }

  var OFF_HOURS_MARK = "不在认证时段内";

  /** 在所有能访问的 document 里找断网文案，命中返回一行上下文，否则 null。 */
  function checkOffHours() {
    var list = [document];
    try {
      var fs = document.querySelectorAll("iframe");
      for (var i = 0; i < fs.length; i++) {
        try { var d = fs[i].contentDocument; if (d) list.push(d); } catch (e) {}
      }
    } catch (e) {}
    for (var j = 0; j < list.length; j++) {
      var t = ((list[j].body && list[j].body.innerText) || "").replace(/\s+/g, " ").trim();
      if (t.indexOf(OFF_HOURS_MARK) >= 0) return t.slice(0, 120);
    }
    return null;
  }

  // 断网判断必须放在"只填一次"的守卫之前：页面刷新后重新注入时，
  // 若守卫先 return，断网提示页就永远识别不到。
  var earlyOff = checkOffHours();
  if (earlyOff) {
    report("offHours", earlyOff);
    window.__xtuDone = true;
    return;
  }

  if (window.__xtuDone) { report("inject", "skip#" + TOKEN + " done"); return; }
  if (window.__xtuRunning) { report("inject", "skip#" + TOKEN + " running"); return; }
  window.__xtuRunning = true;

  var frames = document.querySelectorAll("iframe");
  var reachable = 0;
  for (var fi = 0; fi < frames.length; fi++) {
    try { if (frames[fi].contentDocument) reachable++; } catch (e) {}
  }
  report("inject", "start#" + TOKEN +
    " title=" + (document.title || "") +
    " inputs=" + document.querySelectorAll("input").length +
    " iframes=" + frames.length + "/reachable=" + reachable +
    " url=" + location.href.slice(0, 80));

  function docs() {
    var list = [document];
    for (var i = 0; i < frames.length; i++) {
      try { var d = frames[i].contentDocument; if (d) list.push(d); } catch (e) {}
    }
    return list;
  }

  function findInDocs(sel) {
    var ds = docs();
    for (var i = 0; i < ds.length; i++) {
      var el = ds[i].querySelector(sel);
      if (el) return el;
    }
    return null;
  }

  function setValue(el, value) {
    var proto = Object.getPrototypeOf(el);
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    el.dispatchEvent(new Event("keyup", { bubbles: true }));
    el.dispatchEvent(new Event("blur", { bubbles: true }));
  }

  function submit() {
    var btn = findInDocs("#submitBtn") || findInDocs("button[type=submit]");
    if (!btn) {
      var form = findInDocs("#normalLoginForm");
      report("submitViaForm", "no button, form=" + (!!form));
      window.__xtuDone = true;
      if (form) { try { form.requestSubmit ? form.requestSubmit() : form.submit(); } catch (e) { report("error", "form submit: " + e); } }
      return;
    }
    var n = 0;
    var timer = setInterval(function () {
      n++;
      var disabled = btn.disabled || btn.classList.contains("disabled") || btn.classList.contains("ant-btn-disabled");
      if (!disabled) {
        clearInterval(timer);
        window.__xtuDone = true;
        report("submitted", "clicked after " + (n * 250) + "ms");
        btn.click();
        var polls = 0;
        var watch = setInterval(function () {
          polls++;
          acceptAgreementDialog();
          if (polls >= 24) clearInterval(watch);
        }, 500);
      } else if (n >= 24) {
        clearInterval(timer);
        window.__xtuDone = true;
        report("submitFallback", "button disabled after " + (n * 250) + "ms");
        var form2 = findInDocs("#normalLoginForm");
        if (form2) { try { form2.requestSubmit ? form2.requestSubmit() : form2.submit(); } catch (e) { report("error", "fallback submit: " + e); } }
      }
    }, 250);
  }

  /**
   * 勾选协议框。
   *
   * 坑：直接对 input.click() 有时不会让 Angular 的表单模型生效（页面照旧认为没勾），
   * 于是点登录后门户会弹出《用户协议》详情框 —— 看起来就像"点偏了"。
   * 所以逐级尝试 input → .ant-checkbox → label，每一步回读 checked。
   */
  function ensureChecked(box) {
    if (box.checked) { report("agreementChecked", "already checked"); return; }
    box.click();
    if (box.checked) { report("agreementChecked", "input.click -> checked"); return; }
    var label = box.closest ? box.closest("label") : null;
    var inner = label ? label.querySelector(".ant-checkbox") : null;
    if (inner) {
      inner.click();
      if (box.checked) { report("agreementChecked", "ant-checkbox.click -> checked"); return; }
    }
    if (label) {
      label.click();
      if (box.checked) { report("agreementChecked", "label.click -> checked"); return; }
      var aria = label.getAttribute("aria-checked") || "";
      report("agreementChecked", "点击后仍未勾选 aria=" + aria + " cls=" + label.className);
    } else {
      report("agreementChecked", "input 无 label，checked=" + box.checked);
    }
  }

  /** 找当前显示的弹窗（ant-modal 一类）。 */
  function findDialog() {
    var wraps = document.querySelectorAll(".ant-modal-wrap, .ant-modal, .ant-dialog, .van-dialog");
    for (var i = 0; i < wraps.length; i++) {
      var w = wraps[i];
      if (!w.offsetParent) continue;
      var text = (w.innerText || "").replace(/\s+/g, " ").slice(0, 160);
      if (text) return { el: w, text: text };
    }
    return null;
  }

  /** 只自动确认"协议类"弹窗，其它（比如密码错误）只记录不关闭。 */
  function acceptAgreementDialog() {
    var d = findDialog();
    if (!d) return false;
    if (d.text === window.__xtuLastDialog) return false;
    window.__xtuLastDialog = d.text;
    report("dialog", d.text);
    if (!/协议|同意/.test(d.text)) return false;
    var btns = d.el.querySelectorAll("button, a.ant-btn");
    for (var i = 0; i < btns.length; i++) {
      var t = (btns[i].innerText || "").trim();
      if (/同意/.test(t)) {
        report("dialogClick", t);
        btns[i].click();
        return true;
      }
    }
    return false;
  }

  var waited = 0;
  function tick() {
    // 每轮先看页面有没有变成"不在认证时段内"
    var off = checkOffHours();
    if (off) {
      window.__xtuDone = true;
      window.__xtuRunning = false;
      report("offHours", off);
      return;
    }
    var user = findInDocs("input#nameInput") || findInDocs("input[name=username]");
    var pass = findInDocs("input[type=password]");
    if (!user || !pass) {
      waited += 300;
      if (waited <= 30000) {
        setTimeout(tick, 300);
      } else {
        window.__xtuRunning = false;
        report("noForm", "30s 后仍没表单 title=" + (document.title || "") +
          " inputs=" + document.querySelectorAll("input").length +
          " iframes=" + frames.length +
          " bodyLen=" + (document.body ? document.body.innerHTML.length : -1));
      }
      return;
    }

    var changed = false;
    if (user.value !== ACCOUNT) { setValue(user, ACCOUNT); changed = true; }
    if (pass.value !== PASSWORD) { setValue(pass, PASSWORD); changed = true; }
    report("filled", "waited=" + waited + "ms changed=" + changed + " userLen=" + (user.value || "").length + " pwdLen=" + (pass.value || "").length);

    var box = findInDocs("input.ant-checkbox-input[type=checkbox]") ||
              findInDocs("input.ant-checkbox-input");
    if (box) {
      ensureChecked(box);
    } else {
      report("agreementMissing", "checkbox not found");
    }

    setTimeout(submit, 600);
  }

  tick();
})();
""";
}
