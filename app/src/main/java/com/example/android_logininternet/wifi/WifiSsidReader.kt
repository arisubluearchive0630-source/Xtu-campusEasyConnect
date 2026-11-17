package com.example.android_logininternet.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 读 Wi-Fi 状态。
 *
 * SSID 属于位置敏感信息，Android 13+ 即使声明了 NEARBY_WIFI_DEVICES，
 * 没有 ACCESS_FINE_LOCATION 时 getSSID() 也会返回 "<unknown ssid>"。
 * 所以"是否连着 Wi-Fi"和"拿不拿得到 SSID"必须分开判断。
 */
object WifiSsidReader {

    private const val TAG = "WifiSsidReader"

    val CAMPUS_SSIDS = setOf("XTU-STUDENT", "XTU-FREE")

    data class WifiState(
        val connected: Boolean,
        val ssid: String?,
        val hasFineLocation: Boolean,
        val hasNearbyDevices: Boolean
    ) {
        val campus: Boolean get() = ssid != null && ssid in CAMPUS_SSIDS
    }

    fun state(context: Context): WifiState {
        val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        val connected = runCatching { isWifiConnected(context) }.getOrElse {
            Log.w(TAG, "isWifiConnected failed", it)
            false
        }
        val ssid = if (connected) runCatching { readSsid(context) }.getOrNull() else null
        Log.i(TAG, "connected=" + connected + " ssid=" + ssid + " fine=" + fine + " nearby=" + nearby)
        return WifiState(connected, ssid, fine, nearby)
    }

    fun currentSsid(context: Context): String? = state(context).ssid

    fun isCampusNetwork(context: Context): Boolean = state(context).campus

    /** 连着 Wi-Fi 就算连着：先看默认网络，再看系统里所有 Wi-Fi 网络。 */
    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true

        // 兜底 1：遍历所有网络找 Wi-Fi（有些 ROM 在默认网络是 VPN/蜂窝时 activeNetwork 不是 Wi-Fi）
        val all = runCatching { cm.allNetworks }.getOrNull()
        if (all != null) {
            for (n in all) {
                val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
            }
        }

        // 兜底 2：看接口名是不是 wlan*
        val iface = active?.let { runCatching { cm.getLinkProperties(it)?.interfaceName }.getOrNull() }
        if (iface != null && iface.startsWith("wlan")) return true

        // 兜底 3：WifiManager 的连接信息
        val info = runCatching { wifiInfo(context) }.getOrNull()
        if (info != null && info.networkId != -1) return true
        return false
    }

    private fun wifiInfo(context: Context): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val info = caps?.transportInfo as? WifiInfo
            if (info != null) return info
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        return wm?.connectionInfo
    }

    private fun readSsid(context: Context): String? {
        val info = runCatching { wifiInfo(context) }.getOrNull()
        return normalize(info?.ssid)
    }

    private fun normalize(raw: String?): String? {
        val v = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: return null
        if (v.isBlank()) return null
        if (v.equals("<unknown ssid>", ignoreCase = true)) return null
        if (v == "0x") return null
        if (v.equals("unknown", ignoreCase = true)) return null
        return v
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 给界面用的原始诊断信息，方便用户截图反馈。 */
    fun diagnostics(context: Context): String {
        val sb = StringBuilder()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        sb.appendLine("SDK " + Build.VERSION.SDK_INT + " / " + Build.MANUFACTURER + " " + Build.MODEL)
        sb.appendLine("FINE_LOCATION " + hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
        sb.appendLine("NEARBY_WIFI " + hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES))
        sb.appendLine("activeNetwork " + (cm?.activeNetwork?.toString() ?: "null"))
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        sb.appendLine("transports " + if (caps == null) "null" else
            listOfNotNull(
                "WIFI".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                "CELLULAR".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                "VPN".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                "ETHERNET".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            ).joinToString(",").ifEmpty { "none" })
        sb.appendLine("iface " + runCatching {
            cm?.activeNetwork?.let { cm.getLinkProperties(it)?.interfaceName }
        }.getOrNull())
        sb.appendLine("netCount " + runCatching { cm?.allNetworks?.size }.getOrNull())
        val info = runCatching { wifiInfo(context) }.getOrNull()
        sb.appendLine("wifiInfo " + (if (info == null) "null" else "networkId=" + info.networkId + " ssid=" + info.ssid))
        sb.appendLine("wifiEnabled " + runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
        }.getOrNull())
        sb.appendLine("connected " + runCatching { isWifiConnected(context) }.getOrElse { "err:" + it })
        sb.appendLine("ssid " + currentSsid(context))
        return sb.toString()
    }
}
