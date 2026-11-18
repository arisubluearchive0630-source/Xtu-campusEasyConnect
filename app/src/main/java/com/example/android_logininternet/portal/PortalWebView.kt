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

/** 往当前页面注入全部脚本（填表 / 断网提示 / 成功检测）。同源 iframe 由填表脚本自己遍历。 */
private fun injectAll(view: WebView, account: String, password: String, token: Int, tag: String) {
    EventLog.add("web", "注入全部脚本 " + tag + " token=" + token)
    if (account.isNotBlank() && password.isNotBlank()) {
        view.evaluateJavascript(PortalAutofill.script(account, password, token)) { result ->
            EventLog.add("js", "inject#" + token + " result=" + result)
        }
    } else {
        EventLog.add("web", "账密为空，只注入检测脚本")
    }
    view.evaluateJavascript(PortalAutofill.successScript(), null)
    view.evaluateJavascript(PortalAutofill.offHoursScript(), null)
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
                    // 每一次导航（不管什么页面）都立即注入全部脚本，
                    // 再按几个时间点补注入：Angular 渲染时机不确定，
                    // 认证页也常年挂着长连接，onPageFinished 可能永远不来。
                    val seq = navSeq.incrementAndGet()
                    injectAll(view, currentAccount, currentPassword, seq * 100, "start#" + seq)
                    val delays = longArrayOf(300L, 800L, 1500L, 3000L, 6000L)
                    delays.forEachIndexed { index, d ->
                        view.postDelayed({
                            injectAll(
                                view,
                                currentAccount,
                                currentPassword,
                                seq * 100 + index + 1,
                                "start#" + seq + "@" + d + "ms"
                            )
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

                    // 加载完成也立即注入一次，然后再补几个时间点（DOM 可能还在变）
                    val seq = navSeq.get()
                    injectAll(view, currentAccount, currentPassword, 60000 + seq, "finish#" + seq)
                    longArrayOf(300L, 1000L, 3000L).forEachIndexed { index, d ->
                        view.postDelayed({
                            injectAll(
                                view,
                                currentAccount,
                                currentPassword,
                                61000 + seq * 10 + index,
                                "finish#" + seq + "@" + d + "ms"
                            )
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

    LaunchedEffect(attempt) { EventLog.add("web", "新的一轮尝试 attempt=" + attempt) }

    LaunchedEffect(portalUrl) {
        if (portalUrl.isNotBlank()) {
            EventLog.add("web", "loadUrl " + portalUrl)
            webView.loadUrl(portalUrl)
            // 页面开着就一直每 1.2 秒把全部脚本再注入一遍：任何时刻出现的页面
            //（包括跳转后、SPA 换路由后新打开的页面）都会被填表。
            // 脚本内部按 URL 重置守卫，所以换页会重新填，同页重复注入是空操作。
            var round = 0
            while (true) {
                delay(1_200L)
                round++
                injectAll(webView, currentAccount, currentPassword, 20_000 + round, "tick#" + round)
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
