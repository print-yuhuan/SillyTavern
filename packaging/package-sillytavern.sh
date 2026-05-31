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
include=()
for p in server.js package.json package-lock.json default src public plugins plugins.js recover.js index.d.ts; do
  [[ -e "$ST_DIR/$p" ]] && include+=("$p")
done
( cd "$ST_DIR" && zip -r -q -X "$ASSETS/sillytavern-code.zip" "${include[@]}" \
    -x '*/.git/*' '*/.github/*' '*/node_modules/*' '*/tests/*' '*.log' )

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
