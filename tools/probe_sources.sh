#!/bin/bash
# Проверка доступности сайтов облэнерго с машины, где запущен скрипт.
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
while read -r name url; do
  [ -z "$name" ] && continue
  r=$(curl -s -L -o /tmp/p.html -m 30 -A "$UA" -w "%{http_code} %{size_download}" "$url")
  printf "%-14s %-60s %s %s\n" "$name" "$url" "$r" "$(grep -o '<title>[^<]*' /tmp/p.html | head -1 | cut -c8-60)"
done <<'LIST'
kyiv https://www.dtek-kem.com.ua/ua/shutdowns
kyiv-obl https://www.dtek-krem.com.ua/ua/shutdowns
dnipro https://www.dtek-dnem.com.ua/ua/shutdowns
odesa https://www.dtek-oem.com.ua/ua/shutdowns
donetsk https://www.dtek-dem.com.ua/ua/shutdowns
yasno https://api.yasno.com.ua/api/v1/pages/home/schedule-turn-off-electricity
zaporizhzhia https://www.zoe.com.ua/
zp-gpv https://www.zoe.com.ua/%D0%B3%D1%80%D0%B0%D1%84%D1%96%D0%BA%D0%B8-%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B8%D0%BD%D0%BD%D0%B8%D1%85-%D1%81%D1%82%D0%B0%D0%B1%D1%96%D0%BB%D1%96%D0%B7%D0%B0%D1%86%D1%96%D0%B9%D0%BD%D0%B8%D1%85/
lviv https://api.loe.lviv.ua/api/menus?page=1&type=photo-grafic
kharkiv https://www.oblenergo.kharkov.ua/
poltava https://www.poe.pl.ua/
vinnytsia https://www.voe.com.ua/
cherkasy https://www.cherkasyoblenergo.com/
mykolaiv-api https://off.energy.mk.ua/api/v2/schedule/active
zt-gpv https://www.ztoe.com.ua/unhooking-search.php
telegram https://t.me/s/Ukrenergo
sumy https://www.sumyoblenergo.com.ua/
mykolaiv https://www.energy.mk.ua/
ternopil https://www.toe.com.ua/
rivne https://www.roe.vsei.ua/
zhytomyr https://www.ztoe.com.ua/
khmelnytskyi https://www.hoe.com.ua/
ivano-frank https://www.oe.if.ua/
zakarpattia https://www.zakarpat.energy/
chernivtsi https://www.oblenergo.cv.ua/
dnipro-cek https://www.cek.dp.ua/
kherson https://www.ksoe.com.ua/
chernihiv https://www.chernigivoblenergo.com.ua/
volyn https://www.energy.volyn.ua/
kirovohrad https://kiroe.com.ua/
ukrenergo https://www.ukrenergo.energy/
LIST

# Пробный сбор новых областей с этой машины (GitHub Actions) — без публикации.
echo; echo "=== пробный сбор"
export OUT_DIR=$(mktemp -d); ONLY=zaporizhia,ivano-frankivsk,ternopil,sumy,chernihiv,chernivtsi,volyn,zakarpattia,vinnytsia,poltava,kherson,donetsk \
  python3 -c "import sys; sys.path.insert(0, 'collector'); import addresses; addresses.update = lambda *a, **k: None; import collect; collect.main()" &&
  python3 -c "import json,glob; [print(f.split('/')[-1], {d: v.get('1.1') or v.get('1') for d, v in json.load(open(f))['days'].items()}) for f in sorted(glob.glob('$OUT_DIR/v1/*.json')) if 'regions' not in f]"
