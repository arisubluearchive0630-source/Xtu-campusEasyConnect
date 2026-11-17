package com.example.android_logininternet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * 找"当前连着的那个 Wi-Fi 网络"，并把进程绑定到它。
 *
 * 为什么需要绑定：手机连着需要认证的校园网、同时又开着蜂窝时，系统的默认网络是蜂窝，
 * 于是 App 里所有 HTTP 请求（包括 WebView）都会走蜂窝 —— 打不开校园网认证页，
 * 认证页也会以为你在用蜂窝的 IP。绑定到 Wi-Fi 之后，本进程的请求才真正走校园网。
 */
object WifiNetworks {

    private const val TAG = "WifiNetworks"

    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork
        val activeCaps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active

        val all = runCatching { cm.allNetworks }.getOrNull() ?: return null
        for (n in all) {
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    /** 该 Wi-Fi 网络对应的 WifiInfo（拿 SSID 用），拿不到返回 null。 */
    fun wifiInfo(context: Context): WifiInfo? = wifiInfoCandidates(context).firstOrNull()

    /**
     * 所有能拿到 WifiInfo 的来源，按"可靠性"排序。
     *
     * 坑：当默认网络是蜂窝时，非默认 Wi-Fi 的 NetworkCapabilities.transportInfo
     * 里 SSID 常常是 "<unknown ssid>"，而 WifiManager.connectionInfo 反而是好的。
     * 所以两个来源都试，取第一个能读出真实 SSID 的。
     */
    fun wifiInfoCandidates(context: Context): List<WifiInfo> {
        val list = mutableListOf<WifiInfo>()
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.let { list.add(it) }
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = runCatching { wifiNetwork(context) }.getOrNull()
        if (cm != null && net != null) {
            runCatching {
                (cm.getNetworkCapabilities(net)?.transportInfo as? WifiInfo)?.let { list.add(it) }
            }
        }
        if (cm != null) {
            val active = cm.activeNetwork
            if (active != null && active != net) {
                runCatching {
                    (cm.getNetworkCapabilities(active)?.transportInfo as? WifiInfo)?.let { list.add(it) }
                }
            }
        }
        return list
    }

    /** 依次尝试各来源，返回第一个读得出的真实 SSID。 */
    fun readSsid(context: Context): String? {
        for (info in wifiInfoCandidates(context)) {
            val ssid = normalizeSsid(info.ssid)
            if (ssid != null) return ssid
        }
        return null
    }

    fun normalizeSsid(raw: String?): String? {
        val v = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: return null
        if (v.isBlank()) return null
        if (v.equals("<unknown ssid>", ignoreCase = true)) return null
        if (v == "0x") return null
        if (v.equals("unknown", ignoreCase = true)) return null
        return v
    }


    fun isWifiConnected(context: Context): Boolean = wifiNetwork(context) != null

    /** 把本进程绑定到该 Wi-Fi；返回是否成功。 */
    fun bind(context: Context, network: Network?): Boolean {
        if (network == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return runCatching { cm?.bindProcessToNetwork(network) == true }.getOrElse {
            Log.w(TAG, "bind failed", it)
            false
        }
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.bindProcessToNetwork(null) }
            .onFailure { Log.w(TAG, "unbind failed", it) }
    }

    fun ifaceName(context: Context, network: Network?): String? {
        if (network == null) return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return runCatching { cm?.getLinkProperties(network)?.interfaceName }.getOrNull()
    }
}
