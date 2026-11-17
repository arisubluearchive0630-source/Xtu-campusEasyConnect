package com.example.android_logininternet.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 读 Wi-Fi 状态。
 *
 * 关键点：手机的默认网络可能是蜂窝（校园网待认证时就是这种情况），
 * 所以不能只看 activeNetwork，要专门找"那个 Wi-Fi 网络"再读它的 SSID。
 */
object WifiSsidReader {

    private const val TAG = "WifiSsidReader"

    val CAMPUS_SSIDS = setOf("XTU-STUDENT", "XTU-FREE")

    data class WifiState(
        val connected: Boolean,
        val ssid: String?,
        val hasFineLocation: Boolean,
        val hasNearbyDevices: Boolean,
        val locationEnabled: Boolean = true
    ) {
        val campus: Boolean get() = ssid != null && ssid in CAMPUS_SSIDS
    }

    fun state(context: Context): WifiState {
        val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        val connected = runCatching { WifiNetworks.isWifiConnected(context) }.getOrElse {
            Log.w(TAG, "isWifiConnected failed", it)
            false
        }
        val ssid = if (connected) {
            runCatching { WifiNetworks.readSsid(context) }.getOrNull()
        } else null
        val locEnabled = runCatching { locationEnabled(context) }.getOrDefault(true)
        Log.i(TAG, "connected=" + connected + " ssid=" + ssid + " fine=" + fine + " nearby=" + nearby + " loc=" + locEnabled)
        return WifiState(connected, ssid, fine, nearby, locEnabled)
    }

    fun currentSsid(context: Context): String? = state(context).ssid

    fun isCampusNetwork(context: Context): Boolean = state(context).campus

    /** 系统的"定位服务"开关，关着的时候 Android 也会隐藏 SSID。 */
    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return true
        return lm.isLocationEnabled
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 给界面用的原始诊断信息，方便用户截图反馈。 */
    fun diagnostics(context: Context): String {
        val sb = StringBuilder()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        sb.appendLine("SDK " + Build.VERSION.SDK_INT + " / " + Build.MANUFACTURER + " " + Build.MODEL)
        sb.appendLine("FINE_LOCATION " + hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
        sb.appendLine("LOCATION_SERVICE " + runCatching { locationEnabled(context) }.getOrDefault(true))
        sb.appendLine("NEARBY_WIFI " + hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES))
        sb.appendLine("activeNetwork " + (cm?.activeNetwork?.toString() ?: "null"))
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        sb.appendLine("activeTransports " + if (caps == null) "null" else
            listOfNotNull(
                "WIFI".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                "CELLULAR".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                "VPN".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ).joinToString(",").ifEmpty { "none" })
        val net = runCatching { WifiNetworks.wifiNetwork(context) }.getOrNull()
        sb.appendLine("wifiNetwork " + (net?.toString() ?: "null"))
        sb.appendLine("wifiIface " + runCatching { WifiNetworks.ifaceName(context, net) }.getOrNull())
        val candidates = runCatching { WifiNetworks.wifiInfoCandidates(context) }.getOrNull() ?: emptyList()
        candidates.forEachIndexed { i, info ->
            sb.appendLine("wifiInfo[" + i + "] " + "networkId=" + info.networkId + " ssid=" + info.ssid)
        }
        if (candidates.isEmpty()) sb.appendLine("wifiInfo null")
        sb.appendLine("wifiEnabled " + runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
        }.getOrNull())
        sb.appendLine("connected " + runCatching { WifiNetworks.isWifiConnected(context) }.getOrElse { "err:" + it })
        sb.appendLine("ssid " + currentSsid(context))
        return sb.toString()
    }
}
