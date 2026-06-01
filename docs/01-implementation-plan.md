# SillyTavern Android 实现方案

> 基于开源项目 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 与 [Sanitised/ST-android](https://github.com/Sanitised/ST-android) 模式
> 目标平台:Android(仅 arm64) · 拟定日期:2026-06-01

---

## 0. 一句话定位

一个**原生 Android 壳(Kotlin)**,内置一个**为 android-arm64 编译的 `node` 二进制**,把从 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git) 拉取的**原版 SillyTavern 源码(git submodule,零修改)**放在 `SILLYTAVERN_HOME_DIR/server/` 中运行；启动时显式传入 SillyTavern 已支持的 `--configPath` 与 `--dataRoot`,把配置、用户数据和服务端代码拆成三层。壳按 `SILLYTAVERN_CONFIG_FILE` 里的 `listen` / `listenAddress` / `port` 监控和打开对应地址(默认 `127.0.0.1:8000`,也可按配置监听局域网/`0.0.0.0`);壳提供「**启动/停止服务、改配置、开界面**」等控制,WebView 加载本地服务即得到独立离线 App。

三条硬约束:**独立离线运行**、**仅安卓 arm64**、**易于同步上游**(submodule + CI 自动出包)。

### App 基本信息(暂定)

| 项 | 值 | 说明 |
|---|---|---|
| App 展示名称 | `SillyTavern` | 桌面图标、最近任务、系统设置、通知栏显示名称 |
| 项目目标仓库 | [print-yuhuan/SillyTavern.git](https://github.com/print-yuhuan/SillyTavern.git) | Android 壳项目、GitHub Actions、Release APK 与 CI 缓存所在仓库 |
| `applicationId` / 包名 | `org.sillytavern` | Android 唯一包名；正式发布前固定后不要再改,否则升级会变成另一个 App |
| App 模块名 | `android-app` | Gradle Android 工程模块 |
| 最低/目标系统 | `minSdk 28` / `targetSdk 36` | 兼顾前台服务、WebView、文件权限与现代 Android 约束；最终以实测调整 |
| ABI | `arm64-v8a` only | 与当前目标一致,不出 armeabi-v7a/x86 包 |
| 默认主题 | 跟随系统深浅色 | 首页为原生 Compose 控制台,WebView 内仍由 SillyTavern 自己控制 |
| 默认入口 Activity | `LauncherActivity` | 负责启动/停止服务、显示状态、打开 WebView |
| WebView Activity | `WebViewActivity` | 只加载本机或配置允许的 SillyTavern URL |
| 前台服务 | `NodeService` | 管理 `libnode.so server.js` 进程 |
| 通知渠道 | `SillyTavern Service` | 常驻通知,显示运行状态、端口、停止动作 |
| App 私有根目录 | `APP_FILES_DIR/sillytavern` | 即 `SILLYTAVERN_HOME_DIR`,存放 `server/`、`config/`、`data/` |
| 备份显示名 | `SillyTavern Backup` | 导出备份时的默认文件/目录名 |
| Release APK 命名 | `SillyTavern-android-arm64-v8a-v<version>.apk` | CI 产物命名,便于区分架构和版本 |
| Release 触发方式 | 推送 `v*` Git Tag,例如 `v1.0.0` | 只有版本 Tag 触发 APK 打包并发布到 GitHub Releases |
| Release 签名 | CI 读取 GitHub Secrets 中的 keystore | M0 可用 debug APK,正式 `v*` Release 必须签名 |

版本规则建议:Android 壳版本用 `appVersionName`,内置 SillyTavern 版本另写入 `SILLYTAVERN_VERSION_FILE`。例如 App `1.0.0`,内置 SillyTavern `1.18.0`;二者不要混成一个版本号。

---

## 1. 架构总览

```
┌─────────────────────────── Android App (Kotlin/Compose) ─────────────────────────────┐
│  Launcher 首页        配置编辑页        设置/日志页              WebView Activity    │
│  [启动][停止][打开]   config/ + data/  版本切换/备份/更新     监控/打开 URL 来自配置 │
└───────────────┬────────────────────────────────────────────────────────┬─────────────┘
                │ bind / 控制                                            │ 加载
        ┌───────▼─────────────────────┐                                  │
        │  ForegroundService          │  spawn(ProcessBuilder)           │
        │  (保活 + 持久通知 + 进程管理) ├──────────► node server.js ◄────┘
        └─────────────────────────────┘              │
                                                     ├─ libnode.so  (来自 nativeLibraryDir,只读可执行)
                                                     ├─ SILLYTAVERN_SERVER_DIR/ (server.js + SillyTavern 源码 + node_modules)
                                                     ├─ SILLYTAVERN_CONFIG_DIR/ (config.yaml, --configPath)
                                                     └─ SILLYTAVERN_DATA_DIR/   (用户数据, --dataRoot)
```

---

## 2. 关键技术决策(成败都在这里,务必先看)

| # | 决策点 | 方案 | 说明 |
|---|---|---|---|
| 2.1 | Node 运行时 | **内置 android-arm64 `node` 二进制** | 沿用 ST-android。需跟 SillyTavern 的 Node ≥20 |
| 2.2 | **可执行权限(W^X)** | **`node` 必须以 `libnode.so` 形式打进 `jniLibs/arm64-v8a/`,从 `nativeLibraryDir` 执行** | ⚠️**最关键的坑**。Android 10+ 禁止从应用可写目录 exec;只有 `nativeLibraryDir` 既只读又可执行。必须 `android:extractNativeLibs="true"`,否则 .so 不落地成真实文件、无法 exec |
| 2.3 | 进程保活 | **前台服务 + 持久通知** | 否则后台被杀;Android 14 需声明 `foregroundServiceType` |
| 2.4 | 数据/代码/配置分离 | 用 SillyTavern 原生支持的 **`--configPath` + `--dataRoot`** 把配置和用户数据放到服务端代码目录之外 | 这样更新 `SILLYTAVERN_SERVER_DIR` 不会动 `config/` 和 `data/`,升级不丢配置/数据 |
| 2.5 | 协议/监听地址 | **HTTP(默认关 SSL),监听地址由 `SILLYTAVERN_CONFIG_FILE` 决定** | 源码中 `listen` 是布尔值；`false` 绑定 `127.0.0.1`, `true` 时按 `listenAddress.ipv4` / `listenAddress.ipv6` 绑定。壳生成 URL 时必须按这组字段解析,不要把 `listen` 当字符串 IP。 |
| 2.6 | Android 外壳技术栈 | **Kotlin + Jetpack Compose,不选 Flutter** | 本项目只做 Android,核心工作是 `ForegroundService`、`ProcessBuilder`、`nativeLibraryDir`、WebView、通知、SAF、后台保活和文件目录管理。Kotlin 直接调用系统 API,调试链路短；Flutter 需要额外引擎和 platform channel,对这种“原生服务控制台 + WebView 容器”的收益不明显,还会增大包体和维护面。 |

> 关于 2.2:SillyTavern 的依赖现已高度 WASM 化(`@jimp/wasm-*`、`isomorphic-git` 纯 JS、`tiktoken` 走 WASM),**基本没有 `.node` 原生附件**。若个别依赖仍带 `.node`,同样要按 `lib*.so` 方式打包并修正其加载路径。打包时务必核验一遍。

Node 产物来源(统一渠道):

libnode.so 由 `third_party/Android-Node-Builder/` 子模块的 `build-libnode.yml` 构建,并随其 GitHub Release 发布。当前 Node `24.16.0`、API 28、arm64-v8a 的产物见 [Android-Node-Builder v1.0.0 Release](https://github.com/print-yuhuan/Android-Node-Builder/releases/tag/v1.0.0)(含 `libnode.so` 与 `libnode.so.sha256`)。CI 不读取本机 `D:\...` 路径,统一从该 Release 直链下载,校验 `libnode.so.sha256`(仅比对哈希值)后放入 `android-app/src/main/jniLibs/arm64-v8a/libnode.so`。

Node 自动编译不在本仓库重复实现,`third_party/Android-Node-Builder/` 使用 [print-yuhuan/Android-Node-Builder.git](https://github.com/print-yuhuan/Android-Node-Builder.git) 作为 Git Submodule。该 submodule 的 `build-libnode.yml` 负责构建 `libnode.so`,默认参数为 Node `24.16.0`、Android NDK `r27d`、Android API `28`、目标架构 `arm64-v8a`;上传产物包含 `arm64-v8a/libnode.so` 和 `arm64-v8a/libnode.so.sha256`。本仓库只在 GitHub Actions 中触发/下载/校验/消费该产物。

本机开发电脑环境不完整,不作为 APK 构建环境。不要在 `D:\SillyTavern` 本地尝试 Gradle 构建或打包 APK;可安装 APK 统一由 GitHub Actions 的 CI 工作流自动化产出。

---

## 3. 仓库结构(单仓 monorepo)

仓库根目录固定使用 `SillyTavern/`。为避免和上游源码目录混淆,仓库内部通过子目录命名区分 Android 壳、上游源码、Node 构建和打包脚本。

命名原则:

- 仓库名:`SillyTavern`。
- Git 远端目标仓库:[print-yuhuan/SillyTavern.git](https://github.com/print-yuhuan/SillyTavern.git)。
- 上游源码固定从 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git) 以 Git Submodule 拉取到 `third_party/SillyTavern/`,保留上游项目原名。
- Node 构建仓库固定从 [print-yuhuan/Android-Node-Builder.git](https://github.com/print-yuhuan/Android-Node-Builder.git) 以 Git Submodule 拉取到 `third_party/Android-Node-Builder/`,与 `third_party/SillyTavern/` 同级统一管理,叶子目录名使用正式仓库名。
- `D:\SillyTavern\SillyTavern` 仅作为编写方案时的本地参考样本,不得作为 APK 打包输入。
- Android 工程模块用 `android-app/`,比泛用的 `app/` 更清楚。
- Node 产物接入、资产打包、CI 脚本分别独立目录,避免塞进 Android 模块。
- APK 构建和打包只在 GitHub Actions 中执行;本机不尝试打包 APK。
- CI 生成物只进 `android-app/src/main/assets/` 和 `android-app/src/main/jniLibs/`,不要提交临时构建目录。

```
SillyTavern/
├─ android-app/               # Android 工程 (Kotlin + Jetpack Compose)
│  ├─ src/main/
│  │  ├─ java/.../LauncherActivity.kt      # 首页
│  │  ├─ java/.../WebViewActivity.kt       # SillyTavern 界面
│  │  ├─ java/.../NodeService.kt           # 前台服务 + 进程管理
│  │  ├─ java/.../ConfigEditor.kt          # App UI 配置项映射、校验、写回 config.yaml
│  │  ├─ jniLibs/arm64-v8a/libnode.so      # CI 产物(交叉编译的 node)
│  │  ├─ assets/sillytavern-code.zip       # CI 产物(从 third_party/SillyTavern 打包的官方上游源码)
│  │  ├─ assets/sillytavern-modules.zip    # CI 产物(node_modules)
│  │  └─ AndroidManifest.xml
│  └─ build.gradle.kts
├─ third_party/SillyTavern/              # ★ git submodule:https://github.com/SillyTavern/SillyTavern.git,锁定到上游 tag,零修改
├─ third_party/Android-Node-Builder/     # ★ git submodule:https://github.com/print-yuhuan/Android-Node-Builder.git
├─ packaging/                 # 打包 SillyTavern 源码和 node_modules 为 assets zip
├─ scripts/                   # 通用维护脚本:bump submodule、校验依赖、生成版本元数据
├─ docs/                      # 设计文档、实现方案、调试记录
└─ .github/workflows/
   ├─ build-apk.yml           # 构建+发布
   └─ check-upstream.yml      # 定时检测上游新版→bump submodule
```

`.gitmodules` 必须固定为:

```ini
[submodule "third_party/SillyTavern"]
	path = third_party/SillyTavern
	url = https://github.com/SillyTavern/SillyTavern.git
[submodule "third_party/Android-Node-Builder"]
	path = third_party/Android-Node-Builder
	url = https://github.com/print-yuhuan/Android-Node-Builder.git
```

---

## 4. APK 内部布局与首启流程

`D:\SillyTavern\SillyTavern` 仅作为本方案编写和核对 SillyTavern 运行细节时的本地参考样本,不得作为 APK 打包输入,也不要移动到工程内。实际构建只使用从 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git) 拉取并锁定在 `third_party/SillyTavern/` 的源码。目录设计采用 SillyTavern 已支持的 `--configPath` 与 `--dataRoot`，避免把用户配置夹在可热更新的服务端代码里。注意：如果不传 `--configPath`，SillyTavern 默认会在 `server.js` 同级创建 `config.yaml`；Android 壳应显式传 `--configPath`，把它固定到 `SILLYTAVERN_CONFIG_FILE`。

### 4.1 配置文件职责与启动参数边界

SillyTavern 运行时存在两类 `config.yaml`，Android 壳必须明确区分：

| 文件 | 位置 | 职责 | 是否直接生效 |
|---|---|---|---|
| 默认模板 | `SILLYTAVERN_SERVER_DIR/default/config.yaml` | 上游默认配置模板；用于首次创建实际配置，并在升级后补齐新增键 | 否 |
| 实际配置 | `SILLYTAVERN_CONFIG_FILE`，即 `SILLYTAVERN_CONFIG_DIR/config.yaml` | 用户持久配置；由 `--configPath` 指定，由 App UI 结构化读写 | 是 |

上游源码在 standalone 模式下默认读取 `./config.yaml`；如果传入 `--configPath`，实际配置路径改为该参数指定文件。Android 壳必须始终传 `--configPath <SILLYTAVERN_CONFIG_FILE>`，并且不要传 `--global`，因为 `--global` 模式会忽略 `--configPath` 与 `--dataRoot`。

`default/config.yaml` 只随 `SILLYTAVERN_SERVER_DIR` 更新；`SILLYTAVERN_CONFIG_FILE` 必须随 App 数据持久保留。升级上游时允许模板变化，但不允许覆盖用户实际配置。首次启动或升级后，SillyTavern 的 `src/config-init.js` 会以模板为基准创建/补齐实际配置。App 侧可以在服务启动后做一次只读校验：递归提取模板与实际配置的键路径，记录“模板有但实际无 / 实际有但模板无”的差异；差异只用于日志和诊断，不得直接删除用户未知字段。

命令行参数优先级高于 `config.yaml`。因此，App 只应把“路径和外壳控制”固定为 CLI 参数，用户可配置项尽量写入 `SILLYTAVERN_CONFIG_FILE`，避免 UI 修改被 CLI 覆盖后看似不生效。

Android 壳固定传入：

```text
libnode.so server.js \
  --configPath <SILLYTAVERN_CONFIG_FILE> \
  --dataRoot <SILLYTAVERN_DATA_DIR> \
  --browserLaunchEnabled=false
```

可选启动参数按以下策略处理：

| 参数 | 策略 |
|---|---|
| `--port`、`--listen`、`--listenAddressIPv4`、`--listenAddressIPv6`、`--enableIPv4`、`--enableIPv6`、`--dnsPreferIPv6` | 常规情况下不传，由 `config.yaml` 决定；仅调试/一次性安全模式可临时覆盖 |
| `--ssl`、`--certPath`、`--keyPath`、`--keyPassphrase` | 常规情况下不传，由 `config.yaml` 决定；Android WebView 场景默认保持 HTTP |
| `--whitelist`、`--basicAuthMode`、`--corsProxy`、`--disableCsrf` | 常规情况下不传，由 `config.yaml` 决定；安全相关覆盖必须在日志中明确标注 |
| `--enableKeepAlive`、`--requestProxyEnabled`、`--requestProxyUrl`、`--requestProxyBypass`、`--heartbeatInterval` | 常规情况下不传，由 `config.yaml` 决定；如 App 做诊断模式，可作为临时覆盖 |
| `--global` | Android 壳禁止使用 |
| `--autorun`、`--autorunHostname`、`--autorunPortOverride`、`--avoidLocalhost` | 已弃用，不使用；改用 `browserLaunch*` 新参数 |

| 名称 | 定义 | 用途 |
|---|---|---|
| `APP_NATIVE_LIB_DIR` | `applicationInfo.nativeLibraryDir` | Android 解压出的原生库目录，只读可执行 |
| `NODE_BIN` | `APP_NATIVE_LIB_DIR/libnode.so` | 内置 Node 可执行文件 |
| `APP_FILES_DIR` | `filesDir` | App 私有可写文件根目录 |
| `SILLYTAVERN_HOME_DIR` | `APP_FILES_DIR/sillytavern` | SillyTavern 在 App 私有目录下的专用根目录 |
| `SILLYTAVERN_SERVER_DIR` | `SILLYTAVERN_HOME_DIR/server` | SillyTavern 服务端程序目录；`server.js`、`default/config.yaml`、`src/`、`public/`、`node_modules/` 都在这里 |
| `SILLYTAVERN_SERVER_JS` | `SILLYTAVERN_SERVER_DIR/server.js` | SillyTavern 启动入口 |
| `SILLYTAVERN_CONFIG_DIR` | `SILLYTAVERN_HOME_DIR/config` | Android 壳管理的配置目录 |
| `SILLYTAVERN_CONFIG_FILE` | `SILLYTAVERN_CONFIG_DIR/config.yaml` | 通过 `--configPath` 传给 SillyTavern；首次启动时由 `src/config-init.js` 从 `default/config.yaml` 创建/补齐 |
| `SILLYTAVERN_NODE_MODULES_DIR` | `SILLYTAVERN_SERVER_DIR/node_modules` | SillyTavern Node 依赖目录 |
| `SILLYTAVERN_DATA_DIR` | `SILLYTAVERN_HOME_DIR/data` | 通过 `--dataRoot` 传给 SillyTavern 的用户数据目录 |
| `SILLYTAVERN_VERSION_FILE` | `SILLYTAVERN_HOME_DIR/version.json` | 当前 SillyTavern 版本、Node 要求和资产包版本标记 |
| `APP_CACHE_DIR` | `cacheDir` | App 缓存根目录 |
| `NODE_TMP_DIR` | `APP_CACHE_DIR/node-tmp` | 传给 Node 的 `TMPDIR` |

安装、首次打开 App 并首次启动 SillyTavern 服务后的目标结构：

```text
APP_NATIVE_LIB_DIR/
└─ libnode.so                         # NODE_BIN, 只读可执行

APP_FILES_DIR/
└─ sillytavern/                       # SILLYTAVERN_HOME_DIR
   ├─ server/                         # SILLYTAVERN_SERVER_DIR, 可被热更新整体替换
   │  ├─ server.js                    # SILLYTAVERN_SERVER_JS
   │  ├─ package.json                 # 当前源码 engines.node: >=20
   │  ├─ default/config.yaml          # SillyTavern 默认配置模板
   │  ├─ src/
   │  ├─ public/
   │  ├─ plugins/
   │  └─ node_modules/                # SILLYTAVERN_NODE_MODULES_DIR
   ├─ config/
   │  └─ config.yaml                  # SILLYTAVERN_CONFIG_FILE, 首次启动时按 --configPath 创建
   ├─ data/                           # SILLYTAVERN_DATA_DIR, dataRoot
   │  ├─ default-user/
   │  ├─ _cache/
   │  ├─ _css/
   │  ├─ _errors/
   │  ├─ _storage/
   │  ├─ _uploads/
   │  └─ cookie-secret.txt
   └─ version.json                    # SILLYTAVERN_VERSION_FILE

APP_CACHE_DIR/
└─ node-tmp/                          # NODE_TMP_DIR
```

- **安装后**:`NODE_BIN` 已位于 `APP_NATIVE_LIB_DIR/libnode.so`。
- **首次打开 App**(显示进度条,约数十秒):
  1. 把 `assets/sillytavern-code.zip` 解压到 `SILLYTAVERN_SERVER_DIR`。
  2. 把 `assets/sillytavern-modules.zip` 解压到 `SILLYTAVERN_NODE_MODULES_DIR`。
  3. 创建 `SILLYTAVERN_CONFIG_DIR`、`SILLYTAVERN_DATA_DIR`、`NODE_TMP_DIR`、`SILLYTAVERN_HOME_DIR` 等壳侧目录。
  4. 落一个 `SILLYTAVERN_VERSION_FILE` 标记当前 SillyTavern 版本、Node 要求和资产包版本。
- **首次启动 SillyTavern 服务**:
  1. 壳不要自己手写一份 `config.yaml`；直接运行 `server.js --configPath <SILLYTAVERN_CONFIG_FILE> --dataRoot <SILLYTAVERN_DATA_DIR>`。
  2. SillyTavern 的 `src/config-init.js` 会在 `SILLYTAVERN_CONFIG_FILE` 不存在时,从 `SILLYTAVERN_SERVER_DIR/default/config.yaml` 创建配置，并在后续版本补齐缺失字段。
  3. 服务启动成功后重新读取 `SILLYTAVERN_CONFIG_FILE`,后续启动、健康检查和 WebView URL 都按该文件计算，不能把监控目标写死为 `127.0.0.1`。
  4. 对 `dataRoot`、`browserLaunch.enabled` 这类 App 管理字段,运行时以命令行参数为准；配置页可以展示但应锁定或标注“由 App 管理”。
  5. 启动日志必须打印本次实际使用的 `SILLYTAVERN_SERVER_DIR`、`SILLYTAVERN_CONFIG_FILE`、`SILLYTAVERN_DATA_DIR` 和最终访问 URL，便于排查“改了哪个 config.yaml 才生效”。
- **更新边界**:`SILLYTAVERN_SERVER_DIR` 是可替换层；`SILLYTAVERN_CONFIG_DIR` 与 `SILLYTAVERN_DATA_DIR` 是持久层，热更新、修复和 APK 升级都不能覆盖。

`SILLYTAVERN_VERSION_FILE` 使用 JSON,首版字段固定如下:

```json
{
  "schemaVersion": 1,
  "appVersionName": "1.0.0",
  "appVersionCode": 1,
  "releaseTag": "v1.0.0",
  "sillyTavern": {
    "source": "https://github.com/SillyTavern/SillyTavern.git",
    "commit": "<submodule-commit>",
    "tag": "<upstream-tag-or-empty>"
  },
  "node": {
    "version": "24.16.0",
    "androidApi": 28,
    "abi": "arm64-v8a",
    "sha256": "<libnode.so-sha256>"
  },
  "assets": {
    "codeZipSha256": "<sillytavern-code.zip-sha256>",
    "modulesZipSha256": "<sillytavern-modules.zip-sha256>",
    "builtAt": "<ci-build-iso-time>"
  }
}
```

App 升级、修复和热更新都先读取该文件。`schemaVersion` 不兼容时停止自动热更新,提示用户走 APK 级更新或修复。

---

## 5. 服务生命周期(对应「启动 / 停止服务」)

状态机:`Stopped → Starting → Running → Stopping → Stopped`(异常进 `Error`)。

**启动**(`NodeService` 内,骨架):

```kotlin
val defaultPort = 8000

val nodeBin = File(applicationInfo.nativeLibraryDir, "libnode.so")                 // NODE_BIN
val sillyTavernHome = File(filesDir, "sillytavern")                               // SILLYTAVERN_HOME_DIR
val serverDir = File(sillyTavernHome, "server")                                    // SILLYTAVERN_SERVER_DIR
val configDir = File(sillyTavernHome, "config")                                    // SILLYTAVERN_CONFIG_DIR
val dataDir = File(sillyTavernHome, "data")                                        // SILLYTAVERN_DATA_DIR
val configFile = File(configDir, "config.yaml")                                    // SILLYTAVERN_CONFIG_FILE
val nodeTmp = File(cacheDir, "node-tmp")                                           // NODE_TMP_DIR

val config = readConfigOrNull(configFile)
val port = config?.port ?: defaultPort
val scheme = if (config?.ssl?.enabled == true) "https" else "http"
val listenEnabled = config?.listen ?: false
val ipv4ListenAddress = normalizeIPv4(config?.listenAddress?.ipv4 ?: "0.0.0.0")
val healthCheckHost = if (listenEnabled) {
    if (ipv4ListenAddress == "0.0.0.0") "127.0.0.1" else ipv4ListenAddress
} else {
    "127.0.0.1"
}
val launchUrl = "$scheme://$healthCheckHost:$port"

val pb = ProcessBuilder(
    nodeBin.absolutePath, "server.js",
    "--configPath", configFile.absolutePath,
    "--dataRoot", dataDir.absolutePath,
    "--browserLaunchEnabled=false"
).directory(serverDir)
pb.environment().apply {
    put("HOME", filesDir.absolutePath)
    put("TMPDIR", nodeTmp.absolutePath)         // node 需要可写临时目录
    put("PATH", nodeBin.parent)
}
pb.redirectErrorStream(true)
process = pb.start()
// 1) 起线程读 process.inputStream → 写日志文件 + 推 UI
// 2) 轮询 GET launchUrl 直到 200 → 置 Running、点亮「打开界面」
// 3) 若 configFile 是本次启动后新生成的,重新读取它刷新监听地址、端口和配置页状态
// 4) 若 listen=true 且 listenAddress.ipv4=0.0.0.0,另展示局域网 IP URL
```

**停止**:`process.destroy()`(必要时 `destroyForcibly()` 并清理子进程),`stopForeground()` + `stopSelf()`,状态回 `Stopped`。

**保活**:`startForegroundService()` + 常驻通知(显示状态/端口,带「停止」动作按钮);建议引导用户为本 App **关闭电池优化**(国产 OEM 杀后台严重)。

---

## 6. UI 风格与首页设计

整体原则:**原生控制台 + SillyTavern 内容原样呈现**。Android 壳只负责服务生命周期、配置、更新、日志、备份等系统级操作；聊天、角色、扩展、主题等核心体验仍交给 WebView 内的 SillyTavern 前端。

视觉风格:

| 维度 | 方案 |
|---|---|
| 技术 | Kotlin + Jetpack Compose + Material 3 |
| 气质 | 安静、工具型、偏开发者控制台；不要做营销页、启动页大 Hero 或重装饰界面 |
| 主题 | 跟随系统深浅色；默认深色时可接近 SillyTavern 的暗色氛围,但不复制 Web 前端 CSS |
| 色彩 | 中性色为主,状态色只用于运行/停止/错误/警告；避免大面积紫色渐变或花哨背景 |
| 形状 | 8dp 以内圆角,信息分区清晰；不要卡片套卡片 |
| 图标 | 使用 Material Icons 或 lucide 风格等线性图标:启动、停止、打开、设置、日志、备份、更新 |
| 信息密度 | 首页首屏必须能看到服务状态、监听地址、运行时长、主要操作按钮 |
| WebView | 全屏/沉浸为主,顶部只保留必要的返回、刷新、外部打开、状态入口；不在 WebView 外再做一层复杂导航 |
| 可访问性 | 所有状态不能只靠颜色表达；按钮有文本和 contentDescription；日志等长文本支持复制 |
| 软件语言 | App 外壳所有页面、按钮、控件标签、空状态、错误提示、确认弹窗、通知、权限说明和无障碍描述统一使用**简体中文** |

信息架构:

- `首页`:服务状态、启动/停止、打开界面、当前 URL、运行时长、最近日志摘要。
- `配置`:App UI 表单接管所有可改配置项,使用适合字段类型的原生控件,显示中文配置名称,保存后结构化写回 `config.yaml` 并提示重启。
- `日志`:Node stdout/stderr、最近启动错误、复制/导出日志。
- `数据`:备份、恢复、修复服务端文件、清理缓存。
- `关于`:App 版本、内置 SillyTavern 版本、Node 版本、AGPL 源码链接。

首页线框:

```
┌─────────────────────────────────────────┐
│  SillyTavern                       ⚙ 设置 │
├─────────────────────────────────────────┤
│  状态:● 运行中   http://<配置地址>:<端口>   │   ← 状态卡(颜色随状态变)
│        运行时长 00:12:34                   │
├─────────────────────────────────────────┤
│        [ ▶ 启动服务 ] / [ ■ 停止服务 ]      │   ← 主按钮随状态切换
│        [ 🌐 打开界面 ]  (Running 时可用)    │
├─────────────────────────────────────────┤
│  ⚙ 修改配置        📄 查看日志             │
│  🔄 检查更新       💾 备份/恢复            │
└─────────────────────────────────────────┘
```

按钮 ↔ 状态映射:

| 状态 | 启动 | 停止 | 打开界面 | 修改配置 |
|---|---|---|---|---|
| Stopped | 可点 | 禁用 | 禁用 | 可点 |
| Starting | 禁用(转圈) | 可点(取消) | 禁用 | 禁用 |
| Running | 禁用 | 可点 | **可点** | 可点(提示需重启生效) |

---

## 7. 配置编辑(对应「修改 SillyTavern 配置文件」)

`config.yaml` 的修改完全由 App UI 接管。用户不直接编辑 YAML 文本,也不提供“原始 YAML 编辑器”;所有配置项都在 App 的配置页里修改,保存时由 `ConfigEditor` 结构化写回 `SILLYTAVERN_CONFIG_FILE`(`SILLYTAVERN_CONFIG_DIR/config.yaml`)。

实现规则:

- 配置页启动时读取 `SILLYTAVERN_CONFIG_FILE`;文件不存在时显示「尚未生成配置」,引导用户先启动一次 SillyTavern 服务,由 `src/config-init.js` 按 `--configPath` 创建默认配置。
- 配置页面向用户的选项名称必须使用清楚的**简体中文**文案,不要把 `port`、`listen`、`listenAddress.ipv4`、`basicAuthMode` 等 YAML 英文字段名直接搬到界面上。英文键只允许作为代码映射、调试日志或开发文档中的内部字段。
- 配置项必须使用最适合语义的控件:布尔值用开关,互斥模式用分段按钮/单选项,有限集合用下拉菜单或单选列表,端口等数字用数字输入,IP 地址用带预设项的文本输入,密码用密码输入,只读/由 App 管理的字段用锁定信息行。
- 所有控件标签、辅助说明、校验错误、保存成功提示、重启确认弹窗、通知内容和无障碍描述统一使用简体中文。
- App UI 对配置项做类型约束和范围校验,例如端口范围、IP 地址格式、布尔开关、密码字段非空规则。
- 写入必须使用 YAML 解析/序列化库处理结构化数据,禁止用字符串替换改 YAML。
- 保存时先更新内存中的 YAML 对象,保留 App 暂未接管的未知字段,再原子写入临时文件并替换 `SILLYTAVERN_CONFIG_FILE`,避免写到一半损坏配置。
- App 管理字段如 `dataRoot`、`browserLaunch.enabled` 运行时仍由命令行参数兜底覆盖;配置页可以展示但要标注“由 App 管理”并禁止用户改坏。
- 所有变更写入后显示「需重启服务生效」;若服务运行中保存,弹出「立即重启服务?」。
- 「恢复默认配置」也通过 App UI 执行:停止服务后删除或重建 `SILLYTAVERN_CONFIG_FILE`,再让 SillyTavern 用 `default/config.yaml` 补齐,最后重新加载到表单。

App UI 接管的配置项分四档（**其余字段一律「保留不接管」：读写时原样保留、UI 不显示**）。下表“内部字段”仅供实现映射,界面只显示“中文显示名称”。

**基础（默认配置页）**

| 内部字段 | 中文显示名称 | 控件 | 默认/规则 |
|---|---|---|---|
| `port` | 服务端口 | 数字输入 | 默认 8000;1–65535;越界提示「端口必须在 1 到 65535 之间」 |
| `listen` | 允许局域网访问 | 开关 | 关=仅本机;开=允许局域网设备（见下「局域网放行」注） |
| `listenAddress.ipv4` | IPv4 监听地址 | 预设下拉 + IP 输入 | 预设「全部 0.0.0.0 / 仅本机 127.0.0.1 / 手动」;仅 listen 开启时可编辑 |
| `protocol.ipv4` | 启用 IPv4 | 开关 | 默认开 |
| `protocol.ipv6` | 启用 IPv6 | 开关 | 默认关 |
| `ssl.enabled` | 启用 HTTPS | 开关 | WebView 场景建议关;开启时显示证书相关中文警告 |
| `whitelistMode` | 启用访问白名单 | 开关 | 默认开 |
| `basicAuthMode` | 启用访问密码 | 开关 | 默认关;开启后显示账号/密码 |
| `basicAuthUser.username` | 访问账号 | 文本 | 启用访问密码时必填 |
| `basicAuthUser.password` | 访问密码 | 密码框 + 显隐 | 启用访问密码时必填 |

**高级（默认收起）**

| 内部字段 | 中文显示名称 | 控件 | 规则 |
|---|---|---|---|
| `listenAddress.ipv6` | IPv6 监听地址 | 文本 | 默认 `[::]`;仅启用 IPv6 时有意义 |
| `dnsPreferIPv6` | DNS 优先 IPv6 | 开关 | 仅 IPv6 网络稳定时启用 |
| `enableKeepAlive` | HTTP keep-alive | 开关 | `ECONNRESET` 等网络问题时对照排查 |
| `heartbeatInterval` | 心跳间隔（秒） | 数字 | 0=关闭 |
| `sessionTimeout` | 会话超时（秒） | 数字 | -1=不过期 / 0=关浏览器即过期 / 正数=空闲过期 |
| `whitelist` | IP 白名单 | 列表编辑 | listen 开启时给局域网设备放行（见「局域网放行」注） |
| `ssl.certPath` / `ssl.keyPath` | 证书路径 / 私钥路径 | 文本 | 仅 HTTPS 开启时显示 |
| `ssl.keyPassphrase` | 私钥密码 | 密码框 | 仅 HTTPS 开启时;官方建议改用 CLI/env |
| `requestProxy.enabled` | 外发请求代理 | 开关 | 控制所有外发 HTTP/HTTPS 请求是否走代理 |
| `requestProxy.url` | 代理地址 | 文本 | http/https/socks/socks5/socks4/pac |
| `requestProxy.bypass` | 代理绕过主机 | 列表编辑 | 默认 localhost、127.0.0.1 |
| `logging.enableAccessLog` | 访问日志 | 开关 | — |
| `logging.minLogLevel` | 日志级别 | 下拉 | DEBUG=0 / INFO=1 / WARN=2 / ERROR=3 |
| `skipContentCheck` | 跳过默认内容检查 | 开关 | 跳过每次启动的内容文件复制 |
| `enableDownloadableTokenizers` | 允许下载分词器 | 开关 | 省流量/存储可关 |
| `extensions.enabled` | 启用 UI 扩展 | 开关 | — |
| `extensions.autoUpdate` | 扩展自动更新 | 开关 | — |
| `extensions.models.autoDownload` | 扩展模型自动下载 | 开关 | 省流量/存储可关 |
| `enableUserAccounts` | 多用户账号 | 开关 | 默认关;开启后 SillyTavern 启用账号/登录 |
| `enableDiscreetLogin` | 隐蔽登录 | 开关 | 仅 `enableUserAccounts` 开启时可编辑;登录页隐藏用户列表 |

**危险（默认收起，开启需二次确认 + 中文风险提示）**

| 内部字段 | 中文显示名称 | 控件 | 规则 |
|---|---|---|---|
| `enableCorsProxy` | 启用 CORS 代理 | 危险开关 | 暴露 `/proxy/`;开启前二次确认 |
| `disableCsrfProtection` | 禁用 CSRF 保护 | 危险开关 | 本地建议保持开启（即不禁用）;开启前二次确认 |

**App 管理（只读锁定，运行时被 CLI 覆盖）**

| 内部字段 | 中文显示名称 | 控件 | 规则 |
|---|---|---|---|
| `dataRoot` | 数据目录 | 锁定信息行 | 显示「由 App 管理」;运行时 `--dataRoot` 覆盖 |
| `browserLaunch.enabled` | 自动打开浏览器 | 锁定信息行 | 显示「由 App 管理」;运行时 `--browserLaunchEnabled=false` 覆盖 |

**保留不接管（读写时原样保留，UI 不显示）**：反代/部署（`enableForwardedWhitelist`、`whitelistDockerHosts`、`forwardedHeaders.*`、`rateLimiting.*`、`cors.*`、`sso.*`、`hostWhitelist.*`、`privateAddressWhitelist.*`）、多用户细分（`perUserBasicAuth`）、危险脚枪（`securityOverride`、`allowKeysExposure`）、性能/缩略图/缓存（`performance.*`、`thumbnails.*`、`cacheBuster.*`）、备份（`backups.*` → 第八阶段「数据」页）、内容/下载/插件（`whitelistImportDomains`、`requestOverrides`、`git.backend`、`promptPlaceholder`、`enableServerPlugins`、`enableServerPluginsAutoUpdate`）、`browserLaunch` 其余子项（`browser`/`hostname`/`port`/`avoidLocalhost`）、以及 **LLM 提供商调优**（`openai`/`deepl`/`mistral`/`ollama`/`claude`/`gemini.*`，由 SillyTavern 网页端连接设置自行管理）。

> **局域网放行**：`whitelistMode: true` 时即使开启 `listen`，白名单外的局域网 IP 仍被拒。首批方案＝暴露 `whitelist` 列表编辑由用户手动加设备/网段;二期可加「开启局域网访问时引导加入本机网段」。

> **CLI 优先级 > config.yaml**：App 常规启动只固定传 `--configPath`、`--dataRoot`、`--browserLaunchEnabled=false`，故「App 管理」项恒被覆盖、UI 只读;其余接管项写入 `config.yaml` 后于（重启后）生效。

禁止把弃用参数 `--autorun`、`--autorunHostname`、`--autorunPortOverride`、`--avoidLocalhost` 映射到新 UI；只保留在迁移说明或调试日志中识别。

地址解析规则：SillyTavern 当前源码中 `listen` 是布尔值。`listen: false` 时 IPv4 监听 URL 是 `<scheme>://127.0.0.1:<port>`；`listen: true` 时 IPv4 绑定到 `listenAddress.ipv4`,默认 `0.0.0.0:<port>`。`0.0.0.0` 只能作为绑定地址,不能作为 WebView 目标；App 内健康检查仍使用 `<scheme>://127.0.0.1:<port>`,首页额外展示当前 WLAN/以太网局域网 IP URL 给其他设备。若 `listenAddress.ipv4` 是具体局域网 IP,则健康检查和展示 URL 均按该 IP 生成；若该值无效,按 SillyTavern 源码逻辑等价回落到 `0.0.0.0`。`scheme` 来自 `ssl.enabled`,但 Android 内置 WebView 场景建议保持 `ssl.enabled: false`。

---

## 8. WebView 配置要点(`WebViewActivity`)

必须开启/接管:`javaScriptEnabled`、`domStorageEnabled`、`databaseEnabled`(SillyTavern 重度依赖 IndexedDB/localStorage)、`allowFileAccess`;`WebChromeClient.onShowFileChooser`(导入角色卡 PNG)、`setDownloadListener`(导出聊天/卡)、`onPermissionRequest`(TTS/STT 麦克风);加载前先确认健康检查通过。

---

## 9. 选项设置(对应「一些选项设置」)

- **SillyTavern 版本/分支/ZIP 切换**(同步入口,见 §10b)
- 启动 App 即**自动开服**(开关)
- **屏幕常亮 / 后台保活**引导(跳电池优化设置)
- **数据备份/恢复**:用 SAF(`ACTION_OPEN_DOCUMENT_TREE`)把 `SILLYTAVERN_DATA_DIR` 打包导出/导入
- **修复**:重新从 assets 解压 `SILLYTAVERN_SERVER_DIR`,不动 `SILLYTAVERN_CONFIG_DIR` 和 `SILLYTAVERN_DATA_DIR`
- **检查更新**、**关于**(含 AGPL 源码链接)

---

## 10. 上游同步 + 更新机制

**a) 源码隔离(同步的根基)**
SillyTavern 上游源码只从 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git) 拉取,作为 `third_party/SillyTavern` **submodule 锁定 tag**;壳代码全在 `android-app/`,**绝不改 SillyTavern 文件**。升级 = 移动 submodule 指针。`D:\SillyTavern\SillyTavern` 只作本地参考,不参与构建、打包或热更新。

Node 构建仓库只从 [print-yuhuan/Android-Node-Builder.git](https://github.com/print-yuhuan/Android-Node-Builder.git) 拉取,作为 `third_party/Android-Node-Builder` **submodule 锁定 commit/tag**;本仓库不复制 Docker/NDK 编译逻辑,只在 CI 中消费该 submodule 的工作流/产物规范。

**b) CI 自动跟进**
- `check-upstream.yml`(定时 cron):调 GitHub API 查 SillyTavern 最新 release → 若有新版,bump submodule → 提 PR / 触发构建。
- `build-apk.yml`:仅在推送 `v*` 版本 Tag 时触发发布流程,例如 `v1.0.0`。流程为初始化 submodules → 通过 `third_party/Android-Node-Builder/` 触发/下载/校验 `libnode.so` 产物(**产物缓存**,避免每次 2–3 小时)→ 装 SillyTavern 依赖 → 打包 assets → `assembleRelease` 签名 → 发 GitHub Releases。
- 普通分支 push / PR 不发布 GitHub Releases;最多只跑 lint、单元测试或 dry-run 构建。
- 本地电脑不跑 APK 构建;如需验证 APK,以 GitHub Actions 产出的 artifact/release APK 为准。

**c) 应用内更新(双层,可叠加)**
- **APK 级**:查 Releases API,提示下载并安装(需 `REQUEST_INSTALL_PACKAGES`)。
- **SillyTavern 资产级热更新**(优雅):因 SillyTavern 几乎纯 JS+WASM,下载新版 `sillytavern-code.zip`(+ 必要时 `node_modules`)替换 `SILLYTAVERN_SERVER_DIR`、**保留 `SILLYTAVERN_CONFIG_DIR` 与 `SILLYTAVERN_DATA_DIR`** 即可,无需重打 APK。
  - ⚠️ **判定规则**:更新包元数据里带「所需 Node 版本 / 是否新增原生依赖」。一旦**Node 大版本提升**或**新增 `.node` 原生模块**,资产热更失效 → 强制走 APK 级更新。

---

## 11. 权限与 Manifest 要点

Gradle 端先固定应用身份:

```kotlin
android {
    namespace = "org.sillytavern"

    defaultConfig {
        applicationId = "org.sillytavern"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging { jniLibs { useLegacyPackaging = true } }
}
```

`android-app/src/main/res/values/strings.xml` 至少包含:

```xml
<string name="app_name">SillyTavern</string>
<string name="notification_channel_service">SillyTavern Service</string>
```

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/> <!-- API 34 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>            <!-- API 33 -->
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>      <!-- 自更新 -->
<uses-permission android:name="android.permission.RECORD_AUDIO"/>                  <!-- TTS/STT,可选 -->

<application
    android:label="@string/app_name"
    android:extractNativeLibs="true"
    ...>   <!-- ★ 必须 true,否则 node 无法 exec -->
    <activity android:name=".LauncherActivity" android:exported="true" .../>
    <activity android:name=".WebViewActivity" android:exported="false"/>
    <service android:name=".NodeService"
             android:foregroundServiceType="specialUse"
             android:exported="false"/>
</application>
```

通知标题使用 `SillyTavern`,内容展示运行状态、监听地址和端口,并提供「停止服务」动作。

---

## 12. 构建与 CI 流水线(步骤)

`build-apk.yml` 触发片段固定为:

```yaml
on:
  push:
    tags:
      - 'v*'
permissions:
  contents: write
```

1. **初始化 submodules**:`third_party/SillyTavern/` 指向 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git),`third_party/Android-Node-Builder/` 指向 [print-yuhuan/Android-Node-Builder.git](https://github.com/print-yuhuan/Android-Node-Builder.git)。
2. **准备 Node(android-arm64)**:从 [Android-Node-Builder 的 Release](https://github.com/print-yuhuan/Android-Node-Builder/releases)(当前 `v1.0.0`)直链下载 `libnode.so`,校验 `libnode.so.sha256` 后放入 `android-app/src/main/jniLibs/arm64-v8a/libnode.so`。该产物由 `third_party/Android-Node-Builder/` submodule 的 `build-libnode.yml` 构建,本仓库的 `build-apk.yml` 只做下载/校验/缓存,不重复编译。
3. **装 SillyTavern 依赖**:在 `third_party/SillyTavern/` 内 `npm ci --omit=dev`;核验无 `.node`(有则交叉编译成 `lib*.so`)。
4. **裁剪 + 打包** `third_party/SillyTavern/` 中的官方上游源码与 `node_modules` 成两个 zip 放进 `android-app/src/main/assets/`。
   - `sillytavern-code.zip` 必须包含 `server.js`、`package.json`、`package-lock.json`、`default/`、`src/`、`public/`、`plugins/` 等运行必需文件。
   - `sillytavern-code.zip` 必须排除 `.git/`、`.github/`、测试缓存、临时文件、本地日志和开发构建输出。
   - `sillytavern-modules.zip` 来自 `npm ci --omit=dev` 后的 `node_modules/`;打包前必须扫描 `.node` 原生模块并生成扫描日志。
5. **Release 签名**:M0 调试 APK 可用 debug 签名;正式 `v*` Tag 发布的 APK 必须用 GitHub Secrets 注入 keystore 后签名。
6. GitHub Actions 中执行 `./gradlew assembleRelease` + 签名。
7. 仅当触发 Tag 匹配 `v*`(例如 `v1.0.0`)时,发布 APK 到 **GitHub Releases**(immutable);Release 版本号使用该 Tag。

CI 签名 Secrets 名称固定为:

| Secret | 用途 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | release keystore 的 base64 文本 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 key alias |
| `ANDROID_KEY_PASSWORD` | 签名 key 密码 |

> 本地不执行 APK 构建/打包命令;本节所有构建步骤均指 GitHub Actions CI 环境。

---

## 13. 分阶段里程碑

| 阶段 | 目标 | 验证点 |
|---|---|---|
| **M0 PoC(最高优先)** | 由 GitHub Actions 产出含 `libnode.so` + 最小 `server.js` 的调试 APK,真机安装后 `exec` 跑通 | **验证最大不确定性:Node 能否在真机从 nativeLibraryDir 起来** |
| M1 | 前台服务 + 启动/停止 + 状态机 + 日志 | 后台不被杀 |
| M2 | 首启解压 + `--configPath` + `--dataRoot` + App UI 配置接管 | UI 写回 `config.yaml`,升级不丢配置/数据 |
| M3 | 设置页 + 备份/恢复 + 修复 | — |
| M4 | submodule + CI 自动出包 | bump tag 即出新包 |
| M5 | 双层更新(APK + 资产热更) | 上游更新可秒级同步 |

> 强烈建议**先做 M0**:`exec` 限制和真机能否跑起来,是整条路线唯一的「致命不确定性」,半天就能证伪/证实,别等到后面才发现。

---

## 14. 风险与对策

| 风险 | 对策 |
|---|---|
| W^X 禁止 exec | node 走 `nativeLibraryDir`,`extractNativeLibs=true`(M0 验证) |
| OEM 杀后台 | 前台服务 + 引导关电池优化 |
| 首启解压慢 | 进度 UI;只解压一次 |
| 升级丢数据/配置 | `--configPath` 与 `--dataRoot` 分离;更新只替换 `SILLYTAVERN_SERVER_DIR`,不动 `SILLYTAVERN_CONFIG_DIR` 和 `SILLYTAVERN_DATA_DIR` |
| App 写坏 `config.yaml` | 所有修改走结构化 YAML 读写、字段校验、原子写入;保存前保留未知字段,保存后可立即重新解析校验 |
| Node 版本漂移 | CI 跟 SillyTavern 的 engines 要求;热更带版本判定 |
| 包体偏大 | 仅 arm64;裁剪 devDeps；当前初始化后的 `node_modules` 未发现 `.node` 原生模块,但 CI 仍需每次扫描 |
| **AGPL-3.0** | 仓库公开,About 内提供源码链接与许可证 |

---

## 15. License

SillyTavern 是 **AGPL-3.0**。公开分发 APK 即触发:**整个 App(壳 + 改动)须公开完整对应源码**。保持仓库公开、在「关于」页给出源码与许可证链接即可合规。(非法律意见。)

---

## 附:后续可生成的骨架

- `android-app/` 的 Gradle 配置、`AndroidManifest.xml`
- `NodeService.kt`(前台服务 + 进程管理)
- `LauncherActivity` 首页(启动/停止/打开/改配置)
- `ConfigEditor`(App UI 配置项映射、校验、结构化写回 `SILLYTAVERN_CONFIG_FILE`)
- `.github/workflows/build-apk.yml` 与 `check-upstream.yml`

> 参考项目:[SillyTavern](https://github.com/SillyTavern/SillyTavern) · [Sanitised/ST-android](https://github.com/Sanitised/ST-android)
