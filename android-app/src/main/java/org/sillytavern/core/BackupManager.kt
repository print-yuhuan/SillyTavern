package org.sillytavern.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.sillytavern.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据保护：备份 / 恢复 / 修复 / 清理缓存（工作计划第八阶段、实现方案 §9）。
 *
 * 三层目录的保护边界（实现方案 §4 更新边界）：
 * - 备份/恢复只动持久层 [AppPaths.configDir] 与 [AppPaths.dataDir]，不碰可热更新的 server/。
 * - 修复只重解压 server/（委托 [AssetInstaller]，force=true），不碰 config/ 与 data/。
 * - 清理缓存只删 [AppPaths.nodeTmpDir] 与 data 下可再生缓存（_cache、_webpack），绝不删用户数据。
 *
 * 备份是单个 zip 文档，经 SAF（CreateDocument/OpenDocument）导出/导入，便携可分享。
 * 恢复采用「先解压到 staging，再原子换入」：换入失败可回滚，避免半截恢复损坏现有数据。
 *
 * 所有写文件的破坏性操作（恢复/修复/清理）应在服务停止后调用，由 UI 负责先停服。
 */
object BackupManager {

    const val MANIFEST_NAME = "backup-manifest.json"
    private const val MANIFEST_TYPE = "sillytavern-android-backup"
    private const val SCHEMA_VERSION = 1

    /** zip 内目录前缀：恢复时据此决定换入 config/ 还是 data/。 */
    private const val CONFIG_PREFIX = "config/"
    private const val DATA_PREFIX = "data/"

    /** 备份排除的可再生缓存（data 下顶层目录）：不入包，减小体积、加快导出。 */
    val DATA_REGENERABLE = setOf("_cache", "_webpack")

    private const val STAGING_DIR = "restore-staging"
    private const val PROGRESS_EVERY = 50

    /** 当前进行中或刚结束的操作类型，供 UI 选择对应文案。 */
    enum class Op { BACKUP, RESTORE, CLEAN, REPAIR }

    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    data class OpState(
        val op: Op? = null,
        val phase: Phase = Phase.IDLE,
        val files: Int = 0,
        val bytesFreed: Long = 0,
        val errorDetail: String = "",
    )

    private val _state = MutableStateFlow(OpState())
    val state: StateFlow<OpState> = _state.asStateFlow()

    /** 串行化所有数据操作，避免并发读写同一目录。 */
    private val mutex = Mutex()

    /** 离开数据页时清掉「已完成/失败」横幅；进行中（RUNNING）不清。 */
    fun resetIfIdle() {
        if (_state.value.phase != Phase.RUNNING) _state.value = OpState()
    }

    /** 备份文件默认名（含时间戳，便于区分多次备份）。 */
    fun defaultBackupName(): String =
        "SillyTavern-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    // ── 导出备份 ────────────────────────────────────────────────────────────

    /** 把 config/ 与 data/（排除可再生缓存）打包成 zip 写入 SAF 选择的文档。 */
    suspend fun exportBackup(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val paths = AppPaths(context)
            _state.value = OpState(Op.BACKUP, Phase.RUNNING)
            try {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法写入所选位置")
                var count = 0
                ZipOutputStream(out.buffered()).use { zos ->
                    zos.putNextEntry(ZipEntry(MANIFEST_NAME))
                    zos.write(buildManifest().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    count += zipTree(zos, paths.configDir, CONFIG_PREFIX, emptySet()) {
                        _state.value = OpState(Op.BACKUP, Phase.RUNNING, files = it)
                    }
                    count += zipTree(zos, paths.dataDir, DATA_PREFIX, DATA_REGENERABLE) {
                        _state.value = OpState(Op.BACKUP, Phase.RUNNING, files = it)
                    }
                }
                _state.value = OpState(Op.BACKUP, Phase.DONE, files = count)
                NodeRuntime.appendLog("[backup] 已导出备份，共 $count 个文件")
                true
            } catch (e: Exception) {
                NodeRuntime.appendLog("[backup] 导出失败：${e.message}")
                _state.value = OpState(Op.BACKUP, Phase.ERROR, errorDetail = e.message ?: "")
                false
            }
        }
    }

    // ── 从备份恢复 ──────────────────────────────────────────────────────────

    /**
     * 从 SAF 选择的备份 zip 恢复 config/ 与 data/。
     * 先解压到 staging 校验，再逐目录原子换入（可回滚）。调用前应已停止服务。
     */
    suspend fun importBackup(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val paths = AppPaths(context)
            _state.value = OpState(Op.RESTORE, Phase.RUNNING)
            val staging = File(paths.filesDir, STAGING_DIR)
            try {
                staging.deleteRecursively()
                staging.mkdirs()
                val rootCanon = staging.canonicalPath
                val rootPrefix = rootCanon + File.separator
                val buffer = ByteArray(64 * 1024)
                var count = 0
                var foundConfig = false
                var foundData = false

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.startsWith(CONFIG_PREFIX) || name.startsWith(DATA_PREFIX)) {
                            val target = File(staging, name)
                            val canon = target.canonicalPath
                            if (canon != rootCanon && !canon.startsWith(rootPrefix)) {
                                throw SecurityException("非法 zip 条目（目录穿越）：$name")
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { o ->
                                    var n = zis.read(buffer)
                                    while (n >= 0) {
                                        o.write(buffer, 0, n)
                                        n = zis.read(buffer)
                                    }
                                }
                                count++
                                if (count % PROGRESS_EVERY == 0) {
                                    _state.value = OpState(Op.RESTORE, Phase.RUNNING, files = count)
                                }
                            }
                            if (name.startsWith(CONFIG_PREFIX)) foundConfig = true else foundData = true
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                // 必须含 config/ 或 data/ 内容，否则不是有效备份。
                if (!foundConfig && !foundData) throw IllegalStateException("无效的备份文件")

                paths.sillyTavernHome.mkdirs()
                if (foundConfig && !swapDir(File(staging, "config"), paths.configDir)) {
                    throw IllegalStateException("恢复配置失败")
                }
                if (foundData && !swapDir(File(staging, "data"), paths.dataDir)) {
                    throw IllegalStateException("恢复数据失败")
                }
                paths.ensureRuntimeDirs()

                _state.value = OpState(Op.RESTORE, Phase.DONE, files = count)
                NodeRuntime.appendLog("[backup] 已从备份恢复 config/data，共 $count 个文件")
                true
            } catch (e: Exception) {
                NodeRuntime.appendLog("[backup] 恢复失败：${e.message}")
                _state.value = OpState(Op.RESTORE, Phase.ERROR, errorDetail = e.message ?: "")
                false
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    // ── 修复服务端文件 ──────────────────────────────────────────────────────

    /** 重解压 server/（不动 config/ 与 data/）。委托 [AssetInstaller] 强制重装。 */
    suspend fun repairServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _state.value = OpState(Op.REPAIR, Phase.RUNNING)
            val ok = AssetInstaller.ensureInstalled(context, force = true)
            _state.value = if (ok) {
                NodeRuntime.appendLog("[backup] 已重解压服务端文件")
                OpState(Op.REPAIR, Phase.DONE)
            } else {
                OpState(Op.REPAIR, Phase.ERROR, errorDetail = AssetInstaller.state.value.message)
            }
            ok
        }
    }

    // ── 清理缓存 ────────────────────────────────────────────────────────────

    /** 删除 node 临时目录与 data 下可再生缓存（_cache、_webpack）；返回释放的字节数。 */
    suspend fun cleanCache(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val paths = AppPaths(context)
            _state.value = OpState(Op.CLEAN, Phase.RUNNING)
            try {
                val targets = buildList {
                    add(paths.nodeTmpDir)
                    DATA_REGENERABLE.forEach { add(File(paths.dataDir, it)) }
                }
                var freed = 0L
                for (t in targets) {
                    if (t.exists()) {
                        freed += dirSize(t)
                        if (!t.deleteRecursively()) throw IllegalStateException("无法删除 ${t.name}")
                    }
                }
                paths.nodeTmpDir.mkdirs()
                _state.value = OpState(Op.CLEAN, Phase.DONE, bytesFreed = freed)
                NodeRuntime.appendLog("[backup] 清理缓存，释放 $freed 字节")
                true
            } catch (e: Exception) {
                NodeRuntime.appendLog("[backup] 清理缓存失败：${e.message}")
                _state.value = OpState(Op.CLEAN, Phase.ERROR, errorDetail = e.message ?: "")
                false
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 递归统计目录占用字节数（用于存储占用展示与清理统计）。 */
    fun dirSize(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 把目录树写入 zip。[prefix] 为 zip 内前缀（config/ 或 data/）；
     * [exclude] 为 root 下要跳过的顶层目录名（可再生缓存）。返回新增文件数。
     */
    private fun zipTree(
        zos: ZipOutputStream,
        root: File,
        prefix: String,
        exclude: Set<String>,
        onProgress: (Int) -> Unit,
    ): Int {
        if (!root.exists()) return 0
        val rootPath = root.absolutePath
        val buffer = ByteArray(64 * 1024)
        var count = 0
        root.walkTopDown()
            .onEnter { dir -> !(dir.parentFile?.absolutePath == rootPath && dir.name in exclude) }
            .forEach { file ->
                if (file.absolutePath == rootPath) return@forEach
                val rel = file.absolutePath.substring(rootPath.length)
                    .trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                if (rel.substringBefore('/') in exclude) return@forEach
                if (file.isDirectory) {
                    zos.putNextEntry(ZipEntry("$prefix$rel/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry("$prefix$rel"))
                    file.inputStream().use { input ->
                        var n = input.read(buffer)
                        while (n >= 0) {
                            zos.write(buffer, 0, n)
                            n = input.read(buffer)
                        }
                    }
                    zos.closeEntry()
                    count++
                    if (count % PROGRESS_EVERY == 0) onProgress(count)
                }
            }
        return count
    }

    /**
     * 用 [staged] 原子替换 [live]：先把现有 live 改名为 .bak，换入成功后删 .bak；
     * 失败则回滚 .bak，保证不会出现「旧的已删、新的没进来」的半截状态。
     */
    private fun swapDir(staged: File, live: File): Boolean {
        if (!staged.exists()) return true
        val backup = File(live.parentFile, "${live.name}.bak")
        if (backup.exists() && !backup.deleteRecursively()) return false

        val hadLive = live.exists()
        // 关键：没有成功把现有数据改名为 .bak 就绝不删除它——否则一旦换入再失败，
        // 旧数据已丢且无从回滚（恢复是不可撤销操作，必须守住这条边界）。
        if (hadLive && !live.renameTo(backup)) return false

        if (!staged.renameTo(live)) {
            if (hadLive && backup.exists()) backup.renameTo(live) // 回滚到原数据
            return false
        }
        if (backup.exists()) backup.deleteRecursively()
        return true
    }

    private fun buildManifest(): String = JSONObject().apply {
        put("type", MANIFEST_TYPE)
        put("schemaVersion", SCHEMA_VERSION)
        put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        put("appVersionName", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE)
        put("contents", JSONArray(listOf("config", "data")))
    }.toString()
}
