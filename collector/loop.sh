#!/usr/bin/env bash
# Собирает графики каждые 10 минут в течение LOOP_SECONDS и публикует их в ветку data.
# Расписание GitHub (cron) часто срабатывает с опозданием на часы, поэтому один запуск
# работает почти 6 часов, а в конце запускает себя снова.
set -u
end=$(( $(date +%s) + ${LOOP_SECONDS:-20400} ))
publish() {
  # Карты домов собирает отдельная еженедельная задача (maps.yml) в ветку maps — добавляем их в data.
  if git fetch -q --depth 1 origin maps 2>/dev/null; then
    rm -rf collector/out/v1/maps && mkdir -p collector/out/v1/maps
    git --work-tree=collector/out checkout origin/maps -- v1/maps 2>/dev/null && git reset -q
  fi
  ( cd collector/out
    rm -rf .git && git init -q -b data
    git config user.name "svetlyachok-bot"
    git config user.email "svetlyachok-bot@users.noreply.github.com"
    touch .nojekyll
    git add -A && git commit -qm "data $(date -u +%FT%TZ)"
    for i in 1 2 3; do git push -qf "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}" data && break; sleep 10; done )
}
while :; do
  started=$(date +%s)
  python3 collector/collect.py || echo "сборщик завершился с ошибкой"
  publish || echo "не удалось опубликовать"
  next=$(( started + ${INTERVAL:-600} ))
  [ "$next" -ge "$end" ] && break
  sleep $(( next - $(date +%s) > 0 ? next - $(date +%s) : 0 ))
done
