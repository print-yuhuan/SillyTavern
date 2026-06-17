package org.sillytavern

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import org.sillytavern.core.AssetInstaller
import org.sillytavern.core.NodeRuntime
import org.sillytavern.core.ServiceState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    private var wakeLock: PowerManager.WakeLock? = null

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
        // STOPPING 期间不可启动：避免旧进程未退出就拉起新进程造成端口冲突。
        // 重启流程由调用方等待 STOPPED 后再 start（见 ConfigScreen 重启/恢复默认）。
        if (current == ServiceState.STARTING || current == ServiceState.RUNNING || current == ServiceState.STOPPING) return

        intentionalStop = false
        // 前台服务保进程不保 CPU：息屏/Doze 下 CPU 可能被挂起，导致首启 webpack 编译停滞、
        // 健康检查超时误判，或局域网访问无响应。持 PARTIAL_WAKE_LOCK 保活直到停止。
        acquireWakeLock()
        NodeRuntime.setState(ServiceState.STARTING)
        // 必须在 startForegroundService 后 5 秒内 startForeground。
        startForegroundCompat(buildNotification(ServiceState.STARTING, getString(R.string.notification_starting_title)))
        scope.launch { runNode() }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private suspend fun runNode() {
        if (!paths.nodeBinReady()) {
            NodeRuntime.appendLog("[shell] " + getString(R.string.msg_node_missing) + " -> " + paths.nodeBin.absolutePath)
            failTo(getString(R.string.notification_error_title))
            return
        }

        // libnode.so 动态依赖 libc++_shared.so；缺失则链接器在 exec 前即失败，提前给出明确提示。
        if (!paths.libcxxShared.exists()) {
            NodeRuntime.appendLog("[shell] " + getString(R.string.msg_libcxx_missing) + " -> " + paths.libcxxShared.absolutePath)
            failTo(getString(R.string.notification_error_title))
            return
        }

        // 首启/升级：解压 SillyTavern 资产（幂等，已就绪则立即返回）。
        if (!AssetInstaller.ensureInstalled(applicationContext)) {
            NodeRuntime.appendLog("[shell] " + AssetInstaller.state.value.message)
            failTo(getString(R.string.notification_error_title))
            return
        }
        if (!paths.serverReady()) {
            NodeRuntime.appendLog("[shell] " + getString(R.string.msg_assets_missing) + " -> " + paths.serverJs.absolutePath)
            failTo(getString(R.string.notification_error_title))
            return
        }

        try {
            paths.ensureRuntimeDirs()

            // 首启时 config.yaml 尚不存在，由 SillyTavern 按 --configPath 从 default/config.yaml 创建。
            val configExistedBefore = paths.configFile.exists()
            val cmd = listOf(
                paths.nodeBin.absolutePath, "server.js",
                "--configPath", paths.configFile.absolutePath,
                "--dataRoot", paths.dataDir.absolutePath,
                "--browserLaunchEnabled=false",
            )
            NodeRuntime.appendLog("[shell] exec ${cmd.joinToString(" ")} (cwd=${paths.serverDir})")
            val pb = ProcessBuilder(cmd)
                .directory(paths.serverDir)
                .redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", paths.filesDir.absolutePath)
                put("TMPDIR", paths.nodeTmpDir.absolutePath)
                // 把 nativeLibDir 前置到 PATH 而非覆盖，保留系统 PATH，
                // 以便 SillyTavern 部分功能/扩展能找到 git、ffmpeg 等子进程命令。
                val existingPath = get("PATH")
                put(
                    "PATH",
                    if (existingPath.isNullOrEmpty()) paths.nativeLibDir.absolutePath
                    else "${paths.nativeLibDir.absolutePath}:$existingPath",
                )
                // 让动态链接器在 nativeLibraryDir 找到 libnode.so 依赖的 libc++_shared.so。
                val existingLdPath = get("LD_LIBRARY_PATH")
                put(
                    "LD_LIBRARY_PATH",
                    if (existingLdPath.isNullOrEmpty()) paths.nativeLibDir.absolutePath
                    else "${paths.nativeLibDir.absolutePath}:$existingLdPath",
                )
            }
            val proc = pb.start()
            process = proc

            scope.launch { pumpOutput(proc) }
            scope.launch { awaitExit(proc) }

            // 首启新建 config 后重新读取，用真实端口/监听地址计算健康检查与局域网 URL。
            if (!configExistedBefore) awaitConfigFile(paths.configFile, proc)
            val config = configEditor.readOrNull(paths.configFile)
            val port = config?.port ?: ConfigEditor.DEFAULT_PORT
            val healthUrl = ConfigEditor.healthCheckUrl(config)

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
                // 健康检查目标恒为本机回环（见 ConfigEditor.healthCheckUrl）；SillyTavern 启用 SSL 时
                // 使用自签证书，这里仅对该回环连接放宽校验，不影响出站请求的证书验证。
                if (this is HttpsURLConnection) {
                    sslSocketFactory = trustAllSocketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
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

    /** 仅用于本机回环自签 TLS 健康检查的「信任全部」工厂（连接目标已限定回环，故安全）。 */
    private val trustAllSocketFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }.socketFactory
    }

    /** 轮询等待 config.yaml 出现（首启由 SillyTavern 按 --configPath 新建），最多 CONFIG_WAIT_MS。 */
    private suspend fun awaitConfigFile(file: File, proc: Process) {
        val deadline = SystemClock.elapsedRealtime() + CONFIG_WAIT_MS
        while (scope.isActive && SystemClock.elapsedRealtime() < deadline) {
            if (file.exists() || !proc.isAlive || intentionalStop) return
            delay(200)
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
        releaseWakeLock()

        NodeRuntime.setState(ServiceState.STOPPED)
        NodeRuntime.appendLog("[shell] 服务已停止")
        stopForegroundCompat()
        stopSelf()
    }

    private fun failTo(title: String) {
        NodeRuntime.setState(ServiceState.ERROR)
        process = null
        releaseWakeLock()
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
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "node_service"
        private const val NOTIF_ID = 1001
        private const val WAKELOCK_TAG = "SillyTavern:NodeService"

        const val ACTION_START = "org.sillytavern.action.START"
        const val ACTION_STOP = "org.sillytavern.action.STOP"

        // 首启时 SillyTavern 会在手机上用 webpack 编译前端库（公共 lib.js），耗时明显高于桌面；
        // 放宽到 180s，避免首启被误判失败。编译结果缓存在 dataRoot/_webpack，后续启动很快。
        private const val HEALTH_TIMEOUT_MS = 180_000L
        private const val HEALTH_INTERVAL_MS = 500L
        private const val FORCE_KILL_AFTER_MS = 4_000L
        private const val CONFIG_WAIT_MS = 15_000L

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
