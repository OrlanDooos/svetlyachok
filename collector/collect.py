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
import struct
import subprocess
import tempfile
import sys
import time
import zlib
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


EMERGENCY_ON = re.compile(
    r"(запроваджено|запроваджені|застосовано|застосовані|введено|введені|діють|діятимуть|застосовуватимуться)\s+(\S+\s+){0,3}?"
    r"(графік\w*\s+аварійних|аварійні\s+відключення|екстрені\s+відключення|гав\b|сгав\b)")
EMERGENCY_OFF = re.compile(
    r"((скасован\w*|відмінен\w*|припинен\w*|завершено)\s+(\S+\s+){0,3}?(гав\b|графік\w*\s+аварійних|аварійн\w*\s+відключ|екстрен\w*\s+відключ))"
    r"|((гав|аварійні відключення|екстрені відключення)\s+(скасовано|скасовані|не застосовуються|не діють|припинено))"
    r"|(повернення до (графіків погодинних|гпв)\s+(відбулося|здійснено))|(повертаємося до (графіків погодинних|гпв))"
    r"|(повернулис[ья] до (графіків погодинних|гпв))")


def detect_mode(text):
    """Режим по тексту объявления: emergency — аварийные отключения (ГАВ, графики могут не действовать)."""
    t = (text or "").lower()
    if re.search(r"(екстрені|аварійні)\s+відключення", t) and re.search(r"графік\w*\s+(тут\s+)?не\s+(працюють|діють|застосовуються)", t):
        return "emergency"
    if EMERGENCY_ON.search(t) and not EMERGENCY_OFF.search(t):
        return "emergency"
    if "блекаут" in t:
        return "blackout"
    return None


def lifted(text):
    """Объявление об отмене аварийных отключений."""
    return bool(EMERGENCY_OFF.search((text or "").lower()))


# ------------------------------------------------------------------ DTEK (Київ, Київщина, Дніпро, Одеса)

DTEK_VALUES = {"yes": ("1", "1"), "no": ("0", "0"), "first": ("0", "1"), "second": ("1", "0"),
               "maybe": ("2", "2"), "mfirst": ("2", "1"), "msecond": ("1", "2")}


def clean_text(s):
    """Убирает лишние пробелы из HTML-текста: «енергії .» → «енергії.»."""
    if not s:
        return s
    s = re.sub(r"[ \t\u00a0]+", " ", s)
    s = re.sub(r" *\n *", "\n", s)
    s = re.sub(r"\n{2,}", "\n", s)
    s = re.sub(r" +([.,;:!?])", r"\1", s)
    return s.strip() or None


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
    # Когда недельный график не действует, ДТЭК отдаёт шаблон «свет есть всегда» — это не прогноз.
    if all(set(r) == {"1"} for rows in week.values() for r in rows):
        return {}
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
        attention = clean_text(html.unescape(re.sub(r"<[^>]+>", " ", att.group(1)))) if att else None
        src = f"https://www.{site}/ua/shutdowns"
    except Exception as e:
        log(f"  {site}: прямой запрос не удался ({e}), берём зеркало")
        d = json.loads(fetch(f"https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/{mirror}.json"))
        fact = d["fact"]
        preset = d.get("preset") or {}
        attention = clean_text((d.get("attention") or {}).get("text"))
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

def telegram_posts(channel, hours=36, max_pages=8):
    """Посты канала за последние [hours] часов, по возрастанию времени.

    t.me/s показывает только ~20 последних постов; в загруженные дни (много сообщений об аварийных
    отключениях) пост с ГПВ на сегодня быстро уходит за первую страницу, поэтому листаем назад (?before=).
    """
    posts, url = {}, f"https://t.me/s/{channel}"
    since = dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=hours)
    for _ in range(max_pages):
        page = fetch(url)
        ids = []
        for block in re.findall(r'<div class="tgme_widget_message_wrap.*?</time>', page, re.S):
            pid = re.search(r'data-post="[^"/]+/(\d+)"', block)
            if pid: ids.append(int(pid.group(1)))
            t = re.search(r'<div class="tgme_widget_message_text js-message_text"[^>]*>(.*?)</div>', block, re.S)
            when = re.search(r'datetime="([^"]+)"', block)
            if not t or not when:
                continue
            text = html.unescape(re.sub(r"<br\s*/?>", "\n", t.group(1)))
            key = int(pid.group(1)) if pid else len(posts) + 10**9
            posts[key] = (dt.datetime.fromisoformat(when.group(1)), re.sub(r"<[^>]+>", "", text))
        oldest = min((w for w, _ in posts.values()), default=None)
        if not ids or (oldest and oldest < since):
            break
        url = f"https://t.me/s/{channel}?before={min(ids)}"
    return sorted(posts.values(), key=lambda p: p[0])


OBLASTS = r"(вінниц|волин|дніпропетровськ|донецьк|житомир|закарпат|запорі|івано-франків|київськ|кіровоград|луганськ|львів|миколаїв|одес|полтав|рівнен|сумськ|тернопіл|харків|херсон|хмельниц|черкас|чернівец|чернігів)"


def telegram_attention(posts, own, now=None):
    """Объявление об аварийных отключениях за последние 12 часов (None — их нет или их отменили).

    [own] — корень названия своей области: пост, где названы только другие области, пропускаем.
    """
    now = now or dt.datetime.now(KYIV)
    attention = None
    for when, text in posts:
        if (now - when.astimezone(KYIV)) >= dt.timedelta(hours=12):
            continue
        low = text.lower()
        named = set(re.findall(OBLASTS, low))
        if named and own not in named:
            continue
        if detect_mode(text):
            attention = clean_text(text)[:600]
        elif lifted(text):
            attention = None
    return attention


def telegram_gpv(channels, source_url, own):
    """Берём последний пост с ГПВ на каждую дату (более поздний пост заменяет ранний).

    Режим: последнее за 12 часов объявление об аварийных отключениях (ГАВ) включает «аварийные»,
    более позднее объявление об их отмене — выключает. Каналы читаем все сразу, по времени.
    """
    posts = sorted((p for ch in channels for p in telegram_posts(ch)), key=lambda p: p[0])
    days = {}
    for when, text in posts:
        local = when.astimezone(KYIV)
        if re.search(r"ГПВ|графік\w* погодинних", text, re.I):
            date = find_date(text, local.year) or local.date()
            sched = parse_text_schedule(text)
            if len(sched) >= 4:
                days[date.isoformat()] = sched
    return {"days": days, "attention": telegram_attention(posts, own), "source": f"https://t.me/{channels[0]}"}


def with_channel_attention(fn, channels, own):
    """Сборщик с сайта + объявления об аварийных из официальных Telegram-каналов облэнерго."""
    def run():
        r = fn()
        if not detect_mode(r.get("attention")):
            try:
                posts = sorted((p for ch in channels for p in telegram_posts(ch)), key=lambda p: p[0])
                att = telegram_attention(posts, own)
                if att:
                    r["attention"] = att
            except Exception as e:  # канал недоступен — режим определяем по сайту
                log(f"  канал {channels}: {e}")
        return r
    return run


# ------------------------------------------------------------------ Остальные области (сайты и каналы облэнерго)

QMARK = re.compile(r"(?<![\d.,:/])([1-6])\s?[.,/]\s?([12])(?!\d)")
QRANGE = re.compile(r"(?<![\d.])(\d{1,2})(?::(\d{2}))?\s*(?:-|–|—|до)\s*(\d{1,2})(?::(\d{2}))?(?![\d.])")
DATE_ANY = re.compile(r"(\d{1,2})\s+(" + "|".join(MONTHS) + r")|(\d{2})[.\-](\d{2})[.\-](\d{4})", re.I)


def ranges_to_day(seg):
    """«08:00 - 11:00, 20:00-24:00», «04-06, 00-01:30» → 48 слотов; None, если времени нет."""
    day, found = empty_day(), False
    for a, am, b, bm in QRANGE.findall(seg):
        if not am and not bm and (len(a) != 2 or len(b) != 2):
            continue  # «2-3 години» и т. п. — не время
        s, e = int(a) * 60 + int(am or 0), int(b) * 60 + int(bm or 0)
        if e in (0, 1439):
            e = 1440
        if s >= 1440 or e > 1440 or e <= s or (am and int(am) > 59) or (bm and int(bm) > 59):
            continue
        found = True
        mark(day, s, e)
    return "".join(day) if found else None


def parse_queue_ranges(text):
    """Очереди с часами отключений в любом из форматов облэнерго:
    «Черга 1.1: 04-06, 12-14», «▪️ Підчерга 1.1 — 11:00 - 14:00», «1.1 10:00-13:30; 17:00-24:00», «2/1»."""
    marks = list(QMARK.finditer(text))
    res = {}
    for i, m in enumerate(marks):
        seg = text[m.end(): marks[i + 1].start() if i + 1 < len(marks) else len(text)]
        seg = re.split(r"\n\s*\n|[⚠❗‼➡]|Увага|УВАГА|Оновлено|Для промисл|ДЛЯ ПРОМИСЛ", seg)[0][:300]
        q = f"{m.group(1)}.{m.group(2)}"
        day = ranges_to_day(seg)
        if day is None and re.match(r"[\s:–—.-]*(не планується|не застосовується|відключень немає|без відключень|світло є)", seg, re.I):
            day = "".join(empty_day())
        if day is not None:
            res[q] = day
    return res


def _date_of(m, year):
    if m.group(1):
        return dt.date(year, MONTHS[m.group(2).lower()], int(m.group(1)))
    return dt.date(int(m.group(5)), int(m.group(4)), int(m.group(3)))


def post_schedules(text, when):
    """{дата: {очередь: 48 символов}} из одного поста. В посте может быть несколько дат
    (Рівнеобленерго: «📅 26.11 … 📅 27.11 …») — каждая очередь относится к ближайшей дате перед ней."""
    local = when.astimezone(KYIV)
    dates = []
    for m in DATE_ANY.finditer(text):
        try:
            dates.append((m.start(), _date_of(m, local.year)))
        except ValueError:
            pass
    if not dates:
        dates = [(0, local.date() + dt.timedelta(days=1 if re.search(r"\bзавтра\b", text, re.I) else 0))]
    out = {}
    for i, (pos, date) in enumerate(dates):
        end = dates[i + 1][0] if i + 1 < len(dates) else len(text)
        chunk = parse_queue_ranges(text[pos:end])
        if chunk:
            out.setdefault(date.isoformat(), {}).update(chunk)
    return out


GPV_POST = re.compile(r"ГПВ|графік\w*\s+(погодинн|відключень|заживлення)", re.I)
ADDITIVE = re.compile(r"додатков\w*\s+(години|відключ)", re.I)


def apply_update(days, date, qs, when, additive=False, min_queues=10):
    """Применяет опубликованный в момент [when] график [qs] на дату [date] к уже собранным [days].

    Первый полный график задаёт день. Более позднее обновление на тот же день меняет график только
    с момента публикации (прошедшие часы остаются как были), очереди без отключений в нём — со светом.
    additive — «додаткові години»: отключения добавляются к уже известным.
    """
    local = when.astimezone(KYIV)
    cur = days.get(date)
    if additive and cur:
        for q, s in qs.items():
            base = cur.get(q, "1" * 48)
            cur[q] = "".join("0" if s[i] == "0" else base[i] for i in range(48))
        return
    if len(qs) < 4 or (cur is None and len(qs) < min_queues):
        return
    start = 0
    if date == local.date().isoformat() and cur:
        start = max(0, min(48, (local.hour * 60 + local.minute) // 30))
    if start == 0 or not cur:
        days[date] = dict(qs) if len(qs) >= min_queues or not cur else {**{q: "1" * 48 for q in cur}, **qs}
        return
    for q in set(cur) | set(qs):
        old = cur.get(q, "1" * 48)
        new = qs.get(q, "1" * 48)
        cur[q] = old[:start] + new[start:]


def channel_schedule(channels, own, source, min_queues=10):
    """Графики из текстовых постов официальных каналов облэнерго.

    Полный пост (≥ min_queues очередей) на дату задаёт график. Более поздний пост на ту же дату
    («Оновлення графіка…») меняет график только с момента публикации: прошедшие часы остаются
    как были, а очереди, которых нет в обновлении, дальше считаются со светом.
    Пост «додаткові години відсутності» добавляет отключения к уже известным.
    """
    posts = sorted((p for ch in channels for p in telegram_posts(ch, hours=40)), key=lambda p: p[0])
    days = {}
    for when, text in posts:
        if not GPV_POST.search(text):
            continue
        for date, qs in post_schedules(text, when).items():
            apply_update(days, date, qs, when, additive=bool(ADDITIVE.search(text)), min_queues=min_queues)
    return {"days": days, "attention": telegram_attention(posts, own), "source": source, "posts": posts,
            "modeHint": schedule_hint(posts)}


def zhytomyr():
    """Житомиробленерго: таблица ГПВ на сайте (цвет ячейки — отключение), кодировка cp1251."""
    url = "https://www.ztoe.com.ua/unhooking-search.php"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as r:
        page = r.read().decode("cp1251", "replace")
    days = {}
    # Каждая таблица начинается с даты в шапке над часами «00:00-00:30».
    heads = [(m.start(), m.group(1)) for m in re.finditer(r">\s*(\d{2}\.\d{2}\.\d{4})\s*<", page)
             if "00:00-00:30" in page[m.end(): m.end() + 3000]]
    for i, (pos, d) in enumerate(heads):
        part = page[pos: heads[i + 1][0] if i + 1 < len(heads) else len(page)]
        day = {}
        for q, body in re.findall(r"<b[^>]*>\s*([1-6]\.[12])\s*</b>(?:</a>)?</td>(.*?)</tr>", part, re.S):
            cells = re.findall(r"background:\s*(#[0-9a-fA-F]{6})", body)
            if len(cells) == 48:
                day[q] = "".join("1" if c.lower() == "#ffffff" else "0" for c in cells)
        if len(day) >= 10:
            dd, mm, yy = d.split(".")
            days[f"{yy}-{mm}-{dd}"] = day
    text = clean_text(html.unescape(re.sub(r"<[^>]+>", " ", re.sub(r"<script.*?</script>|<style.*?</style>", "", page, flags=re.S))))
    att = re.search(r"УВАГА!.{0,600}?(?=Оберіть|$)", text or "", re.S)
    return {"days": days, "attention": clean_text(att.group(0))[:600] if att else None, "source": url}


def mykolaiv():
    """Миколаївобленерго: открытый API сайта off.energy.mk.ua (тот же, что показывает их страница)."""
    base = "https://off.energy.mk.ua/api"
    slots = {s["id"]: hm(s["start"][:5]) // 30 for s in json.loads(fetch(base + "/schedule/time-series"))}
    gpv = {q["id"]: q["name"] for q in json.loads(fetch(base + "/outage-queue/by-type/3"))}
    emergency = [q for t in (1, 2) for q in json.loads(fetch(f"{base}/outage-queue/by-type/{t}")) if q.get("enabled")]
    days = {}
    for sch in json.loads(fetch(base + "/v2/schedule/active")):
        start = dt.datetime.fromisoformat(sch["from"].replace("Z", "+00:00")).astimezone(KYIV)
        date = (start + dt.timedelta(hours=1)).date().isoformat()
        day = {q: empty_day() for q in gpv.values()}
        for s in sch.get("series") or []:
            q, i = gpv.get(s.get("outage_queue_id")), slots.get(s.get("time_series_id"))
            if q is None or i is None:
                continue
            day[q][i] = "2" if s.get("type") == "PROBABLY_OFF" else "0"
        days[date] = {q: "".join(v) for q, v in day.items()}
    att = None
    if emergency:
        att = "Застосовано графіки аварійних відключень (ГАВ/СГАВ) — графік може не діяти. Черги: " + \
              ", ".join(sorted({q["name"] for q in emergency}))
    return {"days": days, "attention": att, "source": "https://off.energy.mk.ua/"}


# ------------------------------------------------------------------ Графики-картинки облэнерго

CACHE = os.path.join(os.path.dirname(os.path.abspath(OUT)), "cache")


def _cache(name):
    try:
        return json.load(open(os.path.join(CACHE, name)))
    except Exception:
        return {}


def _cache_save(name, data):
    os.makedirs(CACHE, exist_ok=True)
    json.dump(data, open(os.path.join(CACHE, name), "w"))


def fetch_bytes(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()



def png_rgb(data):
    """Минимальный PNG-декодер (8 бит, RGB/RGBA/палитра/серый, без interlace) → (w, h, rows[[(r,g,b)]])."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, pal = 8, b"", None
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if typ == b"IHDR":
            w, h, depth, ctype, _, _, inter = struct.unpack(">IIBBBBB", body)
        elif typ == b"PLTE":
            pal = [tuple(body[i:i + 3]) for i in range(0, n, 3)]
        elif typ == b"IDAT":
            idat += body
        pos += 12 + n
    if depth != 8 or inter:
        raise ValueError("unsupported png")
    bpp = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw = zlib.decompress(idat)
    stride = w * bpp
    prev = bytearray(stride)
    rows, i = [], 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        if ctype == 2: px = [tuple(line[k:k + 3]) for k in range(0, stride, 3)]
        elif ctype == 6: px = [tuple(line[k:k + 3]) if line[k + 3] > 0 else (255, 255, 255) for k in range(0, stride, 4)]
        elif ctype == 3: px = [pal[v] for v in line]
        elif ctype == 0: px = [(v, v, v) for v in line]
        else: px = [(line[k],) * 3 for k in range(0, stride, 2)]
        rows.append(px)
    return w, h, rows


def grid_cells(rows, is_line, ncols, nrows_per_group, groups=1, per_cell=1):
    """Ищет таблицы: полосы между линиями сетки, в каждой полосе — ячейки между разделителями.
    Возвращает список строк (по nrows_per_group последних полос в каждой из groups таблиц),
    каждая строка — цвета центров её последних ncols ячеек."""
    h, w = len(rows), len(rows[0])
    linefrac = [sum(1 for p in r if is_line(p)) / w for r in rows]
    bands, start = [], None
    for y in range(h + 1):
        sep = y == h or linefrac[y] > 0.6
        if not sep and start is None: start = y
        if sep and start is not None:
            if y - start >= 8: bands.append((start, y))
            start = None
    good = []
    for a, b in bands:
        r = rows[(a + b) // 2]
        runs, s = [], None
        for x in range(w + 1):
            ln = x == w or is_line(r[x])
            if not ln and s is None: s = x
            if ln and s is not None:
                runs.append((s, x)); s = None
        runs = [(s, e) for s, e in runs if e - s >= 8]
        if len(runs) < ncols: continue
        tail = runs[-ncols:]
        widths = sorted(e - s for s, e in tail)
        med = widths[len(widths) // 2]
        if med < 6 or any(abs((e - s) - med) > med * 0.35 for s, e in tail): continue
        y = (a + b) // 2
        k = per_cell  # точек на ячейку: ячейка в час — две получасовые точки
        good.append((a, b, [rows[y][s + (e - s) * (2 * j + 1) // (2 * k)] for s, e in tail for j in range(k)]))
    # разбиваем на таблицы по большим разрывам по вертикали
    tables, cur = [], []
    for g in good:
        if cur and g[0] - cur[-1][1] > (cur[-1][1] - cur[-1][0]):
            tables.append(cur); cur = []
        cur.append(g)
    if cur: tables.append(cur)
    tables = [t for t in tables if len(t) >= nrows_per_group][-groups:]
    if len(tables) != groups: raise ValueError(f"таблиц {len(tables)} вместо {groups}")
    return [[c for c in row[2]] for t in tables for row in t[-nrows_per_group:]]


def ranges_day(ranges):
    day = empty_day()
    for a, b in ranges:
        mark(day, a, b)
    return "".join(day)


QUEUE_ORDER = ["1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2"]


def inneti_image(host, kind):
    """Волинь/Закарпаття: таблица ГПВ картинкой из открытого API сайта облэнерго (платформа inneti).

    Сетка без текста: закрашенная ячейка — отключение. Дата: картинка «today» на сегодня, «tomorrow» — на завтра.
    """
    opts = json.loads(fetch(f"https://{host}/api/options?option_key%5B%5D=pw_gpv_image_today&option_key%5B%5D=pw_gpv_image_tomorrow"))
    now = dt.datetime.now(KYIV)
    cache = _cache("inneti.json")
    days = {}
    for m in opts.get("hydra:member") or []:
        path = m.get("value")
        if not path:
            continue
        key = host + path
        if key not in cache:
            # Дату берём, когда картинку увидели впервые: «today» — сегодня, «tomorrow» — завтра.
            # (Поле date у них — время загрузки, а «завтра» переезжает в «сегодня» с тем же временем.)
            date = now.date() + dt.timedelta(days=1 if m["option_key"].endswith("tomorrow") else 0)
            w, h, rows = png_rgb(fetch_bytes(f"https://{host}{path}"))
            if kind == "volyn":  # две таблицы по 12 строк × 24 получаса, светлые линии, тёмно-синее — отключение
                cells = grid_cells(rows, lambda p: min(p) > 235, 24, 12, 2)
                lum = lambda p: (p[0] * 299 + p[1] * 587 + p[2] * 114) / 1000
                sched = {q: "".join("0" if lum(c) < 150 else "1" for c in cells[i] + cells[i + 12]) for i, q in enumerate(QUEUE_ORDER)}
            else:  # одна таблица 12 строк × 24 часа (по две получасовые точки), чёрные линии, белое — свет есть
                cells = grid_cells(rows, lambda p: max(p) < 90, 24, 12, 1, per_cell=2)
                sched = {q: "".join("1" if min(c) > 235 else "0" for c in cells[i]) for i, q in enumerate(QUEUE_ORDER)}
            cache[key] = {"date": date.isoformat(), "queues": sched}
            _cache_save("inneti.json", dict(list(cache.items())[-20:]))
        if cache[key]["date"] < now.date().isoformat():
            continue
        days[cache[key]["date"]] = cache[key]["queues"]
    return days


def inneti_region(host, kind, own, source):
    def run():
        r = mode_only([], own, source)
        days = inneti_image(host, kind)
        r.pop("modeOnly", None)
        r["days"] = days
        return r
    return run


OCR_TIME = re.compile(r"([0-2]\d)[:.]([0-5]\d)\s*[-–]?\s*([0-2]\d)[:.]([0-5]\d)")
OCR_QUEUES = ["1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2"]

def ocr_words(img, thr=150, invert=None, channel=None, psm="11", width=2400):
    from PIL import Image, ImageOps
    im = img.convert("RGB").split()[channel] if channel is not None else img.convert("L")
    # тёмный фон (Сумы) — инвертируем, чтобы текст был тёмным на светлом
    hist = im.histogram(); dark = sum(hist[:100]) > sum(hist[156:])
    if invert if invert is not None else dark: im = ImageOps.invert(im)
    scale = max(1, round(width / im.width))
    im = im.resize((im.width * scale, im.height * scale), Image.LANCZOS).point(lambda v: 255 if v > thr else 0)
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
        im.save(f.name); path = f.name
    try:
        out = subprocess.run(["tesseract", path, "-", "--psm", psm, "-c", "tessedit_char_whitelist=0123456789:.-", "tsv"],
                             capture_output=True, text=True, timeout=120).stdout
    finally:
        os.unlink(path)
    words = []
    for l in out.splitlines()[1:]:
        p = l.split("\t")
        if len(p) >= 12 and p[11].strip():
            words.append((int(p[6]) / scale, int(p[7]) / scale, int(p[8]) / scale, int(p[9]) / scale, p[11].strip()))
    return words


def merge_words(words):
    """Склеивает соседние слова одной строки («05:00», «-», «08:00» → «05:00-08:00»)."""
    words = sorted(words, key=lambda w: (round(w[1] / max(w[3], 1) * 2), w[0]))
    out = []
    for w in words:
        if out:
            x, y, ww, h, t = out[-1]
            hh = max(h, w[3], 1)
            if abs((w[1] + w[3] / 2) - (y + h / 2)) < hh * 0.6 and 0 <= w[0] - (x + ww) < hh * 1.2 \
                    and not OCR_TIME.search(t) and len(t) < 11:
                nx, ny = min(x, w[0]), min(y, w[1])
                out[-1] = (nx, ny, max(x + ww, w[0] + w[2]) - nx, max(y + h, w[1] + w[3]) - ny, t + w[4])
                continue
        out.append(w)
    return out

def parse_cards(words):
    """Карточки «подочередь + интервалы отключений». Возвращает (дата|None, {очередь: [(start,end)…]})."""
    date = None
    for *_, t in words:
        m = re.search(r"(\d{2})\.(\d{2})\.(20\d{2})", t)
        if m: date = f"{m.group(3)}-{m.group(2)}-{m.group(1)}"; break
    times, labels = [], []
    for x, y, w, h, t in words:
        m = OCR_TIME.search(t)
        if m:
            a, b = int(m.group(1)) * 60 + int(m.group(2)), int(m.group(3)) * 60 + int(m.group(4))
            times.append((x, y, h, a, b))
        elif re.fullmatch(r"\d{1,2}[.,]?\d?", t) and not re.search(r"\d{2}\.\d{2}", t):
            labels.append((x, y, w, h, t))
    if not labels or not times:
        raise ValueError("нет меток или интервалов")
    th = sorted(h for *_, h, a, b in times)[len(times) // 2]
    labels = [l for l in labels if l[3] >= th * 1.05]
    if len(labels) != 12:
        raise ValueError(f"меток {len(labels)}")
    # порядок карточек: по строкам (близкие y), затем по x
    labels.sort(key=lambda l: l[1])
    rows, cur = [], [labels[0]]
    for l in labels[1:]:
        if abs(l[1] - cur[-1][1]) < l[3]: cur.append(l)
        else: rows.append(cur); cur = [l]
    rows.append(cur)
    order = [l for r in rows for l in sorted(r, key=lambda l: l[0])]
    agree = 0
    for q, l in zip(OCR_QUEUES, order):
        d = re.sub(r"\D", "", l[4])
        if len(d) == 2 and d[0] in "123456" and d[1] in "12":
            if d != q.replace(".", ""): raise ValueError(f"метка {l[4]} на месте {q}")
            agree += 1
    if agree < 8:
        raise ValueError(f"распознано меток {agree}/12")
    res = {q: [] for q in OCR_QUEUES}
    for x, y, h, a, b in times:
        cands = [(i, l) for i, l in enumerate(order) if l[0] <= x + h and l[1] <= y + h]
        if not cands: raise ValueError("интервал без метки")
        colx = max(l[0] for _, l in cands if True)
        # ближайшая по столбцу (наибольший x не правее), затем ближайшая сверху
        same = [(i, l) for i, l in cands if l[0] >= colx - 3 * h]
        i, _ = max(same, key=lambda il: (il[1][1], il[1][0]))
        if b == 0: b = 1440
        if not (0 <= a < b <= 1440) or a % 30 or b % 30: raise ValueError(f"время {a}-{b}")
        res[OCR_QUEUES[i]].append((a, b))
    return date, res


def color_boxes(img, pred, min_frac=0.35):
    """Прямоугольные карточки цвета pred: интервалы по x и y, где таких пикселей много → сетка ячеек."""
    im = img.convert("RGB")
    w, h = im.size
    px = im.load()
    step = 2
    cols = [sum(1 for y in range(0, h, step) if pred(px[x, y])) for x in range(w)]
    rows = [sum(1 for x in range(0, w, step) if pred(px[x, y])) for y in range(h)]
    def spans(v, n):
        out, s = [], None
        thr = max(v) * min_frac
        for i in range(len(v) + 1):
            on = i < len(v) and v[i] >= thr
            if on and s is None: s = i
            if not on and s is not None:
                if i - s > 20: out.append((s, i))
                s = None
        return out
    return spans(cols, w), spans(rows, h)


def parse_grid_boxes(words, xs, ys, queues=OCR_QUEUES):
    """Интервалы по карточкам сетки xs × ys (по строкам слева направо) → {очередь: [(a,b)]}."""
    if len(xs) * len(ys) != len(queues):
        raise ValueError(f"карточек {len(xs)}x{len(ys)}")
    res = {q: [] for q in queues}
    for x, y, w, h, t in words:
        m = OCR_TIME.search(t)
        if not m: continue
        a, b = int(m.group(1)) * 60 + int(m.group(2)), int(m.group(3)) * 60 + int(m.group(4))
        if b == 0: b = 1440
        if not (0 <= a < b <= 1440) or a % 30 or b % 30: raise ValueError(f"время {t}")
        cx, cy = x + w / 2, y + h / 2
        ci = [i for i, (s, e) in enumerate(xs) if s <= cx <= e]
        ri = [i for i, (s, e) in enumerate(ys) if s <= cy <= e]
        if not ci or not ri: raise ValueError(f"интервал {t} вне карточек")
        res[queues[ri[0] * len(xs) + ci[0]]].append((a, b))
    return res


def ocr_available():
    import shutil
    try:
        import PIL  # noqa: F401
    except ImportError:
        return False
    return shutil.which("tesseract") is not None


def ocr_schedule(img_bytes, layout):
    """Распознаёт картинку графика несколькими способами и берёт результат, только если он
    совпал минимум в 3 вариантах и встречается вдвое чаще любого другого. Иначе — None."""
    import io
    from collections import Counter
    from PIL import Image
    img = Image.open(io.BytesIO(img_bytes))
    votes, dates = Counter(), Counter()
    boxes = None
    if layout == "chernihiv":
        boxes = color_boxes(img, lambda p: p[0] > 220 and 150 < p[1] < 215 and p[2] < 180)
    for thr in range(80, 200, 20):
        for width in (1600, 2400):
            try:
                words = ocr_words(img, thr, width=width)
                if boxes:
                    res = parse_grid_boxes(words, *boxes)
                    date = next((f"{m.group(3)}-{m.group(2)}-{m.group(1)}" for *_, t in words
                                 for m in [re.search(r"(\d{2})\.(\d{2})\.(20\d{2})", t)] if m), None)
                else:
                    date, res = parse_cards(words)
            except Exception:
                continue
            votes[json.dumps(res, sort_keys=True)] += 1
            if date:
                dates[date] += 1
    top = votes.most_common(2)
    if not top or top[0][1] < 3 or (len(top) > 1 and top[0][1] < 2 * top[1][1]):
        return None
    res = json.loads(top[0][0])
    return {"date": dates.most_common(1)[0][0] if dates else None,
            "queues": {q: ranges_day(v) for q, v in res.items()}}


def telegram_photos(channel, hours=36):
    """Посты канала с картинками: [(время, текст, [ссылки на картинки])] за последние [hours] часов."""
    page = fetch(f"https://t.me/s/{channel}")
    since = dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=hours)
    out = []
    for block in re.findall(r'<div class="tgme_widget_message_wrap.*?</time>', page, re.S):
        when = re.search(r'datetime="([^"]+)"', block)
        urls = re.findall(r"tgme_widget_message_photo_wrap[^>]*background-image:url\('([^']+)'\)", block)
        if not when or not urls:
            continue
        w = dt.datetime.fromisoformat(when.group(1))
        if w < since:
            continue
        t = re.search(r'<div class="tgme_widget_message_text js-message_text"[^>]*>(.*?)</div>', block, re.S)
        text = re.sub(r"<[^>]+>", "", html.unescape(re.sub(r"<br\s*/?>", "\n", t.group(1)))) if t else ""
        out.append((w, text, urls))
    return sorted(out, key=lambda p: p[0])


def photo_region(channels, own, source, layout):
    """Область, где облэнерго публикует график картинкой в канале: распознаём (OCR) с проверкой.
    Без tesseract/Pillow или если распознать надёжно не удалось — остаётся только режим."""
    def run():
        r = mode_only(channels, own, source)
        if not ocr_available():
            return r
        cache = _cache("ocr.json")
        days = {}
        for ch in channels:
            for when, text, urls in telegram_photos(ch):
                if text and not GPV_POST.search(text):
                    continue
                for u in urls:
                    if u not in cache:
                        try:
                            cache[u] = ocr_schedule(fetch_bytes(u), layout)
                        except Exception as e:
                            log(f"  OCR {u[-30:]}: {e}")
                            cache[u] = None
                        _cache_save("ocr.json", dict(list(cache.items())[-200:]))
                    got = cache[u]
                    if not got:
                        continue
                    date = got["date"] or (post_schedules_date(text, when.astimezone(KYIV)) or [when.astimezone(KYIV).date()])[0]
                    date = date if isinstance(date, str) else date.isoformat()
                    apply_update(days, date, got["queues"], when)
        if days:
            r.pop("modeOnly", None)
            r["days"] = days
        return r
    return run


# ------------------------------------------------------------------ Фан-источники (когда облэнерго недоступно)

VOLT_RANGE = re.compile(r"(\d{1,2}):(\d{2})\s*[–-]\s*(\d{1,2}):(\d{2})")


def volt(slug):
    """volt-outage.com — сайт-агрегатор энтузиастов (тот же, что Telegram-бот @VoltOutageBot и каналы svitlo_*_ua).
    Сам берёт графики с сайтов облэнерго. Читаем открытую страницу области (не /api/, robots.txt это разрешает)."""
    page = fetch(f"https://volt-outage.com/{slug}")
    page = re.sub(r"<script.*?</script>|<style.*?</style>", "", page, flags=re.S)
    text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", page)))
    now = dt.datetime.now(KYIV)
    days = {}
    for m in re.finditer(r"Черга ([\d.]+), (сьогодні|завтра),? (\d{1,2} \w+): (.*?)(?= Черга [\d.]+, |$)", text):
        q, rel, date_s, desc = m.groups()
        date = find_date(date_s, now.year) or (now.date() + dt.timedelta(days=1 if rel == "завтра" else 0))
        desc = desc[:400]
        day = empty_day()
        off = re.search(r"світла немає (.*?)(?:;|, разом|$)", desc)
        maybe = re.search(r"можливе відключення (.*?)(?:;|, разом| \d+ год|$)", desc)
        if not off and not maybe and "не заплановано" not in desc:
            continue
        for part, val in ((maybe, "2"), (off, "0")):
            for a, am, b, bm in VOLT_RANGE.findall(part.group(1) if part else ""):
                mark(day, int(a) * 60 + int(am), int(b) * 60 + int(bm) or 1440, val)
        days.setdefault(date.isoformat(), {})[q if "." in q else q] = "".join(day)
    return {d: qs for d, qs in days.items() if len(qs) >= 6}


def volt_region(slug, own, source_channels=()):
    """Область, где официальный график недоступен: режим — по каналам облэнерго/Укренерго, часы — volt-outage.com."""
    def run():
        r = mode_only(list(source_channels), own, f"https://volt-outage.com/{slug}")
        days = volt(slug)
        if days:
            r.pop("modeOnly", None)
            r["days"] = days
        return r
    return run


def with_fallback_days(fn, fallback):
    """Если основной источник не дал график на какую-то дату, берём его из запасного (фан-источника)."""
    def run():
        r = fn()
        try:
            extra = fallback()
        except Exception as e:
            log(f"  запасной источник: {e}")
            return r
        today = dt.datetime.now(KYIV).date().isoformat()
        added = [d for d in extra if d >= today and d not in (r.get("days") or {})]
        for d in added:
            r.setdefault("days", {})[d] = extra[d]
        if added:
            r.pop("modeOnly", None)
            log(f"  из запасного источника: {', '.join(added)}")
        return r
    return run


_UKRENERGO = {}


def ukrenergo_posts():
    """Канал НЕК «Укренерго»: аварийные отключения по областям и общие объявления (раз за запуск)."""
    if "posts" not in _UKRENERGO:
        try:
            _UKRENERGO["posts"] = telegram_posts("Ukrenergo", hours=30, max_pages=3)
        except Exception as e:
            log(f"  Укренерго: {e}")
            _UKRENERGO["posts"] = []
    return _UKRENERGO["posts"]


GPV_ON = re.compile(r"(будуть|буде|діятим\w*|діють|діє|застосовуватим\w*|застосован\w*|застосовуються|запроваджен\w*|внесено зміни|орієнтовний графік заживлення)"
                    r"[^.]{0,120}?(гпв|графік\w*\s+погодинн|графік\w*\s+заживлення)"
                    r"|(гпв|графік\w*\s+погодинних\s+(відключень|вимкнень))[^.]{0,40}?(будуть\s+)?(діяти|діятим\w*|застосовуватим\w*)"
                    r"|орієнтовний графік заживлення|оновлен\w*\s+графік\w*\s+погодинн", re.I)
GPV_OFF = re.compile(r"(гпв|графік\w*\s+погодинн\w*\s+\w+)[^.]{0,60}?(не\s+(будуть|буде)\s+застосов\w*|не\s+застосовуватим\w*|не\s+діятим\w*|не\s+плануються|скасован\w*)"
                     r"|не\s+(застосовуватим\w*|плануються|прогнозуються)\s+(\S+\s+){0,2}?(гпв|графік\w*\s+погодинн)|без\s+(графіків|відключень)\s+(погодинн|світла)", re.I)


def schedule_hint(posts, now=None):
    """«schedule» / «none» по последнему объявлению облэнерго о ГПВ на сегодня (None — объявлений нет)."""
    now = now or dt.datetime.now(KYIV)
    today, hint = now.date(), None
    for when, text in posts:
        local = when.astimezone(KYIV)
        dates = [d for d in (post_schedules_date(text, local)) if d]
        date = dates[0] if dates else (local.date() + dt.timedelta(days=1) if re.search(r"\bзавтра\b", text, re.I) else local.date())
        if date != today:
            continue
        if GPV_OFF.search(text):
            hint = "none"
        elif GPV_ON.search(text):
            hint = "schedule"
    return hint


def post_schedules_date(text, local):
    out = []
    for m in DATE_ANY.finditer(text[:300]):
        try:
            out.append(_date_of(m, local.year))
        except ValueError:
            pass
    return out


def mode_only(channels, own, source):
    """Область без графика по очередям: только режим (ГПВ / аварийные / без отключений).

    Источники: официальный канал облэнерго (если есть) и канал НЕК «Укренерго», который объявляет
    аварийные отключения по областям. Графики по очередям эти облэнерго публикуют картинкой.
    """
    posts = []
    for ch in channels:
        try:
            posts += telegram_posts(ch, hours=30, max_pages=3)
        except Exception as e:
            log(f"  канал {ch}: {e}")
    posts.sort(key=lambda p: p[0])
    ukr = ukrenergo_posts()
    att = telegram_attention(sorted(posts + ukr, key=lambda p: p[0]), own)
    hint = schedule_hint(posts)
    if hint is None:
        # Укренерго: «…в усіх регіонах діятимуть графіки погодинних відключень» — значит, и здесь.
        allr = [(w, t) for w, t in ukr if re.search(r"(в|у)\s+(усіх|всіх)\s+(регіонах|областях)", t, re.I)]
        hint = schedule_hint(allr)
    if not channels and not ukr:
        raise RuntimeError("нет источников")
    return {"days": {}, "attention": att, "source": source, "modeHint": hint, "modeOnly": True}


def with_ukrenergo(fn, own):
    """Сборщик облэнерго + аварийные отключения, объявленные НЕК «Укренерго» для этой области."""
    def run():
        r = fn()
        r.pop("posts", None)
        if not detect_mode(r.get("attention")):
            att = telegram_attention(ukrenergo_posts(), own)
            if att:
                r["attention"] = att
        return r
    return run


def chan(channels, own, source, attention_channels=()):
    def run():
        r = channel_schedule(channels, own, source)
        if attention_channels and not detect_mode(r.get("attention")):
            try:
                posts = sorted((p for ch in attention_channels for p in telegram_posts(ch)), key=lambda p: p[0])
                r["attention"] = telegram_attention(posts, own) or r.get("attention")
            except Exception as e:
                log(f"  канал {attention_channels}: {e}")
        return r
    return with_ukrenergo(run, own)


# ------------------------------------------------------------------ регионы

REGIONS = [
    # id области как на карте приложения, имя, функция-сборщик
    ("kyiv-city", "Київ", "ДТЕК Київські електромережі", lambda: dtek("dtek-kem.com.ua", "kyiv")),
    ("kyiv", "Київська область", "ДТЕК Київські регіональні електромережі", lambda: dtek("dtek-krem.com.ua", "kyiv-region")),
    ("dnipropetrovsk", "Дніпропетровська область", "ДТЕК Дніпровські електромережі", lambda: dtek("dtek-dnem.com.ua", "dnipro")),
    ("odessa", "Одеська область", "ДТЕК Одеські електромережі", with_channel_attention(lambda: dtek("dtek-oem.com.ua", "odesa"), ["DTEKOdeskiElektromerezhi"], "одес")),
    ("lviv", "Львівська область", "Львівобленерго", with_channel_attention(lviv, ["lvivoblenergo"], "львів")),
    ("zaporizhia", "Запорізька область", "Запоріжжяобленерго", with_fallback_days(lambda: telegram_gpv(["zoe_alarm", "Zaporizhzhyaoblenergo_news"], "https://www.zoe.com.ua/", "запорі"),
                                                                            lambda: channel_schedule(["zp_energy"], "запорі", "https://t.me/zp_energy")["days"])),
    # Графики по очередям: сайты и текстовые посты официальных каналов.
    ("zhytomyr", "Житомирська область", "Житомиробленерго", with_ukrenergo(with_channel_attention(zhytomyr, ["ztoe_ua"], "житомир"), "житомир")),
    ("mykolaiv", "Миколаївська область", "Миколаївобленерго", with_ukrenergo(with_channel_attention(mykolaiv, ["mk_energy_ua"], "миколаїв"), "миколаїв")),
    ("kirovohrad", "Кіровоградська область", "Кіровоградобленерго", chan(["SvitloKropyvnytskohoRaionu", "SvitloKropyvnytskyiMisto"], "кіровоград", "https://kiroe.com.ua/", ["kirovogradoblenergo"])),
    ("rivne", "Рівненська область", "Рівнеобленерго", chan(["rivneoblenergo"], "рівнен", "https://www.roe.vsei.ua/disconnections")),
    ("khmelnytskyi", "Хмельницька область", "Хмельницькобленерго", chan(["khmelnytskoblenergo"], "хмельниц", "https://hoe.com.ua/")),
    ("cherkasy", "Черкаська область", "Черкасиобленерго", chan(["pat_cherkasyoblenergo"], "черкас", "https://www.cherkasyoblenergo.com/")),
    ("kharkiv", "Харківська область", "Харківобленерго", chan(["kharkivenergy"], "харків", "https://oblenergo.kharkiv.ua/")),
    # Только режим: эти облэнерго публикуют график картинкой или сайт закрыт для зарубежных запросов.
    ("ivano-frankivsk", "Івано-Франківська область", "Прикарпаттяобленерго", photo_region(["prykarpattyaoblenergo_official"], "івано-франків", "https://www.oe.if.ua/", "cards")),
    ("ternopil", "Тернопільська область", "Тернопільобленерго", volt_region("ternopilska-oblast", "тернопіл", ["ternopiloblenerho"])),
    ("sumy", "Сумська область", "Сумиобленерго", with_fallback_days(photo_region(["SumyEnergo"], "сумськ", "https://www.soe.com.ua/consumers/shutdowns", "cards"), lambda: volt("sumska-oblast"))),
    ("chernihiv", "Чернігівська область", "Чернігівобленерго", photo_region(["chernigivoblenergo"], "чернігів", "https://www.energy.cn.ua/", "chernihiv")),
    ("chernivtsi", "Чернівецька область", "Чернівціобленерго", volt_region("chernivetska-oblast", "чернівец", ["oe_cv_ua"])),
    ("volyn", "Волинська область", "Волиньобленерго", inneti_region("api-voe-poweron.inneti.net", "volyn", "волин", "https://energy.volyn.ua/")),
    ("zakarpattia", "Закарпатська область", "Закарпаттяобленерго", inneti_region("api-outage-zakarpat-energy.inneti.net", "zakarpattia", "закарпат", "https://zakarpat.energy/")),
    ("vinnytsia", "Вінницька область", "Вінницяобленерго", volt_region("vinnytska-oblast", "вінниц")),
    ("poltava", "Полтавська область", "Полтаваобленерго", with_fallback_days(chan(["svitlo_poltava_ua"], "полтав", "https://t.me/svitlo_poltava_ua"), lambda: volt("poltavska-oblast"))),
    ("kherson", "Херсонська область", "Херсонобленерго", lambda: mode_only([], "херсон", "https://www.ksoe.com.ua/")),
    ("donetsk", "Донецька область", "ДТЕК Донецькі електромережі", volt_region("donetska-oblast", "донецьк")),
]


# Как часто (в секундах) обновлять области, где источник — только объявления в каналах.
SLOW = {**{rid: 290 for rid in ("kherson",)},
        **{rid: 170 for rid in ("ternopil", "chernivtsi", "vinnytsia", "poltava", "donetsk")},
        **{rid: 110 for rid in ("ivano-frankivsk", "sumy", "chernihiv", "volyn", "zakarpattia")}}


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
    only = set(filter(None, os.environ.get("ONLY", "").split(",")))
    for rid, name, operator, fn in REGIONS:
        if only and rid not in only:
            continue
        path = os.path.join(OUT, "v1", f"{rid}.json")
        # Каналы Telegram не дёргаем каждую минуту: области «только режим» обновляем раз в 5 минут.
        every = SLOW.get(rid)
        if every and rid in prev and prev[rid].get("ok") and prev[rid].get("checkedAt") and os.path.exists(path):
            age = (now - dt.datetime.fromisoformat(prev[rid]["checkedAt"])).total_seconds()
            if age < every:
                index.append(prev[rid])
                continue
        log(f"→ {rid}")
        try:
            r = fn()
            old = json.load(open(path)) if os.path.exists(path) else {}
            # История: прошлые дни берём из прошлого файла, чтобы считать статистику за неделю.
            # Сегодня/завтра тоже оставляем из прошлого файла, если источник их сейчас не отдаёт
            # (пост с графиком ушёл из ленты канала): более свежие данные источника всё равно заменят их.
            merged = dict(old.get("days") or {})
            merged.update(r["days"])
            r["days"] = {k: v for k, v in sorted(merged.items()) if k >= (today - dt.timedelta(days=8)).isoformat()}
            cur = {k: v for k, v in r["days"].items() if k >= today.isoformat()}
            mode = detect_mode(r.get("attention")) or ("schedule" if any(("0" in s) for d in cur.values() for s in d.values()) else ("none" if cur else (r.get("modeHint") or "unknown")))
            body = {"id": rid, "name": name, "operator": operator, "source": r["source"], "mode": mode,
                    "attention": r.get("attention"), "updatedAtSource": r.get("updatedAtSource"), "days": r["days"],
                    "week": r.get("week") or {}, "queueSchedules": bool(r["days"]) or not r.get("modeOnly")}
            h = hashlib.sha1(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:12]
            changed = old.get("hash") != h
            body.update({"hash": h, "changedAt": now.isoformat(timespec="seconds") if changed else old.get("changedAt"),
                         "checkedAt": now.isoformat(timespec="seconds"), "ok": True})
            json.dump(body, open(path, "w"), ensure_ascii=False, separators=(",", ":"))
            queues = sorted({q for d in r["days"].values() for q in d}, key=lambda q: tuple(int(x) for x in q.split(".")))
            index.append({"id": rid, "name": name, "operator": operator, "mode": mode, "ok": True, "hash": h,
                          "changedAt": body["changedAt"], "checkedAt": body["checkedAt"],
                          "avgOffMinutesToday": summarize(r["days"], today), "queues": queues,
                          "queueSchedules": body["queueSchedules"]})
            log(f"  ok: {mode}, дней {len(r['days'])}, очередей {len(queues)}, {'ИЗМЕНЕНО' if changed else 'без изменений'}")
        except Exception as e:
            log(f"  ошибка: {e}")
            p = prev.get(rid, {"id": rid, "name": name, "operator": operator, "mode": "unknown", "queues": []})
            p.update({"ok": False, "error": str(e)[:200]})
            index.append(p)
    json.dump({"generatedAt": now.isoformat(timespec="seconds"), "regions": index},
              open(idx_path, "w"), ensure_ascii=False, indent=1)
    import addresses
    addresses.update(OUT, now, log)


if __name__ == "__main__":
    main()
