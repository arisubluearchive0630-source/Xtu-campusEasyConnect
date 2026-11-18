package com.example.android_logininternet.portal

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.android_logininternet.util.EventLog
import kotlinx.coroutines.delay

/** JS 里的 window.XtuBridge，把注入脚本的进度回传给 Kotlin。 */
private class PortalJsBridge(private val onEvent: (String, String) -> Unit) {
    @JavascriptInterface
    fun report(status: String, detail: String) {
        onEvent(status, detail)
    }
}

/**
 * 承载认证页的 WebView：页面加载完成后注入自动填充脚本，并把 URL 变化回报给上层。
 *
 * 注入时机：认证页是 Angular SPA，onPageFinished 时表单常常还没渲染，
 * 所以同一次加载会在 0.4s / 1.5s / 4s / 8s 各注入一次，
 * 真正的"等表单"由脚本内部的轮询负责，重复注入由 JS 侧标记挡住。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalWebView(
    portalUrl: String,
    account: String,
    password: String,
    attempt: Int,
    modifier: Modifier = Modifier,
    onUrlChanged: (String) -> Unit,
    onBridgeEvent: (String, String) -> Unit,
    onReady: () -> Unit = {}
) {
    val currentAccount by rememberUpdatedState(account)
    val currentPassword by rememberUpdatedState(password)
    val currentOnUrl by rememberUpdatedState(onUrlChanged)
    val currentOnBridge by rememberUpdatedState(onBridgeEvent)
    val currentOnReady by rememberUpdatedState(onReady)
    /** 已经注入过的 (导航序号, 时延) 组合，避免同一页面反复注入。 */
    val injected = remember { mutableSetOf<String>() }

    /** 每次导航自增：同一个 URL 刷新后也要重新注入，否则脚本只会在第一次加载时生效。 */
    val navSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = settings.userAgentString + " XtuAutoLogin/1.0"

            addJavascriptInterface(
                PortalJsBridge { status, detail -> currentOnBridge(status, detail) },
                "XtuBridge"
            )

            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    // 认证页出错时可能弹 alert，不处理会卡住页面
                    EventLog.add("js", "alert: " + message)
                    currentOnBridge("pageAlert", message ?: "")
                    result?.confirm()
                    return true
                }

                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    if (msg != null) {
                        EventLog.add("js", msg.message() + " (line " + msg.lineNumber() + ")")
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.toString()?.let {
                        EventLog.add("web", "shouldOverride " + it)
                        currentOnUrl(it)
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url == null || view == null) return
                    EventLog.add("web", "onPageStarted " + url)
                    currentOnUrl(url)
                    // 新导航：断网文案 / 成功文案检查无条件注入，同 URL 刷新也算新导航
                    val seq = navSeq.incrementAndGet()
                    view.postDelayed({
                        view.evaluateJavascript(PortalAutofill.successScript(), null)
                    }, 700L)
                    if (injected.add("off#" + seq)) {
                        view.postDelayed({
                            view.evaluateJavascript(PortalAutofill.offHoursScript()) { result ->
                                EventLog.add("js", "offHours#" + seq + " result=" + result)
                            }
                        }, 800L)
                    }
                    // 认证页常年有长连接，onPageFinished 可能迟迟不来（甚至永远不来），
                    // 所以从 onPageStarted 起就按时间点硬注入，不等加载"完成"。
                    val acc = currentAccount
                    val pwd = currentPassword
                    if (acc.isBlank() || pwd.isBlank()) return
                    if (url.contains("/portal/entry/pc/finish")) return
                    val delays = longArrayOf(1500L, 3000L, 6000L, 10000L, 15000L)
                    delays.forEachIndexed { index, d ->
                        val key = "start|" + seq + "#" + index
                        if (!injected.add(key)) return@forEachIndexed
                        view.postDelayed({
                            if (view.url?.contains("/portal/entry/pc/finish") == true) return@postDelayed
                            val token = 9000 + index
                            EventLog.add("web", "注入脚本(按时间) token=" + token + " @" + d + "ms")
                            view.evaluateJavascript(PortalAutofill.script(acc, pwd, token)) { result ->
                                EventLog.add("js", "inject#" + token + " result=" + result)
                            }
                        }, d)
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    EventLog.add("web", "onPageCommitVisible " + url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null || view == null) return
                    EventLog.add("web", "onPageFinished " + url)
                    currentOnUrl(url)
                    currentOnReady()

                    val seq = navSeq.get()
                    // 再检查一次断网文案：这次是"加载完成"的时机，DOM 更完整
                    if (injected.add("offF#" + seq)) {
                        view.evaluateJavascript(PortalAutofill.offHoursScript()) { result ->
                            EventLog.add("js", "offHoursF#" + seq + " result=" + result)
                        }
                    }

                    // 成功文案可能晚于 onPageFinished 出现，这里先打一枪，之后由定时循环兜住
                    view.evaluateJavascript(PortalAutofill.successScript(), null)

                    if (url.contains("/portal/entry/pc/finish")) {
                        EventLog.add("web", "已到 finish 页")
                        return
                    }
                    val acc = currentAccount
                    val pwd = currentPassword
                    if (acc.isBlank() || pwd.isBlank()) {
                        EventLog.add("web", "账密为空，跳过注入")
                        return
                    }
                    // 不再限定域名：不同网关给出的页面地址不一样，
                    // 由脚本自己判断页面上有没有登录表单。

                    // Angular 渲染时机不确定，多打几个时间点
                    val delays = longArrayOf(400L, 1500L, 4000L, 8000L)
                    delays.forEachIndexed { index, d ->
                        val key = "fin|" + seq + "#" + index
                        if (!injected.add(key)) return@forEachIndexed
                        view.postDelayed({
                            val token = attempt * 100 + index
                            EventLog.add("web", "注入脚本 token=" + token + " @" + d + "ms")
                            view.evaluateJavascript(PortalAutofill.script(acc, pwd, token)) { result ->
                                EventLog.add("js", "inject#" + token + " result=" + result)
                            }
                        }, d)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame != true) return
                    val desc = (error?.description?.toString() ?: "") + " code=" + (error?.errorCode?.toString() ?: "?")
                    EventLog.add("web", "主文档加载失败 " + request.url + " " + desc)
                    currentOnBridge("pageError", desc + " " + request.url)
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    EventLog.add("web", "渲染进程崩溃 didCrash=" + (detail?.didCrash() == true))
                    currentOnBridge("renderGone", "渲染进程崩溃")
                    return true
                }
            }
        }
    }

    LaunchedEffect(attempt) { injected.clear() }

    LaunchedEffect(portalUrl) {
        if (portalUrl.isNotBlank()) {
            EventLog.add("web", "loadUrl " + portalUrl)
            webView.loadUrl(portalUrl)
            // 认证页有时原地刷新、有时慢半拍才渲染出"您已成功连接网络"，
            // 所以整轮流程里每 2 秒补一次成功检查（脚本内部有去重标记，重复注入是空操作）。
            var waited = 0L
            while (waited < 180_000L) {
                delay(2_000L)
                waited += 2_000L
                webView.evaluateJavascript(PortalAutofill.successScript(), null)
            }
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)

    DisposableEffect(Unit) {
        onDispose {
            EventLog.add("web", "dispose WebView")
            webView.stopLoading()
            webView.destroy()
        }
    }
}
