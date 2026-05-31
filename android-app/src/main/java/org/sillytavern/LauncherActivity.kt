package org.sillytavern

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.sillytavern.core.NodeRuntime
import org.sillytavern.core.ServiceState
import org.sillytavern.ui.theme.SillyTavernTheme
import org.sillytavern.ui.theme.status_error
import org.sillytavern.ui.theme.status_running
import org.sillytavern.ui.theme.status_starting
import org.sillytavern.ui.theme.status_stopped

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SillyTavernTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val state by NodeRuntime.state.collectAsStateWithLifecycle()
    val url by NodeRuntime.url.collectAsStateWithLifecycle()
    val lanUrl by NodeRuntime.lanUrl.collectAsStateWithLifecycle()
    val runningSince by NodeRuntime.runningSince.collectAsStateWithLifecycle()
    val logs by NodeRuntime.logs.collectAsStateWithLifecycle()

    // 运行时通知权限请求（API 33+）。launcher 无条件创建以保证组合结构稳定，仅在需要时发起请求。
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响服务运行，仅不显示通知 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(
                        onClick = { /* 设置页（第五阶段） */ },
                        modifier = Modifier.semantics { contentDescription = "设置" },
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            M0Banner()
            StatusCard(state = state, url = url, lanUrl = lanUrl, runningSince = runningSince)
            ActionButtons(
                state = state,
                onStart = { NodeService.start(context) },
                onStop = { NodeService.stop(context) },
                onOpenWeb = { openWeb(context, url) },
            )
            LogsCard(
                logs = logs,
                onCopy = {
                    clipboard.setText(AnnotatedString(NodeRuntime.logsAsText()))
                },
                onClear = { NodeRuntime.clearLogs() },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun M0Banner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.m0_banner),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun StatusCard(state: ServiceState, url: String?, lanUrl: String?, runningSince: Long?) {
    val (label, color) = state.labelAndColor()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                        .semantics { contentDescription = "状态指示：$label" },
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "${stringResource(R.string.home_status_label)}：$label",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = "${stringResource(R.string.home_address_label)}：${url ?: stringResource(R.string.home_no_address)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (lanUrl != null) {
                Text(text = "局域网：$lanUrl", style = MaterialTheme.typography.bodyMedium)
            }
            UptimeText(state = state, runningSince = runningSince)
        }
    }
}

@Composable
private fun UptimeText(state: ServiceState, runningSince: Long?) {
    val uptime by produceState(initialValue = "00:00:00", state, runningSince) {
        if (state == ServiceState.RUNNING && runningSince != null) {
            while (true) {
                val elapsed = (SystemClock.elapsedRealtime() - runningSince).coerceAtLeast(0)
                value = formatUptime(elapsed)
                delay(1000)
            }
        } else {
            value = "00:00:00"
        }
    }
    Text(
        text = "${stringResource(R.string.home_uptime_label)}：$uptime",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ActionButtons(
    state: ServiceState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val isRunning = state == ServiceState.RUNNING
    val isStarting = state == ServiceState.STARTING
    val isStopping = state == ServiceState.STOPPING
    val canStart = state == ServiceState.STOPPED || state == ServiceState.ERROR

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isRunning || isStarting || isStopping) {
            Button(
                onClick = onStop,
                enabled = !isStopping,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "停止 SillyTavern 本地服务" },
                colors = ButtonDefaults.buttonColors(containerColor = status_error),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_stop))
            }
        } else {
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "启动 SillyTavern 本地服务" },
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_start))
            }
        }

        OutlinedButton(
            onClick = onOpenWeb,
            enabled = isRunning,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "在内置浏览器中打开 SillyTavern 界面" },
        ) {
            if (isStarting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
            }
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_open_web))
        }
    }
}

@Composable
private fun LogsCard(logs: List<String>, onCopy: () -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.home_recent_logs), style = MaterialTheme.typography.titleSmall)
                }
                Row {
                    OutlinedButton(onClick = onCopy, enabled = logs.isNotEmpty()) {
                        Text(stringResource(R.string.logs_copy))
                    }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                        Text(stringResource(R.string.logs_clear))
                    }
                }
            }
            if (logs.isEmpty()) {
                Text(stringResource(R.string.logs_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 仅渲染最近 300 行，避免超长日志拖慢 UI
                        val tail = if (logs.size > 300) logs.subList(logs.size - 300, logs.size) else logs
                        Text(
                            text = tail.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

private fun ServiceState.labelAndColor(): Pair<String, Color> = when (this) {
    ServiceState.STOPPED -> "已停止" to status_stopped
    ServiceState.STARTING -> "启动中" to status_starting
    ServiceState.RUNNING -> "运行中" to status_running
    ServiceState.STOPPING -> "停止中" to status_starting
    ServiceState.ERROR -> "出错" to status_error
}

private fun formatUptime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun openWeb(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    context.startActivity(
        Intent(context, WebViewActivity::class.java)
            .putExtra(WebViewActivity.EXTRA_URL, url),
    )
}
