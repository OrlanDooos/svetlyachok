"""Адреса домов из Visicom Data API (© Візіком) для домов, у которых в OSM адреса нет.

ЗАПУСКАТЬ МАССОВО ТОЛЬКО ПОСЛЕ ПИСЬМЕННОГО СОГЛАСИЯ VISICOM на хранение адресов в файле карты.
Результат — кэш {osm way id: адрес или ""}; в публичный git его не кладём.

usage: VISICOM_KEY=... visicom_fill.py tiles_dir cache.json [--max N]
  tiles_dir — тайлы Overpass из build_maps.py (b_*.json, boundary.json)
  --max N   — не больше N запросов за запуск (лимит ключа в сутки); повторный запуск продолжает.
Логика та же, что в приложении (Visicom.kt): точка внутри дома → контур дома → ближайший адрес ≤ 12 м.
"""
import glob, json, os, sys, time, urllib.error, urllib.parse, urllib.request

API = "https://api.visicom.ua/data-api/5.0/uk/geocode.json"
requests_made = 0


def request(key, param, geometry, extra=""):
    global requests_made
    requests_made += 1
    url = f"{API}?categories=adr_address&{param}={urllib.parse.quote(geometry)}{extra}&limit=5&key={urllib.parse.quote(key)}"
    req = urllib.request.Request(url, headers={"User-Agent": "svetlyachok-maps"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {}
            if e.code in (401, 403, 429):
                raise  # ключ/лимит — останавливаемся, кэш уже сохранён
        except Exception:
            pass
        time.sleep(3 * (attempt + 1))
    raise RuntimeError("visicom: нет ответа")


def features(o):
    if o.get("type") == "Feature":
        return [o]
    return o.get("features") or []


def address(f, polygons_only):
    p = f.get("properties") or {}
    if "adr_address" not in (p.get("categories") or ""):
        return None
    gt = (f.get("geometry") or {}).get("type") or ""
    if polygons_only and gt and "Polygon" not in gt:
        return None
    name, street = (p.get("name") or "").strip(), (p.get("street") or "").strip()
    if not name or not street or not name[0].isdigit():
        return None
    t = (p.get("street_type") or "").strip()
    return (f"{t} {street}" if t else street) + ", " + name


def addresses(o, polygons_only):
    return list(dict.fromkeys(a for a in (address(f, polygons_only) for f in features(o)) if a))


def nearest(o):
    lst = sorted(((address(f, False), (f.get("properties") or {}).get("dist_meters")) for f in features(o)),
                 key=lambda x: x[1] if x[1] is not None else 1e9)
    lst = [(a, d) for a, d in lst if a and d is not None]
    if not lst or lst[0][1] > 12:
        return None
    a0, d0 = lst[0]
    second = next((d for a, d in lst if a != a0), None)
    if second is not None and second < max(2 * d0, d0 + 6):
        return None
    return a0


def inside_point(g):
    """Точка внутри контура: середина горизонтального сечения по средней широте."""
    ys = sorted(p["lat"] for p in g)
    y = (ys[0] + ys[-1]) / 2
    xs = []
    for a, b in zip(g, g[1:] + g[:1]):
        if (a["lat"] > y) != (b["lat"] > y):
            xs.append(a["lon"] + (y - a["lat"]) * (b["lon"] - a["lon"]) / (b["lat"] - a["lat"]))
    xs.sort()
    if len(xs) >= 2:
        return (xs[0] + xs[1]) / 2, y
    return sum(p["lon"] for p in g) / len(g), sum(p["lat"] for p in g) / len(g)


def wkt(g):
    step = (len(g) + 248) // 249
    pts = [(p["lon"], p["lat"]) for i, p in enumerate(g) if i % step == 0]
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    return "POLYGON((" + ",".join(f"{x:.7f} {y:.7f}" for x, y in pts) + "))"


def lookup(key, g):
    x, y = inside_point(g)
    ck = f"{x:.6f},{y:.6f}"
    by_point = addresses(request(key, "intersect", ck), True)
    if len(by_point) == 1:
        return by_point[0]
    if len(by_point) > 1:
        return ""
    by_shape = addresses(request(key, "intersect", wkt(g)), False)
    if len(by_shape) == 1:
        return by_shape[0]
    return nearest(request(key, "near", ck, "&r=25")) or ""


def main():
    tiles, cache_path = sys.argv[1], sys.argv[2]
    limit = int(sys.argv[sys.argv.index("--max") + 1]) if "--max" in sys.argv else 10**9
    key = os.environ["VISICOM_KEY"]
    cache = json.load(open(cache_path)) if os.path.exists(cache_path) else {}
    todo = {}
    for f in glob.glob(os.path.join(tiles, "b_*.json")):
        for e in json.load(open(f)).get("elements", []):
            t = e.get("tags") or {}
            if e["type"] == "way" and "geometry" in e and not t.get("addr:housenumber") and str(e["id"]) not in cache:
                todo[e["id"]] = e["geometry"][:-1] if e["geometry"][0] == e["geometry"][-1] else e["geometry"]
    print(f"без адреса и ещё не спрошено: {len(todo)}", flush=True)
    found = 0
    try:
        for i, (wid, g) in enumerate(todo.items()):
            if requests_made + 3 > limit or len(g) < 3:
                break
            a = lookup(key, g)
            cache[str(wid)] = a
            found += bool(a)
            if i % 200 == 0:
                json.dump(cache, open(cache_path, "w"), ensure_ascii=False)
                print(f"  {i}: запросов {requests_made}, найдено {found}", flush=True)
    finally:
        json.dump(cache, open(cache_path, "w"), ensure_ascii=False)
        print(f"итого: запросов {requests_made}, найдено адресов {found}, в кэше {len(cache)}", flush=True)


if __name__ == "__main__":
    main()
