"""Раз в неделю пересобирает карты домов из OpenStreetMap (© участники OSM, ODbL).

Для каждого города: граница (relation), дома, дороги, парки, вода и адресные точки
качаем из Overpass по клеткам, затем gen_city_bin.py собирает бинарный файл карты.
Результат: out/v1/maps/{id}.bin и out/v1/maps/index.json ({id: {"hash", "builtAt", ...}}).
Если хоть одна клетка не скачалась, карту не публикуем: лучше старая полная, чем новая с дырами.
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
    subprocess.run([sys.executable, os.path.join(HERE, "gen_city_bin.py"), tiles, target, os.path.join(HERE, f"{cid}.meta.json")], check=True)
    return target


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    idx_path = os.path.join(out_dir, "index.json")
    index = json.load(open(idx_path)) if os.path.exists(idx_path) else {}
    for cid, (rel, bbox, n) in CITIES.items():
        print(f"→ {cid}", flush=True)
        try:
            path = build(cid, rel, bbox, n, out_dir)
            h = hashlib.sha1(open(path, "rb").read()).hexdigest()[:12]
            index[cid] = {"hash": h, "builtAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "bytes": os.path.getsize(path)}
            print(f"  ok {h} {os.path.getsize(path)} байт", flush=True)
        except Exception as ex:
            print(f"  ошибка {cid}: {ex} — оставляем прошлую карту", flush=True)
    json.dump(index, open(idx_path, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
