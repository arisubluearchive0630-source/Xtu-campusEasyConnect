package com.example.android_logininternet.net

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 generate_204 判断是否已经能上外网；被劫持时从响应里抠出认证页地址。
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"
    private const val PROBE_URL = "http://connect.rom.miui.com/generate_204"
    private const val PORTAL_HOST = "zz.xtu.edu.cn"

    sealed interface Result {
        /** 已经是 204，能上网。 */
        data object Online : Result
        /** 被劫持，返回认证页地址。 */
        data class Portal(val url: String) : Result
        /** 没网、超时或其它。 */
        data class Unreachable(val detail: String) : Result
    }

    fun probe(): Result {
        val conn = try {
            (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            }
        } catch (t: Throwable) {
            return Result.Unreachable(t.message ?: t.javaClass.simpleName)
        }

        return try {
            val code = conn.responseCode
            if (code == 204) {
                Result.Online
            } else if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) Result.Unreachable("redirect without location")
                else Result.Portal(location)
            } else {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: conn.inputStream?.bufferedReader()?.use { it.readText() }
                    ?: ""
                val url = extractPortalUrl(body)
                if (url != null) Result.Portal(url) else Result.Unreachable("HTTP " + code)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
            Result.Unreachable(t.message ?: t.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    fun isOnline(): Boolean = probe() is Result.Online

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
