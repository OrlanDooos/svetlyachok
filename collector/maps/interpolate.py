"""Оценка номеров домов без адреса: интерполяция между соседями с адресами на той же стороне улицы.

Улицы — линии OSM с name, дома-опоры — здания с addr:street + addr:housenumber.
Для каждой улицы (все её линии склеены в цепочки) считаем положение дома вдоль улицы (t, метры)
и сторону (слева/справа). Между двумя опорами одной стороны с номерами одной чётности:
  * если домов-кандидатов ровно столько, сколько свободных номеров, — раздаём номера по порядку;
  * иначе — линейная интерполяция по t с округлением до нужной чётности (без повторов).
Если улица есть в официальном списке облэнерго, оставляем только номера, которые в этом списке есть.
Результат всегда помечается как приблизительный («≈»): это оценка, а не адрес из источника.
"""
import math, re, os, sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from addresses import street_keys  # noqa: E402

RESIDENTIAL = {'house', 'detached', 'residential', 'apartments', 'semidetached_house', 'terrace', 'bungalow', 'yes'}
MAX_ANCHOR_DIST = 80.0   # м: опора дальше от своей улицы — не используем
MAX_CAND_DIST = 40.0     # м: дом дальше от улицы (во дворе) — не нумеруем
MIN_AREA = 35.0          # м²: сараи и гаражи меньше
MAX_GAP = 40             # номеров между опорами: больше — слишком неуверенно


def _num(h):
    m = re.match(r'^\s*(\d+)\s*$', h or '')
    return int(m.group(1)) if m else None


def _lead(h):
    m = re.match(r'^\s*(\d+)', h or '')
    return int(m.group(1)) if m else None


def _chains(lines):
    """Склеивает линии одной улицы в цепочки по общим концам."""
    segs = [list(l) for l in lines if len(l) >= 2]
    out = []
    while segs:
        ch = segs.pop(0)
        changed = True
        while changed:
            changed = False
            for i, s in enumerate(segs):
                if s[0] == ch[-1]: ch += s[1:]
                elif s[-1] == ch[-1]: ch += s[::-1][1:]
                elif s[-1] == ch[0]: ch = s[:-1] + ch
                elif s[0] == ch[0]: ch = s[::-1][:-1] + ch
                else: continue
                segs.pop(i); changed = True; break
        out.append(ch)
    return out


class _Streets:
    def __init__(self, roads):
        by_key = defaultdict(list)
        self.names = {}
        for name, pts in roads:
            ks = street_keys(name)
            if not ks: continue
            by_key[ks[0]].append(pts)
            self.names.setdefault(ks[0], name)
        self.chains = []           # (key, [(x,y)], cum)
        self.grid = defaultdict(list)
        for k, ls in by_key.items():
            for ch in _chains(ls):
                cum = [0.0]
                for a, b in zip(ch, ch[1:]): cum.append(cum[-1] + math.dist(a, b))
                ci = len(self.chains); self.chains.append((k, ch, cum))
                for si, (a, b) in enumerate(zip(ch, ch[1:])):
                    for gx in range(int(min(a[0], b[0]) // 50) - 1, int(max(a[0], b[0]) // 50) + 2):
                        for gy in range(int(min(a[1], b[1]) // 50) - 1, int(max(a[1], b[1]) // 50) + 2):
                            self.grid[(gx, gy)].append((ci, si))

    def project(self, x, y, key=None, maxd=50.0):
        """Ближайшая точка улицы: (dist, chain, t, side). key — только эта улица."""
        best = None
        seen = set()
        r = int(maxd // 50) + 1
        for gx in range(int(x // 50) - r, int(x // 50) + r + 1):
            for gy in range(int(y // 50) - r, int(y // 50) + r + 1):
                for ci, si in self.grid.get((gx, gy), ()):
                    if (ci, si) in seen: continue
                    seen.add((ci, si))
                    k, ch, cum = self.chains[ci]
                    if key is not None and k != key: continue
                    (ax, ay), (bx, by) = ch[si], ch[si + 1]
                    dx, dy = bx - ax, by - ay
                    L2 = dx * dx + dy * dy
                    if L2 == 0: continue
                    u = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / L2))
                    px, py = ax + u * dx, ay + u * dy
                    d = math.hypot(x - px, y - py)
                    if d <= maxd and (best is None or d < best[0]):
                        side = 1 if dx * (y - ay) - dy * (x - ax) > 0 else -1
                        best = (d, ci, cum[si] + u * math.sqrt(L2), side)
        return best


def interpolate(buildings, roads, zoe=None, hold_out=None):
    """buildings: [(id, (cx, cy) м, area м², tags)], roads: [(name, [(x, y) м])].
    zoe: {ключ улицы: set(номера)} или None. hold_out: id опор, которые прячем (для проверки точности).
    Возвращает {id: (улица для показа, номер)}."""
    st = _Streets(roads)
    groups = defaultdict(lambda: {'a': [], 'c': []})
    used = defaultdict(set)      # ключ улицы → номера, которые уже есть (в т.ч. «12а» → 12)
    disp = defaultdict(lambda: defaultdict(int))
    for bid, (x, y), area, t in buildings:
        street, hn = t.get('addr:street'), t.get('addr:housenumber')
        if street and hn and (hold_out is None or bid not in hold_out):
            ks = street_keys(street)
            if not ks: continue
            k = ks[0]
            if _lead(hn) is not None: used[k].add(_lead(hn))
            n = _num(hn)
            p = st.project(x, y, k, MAX_ANCHOR_DIST)
            if n is None or p is None: continue
            groups[(p[1], p[3])]['a'].append((p[2], n))
            disp[k][street] += 1
        elif not hn and t.get('building') in RESIDENTIAL and not t.get('amenity') and not t.get('shop') and area >= MIN_AREA:
            p = st.project(x, y, None, MAX_CAND_DIST)
            if p is None: continue
            groups[(p[1], p[3])]['c'].append((p[2], bid))
    out = {}
    for (ci, side), g in groups.items():
        key = st.chains[ci][0]
        anchors = sorted(g['a']); cands = sorted(g['c'])
        if len(anchors) < 2 or not cands: continue
        name = max(disp[key].items(), key=lambda kv: kv[1])[0] if disp[key] else st.names[key]
        allowed = zoe.get(key) if zoe else None
        for (t1, n1), (t2, n2) in zip(anchors, anchors[1:]):
            if n1 % 2 != n2 % 2 or abs(n2 - n1) < 4 or abs(n2 - n1) > MAX_GAP or t2 - t1 < 1: continue
            # Опоры должны идти по порядку: соседняя пара не может «перепрыгивать» через другую опору.
            inside = [(t, b) for t, b in cands if t1 < t < t2]
            if not inside: continue
            step = 2 if n2 > n1 else -2
            slots = [n for n in range(n1 + step, n2, step) if n not in used[key]]
            if not slots: continue
            picks = {}
            if len(inside) == len(slots):
                for (t, b), n in zip(inside, slots): picks[b] = n
            else:
                taken = {}
                for t, b in inside:
                    v = n1 + (t - t1) / (t2 - t1) * (n2 - n1)
                    n = int(round((v - n1) / 2.0)) * 2 + n1
                    if n not in slots: continue
                    if n in taken:  # два дома на один номер — неоднозначно, не даём никому
                        taken[n] = None; continue
                    taken[n] = b
                picks = {b: n for n, b in taken.items() if b is not None}
            for b, n in picks.items():
                if allowed is not None and str(n) not in allowed: continue
                out[b] = (name, str(n))
    if zoe:
        _fit_ends(st, groups, used, disp, zoe, out)
    return out


def _free(zoe_nums, used, parity, lo=None, hi=None):
    ns = sorted({int(h) for h in zoe_nums if h.isdigit()} - used)
    return [n for n in ns if n % 2 == parity and (lo is None or n > lo) and (hi is None or n < hi)]


def _fit_ends(st, groups, used, disp, zoe, out):
    """Участки без пары опор: номера из официального списка, если число домов совпадает в точности.

    Направление роста номеров берём из опор улицы; если опор нет — пробуем оба и принимаем только то,
    при котором на обеих сторонах число домов в точности равно числу свободных номеров (а при обратном — нет).
    Нечётная сторона — справа по ходу роста номеров (так у 94% опор в Запорожье).
    """
    by_key = defaultdict(list)
    for ci, (k, ch, cum) in enumerate(st.chains): by_key[k].append(ci)
    for key, cis in by_key.items():
        if key not in zoe or len(cis) != 1: continue   # улица из нескольких кусков — порядок неясен
        ci = cis[0]
        name = max(disp[key].items(), key=lambda kv: kv[1])[0] if disp[key] else st.names[key]
        sides = {sd: (sorted(groups[(ci, sd)]['a']) if (ci, sd) in groups else [],
                      sorted((t, b) for t, b in (groups[(ci, sd)]['c'] if (ci, sd) in groups else []) if b not in out))
                 for sd in (1, -1)}
        anchors = sorted(sides[1][0] + sides[-1][0])
        if len(anchors) >= 2:
            up = sum(1 for a, b in zip(anchors, anchors[1:]) if b[1] > a[1]); down = sum(1 for a, b in zip(anchors, anchors[1:]) if b[1] < a[1])
            if up == down or min(up, down) > 0: continue  # опоры противоречат друг другу
            dirs = [1 if up else -1]
        elif not anchors:
            dirs = [1, -1]
        else:
            continue
        fits = []
        for d in dirs:
            plan = {}
            ok = True
            for sd in (1, -1):
                parity = 1 if sd * d == -1 else 0      # нечётные справа по ходу роста номеров
                a, c = sides[sd]
                a = [(t, n) for t, n in a if n % 2 == parity]
                if not c: continue
                if not a:
                    segs = [(None, None, c)]
                else:
                    first, last = (a[0], a[-1]) if d == 1 else (a[-1], a[0])
                    before = [x for x in c if (x[0] < a[0][0] if d == 1 else x[0] > a[-1][0])]
                    after = [x for x in c if (x[0] > a[-1][0] if d == 1 else x[0] < a[0][0])]
                    segs = [(None, first[1], before), (last[1], None, after)]
                for lo, hi, cs in segs:
                    if not cs: continue
                    nums = _free(zoe[key], used[key], parity, lo, hi)
                    if len(nums) != len(cs): ok = False; break
                    order = cs if d == 1 else cs[::-1]
                    for (t, b), n in zip(order, nums): plan[b] = n
                if not ok: break
            if ok and plan: fits.append(plan)
        if len(fits) == 1:
            for b, n in fits[0].items(): out[b] = (name, str(n))
