import { test } from "node:test";
import * as assert from "node:assert/strict";
const { Adapter, rowsOf } = require("../../media/dataView.js");
const batch = (times: number[], values: unknown[], type = "float64", children: unknown[] = []) => ({
  fields: [{ name: "timestamp", type: "timestamp[us]" }, { name: "value", type, children }], metadata: {},
  batches: [{ length: times.length, metadata: { entry: "/fixture" }, columns: [
    { values: times }, { name: "value", type, values } ] }],
});
test("follow batches append rows and keep the recorded duplicates at a boundary", async () => {
  let rows: unknown[] = [];
  const table = { update: async (next: unknown[]) => { rows.push(...next); }, replace: async (next: unknown[]) => { rows = next; }, size: async () => rows.length };
  const adapter = new Adapter({ table: async () => table }, { load: async () => {} });
  assert.equal(await adapter.accept(batch([1e6, 2e6], [2, 4]), false), 2);
  assert.equal(await adapter.accept(batch([2e6, 2e6, 3e6], [4, 5, 6]), true), 4);
  assert.deepEqual(rows, [2, 4, 5, 6].map((value, i) => ({ entry: "/fixture", timestamp_sec: [1, 2, 2, 3][i], "/fixture": value })));
});
test("a struct's fields become separate typed columns, not JSON in one cell", () => {
  const result = rowsOf(batch([2e6], [{ translation: { x: 3, y: 4 }, heading: 0.5 }], "struct", [
    { name: "translation", type: "struct", children: [{ name: "x", type: "float64" }, { name: "y", type: "float64" }] },
    { name: "heading", type: "float64" },
  ]));
  assert.deepEqual(result.rows, [{ entry: "/fixture", timestamp_sec: 2, "/fixture.translation.x": 3, "/fixture.translation.y": 4, "/fixture.heading": 0.5 }]);
  assert.equal(result.schema["/fixture.translation.x"], "float");
  assert.throws(() => rowsOf({ ...batch([0], [1]), metadata: { bucketed: "true" } }), /exact samples/);
});

test("adding a selected entry keeps existing rows and makes its columns visible", async () => {
  let shown: any;
  const worker = { table: async () => {
    const held: any[] = [];
    return { update: async (rows: any[]) => held.push(...rows), size: async () => held.length,
      view: async () => ({ to_json: async () => [...held], delete: async () => {} }), delete: async () => {} };
  } };
  const viewer = { load: async () => {}, delete: async () => {},
    save: async () => ({ columns: ["entry", "timestamp_sec", "/fixture"], sort: [["timestamp_sec", "desc"]] }),
    restore: async (config: any) => { shown = config; } };
  const adapter = new Adapter(worker, viewer);
  await adapter.accept(batch([1e6], [2]), false);
  const second = batch([2e6], [3]); second.batches[0].metadata.entry = "/second";
  assert.equal(await adapter.accept(second, true), 2);
  assert.deepEqual(shown.columns, ["entry", "timestamp_sec", "/fixture", "/second"]);
  assert.deepEqual(shown.sort, [["timestamp_sec", "desc"]]);
});


test("the data pane permits only bundled wasm and the local-source worker, without general eval", () => {
  const { contentSecurityPolicy } = require("../explorer/webviewHtml");
  const policy = contentSecurityPolicy({ cspSource: "https://extension-resource.test", nonce: "random", data: {} });
  assert.ok(policy.includes("'wasm-unsafe-eval'")); assert.ok(!policy.includes("'unsafe-eval'"));
  assert.ok(policy.includes("connect-src https://extension-resource.test"));
  assert.ok(policy.includes("worker-src blob:")); assert.ok(!policy.includes(" https:;"));
});

test("a validity bitmap null is a null cell even when its storage holds a number", () => {
  const stream = batch([1e6, 2e6, 3e6], [10, 99, 30]);
  Object.assign(stream.batches[0].columns[1], { valid: [true, false, true] });
  assert.deepEqual(rowsOf(stream).rows.map((row: any) => row["/fixture"]), [10, null, 30]);
});
