package org.sillytavern.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级共享运行状态。NodeService 写入，UI 读取（StateFlow）。
 *
 * 采用单例而非 Binder：本应用只有一个 Node 进程，UI 与服务在同一进程，
 * 共享 StateFlow 足够，且省去绑定/解绑的复杂度。
 */
object NodeRuntime {

    private const val MAX_LOG_LINES = 2000

    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    /** 当前可在 WebView / 浏览器打开的健康检查地址，未就绪时为 null。 */
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    /** 局域网可访问地址（仅当允许局域网访问时展示给其他设备），否则 null。 */
    private val _lanUrl = MutableStateFlow<String?>(null)
    val lanUrl: StateFlow<String?> = _lanUrl.asStateFlow()

    /** 进入 Running 的时刻（SystemClock.elapsedRealtime），用于计算运行时长。 */
    private val _runningSince = MutableStateFlow<Long?>(null)
    val runningSince: StateFlow<Long?> = _runningSince.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Synchronized
    fun setState(state: ServiceState) {
        _state.value = state
        if (state != ServiceState.RUNNING && state != ServiceState.STARTING) {
            _runningSince.value = null
        }
        if (state == ServiceState.STOPPED || state == ServiceState.ERROR) {
            _url.value = null
            _lanUrl.value = null
        }
    }

    fun setUrls(url: String?, lanUrl: String?) {
        _url.value = url
        _lanUrl.value = lanUrl
    }

    fun markRunning(since: Long) {
        _runningSince.value = since
        _state.value = ServiceState.RUNNING
    }

    @Synchronized
    fun appendLog(line: String) {
        val next = _logs.value + line
        _logs.value = if (next.size > MAX_LOG_LINES) {
            next.subList(next.size - MAX_LOG_LINES, next.size)
        } else {
            next
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun logsAsText(): String = _logs.value.joinToString("\n")
}
