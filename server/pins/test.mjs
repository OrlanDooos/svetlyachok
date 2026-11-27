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
