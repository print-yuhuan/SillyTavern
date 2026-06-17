package org.sillytavern.ui

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sillytavern.NodeService
import org.sillytavern.R
import org.sillytavern.core.AppPaths
import org.sillytavern.core.BackupManager
import org.sillytavern.core.NodeRuntime
import org.sillytavern.core.ServiceState
import org.sillytavern.ui.theme.status_error
import org.sillytavern.ui.theme.status_running
import java.io.File

private data class StorageSizes(val data: Long = -1, val config: Long = -1, val cache: Long = -1)

/**
 * 数据页（第八阶段）：备份 / 恢复 / 修复 / 清理缓存。
 *
 * - 备份/恢复经 SAF 单文件 zip（[BackupManager]），只动 config/ 与 data/。
 * - 恢复/修复/清理均为破坏性或涉及运行文件的操作：若服务在运行，确认后先停服再执行。
 * - 文案与确认弹窗全部简体中文；状态不仅靠颜色，配合图标与文本表达。
 */
@Composable
fun DataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paths = remember { AppPaths(context) }

    val serviceState by NodeRuntime.state.collectAsStateWithLifecycle()
    // 运行中（含启动/停止过渡态）：破坏性操作前需先停服并等待真正 STOPPED。
    val running = serviceState != ServiceState.STOPPED && serviceState != ServiceState.ERROR
    val op by BackupManager.state.collectAsStateWithLifecycle()
    val busy = op.phase == BackupManager.Phase.RUNNING

    // 进入数据页时清掉上次遗留的「已完成/失败」横幅（进行中则保留）。
    LaunchedEffect(Unit) { BackupManager.resetIfIdle() }

    // 存储占用：每次操作结束（op.phase 变化）重新统计。
    val sizes by produceState(initialValue = StorageSizes(), op.phase) {
        value = withContext(Dispatchers.IO) {
            StorageSizes(
                data = BackupManager.dirSize(paths.dataDir),
                config = BackupManager.dirSize(paths.configDir),
                cache = BackupManager.dirSize(paths.nodeTmpDir) +
                    BackupManager.DATA_REGENERABLE.sumOf { BackupManager.dirSize(File(paths.dataDir, it)) },
            )
        }
    }
    val computed = sizes.data >= 0L
    val hasData = (sizes.data > 0) || (sizes.config > 0)

    var pendingExport by remember { mutableStateOf<Uri?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var showRepair by remember { mutableStateOf(false) }
    var showClean by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri ->
        if (uri != null) {
            // 运行中导出可能打入正在写入的文件：先确认并停服，保证备份一致。
            if (running) pendingExport = uri
            else scope.launch { BackupManager.exportBackup(context, uri) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    // 破坏性/涉运行文件的操作统一封装：运行中先停服并等待真正 STOPPED 再执行；
    // 超时则中止操作，避免与仍在运行的 Node 并发读写造成数据损坏。
    fun runStopped(block: suspend () -> Unit) {
        scope.launch {
            val s = NodeRuntime.state.value
            if (s != ServiceState.STOPPED && s != ServiceState.ERROR) {
                if (s != ServiceState.STOPPING) NodeService.stop(context)
                val reached = withTimeoutOrNull(15_000) {
                    NodeRuntime.state.first { it == ServiceState.STOPPED || it == ServiceState.ERROR }
                }
                if (reached == null) {
                    Toast.makeText(context, R.string.data_stop_timeout, Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            block()
        }
    }

    DetailScaffold(title = stringResource(R.string.data_title), onBack = onBack) {
        Text(stringResource(R.string.data_intro), style = MaterialTheme.typography.bodyMedium)

        OpBanner(op)

        // ── 存储占用 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(R.string.data_storage_title))
                InfoRow(stringResource(R.string.data_size_data), sizeText(context, sizes.data))
                InfoRow(stringResource(R.string.data_size_config), sizeText(context, sizes.config))
                InfoRow(stringResource(R.string.data_size_cache), sizeText(context, sizes.cache))
                Text(
                    text = paths.dataDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // ── 备份与恢复 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.data_backup_title))
                Text(stringResource(R.string.data_backup_desc), style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { exportLauncher.launch(BackupManager.defaultBackupName()) },
                    enabled = !busy && hasData,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_export))
                }
                if (computed && !hasData) {
                    Text(
                        text = stringResource(R.string.data_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                OutlinedButton(
                    onClick = { runCatching { importLauncher.launch(IMPORT_MIME) } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_import))
                }
            }
        }

        // ── 维护 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.data_maintenance_title))
                Text(stringResource(R.string.data_repair_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { showRepair = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_repair))
                }
                Text(stringResource(R.string.data_clean_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { showClean = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_clean_cache))
                }
            }
        }

        Spacer(Modifier.size(8.dp))
    }

    // ── 导出确认（运行中：先停服保证一致） ──
    pendingExport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.data_export),
            message = stringResource(R.string.data_export_stop_hint),
            onConfirm = {
                pendingExport = null
                runStopped { BackupManager.exportBackup(context, uri) }
            },
            onDismiss = { pendingExport = null },
        )
    }

    // ── 恢复确认（破坏性） ──
    pendingImport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.data_restore_confirm_title),
            message = stringResource(R.string.data_restore_confirm_msg) +
                if (running) "\n\n" + stringResource(R.string.data_stop_hint) else "",
            onConfirm = {
                pendingImport = null
                runStopped { BackupManager.importBackup(context, uri) }
            },
            onDismiss = { pendingImport = null },
        )
    }

    // ── 修复确认 ──
    if (showRepair) {
        ConfirmDialog(
            title = stringResource(R.string.data_repair_confirm_title),
            message = stringResource(R.string.data_repair_confirm_msg) +
                if (running) "\n\n" + stringResource(R.string.data_stop_hint) else "",
            onConfirm = {
                showRepair = false
                runStopped { BackupManager.repairServer(context) }
            },
            onDismiss = { showRepair = false },
        )
    }

    // ── 清理缓存确认 ──
    if (showClean) {
        ConfirmDialog(
            title = stringResource(R.string.data_clean_confirm_title),
            message = stringResource(R.string.data_clean_confirm_msg) +
                if (running) "\n\n" + stringResource(R.string.data_stop_hint) else "",
            onConfirm = {
                showClean = false
                runStopped { BackupManager.cleanCache(context) }
            },
            onDismiss = { showClean = false },
        )
    }
}

@Composable
private fun OpBanner(op: BackupManager.OpState) {
    val context = LocalContext.current
    when (op.phase) {
        BackupManager.Phase.RUNNING -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(runningMessage(op.op), style = MaterialTheme.typography.bodyMedium)
            }
        }
        BackupManager.Phase.DONE -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = doneMessage(context, op),
                style = MaterialTheme.typography.bodyMedium,
                color = status_running,
                modifier = Modifier.padding(16.dp),
            )
        }
        BackupManager.Phase.ERROR -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = errorMessage(op.op),
                style = MaterialTheme.typography.bodyMedium,
                color = status_error,
                modifier = Modifier.padding(16.dp),
            )
        }
        BackupManager.Phase.IDLE -> Unit
    }
}

@Composable
private fun runningMessage(op: BackupManager.Op?): String = stringResource(
    when (op) {
        BackupManager.Op.BACKUP -> R.string.data_op_backing_up
        BackupManager.Op.RESTORE -> R.string.data_op_restoring
        BackupManager.Op.CLEAN -> R.string.data_op_cleaning
        BackupManager.Op.REPAIR -> R.string.data_op_repairing
        null -> R.string.data_op_backing_up
    },
)

@Composable
private fun doneMessage(context: Context, op: BackupManager.OpState): String = when (op.op) {
    BackupManager.Op.BACKUP -> stringResource(R.string.data_backup_done)
    BackupManager.Op.RESTORE -> stringResource(R.string.data_restore_done)
    BackupManager.Op.CLEAN -> stringResource(R.string.data_clean_done, Formatter.formatShortFileSize(context, op.bytesFreed))
    BackupManager.Op.REPAIR -> stringResource(R.string.data_repair_done)
    null -> ""
}

@Composable
private fun errorMessage(op: BackupManager.Op?): String = stringResource(
    when (op) {
        BackupManager.Op.BACKUP -> R.string.data_backup_failed
        BackupManager.Op.RESTORE -> R.string.data_restore_failed
        BackupManager.Op.CLEAN -> R.string.data_clean_failed
        BackupManager.Op.REPAIR -> R.string.data_repair_failed
        null -> R.string.data_backup_failed
    },
)

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun sizeText(context: Context, bytes: Long): String =
    if (bytes < 0) context.getString(R.string.data_size_calculating)
    else Formatter.formatShortFileSize(context, bytes)

private const val BACKUP_MIME = "application/zip"
private val IMPORT_MIME = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
