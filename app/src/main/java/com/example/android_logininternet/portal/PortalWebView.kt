package com.example.android_logininternet.portal

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** JS 里的 window.XtuBridge，把注入脚本的进度回传给 Kotlin。 */
private class PortalJsBridge(private val onEvent: (String, String) -> Unit) {
    @JavascriptInterface
    fun report(status: String, detail: String) {
        onEvent(status, detail)
    }
}

/**
 * 承载认证页的 WebView：页面加载完成后注入自动填充脚本，并把 URL 变化回报给上层。
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
    onBridgeEvent: (String, String) -> Unit
) {
    val currentAccount by rememberUpdatedState(account)
    val currentPassword by rememberUpdatedState(password)
    val currentOnUrl by rememberUpdatedState(onUrlChanged)
    val currentOnBridge by rememberUpdatedState(onBridgeEvent)
    val injected = remember { mutableSetOf<String>() }
    val lastUrl = remember { mutableStateOf("") }

    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString()
                    if (url != null) {
                        lastUrl.value = url
                        currentOnUrl(url)
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null) {
                        lastUrl.value = url
                        currentOnUrl(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    lastUrl.value = url
                    currentOnUrl(url)
                    if (!url.contains("zz.xtu.edu.cn")) return
                    if (url.contains("/portal/entry/pc/finish")) return
                    val acc = currentAccount
                    val pwd = currentPassword
                    if (acc.isBlank() || pwd.isBlank()) return
                    val key = url + "|" + acc
                    if (!injected.add(key)) return
                    view?.postDelayed({
                        view.evaluateJavascript(PortalAutofill.script(acc, pwd), null)
                    }, 400L)
                }
            }
        }
    }

    LaunchedEffect(attempt) {
        injected.clear()
    }

    LaunchedEffect(portalUrl) {
        if (portalUrl.isNotBlank() && portalUrl != lastUrl.value) {
            webView.loadUrl(portalUrl)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
}