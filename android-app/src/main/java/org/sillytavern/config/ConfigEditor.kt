package org.sillytavern.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * config.yaml 的结构化读写（实现方案 §7，第六阶段接管范围）。
 *
 * 设计约束：
 * - 仅用 YAML 解析/序列化处理结构化数据，禁止字符串替换改 YAML。
 * - 写回时**保留 App 未接管的未知字段**：以原文件 Map 为基底，仅覆盖接管字段后整树 dump。
 * - 原子写入：先写 `.tmp` 再 rename 替换，避免写一半损坏配置。
 * - `dataRoot` / `browserLaunch.enabled` 为 App 管理项，运行时由 CLI 覆盖，**不写回**（只读展示）。
 *
 * 注：SnakeYAML 的 Map 读写不保留 YAML 注释；配置由 App UI 管理、用户不手改原文件，可接受。
 */
class ConfigEditor {

    /** 接管字段的结构化视图（仅 App 接管的部分；未知字段不在此，写回时原样保留）。 */
    data class STConfig(
        // 基础
        val port: Int = DEFAULT_PORT,
        val listen: Boolean = false,
        val listenAddressIpv4: String = "0.0.0.0",
        val protocolIpv4: Boolean = true,
        val protocolIpv6: Boolean = false,
        val sslEnabled: Boolean = false,
        val whitelistMode: Boolean = true,
        val basicAuthMode: Boolean = false,
        val basicAuthUsername: String = "user",
        val basicAuthPassword: String = "password",
        // 高级
        val listenAddressIpv6: String = "[::]",
        val dnsPreferIPv6: Boolean = false,
        val enableKeepAlive: Boolean = false,
        val heartbeatInterval: Int = 0,
        val sessionTimeout: Int = -1,
        val whitelist: List<String> = listOf("::1", "127.0.0.1"),
        val sslCertPath: String = "./certs/cert.pem",
        val sslKeyPath: String = "./certs/privkey.pem",
        val sslKeyPassphrase: String = "",
        val requestProxyEnabled: Boolean = false,
        val requestProxyUrl: String = "",
        val requestProxyBypass: List<String> = listOf("localhost", "127.0.0.1"),
        val loggingEnableAccessLog: Boolean = true,
        val loggingMinLogLevel: Int = 0,
        val skipContentCheck: Boolean = false,
        val enableDownloadableTokenizers: Boolean = true,
        val extensionsEnabled: Boolean = true,
        val extensionsAutoUpdate: Boolean = true,
        val extensionsModelsAutoDownload: Boolean = true,
        val enableUserAccounts: Boolean = false,
        val enableDiscreetLogin: Boolean = false,
        // 危险
        val enableCorsProxy: Boolean = false,
        val disableCsrfProtection: Boolean = false,
        // App 管理（只读展示，不写回）
        val dataRoot: String = "./data",
        val browserLaunchEnabled: Boolean = true,
    )

    /** 读取配置文件；不存在或解析失败返回 null（调用方按默认值兜底）。 */
    fun readOrNull(file: File): STConfig? {
        if (!file.exists()) return null
        return try {
            val root = loadRoot(file) ?: return null
            STConfig(
                port = (root["port"] as? Number)?.toInt() ?: DEFAULT_PORT,
                listen = root["listen"] as? Boolean ?: false,
                listenAddressIpv4 = nested(root, "listenAddress", "ipv4") as? String ?: "0.0.0.0",
                protocolIpv4 = nested(root, "protocol", "ipv4") as? Boolean ?: true,
                protocolIpv6 = nested(root, "protocol", "ipv6") as? Boolean ?: false,
                sslEnabled = nested(root, "ssl", "enabled") as? Boolean ?: false,
                whitelistMode = root["whitelistMode"] as? Boolean ?: true,
                basicAuthMode = root["basicAuthMode"] as? Boolean ?: false,
                basicAuthUsername = nested(root, "basicAuthUser", "username") as? String ?: "user",
                basicAuthPassword = nested(root, "basicAuthUser", "password") as? String ?: "password",
                listenAddressIpv6 = nested(root, "listenAddress", "ipv6") as? String ?: "[::]",
                dnsPreferIPv6 = root["dnsPreferIPv6"] as? Boolean ?: false,
                enableKeepAlive = root["enableKeepAlive"] as? Boolean ?: false,
                heartbeatInterval = (root["heartbeatInterval"] as? Number)?.toInt() ?: 0,
                sessionTimeout = (root["sessionTimeout"] as? Number)?.toInt() ?: -1,
                whitelist = stringList(root["whitelist"]) ?: listOf("::1", "127.0.0.1"),
                sslCertPath = nested(root, "ssl", "certPath") as? String ?: "./certs/cert.pem",
                sslKeyPath = nested(root, "ssl", "keyPath") as? String ?: "./certs/privkey.pem",
                sslKeyPassphrase = nested(root, "ssl", "keyPassphrase") as? String ?: "",
                requestProxyEnabled = nested(root, "requestProxy", "enabled") as? Boolean ?: false,
                requestProxyUrl = nested(root, "requestProxy", "url") as? String ?: "",
                requestProxyBypass = stringList(nested(root, "requestProxy", "bypass")) ?: listOf("localhost", "127.0.0.1"),
                loggingEnableAccessLog = nested(root, "logging", "enableAccessLog") as? Boolean ?: true,
                loggingMinLogLevel = (nested(root, "logging", "minLogLevel") as? Number)?.toInt() ?: 0,
                skipContentCheck = root["skipContentCheck"] as? Boolean ?: false,
                enableDownloadableTokenizers = root["enableDownloadableTokenizers"] as? Boolean ?: true,
                extensionsEnabled = nested(root, "extensions", "enabled") as? Boolean ?: true,
                extensionsAutoUpdate = nested(root, "extensions", "autoUpdate") as? Boolean ?: true,
                extensionsModelsAutoDownload = nested(root, "extensions", "models", "autoDownload") as? Boolean ?: true,
                enableUserAccounts = root["enableUserAccounts"] as? Boolean ?: false,
                enableDiscreetLogin = root["enableDiscreetLogin"] as? Boolean ?: false,
                enableCorsProxy = root["enableCorsProxy"] as? Boolean ?: false,
                disableCsrfProtection = root["disableCsrfProtection"] as? Boolean ?: false,
                dataRoot = root["dataRoot"] as? String ?: "./data",
                browserLaunchEnabled = nested(root, "browserLaunch", "enabled") as? Boolean ?: true,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 结构化写回：以原文件 Map 为基底保留未知字段，仅覆盖接管字段，原子写入。
     * @return 是否成功（写后重读能解析即成功）。
     */
    fun write(file: File, c: STConfig): Boolean {
        return try {
            val root: MutableMap<String, Any?> =
                (if (file.exists()) loadRoot(file) else null) ?: LinkedHashMap()
            applyTo(root, c)

            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
                width = 4096 // 避免长字符串被折行
            }
            val text = Yaml(options).dump(root)

            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // 不支持原子移动时退化为「先备份再换入」，绝不直接覆盖写半截损坏现有配置。
                val bak = File(file.parentFile, "${file.name}.bak")
                if (file.exists() && !file.renameTo(bak)) {
                    tmp.delete()
                    return false
                }
                if (!tmp.renameTo(file)) {
                    if (bak.exists()) bak.renameTo(file) // 回滚
                    return false
                }
                bak.delete()
            }
            // 写后重读校验：能解析即认为成功
            loadRoot(file) != null
        } catch (_: Exception) {
            false
        }
    }

    /** 仅覆盖接管字段；不触碰 dataRoot / browserLaunch（App 管理）与其它未知字段。 */
    private fun applyTo(root: MutableMap<String, Any?>, c: STConfig) {
        root["port"] = c.port
        root["listen"] = c.listen
        nestedMap(root, "listenAddress").apply {
            put("ipv4", c.listenAddressIpv4)
            put("ipv6", c.listenAddressIpv6)
        }
        nestedMap(root, "protocol").apply {
            put("ipv4", c.protocolIpv4)
            put("ipv6", c.protocolIpv6)
        }
        root["dnsPreferIPv6"] = c.dnsPreferIPv6
        nestedMap(root, "ssl").apply {
            put("enabled", c.sslEnabled)
            put("certPath", c.sslCertPath)
            put("keyPath", c.sslKeyPath)
            put("keyPassphrase", c.sslKeyPassphrase)
        }
        root["whitelistMode"] = c.whitelistMode
        root["whitelist"] = ArrayList(c.whitelist)
        root["basicAuthMode"] = c.basicAuthMode
        nestedMap(root, "basicAuthUser").apply {
            put("username", c.basicAuthUsername)
            put("password", c.basicAuthPassword)
        }
        root["enableCorsProxy"] = c.enableCorsProxy
        nestedMap(root, "requestProxy").apply {
            put("enabled", c.requestProxyEnabled)
            put("url", c.requestProxyUrl)
            put("bypass", ArrayList(c.requestProxyBypass))
        }
        root["sessionTimeout"] = c.sessionTimeout
        root["disableCsrfProtection"] = c.disableCsrfProtection
        nestedMap(root, "logging").apply {
            put("enableAccessLog", c.loggingEnableAccessLog)
            put("minLogLevel", c.loggingMinLogLevel)
        }
        root["heartbeatInterval"] = c.heartbeatInterval
        root["enableKeepAlive"] = c.enableKeepAlive
        root["skipContentCheck"] = c.skipContentCheck
        root["enableDownloadableTokenizers"] = c.enableDownloadableTokenizers
        nestedMap(root, "extensions").apply {
            put("enabled", c.extensionsEnabled)
            put("autoUpdate", c.extensionsAutoUpdate)
            nestedMap(this, "models").put("autoDownload", c.extensionsModelsAutoDownload)
        }
        root["enableUserAccounts"] = c.enableUserAccounts
        root["enableDiscreetLogin"] = c.enableDiscreetLogin
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadRoot(file: File): MutableMap<String, Any?>? =
        file.inputStream().use { Yaml().load<Any?>(it) } as? MutableMap<String, Any?>

    private fun nested(root: Map<String, Any?>, vararg keys: String): Any? {
        var cur: Any? = root
        for (k in keys) {
            @Suppress("UNCHECKED_CAST")
            cur = (cur as? Map<String, Any?>)?.get(k) ?: return null
        }
        return cur
    }

    @Suppress("UNCHECKED_CAST")
    private fun nestedMap(parent: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> {
        val existing = parent[key]
        if (existing is MutableMap<*, *>) return existing as MutableMap<String, Any?>
        val created = LinkedHashMap<String, Any?>()
        parent[key] = created
        return created
    }

    private fun stringList(value: Any?): List<String>? =
        (value as? List<*>)?.map { it.toString() }

    companion object {
        const val DEFAULT_PORT = 8000

        /**
         * App 内探活 + WebView 加载地址：**恒为本机回环**，与对外绑定地址解耦。
         *
         * 之前用对外绑定 IP 会导致两类“服务正常却被误判失败”：
         * - 绑定具体局域网 IP（如 192.168.x.x）时，明文请求被 network_security_config 拦截；
         * - 仅启用 IPv6 时，回落到 IPv4 绑定地址不可达。
         * 本机一律走回环即可探活与加载；对外可达性由 [lanAccessEnabled] 单独表达。
         */
        fun healthCheckUrl(config: STConfig?): String {
            val c = config ?: STConfig()
            val scheme = if (c.sslEnabled) "https" else "http"
            // 仅启用 IPv6（关闭 IPv4）时走 [::1]；否则一律 127.0.0.1（默认 IPv4 / 双栈）。
            val host = if (!c.protocolIpv4 && c.protocolIpv6) "[::1]" else "127.0.0.1"
            return "$scheme://$host:${c.port}"
        }

        /** 是否允许局域网访问（listen=true 且绑定 0.0.0.0 或具体局域网 IP）。 */
        fun lanAccessEnabled(config: STConfig?): Boolean = config?.listen == true

        fun isValidIpv4(addr: String): Boolean {
            val parts = addr.split(".")
            if (parts.size != 4) return false
            return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
    }
}
