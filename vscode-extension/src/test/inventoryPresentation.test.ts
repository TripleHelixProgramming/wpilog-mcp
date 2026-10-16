import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ByteBudget } from "../visualization/byteBudget";
import { InventoryPresentation } from "../visualization/inventoryPresentation";
import { OverviewState } from "../visualization/inventoryOverview";
const state = (generation: number) => ({ generation, loading: false, expired: false, hidden: false, retryAt: 0, filter: { query: "", types: [], unavailable: false }, previous: false, next: false, pageNumber: 1 }) as OverviewState;
test("renderer delivery retains one accounted clone and coalesces a burst until acknowledgement", () => {
  const budget = new ByteBudget(), sent: { id: string; state: OverviewState }[] = [];
  const p = new InventoryPresentation(budget, (id, state) => sent.push({ id, state }), reason => assert.fail(reason));
  p.offer(state(1)); const firstBytes = budget.reserved;
  for (let n = 2; n < 1000; n++) p.offer(state(n));
  assert.equal(sent.length, 1); assert.equal(budget.reserved, firstBytes); assert.ok(firstBytes > 0);
  p.acknowledge("foreign"); assert.equal(sent.length, 1);
  p.acknowledge(sent[0].id); assert.equal(sent.length, 2); assert.equal(sent[1].state.generation, 999);
  p.acknowledge(sent[0].id); assert.equal(budget.reserved, firstBytes);
  p.acknowledge(sent[1].id); assert.equal(budget.reserved, 0); p.dispose();
});
test("renderer stall stops acquisition while its undelivered copy remains accounted until close", async () => {
  const budget = new ByteBudget(), failures: string[] = [];
  const p = new InventoryPresentation(budget, () => {}, reason => failures.push(reason), 5);
  p.offer(state(1)); await new Promise(r => setTimeout(r, 25));
  assert.equal(failures.length, 1); assert.match(failures[0], /stopped acknowledging/); assert.ok(budget.reserved > 0);
  p.dispose(); assert.equal(budget.reserved, 0); p.offer(state(2)); assert.equal(budget.reserved, 0);
});
test("renderer cannot clone a state beyond the remaining window budget", () => {
  const budget = new ByteBudget(1), errors: string[] = []; let sends = 0;
  const p = new InventoryPresentation(budget, () => sends++, reason => errors.push(reason));
  p.offer(state(1)); assert.equal(sends, 0); assert.equal(errors.length, 1); assert.match(errors[0], /budget/); p.dispose();
});
