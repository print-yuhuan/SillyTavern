package org.sillytavern.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.sillytavern.NodeService
import org.sillytavern.R
import org.sillytavern.WebViewActivity
import org.sillytavern.core.AssetInstaller
import org.sillytavern.core.NodeRuntime
import org.sillytavern.core.ServiceState
import org.sillytavern.ui.theme.status_error
import org.sillytavern.ui.theme.status_running
import org.sillytavern.ui.theme.status_starting
import org.sillytavern.ui.theme.status_stopped

/**
 * 首页（控制台 hub，实现方案 §6）：服务状态、启动/停止、打开界面、当前 URL、
 * 运行时长、最近日志摘要，以及到配置/日志/数据/关于的二级入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenConfig: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val state by NodeRuntime.state.collectAsStateWithLifecycle()
    val url by NodeRuntime.url.collectAsStateWithLifecycle()
    val lanUrl by NodeRuntime.lanUrl.collectAsStateWithLifecycle()
    val runningSince by NodeRuntime.runningSince.collectAsStateWithLifecycle()
    val logs by NodeRuntime.logs.collectAsStateWithLifecycle()
    val installState by AssetInstaller.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InstallBanner(installState)
            StatusCard(state = state, url = url, lanUrl = lanUrl, runningSince = runningSince)
            ActionButtons(
                state = state,
                installing = installState.phase == AssetInstaller.Phase.EXTRACTING,
                onStart = { NodeService.start(context) },
                onStop = { NodeService.stop(context) },
                onOpenWeb = { openWeb(context, url) },
            )
            SecondaryActions(
                onOpenConfig = onOpenConfig,
                onOpenLogs = onOpenLogs,
                onOpenData = onOpenData,
                onOpenAbout = onOpenAbout,
            )
            RecentLogsCard(logs = logs, onOpenLogs = onOpenLogs)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InstallBanner(state: AssetInstaller.InstallState) {
    when (state.phase) {
        AssetInstaller.Phase.EXTRACTING -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.install_preparing), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.filesExtracted > 0) {
                    Text(
                        text = stringResource(R.string.install_files_extracted, state.filesExtracted),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        AssetInstaller.Phase.ERROR -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.message.ifBlank { stringResource(R.string.install_failed) },
                style = MaterialTheme.typography.bodyMedium,
                color = status_error,
                modifier = Modifier.padding(12.dp),
            )
        }
        else -> Unit
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
    installing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val isRunning = state == ServiceState.RUNNING
    val isStarting = state == ServiceState.STARTING
    val isStopping = state == ServiceState.STOPPING
    // 资产解压期间禁用启动，避免在资产未就绪时拉起服务。
    val canStart = (state == ServiceState.STOPPED || state == ServiceState.ERROR) && !installing

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
private fun SecondaryActions(
    onOpenConfig: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NavTile(Modifier.weight(1f), Icons.Filled.Tune, stringResource(R.string.action_edit_config), onOpenConfig)
            NavTile(Modifier.weight(1f), Icons.Filled.Description, stringResource(R.string.action_view_logs), onOpenLogs)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NavTile(Modifier.weight(1f), Icons.Filled.Backup, stringResource(R.string.action_backup), onOpenData)
            NavTile(Modifier.weight(1f), Icons.Filled.Info, stringResource(R.string.action_about), onOpenAbout)
        }
    }
}

@Composable
private fun NavTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun RecentLogsCard(logs: List<String>, onOpenLogs: () -> Unit) {
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
                TextButton(onClick = onOpenLogs) { Text(stringResource(R.string.home_view_full_logs)) }
            }
            if (logs.isEmpty()) {
                Text(stringResource(R.string.logs_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                val tail = if (logs.size > 6) logs.subList(logs.size - 6, logs.size) else logs
                Text(
                    text = tail.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
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
