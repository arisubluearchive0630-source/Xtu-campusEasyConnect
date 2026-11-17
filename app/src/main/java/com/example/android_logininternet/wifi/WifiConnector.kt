package com.example.android_logininternet.wifi

import android.content.Context
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * 把校园网（开放网络）交给系统去连。
 * 29+ 用 WifiNetworkSuggestion，28 用 addNetwork + enableNetwork。
 * 都是"提交建议/配置"，由系统择机连接，所以调用方需要轮询 SSID。
 */
object WifiConnector {

    private const val TAG = "WifiConnector"

    /** 提交连接校园网的请求，返回是否成功提交。 */
    fun requestConnect(context: Context, ssid: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestWithSuggestion(context, ssid)
        } else {
            @Suppress("DEPRECATION")
            requestLegacy(context, ssid)
        }
    }

    private fun requestWithSuggestion(context: Context, ssid: String): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(false)
            .build()
        val result = try {
            wm.removeNetworkSuggestions(listOf(suggestion))
            wm.addNetworkSuggestions(listOf(suggestion))
        } catch (t: Throwable) {
            Log.w(TAG, "addNetworkSuggestions failed", t)
            return false
        }
        Log.i(TAG, "addNetworkSuggestions($ssid) -> $result")
        return result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }

    @Suppress("DEPRECATION")
    private fun requestLegacy(context: Context, ssid: String): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val config = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$ssid\""
            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
        }
        return try {
            val existing = wm.configuredNetworks?.firstOrNull { it.SSID == config.SSID }
            val netId = if (existing != null) existing.networkId else wm.addNetwork(config)
            if (netId < 0) return false
            wm.disconnect()
            wm.enableNetwork(netId, true)
            wm.reconnect()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "legacy connect failed", t)
            false
        }
    }
}
