// POST /pin        {region, address, lon, lat, install, app}  — отметка жителя.
// GET  /confirmed?region=zaporizhia                              — дома, подтверждённые 2+ установками.
// PUT  /data/v1/<файл>.json  — сборщик (GitHub Actions, вход по OIDC-токену GitHub) кладёт свежие графики.
// GET  /data/v1/<файл>.json  — приложение читает их (кеш 20 с), без 5-минутной задержки raw.githubusercontent.
// GET  /maps/v1/index.json, /maps/v1/<город>.bin — карты домов с адресами из нашей базы (кеш 1 ч).
// PUT  /maps/v1/<файл> — сборка карт (GitHub Actions по OIDC или ADMIN_KEY). Эти карты НЕ кладём в публичный git:
//      в них адреса © Візіком, их разрешено хранить только для работы приложения, без передачи третьим лицам.
// GET/PUT /addr/v1/<область>.json — своя база адресов домов {osm way id: адрес} (только OIDC или ADMIN_KEY).
// PUT /addr/v1/<область>/queue {items: [{way, ring, prio}]} — дома без адреса; cron спрашивает их у Visicom (visicom.js).
// POST /feedback, /tg/*, /admin/*, cron — личный Telegram-бот владельца, см. telegram.js.
// Наружу отдаём только адрес и усреднённую точку: id установок и хеши IP не публикуются.

import { route as botRoute, scheduled as botScheduled } from "./telegram.js";
import { fill as visicomFill } from "./visicom.js";

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

const REPO = "OrlanDooos/svetlyachok";
const OIDC_ISS = "https://token.actions.githubusercontent.com";
const OIDC_AUD = "svetlyachok-data";
const DATA_PATH = /^\/data\/v1\/([a-z0-9-]+\.json)$/;
const MAPS_PATH = /^\/maps\/v1\/([a-z0-9-]+\.(?:json|bin))$/;
const ADDR_PATH = /^\/addr\/v1\/([a-z0-9-]+)(\.json|\/queue)$/;
const CHUNK = 900_000;           // D1: строка не больше ~1 МБ, большие файлы режем на части
const MAX_MAP = 20_000_000;
let jwks = null, jwksAt = 0;

const b64u = s => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), c => c.charCodeAt(0));

/** Проверяет OIDC-токен GitHub Actions: подпись (RS256, ключи GitHub), издатель, аудитория, репозиторий, срок. */
export async function verifyGithubOidc(token, now = Date.now() / 1000, fetchKeys = null) {
  const parts = (token || "").split(".");
  if (parts.length !== 3) return false;
  let head, claims;
  try { head = JSON.parse(new TextDecoder().decode(b64u(parts[0]))); claims = JSON.parse(new TextDecoder().decode(b64u(parts[1]))); } catch { return false; }
  if (head.alg !== "RS256" || claims.iss !== OIDC_ISS || claims.aud !== OIDC_AUD || claims.repository !== REPO) return false;
  if (!(claims.exp > now) || (claims.nbf && claims.nbf > now + 60)) return false;
  if (!jwks || Date.now() - jwksAt > 3600e3) {
    jwks = fetchKeys ? await fetchKeys() : await (await fetch(OIDC_ISS + "/.well-known/jwks")).json();
    jwksAt = Date.now();
  }
  const jwk = (jwks.keys || []).find(k => k.kid === head.kid);
  if (!jwk) { jwks = null; return false; }
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  return crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64u(parts[2]), new TextEncoder().encode(parts[0] + "." + parts[1]));
}

const bearer = req => (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");

async function putBlob(env, path, body) {
  const st = [env.DB.prepare("DELETE FROM blobs WHERE path=?1").bind(path)];
  for (let i = 0, n = 0; i < body.length || n === 0; i += CHUNK, n++)
    st.push(env.DB.prepare("INSERT INTO blobs (path, part, data) VALUES (?1, ?2, ?3)").bind(path, n, body.subarray(i, i + CHUNK)));
  await env.DB.batch(st);
}

async function getMap(req, env, name) {
  const cache = caches.default;
  const hit = await cache.match(req.url);
  if (hit) return hit;
  const { results } = await env.DB.prepare("SELECT data FROM blobs WHERE path=?1 ORDER BY part").bind("maps/" + name).all();
  if (!results.length) return json({ error: "not found" }, 404);
  const parts = results.map(r => new Uint8Array(r.data));
  const out = new Uint8Array(parts.reduce((a, p) => a + p.length, 0));
  let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
  const res = new Response(out, { headers: {
    "content-type": name.endsWith(".json") ? "application/json; charset=utf-8" : "application/octet-stream",
    "access-control-allow-origin": "*", "cache-control": "public, max-age=" + (name.endsWith(".json") ? 300 : 3600) } });
  await cache.put(req.url, res.clone());
  return res;
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, POST", "access-control-allow-headers": "content-type" } });
    const dm = url.pathname.match(DATA_PATH);
    if (dm && req.method === "GET") {
      const row = await env.DB.prepare("SELECT body FROM files WHERE path=?1").bind(dm[1]).first();
      if (!row) return json({ error: "not found" }, 404);
      return new Response(row.body, { headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*", "cache-control": "public, max-age=20" } });
    }
    if (dm && req.method === "PUT") {
      const auth = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
      if (!(await verifyGithubOidc(auth))) return json({ error: "forbidden" }, 403);
      const body = await req.text();
      if (body.length > 2_000_000) return json({ error: "too big" }, 413);
      try { JSON.parse(body); } catch { return json({ error: "bad json" }, 400); }
      await env.DB.prepare("INSERT OR REPLACE INTO files (path, body, updated) VALUES (?1, ?2, ?3)").bind(dm[1], body, Math.floor(Date.now() / 1000)).run();
      return json({ ok: true });
    }
    const mm = url.pathname.match(MAPS_PATH);
    if (mm && req.method === "GET") return getMap(req, env, mm[1]);
    if (mm && req.method === "PUT") {
      const t = bearer(req);
      if (!((env.ADMIN_KEY && t === env.ADMIN_KEY) || (await verifyGithubOidc(t)))) return json({ error: "forbidden" }, 403);
      const body = new Uint8Array(await req.arrayBuffer());
      if (body.length > MAX_MAP) return json({ error: "too big" }, 413);
      await putBlob(env, "maps/" + mm[1], body);
      return json({ ok: true, bytes: body.length });
    }
    const am = url.pathname.match(ADDR_PATH);
    if (am) {
      const t = bearer(req);
      if (!((env.ADMIN_KEY && t === env.ADMIN_KEY) || (await verifyGithubOidc(t)))) return json({ error: "forbidden" }, 403);
      if (am[2] === "/queue") {
        if (req.method !== "PUT") return json({ error: "method" }, 405);
        let p; try { p = await req.json(); } catch { return json({ error: "bad json" }, 400); }
        const items = Array.isArray(p.items) ? p.items : [];
        const st = env.DB.prepare("INSERT OR IGNORE INTO addr_queue (region, way, ring, prio) SELECT ?1, ?2, ?3, ?4 WHERE NOT EXISTS (SELECT 1 FROM addresses WHERE region=?1 AND way=?2)");
        const rows = items.filter(i => Number.isInteger(i.way) && Array.isArray(i.ring) && i.ring.length >= 3 && i.ring.length < 2000)
          .map(i => st.bind(am[1], i.way, JSON.stringify(i.ring), Number(i.prio) || 0));
        for (let i = 0; i < rows.length; i += 500) await env.DB.batch(rows.slice(i, i + 500));
        const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM addr_queue WHERE region=?1").bind(am[1]).first();
        return json({ ok: true, queued: left.n });
      }
      if (req.method === "GET") {
        const { results } = await env.DB.prepare("SELECT way, address, source FROM addresses WHERE region=?1").bind(am[1]).all();
        const out = {};
        for (const r of results) out[r.way] = r.address;
        return new Response(JSON.stringify(out), { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
      }
      if (req.method === "PUT") {
        // {source, items: {way id: адрес или ""}} — "" значит «источник адреса не знает», тоже запоминаем.
        let p; try { p = await req.json(); } catch { return json({ error: "bad json" }, 400); }
        const src = String(p.source || "");
        if (!/^[a-z]{2,20}$/.test(src) || typeof p.items !== "object") return json({ error: "bad body" }, 400);
        const now = Math.floor(Date.now() / 1000);
        const st = env.DB.prepare("INSERT OR REPLACE INTO addresses (region, way, address, source, updated) VALUES (?1, ?2, ?3, ?4, ?5)");
        const rows = Object.entries(p.items).filter(([w, a]) => /^\d{1,15}$/.test(w) && typeof a === "string" && a.length < 200)
          .map(([w, a]) => st.bind(am[1], Number(w), a.trim(), src, now));
        for (let i = 0; i < rows.length; i += 500) await env.DB.batch(rows.slice(i, i + 500));
        return json({ ok: true, saved: rows.length });
      }
    }
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
    const bot = await botRoute(req, env, url, json);
    if (bot) return bot;
    return json({ error: "not found" }, 404);
  },
  async scheduled(event, env, ctx) {
    if (event.cron === "*/10 * * * *") ctx.waitUntil(botScheduled(env, env.PUBLIC_URL || "https://svetlyachok-pins.svetlyachok.workers.dev"));
    if (event.cron === "*/2 * * * *") ctx.waitUntil(visicomFill(env).then(r => r.done ? console.log("visicom", JSON.stringify(r)) : null, e => console.log("visicom error", String(e))));
  },
};
