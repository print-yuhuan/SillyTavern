package org.sillytavern.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sillytavern.NodeService
import org.sillytavern.R
import org.sillytavern.config.ConfigEditor
import org.sillytavern.core.AppPaths
import org.sillytavern.core.NodeRuntime
import org.sillytavern.core.ServiceState

private enum class Danger { CORS, CSRF }

/**
 * 配置页（第六阶段）：UI 接管 config.yaml 的可编辑表单。
 * 分四档（基础/高级/危险/App 管理）；保存经 [ConfigEditor.write] 结构化写回、保留未知字段、原子写入；
 * 运行中保存提示重启;「恢复默认」删除配置交由 SillyTavern 重建。
 */
@Composable
fun ConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paths = remember { AppPaths(context) }

    val serviceState by NodeRuntime.state.collectAsStateWithLifecycle()
    val running = serviceState == ServiceState.RUNNING || serviceState == ServiceState.STARTING

    var form by remember { mutableStateOf<ConfigEditor.STConfig?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("") }
    var heartbeatText by remember { mutableStateOf("") }
    var sessionText by remember { mutableStateOf("") }
    var whitelistText by remember { mutableStateOf("") }
    var bypassText by remember { mutableStateOf("") }

    var advancedExpanded by remember { mutableStateOf(false) }
    var dangerExpanded by remember { mutableStateOf(false) }
    var pendingDanger by remember { mutableStateOf<Danger?>(null) }
    var showRestart by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }

    fun seed(cfg: ConfigEditor.STConfig) {
        form = cfg
        portText = cfg.port.toString()
        heartbeatText = cfg.heartbeatInterval.toString()
        sessionText = cfg.sessionTimeout.toString()
        whitelistText = cfg.whitelist.joinToString("\n")
        bypassText = cfg.requestProxyBypass.joinToString("\n")
    }

    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) { ConfigEditor().readOrNull(paths.configFile) }
        if (cfg != null) seed(cfg)
        loaded = true
    }

    DetailScaffold(title = stringResource(R.string.config_title), onBack = onBack) {
        val f = form
        if (f == null) {
            if (loaded) {
                Text(stringResource(R.string.cfg_not_generated), style = MaterialTheme.typography.bodyMedium)
            }
            return@DetailScaffold
        }

        // ── 校验 ──
        val port = portText.trim().toIntOrNull()
        val portError = port == null || port !in 1..65535
        val ipv4Error = f.listen && !ConfigEditor.isValidIpv4(f.listenAddressIpv4.trim())
        val basicAuthError = f.basicAuthMode && (f.basicAuthUsername.isBlank() || f.basicAuthPassword.isBlank())
        val valid = !portError && !ipv4Error && !basicAuthError

        // ── 基础 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.cfg_section_basic))
                FormField(
                    label = stringResource(R.string.cfg_port),
                    value = portText,
                    onChange = { portText = it },
                    numeric = true,
                    isError = portError,
                    supporting = if (portError) stringResource(R.string.cfg_port_error) else null,
                )
                SwitchRow(stringResource(R.string.cfg_lan), f.listen) { form = f.copy(listen = it) }
                FormField(
                    label = stringResource(R.string.cfg_ipv4_addr),
                    value = f.listenAddressIpv4,
                    onChange = { form = f.copy(listenAddressIpv4 = it) },
                    enabled = f.listen,
                    isError = ipv4Error,
                    supporting = if (ipv4Error) stringResource(R.string.cfg_ipv4_error) else null,
                )
                SwitchRow(stringResource(R.string.cfg_ipv4), f.protocolIpv4) { form = f.copy(protocolIpv4 = it) }
                SwitchRow(stringResource(R.string.cfg_ipv6), f.protocolIpv6) { form = f.copy(protocolIpv6 = it) }
                SwitchRow(stringResource(R.string.cfg_https), f.sslEnabled) { form = f.copy(sslEnabled = it) }
                SwitchRow(stringResource(R.string.cfg_whitelist), f.whitelistMode) { form = f.copy(whitelistMode = it) }
                SwitchRow(stringResource(R.string.cfg_basic_auth), f.basicAuthMode) { form = f.copy(basicAuthMode = it) }
                if (f.basicAuthMode) {
                    FormField(
                        label = stringResource(R.string.cfg_basic_user),
                        value = f.basicAuthUsername,
                        onChange = { form = f.copy(basicAuthUsername = it) },
                        isError = f.basicAuthUsername.isBlank(),
                    )
                    FormField(
                        label = stringResource(R.string.cfg_basic_pass),
                        value = f.basicAuthPassword,
                        onChange = { form = f.copy(basicAuthPassword = it) },
                        password = true,
                        isError = f.basicAuthPassword.isBlank(),
                        supporting = if (basicAuthError) stringResource(R.string.cfg_basic_auth_error) else null,
                    )
                }
            }
        }

        // ── 高级 ──
        ExpandableSection(stringResource(R.string.cfg_section_advanced), advancedExpanded, { advancedExpanded = !advancedExpanded }) {
            FormField(stringResource(R.string.cfg_ipv6_addr), f.listenAddressIpv6, { form = f.copy(listenAddressIpv6 = it) }, enabled = f.protocolIpv6)
            SwitchRow(stringResource(R.string.cfg_dns_ipv6), f.dnsPreferIPv6) { form = f.copy(dnsPreferIPv6 = it) }
            SwitchRow(stringResource(R.string.cfg_keepalive), f.enableKeepAlive) { form = f.copy(enableKeepAlive = it) }
            FormField(stringResource(R.string.cfg_heartbeat), heartbeatText, { heartbeatText = it }, numeric = true, supporting = stringResource(R.string.cfg_heartbeat_helper))
            FormField(stringResource(R.string.cfg_session_timeout), sessionText, { sessionText = it }, numeric = true, supporting = stringResource(R.string.cfg_session_helper))
            MultilineField(stringResource(R.string.cfg_whitelist_list), whitelistText, { whitelistText = it }, stringResource(R.string.cfg_list_helper))
            SwitchRow(stringResource(R.string.cfg_https), f.sslEnabled) { form = f.copy(sslEnabled = it) }
            if (f.sslEnabled) {
                FormField(stringResource(R.string.cfg_ssl_cert), f.sslCertPath, { form = f.copy(sslCertPath = it) })
                FormField(stringResource(R.string.cfg_ssl_key), f.sslKeyPath, { form = f.copy(sslKeyPath = it) })
                FormField(stringResource(R.string.cfg_ssl_pass), f.sslKeyPassphrase, { form = f.copy(sslKeyPassphrase = it) }, password = true)
            }
            SwitchRow(stringResource(R.string.cfg_proxy_enabled), f.requestProxyEnabled) { form = f.copy(requestProxyEnabled = it) }
            if (f.requestProxyEnabled) {
                FormField(stringResource(R.string.cfg_proxy_url), f.requestProxyUrl, { form = f.copy(requestProxyUrl = it) }, supporting = stringResource(R.string.cfg_proxy_url_helper))
                MultilineField(stringResource(R.string.cfg_proxy_bypass), bypassText, { bypassText = it }, stringResource(R.string.cfg_list_helper))
            }
            SwitchRow(stringResource(R.string.cfg_access_log), f.loggingEnableAccessLog) { form = f.copy(loggingEnableAccessLog = it) }
            LogLevelRow(f.loggingMinLogLevel) { form = f.copy(loggingMinLogLevel = it) }
            SwitchRow(stringResource(R.string.cfg_skip_content), f.skipContentCheck) { form = f.copy(skipContentCheck = it) }
            SwitchRow(stringResource(R.string.cfg_tokenizers), f.enableDownloadableTokenizers) { form = f.copy(enableDownloadableTokenizers = it) }
            SwitchRow(stringResource(R.string.cfg_ext_enabled), f.extensionsEnabled) { form = f.copy(extensionsEnabled = it) }
            SwitchRow(stringResource(R.string.cfg_ext_autoupdate), f.extensionsAutoUpdate) { form = f.copy(extensionsAutoUpdate = it) }
            SwitchRow(stringResource(R.string.cfg_ext_model_dl), f.extensionsModelsAutoDownload) { form = f.copy(extensionsModelsAutoDownload = it) }
            SwitchRow(stringResource(R.string.cfg_user_accounts), f.enableUserAccounts) {
                // 关闭多用户时连带关闭隐蔽登录，避免残留无效状态
                form = if (it) f.copy(enableUserAccounts = true) else f.copy(enableUserAccounts = false, enableDiscreetLogin = false)
            }
            SwitchRow(stringResource(R.string.cfg_discreet_login), f.enableDiscreetLogin, enabled = f.enableUserAccounts) { form = f.copy(enableDiscreetLogin = it) }
        }

        // ── 危险 ──
        ExpandableSection(stringResource(R.string.cfg_section_danger), dangerExpanded, { dangerExpanded = !dangerExpanded }) {
            SwitchRow(stringResource(R.string.cfg_cors), f.enableCorsProxy) {
                if (it) pendingDanger = Danger.CORS else form = f.copy(enableCorsProxy = false)
            }
            SwitchRow(stringResource(R.string.cfg_csrf), f.disableCsrfProtection) {
                if (it) pendingDanger = Danger.CSRF else form = f.copy(disableCsrfProtection = false)
            }
        }

        // ── App 管理（只读） ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.cfg_section_managed))
                LockedRow(stringResource(R.string.cfg_data_root), f.dataRoot)
                LockedRow(stringResource(R.string.cfg_browser_launch), stringResource(R.string.cfg_managed_hint))
            }
        }

        // ── 操作 ──
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val cfg = f.copy(
                        port = port ?: f.port,
                        listenAddressIpv4 = f.listenAddressIpv4.trim(),
                        heartbeatInterval = heartbeatText.trim().toIntOrNull() ?: 0,
                        sessionTimeout = sessionText.trim().toIntOrNull() ?: -1,
                        whitelist = whitelistText.toEntries(),
                        requestProxyBypass = bypassText.toEntries(),
                    )
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ConfigEditor().write(paths.configFile, cfg) }
                        if (ok) {
                            seed(cfg)
                            if (running) showRestart = true
                            else Toast.makeText(context, R.string.cfg_saved, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, R.string.cfg_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = valid,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.cfg_save)) }
            OutlinedButton(onClick = { showRestore = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cfg_restore_default))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── 危险项二次确认 ──
    pendingDanger?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingDanger = null },
            title = { Text(stringResource(R.string.cfg_danger_title)) },
            text = {
                Text(stringResource(if (d == Danger.CORS) R.string.cfg_danger_cors_msg else R.string.cfg_danger_csrf_msg))
            },
            confirmButton = {
                TextButton(onClick = {
                    form = when (d) {
                        Danger.CORS -> form?.copy(enableCorsProxy = true)
                        Danger.CSRF -> form?.copy(disableCsrfProtection = true)
                    }
                    pendingDanger = null
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDanger = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // ── 运行中保存：提示重启 ──
    if (showRestart) {
        AlertDialog(
            onDismissRequest = { showRestart = false },
            title = { Text(stringResource(R.string.cfg_restart_title)) },
            text = { Text(stringResource(R.string.cfg_restart_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestart = false
                    scope.launch {
                        NodeService.stop(context)
                        withTimeoutOrNull(8000) { NodeRuntime.state.first { it == ServiceState.STOPPED } }
                        NodeService.start(context)
                    }
                }) { Text(stringResource(R.string.cfg_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestart = false }) { Text(stringResource(R.string.cfg_restart_later)) }
            },
        )
    }

    // ── 恢复默认确认 ──
    if (showRestore) {
        AlertDialog(
            onDismissRequest = { showRestore = false },
            title = { Text(stringResource(R.string.cfg_restore_title)) },
            text = { Text(stringResource(R.string.cfg_restore_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestore = false
                    scope.launch {
                        if (running) {
                            NodeService.stop(context)
                            withTimeoutOrNull(8000) { NodeRuntime.state.first { it == ServiceState.STOPPED } }
                        }
                        withContext(Dispatchers.IO) { paths.configFile.delete() }
                        form = null
                        loaded = true
                        Toast.makeText(context, R.string.cfg_restored, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestore = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun String.toEntries(): List<String> =
    lines().map { it.trim() }.filter { it.isNotEmpty() }

// ── 复用行组件 ──────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    numeric: Boolean = false,
    password: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { text -> @Composable { Text(text) } },
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        trailingIcon = if (password) {
            @Composable {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.cd_reveal_password),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MultilineField(label: String, value: String, onChange: (String) -> Unit, supporting: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(supporting) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LogLevelRow(value: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        0 to stringResource(R.string.cfg_log_debug),
        1 to stringResource(R.string.cfg_log_info),
        2 to stringResource(R.string.cfg_log_warn),
        3 to stringResource(R.string.cfg_log_error),
    )
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.cfg_log_level), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == value }?.second ?: value.toString())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (v, lbl) ->
                    DropdownMenuItem(text = { Text(lbl) }, onClick = { onSelect(v); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun LockedRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.cd_section_toggle),
                )
            }
            if (expanded) {
                content()
            }
        }
    }
}
