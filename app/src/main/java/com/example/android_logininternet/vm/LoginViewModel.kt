package com.example.android_logininternet.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.android_logininternet.data.SettingsRepository
import com.example.android_logininternet.net.ConnectivityProbe
import com.example.android_logininternet.util.EventLog
import com.example.android_logininternet.wifi.WifiConnector
import com.example.android_logininternet.wifi.WifiNetworks
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

/**
 * 登录状态机。
 *
 * 判定原则：登录成功与否只看认证页有没有走到 finish 页，
 * 不做"能不能上外网"的探测 —— 因为手机开着蜂窝时，外网探测会误判成功。
 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 断网时段由页面自己说明（"不在认证时段内"），程序不按系统时间猜。 */
        const val OUT_OF_HOURS_TEXT = "不在认证时段内，凌晨断网你不知道的吗笨蛋！"
    }

    enum class Phase { Idle, ConnectingWifi, Probing, LoadingPortal, Filling, Verifying, Online, Failed }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val message: String = "",
        val ssid: String? = null,
        val wifiConnected: Boolean = false,
        val hasFineLocation: Boolean = false,
        val locationEnabled: Boolean = true,
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

    /** 探测或页面上见过"不在认证时段内"这几个字。 */
    @Volatile
    private var offHoursSeen = false

    /** 置 true 后 run() 里的各个循环立刻退出（停止、失败、重新启动都会置位）。 */
    @Volatile
    private var aborted = false

    /** 启动时自动连接只触发一次，旋转屏幕重建 ViewModel 不会重复连。 */
    private var autoStartUsed = false

    init {
        refreshNetwork()
    }

    /**
     * 设置里打开了"启动时自动连接"就在进界面时自动跑一轮。
     *
     * 由界面在权限申请结束后调用：太早调用会因为拿不到 SSID 而显得"没生效"。
     */
    fun autoStartIfNeeded() {
        if (autoStartUsed) return
        autoStartUsed = true
        if (!settings.autoConnectOnLaunch) {
            EventLog.add("vm", "启动时自动连接：未开启")
            return
        }
        if (settings.account.isBlank() || settings.password.isBlank()) {
            EventLog.add("vm", "启动时自动连接：已开启但没有账密，跳过")
            _state.update { it.copy(message = "启动时自动连接已开启，请先填写学号和密码") }
            return
        }
        EventLog.add("vm", "启动时自动连接：自动开始")
        start()
    }

    /** 刷新 Wi-Fi 状态（SSID、是否连着、定位权限）。 */
    fun refreshNetwork() {
        val st = runCatching { WifiSsidReader.state(getApplication()) }.getOrNull() ?: return
        _state.update {
            it.copy(
                ssid = st.ssid,
                wifiConnected = st.connected,
                hasFineLocation = st.hasFineLocation,
                locationEnabled = st.locationEnabled,
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
        EventLog.add("vm", "点击启动 phase=" + state.value.phase)
        // 上一轮可能还在跑（比如已判定失败但循环没退），直接掐掉再重开，
        // 否则用户点了启动会毫无反应。
        workJob?.cancel()
        timerJob?.cancel()
        workJob = null
        aborted = false
        WifiNetworks.unbind(getApplication())
        val account = settings.account
        val password = settings.password
        if (account.isBlank() || password.isBlank()) {
            EventLog.add("vm", "账密为空，拒绝启动")
            _state.update { it.copy(phase = Phase.Failed, message = "请先在设置里填写学号和密码") }
            return
        }
        finishSeen = false
        offHoursSeen = false
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
        EventLog.add("vm", "点击停止 phase=" + state.value.phase)
        aborted = true
        workJob?.cancel()
        timerJob?.cancel()
        workJob = null
        WifiNetworks.unbind(getApplication())
        _state.update { it.copy(phase = Phase.Idle, message = "", portalUrl = null, pageUrl = "") }
        refreshNetwork()
    }

    /** 关掉认证页（页面卡住时用）。 */
    fun closePortal() {
        EventLog.add("vm", "关闭认证页")
        _state.update { it.copy(portalUrl = null, pageUrl = "") }
    }

    /** WebView 的 URL 变化。 */
    fun onPageUrl(url: String) {
        if (url.contains("/portal/entry/pc/finish")) finishSeen = true
        _state.update { it.copy(pageUrl = url) }
    }

    /** 注入脚本的回传。 */
    fun onBridgeEvent(status: String, detail: String) {
        EventLog.add("bridge", status + " " + detail)
        when (status) {
            "inject", "skip", "log" -> Unit
            "form" -> _state.update { it.copy(phase = Phase.Filling, message = "找到登录表单") }
            "filled" -> _state.update { it.copy(phase = Phase.Filling, message = "已填入账号密码") }
            "agreementChecked", "agreementMissing" ->
                _state.update { it.copy(message = "协议：" + detail) }
            "submitted" -> _state.update { it.copy(phase = Phase.Verifying, message = "已点击登录，等待认证…") }
            "submitFallback", "submitViaForm" ->
                _state.update { it.copy(phase = Phase.Verifying, message = "按钮不可用，已直接提交表单") }
            "pageAlert" -> _state.update { it.copy(message = "页面提示：" + detail) }
            "dialog" -> _state.update { it.copy(message = "页面弹窗：" + detail) }
            "dialogClick" -> _state.update { it.copy(message = "已点掉协议弹窗：" + detail) }
            "offHours" -> {
                offHoursSeen = true
                EventLog.add("vm", "页面提示不在认证时段：" + detail)
                fail(OUT_OF_HOURS_TEXT)
            }
            "noForm" -> fail("没找到登录表单（" + detail + "）")
            "pageError" -> fail("认证页打不开：" + detail, networkIssue = true)
            "renderGone" -> fail("WebView 渲染进程崩溃，点重试")
        }
    }

    /**
     * 统一收尾。
     *
     * networkIssue = true 表示这次失败是"连不上网/连不上认证页"这一类；
     * 而页面上又写着"不在认证时段内"（凌晨断网）时，把提示换成那句人话。
     */
    private fun fail(message: String, networkIssue: Boolean = false) {
        val shown = if (networkIssue && offHoursSeen) OUT_OF_HOURS_TEXT else message
        EventLog.add("vm", "失败：" + message + if (shown != message) "（已改为时段提示）" else "")
        aborted = true
        timerJob?.cancel()
        WifiNetworks.unbind(getApplication())
        _state.update { it.copy(phase = Phase.Failed, message = shown) }
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
        val app = getApplication<Application>()

        EventLog.add("vm", "run 开始，phase=" + state.value.phase)

        // 1. 确保连着 Wi-Fi（不要求一定能读到 SSID）
        var st = WifiSsidReader.state(app)
        if (!st.connected) {
            _state.update { it.copy(phase = Phase.ConnectingWifi, message = "正在连接 XTU-STUDENT…") }
            WifiConnector.requestConnect(app, "XTU-STUDENT")
            var waited = 0
            while (waited < 20 && !st.connected && !aborted) {
                delay(1000)
                waited++
                st = WifiSsidReader.state(app)
                _state.update {
                    it.copy(
                        ssid = st.ssid,
                        wifiConnected = st.connected,
                        hasFineLocation = st.hasFineLocation,
                        locationEnabled = st.locationEnabled,
                        campus = st.campus
                    )
                }
            }
        } else {
            _state.update {
                it.copy(
                    ssid = st.ssid,
                    wifiConnected = st.connected,
                    hasFineLocation = st.hasFineLocation,
                    locationEnabled = st.locationEnabled,
                    campus = st.campus
                )
            }
        }
        if (!st.connected) {
            fail("没有连上 Wi-Fi，请先连 XTU-STUDENT", networkIssue = true)
            return@withContext
        }

        // 2. 把本进程绑到 Wi-Fi：默认网络可能是蜂窝，不绑定就连不到校园网认证页
        val wifiNet = WifiNetworks.wifiNetwork(app)
        val bound = WifiNetworks.bind(app, wifiNet)
        EventLog.add("vm", "wifiNet=" + wifiNet + " bound=" + bound + " ssid=" + st.ssid)
        _state.update {
            it.copy(message = if (bound) "已切换到 Wi-Fi 通道（" + (st.ssid ?: "名称不可见") + "）" else "只用 Wi-Fi 连接")
        }

        // 3. 找认证页入口（只做发现，不当作是否联网的判据）
        _state.update { it.copy(phase = Phase.Probing, message = "正在找认证页…") }
        var portal: String? = null
        var alreadyOnline = false
        loop@ for (i in 0 until 3) {
            if (aborted) return@withContext
            val r = ConnectivityProbe.probe(wifiNet)
            EventLog.add("vm", "probe#" + i + " -> " + r)
            when (r) {
                is ConnectivityProbe.Result.Online -> {
                    alreadyOnline = true
                    break@loop
                }
                is ConnectivityProbe.Result.Portal -> {
                    portal = r.url
                    break@loop
                }
                is ConnectivityProbe.Result.OffHours -> {
                    offHoursSeen = true
                    EventLog.add("vm", "探测到断网时段：" + r.detail)
                    fail(OUT_OF_HOURS_TEXT)
                    return@withContext
                }
                is ConnectivityProbe.Result.Unreachable -> {
                    if (i < 2) {
                        _state.update { it.copy(message = "探测失败：" + r.detail + "，重试中…") }
                        delay(800)
                    }
                }
            }
        }
        if (portal == null) {
            if (alreadyOnline) {
                WifiNetworks.unbind(app)
                timerJob?.cancel()
                EventLog.add("vm", "已经在线，不认证")
                _state.update { it.copy(phase = Phase.Online, message = "这个 Wi-Fi 已经能上网，不用认证") }
                return@withContext
            }
            if (st.campus || st.ssid == null) {
                // 探测不到跳转页时，让 WebView 自己发一个 generate_204，由网关跳转过去。
                // 直接打开 /portal/entry/pc/authenticate 会缺 flowSessionId，页面是空的。
                portal = ConnectivityProbe.PROBE_URL
                EventLog.add("vm", "探测失败，改用 WebView 触发跳转：" + portal)
                _state.update { it.copy(message = "探测不到跳转页，改由 WebView 触发跳转") }
            } else {
                fail("当前不是校园网（" + st.ssid + "），也没有认证页可打开", networkIssue = true)
                return@withContext
            }
        }

        // 4. 交给 WebView 填充
        _state.update {
            it.copy(portalUrl = portal, phase = Phase.LoadingPortal, message = "正在打开认证页…")
        }

        // 5. 等认证页走到 finish（不联网探测）
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline && !aborted) {
            delay(500)
            if (finishSeen) {
                WifiNetworks.unbind(app)
                timerJob?.cancel()
                EventLog.add("vm", "看到 finish 页，判定成功")
                _state.update { it.copy(phase = Phase.Online, message = "登录成功，已联网") }
                return@withContext
            }
        }
        if (!aborted) fail("认证超时（120 秒），可看日志判断卡在哪一步", networkIssue = true)
    }
}
