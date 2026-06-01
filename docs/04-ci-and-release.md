# CI 与发布说明

本机环境不完整，**不在本机构建、打包或签名 APK**。所有 APK 由 GitHub Actions 产出。

## 工作流

| 工作流 | 触发 | 作用 |
|---|---|---|
| `build-apk.yml` | push 分支 / PR / 手动 | 构建 **debug** APK，作为 workflow artifact（M0 真机验证用，不发布 Release） |
| `build-apk.yml` | push `v*` Tag（如 `v1.0.0`） | 构建 **release** APK、签名、发布到 GitHub Releases |
| `check-upstream.yml` | 每日 cron / 手动 | 检测 SillyTavern 上游新 Release，bump 子模块并自动开 PR |

> 普通分支 push / PR 不发布 Release；只有 `v*` Tag 触发发布。

## libnode.so 输入（已打通，无需手动操作）

CI 不能读取本机 `D:\` 路径。libnode.so 由 `Android-Node-Builder` 的 `build-libnode.yml`
（默认 Node 24.16.0 / NDK r27d / API 28）构建，并随 Release 发布。

`build-apk.yml` 获取 libnode.so 的来源（统一渠道）：

1. **Actions cache**（命中则跳过下载）；
2. **`Android-Node-Builder` 的 Release**：`v1.0.0` 已附带
   [`libnode.so`](https://github.com/print-yuhuan/Android-Node-Builder/releases/download/v1.0.0/libnode.so)
   与 `libnode.so.sha256`，直链下载。

下载后按 `libnode.so.sha256`（仅比对哈希值）校验，再放入 `android-app/src/main/jniLibs/arm64-v8a/libnode.so`。

> 升级 Node 版本时：在 `Android-Node-Builder` 重新构建并发布新 Release，然后在 `build-apk.yml`
> 同步 `ANB_RELEASE_TAG` / `NODE_VERSION`（并更新 `third_party/Android-Node-Builder/` 子模块指针）。

## 配置模板与启动参数校验

APK 内只打包上游服务端模板 `default/config.yaml`，不打包也不覆盖设备上的实际 `config.yaml`。实际配置必须在设备首次启动时由：

```text
server.js --configPath <SILLYTAVERN_CONFIG_FILE> --dataRoot <SILLYTAVERN_DATA_DIR>
```

创建到 App 私有持久目录。

CI 打包阶段需要校验：

1. `sillytavern-code.zip` 必须包含 `default/config.yaml`、`server.js`、`src/config-init.js`、`src/command-line.js`。
2. `sillytavern-code.zip` 不得包含根目录实际运行配置 `config.yaml`，避免把构建机配置带进 APK。
3. `default/config.yaml` 能被 YAML 解析。
4. `src/command-line.js` 或 `node server.js --help` 中仍存在 Android 壳依赖的参数：`configPath`、`dataRoot`、`browserLaunchEnabled`。
5. Release 说明中记录本次内置 SillyTavern commit/tag、Node 版本、`default/config.yaml` 的 sha256，便于用户反馈时定位模板版本。

运行时固定参数策略：

- 必传：`--configPath`、`--dataRoot`、`--browserLaunchEnabled=false`。
- 禁止：`--global`。
- 常规不传：`--port`、`--listen`、`--ssl`、`--whitelist`、`--basicAuthMode`、`--requestProxy*` 等用户可配置项，避免覆盖持久配置。
- 弃用参数 `--autorun*` 和 `--avoidLocalhost` 不再进入新实现。

配置 UI 接管范围（第六阶段）以 [01-implementation-plan.md](01-implementation-plan.md) §7 的四档清单为准：UI 写回 `config.yaml` 时必须保留未知字段（保留不接管的字段原样保留），`dataRoot` / `browserLaunch.enabled` 为 App 管理项（运行时被 CLI 覆盖，不写值）。

## 发布正式版（v* Tag）

1. 配置签名 Secrets（仓库 Settings → Secrets and variables → Actions）：
   - `ANDROID_KEYSTORE_BASE64`：release keystore 的 base64 文本
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
   - 缺省时 release 包回退 debug 签名（仅供验证，勿正式分发）。
2. 打 Tag 并推送：
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. CI 产出 `SillyTavern-android-arm64-v8a-v1.0.0.apk` 并发布到 Releases。

## App 版本号

- `ST_APP_VERSION_NAME`：release 时取自 Tag（去掉前缀 `v`）。
- `ST_APP_VERSION_CODE`：取 `github.run_number`。
- 内置 SillyTavern 版本另记于 `version.json`，与 App 版本号分离。
