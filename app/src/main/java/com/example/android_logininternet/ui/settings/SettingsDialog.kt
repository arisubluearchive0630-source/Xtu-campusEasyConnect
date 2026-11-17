package com.example.android_logininternet.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** 设置弹窗：手填学号 / 密码，可选「启动时自动连接」。 */
@Composable
fun SettingsDialog(
    initialAccount: String,
    initialPassword: String,
    initialAutoConnect: Boolean,
    onDismiss: () -> Unit,
    onSave: (account: String, password: String, autoConnect: Boolean) -> Unit
) {
    var account by remember { mutableStateOf(initialAccount) }
    var password by remember { mutableStateOf(initialPassword) }
    var autoConnect by remember { mutableStateOf(initialAutoConnect) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录设置") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("学号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                    Spacer(Modifier.height(0.dp))
                    Text("  启动时自动连接")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(account.trim(), password, autoConnect)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
