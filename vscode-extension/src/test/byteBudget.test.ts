import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ByteBudget, decodedBytes } from "../visualization/byteBudget";
test("byte reservations are atomic and idempotently released without oversubscription", () => {
  const b = new ByteBudget(10), a = b.reserve(6); assert.throws(() => b.reserve(5), /budget/);
  const c = b.reserve(4); assert.equal(b.reserved, 10); a(); a(); assert.equal(b.reserved, 4); c(); assert.equal(b.reserved, 0);
  for (const n of [-1, 0.5, Infinity, NaN]) assert.throws(() => b.reserve(n));
  assert.throws(() => new ByteBudget(0));
});
test("decoded accounting includes UTF-16 text, keys, references and container overhead", () => {
  assert.equal(decodedBytes(null), 16); assert.equal(decodedBytes("😀"), 36);
  assert.equal(decodedBytes({ a: 1 }), 146);
  assert.equal(decodedBytes([null, "x"]), 130);
  let deep: unknown = null; for (let i = 0; i < 66; i++) deep = [deep];
  assert.throws(() => decodedBytes(deep), /nesting/);
});
