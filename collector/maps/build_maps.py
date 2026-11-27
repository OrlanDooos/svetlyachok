"""Раз в неделю пересобирает карты домов из OpenStreetMap (© участники OSM, ODbL).

Для каждого города: граница (relation), дома, дороги, парки, вода и адресные точки
качаем из Overpass по клеткам, затем gen_city_bin.py собирает бинарный файл карты.
Результат: out/v1/maps/{id}.bin и out/v1/maps/index.json ({id: {"hash", "builtAt", ...}}).
Если хоть одна клетка не скачалась, карту не публикуем: лучше старая полная, чем новая с дырами.

Своя база адресов (server/pins, /addr/v1): если есть доступ к серверу (OIDC-токен GitHub Actions или ADMIN_KEY),
собираем вторую карту — с адресами из базы (© Візіком) — и кладём её только на сервер (/maps/v1),
а дома без адреса отправляем в очередь, которую сервер спрашивает у Visicom. В публичный git эти адреса не попадают.
"""
import hashlib, json, os, subprocess, sys, time, urllib.parse, urllib.request, datetime as dt

HERE = os.path.dirname(os.path.abspath(__file__))
SERVERS = ["https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
           "https://overpass.kumi.systems/api/interpreter"]
CITIES = {
    # id: (relation границы, bbox (юг, запад, север, восток), клеток по стороне)
    "zaporizhzhia": (12152861, (47.75, 34.98, 47.96, 35.375), 5),
}
ROADS = 'way[highway~"^(primary|secondary|tertiary|residential|trunk|unclassified|pedestrian|living_street)$"]({bb});' \
        'way[leisure=park]({bb});way[natural=water]({bb});way[waterway=riverbank]({bb});'


def overpass(query):
    last = None
    for attempt in range(6):
        url = SERVERS[attempt % len(SERVERS)]
        try:
            data = urllib.parse.urlencode({"data": f"[out:json][timeout:180];{query}"}).encode()
            req = urllib.request.Request(url, data=data, headers={"User-Agent": "svetlyachok-maps (github.com/OrlanDooos/svetlyachok)"})
            with urllib.request.urlopen(req, timeout=240) as r:
                body = r.read()
            doc = json.loads(body)
            if "elements" not in doc:
                raise ValueError("no elements")
            if doc.get("remark") and "error" in doc["remark"].lower():
                raise ValueError(doc["remark"][:120])
            return body
        except Exception as e:
            last = e
            print(f"    {url}: {e}", flush=True)
            time.sleep(15 * (attempt + 1))
    raise RuntimeError(f"overpass failed: {last}")


def build(cid, rel, bbox, n, out_dir):
    tiles = os.path.join("/tmp", f"tiles_{cid}")
    os.makedirs(tiles, exist_ok=True)
    s, w, nn, e = bbox
    open(os.path.join(tiles, "boundary.json"), "wb").write(overpass(f"rel({rel});out geom;"))
    for i in range(n):
        for j in range(n):
            bb = f"{s + (nn - s) * i / n},{w + (e - w) * j / n},{s + (nn - s) * (i + 1) / n},{w + (e - w) * (j + 1) / n}"
            print(f"  клетка {i},{j}", flush=True)
            open(os.path.join(tiles, f"b_{i}_{j}.json"), "wb").write(overpass(f"(way[building]({bb}););out geom tags;"))
            open(os.path.join(tiles, f"r_{i}_{j}.json"), "wb").write(overpass("(" + ROADS.format(bb=bb) + ");out geom tags;"))
    # Большая вода (Днепр, водохранилища, острова внутри них) в OSM — мультиполигоны (relation), а не отдельные пути.
    # Качаем целиком по всему bbox: кольца собираем и обрезаем по bbox в gen_city_bin.py.
    open(os.path.join(tiles, "water.json"), "wb").write(overpass(
        f"(relation[natural=water]({s},{w},{nn},{e});relation[waterway=riverbank]({s},{w},{nn},{e}););out geom;"))
    open(os.path.join(tiles, "addr_nodes.json"), "wb").write(overpass(
        f'(node["addr:housenumber"]({s},{w},{nn},{e});relation["building"]["addr:housenumber"]({s},{w},{nn},{e}););out center tags;'))
    # Связки «улица → дома» (associatedStreet): номер дома стоит на здании, а улица — на отношении.
    open(os.path.join(tiles, "assoc.json"), "wb").write(overpass(f"area(id:{3600000000 + rel})->.a;relation[type=associatedStreet](area.a);out body;"))
    # Официальный список адресов облэнерго (его собирает collect.yml в ветку data): нужен для оценки номеров.
    region = json.load(open(os.path.join(HERE, f"{cid}.meta.json")))["region"]
    try:
        with urllib.request.urlopen(f"https://raw.githubusercontent.com/OrlanDooos/svetlyachok/data/v1/addr/{region}.json", timeout=60) as r:
            open(os.path.join(tiles, "zoe.json"), "wb").write(r.read())
    except Exception as ex:
        print(f"  нет списка адресов {region}: {ex} — номера не оцениваем", flush=True)
    # Дома, отмеченные жителями (сервер server/pins, подтверждение 2+ установками).
    pins_url = os.environ.get("PINS_URL", "").rstrip("/")
    if pins_url:
        try:
            with urllib.request.urlopen(f"{pins_url}/confirmed?region={region}", timeout=60) as r:
                open(os.path.join(tiles, "pins.json"), "wb").write(r.read())
        except Exception as ex:
            print(f"  нет отметок жителей: {ex}", flush=True)
    target = os.path.join(out_dir, f"{cid}.bin")
    vf = os.path.join(tiles, "visicom.json")
    if os.path.exists(vf):
        os.remove(vf)  # публичная карта — без адресов Visicom
    subprocess.run([sys.executable, os.path.join(HERE, "gen_city_bin.py"), tiles, target, os.path.join(HERE, f"{cid}.meta.json")], check=True)
    return target, tiles, region


def server_token():
    """OIDC-токен GitHub Actions (aud svetlyachok-data) или ADMIN_KEY; None — сервера нет, вторую карту не собираем."""
    url, tok = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL"), os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN")
    if url and tok:
        req = urllib.request.Request(f"{url}&audience=svetlyachok-data", headers={"Authorization": f"Bearer {tok}"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)["value"]
    return os.environ.get("ADMIN_KEY")


def server(method, path, token, body=None, ctype="application/json"):
    req = urllib.request.Request(os.environ.get("PINS_URL", "https://svetlyachok-pins.svetlyachok.workers.dev").rstrip("/") + path,
                                 data=body, method=method, headers={"Authorization": f"Bearer {token}", "Content-Type": ctype,
                                                                    "User-Agent": "svetlyachok-maps"})  # Python-urllib Cloudflare режет (1010)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def build_private(cid, tiles, region, token, out_dir):
    """Карта с адресами из своей базы: только на сервер. Плюс очередь домов без адреса."""
    open(os.path.join(tiles, "visicom.json"), "wb").write(server("GET", f"/addr/v1/{region}.json", token))
    target = os.path.join(out_dir, f"{cid}.bin")
    qf = os.path.join(tiles, "queue.json")
    subprocess.run([sys.executable, os.path.join(HERE, "gen_city_bin.py"), tiles, target, os.path.join(HERE, f"{cid}.meta.json")],
                   check=True, env={**os.environ, "QUEUE_OUT": qf})
    queue = json.load(open(qf))
    for i in range(0, len(queue), 2000):
        r = server("PUT", f"/addr/v1/{region}/queue", token, json.dumps({"items": queue[i:i + 2000]}).encode())
    if queue:
        print(f"  очередь адресов: {json.loads(r)}", flush=True)
    server("PUT", f"/maps/v1/{cid}.bin", token, open(target, "rb").read(), "application/octet-stream")
    return target


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    idx_path = os.path.join(out_dir, "index.json")
    index = json.load(open(idx_path)) if os.path.exists(idx_path) else {}
    token = server_token()
    private_dir = os.path.join("/tmp", "maps_private")
    os.makedirs(private_dir, exist_ok=True)
    private_index = {}
    if token:
        try:
            private_index = json.loads(server("GET", "/maps/v1/index.json", token))
        except Exception:
            pass
    stamp = lambda p: {"hash": hashlib.sha1(open(p, "rb").read()).hexdigest()[:12],
                       "builtAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "bytes": os.path.getsize(p)}
    for cid, (rel, bbox, n) in CITIES.items():
        print(f"→ {cid}", flush=True)
        try:
            path, tiles, region = build(cid, rel, bbox, n, out_dir)
            index[cid] = stamp(path)
            print(f"  ok {index[cid]['hash']} {index[cid]['bytes']} байт", flush=True)
        except Exception as ex:
            print(f"  ошибка {cid}: {ex} — оставляем прошлую карту", flush=True)
            continue
        if token:
            try:
                p = build_private(cid, tiles, region, token, private_dir)
                private_index[cid] = stamp(p)
                print(f"  на сервер: {private_index[cid]['hash']} {private_index[cid]['bytes']} байт", flush=True)
            except Exception as ex:
                print(f"  карта с адресами базы не собрана: {ex}", flush=True)
    json.dump(index, open(idx_path, "w"), ensure_ascii=False, indent=1)
    if token and private_index:
        server("PUT", "/maps/v1/index.json", token, json.dumps(private_index).encode())


if __name__ == "__main__":
    main()
