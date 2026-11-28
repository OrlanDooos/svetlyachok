"""Сторож данных: если у какой-то области нет графика на сегодня, или данные давно не обновлялись,
или источник сломан — открывает issue в репозитории (GitHub сам пришлёт уведомление владельцу).
Когда проблема ушла — закрывает issue с комментарием.

Запускается из collector/loop.sh после каждого сбора и отдельно по расписанию (watchdog.yml),
чтобы заметить и остановку самого сборщика.
Использование: python3 collector/watchdog.py <папка с v1/>
"""
import datetime as dt
import json
import os
import sys
import urllib.request
from zoneinfo import ZoneInfo

KYIV = ZoneInfo("Europe/Kyiv")
LABEL = "watchdog"
STALE_MIN = 45        # данные старше — сборщик, похоже, стоит
BROKEN_MIN = 60       # источник отдаёт ошибку дольше — тревога


def problems(root, now):
    """{ключ: (заголовок, текст)} — что сейчас не так."""
    out = {}
    idx_path = os.path.join(root, "v1", "regions.json")
    if not os.path.exists(idx_path):
        return {"index": ("Нет файла regions.json", "Сборщик не опубликовал список областей.")}
    idx = json.load(open(idx_path))
    gen = dt.datetime.fromisoformat(idx["generatedAt"])
    age = (now - gen).total_seconds() / 60
    if age > STALE_MIN:
        out["stale"] = ("Данные не обновлялись %d мин" % age,
                        f"Последний сбор: {idx['generatedAt']}. Похоже, сборщик (collect.yml) не работает — "
                        "проверьте Actions и при необходимости запустите Collect schedules вручную.")
    today = now.date().isoformat()
    for r in idx["regions"]:
        rid, name = r["id"], r.get("name", r["id"])
        path = os.path.join(root, "v1", f"{rid}.json")
        body = json.load(open(path)) if os.path.exists(path) else {}
        if not r.get("ok"):
            since = body.get("checkedAt")
            mins = (now - dt.datetime.fromisoformat(since)).total_seconds() / 60 if since else 10**6
            if mins > BROKEN_MIN:
                out[f"broken:{rid}"] = (f"{name}: источник не отвечает",
                                        f"Ошибка: {r.get('error')}\nПоследний удачный сбор: {since or 'никогда'}.")
        # После 01:00 график на сегодня уже должен быть (его публикуют накануне вечером).
        # Области, где облэнерго публикует только режим (график картинкой), и дни без ГПВ — не ошибка.
        if now.hour >= 1 and today not in (body.get("days") or {}) and body.get("queueSchedules", True) \
                and body.get("mode") not in ("none", "emergency", "blackout"):
            out[f"today:{rid}"] = (f"{name}: нет графика на {today}",
                                   f"В приложении у этой области «нет графика». Источник: {body.get('source')}.\n"
                                   f"Есть дни: {', '.join(sorted(body.get('days') or {})) or 'нет'}.")
    return out


def gh(method, path, data=None):
    req = urllib.request.Request(f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}{path}",
                                 method=method, data=json.dumps(data).encode() if data is not None else None,
                                 headers={"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
                                          "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"null")


def sync(found):
    """Открывает issue на новые проблемы, закрывает решённые. Ключ проблемы хранится в теле issue."""
    open_issues = gh("GET", f"/issues?state=open&labels={LABEL}&per_page=100") or []
    by_key = {}
    for i in open_issues:
        for line in (i.get("body") or "").splitlines():
            if line.startswith("<!-- key: "):
                by_key[line[10:].split(" -->")[0]] = i
    for key, (title, text) in found.items():
        if key not in by_key:
            gh("POST", "/issues", {"title": f"⚠️ {title}", "labels": [LABEL],
                                   "body": f"{text}\n\nIssue закроется сам, когда проблема исчезнет.\n<!-- key: {key} -->"})
            print("открыт issue:", title)
    for key, i in by_key.items():
        if key not in found:
            gh("POST", f"/issues/{i['number']}/comments", {"body": "Проблема исчезла, данные снова в порядке."})
            gh("PATCH", f"/issues/{i['number']}", {"state": "closed", "state_reason": "completed"})
            print("закрыт issue:", i["title"])


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "out")
    found = problems(root, dt.datetime.now(KYIV))
    for k, (t, _) in found.items():
        print("ПРОБЛЕМА:", t)
    if os.environ.get("GITHUB_TOKEN") and os.environ.get("GITHUB_REPOSITORY"):
        sync(found)


if __name__ == "__main__":
    main()
