#!/usr/bin/env bash
# 一键发版：构建 release + debug → 上传 → 合并 releases.json（保留历史）→ 回验
#
# 用法：
#   TK_SSH_PASS='密码' ./publish-release.sh "更新说明"
#   TK_SSH_PASS='密码' ./publish-release.sh "更新说明" 1.8.20
#
# 目录约定（服务端）：
#   <root>/apps/<appId>/*.apk
#   <root>/api/apps/<appId>/releases.json
#   appId: tavernium（正式）/ tavernium-debug（调试，独立清单，客户端不读）
set -euo pipefail

# 凭据与主机信息一律由环境变量提供（可用同目录 .env 文件，该文件已加入 .gitignore）
# 仓库里不保留任何服务器地址、账号或密码。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT_DIR/.env"
    set +a
fi

HOST="${TK_SSH_HOST:?请设置 TK_SSH_HOST（服务器地址），或写入 .env}"
USER="${TK_SSH_USER:?请设置 TK_SSH_USER（SSH 用户名），或写入 .env}"
PASS="${TK_SSH_PASS:?请设置 TK_SSH_PASS（SSH 密码），或写入 .env}"
DOMAIN="${TK_DOMAIN:?请设置 TK_DOMAIN（更新站域名），或写入 .env}"
SITE="${TK_SITE_ROOT:-/opt/1panel/apps/openresty/openresty/www/sites}/${DOMAIN}/index"
PROJECT_DIR="$ROOT_DIR/tavernium"

NOTES="${1:-}"
VERSION="${2:-$(grep -oP "tkVersionName = '\K[0-9.]+" "$PROJECT_DIR/app/build.gradle" | head -1)}"
CODE="$(grep -oP 'versionCode \K[0-9]+' "$PROJECT_DIR/app/build.gradle" | head -1)"
REL_APK="$PROJECT_DIR/app/build/outputs/apk/release/tavermium-release-${VERSION}.apk"
DBG_APK="$PROJECT_DIR/app/build/outputs/apk/debug/tavermium-debug-${VERSION}.apk"

SSH=(sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no "$USER@$HOST")
SCP=(sshpass -p "$PASS" scp -o StrictHostKeyChecking=no)

echo "==> 目标 $DOMAIN / $VERSION (code $CODE)"
echo "==> 构建 release + debug"
( cd "$PROJECT_DIR" && ./gradlew assembleRelease assembleDebug -q )

[ -f "$REL_APK" ] || { echo "缺少正式包: $REL_APK"; exit 1; }
[ -f "$DBG_APK" ] || { echo "缺少调试包: $DBG_APK"; exit 1; }

# publish_app <appId> <apkPath> <channel> <notes>
publish_app() {
  local app_id="$1" apk="$2" channel="$3" notes="$4"
  local name; name="$(basename "$apk")"
  echo "==> [$app_id] 上传 $name"
  "${SCP[@]}" "$apk" "$USER@$HOST:/tmp/${name}"
  "${SSH[@]}" "echo '$PASS' | sudo -S bash -c 'mkdir -p ${SITE}/apps/${app_id} && install -m 644 /tmp/${name} ${SITE}/apps/${app_id}/${name}'" >/dev/null

  echo "==> [$app_id] 更新版本清单"
  curl -s --max-time 20 "https://${DOMAIN}/api/apps/${app_id}/releases.json" -o /tmp/rel_old.json || true
  python3 - "$apk" /tmp/rel_old.json "$VERSION" "$CODE" "$DOMAIN" "$app_id" "$notes" "$channel" <<'PY'
import hashlib, json, os, sys, time
apk, old_json, ver, code, domain, app_id, notes, channel = sys.argv[1:9]
data = open(apk, 'rb').read()
releases = []
if os.path.exists(old_json):
    try:
        old = json.load(open(old_json, encoding='utf-8'))
        releases = [r for r in old.get('releases', []) if str(r.get('versionCode')) != code]
    except Exception:
        releases = []
releases.insert(0, {
    "versionName": ver,
    "versionCode": int(code),
    "url": f"https://{domain}/apps/{app_id}/{os.path.basename(apk)}",
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "date": time.strftime("%Y-%m-%d"),
    "channel": channel,
    "notes": notes,
})
releases.sort(key=lambda r: int(r.get('versionCode', 0)), reverse=True)
doc = {"app": app_id, "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
       "schema": 1, "releases": releases}
json.dump(doc, open('/tmp/rel_new.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(f"    清单含 {len(releases)} 个版本")
PY
  "${SCP[@]}" /tmp/rel_new.json "$USER@$HOST:/tmp/rel_new.json"
  "${SSH[@]}" "echo '$PASS' | sudo -S bash -c 'mkdir -p ${SITE}/api/apps/${app_id} && install -m 644 /tmp/rel_new.json ${SITE}/api/apps/${app_id}/releases.json'" >/dev/null
  curl -s -o /dev/null -w "    回验 $app_id: %{http_code}\n" --max-time 20 \
    "https://${DOMAIN}/api/apps/${app_id}/releases.json"
}

publish_app "tavernium" "$REL_APK" "stable" "$NOTES"
publish_app "tavernium-debug" "$DBG_APK" "debug" "[调试版] ${NOTES}"

echo
echo "正式版：https://${DOMAIN}/apps/tavernium/$(basename "$REL_APK")"
echo "调试版：https://${DOMAIN}/apps/tavernium-debug/$(basename "$DBG_APK")"
echo "下载页：https://${DOMAIN}/"
