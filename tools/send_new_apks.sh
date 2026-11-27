#!/usr/bin/env bash
# Отправляет в Telegram владельца каждый svetlyachok-*.apk из общей папки проекта, который туда ещё не уходил.
# Запускать после любой сборки (из любого потока): tools/send_new_apks.sh
# Что уже отправлено — в $DIR/.sent-apks (по имени файла и его sha256), ключ — в signing/cloudflare.env.
set -euo pipefail
DIR="${SVETLO_APK_DIR:-/mnt/project-files/svetlyachok}"
ENV="${SVETLO_ENV:-$DIR/signing/cloudflare.env}"
[ -n "${SVETLO_ADMIN_KEY:-}" ] || { set -a; . "$ENV"; set +a; }
SENT="$DIR/.sent-apks"; touch "$SENT"
here="$(cd "$(dirname "$0")" && pwd)"
for apk in $(ls -tr "$DIR"/svetlyachok-*.apk); do
  name="$(basename "$apk")"
  case "$name" in *-test.apk) continue;; esac
  sum="$(sha256sum "$apk" | cut -c1-16)"
  grep -qx "$name $sum" "$SENT" && continue
  ver="${name#svetlyachok-}"; ver="${ver%.apk}"
  echo "отправляю $name"
  "$here/send_apk.sh" "$apk" "Світлячок $ver${SVETLO_APK_NOTE:+: $SVETLO_APK_NOTE}"
  echo "$name $sum" >> "$SENT"
done
