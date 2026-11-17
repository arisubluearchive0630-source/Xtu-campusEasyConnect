package com.example.android_logininternet.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 读 Wi-Fi 状态。
 *
 * 注意：SSID 属于位置敏感信息，Android 13+ 即使声明了 NEARBY_WIFI_DEVICES，
 * 没有 ACCESS_FINE_LOCATION 时 getSSID() 也可能返回 "<unknown ssid>"。
 * 所以这里把"是否连着 Wi-Fi"和"拿不拿得到 SSID"分开返回，UI 才能给出准确提示。
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
        val connected = isWifiConnected(context)
        val ssid = if (connected) readSsid(context) else null
        Log.i(TAG, "state connected=" + connected + " ssid=" + ssid + " fine=" + fine + " nearby=" + nearby)
        return WifiState(connected, ssid, fine, nearby)
    }

    fun currentSsid(context: Context): String? = state(context).ssid

    fun isCampusNetwork(context: Context): Boolean = state(context).campus

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun readSsid(context: Context): String? {
        // 1) Android 12+ 从 NetworkCapabilities 里取
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val info = caps?.transportInfo as? WifiInfo
            val ssid = normalize(info?.ssid)
            if (ssid != null) return ssid
        }
        // 2) 退回 WifiManager（各版本都能试，失败就吞掉）
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            normalize(wm?.connectionInfo?.ssid)
        } catch (t: Throwable) {
            Log.w(TAG, "connectionInfo failed", t)
            null
        }
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
}
