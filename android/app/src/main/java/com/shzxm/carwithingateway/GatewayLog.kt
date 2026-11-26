package com.shzxm.carwithingateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GatewayRuntimeState(
    val running: Boolean = false,
    val statusText: String = "已停止",
    val currentUpstream: String = "未探测",
)

object GatewayLog {
    private const val MAX_LINES = 200
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _logsFlow = MutableStateFlow("等待启动...\n")
    val logsFlow: StateFlow<String> = _logsFlow

    private val _stateFlow = MutableStateFlow(GatewayRuntimeState())
    val stateFlow: StateFlow<GatewayRuntimeState> = _stateFlow

    fun append(msg: String) {
        val ts = timeFmt.format(Date())
        synchronized(lock) {
            if (lines.size >= MAX_LINES) {
                lines.removeFirst()
            }
            lines.addLast("[$ts] $msg")
            _logsFlow.value = lines.joinToString("\n") + "\n"
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            _logsFlow.value = ""
        }
    }

    fun updateState(
        running: Boolean = _stateFlow.value.running,
        statusText: String = _stateFlow.value.statusText,
        currentUpstream: String = _stateFlow.value.currentUpstream,
    ) {
        _stateFlow.value = GatewayRuntimeState(
            running = running,
            statusText = statusText,
            currentUpstream = currentUpstream,
        )
    }
}
