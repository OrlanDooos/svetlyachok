#!/usr/bin/env python3
"""Сборщик графиков отключений для «Світлячка».

Забирает графики с сайтов и каналов облэнерго, приводит к одному формату и пишет
статические JSON в каталог out/v1/. GitHub Actions публикует его в ветку data.

Формат слота: строка из 48 символов на сутки (по 30 минут с 00:00 по Киеву):
  '1' — свет есть, '0' — отключение, '2' — возможно отключение.
Только стандартная библиотека Python, чтобы запускаться где угодно.
"""
import datetime as dt
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.request
from zoneinfo import ZoneInfo

KYIV = ZoneInfo("Europe/Kyiv")
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
OUT = os.environ.get("OUT_DIR", os.path.join(os.path.dirname(__file__), "out"))

MONTHS = {"січня": 1, "лютого": 2, "березня": 3, "квітня": 4, "травня": 5, "червня": 6, "липня": 7,
          "серпня": 8, "вересня": 9, "жовтня": 10, "листопада": 11, "грудня": 12}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def fetch(url, timeout=40, data=None, headers=None):
    h = {"User-Agent": UA, "Accept-Language": "uk-UA,uk;q=0.9"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h)
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # сеть нестабильна — пробуем ещё
            last = e
            time.sleep(3 * (attempt + 1))
    raise last


# ------------------------------------------------------------------ helpers

def empty_day():
    return ["1"] * 48


def mark(day, start_min, end_min, value="0"):
    a = max(0, start_min // 30)
    b = min(48, (end_min + 29) // 30)
    for i in range(a, b):
        day[i] = value


def hm(s):
    h, m = s.split(":")
    return int(h) * 60 + int(m)


def norm_queue(q):
    q = q.strip().replace("GPV", "").replace(",", ".")
    return q if "." in q else q + ".1"


def detect_mode(text):
    """Режим по тексту объявления: emergency — графики не действуют."""
    t = (text or "").lower()
    if re.search(r"(екстрені|аварійні)\s+відключення", t) and re.search(r"графік\w*\s+(тут\s+)?не\s+(працюють|діють|застосовуються)", t):
        return "emergency"
    if "блекаут" in t:
        return "blackout"
    return None


# ------------------------------------------------------------------ DTEK (Київ, Київщина, Дніпро, Одеса)

DTEK_VALUES = {"yes": ("1", "1"), "no": ("0", "0"), "first": ("0", "1"), "second": ("1", "0"),
               "maybe": ("2", "2"), "mfirst": ("2", "1"), "msecond": ("1", "2")}


def parse_dtek_fact(fact):
    days = {}
    for ts, groups in (fact.get("data") or {}).items():
        date = dt.datetime.fromtimestamp(int(ts), KYIV).date().isoformat()
        out = {}
        for g, hours in groups.items():
            day = empty_day()
            for h, v in hours.items():
                a, b = DTEK_VALUES.get(v, ("1", "1"))
                i = (int(h) - 1) * 2
                day[i], day[i + 1] = a, b
            out[norm_queue(g)] = "".join(day)
        days[date] = out
    return days


def parse_dtek_preset(preset):
    """Недельный шаблон ДТЭК: для каждой очереди 7 строк (пн…нд). Нужен для прогноза на неделю."""
    week = {}
    for g, days in (preset.get("data") or {}).items():
        rows = []
        for wd in range(1, 8):
            day = empty_day()
            for h, v in (days.get(str(wd)) or {}).items():
                a, b = DTEK_VALUES.get(v, ("1", "1"))
                i = (int(h) - 1) * 2
                day[i], day[i + 1] = a, b
            rows.append("".join(day))
        week[norm_queue(g)] = rows
    return week


def dtek(site, mirror):
    """Сначала сайт ДТЭК, при неудаче — зеркало outage-data-ua (MIT), где та же структура «как есть»."""
    try:
        page = fetch(f"https://www.{site}/ua/shutdowns")
        m = re.search(r"DisconSchedule\.fact\s*=\s*(\{.*?\})\s*(?:;|\n|</script>)", page, re.S)
        if not m:
            raise ValueError("no DisconSchedule.fact")
        fact = json.loads(m.group(1))
        mp = re.search(r"DisconSchedule\.preset\s*=\s*(\{.*?\})\s*(?:;|\n|</script>)", page, re.S)
        preset = json.loads(mp.group(1)) if mp else {}
        att = re.search(r'<div class="m-attention__text">(.*?)</div>', page, re.S)
        attention = html.unescape(re.sub(r"<[^>]+>", " ", att.group(1))).strip() if att else None
        src = f"https://www.{site}/ua/shutdowns"
    except Exception as e:
        log(f"  {site}: прямой запрос не удался ({e}), берём зеркало")
        d = json.loads(fetch(f"https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/{mirror}.json"))
        fact = d["fact"]
        preset = d.get("preset") or {}
        attention = (d.get("attention") or {}).get("text")
        src = f"https://www.{site}/ua/shutdowns"
    days = parse_dtek_fact(fact)
    return {"days": days, "week": parse_dtek_preset(preset), "attention": attention, "source": src, "updatedAtSource": fact.get("update")}


# ------------------------------------------------------------------ Текстовые графики «1.1: 00:00 - 02:00, ...»

def parse_text_schedule(text):
    """Разбирает строки вида «1.1: 00:00 - 02:00, 17:30 - 22:30» или
    «Група 1.1. Електроенергії немає з 17:00 до 20:00»."""
    res = {}
    for line in text.splitlines():
        m = re.match(r"\s*(?:Група|Черга|Підчерга)?\s*(\d{1,2}[.,]\d)\s*[.:)]?\s*(.*)", line, re.I)
        if not m:
            continue
        q, rest = norm_queue(m.group(1)), m.group(2)
        day = empty_day()
        ranges = re.findall(r"(\d{1,2}:\d{2})\s*(?:-|–|—|до)\s*(\d{1,2}:\d{2})", rest)
        for a, b in ranges:
            s, e = hm(a), hm(b)
            if e == 0:
                e = 24 * 60
            if e > s:
                mark(day, s, e)
        if ranges or re.search(r"(є|відсутні відключення|не відключа)", rest):
            res[q] = "".join(day)
    return res


def find_date(text, fallback_year):
    m = re.search(r"(\d{1,2})\s+(" + "|".join(MONTHS) + r")", text, re.I)
    if m:
        return dt.date(fallback_year, MONTHS[m.group(2).lower()], int(m.group(1)))
    m = re.search(r"(\d{2})\.(\d{2})\.(\d{4})", text)
    if m:
        return dt.date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
    return None


# ------------------------------------------------------------------ Львівобленерго (API)

def lviv():
    d = json.loads(fetch("https://api.loe.lviv.ua/api/menus?page=1&type=photo-grafic"))
    items = d[0]["menuItems"] if isinstance(d, list) else d["hydra:member"][0]["menuItems"]
    days = {}
    now = dt.datetime.now(KYIV)
    for it in items:
        if it.get("name") not in ("Today", "Tomorrow"):
            continue
        raw = it.get("rawHtml") or ""
        text = html.unescape(re.sub(r"<br\s*/?>|</p>", "\n", raw))
        text = re.sub(r"<[^>]+>", "", text)
        date = find_date(text, now.year)
        if not date:
            continue
        sched = parse_text_schedule(text)
        if sched:
            days[date.isoformat()] = sched
    return {"days": days, "attention": None, "source": "https://poweron.loe.lviv.ua/"}


# ------------------------------------------------------------------ Telegram-каналы облэнерго

def telegram_posts(channel):
    page = fetch(f"https://t.me/s/{channel}")
    posts = []
    for block in re.findall(r'<div class="tgme_widget_message_wrap.*?</time>', page, re.S):
        t = re.search(r'<div class="tgme_widget_message_text js-message_text"[^>]*>(.*?)</div>', block, re.S)
        when = re.search(r'datetime="([^"]+)"', block)
        if not t or not when:
            continue
        text = html.unescape(re.sub(r"<br\s*/?>", "\n", t.group(1)))
        posts.append((dt.datetime.fromisoformat(when.group(1)), re.sub(r"<[^>]+>", "", text)))
    return posts


def telegram_gpv(channel, source_url):
    """Берём последний пост с ГПВ на каждую дату (более поздний пост заменяет ранний)."""
    days, attention = {}, None
    for when, text in telegram_posts(channel):
        local = when.astimezone(KYIV)
        if re.search(r"ГПВ|графік\w* погодинних", text, re.I):
            date = find_date(text, local.year) or local.date()
            sched = parse_text_schedule(text)
            if len(sched) >= 4:
                days[date.isoformat()] = sched
        mode = detect_mode(text)
        if mode and (dt.datetime.now(KYIV) - local) < dt.timedelta(hours=12):
            attention = text.strip()[:600]
    return {"days": days, "attention": attention, "source": f"https://t.me/{channel}"}


# ------------------------------------------------------------------ регионы

REGIONS = [
    # id области как на карте приложения, имя, функция-сборщик
    ("kyiv-city", "Київ", "ДТЕК Київські електромережі", lambda: dtek("dtek-kem.com.ua", "kyiv")),
    ("kyiv", "Київська область", "ДТЕК Київські регіональні електромережі", lambda: dtek("dtek-krem.com.ua", "kyiv-region")),
    ("dnipropetrovsk", "Дніпропетровська область", "ДТЕК Дніпровські електромережі", lambda: dtek("dtek-dnem.com.ua", "dnipro")),
    ("odessa", "Одеська область", "ДТЕК Одеські електромережі", lambda: dtek("dtek-oem.com.ua", "odesa")),
    ("lviv", "Львівська область", "Львівобленерго", lviv),
    ("zaporizhia", "Запорізька область", "Запоріжжяобленерго", lambda: telegram_gpv("zoe_alarm", "https://www.zoe.com.ua/")),
]


def summarize(days, today):
    """Среднее время без света за сегодня по всем очередям, в минутах."""
    d = days.get(today.isoformat())
    if not d:
        return None
    offs = [s.count("0") * 30 for s in d.values()]
    return round(sum(offs) / len(offs)) if offs else None


def main():
    os.makedirs(os.path.join(OUT, "v1"), exist_ok=True)
    now = dt.datetime.now(KYIV)
    today = now.date()
    prev = {}
    idx_path = os.path.join(OUT, "v1", "regions.json")
    if os.path.exists(idx_path):
        prev = {r["id"]: r for r in json.load(open(idx_path))["regions"]}
    index = []
    for rid, name, operator, fn in REGIONS:
        log(f"→ {rid}")
        path = os.path.join(OUT, "v1", f"{rid}.json")
        try:
            r = fn()
            old = json.load(open(path)) if os.path.exists(path) else {}
            # История: прошлые дни берём из прошлого файла, чтобы считать статистику за неделю.
            merged = {k: v for k, v in (old.get("days") or {}).items() if k < today.isoformat()}
            merged.update(r["days"])
            r["days"] = {k: v for k, v in sorted(merged.items()) if k >= (today - dt.timedelta(days=8)).isoformat()}
            cur = {k: v for k, v in r["days"].items() if k >= today.isoformat()}
            mode = detect_mode(r.get("attention")) or ("schedule" if any(("0" in s) for d in cur.values() for s in d.values()) else ("none" if cur else "unknown"))
            body = {"id": rid, "name": name, "operator": operator, "source": r["source"], "mode": mode,
                    "attention": r.get("attention"), "updatedAtSource": r.get("updatedAtSource"), "days": r["days"],
                    "week": r.get("week") or {}}
            h = hashlib.sha1(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:12]
            changed = old.get("hash") != h
            body.update({"hash": h, "changedAt": now.isoformat(timespec="seconds") if changed else old.get("changedAt"),
                         "checkedAt": now.isoformat(timespec="seconds"), "ok": True})
            json.dump(body, open(path, "w"), ensure_ascii=False, separators=(",", ":"))
            queues = sorted({q for d in r["days"].values() for q in d}, key=lambda q: tuple(int(x) for x in q.split(".")))
            index.append({"id": rid, "name": name, "operator": operator, "mode": mode, "ok": True, "hash": h,
                          "changedAt": body["changedAt"], "checkedAt": body["checkedAt"],
                          "avgOffMinutesToday": summarize(r["days"], today), "queues": queues})
            log(f"  ok: {mode}, дней {len(r['days'])}, очередей {len(queues)}, {'ИЗМЕНЕНО' if changed else 'без изменений'}")
        except Exception as e:
            log(f"  ошибка: {e}")
            p = prev.get(rid, {"id": rid, "name": name, "operator": operator, "mode": "unknown", "queues": []})
            p.update({"ok": False, "error": str(e)[:200]})
            index.append(p)
    json.dump({"generatedAt": now.isoformat(timespec="seconds"), "regions": index},
              open(idx_path, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
