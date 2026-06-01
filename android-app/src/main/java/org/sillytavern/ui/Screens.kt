package org.sillytavern.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sillytavern.BuildConfig
import org.sillytavern.R
import org.sillytavern.core.AppPaths
import org.sillytavern.core.AssetInstaller
import org.sillytavern.core.NodeRuntime

private const val SOURCE_URL = "https://github.com/print-yuhuan/SillyTavern"

enum class Screen { HOME, CONFIG, LOGS, DATA, ABOUT }

/**
 * 应用导航宿主（第五阶段）。控制台为浅层导航：首页是 hub，其余为叶子页，
 * 用状态切换 + BackHandler 返回首页，无需引入导航库。
 * 开屏统一在此请求通知权限并触发资产解压（幂等）。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响服务运行，仅不显示通知 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { AssetInstaller.ensureInstalled(context.applicationContext) }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onOpenConfig = { screen = Screen.CONFIG },
            onOpenLogs = { screen = Screen.LOGS },
            onOpenData = { screen = Screen.DATA },
            onOpenAbout = { screen = Screen.ABOUT },
        )
        Screen.CONFIG -> ConfigScreen { screen = Screen.HOME }
        Screen.LOGS -> LogsScreen { screen = Screen.HOME }
        Screen.DATA -> DataScreen { screen = Screen.HOME }
        Screen.ABOUT -> AboutScreen { screen = Screen.HOME }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
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
            content = content,
        )
    }
}

// ── 日志页（完整日志 + 复制 / 清空） ─────────────────────────────────────
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val logs by NodeRuntime.logs.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.logs_title), onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(NodeRuntime.logsAsText())) },
                enabled = logs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.logs_copy)) }
            OutlinedButton(
                onClick = { NodeRuntime.clearLogs() },
                enabled = logs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.logs_clear)) }
        }
        if (logs.isEmpty()) {
            Text(stringResource(R.string.logs_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectionContainer {
                Text(
                    text = logs.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

// 配置页 ConfigScreen 见 ConfigScreen.kt（第六阶段：可编辑表单，结构化写回 config.yaml）。

// ── 数据页（备份/恢复/修复占位，第八阶段实现） ───────────────────────────
@Composable
private fun DataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dataDir = remember { AppPaths(context).dataDir.absolutePath }
    DetailScaffold(title = stringResource(R.string.data_title), onBack = onBack) {
        Text(stringResource(R.string.data_intro), style = MaterialTheme.typography.bodyMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.data_dir_label), style = MaterialTheme.typography.titleSmall)
                Text(dataDir, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

// ── 关于页（版本信息 + 源码/许可证） ─────────────────────────────────────
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val info by produceState(initialValue = VersionInfo()) {
        value = withContext(Dispatchers.IO) { readVersionInfo(context) }
    }
    DetailScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoRow(stringResource(R.string.about_app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow(stringResource(R.string.about_st_version), info.stVersion)
                InfoRow(stringResource(R.string.about_node_version), info.nodeVersion)
                InfoRow(stringResource(R.string.about_abi), info.abi)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.about_license_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_license_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { openUrl(context, SOURCE_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.about_source_button)) }
            }
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private data class VersionInfo(
    val stVersion: String = "—",
    val nodeVersion: String = "—",
    val abi: String = "—",
)

private fun readVersionInfo(context: Context): VersionInfo = try {
    val o = JSONObject(context.assets.open(AssetInstaller.VERSION_ASSET).use { it.readBytes() }.decodeToString())
    val st = o.optJSONObject("sillyTavern")
    val tag = st?.optString("tag").orEmpty()
    val commit = st?.optString("commit").orEmpty()
    val node = o.optJSONObject("node")
    VersionInfo(
        stVersion = when {
            tag.isNotBlank() -> tag
            commit.isNotBlank() -> commit.take(7)
            else -> "—"
        },
        nodeVersion = node?.optString("version").orEmpty().ifBlank { "—" },
        abi = node?.optString("abi").orEmpty().ifBlank { "—" },
    )
} catch (_: Exception) {
    VersionInfo()
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
