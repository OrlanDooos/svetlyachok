// Личный Telegram-бот владельца «Светлячка»: присылает ему отзывы из приложения, новые APK и тревоги сторожа.
// Бот односторонний: пишет только владельцу. Владелец привязывается один раз: /start <TG_CLAIM>.
//
// Секреты Worker (не в репозитории): TG_BOT_TOKEN — токен от @BotFather, TG_CLAIM — одноразовый код привязки,
// ADMIN_KEY — ключ для POST /admin/* (выкладка APK из сборки). Без TG_BOT_TOKEN всё молча выключено.
//
// POST /feedback          {text, contact?, app?, install, region?, device?} — отзыв из приложения.
// POST /tg/<hook>         — webhook Telegram (hook выводится из токена, плюс заголовок secret_token).
// POST /admin/apk         multipart: file, caption? (Authorization: Bearer ADMIN_KEY) — APK владельцу файлом.
// POST /admin/message     {text} (тот же ключ) — произвольное сообщение владельцу.
// POST /tg-setup         — сразу поставить webhook (иначе это сделает cron).
// cron каждые 10 мин      — ставит webhook, следит за issues сторожа и свежестью данных в D1.

const REPO = "OrlanDooos/svetlyachok";
const DAY = 86400;
const STALE_MIN = 60;                 // данные в D1 старше — сборщик стоит (сторож в GitHub тоже может стоять)
const FEEDBACK_PER_INSTALL_DAY = 5;
const FEEDBACK_PER_IP_DAY = 20;

export const esc = s => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}
const hookId = token => sha256("hook:" + token).then(h => h.slice(0, 32));

async function kvGet(env, k) {
  const r = await env.DB.prepare("SELECT v FROM kv WHERE k=?1").bind(k).first();
  return r ? r.v : null;
}
const kvSet = (env, k, v) => env.DB.prepare("INSERT OR REPLACE INTO kv (k, v) VALUES (?1, ?2)").bind(k, String(v)).run();

async function tg(env, method, body) {
  const isForm = body instanceof FormData;
  const r = await fetch(`https://api.telegram.org/bot${env.TG_BOT_TOKEN}/${method}`, {
    method: "POST", body: isForm ? body : JSON.stringify(body),
    headers: isForm ? {} : { "content-type": "application/json" },
  });
  const j = await r.json().catch(() => ({ ok: false }));
  if (!j.ok) console.log("telegram", method, r.status, JSON.stringify(j).slice(0, 300));
  return j;
}

/** Сообщение владельцу (HTML). false — бот не настроен или владелец ещё не привязан. */
export async function notify(env, html) {
  if (!env.TG_BOT_TOKEN) return false;
  const chat = await kvGet(env, "owner");
  if (!chat) return false;
  const j = await tg(env, "sendMessage", { chat_id: chat, text: html.slice(0, 4000), parse_mode: "HTML", disable_web_page_preview: true });
  return !!j.ok;
}

export function validateFeedback(p) {
  if (!p || typeof p !== "object") return "bad body";
  if (typeof p.text !== "string" || p.text.trim().length < 3 || p.text.length > 2000) return "bad text";
  if (p.contact != null && (typeof p.contact !== "string" || p.contact.length > 100)) return "bad contact";
  if (typeof p.install !== "string" || !/^[0-9a-f-]{36}$/i.test(p.install)) return "bad install";
  return null;
}

export function feedbackMessage(p) {
  const meta = [p.app && `версия ${p.app}`, p.region, p.device].filter(Boolean).map(s => esc(String(s).slice(0, 60))).join(" · ");
  return `💬 <b>Отзыв из приложения</b>\n\n${esc(p.text.trim())}` +
    (p.contact ? `\n\n<b>Контакт:</b> ${esc(p.contact.trim())}` : "") +
    (meta ? `\n\n<i>${meta}</i>` : "");
}

async function feedback(req, env, json) {
  let p; try { p = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  const err = validateFeedback(p); if (err) return json({ error: err }, 400);
  const now = Math.floor(Date.now() / 1000);
  const ip = await sha256((req.headers.get("cf-connecting-ip") || "") + (env.IP_SALT || "svetlyachok"));
  const lim = await env.DB.prepare("SELECT (SELECT COUNT(*) FROM feedback WHERE install=?1 AND created>?3) AS a, (SELECT COUNT(*) FROM feedback WHERE ip_hash=?2 AND created>?3) AS b")
    .bind(p.install, ip, now - DAY).first();
  if (lim.a >= FEEDBACK_PER_INSTALL_DAY || lim.b >= FEEDBACK_PER_IP_DAY) return json({ error: "too many" }, 429);
  const sent = await notify(env, feedbackMessage(p));
  await env.DB.prepare("INSERT INTO feedback (install, ip_hash, text, contact, app, region, device, created, sent) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)")
    .bind(p.install, ip, p.text.trim(), (p.contact || "").trim(), String(p.app || "").slice(0, 20), String(p.region || "").slice(0, 40), String(p.device || "").slice(0, 60), now, sent ? 1 : 0).run();
  return json({ ok: true });
}

async function statusText(env) {
  const row = await env.DB.prepare("SELECT body FROM files WHERE path='regions.json'").first();
  if (!row) return "Нет данных regions.json.";
  const idx = JSON.parse(row.body);
  const age = Math.round((Date.now() - Date.parse(idx.generatedAt)) / 60000);
  const bad = (idx.regions || []).filter(r => r.ok === false).map(r => esc(r.name || r.id));
  const open = JSON.parse((await kvGet(env, "issues")) || "{}");
  return `📊 Последний сбор: ${age} мин назад.\nОбластей: ${(idx.regions || []).length}` +
    (bad.length ? `\nС ошибкой: ${bad.join(", ")}` : "\nОшибок источников нет.") +
    `\nОткрытых тревог: ${Object.keys(open).length}`;
}

async function webhook(req, env, json) {
  const hook = await hookId(env.TG_BOT_TOKEN);
  if (req.headers.get("x-telegram-bot-api-secret-token") !== hook) return json({ error: "forbidden" }, 403);
  const u = await req.json().catch(() => null);
  const m = u && u.message;
  if (!m || !m.chat) return json({ ok: true });
  const chat = String(m.chat.id), text = (m.text || "").trim();
  const owner = await kvGet(env, "owner");
  const reply = t => tg(env, "sendMessage", { chat_id: chat, text: t, parse_mode: "HTML" });
  if (chat === owner) {
    if (text === "/status") await reply(await statusText(env));
    else await reply("Я присылаю сюда отзывы, новые APK и тревоги сторожа. Команда /status — состояние сборщика.\nЭтот бот не пересылает сообщения в Claude.");
  } else if (!owner && env.TG_CLAIM && text === `/start ${env.TG_CLAIM}`) {
    await kvSet(env, "owner", chat);
    await reply("✅ Готово. Теперь сюда будут приходить отзывы из «Світлячка», новые APK и тревоги сторожа.\n/status — состояние сборщика.");
  } else {
    await reply("Это личный бот уведомлений приложения «Світлячок». Отзыв можно оставить в самом приложении: Ще → Написати відгук.");
  }
  return json({ ok: true });
}

async function admin(req, env, url, json) {
  const key = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!env.ADMIN_KEY || key !== env.ADMIN_KEY) return json({ error: "forbidden" }, 403);
  if (!env.TG_BOT_TOKEN) return json({ error: "bot not configured" }, 503);
  const owner = await kvGet(env, "owner");
  if (!owner) return json({ error: "owner not linked" }, 503);
  if (url.pathname === "/admin/message") {
    const p = await req.json().catch(() => ({}));
    return json({ ok: await notify(env, String(p.text || "")) });
  }
  const form = await req.formData();
  const file = form.get("file");
  if (!file || typeof file === "string") return json({ error: "no file" }, 400);
  const out = new FormData();
  out.append("chat_id", owner);
  out.append("document", file, file.name || "svetlyachok.apk");
  const cap = form.get("caption");
  if (cap) { out.append("caption", String(cap).slice(0, 1000)); out.append("parse_mode", "HTML"); }
  const j = await tg(env, "sendDocument", out);
  return json({ ok: !!j.ok }, j.ok ? 200 : 502);
}

/** Маршруты бота; null — не наш путь. */
export async function route(req, env, url, json) {
  if (req.method !== "POST") return null;
  if (url.pathname === "/feedback") return feedback(req, env, json);
  if (url.pathname === "/tg-setup") {      // без ключа: только направляет webhook бота на этот же Worker
    if (!env.TG_BOT_TOKEN) return json({ ok: false, error: "no TG_BOT_TOKEN" }, 503);
    const ok = await ensureWebhook(env, url.origin);
    return json({ ok, owner: !!(await kvGet(env, "owner")) });
  }
  if (url.pathname.startsWith("/tg/")) {
    if (!env.TG_BOT_TOKEN || url.pathname !== "/tg/" + await hookId(env.TG_BOT_TOKEN)) return json({ error: "not found" }, 404);
    return webhook(req, env, json);
  }
  if (url.pathname === "/admin/apk" || url.pathname === "/admin/message") return admin(req, env, url, json);
  return null;
}

/** Изменения в открытых issues сторожа: {opened: [...], closed: [...]}, next — новое состояние. */
export function diffIssues(prev, issues) {
  const next = {}, opened = [], closed = [];
  for (const i of issues) {
    next[i.number] = i.title;
    if (!(i.number in prev)) opened.push(i);
  }
  for (const n of Object.keys(prev)) if (!(n in next)) closed.push({ number: Number(n), title: prev[n] });
  return { next, opened, closed };
}

async function watchIssues(env) {
  const r = await fetch(`https://api.github.com/repos/${REPO}/issues?state=open&labels=watchdog&per_page=100`, {
    headers: { "user-agent": "svetlyachok-pins-worker", accept: "application/vnd.github+json" },
  });
  if (!r.ok) { console.log("github", r.status); return; }
  const issues = (await r.json()).filter(i => !i.pull_request);
  const prevRaw = await kvGet(env, "issues");
  const { next, opened, closed } = diffIssues(JSON.parse(prevRaw || "{}"), issues);
  if (prevRaw !== null) {             // при первом запуске только запоминаем, чтобы не засыпать старыми тревогами
    for (const i of opened) {
      const body = (i.body || "").replace(/<!--[\s\S]*?-->/g, "").replace("Issue закроется сам, когда проблема исчезнет.", "").trim();
      await notify(env, `🚨 <b>${esc(i.title)}</b>\n\n${esc(body)}\n\n<a href="${i.html_url}">issue #${i.number}</a>`);
    }
    for (const i of closed) await notify(env, `✅ Починилось: ${esc(i.title.replace(/^⚠️\s*/, ""))}`);
  } else if (issues.length) {
    await notify(env, `Сейчас открыто тревог сторожа: ${issues.length}\n` + issues.map(i => "• " + esc(i.title)).join("\n"));
  }
  await kvSet(env, "issues", JSON.stringify(next));
}

async function watchFreshness(env) {
  const row = await env.DB.prepare("SELECT updated FROM files WHERE path='regions.json'").first();
  if (!row) return;
  const age = (Date.now() / 1000 - row.updated) / 60;
  const alerted = (await kvGet(env, "stale")) === "1";
  if (age > STALE_MIN && !alerted) {
    if (await notify(env, `🚨 <b>Сборщик молчит ${Math.round(age)} мин</b>\nСвежие графики не приходили на сервер. Проверьте Actions → Collect schedules.\nhttps://github.com/${REPO}/actions`)) await kvSet(env, "stale", "1");
  } else if (age <= STALE_MIN && alerted) {
    await notify(env, "✅ Сборщик снова присылает данные.");
    await kvSet(env, "stale", "0");
  }
}

/** Регистрирует webhook на этот Worker (один раз на токен). */
async function ensureWebhook(env, origin) {
  const hook = await hookId(env.TG_BOT_TOKEN);
  if ((await kvGet(env, "webhook")) === hook) return true;
  const j = await tg(env, "setWebhook", { url: `${origin}/tg/${hook}`, secret_token: hook, allowed_updates: ["message"], drop_pending_updates: true });
  if (j.ok) await kvSet(env, "webhook", hook);
  return !!j.ok;
}

export async function scheduled(env, origin) {
  if (!env.TG_BOT_TOKEN) return;
  await ensureWebhook(env, origin);
  if (!(await kvGet(env, "owner"))) return;
  await watchFreshness(env);
  await watchIssues(env);
}
