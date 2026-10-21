/** Drive the real host callbacks and inventory owners while catalog/render replies are delayed. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import { VisualizationBudgets } from "../visualization/byteBudget";
import { InventoryOverview, OverviewClock } from "../visualization/inventoryOverview";
import { RecordedClient } from "../visualization/recordedClient";
const loader = require("node:module"), original = loader._load;
let RecordedPreview: typeof import("../visualization/recordedPreview").RecordedPreview;
try { loader._load = (name: string, ...args: any[]) => name === "vscode" ? {} : original(name, ...args); RecordedPreview = require("../visualization/recordedPreview").RecordedPreview; }
finally { loader._load = original; }
const source = { kind: "session", origin_store_id: "store", session_id: "recording", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
const tick = async () => { for (let i = 0; i < 20; i++) await new Promise<void>(r => setImmediate(r)); };
function deferred<T>() { let resolve!: (v: T) => void, reject!: (e: Error) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function fixture(t: any) {
  const preview: any = Object.create(RecordedPreview.prototype), budgets = new VisualizationBudgets();
  const requests: { route: string; body?: any }[] = [], owners: InventoryOverview[] = [], messages: any[] = [], warnings: unknown[] = [];
  const pending: ReturnType<typeof deferred<any>>[] = [], signals: AbortSignal[] = [], timers = new Map<object, () => void>();
  const time: OverviewClock = { now: () => 100000, later: fn => { const id = {}; timers.set(id, fn); return id; }, cancel: id => { timers.delete(id as object); } };
  let opens = 0, changed = () => {}, closed = () => {};
  const uri = { scheme: "file", fsPath: "/synthetic/capture.wpilog", toString: () => "file:///synthetic/capture.wpilog" };
  const panel = { visible: true, webview: { html: "", onDidReceiveMessage() {}, postMessage: async (m: any) => {
    messages.push(m); if (m.delivery_id) queueMicrotask(() => page.delivery?.acknowledge(m.delivery_id)); return true;
  } }, onDidChangeViewState: (fn: () => void) => { changed = fn; }, onDidDispose: (fn: () => void) => { closed = fn; } };
  const client = { visualization: async (route: string, body: any, _signal: unknown, options: any) => {
    requests.push({ route, body }); options.captureSession?.("owner");
    if (route === "/capabilities") return { recording_inventory_paging: true };
    if (route === "/source/describe") return { inventory_mode: "paged", inventory_id: "i" + opens++, server_epoch: "epoch", source_ref: source,
      expires_at: new Date(400000).toISOString(), idle_expiry_ms: 60000, total_entry_names: 1, conflicting_entry_names: 0, unavailable_entry_names: 0, members: [] };
    if (route === "/source/entries/list") return { inventory_id: body.inventory_id, server_epoch: "epoch", complete: true, next_cursor: null,
      entries: [{ row_id: "row", name: "/value", type: "int64", sample_count: "1", conflict: false, unavailable: false, members: [] }],
      returned: 1, total_matching: 1, total_entry_names: 1, unavailable_entry_names: 0, unavailable_excluded: true };
    if (route === "/source/inventory/close") return {};
    throw new Error(route);
  } };
  Object.assign(preview, { budgets, host: { client: async () => client, overviewClock: time }, overviews: new Map(), identity: async () => {},
    targetEntries() {}, pageHtml: () => "", warning: (_panel: unknown, error: unknown) => warnings.push(error), refreshOverview: async () => {},
    backend: { identify: (_path: string, signal: AbortSignal) => { signals.push(signal); const p = deferred<any>(); pending.push(p); return p.promise; }, describe: async () => { throw new Error("Recording used full inspection"); } } });
  preview.renderOverview(uri, panel); const page = preview.overviews.get(uri.toString());
  let current: InventoryOverview | undefined;
  Object.defineProperty(page, "inventory", { get: () => current, set: (v: InventoryOverview) => { current = v; owners.push(v); } });
  const send = () => preview.overviewMessage(page, { type: "ready" });
  const hide = () => { panel.visible = false; changed(); }, reveal = () => { panel.visible = true; changed(); };
  t.after(async () => { closed(); pending.forEach(p => p.resolve({ source_ref: source })); await tick(); for (const v of owners) await v.dispose(); page.delivery?.dispose(); });
  return { preview, page, panel, pending, signals, owners, messages, requests, warnings, budgets, timers, send, hide, reveal, close: closed,
    catalog: (n: number) => pending[n].resolve({ source_ref: source }) };
}

test("duplicate ready joins one catalog initialization and admits one inventory owner", async t => {
  const f = fixture(t), a = f.send(); await tick(); const b = f.send(); await tick();
  assert.equal(f.pending.length, 1, "one catalog read for concurrent renderers in the same visible generation");
  f.catalog(0); await Promise.all([a, b]); await tick(); assert.equal(f.owners.length, 1);
  await f.send(); await tick(); assert.equal(f.owners.length, 1, "a later ready reuses the admitted owner too");
  f.close(); await tick(); assert.equal(f.budgets.decoded.reserved, 0); assert.equal(f.budgets.encoded.reserved, 0);
});
test("a replica replacement during overview initialization schedules a fresh description", async t => {
  const f = fixture(t); let notify: (value: any) => void = () => {}, descriptions = 0;
  const listing = deferred<void>(); f.preview.refreshOverview = () => listing.promise;
  f.preview.backend.describe = async () => ({ replica: { generation: descriptions++ ? "new" : "old", content_sha256: "a".repeat(64), verification_basis: "provisional" } });
  f.preview.healthMonitor = { watch: (_source: unknown, callback: (value: any) => void) => { notify = callback; return { dispose() {} }; } };
  f.page.summary = { source_ref: { schema_version: 2, server_instance_id: "server", scope_id: "mirror", content: { kind: "file_version", master_store_id: "master", file_id: "file", file_version: "version" } } };
  const ready = f.send(); await tick();
  notify({ kind: "ok", health: { replica: { generation: "old", content_sha256: "a".repeat(64), verification_basis: "provisional" } } });
  notify({ kind: "ok", health: { replica: { generation: "new", content_sha256: "a".repeat(64), verification_basis: "provisional" } } });
  listing.resolve(); await ready; await tick();
  assert.equal(descriptions, 2, "joining the obsolete initialization must not swallow the refresh");
  assert.equal(f.messages.filter(m => m.type === "inventory").at(-1).inventory.replica.generation, "new");
});
for (const first of ["obsolete", "current"] as const) test(`hide/reveal invalidates initialization when ${first} catalog reply arrives first`, async t => {
  const f = fixture(t), a = f.send(); await tick(); f.hide(); f.reveal(); const b = f.send(); await tick();
  assert.equal(f.pending.length, 2, "reveal starts a fresh catalog generation");
  assert.equal(f.signals[0]?.aborted, true); assert.equal(f.signals[1]?.aborted, false);
  f.catalog(first === "obsolete" ? 0 : 1); await tick();
  if (first === "obsolete") assert.equal(f.owners.length, 0, "late identification cannot publish an owner");
  f.catalog(first === "obsolete" ? 1 : 0); await Promise.all([a, b]); await tick();
  assert.equal(f.owners.length, 1, "both replies must leave exactly one reachable owner");
  assert.equal(f.requests.filter(r => r.route === "/source/describe").length, 1);
  f.hide(); f.close(); await tick();
  assert.equal(f.budgets.decoded.reserved, 0); assert.equal(f.budgets.encoded.reserved, 0); assert.equal(f.timers.size, 0);
  assert.equal(f.requests.filter(r => r.route === "/source/inventory/close").length, 1);
  const before = f.requests.length; for (const fn of [...f.timers.values()]) fn(); await tick();
  assert.equal(f.requests.length, before, "closing leaves no owner able to refresh later");
});
for (const stop of ["hide", "close"] as const) test(`${stop} cancels initialization before identity or catalog can publish`, async t => {
  const f = fixture(t), identity = deferred<void>(); f.preview.identity = () => identity.promise;
  const ready = f.send(); await tick(); f[stop](); identity.resolve(); await tick();
  assert.equal(f.pending.length, 0, "obsolete identity cannot start a catalog read"); assert.equal(f.owners.length, 0);
  await ready;
});
test("ready during summary delivery stays paged and closing rejects delayed publication", async t => {
  const f = fixture(t), summary = deferred<boolean>();
  const post = f.panel.webview.postMessage; f.panel.webview.postMessage = m => m.type === "summary" ? summary.promise : post(m);
  const a = f.send(); await tick(); f.catalog(0); await tick(); const b = f.send(); await tick();
  f.close(); summary.resolve(true); await Promise.all([a, b]); await tick();
  assert.equal(f.requests.filter(r => r.route === "/source/describe").length, 0, "closed page cannot open an inventory after a renderer await");
  assert.equal(f.budgets.decoded.reserved, 0); assert.equal(f.timers.size, 0);
});
test("failed initialization can retry while an obsolete failure cannot affect the replacement", async t => {
  const f = fixture(t), a = f.send(); await tick(); f.pending[0].reject(new Error("catalog unavailable"));
  await assert.rejects(a, /catalog unavailable/); const b = f.send(); await tick();
  f.hide(); f.reveal(); const c = f.send(); await tick(); f.catalog(2); await c;
  f.pending[1].reject(new Error("obsolete failure")); await b; await tick();
  assert.equal(f.owners.length, 1); assert.equal(f.warnings.length, 0);
});

test("catalog identification forwards cancellation to the existing client", async () => {
  const abort = new AbortController(), calls: any[][] = [];
  const client = new RecordedClient(async () => ({ visualization: async (...args: any[]) => { calls.push(args); return { source_ref: source }; } } as any));
  await (client as any).identify("/synthetic/capture.wpilog", abort.signal);
  assert.equal(calls[0][0], "/source"); assert.deepEqual(calls[0][1], { path: "/synthetic/capture.wpilog" }); assert.equal(calls[0][2], abort.signal);
});
