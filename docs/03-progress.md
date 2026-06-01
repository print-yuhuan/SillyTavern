# 实施进度

> 按工作计划（[02-work-plan.md](02-work-plan.md)）分阶段推进。本文件记录各阶段完成情况、未完成项与下一步。

## 阶段总览

| 阶段 | 名称 | 状态 |
|---|---|---|
| 第一阶段 | 仓库骨架 | ✅ 已完成 |
| 第二阶段 | M0 真机 PoC | ✅ 已完成（真机验证 Node 启动成功） |
| 第三阶段 | SillyTavern 资产解压 | ✅ 已完成（真机验证通过） |
| 第四阶段 | 完整 NodeService | ✅ 已完成（真机验证通过） |
| 第五阶段 | App UI | ✅ 已完成（控制台多页 UI；待真机回归） |
| 第六阶段 | 配置 UI 接管 config.yaml | 🚧 接管范围已定稿（四档，见 01 §7），待实现 |
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

真机验证结果（已通过）：

- 首次安装真机启动报 `CANNOT LINK EXECUTABLE ... library "libc++_shared.so" not found`：`libnode.so`（NDK/Clang 构建）运行时依赖 `libc++_shared.so`，而 APK 只打了 `libnode.so`。
- 修复：CI 从 NDK r27d 复制 `libc++_shared.so` 一并打进 `jniLibs/arm64-v8a`；`NodeService` 设 `LD_LIBRARY_PATH=nativeLibraryDir` 并在启动前检查该库；CI 增加 APK 内容校验。修复后 M0 真机启动成功。

---

## 第三阶段：SillyTavern 资产解压 ✅

完成情况：

- `core/AssetInstaller`：首启/升级时把 APK 内 `sillytavern-code.zip`、`sillytavern-modules.zip` 解压到 `SILLYTAVERN_SERVER_DIR`（modules zip 顶层即 `node_modules/`）；创建 `config/`、`data/`、`node-tmp/`；写入 `version.json`。
  - 安装判定按 `assets/version.json` 的 `codeZipSha256` / `modulesZipSha256` 与已安装版本比对：一致即跳过（首次只解压一次），不一致或缺失则重装 → APK 升级自动重解压 `server/`。
  - `server/` 为可替换层（重装前清空再解压）；`config/`、`data/` 为持久层，解压/升级绝不触碰 → 升级保留用户配置与数据。
  - `force=true` 提供「修复」语义（仅重解压 `server/`，第八阶段接 UI）。
  - 解压带目录穿越防护与进度回调（`InstallState` StateFlow）。
- `AppPaths`：新增 `serverReady()`、`ensureRuntimeDirs()`。
- `LauncherActivity`：开屏触发解压，进度条展示，解压期间禁用「启动服务」。
- CI（`build-apk.yml`）：启用 `packaging/package-sillytavern.sh`（`npm ci --omit=dev` → 扫描 `.node` → 打两个 zip + `version.json`），每次构建都打进 APK；APK 内容校验扩展为同时检查 `code.zip`/`modules.zip`/`version.json`。
- `build.gradle.kts`：`androidResources { noCompress += "zip" }`，资产按原样打包。

真机回归修复（漏包 webpack.config.js）：首启 `server.js` 报 `ERR_MODULE_NOT_FOUND: webpack.config.js` —— 打包 include 漏了上游根文件 `webpack.config.js`（被 `src/middleware/webpack-serve.js` 直接 import）。修复：打包脚本 include 补入该文件 + 必需项断言 + 打包后 `unzip -Z1` 校验关键文件；`AppPaths.serverReady()` 增加 `webpack.config.js` 检查；首启 webpack 在手机上编译前端库较慢，`HEALTH_TIMEOUT_MS` 由 60s 放宽到 180s。

真机验证（已通过）：首启自动解压、建立 server/config/data 三层；内容文件落入 `data/`（`_css`/`_errors`/`default-user` 等）。

---

## 第四阶段：完整 NodeService ✅

完成情况：

- `NodeService` 由 M0 测试服务切换到真实 SillyTavern：
  `libnode.so server.js --configPath <config.yaml> --dataRoot <data> --browserLaunchEnabled=false`，`cwd=SILLYTAVERN_SERVER_DIR`。
- 启动前依次校验：`libnode.so` 可执行、`libc++_shared.so` 存在、资产已解压（`AssetInstaller.ensureInstalled`）、`serverReady()`。
- 环境变量：`HOME`、`TMPDIR`、`PATH`、`LD_LIBRARY_PATH`（去掉 M0 的 `ST_PORT`）。
- 首启时 `config.yaml` 由 SillyTavern 按 `--configPath` 新建；`awaitConfigFile` 等其出现后重读，按真实 `port`/`listen`/`listenAddress.ipv4`/`ssl.enabled` 计算健康检查与局域网 URL。
- 健康检查轮询、状态机（Stopped/Starting/Running/Stopping/Error）、退出码捕获沿用 M1 实现。

真机验证（已通过）：完整 SillyTavern 1.18.0 启动并监听 `127.0.0.1:8000`；首启 `config.yaml` 由 `--configPath` 自动创建；可停止并再次启动。webpack 编译前端库首启 **17.166s**、第二次命中缓存仅 **999ms**（缓存在 `dataRoot/_webpack`）——印证 `HEALTH_TIMEOUT_MS` 调到 180s 的必要性。

新增核对事项：

- 启动参数需最终统一为 `--configPath <SILLYTAVERN_CONFIG_FILE>`、`--dataRoot <SILLYTAVERN_DATA_DIR>`、`--browserLaunchEnabled=false`；常规启动不应再传 `--port`、`--listen` 等会覆盖配置文件的参数。
- 日志需明确打印默认模板 `SILLYTAVERN_SERVER_DIR/default/config.yaml` 与实际生效配置 `SILLYTAVERN_CONFIG_FILE`，避免后续调试误改模板。
- 需要补一项只读诊断：递归比较模板配置与实际配置的键路径，记录缺失/多余键；只用于诊断，不删除用户未知字段。

---

## 第五阶段：App UI ✅

完成情况（原生控制台多页 UI，实现方案 §6）：

- `LauncherActivity` 精简为只承载主题与窗口；导航与页面移到 `ui/`。
- `ui/Screens.kt`：`AppRoot` 浅层状态导航（首页 hub + 配置/日志/数据/关于，`BackHandler` 返回首页，未引入导航库）；开屏统一请求通知权限并触发资产解压。
- `ui/HomeScreen.kt`：状态卡（状态/URL/局域网/运行时长）、启动/停止/打开界面、二级入口（配置/日志/数据/关于）、最近日志摘要 +「查看完整日志」。
- 日志页：完整日志 + 复制/清空。
- 配置页：只读展示当前 `config.yaml`（端口/局域网/IPv4 地址/协议/HTTPS/白名单/访问密码/心跳）；编辑接管留待第六阶段。
- 数据页：备份/恢复/修复占位（第八阶段），展示数据目录路径。
- 关于页：App 版本、内置 SillyTavern 版本、Node 版本、架构（读 `version.json`）+ AGPL 源码链接。
- 文案全简体中文；状态不只靠颜色（圆点 + 文字标签 + 无障碍描述）；关键按钮含文本与 contentDescription。

待真机回归：各页导航与返回键、配置只读展示、关于版本信息显示正常。

---

## 关键事实备忘

- libnode.so sha256：`fa3ae680f5e796953e3275b84eaa29d51f14346ba9da43ea9b5617f5b461c2de`（Node 24.16.0 / API 28 / arm64-v8a）。
- 本机参考 clone `D:\SillyTavern\SillyTavern` 为 tag 1.18.0，仅供参考，不入库、不打包。
- 上游实际生效配置由 `--configPath` 决定；`default/config.yaml` 只是模板，用于首次创建和补齐实际配置。
- 上游 `server.js` 常用 CLI：`--configPath`、`--dataRoot`、`--browserLaunchEnabled`；完整可用项还包括 `--port`、`--listen`、`--listenAddressIPv4`、`--listenAddressIPv6`、`--enableIPv4`、`--enableIPv6`、`--dnsPreferIPv6`、`--ssl`、`--certPath`、`--keyPath`、`--keyPassphrase`、`--whitelist`、`--basicAuthMode`、`--corsProxy`、`--disableCsrf`、`--enableKeepAlive`、`--requestProxyEnabled`、`--requestProxyUrl`、`--requestProxyBypass`、`--heartbeatInterval`。
- CLI 参数优先级高于 `config.yaml`；Android 壳常规启动只固定传路径和 WebView 控制参数，用户可配置项写入实际配置文件。
- `--global` 会忽略 `--configPath` 与 `--dataRoot`，Android 壳禁止使用。
- 弃用 CLI：`--autorun`、`--autorunHostname`、`--autorunPortOverride`、`--avoidLocalhost`；后续不映射到 UI。
- 健康检查 URL 逻辑对齐上游 `getIPv4ListenUrl`：`listen=false`→`127.0.0.1`；`listen=true` 用 `listenAddress.ipv4`（非法回落 `0.0.0.0`，访问改 `127.0.0.1`）。
