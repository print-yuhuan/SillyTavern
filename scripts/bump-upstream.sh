#!/usr/bin/env bash
# 将 third_party/SillyTavern 子模块指针更新到上游最新 Release tag。
# 在 GitHub Actions（check-upstream.yml）中调用；也可本地运行。
set -euo pipefail

SUB="third_party/SillyTavern"
OUT="${GITHUB_OUTPUT:-/dev/null}"

if [[ ! -d "$SUB/.git" && ! -f "$SUB/.git" ]]; then
  echo "::error::未初始化子模块 $SUB（请先 git submodule update --init）。"
  exit 1
fi

latest_tag="$(gh api repos/SillyTavern/SillyTavern/releases/latest --jq .tag_name)"
echo "上游最新 Release：$latest_tag"

cur_commit="$(git -C "$SUB" rev-parse HEAD)"
echo "当前子模块 commit：$cur_commit"

git -C "$SUB" fetch --tags --depth 1 origin "refs/tags/${latest_tag}:refs/tags/${latest_tag}"
git -C "$SUB" checkout -q "tags/${latest_tag}"
new_commit="$(git -C "$SUB" rev-parse HEAD)"

if git diff --quiet -- "$SUB"; then
  echo "changed=false" >> "$OUT"
  echo "已是最新（$latest_tag / $new_commit），无需更新。"
else
  echo "changed=true" >> "$OUT"
  echo "latest_tag=$latest_tag" >> "$OUT"
  echo "将升级：$cur_commit -> $new_commit ($latest_tag)"
fi
