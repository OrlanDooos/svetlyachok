// POST /pin        {region, address, lon, lat, install, app}  — отметка жителя.
// GET  /confirmed?region=zaporizhia                              — дома, подтверждённые 2+ установками.
// Наружу отдаём только адрес и усреднённую точку: id установок и хеши IP не публикуются.

const REGIONS = new Set(["zaporizhia"]);
const MIN_CONFIRM = 2;          // столько разных установок должны отметить одно место
const SAME_PLACE_M = 15;        // отметки ближе этого — одно и то же место
const DAY = 86400;
const MAX_PER_INSTALL_DAY = 20;
const MAX_PER_IP_DAY = 60;

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*", "cache-control": status === 200 ? "public, max-age=300" : "no-store" },
});

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function dist(a, b) {
  const k = 111320 * Math.cos(a.lat * Math.PI / 180);
  return Math.hypot((a.lon - b.lon) * k, (a.lat - b.lat) * 110540);
}

export function validate(p) {
  if (!p || typeof p !== "object") return "bad body";
  if (!REGIONS.has(p.region)) return "bad region";
  if (typeof p.address !== "string" || p.address.length < 3 || p.address.length > 120 || !/,\s*\d/.test(p.address)) return "bad address";
  if (typeof p.lon !== "number" || typeof p.lat !== "number" || p.lon < 22 || p.lon > 40.3 || p.lat < 44.3 || p.lat > 52.4) return "bad point";
  if (typeof p.install !== "string" || !/^[0-9a-f-]{36}$/i.test(p.install)) return "bad install";
  return null;
}

/** Подтверждённые места: для каждого адреса — кластер отметок от MIN_CONFIRM+ разных установок; при равенстве двух кластеров — ничего. */
export function confirmed(rows) {
  const byAddr = new Map();
  for (const r of rows) { if (!byAddr.has(r.address)) byAddr.set(r.address, []); byAddr.get(r.address).push(r); }
  const out = [];
  for (const [address, pins] of byAddr) {
    let best = null, tie = false;
    for (const p of pins) {
      const near = pins.filter(q => dist(p, q) <= SAME_PLACE_M);
      const n = new Set(near.map(q => q.install)).size;
      if (n < MIN_CONFIRM) continue;
      const lon = near.reduce((s, q) => s + q.lon, 0) / near.length, lat = near.reduce((s, q) => s + q.lat, 0) / near.length;
      if (!best || n > best.n) { best = { address, lon, lat, n }; tie = false; }
      else if (n === best.n && dist(best, { lon, lat }) > SAME_PLACE_M) tie = true;
    }
    if (best && !tie) out.push({ address, lon: Math.round(best.lon * 1e6) / 1e6, lat: Math.round(best.lat * 1e6) / 1e6, n: best.n });
  }
  return out.sort((a, b) => a.address.localeCompare(b.address));
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, POST", "access-control-allow-headers": "content-type" } });
    if (req.method === "POST" && url.pathname === "/pin") {
      let p; try { p = await req.json(); } catch { return json({ error: "bad json" }, 400); }
      const err = validate(p); if (err) return json({ error: err }, 400);
      const now = Math.floor(Date.now() / 1000);
      const ip = await sha256((req.headers.get("cf-connecting-ip") || "") + (env.IP_SALT || "svetlyachok"));
      const lim = await env.DB.prepare("SELECT (SELECT COUNT(*) FROM pins WHERE install=?1 AND created>?3) AS a, (SELECT COUNT(*) FROM pins WHERE ip_hash=?2 AND created>?3) AS b")
        .bind(p.install, ip, now - DAY).first();
      if (lim.a >= MAX_PER_INSTALL_DAY || lim.b >= MAX_PER_IP_DAY) return json({ error: "too many" }, 429);
      await env.DB.prepare("INSERT OR REPLACE INTO pins (install, region, address, lon, lat, ip_hash, app, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)")
        .bind(p.install, p.region, p.address.trim(), p.lon, p.lat, ip, String(p.app || "").slice(0, 20), now).run();
      return json({ ok: true });
    }
    if (req.method === "GET" && url.pathname === "/confirmed") {
      const region = url.searchParams.get("region");
      if (!REGIONS.has(region)) return json({ error: "bad region" }, 400);
      const { results } = await env.DB.prepare("SELECT install, address, lon, lat FROM pins WHERE region=?1").bind(region).all();
      return json({ region, minConfirm: MIN_CONFIRM, houses: confirmed(results) });
    }
    return json({ error: "not found" }, 404);
  },
};
