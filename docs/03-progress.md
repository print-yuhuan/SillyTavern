# 实施进度

> 按工作计划（[02-work-plan.md](02-work-plan.md)）分阶段推进。本文件记录各阶段完成情况、未完成项与下一步。

## 阶段总览

| 阶段 | 名称 | 状态 |
|---|---|---|
| 第一阶段 | 仓库骨架 | ✅ 已完成 |
| 第二阶段 | M0 真机 PoC | 🚧 CI 已成功产出 debug APK，待真机验证 Node 启动 |
| 第三阶段 | SillyTavern 资产解压 | ⬜ 未开始 |
| 第四阶段 | 完整 NodeService | ⬜ 未开始 |
| 第五阶段 | App UI | ⬜ 未开始（首页 M0 版已具雏形） |
| 第六阶段 | 配置 UI 接管 config.yaml | ⬜ 未开始（已具备结构化读取与 URL 推导） |
| 第七阶段 | WebView | 🚧 基础实现已就绪，待联调 |
| 第八阶段 | 数据/备份/修复 | ⬜ 未开始 |
| 第九阶段 | 打包与 CI | 🚧 build-apk 已验证可产出 debug APK；release/打包脚本待 Tag 触发验证 |

---

## 第一阶段：仓库骨架 ✅

完成情况：

- 目录结构：`android-app/`、`third_party/SillyTavern/`、`third_party/Android-Node-Builder/`、`packaging/`、`scripts/`、`docs/`、`.github/workflows/`。
- `git init`（默认分支 `main`），`origin` = `https://github.com/print-yuhuan/SillyTavern.git`。
- 子模块（已锁定）：
  - `third_party/SillyTavern` → `SillyTavern/SillyTavern.git`，tag **1.18.0**（commit `51ad27f`）。
  - `third_party/Android-Node-Builder` → `print-yuhuan/Android-Node-Builder.git`，tag **v1.0.0**（commit `f516600`）。
- `.gitmodules` 路径与 URL 与文档一致。
- `.gitignore` 排除：本机参考 `/SillyTavern/`、本机 `libnode-*.zip`、CI 生成的 `jniLibs/`、assets 内 zip 与 version.json、Gradle/AS 产物。
- Android 工程：Kotlin + Compose + Material 3；`applicationId=org.sillytavern`、`minSdk=28`、`targetSdk=36`、`compileSdk=36`、`abiFilters=arm64-v8a`、`extractNativeLibs=true`、`jniLibs.useLegacyPackaging=true`。
- 基础类：`LauncherActivity`、`WebViewActivity`、`NodeService`、`ConfigEditor`（另含 `SillyTavernApp`、`core/*`、`ui/theme/*`）。
- Manifest 权限：`INTERNET`、前台服务（含 `FOREGROUND_SERVICE_SPECIAL_USE`）、`POST_NOTIFICATIONS`、`REQUEST_INSTALL_PACKAGES`、`RECORD_AUDIO` 等。
- App 名称显示为 `SillyTavern`；外壳文案全部简体中文。
- 自带 Gradle Wrapper（8.11.1，`gradlew`/`gradlew.bat`/`gradle-wrapper.jar` 均已就位）。

未完成 / 注意：

- 本机不构建；编译验证以 GitHub Actions 为准（验收即「CI 能跑到 Gradle 编译阶段」）。
- 工具链版本保守锁定（AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.0.21），并用 `android.suppressUnsupportedCompileSdk=36` 兼容 compileSdk 36；首版 CI 通过后可上调 AGP 去掉该项。

---

## 第二阶段：M0 真机 PoC 🚧

完成情况：

- `assets/m0-server.js`：仅用 Node 核心模块的最小 HTTP 测试服务（非完整 SillyTavern）。
- `NodeService`：用 `ProcessBuilder` 执行 `nativeLibraryDir/libnode.so server.js`，设置 `HOME`/`TMPDIR`/`PATH`，合并捕获 stdout/stderr 写入日志，轮询健康检查，状态机 Stopped/Starting/Running/Stopping/Error，前台服务常驻通知 + 「停止」动作。
- `LauncherActivity`：首页可启动/停止、查看运行状态与监听地址、运行时长、最近日志（可复制/清空）、运行中可打开 WebView。
- `build-apk.yml`：分支/手动触发产出 debug APK（artifact），可用于 M0 真机安装。

libnode 来源（已打通）：

- `Android-Node-Builder` 的 **`v1.0.0` Release 已发布** `libnode.so`（113616432 字节）与 `libnode.so.sha256`（`fa3ae680…`）。
- `build-apk.yml` 从该 Release 直链下载（已实测 URL 可达），按 sha256 校验后放入 `jniLibs/arm64-v8a/`。
- libnode 统一从 Android-Node-Builder Releases 获取；本机 zip 与上传脚本已删除，无需手动上传。

CI 验证（已通过）：

- 首个 `Build APK` 运行成功（push `main`，run 26723044082，4m5s）：Gradle 8.11.1 + Android SDK 36 安装、从 ANB v1.0.0 Release 下载 `libnode.so` 并校验 sha256、submodule 递归检出、Kotlin/Compose 编译、`compileSdk 36`（AGP 8.7.3 + `suppressUnsupportedCompileSdk`）、arm64 debug APK 打包并上传 artifact，全部通过。
- 产物 artifact：`SillyTavern-android-arm64-v8a-debug-<run_number>.apk`。
- 已为 `build-apk.yml` 与 `check-upstream.yml` 顶部加 `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`，消除 actions 的 Node 20 弃用告警。

未完成（仅剩真机验证）：

- 安装 debug APK 到 arm64 真机：Node 能否从 `nativeLibraryDir` 启动、日志是否回显、停止是否结束进程、后台前台服务通知是否常驻。

下一步：

1. 下载 workflow artifact 中的 debug APK，安装到 arm64 真机，按工作计划第二阶段验收点逐项确认。
2. M0 通过后再进入第三阶段（资产解压）与第四阶段（完整 SillyTavern 启动）。

---

## 关键事实备忘

- libnode.so sha256：`fa3ae680f5e796953e3275b84eaa29d51f14346ba9da43ea9b5617f5b461c2de`（Node 24.16.0 / API 28 / arm64-v8a）。
- 本机参考 clone `D:\SillyTavern\SillyTavern` 为 tag 1.18.0，仅供参考，不入库、不打包。
- 上游 `server.js` CLI：`--configPath`、`--dataRoot`、`--browserLaunchEnabled`（详见 `third_party/SillyTavern/src/command-line.js`）。
- 健康检查 URL 逻辑对齐上游 `getIPv4ListenUrl`：`listen=false`→`127.0.0.1`；`listen=true` 用 `listenAddress.ipv4`（非法回落 `0.0.0.0`，访问改 `127.0.0.1`）。
