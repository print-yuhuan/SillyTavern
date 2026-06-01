#!/usr/bin/env bash
# 从 third_party/SillyTavern 打包运行所需资产（实现方案 §12 / 工作计划第九阶段）。
# 仅在 GitHub Actions 中执行；本机不打包 APK。
#
# 产出（写入 android-app/src/main/assets/，均为 .gitignore 忽略的 CI 生成物）：
#   - sillytavern-code.zip     运行必需源码
#   - sillytavern-modules.zip  npm ci --omit=dev 后的 node_modules
#   - version.json             版本与校验元数据
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ST_DIR="$ROOT/third_party/SillyTavern"
ASSETS="$ROOT/android-app/src/main/assets"
JNILIBS="$ROOT/android-app/src/main/jniLibs/arm64-v8a"
EXPECTED_REMOTE="https://github.com/SillyTavern/SillyTavern.git"

mkdir -p "$ASSETS"

# 0) 确认上游来源正确（不得使用本机参考 clone）
remote="$(git -C "$ST_DIR" remote get-url origin)"
echo "third_party/SillyTavern remote: $remote"
if [[ "$remote" != "$EXPECTED_REMOTE" ]]; then
  echo "::error::third_party/SillyTavern 的 remote 不是 $EXPECTED_REMOTE"
  exit 1
fi

# 1) 安装生产依赖
echo "==> npm ci --omit=dev"
( cd "$ST_DIR" && npm ci --omit=dev )

# 2) 扫描 .node 原生模块（每次都扫描并产出日志）
echo "==> 扫描 .node 原生模块"
node_addons="$(find "$ST_DIR/node_modules" -name '*.node' 2>/dev/null || true)"
if [[ -n "$node_addons" ]]; then
  echo "::warning::发现 .node 原生模块，需交叉编译为 lib*.so 并修正加载路径："
  echo "$node_addons"
else
  echo "未发现 .node 原生模块。"
fi
echo "$node_addons" > "$ROOT/packaging/native-addons-scan.txt"

# 3) 打包运行必需源码 → sillytavern-code.zip（排除 .git/.github/测试/开发产物）
echo "==> 打包 sillytavern-code.zip"
rm -f "$ASSETS/sillytavern-code.zip"

# 运行必需文件/目录：缺失视为上游结构变动，直接失败，避免漏包静默通过构建。
# ★ webpack.config.js 必不可少：server.js → src/middleware/webpack-serve.js 直接 import '../../webpack.config.js'，
#    缺失会导致真机 Node 启动即 ERR_MODULE_NOT_FOUND。
required=(server.js package.json package-lock.json webpack.config.js default src public)
for p in "${required[@]}"; do
  if [[ ! -e "$ST_DIR/$p" ]]; then
    echo "::error::上游缺少运行必需项 '$p'（third_party/SillyTavern 结构可能已变动），中止打包。"
    exit 1
  fi
done

# 实际打包清单 = 必需项 + 可选项（存在才纳入）。
include=("${required[@]}")
for p in plugins plugins.js recover.js index.d.ts; do
  [[ -e "$ST_DIR/$p" ]] && include+=("$p")
done
( cd "$ST_DIR" && zip -r -q -X "$ASSETS/sillytavern-code.zip" "${include[@]}" \
    -x '*/.git/*' '*/.github/*' '*/node_modules/*' '*/tests/*' '*.log' )

# 校验 zip 内确实含关键文件（构建期即暴露漏包，而非真机启动才发现）。
echo "==> 校验 sillytavern-code.zip 关键文件"
zip_names="$(unzip -Z1 "$ASSETS/sillytavern-code.zip")"
for f in server.js package.json package-lock.json webpack.config.js; do
  grep -qxF "$f" <<<"$zip_names" || { echo "::error::sillytavern-code.zip 缺少文件 '$f'。"; exit 1; }
done
for d in default src public; do
  grep -qE "^${d}/" <<<"$zip_names" || { echo "::error::sillytavern-code.zip 缺少目录 '$d/'。"; exit 1; }
done

# 4) 打包 node_modules → sillytavern-modules.zip
echo "==> 打包 sillytavern-modules.zip"
rm -f "$ASSETS/sillytavern-modules.zip"
( cd "$ST_DIR" && zip -r -q -X "$ASSETS/sillytavern-modules.zip" node_modules )

# 5) 生成 version.json
echo "==> 生成 version.json"
st_commit="$(git -C "$ST_DIR" rev-parse HEAD)"
st_tag="$(git -C "$ST_DIR" describe --tags --exact-match 2>/dev/null || echo '')"
code_sha="$(sha256sum "$ASSETS/sillytavern-code.zip" | awk '{print $1}')"
modules_sha="$(sha256sum "$ASSETS/sillytavern-modules.zip" | awk '{print $1}')"
node_sha=""
[[ -f "$JNILIBS/libnode.so.sha256" ]] && node_sha="$(awk '{print $1}' "$JNILIBS/libnode.so.sha256")"
[[ -z "$node_sha" && -f "$JNILIBS/libnode.so" ]] && node_sha="$(sha256sum "$JNILIBS/libnode.so" | awk '{print $1}')"

app_version_name="${ST_APP_VERSION_NAME:-1.0.0}"
app_version_code="${ST_APP_VERSION_CODE:-1}"
release_tag="${GITHUB_REF_NAME:-v${app_version_name}}"
node_version="${NODE_VERSION:-24.16.0}"
android_api="${ANDROID_API:-28}"
built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

cat > "$ASSETS/version.json" <<JSON
{
  "schemaVersion": 1,
  "appVersionName": "${app_version_name}",
  "appVersionCode": ${app_version_code},
  "releaseTag": "${release_tag}",
  "sillyTavern": {
    "source": "${EXPECTED_REMOTE}",
    "commit": "${st_commit}",
    "tag": "${st_tag}"
  },
  "node": {
    "version": "${node_version}",
    "androidApi": ${android_api},
    "abi": "arm64-v8a",
    "sha256": "${node_sha}"
  },
  "assets": {
    "codeZipSha256": "${code_sha}",
    "modulesZipSha256": "${modules_sha}",
    "builtAt": "${built_at}"
  }
}
JSON

echo "==> 完成："
ls -la "$ASSETS"
cat "$ASSETS/version.json"
