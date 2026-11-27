"""Собирает бинарную карту города (дома + дороги) из тайлов Overpass.
usage: gen_city_bin.py tiles_dir out.bin meta.json
Формат (big-endian, DataInputStream):
  'SVC2' | int metaLen | meta(utf8 json)
  int nStr | (ushort len, utf8)*      — таблица строк (адреса и названия улиц)
  int nB  | per building: int strIdx(-1), byte flags (1 — критическая инфраструктура, 2 — номер оценён интерполяцией, 4 — адрес отметили жители), ushort n, int x0, int y0 (дм), (short dx, short dy)*(n-1)
  int nL  | per line: byte kind, int strIdx, ushort n, int x0, int y0, (short dx, short dy)*(n-1)
"""
import json, sys, glob, math, struct, os
tiles, out, metaf = sys.argv[1:4]
meta = json.load(open(metaf))
B, L = {}, {}
for f in glob.glob(os.path.join(tiles, 'b_*.json')):
    for e in json.load(open(f)).get('elements', []):
        if e['type'] == 'way' and 'geometry' in e: B[e['id']] = e
for f in glob.glob(os.path.join(tiles, 'r_*.json')):
    try: els = json.load(open(f)).get('elements', [])
    except Exception: continue
    for e in els:
        if e['type'] == 'way' and 'geometry' in e: L[e['id']] = e
# Граница города: собираем внешние кольца из путей отношения.
bd = json.load(open(os.path.join(tiles, 'boundary.json')))['elements'][0]
segs = [[(p['lon'], p['lat']) for p in m['geometry']] for m in bd['members'] if m['type'] == 'way' and m.get('role') == 'outer' and 'geometry' in m]
rings = []
while segs:
    ring = segs.pop(0)
    changed = True
    while changed and ring[0] != ring[-1]:
        changed = False
        for i, s in enumerate(segs):
            if s[0] == ring[-1]: ring += s[1:]
            elif s[-1] == ring[-1]: ring += s[::-1][1:]
            elif s[-1] == ring[0]: ring = s[:-1] + ring
            elif s[0] == ring[0]: ring = s[::-1][:-1] + ring
            else: continue
            segs.pop(i); changed = True; break
    rings.append(ring)
def inside(x, y):
    c = False
    for r in rings:
        j = len(r) - 1
        for i in range(len(r)):
            xi, yi = r[i]; xj, yj = r[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: c = not c
            j = i
    return c
lat0 = sum(p[1] for r in rings for p in r) / sum(len(r) for r in rings)
lon0 = sum(p[0] for r in rings for p in r) / sum(len(r) for r in rings)
kx = 111320 * math.cos(math.radians(lat0)); ky = 110540
def dm(lon, lat): return int(round((lon - lon0) * kx * 10)), int(round((lat0 - lat) * ky * 10))
def simplify(pts, tol=8):  # Дуглас-Пекер, допуск в дм
    if len(pts) > 2 and pts[0] == pts[-1]:
        # Замкнутый контур: делим на две половины по самой дальней точке, чтобы не выродился.
        pts = pts[:-1]
        far = max(range(len(pts)), key=lambda i: (pts[i][0]-pts[0][0])**2 + (pts[i][1]-pts[0][1])**2)
        a = simplify(pts[:far+1], tol); b = simplify(pts[far:] + [pts[0]], tol)
        return a[:-1] + b[:-1]
    if len(pts) < 4: return pts
    def rec(a, b):
        (x1, y1), (x2, y2) = pts[a], pts[b]
        dx, dy = x2 - x1, y2 - y1; L2 = dx*dx + dy*dy or 1
        best, bi = -1, -1
        for i in range(a+1, b):
            x, y = pts[i]
            d = abs(dy*x - dx*y + x2*y1 - y2*x1) / math.sqrt(L2)
            if d > best: best, bi = d, i
        if best > tol: return rec(a, bi)[:-1] + rec(bi, b)
        return [pts[a], pts[b]]
    return rec(0, len(pts)-1)
strings, sidx = [], {}
def S(s):
    if s is None: return -1
    if s not in sidx: sidx[s] = len(strings); strings.append(s)
    return sidx[s]
crit = {'hospital','clinic','kindergarten','school','fire_station','government','military'}
def fix_addr(st):
    for a, b in (('проспект Соборний', 'Соборний проспект'),):
        st = st.replace(a, b)
    return st
# Адресные точки OSM (адрес стоит на точке внутри здания, а не на самом контуре).
pts_addr = []
af = os.path.join(tiles, 'addr_nodes.json')
if os.path.exists(af):
    for e in json.load(open(af)).get('elements', []):
        tg = e.get('tags', {})
        if 'addr:street' not in tg or 'addr:housenumber' not in tg: continue
        lat = e.get('lat', e.get('center', {}).get('lat')); lon = e.get('lon', e.get('center', {}).get('lon'))
        if lat is None: continue
        pts_addr.append((lon, lat, fix_addr(tg['addr:street']) + ', ' + tg['addr:housenumber']))
grid = {}
for x, y, s in pts_addr: grid.setdefault((int(x * 500), int(y * 500)), []).append((x, y, s))
def poly_has(g, x, y):
    c = False; j = len(g) - 1
    for i in range(len(g)):
        xi, yi = g[i]['lon'], g[i]['lat']; xj, yj = g[j]['lon'], g[j]['lat']
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: c = not c
        j = i
    return c
def addr_inside(g):
    """Адрес по точкам внутри здания: только если все точки внутри дают один и тот же адрес."""
    x0 = min(p['lon'] for p in g); x1 = max(p['lon'] for p in g); y0 = min(p['lat'] for p in g); y1 = max(p['lat'] for p in g)
    found = set()
    for gx in range(int(x0 * 500), int(x1 * 500) + 1):
        for gy in range(int(y0 * 500), int(y1 * 500) + 1):
            for x, y, s in grid.get((gx, gy), ()):
                if x0 <= x <= x1 and y0 <= y <= y1 and poly_has(g, x, y): found.add(s)
    return found.pop() if len(found) == 1 else None
# associatedStreet: дом-участник с номером, но без addr:street берёт улицу из названия отношения.
assoc_street = {}
asf = os.path.join(tiles, 'assoc.json')
if os.path.exists(asf):
    for r in json.load(open(asf)).get('elements', []):
        name = r.get('tags', {}).get('name')
        if not name: continue
        for m in r.get('members', []):
            if m['type'] == 'way' and m.get('role') in ('house', 'building'):
                # Дом в двух связках с разными улицами — неоднозначно, не берём.
                assoc_street[m['ref']] = name if assoc_street.get(m['ref'], name) == name else None
from_points = from_assoc = 0
blds = []
for e in B.values():
    g = e['geometry']; cx = sum(p['lon'] for p in g)/len(g); cy = sum(p['lat'] for p in g)/len(g)
    if not inside(cx, cy): continue
    t = e.get('tags', {})
    st, hn = t.get('addr:street'), t.get('addr:housenumber')
    if hn and not st and assoc_street.get(e['id']):
        st = assoc_street[e['id']]; from_assoc += 1
    pts = simplify([dm(p['lon'], p['lat']) for p in g])
    if len(pts) < 3: continue
    addr = fix_addr(st) + ', ' + hn if st and hn else None
    if addr is None and grid:
        addr = addr_inside(g)
        if addr: from_points += 1
    blds.append((S(addr), 1 if (t.get('building') in crit or t.get('amenity') in crit) else 0, pts, e))
# Отметки жителей (подтверждённые 2+ установками): адрес дома, в котором стоит точка.
# Пустой адрес заполняем всегда, адрес из OSM меняем, только если отметок 3 и больше.
crowd = 0
pf = os.path.join(tiles, 'pins.json')
if os.path.exists(pf):
    try: houses = json.load(open(pf)).get('houses', [])
    except Exception: houses = []
    bgrid = {}
    for i, (si, fl, pts, e) in enumerate(blds):
        g = e['geometry']
        for gx in range(int(min(p['lon'] for p in g) * 500), int(max(p['lon'] for p in g) * 500) + 1):
            for gy in range(int(min(p['lat'] for p in g) * 500), int(max(p['lat'] for p in g) * 500) + 1):
                bgrid.setdefault((gx, gy), []).append(i)
    claimed = {}
    for h in houses:
        hit = [i for i in bgrid.get((int(h['lon'] * 500), int(h['lat'] * 500)), []) if poly_has(blds[i][3]['geometry'], h['lon'], h['lat'])]
        if len(hit) != 1: continue
        i = hit[0]
        if i in claimed and claimed[i][1] >= h['n']:
            if claimed[i][1] == h['n']: claimed[i] = (None, h['n'])  # два адреса поровну на один дом — неоднозначно
            continue
        claimed[i] = (h['address'], h['n'])
    for i, (a, n) in claimed.items():
        si, fl, pts, e = blds[i]
        if a is None or (si >= 0 and n < 3): continue
        blds[i] = (S(a), fl | 4, pts, e); crowd += 1

# Оценка номеров домов без адреса по соседям на той же улице (collector/maps/interpolate.py).
# Только если есть официальный список адресов: номер должен в нём быть. В файле — флаг 2.
interp = {}
zf = os.path.join(tiles, 'zoe.json')
if os.path.exists(zf):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from interpolate import interpolate
    z = json.load(open(zf))
    zoe = {}
    for k, v in z.get('streets', {}).items():
        zoe[k] = {h for hs in v['q'].values() for h in hs.split('|')}
    for k, v in z.get('aliases', {}).items():
        zoe.setdefault(k, set()).update(zoe.get(v, set()))
    def mpt(lon, lat): return ((lon - lon0) * kx, (lat0 - lat) * ky)
    cand = []
    for i, (si, fl, pts, e) in enumerate(blds):
        t = dict(e.get('tags', {}))
        if si >= 0 and 'addr:street' not in t:  # адрес из связки или точки внутри — тоже опора
            st_, hn_ = strings[si].rsplit(', ', 1); t['addr:street'] = st_; t['addr:housenumber'] = hn_
        g = [mpt(p['lon'], p['lat']) for p in e['geometry']]
        area = abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(g, g[1:] + g[:1]))) / 2
        cand.append((i, (sum(x for x, _ in g) / len(g), sum(y for _, y in g) / len(g)), area, t if si >= 0 or 'addr:housenumber' in t else {k: v for k, v in t.items() if not k.startswith('addr:')}))
    roads = [(e['tags']['name'], [mpt(p['lon'], p['lat']) for p in e['geometry']]) for e in L.values()
             if e.get('tags', {}).get('highway') and e['tags'].get('name')]
    interp = interpolate(cand, roads, zoe)
    for i, (street, n) in interp.items():
        si, fl, pts, e = blds[i]
        blds[i] = (S(fix_addr(street) + ', ' + n), fl | 2, pts, e)
blds = [(si, fl, pts) for si, fl, pts, e in blds]
lines = []
KIND = {'trunk':0,'primary':0,'secondary':1,'tertiary':2,'residential':3,'unclassified':3,'living_street':3,'pedestrian':4,'park':5,'water':6}
for e in L.values():
    t = e.get('tags', {})
    k = t.get('highway') or ('park' if t.get('leisure') == 'park' else 'water')
    if k not in KIND: continue
    g = e['geometry']
    if not any(inside(p['lon'], p['lat']) for p in g[::max(1, len(g)//4)]): continue
    pts = simplify([dm(p['lon'], p['lat']) for p in g], 15)
    if len(pts) >= 2: lines.append((KIND[k], S(t.get('name')), pts))
meta.update({'origin': [lat0, lon0], 'buildings': len(blds), 'lines': len(lines)})
with open(out, 'wb') as f:
    m = json.dumps(meta, ensure_ascii=False).encode()
    f.write(b'SVC2'); f.write(struct.pack('>i', len(m))); f.write(m)
    f.write(struct.pack('>i', len(strings)))
    for s in strings:
        b = s.encode(); f.write(struct.pack('>H', len(b))); f.write(b)
    def pts_out(pts):
        f.write(struct.pack('>H', len(pts))); f.write(struct.pack('>ii', *pts[0]))
        for (a, b), (c, d) in zip(pts, pts[1:]):
            f.write(struct.pack('>hh', max(-32768, min(32767, c - a)), max(-32768, min(32767, d - b))))
    f.write(struct.pack('>i', len(blds)))
    for si, fl, pts in blds:
        f.write(struct.pack('>ib', si, fl)); pts_out(pts)
    f.write(struct.pack('>i', len(lines)))
    for k, si, pts in lines:
        f.write(struct.pack('>bi', k, si)); pts_out(pts)
print(from_points, 'addresses from points;', from_assoc, 'from associatedStreet;', len(interp), 'interpolated;', crowd, 'from residents;', len(blds), 'buildings', sum(1 for b in blds if b[0] >= 0), 'with address', len(lines), 'lines', len(rings), 'rings', os.path.getsize(out), 'bytes')
