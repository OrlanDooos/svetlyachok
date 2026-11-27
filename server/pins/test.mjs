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
