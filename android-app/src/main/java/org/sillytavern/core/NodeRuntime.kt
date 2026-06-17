package org.sillytavern.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级共享运行状态。NodeService 写入，UI 读取（StateFlow）。
 *
 * 采用单例而非 Binder：本应用只有一个 Node 进程，UI 与服务在同一进程，
 * 共享 StateFlow 足够，且省去绑定/解绑的复杂度。
 */
object NodeRuntime {

    private const val MAX_LOG_LINES = 2000
    private const val LOG_FLUSH_INTERVAL_MS = 150L

    /** 内部环形缓冲：O(1) 追加/淘汰，避免原先每行 `_logs.value + line` 的整表复制。 */
    private val logBuffer = ArrayDeque<String>(MAX_LOG_LINES)
    private val logLock = Any()
    private val logScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val flushScheduled = AtomicBoolean(false)

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

    fun appendLog(line: String) {
        synchronized(logLock) {
            if (logBuffer.size >= MAX_LOG_LINES) logBuffer.removeFirst()
            logBuffer.addLast(line)
        }
        scheduleLogFlush()
    }

    /** 合并 [LOG_FLUSH_INTERVAL_MS] 内的多行日志为一次发射，避免首启海量日志逐行触发 UI 重组。 */
    private fun scheduleLogFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        logScope.launch {
            delay(LOG_FLUSH_INTERVAL_MS)
            flushScheduled.set(false) // 先复位，确保期间新到的行会再触发一次刷新
            _logs.value = synchronized(logLock) { logBuffer.toList() }
        }
    }

    fun clearLogs() {
        synchronized(logLock) { logBuffer.clear() }
        _logs.value = emptyList()
    }

    fun logsAsText(): String = synchronized(logLock) { logBuffer.joinToString("\n") }
}
