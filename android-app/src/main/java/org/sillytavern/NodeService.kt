package org.sillytavern

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sillytavern.config.ConfigEditor
import org.sillytavern.core.AppPaths
import org.sillytavern.core.NodeRuntime
import org.sillytavern.core.ServiceState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * 前台服务：管理内置 Node 进程的生命周期（实现方案 §5）。
 *
 * M0 阶段运行最小测试服务 assets/m0-server.js，验证「真机能否从 nativeLibraryDir 执行内置 Node」。
 * 第四阶段起改为以 server.js + --configPath + --dataRoot 启动完整 SillyTavern。
 */
class NodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null
    @Volatile private var intentionalStop = false

    private lateinit var paths: AppPaths
    private val configEditor = ConfigEditor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        paths = AppPaths(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> scope.launch { stopNode() }
            else -> {
                // 系统重启服务且无明确动作时不自动拉起 Node，交由用户控制。
                if (NodeRuntime.state.value == ServiceState.STOPPED) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val current = NodeRuntime.state.value
        if (current == ServiceState.STARTING || current == ServiceState.RUNNING) return

        intentionalStop = false
        NodeRuntime.setState(ServiceState.STARTING)
        // 必须在 startForegroundService 后 5 秒内 startForeground。
        startForegroundCompat(buildNotification(ServiceState.STARTING, getString(R.string.notification_starting_title)))
        scope.launch { runNode() }
    }

    private suspend fun runNode() {
        if (!paths.nodeBinReady()) {
            NodeRuntime.appendLog("[shell] " + getString(R.string.msg_node_missing) + " -> " + paths.nodeBin.absolutePath)
            failTo(getString(R.string.notification_error_title))
            return
        }

        val config = configEditor.readOrNull(paths.configFile)
        val port = config?.port ?: ConfigEditor.DEFAULT_PORT
        val healthUrl = ConfigEditor.healthCheckUrl(config)

        try {
            prepareM0Assets()
            paths.nodeTmpDir.mkdirs()

            NodeRuntime.appendLog("[shell] exec ${paths.nodeBin.absolutePath} server.js (cwd=${paths.m0Dir})")
            val pb = ProcessBuilder(paths.nodeBin.absolutePath, "server.js")
                .directory(paths.m0Dir)
                .redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", paths.filesDir.absolutePath)
                put("TMPDIR", paths.nodeTmpDir.absolutePath)
                put("PATH", paths.nativeLibDir.absolutePath)
                put("ST_PORT", port.toString())
            }
            val proc = pb.start()
            process = proc

            scope.launch { pumpOutput(proc) }
            scope.launch { awaitExit(proc) }

            // 健康检查：轮询直到 200，或进程提前退出 / 超时。
            val running = pollHealth(healthUrl, proc)
            if (running) {
                val lanUrl = if (ConfigEditor.lanAccessEnabled(config)) lanIpv4()?.let {
                    val scheme = healthUrl.substringBefore("://")
                    "$scheme://$it:$port"
                } else null
                NodeRuntime.setUrls(healthUrl, lanUrl)
                NodeRuntime.markRunning(SystemClock.elapsedRealtime())
                updateNotification(buildNotification(ServiceState.RUNNING, getString(R.string.notification_address_format, healthUrl)))
            } else if (!intentionalStop) {
                failTo(getString(R.string.notification_error_title))
            }
        } catch (e: Exception) {
            NodeRuntime.appendLog("[shell] start failed: ${e.message}")
            if (!intentionalStop) failTo(getString(R.string.notification_error_title))
        }
    }

    /** 读取进程合并后的 stdout/stderr，写入日志。 */
    private fun pumpOutput(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    NodeRuntime.appendLog(line!!)
                }
            }
        } catch (_: Exception) {
            // 进程结束时流关闭会抛异常，忽略。
        }
    }

    /** 等待进程退出。非用户主动停止时，退出视为错误。 */
    private fun awaitExit(proc: Process) {
        val code = try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            return
        }
        NodeRuntime.appendLog("[shell] node 进程已退出，退出码=$code")
        if (!intentionalStop) {
            failTo(getString(R.string.notification_error_title))
        }
    }

    private suspend fun pollHealth(url: String, proc: Process): Boolean {
        val deadline = SystemClock.elapsedRealtime() + HEALTH_TIMEOUT_MS
        while (scope.isActive && SystemClock.elapsedRealtime() < deadline) {
            if (!proc.isAlive) return false
            if (intentionalStop) return false
            if (httpOk(url)) return true
            delay(HEALTH_INTERVAL_MS)
        }
        return httpOk(url)
    }

    private fun httpOk(url: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                code in 200..499 // 服务起来即可（401/403 等也说明已监听）
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareM0Assets() {
        paths.m0Dir.mkdirs()
        assets.open("m0-server.js").use { input ->
            paths.m0ServerJs.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private suspend fun stopNode() {
        intentionalStop = true
        NodeRuntime.setState(ServiceState.STOPPING)
        updateNotification(buildNotification(ServiceState.STOPPING, getString(R.string.notification_stopping_title)))

        val proc = process
        if (proc != null) {
            proc.destroy()
            var waited = 0L
            while (proc.isAlive && waited < FORCE_KILL_AFTER_MS) {
                delay(100)
                waited += 100
            }
            if (proc.isAlive) proc.destroyForcibly()
        }
        process = null

        NodeRuntime.setState(ServiceState.STOPPED)
        NodeRuntime.appendLog("[shell] 服务已停止")
        stopForegroundCompat()
        stopSelf()
    }

    private fun failTo(title: String) {
        NodeRuntime.setState(ServiceState.ERROR)
        process = null
        updateNotification(buildNotification(ServiceState.ERROR, title))
        // 错误状态保留通知供用户查看，但不再保活前台。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    // ── 通知 ──────────────────────────────────────────────────────────────

    private fun buildNotification(state: ServiceState, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (state) {
            ServiceState.STARTING -> getString(R.string.notification_starting_title)
            ServiceState.RUNNING -> getString(R.string.notification_running_title)
            ServiceState.STOPPING -> getString(R.string.notification_stopping_title)
            ServiceState.ERROR -> getString(R.string.notification_error_title)
            ServiceState.STOPPED -> getString(R.string.notification_stopped_title)
        }
        val ongoing = state == ServiceState.STARTING || state == ServiceState.RUNNING

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_service)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (ongoing) {
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, NodeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.notification_action_stop), stopIntent)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** 返回首个非回环、站点本地的 IPv4 地址（用于展示局域网访问地址）。 */
    private fun lanIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "node_service"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "org.sillytavern.action.START"
        const val ACTION_STOP = "org.sillytavern.action.STOP"

        private const val HEALTH_TIMEOUT_MS = 60_000L
        private const val HEALTH_INTERVAL_MS = 500L
        private const val FORCE_KILL_AFTER_MS = 4_000L

        fun start(context: Context) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NodeService::class.java).setAction(ACTION_STOP))
        }
    }
}
