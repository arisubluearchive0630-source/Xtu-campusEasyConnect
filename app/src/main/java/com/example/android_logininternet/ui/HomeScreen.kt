package com.example.android_logininternet.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.android_logininternet.portal.PortalWebView
import com.example.android_logininternet.ui.settings.SettingsDialog
import com.example.android_logininternet.vm.LoginViewModel
import com.example.android_logininternet.wifi.WifiSsidReader

@Composable
fun HomeScreen(vm: LoginViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var diagText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshNetwork() }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        vm.refreshNetwork()
    }

    // 回到前台时刷新一次（用户可能刚在系统设置里连了 Wi-Fi 或开了权限）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshNetwork() }

    // 空闲时每 2 秒刷新 Wi-Fi 状态，连上/断开能立刻反映出来
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (!vm.state.value.running) vm.refreshNetwork()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "当前 Wi-Fi：" + state.wifiLabel,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    state.campus -> "湘潭大学校园网"
                    !state.wifiConnected -> "未连接 Wi-Fi"
                    state.ssid == null -> "已连 Wi-Fi，但读不到名称（需定位权限 + 定位开关）"
                    else -> "非校园网"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.campus) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            if (!state.hasFineLocation) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Android 13+ 读 SSID 需要定位权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            )
                        )
                    }) { Text("授权") }
                    OutlinedButton(onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    }) { Text("系统设置") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(text = state.message.ifBlank { "未运行" }, style = MaterialTheme.typography.bodyLarge)
            Text(text = "运行时长：" + state.elapsedSeconds + " 秒", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    diagText = WifiSsidReader.diagnostics(context)
                    showDiag = !showDiag
                }) { Text(if (showDiag) "收起诊断" else "诊断信息") }
                Text(
                    text = "v" + versionName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (showDiag) {
                Text(
                    text = diagText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.running) {
                    Button(onClick = { vm.stop() }) { Text("停止") }
                } else {
                    Button(onClick = { vm.start() }) { Text("启动") }
                }
                if (state.phase == LoginViewModel.Phase.Failed) {
                    OutlinedButton(onClick = { vm.start() }) { Text("重试") }
                }
                OutlinedButton(onClick = { showSettings = true }) { Text("设置") }
            }

            Spacer(Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (state.portalUrl != null) {
                    PortalWebView(
                        portalUrl = state.portalUrl.orEmpty(),
                        account = state.account,
                        password = state.password,
                        attempt = state.attempt,
                        modifier = Modifier.fillMaxSize(),
                        onUrlChanged = vm::onPageUrl,
                        onBridgeEvent = vm::onBridgeEvent
                    )
                } else {
                    Text(
                        text = "点击启动后会在这里显示认证页",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (state.pageUrl.isNotBlank()) {
                Text(
                    text = state.pageUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initialAccount = state.account,
            initialPassword = state.password,
            initialAutoConnect = state.autoConnect,
            onDismiss = { showSettings = false },
            onSave = { account, password, autoConnect ->
                vm.saveCredentials(account, password, autoConnect)
            }
        )
    }
}