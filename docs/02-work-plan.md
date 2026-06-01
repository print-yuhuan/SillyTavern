# SillyTavern Android 实施工作计划

> 交给 AI Coding 执行前,先让它完整阅读 `01-implementation-plan.md`。本文是实施顺序和验收标准,`01-implementation-plan.md` 是架构与技术细节来源。

## 0. 执行边界

- 目标:实现一个 Android arm64 App,内置 Node 和 SillyTavern,通过 Kotlin + Jetpack Compose 外壳控制服务生命周期、配置、日志、WebView、备份和更新。
- 仓库名固定为 `SillyTavern`。
- 项目推送目标仓库固定为 [print-yuhuan/SillyTavern.git](https://github.com/print-yuhuan/SillyTavern.git)。
- Android 工程模块使用 `android-app/`。
- 上游 SillyTavern 源码必须从 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git) 拉取到 `third_party/SillyTavern/`,作为 Git Submodule 管理。
- Node 构建仓库必须从 [print-yuhuan/Android-Node-Builder.git](https://github.com/print-yuhuan/Android-Node-Builder.git) 拉取到 `third_party/Android-Node-Builder/`,作为 Git Submodule 管理,与 `third_party/SillyTavern/` 同级统一;叶子目录名使用正式仓库名。
- 不修改上游 SillyTavern 源码;所有 Android 壳代码、脚本、CI 都放在壳项目目录内。
- `config.yaml` 修改完全由 App UI 接管,不提供原始 YAML 文本编辑器。
- 只支持 `arm64-v8a`。
- 不在 `D:\SillyTavern` 本机尝试 Gradle 构建、打包或签名 APK;本机环境不完整,APK 统一由 GitHub Actions CI 工作流自动化产出。
- GitHub Actions 发布 APK 的触发方式固定为推送 `v*` 版本 Tag,例如 `v1.0.0`;普通分支 push / PR 不发布 GitHub Releases。

如果当前工作区里已经存在 `D:\SillyTavern\SillyTavern` 这种上游源码 clone,它只作为参考样本使用,不要移动进工程、不要作为 submodule 来源、不要打包入 APK。实际构建源码必须从 [SillyTavern/SillyTavern.git](https://github.com/SillyTavern/SillyTavern.git) 拉取到 `third_party/SillyTavern/`。

## 1. 第一阶段:仓库骨架

目标:创建可编译的 Android 工程骨架和脚本目录。

任务:

1. 创建目录:

```text
SillyTavern/
├─ android-app/
├─ third_party/SillyTavern/              # https://github.com/SillyTavern/SillyTavern.git
├─ third_party/Android-Node-Builder/     # https://github.com/print-yuhuan/Android-Node-Builder.git
├─ packaging/
├─ scripts/
├─ docs/
└─ .github/workflows/
```

2. 配置项目推送目标仓库:

```text
git remote add origin https://github.com/print-yuhuan/SillyTavern.git
```

如果 `origin` 已存在,改用:

```text
git remote set-url origin https://github.com/print-yuhuan/SillyTavern.git
```

3. 添加官方上游源码来源:

```text
git submodule add https://github.com/SillyTavern/SillyTavern.git third_party/SillyTavern
git submodule add https://github.com/print-yuhuan/Android-Node-Builder.git third_party/Android-Node-Builder
```

必须使用 Git Submodule 并锁定 tag/commit。不要使用 `D:\SillyTavern\SillyTavern` 作为构建输入。

4. 确认 `.gitmodules` 内容:

```ini
[submodule "third_party/SillyTavern"]
	path = third_party/SillyTavern
	url = https://github.com/SillyTavern/SillyTavern.git
[submodule "third_party/Android-Node-Builder"]
	path = third_party/Android-Node-Builder
	url = https://github.com/print-yuhuan/Android-Node-Builder.git
```

5. 初始化 `android-app/`:
   - Kotlin。
   - Jetpack Compose。
   - Material 3。
   - `applicationId = "org.sillytavern"`。
   - `minSdk = 28`。
   - `targetSdk = 36`。
   - `abiFilters += "arm64-v8a"`。
   - `android:extractNativeLibs="true"`。

6. 添加基础 Activity / Service 文件:
   - `LauncherActivity`
   - `WebViewActivity`
   - `NodeService`
   - `ConfigEditor`

验收:

- GitHub Actions 的 debug/release 构建 job 能跑到 Gradle 编译阶段;本机不执行 `./gradlew :android-app:assembleDebug`。
- `.gitmodules` 路径和 URL 与文档一致。
- App 名称显示为 `SillyTavern`。
- Manifest 中已有 `INTERNET`、前台服务、通知权限、可选录音权限。

## 2. 第二阶段:M0 真机 PoC

目标:验证最大不确定性:Android 真机能否从 `nativeLibraryDir` 执行内置 Node。

任务:

1. libnode 产物来源:[Android-Node-Builder 的 Release](https://github.com/print-yuhuan/Android-Node-Builder/releases)(当前 `v1.0.0`),其中包含:

```text
libnode.so
libnode.so.sha256
```

注意:CI 不读取本机 `D:\...` 路径。M0 调试 APK 也通过 CI 生成;libnode.so 统一从 Android-Node-Builder 的 Release 直链获取(由其 `build-libnode.yml` 构建),`build-apk.yml` 只做下载/校验/缓存。

2. CI 校验 `libnode.so.sha256`,然后把 `libnode.so` 放入:

```text
android-app/src/main/jniLibs/arm64-v8a/libnode.so
```

3. 准备最小 `server.js` 测试资产,可先不是完整 SillyTavern。
4. 在 `NodeService` 中用 `ProcessBuilder` 执行:

```text
NODE_BIN server.js
```

5. 设置环境变量:
   - `HOME = filesDir`
   - `TMPDIR = cacheDir/node-tmp`
   - `PATH = nativeLibraryDir`

验收:

- 真机上 Node 进程能启动。
- stdout/stderr 能写入 App 日志。
- 停止按钮能结束进程。
- 后台时前台服务通知持续存在。

## 3. 第三阶段:SillyTavern 资产解压

目标:建立 App 内部目录结构,把 SillyTavern 服务端代码、配置和数据拆开。

运行时目录必须是:

```text
APP_FILES_DIR/
└─ sillytavern/
   ├─ server/
   ├─ config/
   ├─ data/
   └─ version.json

APP_CACHE_DIR/
└─ node-tmp/
```

任务:

1. 从官方上游来源 `third_party/SillyTavern/` 打包源码为 `sillytavern-code.zip`。
2. 打包 `node_modules` 为 `sillytavern-modules.zip`。
3. 首次打开 App 时解压:
   - code zip -> `SILLYTAVERN_SERVER_DIR`
   - modules zip -> `SILLYTAVERN_SERVER_DIR/node_modules`
4. 创建:
   - `SILLYTAVERN_CONFIG_DIR`
   - `SILLYTAVERN_DATA_DIR`
   - `NODE_TMP_DIR`
5. 写入 `version.json`,记录:
   - `schemaVersion`
   - `appVersionName`
   - `appVersionCode`
   - `releaseTag`
   - `sillyTavern.source`
   - `sillyTavern.commit`
   - `sillyTavern.tag`
   - `node.version`
   - `node.androidApi`
   - `node.abi`
   - `node.sha256`
   - `assets.codeZipSha256`
   - `assets.modulesZipSha256`
   - `assets.builtAt`

验收:

- 首次启动只解压一次。
- 修复功能可以重解压 `server/`,但不动 `config/` 和 `data/`。
- 升级后用户配置和数据保留。
- `version.json` 字段完整,可用于后续升级/修复判断。

## 4. 第四阶段:完整 NodeService

目标:用完整 SillyTavern 启动参数运行服务。

启动命令必须包含:

```text
libnode.so server.js \
  --configPath <SILLYTAVERN_CONFIG_FILE> \
  --dataRoot <SILLYTAVERN_DATA_DIR> \
  --browserLaunchEnabled=false
```

启动参数原则:

- 不传 `--global`。该模式会忽略 `--configPath` 与 `--dataRoot`,不适合 Android 私有目录布局。
- 固定传 `--configPath`、`--dataRoot`、`--browserLaunchEnabled=false`。
- 常规启动不传 `--port`、`--listen`、`--ssl`、`--whitelist`、`--basicAuthMode`、`--requestProxy*` 等用户配置项,避免覆盖 `config.yaml` 后导致 UI 修改不生效。
- 若实现“诊断/安全模式”临时覆盖参数,启动日志必须明确写出“CLI 覆盖 config.yaml”。
- 不使用弃用参数 `--autorun`、`--autorunHostname`、`--autorunPortOverride`、`--avoidLocalhost`。

任务:

1. 根据 `config.yaml` 解析:
   - `port`
   - `listen`
   - `listenAddress.ipv4`
   - `ssl.enabled`
2. 启动前输出本次实际路径:
   - `SILLYTAVERN_SERVER_DIR`
   - `SILLYTAVERN_CONFIG_FILE`
   - `SILLYTAVERN_DATA_DIR`
3. 生成健康检查 URL:
   - `listen: false` -> `127.0.0.1`
   - `listen: true` 且 `listenAddress.ipv4 = 0.0.0.0` -> App 内健康检查仍用 `127.0.0.1`
   - `listen: true` 且具体局域网 IP -> 使用该 IP
4. `config.yaml` 不存在时等待 SillyTavern 按 `--configPath` 创建,再重读真实配置。
5. 可选做只读键集合诊断:递归比较 `SILLYTAVERN_SERVER_DIR/default/config.yaml` 与 `SILLYTAVERN_CONFIG_FILE` 的键路径,只记录差异,不删除用户未知字段。
6. 轮询健康检查直到服务可用。
7. 将状态机做完整:
   - `Stopped`
   - `Starting`
   - `Running`
   - `Stopping`
   - `Error`
8. 捕获进程退出码和启动错误。

验收:

- 可启动完整 SillyTavern。
- 可停止并再次启动。
- 首页能显示当前 URL、端口、运行时长。
- `config.yaml` 不存在时,SillyTavern 能通过 `--configPath` 创建它。
- 改动 `SILLYTAVERN_CONFIG_FILE` 后,在没有 CLI 覆盖的情况下重启即可生效。
- 日志能明确区分默认模板 `default/config.yaml` 与实际生效的 `SILLYTAVERN_CONFIG_FILE`。

## 5. 第五阶段:App UI

目标:实现原生控制台 UI,不重做 SillyTavern 聊天界面。

页面:

- 首页
- 配置
- 日志
- 数据
- 关于
- WebView

风格:

- Kotlin + Compose + Material 3。
- 工具型、安静、信息密度适中。
- 跟随系统深浅色。
- 状态颜色只用于运行、停止、错误、警告。
- WebView 全屏/沉浸,只保留必要控制。
- App 外壳语言统一为简体中文;所有页面标题、按钮、控件标签、提示、弹窗、通知和无障碍描述都使用简体中文。

验收:

- 首页首屏可完成启动、停止、打开 WebView。
- 运行状态不是只靠颜色表达。
- 关键按钮有文本和 accessibility 描述。
- App 外壳没有把配置键、按钮或提示直接显示为英文。

## 6. 第六阶段:配置 UI 接管 config.yaml

目标:所有配置修改都通过 App UI 写入 `config.yaml`。

禁止:

- 不提供原始 YAML 编辑器。
- 不用字符串替换改 YAML。
- 不允许用户修改 `dataRoot` 等 App 管理字段。
- 不把 `port`、`listen`、`listenAddress.ipv4`、`basicAuthMode` 等 YAML 英文字段名直接展示给用户。

任务:

1. `ConfigEditor` 读取 `SILLYTAVERN_CONFIG_FILE`。
2. 文件不存在时显示「尚未生成配置」,引导用户先启动服务。
3. 读取 `SILLYTAVERN_SERVER_DIR/default/config.yaml` 作为模板参考,只用于缺键提示和“恢复默认配置”,不得覆盖用户值。
4. UI 接管的配置项分四档（完整字段表见 [01-implementation-plan.md](01-implementation-plan.md) §7;界面只显示中文名称与说明,**未列入的字段一律保留不接管、不显示**）:
   - **基础**:`port`(服务端口)、`listen`(允许局域网访问)、`listenAddress.ipv4`(IPv4 监听地址)、`protocol.ipv4`/`protocol.ipv6`(启用 IPv4/IPv6)、`ssl.enabled`(启用 HTTPS)、`whitelistMode`(启用访问白名单)、`basicAuthMode`(启用访问密码)、`basicAuthUser.username`/`password`(访问账号/密码)。
   - **高级**:`listenAddress.ipv6`、`dnsPreferIPv6`、`enableKeepAlive`、`heartbeatInterval`、`sessionTimeout`、`whitelist`(IP 白名单列表)、`ssl.certPath`/`keyPath`/`keyPassphrase`、`requestProxy.enabled`/`url`/`bypass`、`logging.enableAccessLog`/`minLogLevel`、`skipContentCheck`、`enableDownloadableTokenizers`、`extensions.enabled`/`autoUpdate`/`models.autoDownload`、`enableUserAccounts`(多用户账号)、`enableDiscreetLogin`(隐蔽登录,仅多用户账号开启时可编辑)。
   - **危险**(默认收起 + 二次确认 + 中文风险提示):`enableCorsProxy`、`disableCsrfProtection`。
   - **App 管理**(只读锁定,运行时被 CLI 覆盖):`dataRoot`、`browserLaunch.enabled`。
5. 保存时:
   - 解析 YAML 为结构化对象。
   - 更新 UI 接管字段。
   - 保留未知字段。
   - 原子写入临时文件再替换。
   - 写完后重新读取校验。
   - 递归提取键路径,确认没有意外丢失未知字段。
6. 服务运行中保存时,提示是否立即重启服务。
7. 所有校验错误、保存成功提示、重启确认弹窗都使用简体中文。

验收:

- 通过 UI 修改端口后,重启服务生效。
- 通过 UI 打开局域网访问后,首页展示局域网 URL。
- 保存后 `config.yaml` 仍可被 SillyTavern 正常读取。
- 配置页使用开关、下拉、数字输入、IP 输入、密码输入、锁定信息行等合适控件,不是所有字段都用普通文本框。
- 配置页用户可见文案为简体中文,不直接显示 YAML 英文字段名。
- 恢复默认配置时只重建 `SILLYTAVERN_CONFIG_FILE`,模板仍来自当前 `SILLYTAVERN_SERVER_DIR/default/config.yaml`。
- 保存前后未知字段数量不减少;升级新增字段由 SillyTavern 初始化逻辑补齐。
- 危险项（`enableCorsProxy`、`disableCsrfProtection`）开启前有二次确认与中文风险提示。
- `enableDiscreetLogin` 仅在 `enableUserAccounts` 开启时可编辑;`basicAuthUser` 仅在 `basicAuthMode` 开启时可编辑;HTTPS 证书项仅在 `ssl.enabled` 开启时显示。
- App 管理项（`dataRoot`、`browserLaunch.enabled`）只读锁定,不写值。

## 7. 第七阶段:WebView

目标:WebView 正常承载 SillyTavern 前端。

任务:

1. 开启:
   - JavaScript
   - DOM Storage
   - Database
   - File access
2. 实现:
   - 文件选择器
   - 下载监听
   - 麦克风权限请求
   - 返回/刷新/重新连接
3. 只在服务健康检查成功后打开 WebView。

验收:

- WebView 可加载 SillyTavern。
- 可导入角色卡文件。
- 可下载导出文件。
- 返回键行为合理。

## 8. 第八阶段:数据、备份、修复

目标:保护用户数据和配置。

任务:

1. 备份 `SILLYTAVERN_DATA_DIR`。
2. 备份 `SILLYTAVERN_CONFIG_DIR`。
3. 使用 SAF 导出/导入备份。
4. 修复功能只重解压 `SILLYTAVERN_SERVER_DIR`。
5. 清理缓存只清理 `NODE_TMP_DIR` 和可再生缓存,不删用户数据。

验收:

- 备份恢复后角色、设置、聊天数据仍在。
- 修复服务端文件不会丢配置和用户数据。

## 9. 第九阶段:打包与 CI

目标:通过 GitHub Actions 自动构建可安装 APK。本机不尝试构建、打包或签名 APK。

任务:

1. CI checkout 必须初始化 submodules:
   - `third_party/SillyTavern/` -> `https://github.com/SillyTavern/SillyTavern.git`
   - `third_party/Android-Node-Builder/` -> `https://github.com/print-yuhuan/Android-Node-Builder.git`
2. `build-apk.yml` 发布触发规则:
   - `on.push.tags` 只匹配 `v*`
   - workflow 权限包含 `contents: write`
   - 示例 Tag:`v1.0.0`
   - 推送该 Tag 后自动打包 APK 并创建/更新同名 GitHub Release
   - 普通分支 push / PR 不发布 Release
3. Node 输入来自 [Android-Node-Builder 的 Release](https://github.com/print-yuhuan/Android-Node-Builder/releases)(当前 `v1.0.0`)的 `libnode.so`;CI 不读取本机 `D:\...` 路径:
   - 直链下载 `libnode.so` 与 `libnode.so.sha256`
   - 仅比对哈希值校验 `libnode.so.sha256`
   - 复制到 `android-app/src/main/jniLibs/arm64-v8a/libnode.so`
4. Node 自动编译流程使用 `third_party/Android-Node-Builder/` submodule 中的 [print-yuhuan/Android-Node-Builder](https://github.com/print-yuhuan/Android-Node-Builder.git) GitHub Actions 方案:
   - workflow: `build-libnode.yml`
   - 默认 Node: `24.16.0`
   - 默认 NDK: `r27d`
   - 默认 Android API: `28`
   - 默认目标架构: `arm64-v8a`
   - 产物: `arm64-v8a/libnode.so` 和 `arm64-v8a/libnode.so.sha256`
5. 本仓库不重复维护 Docker/NDK 编译逻辑,只在 `build-apk.yml` 中接入 `third_party/Android-Node-Builder/`:
   - 触发或记录 Android-Node-Builder 构建参数
   - 下载 workflow artifact 或 release zip
   - 校验 sha256
   - 复制 `libnode.so` 到 `android-app/src/main/jniLibs/arm64-v8a/libnode.so`
6. `packaging/` 负责:
   - 确认 `third_party/SillyTavern/` remote 为 `https://github.com/SillyTavern/SillyTavern.git`
   - 在 `third_party/SillyTavern/` 内执行 `npm ci --omit=dev`
   - 扫描 `.node`
   - 仅从 `third_party/SillyTavern/` 打包 `sillytavern-code.zip`
   - `sillytavern-code.zip` 包含 `server.js`、`package.json`、`package-lock.json`、`default/`、`src/`、`public/`、`plugins/` 等运行必需文件
   - `sillytavern-code.zip` 排除 `.git/`、`.github/`、测试缓存、临时文件、本地日志和开发构建输出
   - `sillytavern-modules.zip` 只来自 `npm ci --omit=dev` 后的 `node_modules/`
   - 生成 `version.json`
7. Release 签名 Secrets:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
8. GitHub Actions:
   - `build-apk.yml`
   - `check-upstream.yml`
9. APK 命名:

```text
SillyTavern-android-arm64-v8a-v<version>.apk
```

验收:

- CI 可产出 release APK。
- 推送 `v1.0.0` 这类 `v*` Tag 会触发 APK 打包并发布到 GitHub Releases。
- 普通分支 push 不会发布 GitHub Releases。
- 正式 Release APK 使用 CI Secrets 中的 keystore 签名。
- `sillytavern-code.zip` 不包含 `.git/`、`.github/` 或本地参考源码。
- `version.json` 包含版本、submodule commit、Node sha256 和 assets sha256。
- APK 安装后能首次解压、启动服务、打开 WebView。
- CI 每次扫描 `.node` 原生模块。

## 10. 推荐执行顺序

严格按顺序推进:

1. 仓库骨架 + 最小 GitHub Actions 构建 job。
2. 使用 CI 产出的调试 APK 做 M0 Node 真机执行 PoC。
3. 首启解压和目录结构。
4. `NodeService` 完整启动 SillyTavern。
5. 首页和日志。
6. WebView。
7. 配置 UI 接管 `config.yaml`。
8. 备份、恢复、修复。
9. 完整 CI 和 release。

不要在 M0 通过前投入大量 UI 或热更新代码；CI 只先做到能产出 M0 调试 APK。

## 11. 总体验收清单

- 真机 arm64 可安装。
- 首次打开能解压资产。
- 可启动 SillyTavern。
- 可停止 SillyTavern。
- WebView 能打开本机服务。
- `config.yaml` 由 App UI 修改并写回。
- 端口和局域网监听修改后重启生效。
- 配置和数据不会被热更新覆盖。
- 前台服务通知正常。
- 后台运行不被立即杀死。
- 备份和恢复可用。
- APK 包只包含 arm64。
- 上游 SillyTavern 源码保持零修改。
