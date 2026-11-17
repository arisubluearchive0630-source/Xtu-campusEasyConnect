package com.example.android_logininternet.portal

/**
 * 注入到认证页的自动填充脚本。
 *
 * 选择器来自真机 outerHTML：
 *  - 账号 input#nameInput
 *  - 密码 input[type=password]（没有 id）
 *  - 协议 input.ant-checkbox-input（默认未勾选）
 *  - 登录 button#submitBtn（Angular 校验通过前带 disabled 类）
 */
object PortalAutofill {

    /** 用账密生成注入脚本，字符串按 JSON 规则转义。 */
    fun script(account: String, password: String): String {
        val acc = escape(account)
        val pwd = escape(password)
        return SCRIPT.replace("__ACCOUNT__", acc).replace("__PASSWORD__", pwd)
    }

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

    private const val SCRIPT = """
(function () {
  if (window.__xtuAutoLogin) { return; }
  window.__xtuAutoLogin = true;

  var ACCOUNT = "__ACCOUNT__";
  var PASSWORD = "__PASSWORD__";
  var tries = 0;
  var MAX_TRIES = 50;

  function report(status, detail) {
    try { if (window.XtuBridge) { window.XtuBridge.report(status, detail || ""); } } catch (e) {}
  }

  function setValue(el, value) {
    var proto = Object.getPrototypeOf(el);
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    el.dispatchEvent(new Event("blur", { bubbles: true }));
  }

  function pickAll(q) {
    var el = document.querySelector(q) || document.querySelectorAll(q);
    return el;
  }

  function step() {
    tries++;
    var user = document.querySelector("input#nameInput") || document.querySelector("input[name=username]");
    var pass = document.querySelector("input[type=password]");
    var btn = document.getElementById("submitBtn") || document.querySelector("button[type=submit]");

    if (!user || !pass) {
      if (tries < MAX_TRIES) { setTimeout(step, 300); } else { report("noForm", "account/password input not found"); }
      return;
    }

    var changed = false;
    if (user.value !== ACCOUNT) { setValue(user, ACCOUNT); changed = true; }
    if (pass.value !== PASSWORD) { setValue(pass, PASSWORD); changed = true; }
    if (changed) { report("filled", ""); }

    var box = document.querySelector("input.ant-checkbox-input[type=checkbox]");
    if (box && !box.checked) {
      box.click();
      report("agreementChecked", "checked=" + box.checked);
    }

    setTimeout(function () {
      if (!btn) {
        var form = document.getElementById("normalLoginForm");
        report("submitViaForm", "no button");
        if (form) { if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); } }
        return;
      }
      var disabled = btn.disabled || btn.classList.contains("disabled") || btn.classList.contains("ant-btn-disabled");
      if (disabled) {
        if (tries < MAX_TRIES) { setTimeout(step, 300); return; }
        var form2 = document.getElementById("normalLoginForm");
        report("submitFallback", "button still disabled");
        if (form2) { if (form2.requestSubmit) { form2.requestSubmit(); } else { form2.submit(); } }
        return;
      }
      report("submitted", "");
      btn.click();
    }, 250);
  }

  step();
})();
""";
}
