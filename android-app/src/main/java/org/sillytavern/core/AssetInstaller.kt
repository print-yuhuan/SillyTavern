package org.sillytavern.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 首启资产解压（实现方案 §4 / 工作计划第三阶段）。
 *
 * 把打进 APK 的 SillyTavern 源码与 node_modules 解压到 App 私有目录，建立
 * server / config / data 三层结构。其中：
 * - server/ 是可替换层：重装/升级前清空再解压。
 * - config/ 与 data/ 是持久层：仅创建，解压、修复、APK 升级都绝不覆盖。
 *
 * 安装判定依据 APK 内 assets/version.json 的 code/modules zip sha256：与已安装的
 * version.json 不一致（或尚未安装）才重新解压，实现「首次只解压一次、APK 升级
 * 自动重装、配置与数据保留」。幂等：已是最新则立即返回。
 */
object AssetInstaller {

    const val CODE_ZIP = "sillytavern-code.zip"
    const val MODULES_ZIP = "sillytavern-modules.zip"
    const val VERSION_ASSET = "version.json"

    enum class Phase { IDLE, EXTRACTING, DONE, ERROR }

    data class InstallState(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val filesExtracted: Int = 0,
    )

    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    /** 串行化：UI 开屏与 NodeService 启动可能同时触发，避免并发解压。 */
    private val mutex = Mutex()

    /**
     * 确保资产已解压就绪。
     * @param force 修复用，强制重新解压 server/（仍不动 config/ 与 data/）。
     * @return 成功与否；失败时 [state] 的 phase=ERROR、message 为原因。
     */
    suspend fun ensureInstalled(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val paths = AppPaths(context)
                try {
                    val bundled = readJson { context.assets.open(VERSION_ASSET).use { it.readBytes() } }
                    if (bundled == null) {
                        fail("APK 未包含 SillyTavern 资产（version.json 缺失）")
                        return@withLock false
                    }
                    val installed = if (paths.versionFile.exists()) {
                        readJson { paths.versionFile.readBytes() }
                    } else {
                        null
                    }
                    val upToDate = !force &&
                        paths.serverReady() &&
                        installed != null &&
                        installed.assetSha("codeZipSha256") == bundled.assetSha("codeZipSha256") &&
                        installed.assetSha("modulesZipSha256") == bundled.assetSha("modulesZipSha256")
                    if (upToDate) {
                        _state.value = InstallState(Phase.DONE, "资产已就绪")
                        return@withLock true
                    }

                    _state.value = InstallState(Phase.EXTRACTING, "正在准备 SillyTavern…")
                    paths.ensureRuntimeDirs()
                    // server/ 是可替换层：重装前清空再解压；config/ 与 data/ 为持久层，绝不触碰。
                    if (paths.serverDir.exists()) paths.serverDir.deleteRecursively()
                    paths.serverDir.mkdirs()

                    var count = 0
                    count = unzipAsset(context, CODE_ZIP, paths.serverDir, count)
                    count = unzipAsset(context, MODULES_ZIP, paths.serverDir, count)
                    // 落版本标记（以 APK 内 version.json 为准），作为下次安装判定与「关于」页数据源。
                    copyAsset(context, VERSION_ASSET, paths.versionFile)

                    _state.value = InstallState(Phase.DONE, "资产已就绪", count)
                    true
                } catch (e: Exception) {
                    fail("解压失败：${e.message}")
                    false
                }
            }
        }

    private fun fail(message: String) {
        _state.value = InstallState(Phase.ERROR, message)
    }

    private inline fun readJson(bytes: () -> ByteArray): JSONObject? = try {
        JSONObject(bytes().decodeToString())
    } catch (_: Exception) {
        null
    }

    /** version.json 的 assets.<key>（缺失返回空串）。 */
    private fun JSONObject.assetSha(key: String): String =
        optJSONObject("assets")?.optString(key, "") ?: ""

    private fun copyAsset(context: Context, name: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(name).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * 把 assets 中的 zip 解压到 destRoot，返回累计已解压文件数（用于进度展示）。
     * 防目录穿越：每个条目的规范路径必须落在 destRoot 之内。
     */
    private fun unzipAsset(context: Context, name: String, destRoot: File, startCount: Int): Int {
        val rootCanon = destRoot.canonicalPath
        val rootPrefix = rootCanon + File.separator
        var count = startCount
        val buffer = ByteArray(64 * 1024)
        context.assets.open(name).use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val target = File(destRoot, entry.name)
                    val canon = target.canonicalPath
                    if (canon != rootCanon && !canon.startsWith(rootPrefix)) {
                        throw SecurityException("非法 zip 条目（目录穿越）：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            var n = zis.read(buffer)
                            while (n >= 0) {
                                out.write(buffer, 0, n)
                                n = zis.read(buffer)
                            }
                        }
                        count++
                        if (count % 200 == 0) {
                            _state.value = InstallState(Phase.EXTRACTING, "正在解压资产…", count)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return count
    }
}
