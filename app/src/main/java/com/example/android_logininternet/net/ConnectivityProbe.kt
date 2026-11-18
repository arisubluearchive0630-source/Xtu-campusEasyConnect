package com.example.android_logininternet.net

import android.net.Network
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 找认证页地址。
 *
 * 只负责"发现入口"：向 Wi-Fi 发一个 generate_204，被劫持就从响应里抠出认证页 URL。
 * 判断登录是否成功**不**依赖联网探测（见 LoginViewModel），避免蜂窝网络干扰。
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"
    const val PROBE_URL = "http://connect.rom.miui.com/generate_204"
    private const val PORTAL_HOST = "zz.xtu.edu.cn"

    /** 兜底入口：直接打开门户的认证页。 */
    const val PORTAL_ENTRY = "https://zz.xtu.edu.cn/portal/entry/pc/authenticate"

    /**
     * 认证状态页。
     *
     * 已认证时直接访问这个地址，页面会显示"您已成功连接网络！"；
     * 未认证时门户自己会转去认证页（并带上 flowSessionId），所以它可以当通用入口用，
     * 不需要再靠网关劫持来做跳转。
     */
    const val FINISH_URL = "https://zz.xtu.edu.cn/portal/entry/pc/finish;flowParams=undefined;from="

    /**
     * 夜间断网时网关/门户会返回这句话，用它判断"不在认证时段"。
     * 不按系统时间猜：断网时间学校会调整，页面上写了什么才是准的。
     */
    const val OFF_HOURS_MARK = "不在认证时段内"

    sealed interface Result {
        /** 已经是 204，说明这个 Wi-Fi 已经能上网。 */
        data object Online : Result
        /** 被劫持，返回认证页地址。 */
        data class Portal(val url: String) : Result
        /** 没网、超时或其它。 */
        data class Unreachable(val detail: String) : Result
        /** 页面明确说了"不在认证时段内"（凌晨断网）。 */
        data class OffHours(val detail: String) : Result
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 13)"

    /**
     * 探测一次，然后在必要时把页面正文再抓一遍，确认是不是"不在认证时段内"的提示页。
     *
     * @param network 指定走哪个网络（一般传 Wi-Fi 网络），null 用系统默认。
     */
    fun probe(network: Network? = null): Result {
        val r = probeOnce(network)
        if (r is Result.OffHours) return r
        // 跳转目标 / 探测失败页都可能写着"不在认证时段内"，
        // 单看状态码判断不出来，所以再读一次正文。
        val target = when (r) {
            is Result.Portal -> r.url
            is Result.Unreachable -> PROBE_URL
            else -> null
        } ?: return r
        val body = bodyOf(target, network)
        return if (body.contains(OFF_HOURS_MARK)) {
            Log.i(TAG, "off hours page: " + target)
            Result.OffHours(snippet(body))
        } else {
            r
        }
    }

    /** 只抓页面正文，忽略状态码；任何异常都当空串。 */
    private fun bodyOf(url: String, network: Network?): String {
        return try {
            val u = URL(url)
            @Suppress("DEPRECATION")
            val c = (if (network != null) network.openConnection(u) else u.openConnection()) as HttpURLConnection
            try {
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.responseCode
                (c.errorStream ?: c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                c.disconnect()
            }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun snippet(body: String): String =
        body.replace(Regex("\\s+"), " ").trim().take(120)

    private fun probeOnce(network: Network? = null): Result {
        val conn = try {
            val url = URL(PROBE_URL)
            @Suppress("DEPRECATION")
            (if (network != null) network.openConnection(url) else url.openConnection()) as HttpURLConnection
        } catch (t: Throwable) {
            return Result.Unreachable(t.message ?: t.javaClass.simpleName)
        }
        conn.apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = false
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("User-Agent", UA)
        }

        return try {
            val code = conn.responseCode
            if (code == 204) {
                Result.Online
            } else {
                val body = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: conn.inputStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: ""
                when {
                    // 断网时段：网关/门户直接在响应里写"不在认证时段内"
                    body.contains(OFF_HOURS_MARK) -> {
                        Log.i(TAG, "off hours: " + snippet(body))
                        Result.OffHours(snippet(body))
                    }
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                        if (location.isNullOrBlank()) Result.Unreachable("redirect without location")
                        else Result.Portal(location)
                    }
                    else -> {
                        val url = extractPortalUrl(body)
                        if (url != null) Result.Portal(url) else Result.Unreachable("HTTP " + code)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
            Result.Unreachable(t.message ?: t.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** 从被劫持的 HTML 里找 zz.xtu.edu.cn 的跳转地址。 */
    internal fun extractPortalUrl(html: String): String? {
        if (!html.contains(PORTAL_HOST)) return null
        val patterns = listOf(
            Regex("location\\.(?:href|replace)\\s*=?\\s*['\"]([^'\"]+)['\"]"),
            Regex("href\\s*=\\s*['\"]([^'\"]*zz\\.xtu\\.edu\\.cn[^'\"]*)['\"]"),
            Regex("(https?://[^'\"\\s<>]*zz\\.xtu\\.edu\\.cn[^'\"\\s<>]*)")
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            val candidate = m.groupValues.lastOrNull { it.isNotBlank() } ?: continue
            return candidate.replace("&amp;", "&")
        }
        return null
    }
}
