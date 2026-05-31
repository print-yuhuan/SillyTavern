# SillyTavern Android

把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 封装成**独立离线运行**的原生 Android App（仅 arm64-v8a）。

外壳是 Kotlin + Jetpack Compose（Material 3）原生应用，内置一份为 android-arm64 交叉编译的 `node`，以前台服务运行**零修改**的上游 SillyTavern（`server.js`），再用 WebView 加载本机服务呈现界面。外壳只负责服务生命周期、配置、日志、备份与更新等系统级操作；聊天、角色、扩展、主题等体验仍由 SillyTavern 自身提供。

> - App 外壳所有界面、按钮、提示、通知与无障碍描述均为**简体中文**。
> - 配置由 App UI 接管（中文名称 + 合适控件），不提供原始 YAML 编辑器。

## 设计要点

- **仅 arm64-v8a**；`minSdk 28` / `targetSdk 36`；`applicationId = org.sillytavern`。
- **Node 从 `nativeLibraryDir` 执行**：`node` 以 `libnode.so` 打进 `jniLibs/arm64-v8a/`，配合 `extractNativeLibs=true`——这是 Android 10+ 唯一可 `exec` 的位置（整条路线的关键所在）。
- **前台服务 + 常驻通知**保活 Node 进程，避免后台被杀。
- **代码 / 配置 / 数据三层分离**：用 SillyTavern 原生的 `--configPath` 与 `--dataRoot`，热更新只替换服务端代码，不动配置与用户数据。
- **上游零修改**：以 git submodule 锁定 tag 引入，升级 = 移动 submodule 指针。

## 仓库结构

```
SillyTavern/
├─ android-app/             # Android 工程（Kotlin + Compose + Material 3）
├─ third_party/             # 外部依赖（git submodule，统一同级管理）
│  ├─ SillyTavern/          # 上游源码（SillyTavern/SillyTavern.git，锁定 tag，零修改）
│  └─ Android-Node-Builder/ # libnode.so 构建（print-yuhuan/Android-Node-Builder.git）
├─ packaging/               # 把上游源码与 node_modules 打包为 assets（仅 CI 执行）
├─ scripts/                 # 维护脚本：bump 上游
├─ docs/                    # 设计与进度文档（索引见 docs/README.md）
└─ .github/workflows/       # build-apk.yml（构建+发布）、check-upstream.yml（跟进上游）
```

## 关键信息

| 项 | 值 |
|---|---|
| 项目仓库 | [print-yuhuan/SillyTavern](https://github.com/print-yuhuan/SillyTavern) |
| 包名 / 模块 | `org.sillytavern` / `android-app` |
| ABI / SDK | `arm64-v8a`；`minSdk 28` / `targetSdk 36` |
| 工具链 | AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.0.21 · Compose Material 3 |
| 上游 SillyTavern | submodule 锁定 tag `1.18.0` |
| 内置 Node | `24.16.0`（Android API 28 · arm64-v8a） |
| libnode 构建 | [print-yuhuan/Android-Node-Builder](https://github.com/print-yuhuan/Android-Node-Builder) submodule 锁定 `v1.0.0` |

## 克隆（含子模块）

```bash
git clone --recurse-submodules https://github.com/print-yuhuan/SillyTavern.git
# 或已克隆后：
git submodule update --init --recursive
```

## 构建与发布

**本机不构建、不打包、不签名 APK**（环境不完整）。APK 一律由 GitHub Actions 产出：

- 分支 push / PR / 手动触发 → 产出 **debug** APK（workflow artifact，供真机验证，不发布 Release）。
- 推送 `v*` Tag（如 `v1.0.0`）→ 产出**签名 release** APK 并发布到 GitHub Releases。

`libnode.so` 由 CI 自动从 [Android-Node-Builder 的 Release](https://github.com/print-yuhuan/Android-Node-Builder/releases) 直链下载并校验 sha256，无需手动准备。签名 Secrets 与发版细节见 [docs/04-ci-and-release.md](docs/04-ci-and-release.md)。

## 当前状态

处于 **M0（真机 PoC）**：验证内置 Node 能否在真机从 `nativeLibraryDir` 启动并联通本机端口。仓库骨架、M0 代码与 CI 均已就绪，libnode 来源已打通，待 CI 出包后真机验证。

文档（[docs/README.md](docs/README.md) 为索引）：
[实现方案](docs/01-implementation-plan.md) · [工作计划](docs/02-work-plan.md) · [进度](docs/03-progress.md) · [CI 与发布](docs/04-ci-and-release.md)。

## 参考

- 上游：[SillyTavern](https://github.com/SillyTavern/SillyTavern)
- 模式参考：[Sanitised/ST-android](https://github.com/Sanitised/ST-android)
- Node 构建：[print-yuhuan/Android-Node-Builder](https://github.com/print-yuhuan/Android-Node-Builder)

## License

本项目遵循 **AGPL-3.0**（与上游 SillyTavern 一致）。公开分发 APK 即触发 AGPL：整个 App（外壳 + 改动）须公开对应源码。完整许可证见 [LICENSE](LICENSE)。（非法律意见。）
