package com.example.android_logininternet.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简运行日志：同时打到 logcat 和界面上的"日志"面板。
 *
 * 认证流程里的问题（页面没加载、注入脚本没跑、按钮点不动）基本只能靠现场日志判断，
 * 所以这里把每一步都记下来，用户截图就能定位。
 */
object EventLog {

    private const val TAG = "XtuAutoLogin"
    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(tag: String, msg: String) {
        val line = fmt.format(Date()) + " [" + tag + "] " + msg
        Log.i(TAG, line)
        _lines.update { old ->
            val next = old + line
            if (next.size > MAX) next.subList(next.size - MAX, next.size) else next
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
