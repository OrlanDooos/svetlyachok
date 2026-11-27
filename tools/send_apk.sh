#!/usr/bin/env bash
# Отправляет собранный APK владельцу в Telegram через Worker (POST /admin/apk).
# Использование: SVETLO_ADMIN_KEY=... tools/send_apk.sh app/build/outputs/apk/release/app-release.apk "1.5: что нового"
# Ключ ADMIN_KEY — секрет Worker; в репозиторий не кладётся.
set -euo pipefail
apk="$1"; caption="${2:-Новая версия «Світлячка»}"
url="${SVETLO_PINS_URL:-https://svetlyachok-pins.svetlyachok.workers.dev}"
: "${SVETLO_ADMIN_KEY:?нужен SVETLO_ADMIN_KEY}"
curl -sS --fail-with-body -X POST "$url/admin/apk" -H "Authorization: Bearer $SVETLO_ADMIN_KEY" \
  -F "file=@$apk;type=application/vnd.android.package-archive" --form-string "caption=$caption"
echo
