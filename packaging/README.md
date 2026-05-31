# packaging

将 `third_party/SillyTavern` 子模块打包成 App 运行所需资产。**仅在 GitHub Actions 中执行**，本机不打包。

## package-sillytavern.sh

在 CI（Linux）中运行，产出到 `android-app/src/main/assets/`（均为 `.gitignore` 忽略的 CI 生成物）：

| 产物 | 说明 |
|---|---|
| `sillytavern-code.zip` | 运行必需源码：`server.js`、`package.json`、`package-lock.json`、`default/`、`src/`、`public/`、`plugins/` 等；排除 `.git/`、`.github/`、`tests/`、`node_modules/`、日志等 |
| `sillytavern-modules.zip` | `npm ci --omit=dev` 后的 `node_modules/` |
| `version.json` | 版本、子模块 commit/tag、Node 与资产 sha256、构建时间 |
| `native-addons-scan.txt` | 每次构建对 `.node` 原生模块的扫描结果 |

> 当前 App 仍处于 M0，运行的是 `assets/m0-server.js` 测试服务，尚未消费上述资产。
> 待 `NodeService` 切换到真实 `server.js`（第四阶段）后，再在 `build-apk.yml` 中启用打包步骤。
