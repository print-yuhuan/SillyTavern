package org.sillytavern.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ConfigEditor] 的 JVM 单元测试：覆盖审查中修复的探活地址逻辑（恒走回环），
 * 以及 YAML 读写「保留未知字段 + 原子写」这一高风险路径。
 */
class ConfigEditorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val editor = ConfigEditor()

    @Test
    fun healthCheckUrl_defaultsToLoopbackIpv4() {
        assertEquals("http://127.0.0.1:8000", ConfigEditor.healthCheckUrl(ConfigEditor.STConfig()))
    }

    @Test
    fun healthCheckUrl_ipv6OnlyUsesLoopbackV6() {
        val cfg = ConfigEditor.STConfig(protocolIpv4 = false, protocolIpv6 = true, port = 9000)
        assertEquals("http://[::1]:9000", ConfigEditor.healthCheckUrl(cfg))
    }

    @Test
    fun healthCheckUrl_ignoresLanBindAddress() {
        // 即便监听具体局域网 IP，App 内探活仍应走回环（修复 [严重-4] 的关键断言）。
        val cfg = ConfigEditor.STConfig(listen = true, listenAddressIpv4 = "192.168.1.5")
        assertEquals("http://127.0.0.1:8000", ConfigEditor.healthCheckUrl(cfg))
    }

    @Test
    fun healthCheckUrl_sslUsesHttps() {
        assertEquals("https://127.0.0.1:8000", ConfigEditor.healthCheckUrl(ConfigEditor.STConfig(sslEnabled = true)))
    }

    @Test
    fun isValidIpv4_acceptsAndRejects() {
        assertTrue(ConfigEditor.isValidIpv4("192.168.1.5"))
        assertTrue(ConfigEditor.isValidIpv4("0.0.0.0"))
        assertFalse(ConfigEditor.isValidIpv4("999.1.1.1"))
        assertFalse(ConfigEditor.isValidIpv4("1.2.3"))
        assertFalse(ConfigEditor.isValidIpv4("abc"))
    }

    @Test
    fun readOrNull_missingFileReturnsNull() {
        assertNull(editor.readOrNull(File(tmp.root, "nope.yaml")))
    }

    @Test
    fun writeThenRead_roundTripsManagedFields() {
        val f = File(tmp.root, "config.yaml")
        assertTrue(editor.write(f, ConfigEditor.STConfig(port = 1234, listen = true, sslEnabled = true)))
        val read = editor.readOrNull(f)
        assertNotNull(read)
        assertEquals(1234, read!!.port)
        assertTrue(read.listen)
        assertTrue(read.sslEnabled)
    }

    @Test
    fun write_preservesUnknownTopLevelKeys() {
        val f = File(tmp.root, "config.yaml")
        f.writeText("customUnknownKey: keepme\nport: 8000\n")
        assertTrue(editor.write(f, ConfigEditor.STConfig(port = 8080)))
        val text = f.readText()
        assertTrue("未接管的未知字段应被保留", text.contains("customUnknownKey"))
        assertTrue(text.contains("keepme"))
        assertEquals(8080, editor.readOrNull(f)!!.port) // 接管字段已更新
    }
}
