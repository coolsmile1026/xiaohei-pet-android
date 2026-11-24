#!/bin/sh
# 小黑桌宠 Android 移植版 · 一键发版（照 Switch-HA 的 tools/release.sh 改写）
#
#   ./tools/release.sh                 只编译 + 生成 dist/ 里的版本化 APK 和 update.json
#   ./tools/release.sh --publish       顺带建仓库 / 推 main / 打 tag / 建 Release / 传附件
#   ./tools/release.sh --publish --no-build   跳过编译，用 dist 里已有的 APK 发布
#
# 认证：优先 GITHUB_TOKEN，否则从 macOS 钥匙串取（跟 Switch-HA 同一把）。
# OTA 地址必须公开可读，所以仓库建的是 public；别改成 private，镜像代理也读不到附件。
set -e

OWNER=coolsmile1026
REPO="$OWNER/xiaohei-pet-android"
TOPDIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$TOPDIR"

PUBLISH=0
NOBUILD=0
for a in "$@"; do
  [ "$a" = "--publish" ] && PUBLISH=1
  [ "$a" = "--no-build" ] && NOBUILD=1
done

VER=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1 | tr -d ' \t\r')
CODE=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1 | tr -d ' \t\r')
[ -n "$VER" ] || { echo "读不到 app/build.gradle.kts 里的 versionName"; exit 1; }
[ -n "$CODE" ] || { echo "读不到 versionCode"; exit 1; }
TAG="v$VER"
ASSET="xiaohei-pet-$TAG.apk"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> 版本 $VER (versionCode=$CODE, tag=$TAG)"

# ---------- 1. 编译 ----------
if [ "$NOBUILD" = "0" ]; then
  GRADLE_BIN=$(command -v gradle 2>/dev/null || true)
  if [ -z "$GRADLE_BIN" ]; then
    GRADLE_BIN=$(find "$HOME/.gradle/wrapper/dists" -path "*/gradle-*/bin/gradle" -type f 2>/dev/null | head -1)
  fi
  [ -n "$GRADLE_BIN" ] || { echo "找不到 gradle，装一个或设 GRADLE 环境变量"; exit 1; }
  "$GRADLE_BIN" -p . assembleDebug --offline
else
  echo "    --no-build：跳过编译"
fi
[ -f "$APK" ] || { echo "找不到 $APK"; exit 1; }

mkdir -p dist
cp "$APK" "dist/$ASSET"
SIZE=$(wc -c < "dist/$ASSET" | tr -d ' ')
echo "==> 产物 dist/$ASSET ($SIZE 字节)"

# 发版铁律：版本号、tag、附件名、update.json 四者必须一致；
# versionCode 也必须递增，否则系统安装器按「降级」拒装。
python3 - "$TOPDIR" "$VER" "$TAG" "$SIZE" <<'PY'
import json, re, sys
root, ver, tag, size = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
p = root + "/update.json"
d = json.load(open(p, encoding="utf-8"))
d["version"] = ver
d["url"] = f"https://github.com/coolsmile1026/xiaohei-pet-android/releases/download/{tag}/xiaohei-pet-{tag}.apk"
d["size"] = size
with open(p, "w", encoding="utf-8") as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"    update.json: version={ver} size={size} url=.../{tag}/")
PY

if [ "$PUBLISH" != "1" ]; then
  echo "==> 未发布。手动上传：GitHub -> Releases -> Draft a new release"
  echo "    tag: $TAG   附件: dist/$ASSET 和 update.json"
  exit 0
fi

# ---------- 2. 凭据 ----------
TOKEN="$GITHUB_TOKEN"
if [ -z "$TOKEN" ]; then
  TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' \
      | git credential fill 2>/dev/null \
      | sed -n 's/^password=//p')
fi
[ -n "$TOKEN" ] || { echo "没有 GitHub 凭据：设 GITHUB_TOKEN，或先把令牌存进钥匙串"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
API="https://api.github.com/repos/$REPO"

# ---------- 3. 仓库 / 提交 ----------
STATUS=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$API")
if [ "$STATUS" = "404" ]; then
  echo "==> 创建公开仓库 $REPO"
  curl -sS -X POST -H "$AUTH" -H "Accept: application/vnd.github+json" \
    https://api.github.com/user/repos \
    -d "{\"name\":\"xiaohei-pet-android\",\"description\":\"小黑桌宠 · Android 移植版（电视桌宠 / OTA 自更新）\",\"private\":false,\"auto_init\":false}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('   ', d.get('full_name') or d)"
fi

git rev-parse --git-dir >/dev/null 2>&1 || git init -q -b main
if ! git remote | grep -q '^origin$'; then
  git remote add origin "https://github.com/$REPO.git"
fi
git add -A
# README 里引用的几张真机截图（shots/ 默认被忽略，单独强制加）
for s in dock-a11y-stand.png dock-a11y-final.png dock-a11y-control.png ota-about.png ota-control.png eye-zoom-before.png eye-zoom-after.png; do
  [ -f "shots/$s" ] && git add -f "shots/$s"
done
if ! git diff --cached --quiet; then
  git commit -q -m "release: $TAG" || true
fi
echo "==> 推 main"
git push -u origin main 2>&1 | tail -3

echo "==> 推 tag $TAG"
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "    tag 已存在，跳过"
else
  git tag "$TAG"
fi
git push origin "$TAG" 2>&1 | tail -3

# ---------- 4. Release ----------
echo "==> 创建/复用 Release $TAG"
REL_ID=$(curl -s -H "$AUTH" "$API/releases/tags/$TAG" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || true)
if [ -z "$REL_ID" ]; then
  # body 直接取 update.json 的 notes，保证「看看更新了什么」两条通道看到同一份说明
  PAYLOAD=$(python3 -c "
import json
n=json.load(open('update.json',encoding='utf-8'))['notes']
print(json.dumps({'tag_name':'$TAG','name':'小黑桌宠 $TAG','body':n,
                  'draft':False,'prerelease':False,'target_commitish':'main'},ensure_ascii=False))")
  REL_ID=$(curl -sS -X POST -H "$AUTH" -H "Accept: application/vnd.github+json" \
      "$API/releases" -d "$PAYLOAD" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id') or sys.exit(d.get('message','创建失败')))")
fi
echo "    release id=$REL_ID"

# 同名附件先删掉，重发不用手工去网页清理
ASSETS=$(curl -s -H "$AUTH" "$API/releases/$REL_ID/assets")
for name in "$ASSET" update.json; do
  OLD=$(printf '%s' "$ASSETS" | python3 -c "import sys,json; print(next((str(a['id']) for a in json.load(sys.stdin) if a['name']=='$name'),''))" 2>/dev/null || true)
  [ -n "$OLD" ] && curl -s -X DELETE -H "$AUTH" "$API/releases/assets/$OLD" >/dev/null
done

echo "==> 上传 $ASSET（$(echo "$SIZE" | awk '{printf "%.1f MB", $1/1048576}')，可能要几分钟）"
curl -sS --max-time 900 -X POST -H "$AUTH" -H "Content-Type: application/octet-stream" \
    --data-binary "@dist/$ASSET" \
    "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$ASSET" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('    ', d.get('browser_download_url') or d)"

echo "==> 上传 update.json"
curl -sS -X POST -H "$AUTH" -H "Content-Type: application/json" \
    --data-binary "@update.json" \
    "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=update.json" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('    ', d.get('browser_download_url') or d)"

# ---------- 5. 校验 OTA 能读到 ----------
echo "==> 校验 releases/latest"
curl -sS -H "$AUTH" "$API/releases/latest" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('    tag_name =', d.get('tag_name'), '| prerelease =', d.get('prerelease'), '| draft =', d.get('draft'))
print('    assets   =', [a['name'] for a in d.get('assets',[])])
assert d.get('tag_name')=='$TAG', 'latest 不是本次 tag'
assert any(a['name'].endswith('.apk') for a in d.get('assets',[])), 'Release 里没有 APK'
"

echo "==> 完成： https://github.com/$REPO/releases/tag/$TAG"
