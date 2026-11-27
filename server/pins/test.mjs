// node test.mjs — проверка подсчёта подтверждений без Cloudflare.
import assert from "node:assert";
import { confirmed, validate } from "./src/index.js";
const A = "11111111-1111-1111-1111-111111111111", B = "22222222-2222-2222-2222-222222222222", C = "33333333-3333-3333-3333-333333333333";
const p = (install, lon, lat, address = "вул. Дальня, 14") => ({ install, address, lon, lat });
assert.deepStrictEqual(confirmed([p(A, 35.2034, 47.8131)]), []);                                  // одна отметка — мало
assert.strictEqual(confirmed([p(A, 35.2034, 47.8131), p(A, 35.20341, 47.8131)]).length, 0);       // одна установка дважды
const ok = confirmed([p(A, 35.2034, 47.8131), p(B, 35.20345, 47.81312)]);
assert.strictEqual(ok.length, 1); assert.strictEqual(ok[0].n, 2);
assert.strictEqual(confirmed([p(A, 35.2034, 47.8131), p(B, 35.21, 47.82)]).length, 0);          // далеко друг от друга
assert.strictEqual(confirmed([p(A, 35.2034, 47.8131), p(B, 35.2034, 47.8131), p(C, 35.25, 47.85), p("4" + C.slice(1), 35.25, 47.85)]).length, 0); // ничья двух мест
assert.strictEqual(validate({ region: "zaporizhia", address: "вул. Дальня, 14", lon: 35.2, lat: 47.8, install: A }), null);
assert.notStrictEqual(validate({ region: "kyiv", address: "вул. Дальня, 14", lon: 35.2, lat: 47.8, install: A }), null);
assert.notStrictEqual(validate({ region: "zaporizhia", address: "Дальня", lon: 35.2, lat: 47.8, install: A }), null);
console.log("ok");

// OIDC-токен GitHub Actions: подпись своим ключом вместо ключей GitHub.
import { verifyGithubOidc } from "./src/index.js";
const kp = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
const pub = { ...(await crypto.subtle.exportKey("jwk", kp.publicKey)), kid: "k1" };
const enc = o => Buffer.from(JSON.stringify(o)).toString("base64url");
async function jwt(claims) {
  const h = enc({ alg: "RS256", kid: "k1" }), b = enc(claims);
  const sig = Buffer.from(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", kp.privateKey, new TextEncoder().encode(h + "." + b))).toString("base64url");
  return `${h}.${b}.${sig}`;
}
const keys = async () => ({ keys: [pub] });
const good = { iss: "https://token.actions.githubusercontent.com", aud: "svetlyachok-data", repository: "OrlanDooos/svetlyachok", exp: 2e9 };
assert.strictEqual(await verifyGithubOidc(await jwt(good), 1.9e9, keys), true);
assert.strictEqual(await verifyGithubOidc(await jwt({ ...good, repository: "evil/repo" }), 1.9e9, keys), false);
assert.strictEqual(await verifyGithubOidc(await jwt({ ...good, aud: "x" }), 1.9e9, keys), false);
assert.strictEqual(await verifyGithubOidc(await jwt({ ...good, exp: 1 }), 1.9e9, keys), false);
const t = await jwt(good); assert.strictEqual(await verifyGithubOidc(t.slice(0, -4) + "AAAA", 1.9e9, keys), false);
console.log("oidc ok");

// Telegram-бот: отзывы и тревоги сторожа.
import { validateFeedback, feedbackMessage, diffIssues, esc } from "./src/telegram.js";
assert.strictEqual(validateFeedback({ text: "Не бачу свою чергу", install: A }), null);
assert.notStrictEqual(validateFeedback({ text: "ok", install: A }), null);
assert.notStrictEqual(validateFeedback({ text: "x".repeat(2001), install: A }), null);
assert.notStrictEqual(validateFeedback({ text: "нормальний текст", install: "nope" }), null);
assert.ok(feedbackMessage({ text: "<script>", contact: "@me", app: "1.5" }).includes("&lt;script&gt;"));
assert.strictEqual(esc("a&b"), "a&amp;b");
let d = diffIssues({}, [{ number: 7, title: "⚠️ Київ: нет графика" }]);
assert.strictEqual(d.opened.length, 1);
d = diffIssues(d.next, []);
assert.deepStrictEqual(d.closed, [{ number: 7, title: "⚠️ Київ: нет графика" }]);
console.log("bot ok");

// Visicom: разбор ответов и порядок поиска (без сети).
import { lookup, nearest, insidePoint, fill } from "./src/visicom.js";
const vf = (n, d, poly = true) => ({ type: "Feature", geometry: { type: poly ? "Polygon" : "Point" }, properties: { categories: "adr_address", name: n, street: "Дальня", street_type: "вул.", dist_meters: d } });
const fc = (...f) => ({ type: "FeatureCollection", features: f });
assert.strictEqual(nearest(fc(vf("14", 5), vf("16", 20))), "вул. Дальня, 14");
assert.strictEqual(nearest(fc(vf("14", 5), vf("16", 8))), null);
assert.strictEqual(nearest(fc(vf("14", 15))), null);
assert.deepStrictEqual(insidePoint([[0, 0], [2, 0], [2, 1], [0, 1]]), [1, 0.5]);
const sq = [[35.1, 47.8], [35.1001, 47.8], [35.1001, 47.8001], [35.1, 47.8001]];
let calls = [];
const fake = answers => async (p, g, e) => { calls.push(p); return answers.shift() || {}; };
assert.strictEqual(await lookup(sq, fake([fc(vf("14", 0))])), "вул. Дальня, 14"); assert.deepStrictEqual(calls, ["intersect"]);
calls = []; assert.strictEqual(await lookup(sq, fake([fc(vf("14", 0), vf("16", 0))])), ""); assert.strictEqual(calls.length, 1);
calls = []; assert.strictEqual(await lookup(sq, fake([{}, fc(vf("3", 0, false))])), "вул. Дальня, 3"); assert.strictEqual(calls.length, 2);
calls = []; assert.strictEqual(await lookup(sq, fake([{}, {}, fc(vf("5", 4, false))])), "вул. Дальня, 5"); assert.deepStrictEqual(calls, ["intersect", "intersect", "near"]);

// fill: дневной лимит и запись результатов (D1 подменён простым объектом).
const kv = {}, queue = [{ region: "zaporizhia", way: 1, ring: JSON.stringify(sq) }, { region: "zaporizhia", way: 2, ring: JSON.stringify(sq) }], saved = [];
const DB = {
  prepare(sql) { return { sql, args: [], bind(...a) { this.args = a; return this; },
    async first() { return sql.includes("kv") ? (kv.visicom_used ? { v: kv.visicom_used } : null) : null; },
    async all() { return { results: queue.slice(0, this.args[0]) }; } }; },
  async batch(st) { for (const s of st) { if (s.sql.includes("INTO kv")) kv.visicom_used = s.args[0]; if (s.sql.includes("INTO addresses")) saved.push([s.args[1], s.args[2]]); } },
};
const fetchFn = async () => ({ status: 200, ok: true, json: async () => fc(vf("14", 0)) });
let r = await fill({ DB, VISICOM_KEY: "k", VISICOM_DAILY: "3" }, fetchFn);
assert.strictEqual(r.done, 1); assert.deepStrictEqual(saved, [[1, "вул. Дальня, 14"]]);
r = await fill({ DB, VISICOM_KEY: "k", VISICOM_DAILY: "3" }, fetchFn);
assert.strictEqual(r.skipped, "daily limit");
assert.strictEqual((await fill({ DB }, fetchFn)).skipped, "no key");
console.log("visicom ok");
