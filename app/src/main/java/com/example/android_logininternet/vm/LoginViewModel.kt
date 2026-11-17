package com.example.android_logininternet.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.android_logininternet.data.SettingsRepository
import com.example.android_logininternet.net.ConnectivityProbe
import com.example.android_logininternet.wifi.WifiConnector
import com.example.android_logininternet.wifi.WifiSsidReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 登录状态机。 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { Idle, ConnectingWifi, Probing, LoadingPortal, Filling, Verifying, Online, Failed }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val message: String = "",
        val ssid: String? = null,
        val wifiConnected: Boolean = false,
        val hasFineLocation: Boolean = false,
        val campus: Boolean = false,
        val portalUrl: String? = null,
        val pageUrl: String = "",
        val elapsedSeconds: Long = 0,
        val attempt: Int = 0,
        val account: String = "",
        val password: String = "",
        val configured: Boolean = false,
        val autoConnect: Boolean = false
    ) {
        val running: Boolean
            get() = phase != Phase.Idle && phase != Phase.Online && phase != Phase.Failed

        /** 顶部显示的 Wi-Fi 文案。 */
        val wifiLabel: String
            get() = when {
                ssid != null -> ssid
                wifiConnected -> "已连接（名称不可见）"
                else -> "未连接"
            }
    }

    private val settings = SettingsRepository(app)
    private val _state = MutableStateFlow(
        UiState(
            account = settings.account,
            password = settings.password,
            configured = settings.isConfigured,
            autoConnect = settings.autoConnectOnLaunch
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var workJob: Job? = null
    private var timerJob: Job? = null
    private var finishSeen = false

    init {
        refreshNetwork()
    }

    /** 刷新 Wi-Fi 状态（SSID、是否连着、定位权限）。 */
    fun refreshNetwork() {
        val st = WifiSsidReader.state(getApplication())
        _state.update {
            it.copy(
                ssid = st.ssid,
                wifiConnected = st.connected,
                hasFineLocation = st.hasFineLocation,
                campus = st.campus
            )
        }
    }

    fun saveCredentials(account: String, password: String, autoConnect: Boolean) {
        settings.account = account
        settings.password = password
        settings.autoConnectOnLaunch = autoConnect
        _state.update {
            it.copy(
                account = account,
                password = password,
                configured = account.isNotBlank() && password.isNotBlank(),
                autoConnect = autoConnect
            )
        }
    }

    fun start() {
        if (workJob?.isActive == true) return
        val account = settings.account
        val password = settings.password
        if (account.isBlank() || password.isBlank()) {
            _state.update { it.copy(phase = Phase.Failed, message = "请先在设置里填写学号和密码") }
            return
        }
        finishSeen = false
        refreshNetwork()
        _state.update {
            it.copy(
                phase = Phase.ConnectingWifi,
                message = "正在检查 Wi-Fi…",
                portalUrl = null,
                pageUrl = "",
                elapsedSeconds = 0,
                attempt = it.attempt + 1,
                account = account,
                password = password
            )
        }
        startTimer()
        workJob = viewModelScope.launch { run(account) }
    }

    fun stop() {
        workJob?.cancel()
        timerJob?.cancel()
        workJob = null
        _state.update { it.copy(phase = Phase.Idle, message = "", portalUrl = null, pageUrl = "") }
        refreshNetwork()
    }

    /** WebView 的 URL 变化。 */
    fun onPageUrl(url: String) {
        if (url.contains("/portal/entry/pc/finish")) finishSeen = true
        _state.update { it.copy(pageUrl = url) }
    }

    /** 注入脚本的回传。 */
    fun onBridgeEvent(status: String, detail: String) {
        when (status) {
            "filled" -> _state.update { it.copy(phase = Phase.Filling, message = "已填入账号密码") }
            "agreementChecked" -> _state.update { it.copy(message = "已勾选用户协议") }
            "submitted" -> _state.update { it.copy(phase = Phase.Verifying, message = "已点击登录，等待认证…") }
            "submitFallback", "submitViaForm" ->
                _state.update { it.copy(phase = Phase.Verifying, message = "按钮不可用，已直接提交表单") }
            "noForm" -> _state.update { it.copy(phase = Phase.Failed, message = "没找到登录表单，页面可能变了") }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _state.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            }
        }
    }

    private suspend fun run(account: String) = withContext(Dispatchers.IO) {
        // 1. 确保连着 Wi-Fi（不要求一定能读到 SSID）
        var st = WifiSsidReader.state(getApplication())
        if (!st.connected) {
            _state.update { it.copy(phase = Phase.ConnectingWifi, message = "正在连接 XTU-STUDENT…") }
            WifiConnector.requestConnect(getApplication(), "XTU-STUDENT")
            var waited = 0
            while (waited < 20) {
                delay(1000)
                waited++
                st = WifiSsidReader.state(getApplication())
                _state.update {
                    it.copy(
                        ssid = st.ssid,
                        wifiConnected = st.connected,
                        hasFineLocation = st.hasFineLocation,
                        campus = st.campus
                    )
                }
                if (st.connected) break
            }
        } else {
            _state.update {
                it.copy(
                    ssid = st.ssid,
                    wifiConnected = st.connected,
                    hasFineLocation = st.hasFineLocation,
                    campus = st.campus
                )
            }
        }
        if (!st.connected) {
            _state.update { it.copy(phase = Phase.Failed, message = "没有连上任何 Wi-Fi，请先连 XTU-STUDENT") }
            return@withContext
        }
        if (st.ssid == null) {
            _state.update { it.copy(message = "已连 Wi-Fi 但读不到名称（缺定位权限），先探测认证页…") }
        } else if (!st.campus) {
            _state.update { it.copy(message = "当前不是校园网（" + st.ssid + "），先试着探测…") }
        }

        // 2. 探测入口
        _state.update { it.copy(phase = Phase.Probing, message = "正在探测认证入口…") }
        var portal: String? = null
        for (i in 0 until 5) {
            when (val r = ConnectivityProbe.probe()) {
                is ConnectivityProbe.Result.Online -> {
                    _state.update { it.copy(phase = Phase.Online, message = "已经可以上网，无需登录") }
                    timerJob?.cancel()
                    return@withContext
                }
                is ConnectivityProbe.Result.Portal -> {
                    portal = r.url
                    break
                }
                is ConnectivityProbe.Result.Unreachable -> {
                    _state.update { it.copy(message = "探测失败：" + r.detail + "，重试中…") }
                    delay(1000)
                }
            }
        }
        if (portal == null) {
            val hint = if (st.ssid == null) "拿不到认证页地址：读不到 Wi-Fi 名称，请授予定位权限后重试" else
                "拿不到认证页地址，点重试或检查 Wi-Fi"
            _state.update { it.copy(phase = Phase.Failed, message = hint) }
            return@withContext
        }
        val portalIsCampus = portal.contains("zz.xtu.edu.cn")
        _state.update { it.copy(campus = it.campus || portalIsCampus, message = "") }

        // 3. 交给 WebView 注入
        _state.update {
            it.copy(portalUrl = portal, phase = Phase.LoadingPortal, message = "正在打开认证页…")
        }

        // 4. 轮询结果
        var onlineHits = 0
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(1000)
            val online = ConnectivityProbe.isOnline()
            if (online) onlineHits++ else onlineHits = 0
            if (onlineHits >= 2 && finishSeen) {
                timerJob?.cancel()
                _state.update { it.copy(phase = Phase.Online, message = "登录成功，已联网") }
                return@withContext
            }
            if (onlineHits >= 3) {
                timerJob?.cancel()
                _state.update { it.copy(phase = Phase.Online, message = "已联网") }
                return@withContext
            }
            if (onlineHits == 0 && finishSeen) {
                _state.update { it.copy(message = "页面已到成功页，等待网络放行…") }
            }
        }
        _state.update { it.copy(phase = Phase.Failed, message = "认证超时，请重试") }
    }
}
