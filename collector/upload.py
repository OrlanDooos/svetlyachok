"""Кладёт свежие графики на сервер Cloudflare (server/pins, /data/v1/...), чтобы приложение видело их
через ~20 секунд, а не через 5–10 минут, как через кеш raw.githubusercontent/GitHub Pages.

Вход — OIDC-токен GitHub Actions (нужно permissions: id-token: write), секреты не нужны.
Использование: python3 collector/upload.py <папка с v1/>
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

BASE = os.environ.get("DATA_URL", "https://svetlyachok-pins.svetlyachok.workers.dev")
AUD = "svetlyachok-data"
# Бесплатный D1 — 100 тыс. записей в сутки. Файл области грузим, только если в нём что-то поменялось
# (кроме времени проверки) или прошло 15 минут; regions.json — каждый раз, по нему приложение видит свежесть.
RESEND_SEC = 15 * 60


def content_hash(body):
    return hashlib.sha1(re.sub(rb'"checkedAt":\s*"[^"]*"', b"", body)).hexdigest()


def oidc_token():
    url, tok = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL"), os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN")
    if not url or not tok:
        return None
    req = urllib.request.Request(f"{url}&audience={AUD}", headers={"Authorization": f"Bearer {tok}"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)["value"]


def main():
    root = os.path.join(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "out"), "v1")
    token = oidc_token()
    if not token:
        print("upload: нет OIDC-токена (запуск не в GitHub Actions) — пропускаем")
        return
    idx = json.load(open(os.path.join(root, "regions.json")))
    # Сначала файлы областей, потом список: приложение не увидит новый hash раньше самого файла.
    names = [f"{r['id']}.json" for r in idx["regions"] if os.path.exists(os.path.join(root, f"{r['id']}.json"))] + ["regions.json"]
    state_path = os.path.join(root, "..", ".uploaded.json")
    try:
        state = json.load(open(state_path))
    except Exception:
        state = {}
    now = time.time()
    for name in names:
        body = open(os.path.join(root, name), "rb").read()
        h = content_hash(body)
        prev = state.get(name) or {}
        if name != "regions.json" and prev.get("h") == h and now - prev.get("t", 0) < RESEND_SEC:
            continue
        req = urllib.request.Request(f"{BASE}/data/v1/{name}", data=body, method="PUT",
                                     headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                                              # Cloudflare отклоняет стандартный User-Agent Python-urllib (ошибка 1010).
                                              "User-Agent": "svetlyachok-collector"})
        try:
            urllib.request.urlopen(req, timeout=20).read()
            state[name] = {"h": h, "t": now}
        except Exception as e:
            print(f"upload {name}: {e}")
    json.dump(state, open(state_path, "w"))


if __name__ == "__main__":
    main()
