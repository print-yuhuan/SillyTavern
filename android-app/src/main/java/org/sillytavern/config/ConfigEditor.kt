package org.sillytavern.config

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * config.yaml 的结构化读写（实现方案 §7）。
 *
 * 设计约束：
 * - 仅用 YAML 解析/序列化处理结构化数据，禁止字符串替换改 YAML。
 * - 写回时保留 App 尚未接管的未知字段，原子写入临时文件再替换。
 *
 * 注意：M0 阶段仅使用读取与健康检查 URL 推导；完整的「App UI 接管写回」在第六阶段实现。
 * 写回 API 已预留 [write]，当前以保留未知字段的方式落地，后续在配置页接入。
 */
class ConfigEditor {

    /** 与 SillyTavern 源码字段对应的最小配置视图（仅 App 需要的部分）。 */
    data class STConfig(
        val port: Int = DEFAULT_PORT,
        val listen: Boolean = false,
        val listenAddressIpv4: String = "0.0.0.0",
        val protocolIpv4: Boolean = true,
        val protocolIpv6: Boolean = false,
        val sslEnabled: Boolean = false,
        val whitelistMode: Boolean = true,
        val basicAuthMode: Boolean = false,
        val heartbeatInterval: Int = 0,
    )

    /** 读取配置文件；不存在或解析失败返回 null（调用方按默认值兜底）。 */
    fun readOrNull(file: File): STConfig? {
        if (!file.exists()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = file.inputStream().use { Yaml().load<Any?>(it) } as? Map<String, Any?>
                ?: return null
            STConfig(
                port = (root["port"] as? Number)?.toInt() ?: DEFAULT_PORT,
                listen = root["listen"] as? Boolean ?: false,
                listenAddressIpv4 = nested(root, "listenAddress", "ipv4") as? String ?: "0.0.0.0",
                protocolIpv4 = nested(root, "protocol", "ipv4") as? Boolean ?: true,
                protocolIpv6 = nested(root, "protocol", "ipv6") as? Boolean ?: false,
                sslEnabled = nested(root, "ssl", "enabled") as? Boolean ?: false,
                whitelistMode = root["whitelistMode"] as? Boolean ?: true,
                basicAuthMode = root["basicAuthMode"] as? Boolean ?: false,
                heartbeatInterval = (root["heartbeatInterval"] as? Number)?.toInt() ?: 0,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun nested(root: Map<String, Any?>, vararg keys: String): Any? {
        var cur: Any? = root
        for (k in keys) {
            @Suppress("UNCHECKED_CAST")
            cur = (cur as? Map<String, Any?>)?.get(k) ?: return null
        }
        return cur
    }

    companion object {
        const val DEFAULT_PORT = 8000

        /**
         * 健康检查（App 内部探活 + WebView 加载）地址，逻辑对齐上游 command-line.js#getIPv4ListenUrl：
         * - listen=false → 127.0.0.1
         * - listen=true 且 ipv4 合法 → 该 IP；非法回落 0.0.0.0
         * - 0.0.0.0 只能绑定不能访问，App 内探活统一改用 127.0.0.1
         */
        fun healthCheckUrl(config: STConfig?): String {
            val c = config ?: STConfig()
            val scheme = if (c.sslEnabled) "https" else "http"
            val bind = if (c.listen) {
                if (isValidIpv4(c.listenAddressIpv4)) c.listenAddressIpv4 else "0.0.0.0"
            } else {
                "127.0.0.1"
            }
            val host = if (bind == "0.0.0.0") "127.0.0.1" else bind
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
