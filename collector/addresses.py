"""Адреса → подочередь по официальным спискам облэнерго.

Запоріжжяобленерго публикует для города по Google-таблице на каждую подочередь
(ссылки в t.me/Zaporizhzhyaoblenergo_news и на zoe.com.ua). Строка: улица | дома через «|».
Пишем v1/addr/{region}.json: {"streets": {ключ: {"n": название, "q": {"1.1": "дом|дом", "1.1,2.2": "дом"}}},
"aliases": {старое имя: ключ}}. Дом в нескольких подочередях (разные подъезды) — «1.1,2.2».
Ключ улицы и дома нормализуются так же, как в приложении (AddressIndex.kt).
"""
import csv, io, json, os, re, datetime as dt
import urllib.request

ZOE_CITY = {  # таблица → заголовок проверяем при разборе
    "1JxeTi3tR7vRLPIhM6v32W3C_0-Ig28sjT--3wECuw9M": "1.1",
    "19q7Gj5qW6k6GMixakc7cuUAxqyCW5x8cr-tViD3xIQw": "1.2",
    "1BPbAd3VV4h8Hkle1Cczi4GCt2EggmbPhh396MxBz3hU": "2.2",  # 2.1: населення не задіяно
    "1tiInJeUJrAS0jy-v_Pad4YIa6LEF2vOfVH_m0hGsil8": "3.1",
    "1WeC9RtYQWb3y8Xy7oacB_WUl743ynT6IuqAj9WdOffI": "3.2",
    "1kIS8FDTLvfq1eZTcaiuSJwHippVKlsO-N_a10FAyksA": "4.1",
    "1MvwotnCvVA7BzYERRQ_CES80LWVbbEwE5DTfrm9D-jA": "4.2",
    "1ecxXysI0ls1ztebMuF1ZERWzt9lV84fUO-UxAhMG7SY": "5.1",
    "19kPmcvFtFDJSre-HiiV0Xs607CURnN4fHV_L-p6K4P0": "5.2",
    "1CSl6docaZLiCNDQNjoQuRWFpK_OdkNCJRF7H0oACycA": "6.1",
    "1Isd584TNNw5k0vhrate9kR9z-52LMnFjU24MIkaexkg": "6.2",
}

TYPE_WORDS = {
    "вул", "вулиця", "вулица", "ул", "улица", "бул", "бульв", "бульвар", "просп", "пр", "пр-т", "проспект",
    "пров", "провулок", "переулок", "пер", "пл", "площа", "площадь", "шосе", "ш", "узвіз", "узв", "тупик",
    "проїзд", "проїз", "набережна", "наб", "алея", "сел", "селище", "м-н", "мікрорайон", "мкр", "дорога", "в'їзд", "вʼїзд",
}
LAT = str.maketrans("aceiopxykmhtbABCEHIKMOPTXY", "асеіорхукмнтвАВСЕНІКМОРТХУ")


def street_keys(name):
    """Ключи улицы: основное имя и старое имя в скобках. «вул. Арсена Коваленка (Адмірала Макарова)»."""
    name = name.replace("’", "'").replace("ʼ", "'").replace("`", "'").replace("«", "").replace("»", "").replace('"', "")
    parts = [name.split("(")[0]] + re.findall(r"\(([^)]*)\)", name)
    keys = []
    for p in parts:
        words = [w for w in re.split(r"[\s.,]+", p.lower().translate(LAT)) if w]
        words = [w for w in words if w not in TYPE_WORDS]
        k = " ".join(words).replace("'", "")
        if k:
            keys.append(k)
    return keys


def norm_house(h):
    """«17 п.1–4» → «17», «5 А» → «5а», «34-А» → «34а», «16 (9 пов)» → «16», «142/1» → «142/1»."""
    h = h.lower().translate(LAT)
    h = re.sub(r"\(.*?\)", " ", h)
    h = re.sub(r"\bп\.?\s*\d.*$", " ", h)       # подъезды
    h = re.sub(r"корп(ус)?\.?\s*", "к", h)
    h = re.sub(r"\s+", "", h).replace("–", "-").replace("—", "-")
    h = re.sub(r"(\d)-([а-яіїєґ])$", r"\1\2", h)  # «34-А» = «34а»
    return h.strip(".,;")


def expand(token):
    """Номера из записи: «13-31», «32–66 парні», «12а», «1–9». Пустой список, если не разобрали."""
    t = token.strip()
    if not t:
        return []
    parity = None
    if "непарн" in t:
        parity = 1
    elif "парн" in t:
        parity = 0
    t = re.sub(r"\s*(не)?парн\S*", "", t)
    m = re.fullmatch(r"\s*(\d+)\s*[-–—]\s*(\d+)[а-яa-z]?\s*", t.lower())
    if m:
        a, b = int(m.group(1)), int(m.group(2))
        if b < a or b - a > 400:
            return [str(a)]
        if parity is None:
            # В украинских списках диапазон «13-31» — одна сторона улицы, если чётность концов совпадает.
            step_all = (a % 2) != (b % 2)
            return [str(x) for x in range(a, b + 1) if step_all or x % 2 == a % 2]
        return [str(x) for x in range(a, b + 1) if x % 2 == parity]
    h = norm_house(t)
    if not re.match(r"^\d", h):
        return []
    # Угловой дом «68/Дніпровська,24»: на карте он подписан просто «68».
    m = re.match(r"^(\d+[а-яі]?)/[а-яіїєґ]", h)
    return [h, m.group(1)] if m else [h]


def fetch_sheet(sheet_id):
    url = f"https://docs.google.com/spreadsheets/d/{sheet_id}/gviz/tq?tqx=out:csv"
    req = urllib.request.Request(url, headers={"User-Agent": "svetlyachok-collector"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8")


def zaporizhia_city(log=print):
    streets, aliases = {}, {}
    for sid, queue in ZOE_CITY.items():
        rows = list(csv.reader(io.StringIO(fetch_sheet(sid))))
        title = " ".join(rows[0]) if rows else ""
        m = re.search(r"(\d)\s*черга\s*(\d)\s*підчерга", title)
        if not m or f"{m.group(1)}.{m.group(2)}" != queue or "Запоріжжя" not in title:
            raise ValueError(f"таблица {sid}: неожиданный заголовок «{title[:60]}»")
        for r in rows[2:]:
            if len(r) < 2 or not r[0].strip():
                continue
            keys = street_keys(r[0])
            if not keys:
                continue
            main = keys[0]
            s = streets.setdefault(main, {"n": r[0].split("(")[0].strip(), "h": {}})
            for k in keys[1:]:
                if k != main and k not in streets:
                    aliases.setdefault(k, main)
            for tok in r[1].split("|"):
                for h in expand(tok):
                    qs = set(s["h"].get(h, "").split(",")) - {""}
                    qs.add(queue)
                    s["h"][h] = ",".join(sorted(qs))
        log(f"  {queue}: {len(rows) - 2} улиц")
    # Дома улицы: {подочередь: "дом|дом|..."} — так файл в 3 раза меньше.
    out = {}
    for k, s in streets.items():
        q = {}
        for h, qs in s["h"].items():
            q.setdefault(qs, []).append(h)
        out[k] = {"n": s["n"], "q": {qs: "|".join(hs) for qs, hs in q.items()}}
    return out, {k: v for k, v in aliases.items() if k not in out}


ADDR_SOURCES = {
    "zaporizhia": ("Запоріжжяобленерго: переліки адрес за підчергами ГПВ, м. Запоріжжя",
                   "https://t.me/s/Zaporizhzhyaoblenergo_news", zaporizhia_city),
}


def update(out_dir, now, log=print, max_age_hours=24):
    """Обновляет файлы адресов не чаще раза в сутки: списки меняются редко."""
    d = os.path.join(out_dir, "v1", "addr")
    os.makedirs(d, exist_ok=True)
    for rid, (title, src, fn) in ADDR_SOURCES.items():
        path = os.path.join(d, f"{rid}.json")
        if os.path.exists(path):
            try:
                old = json.load(open(path))
                age = now - dt.datetime.fromisoformat(old["generatedAt"])
                if age < dt.timedelta(hours=max_age_hours):
                    continue
            except Exception:
                pass
        log(f"→ адреса {rid}")
        try:
            streets, aliases = fn(log)
            json.dump({"region": rid, "title": title, "source": src, "generatedAt": now.isoformat(timespec="seconds"),
                       "streets": streets, "aliases": aliases}, open(path, "w"), ensure_ascii=False, separators=(",", ":"))
            log(f"  ok: улиц {len(streets)}, домов {sum(len(v.split('|')) for s in streets.values() for v in s['q'].values())}")
        except Exception as e:
            log(f"  ошибка адресов {rid}: {e}")
