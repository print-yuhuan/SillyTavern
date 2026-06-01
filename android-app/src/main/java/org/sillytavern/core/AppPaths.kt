package org.sillytavern.core

import android.content.Context
import java.io.File

/**
 * 集中定义实现方案 §4 的目录布局，避免各处硬编码路径。
 *
 * - server / config / data 三层分离：更新只替换 server，不动 config 与 data。
 * - NODE_BIN 必须位于 nativeLibraryDir（只读可执行），是 Android 10+ 唯一可 exec 的位置。
 */
class AppPaths(context: Context) {

    /** Android 解压出的原生库目录，只读可执行。 */
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    /** 内置 Node 可执行文件（以 libnode.so 形式打进 jniLibs/arm64-v8a）。 */
    val nodeBin: File = File(nativeLibDir, "libnode.so")

    /**
     * Node 运行所需的 C++ 运行库。libnode.so 由 NDK/Clang 构建，动态依赖 libc++_shared.so，
     * 必须与 libnode.so 一同打进 jniLibs/arm64-v8a；缺失时动态链接器在 exec 前即报
     * "library libc++_shared.so not found"，Node 根本无法启动。
     */
    val libcxxShared: File = File(nativeLibDir, "libc++_shared.so")

    val filesDir: File = context.filesDir
    val cacheDir: File = context.cacheDir

    /** SILLYTAVERN_HOME_DIR */
    val sillyTavernHome: File = File(filesDir, "sillytavern")

    /** SILLYTAVERN_SERVER_DIR（可热更新整体替换层） */
    val serverDir: File = File(sillyTavernHome, "server")
    val serverJs: File = File(serverDir, "server.js")
    val nodeModulesDir: File = File(serverDir, "node_modules")
    val defaultConfigTemplate: File = File(serverDir, "default/config.yaml")

    /** SILLYTAVERN_CONFIG_DIR / FILE（持久层，传给 --configPath） */
    val configDir: File = File(sillyTavernHome, "config")
    val configFile: File = File(configDir, "config.yaml")

    /** SILLYTAVERN_DATA_DIR（持久层，传给 --dataRoot） */
    val dataDir: File = File(sillyTavernHome, "data")

    /** 版本/资产标记 */
    val versionFile: File = File(sillyTavernHome, "version.json")

    /** 传给 Node 的可写临时目录（TMPDIR） */
    val nodeTmpDir: File = File(cacheDir, "node-tmp")

    // ── M0 真机 PoC 专用：最小测试服务（非完整 SillyTavern） ──────────────
    val m0Dir: File = File(filesDir, "m0")
    val m0ServerJs: File = File(m0Dir, "server.js")

    /** 内置 Node 是否存在且具备可执行位。 */
    fun nodeBinReady(): Boolean = nodeBin.exists() && nodeBin.canExecute()

    /** SillyTavern 服务端资产是否已解压就绪（server.js 与 node_modules 均在位）。 */
    fun serverReady(): Boolean = serverJs.exists() && nodeModulesDir.isDirectory

    /**
     * 创建壳侧运行所需目录。
     * config/ 与 data/ 是持久层：仅 mkdirs，绝不在解压或升级时清空（实现方案 §4 更新边界）。
     */
    fun ensureRuntimeDirs() {
        listOf(sillyTavernHome, configDir, dataDir, nodeTmpDir).forEach { it.mkdirs() }
    }
}
