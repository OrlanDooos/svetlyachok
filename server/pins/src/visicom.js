// Своя база адресов: сервер сам спрашивает Visicom Data API (© Візіком) адреса домов из очереди.
// Разрешение Visicom (письма 27.11.2026): хранить данные можно только для работы «Світлячка»,
// третьим лицам не передавать, в приложении указывать «Візіком». Поэтому эти адреса живут только в D1.
// Секрет VISICOM_KEY; VISICOM_DAILY — сколько запросов в сутки можно тратить (по умолчанию 1400).
// Логика поиска та же, что в приложении (Visicom.kt): точка внутри дома → контур → ближайший адрес ≤ 12 м.

const API = "https://api.visicom.ua/data-api/5.0/uk/geocode.json";
const PER_RUN = 40;   // запросов за один запуск cron (лимит подзапросов Workers — 50)

function features(o) {
  if (!o || typeof o !== "object") return [];
  return o.type === "Feature" ? [o] : (o.features || []);
}

export function addressOf(f, polygonsOnly) {
  const p = f.properties || {};
  if (!String(p.categories || "").includes("adr_address")) return null;
  const gt = (f.geometry && f.geometry.type) || "";
  if (polygonsOnly && gt && !gt.includes("Polygon")) return null;
  const name = String(p.name || "").trim(), street = String(p.street || "").trim();
  if (!name || !street || !/^\d/.test(name)) return null;
  const t = String(p.street_type || "").trim();
  return (t ? `${t} ${street}` : street) + ", " + name;
}

export const addresses = (o, polygonsOnly) => [...new Set(features(o).map(f => addressOf(f, polygonsOnly)).filter(Boolean))];

export function nearest(o) {
  const lst = features(o).map(f => [addressOf(f, false), (f.properties || {}).dist_meters])
    .filter(([a, d]) => a && typeof d === "number").sort((a, b) => a[1] - b[1]);
  if (!lst.length || lst[0][1] > 12) return null;
  const [a0, d0] = lst[0];
  const second = lst.find(([a]) => a !== a0);
  if (second && second[1] < Math.max(2 * d0, d0 + 6)) return null;
  return a0;
}

/** Точка внутри контура: середина первого отрезка горизонтального сечения на средней широте. */
export function insidePoint(ring) {
  const ys = ring.map(p => p[1]);
  const y = (Math.min(...ys) + Math.max(...ys)) / 2;
  const xs = [];
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i], b = ring[(i + 1) % ring.length];
    if ((a[1] > y) !== (b[1] > y)) xs.push(a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]));
  }
  xs.sort((a, b) => a - b);
  if (xs.length >= 2) return [(xs[0] + xs[1]) / 2, y];
  return [ring.reduce((s, p) => s + p[0], 0) / ring.length, y];
}

export function wkt(ring) {
  const step = Math.ceil(ring.length / 249);
  const pts = ring.filter((_, i) => i % step === 0);
  if (pts[0][0] !== pts[pts.length - 1][0] || pts[0][1] !== pts[pts.length - 1][1]) pts.push(pts[0]);
  return "POLYGON((" + pts.map(([x, y]) => `${x.toFixed(7)} ${y.toFixed(7)}`).join(",") + "))";
}

class LimitError extends Error {}

/** Адрес дома или "" (Visicom не знает / неоднозначно). Бросает LimitError при исчерпании ключа. */
export async function lookup(ring, call) {
  const [x, y] = insidePoint(ring);
  const ck = `${x.toFixed(6)},${y.toFixed(6)}`;
  const byPoint = addresses(await call("intersect", ck), true);
  if (byPoint.length === 1) return byPoint[0];
  if (byPoint.length > 1) return "";
  const byShape = addresses(await call("intersect", wkt(ring)), false);
  if (byShape.length === 1) return byShape[0];
  return nearest(await call("near", ck, "&r=25")) || "";
}

export async function fill(env, fetchFn = fetch) {
  if (!env.VISICOM_KEY) return { skipped: "no key" };
  const day = new Date().toISOString().slice(0, 10);
  const daily = Number(env.VISICOM_DAILY || 1400);
  const row = await env.DB.prepare("SELECT v FROM kv WHERE k='visicom_used'").first();
  let used = 0;
  if (row) { const [d, n] = row.v.split(" "); if (d === day) used = Number(n) || 0; }
  let budget = Math.min(PER_RUN, daily - used);
  if (budget < 3) return { skipped: "daily limit", used };
  const call = async (param, geom, extra = "") => {
    budget--; used++;
    const r = await fetchFn(`${API}?categories=adr_address&${param}=${encodeURIComponent(geom)}${extra}&limit=5&key=${encodeURIComponent(env.VISICOM_KEY)}`,
      { headers: { "user-agent": "svetlyachok-server" } });
    if (r.status === 404) return {};
    if (r.status === 401 || r.status === 403 || r.status === 429) throw new LimitError("visicom " + r.status);
    if (!r.ok) throw new Error("visicom " + r.status);
    return r.json();
  };
  const { results } = await env.DB.prepare("SELECT region, way, ring FROM addr_queue ORDER BY prio, way LIMIT ?1").bind(Math.ceil(budget / 1.5)).all();
  const done = [];
  let stop = null;
  for (const q of results) {
    if (budget < 3) break;
    try {
      const a = await lookup(JSON.parse(q.ring), call);
      done.push(q);
      q.address = a;
    } catch (e) {
      if (e instanceof LimitError) { stop = e.message; used = daily; break; }
      stop = String(e.message || e); break;   // сеть/ошибка API — попробуем этот дом в следующий раз
    }
  }
  const now = Math.floor(Date.now() / 1000);
  const st = [env.DB.prepare("INSERT OR REPLACE INTO kv (k, v) VALUES ('visicom_used', ?1)").bind(`${day} ${used}`)];
  for (const q of done) {
    st.push(env.DB.prepare("INSERT OR REPLACE INTO addresses (region, way, address, source, updated) VALUES (?1, ?2, ?3, 'visicom', ?4)").bind(q.region, q.way, q.address, now));
    st.push(env.DB.prepare("DELETE FROM addr_queue WHERE region=?1 AND way=?2").bind(q.region, q.way));
  }
  await env.DB.batch(st);
  return { done: done.length, found: done.filter(q => q.address).length, used, stop };
}
