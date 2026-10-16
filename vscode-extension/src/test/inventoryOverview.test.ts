import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ByteBudget } from "../visualization/byteBudget";
import { InventoryOverview, OverviewClock, OverviewState } from "../visualization/inventoryOverview";
import { InventoryBackend, InventoryDetails, InventoryFilter, InventoryHandle, InventoryPage, InventoryRow } from "../visualization/inventoryClient";
import { LocalControlError } from "../mcpClient";

const source = { kind: "session", session_id: "recording", origin_store_id: "store", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
function row(name: string, id = name): InventoryRow { return { row_id: id, name, type: "int64", sample_count: "3", conflict: false, unavailable: false, members: [{ member_id: "a", type: "int64", sample_count: "3" }] }; }
function page(names: string[], next: string | null = null): InventoryPage { return { entries: names.map(n => row(n)), next_cursor: next, complete: !next, returned: names.length, total_matching: 20000, total_entry_names: 20000, unavailable_excluded: true, unavailable_entry_names: 0 }; }
function details(r: InventoryRow): InventoryDetails { return { selected_rows: [r], detail_revision: "fresh", count_basis: "fresh_admission", counts_grew_since_inventory: false, descriptors: [], schema_problems: [] }; }
class Clock implements OverviewClock {
  value = 100000; timers = new Map<object, { at: number; fn(): void }>();
  now = () => this.value;
  later(fn: () => void, ms: number) { const id = {}; this.timers.set(id, { at: this.value + ms, fn }); return id; }
  cancel(id: unknown) { this.timers.delete(id as object); }
  advance(ms: number) { this.value += ms; for (const [id, timer] of [...this.timers]) if (timer.at <= this.value) { this.timers.delete(id); timer.fn(); } }
}
class Backend implements InventoryBackend {
  opened: InventoryHandle[] = []; closed: string[] = []; calls: { id: string; filter: InventoryFilter; cursor?: string }[] = []; inspected: string[] = [];
  failure?: Error; listing = async (_h: InventoryHandle, _f: InventoryFilter, c?: string): Promise<InventoryPage> => c ? page(["/second"], "c2") : page(["/first"], "c1");
  constructor(readonly clock: Clock) {}
  async open(): Promise<InventoryHandle> {
    if (this.failure) throw this.failure;
    const h = { summary: { inventory_mode: "paged", inventory_id: `i${this.opened.length}`, server_epoch: "epoch", source_ref: source,
      total_entry_names: 20000, conflicting_entry_names: 0, unavailable_entry_names: 0, members: [{}, {}], idle_expiry_ms: 60000,
      expires_at: new Date(this.clock.now() + 300000).toISOString() }, session: "client", client: {} } as unknown as InventoryHandle;
    this.opened.push(h); return h;
  }
  async list(h: InventoryHandle, f: InventoryFilter, c?: string): Promise<InventoryPage> { this.calls.push({ id: h.summary.inventory_id, filter: f, cursor: c }); return this.listing(h, f, c); }
  async details(_h: InventoryHandle, r: InventoryRow): Promise<InventoryDetails> { this.inspected.push(r.row_id); return details(r); }
  async close(h: InventoryHandle) { this.closed.push(h.summary.inventory_id); }
}
function fixture(t: any, budget = new ByteBudget()) {
  const clock = new Clock(), backend = new Backend(clock), states: OverviewState[] = [];
  const view = new InventoryOverview(source, backend, budget, s => states.push(s), clock);
  t.after(() => view.dispose()); return { clock, backend, view, states, budget };
}
const tick = () => new Promise<void>(r => setImmediate(r));
async function settle(view: InventoryOverview) { for (let i = 0; i < 30; i++) { await tick(); if (!view.snapshot.loading) return; } throw new Error("view did not settle"); }

test("overview opens only one bounded page and keeps at most two during forward and backward navigation", async t => {
  const f = fixture(t); await f.view.show();
  assert.equal(f.backend.calls.length, 1); assert.equal(f.view.snapshot.summary?.total_entry_names, 20000);
  for (let i = 0; i < 7; i++) { await f.view.navigate(1, f.view.snapshot.generation); assert.ok(f.view.retainedPages <= 2); }
  assert.equal(f.view.snapshot.pageNumber, 8); assert.equal(f.backend.calls.length, 8);
  await f.view.navigate(-1, f.view.snapshot.generation); assert.equal(f.view.snapshot.pageNumber, 7);
  assert.equal(f.backend.calls.at(-1)?.cursor, "c2", "Previous revalidates on the server");
  assert.ok(f.budget.reserved > 0); await f.view.hide(); assert.equal(f.budget.reserved, 0); assert.equal(f.view.retainedPages, 0);
});
test("filter and selection are exact names; unavailable rows and stale UI generations cannot select", async t => {
  const f = fixture(t); await f.view.show(); const old = f.view.snapshot.generation;
  await f.view.filter("Needle", "struct:Pose2d");
  assert.deepEqual(f.backend.calls.at(-1)?.filter, { query: "Needle", types: ["struct:Pose2d"], unavailable: false });
  await f.view.select("/first", old); assert.equal(f.backend.inspected.length, 0);
  await f.view.select("/FIRST", f.view.snapshot.generation); assert.equal(f.backend.inspected.length, 0);
  await f.view.select("/first", f.view.snapshot.generation); assert.deepEqual(f.backend.inspected, ["/first"]);
  assert.equal(f.view.snapshot.details?.count_basis, "fresh_admission");
});
test("visible hard expiry opens a replacement once, preserves filters and revalidates selected row IDs", async t => {
  const f = fixture(t); f.backend.listing = async (h, filter) => ({ ...page(["/first"]), entries: [row("/first", h.summary.inventory_id + filter.query)] });
  await f.view.show(); await f.view.filter("fir", "int64"); await f.view.select("/first", f.view.snapshot.generation);
  f.clock.advance(300000); await settle(f.view);
  assert.equal(f.backend.opened.length, 2); assert.deepEqual(f.backend.closed, ["i0"]);
  assert.equal(f.view.snapshot.selected, "/first"); assert.equal(f.view.snapshot.filter.query, "fir");
  assert.deepEqual(f.backend.inspected, ["i0fir", "i1fir"]); assert.equal(f.view.snapshot.details?.selected_rows[0].row_id, "i1fir");
  assert.ok(f.view.snapshot.openedAt! >= 400000);
});
test("failed refresh preserves valid rows, marks expired rows and backs off instead of spinning", async t => {
  const f = fixture(t); await f.view.show(); const original = f.view.snapshot.page;
  f.backend.failure = new Error("capacity exhausted"); await f.view.refresh();
  assert.equal(f.view.snapshot.page, original); assert.equal(f.view.snapshot.expired, false); assert.match(f.view.snapshot.reason!, /capacity/);
  assert.equal(f.backend.closed.length, 0);
  f.clock.advance(300000); await settle(f.view);
  assert.equal(f.view.snapshot.page, original); assert.equal(f.view.snapshot.expired, true); assert.equal(f.clock.timers.size, 0);
  await f.view.refresh(); assert.equal(f.backend.opened.length, 1);
  f.clock.advance(30000); f.backend.failure = undefined; await f.view.refresh(); assert.equal(f.backend.opened.length, 2);
});
test("replacement closes on failed first page while the admitted old inventory survives", async t => {
  const f = fixture(t); await f.view.show(); f.backend.listing = async () => { throw new Error("first page refused"); };
  await f.view.refresh(); assert.deepEqual(f.backend.closed, ["i1"]); assert.equal(f.view.snapshot.summary?.inventory_id, "i0");
});
test("hide cancels a late response, releases the handle and memory, and reveal opens fresh", async t => {
  const f = fixture(t); await f.view.show();
  let finish!: (p: InventoryPage) => void;
  f.backend.listing = () => new Promise(r => finish = r);
  const pending = f.view.navigate(1, f.view.snapshot.generation); await tick(); const hidden = f.view.hide();
  finish(page(["/late"])); await pending; await hidden;
  assert.equal(f.view.snapshot.page, undefined); assert.deepEqual(f.backend.closed, ["i0"]); assert.equal(f.budget.reserved, 0);
  f.backend.listing = async () => page(["/fresh"]); await f.view.show(); assert.equal((f.view.snapshot as OverviewState).page?.entries[0].name, "/fresh");
  assert.equal(f.backend.opened.length, 2); assert.equal(f.clock.timers.size, 1);
});
test("new filter rejects the obsolete reply before issuing its replacement request", async t => {
  const f = fixture(t); await f.view.show(); let finish!: (p: InventoryPage) => void;
  f.backend.listing = () => new Promise(r => finish = r);
  const a = f.view.filter("a", ""); await tick(); const b = f.view.filter("b", "");
  assert.equal(f.backend.calls.length, 2, "one list/detail request in flight");
  f.backend.listing = async () => page(["/b"]); finish(page(["/a"])); await a; await b;
  assert.equal(f.view.snapshot.page?.entries[0].name, "/b");
  assert.ok(!f.states.some(s => s.page?.entries[0].name === "/a"));
});
test("expiry race reopens instead of replaying a cursor, and repeated admission failure stops", async t => {
  const f = fixture(t); await f.view.show();
  f.backend.listing = async h => { if (h.summary.inventory_id === "i0") throw new LocalControlError("expired", 410, "INVENTORY_EXPIRED"); return page(["/new"]); };
  await f.view.navigate(1, f.view.snapshot.generation); await settle(f.view);
  assert.equal(f.backend.opened.length, 2); assert.equal(f.view.snapshot.page?.entries[0].name, "/new");
  assert.equal(f.backend.calls.at(-1)?.cursor, undefined);
});
test("selection outside the refreshed page resolves by name and reads fresh details, never row position", async t => {
  const f = fixture(t); await f.view.show(); await f.view.select("/first", f.view.snapshot.generation);
  f.backend.listing = async (h, filter) => filter.query === "/first" ? { ...page(["/first"]), entries: [row("/first", h.summary.inventory_id + ":selected")] } : page(["/other"]);
  await f.view.refresh(); assert.equal(f.view.snapshot.page?.entries[0].name, "/other");
  assert.equal(f.view.snapshot.details?.selected_rows[0].row_id, "i1:selected"); assert.equal(f.view.snapshot.selected, "/first");
});
test("decoded budget refuses rows before display and closes the unadmitted handle", async t => {
  const f = fixture(t, new ByteBudget(100)); await f.view.show();
  assert.equal(f.view.snapshot.page, undefined); assert.match(f.view.snapshot.reason!, /byte budget/);
  assert.deepEqual(f.backend.closed, ["i0"]); assert.equal(f.budget.reserved, 0);
});
test("simultaneous refresh actions share one replacement", async t => {
  const f = fixture(t); await f.view.show(); await Promise.all([f.view.refresh(), f.view.refresh(), f.view.refresh()]);
  assert.equal(f.backend.opened.length, 2); assert.deepEqual(f.backend.closed, ["i0"]);
});

test("failed filter keeps the admitted filter with the previous readable rows", async t => {
  const f = fixture(t); await f.view.show();
  f.backend.listing = async () => { throw new Error("filter refused"); };
  await f.view.filter("different", "double");
  assert.equal(f.view.snapshot.filter.query, ""); assert.deepEqual(f.view.snapshot.filter.types, []);
  assert.equal(f.view.snapshot.page?.entries[0].name, "/first");
});

test("rapid reveal during a cancelled refresh opens fresh after the late admission unwinds", async t => {
  const f = fixture(t); await f.view.show();
  const open = f.backend.open.bind(f.backend); let release!: () => void;
  f.backend.open = async () => { const h = await open(); await new Promise<void>(r => release = r); return h; };
  const refresh = f.view.refresh(); await tick(); const hide = f.view.hide();
  f.backend.open = open; const show = f.view.show(); release();
  await Promise.all([refresh, hide, show]);
  assert.equal(f.backend.opened.length, 3); assert.equal(f.view.snapshot.summary?.inventory_id, "i2");
  assert.equal(f.view.snapshot.hidden, false); assert.equal(f.view.snapshot.page?.entries[0].name, "/first");
  assert.deepEqual(new Set(f.backend.closed), new Set(["i0", "i1"]));
});

test("off-page selection lookup respects the server's 256-codepoint query limit while matching the full name", async t => {
  const f = fixture(t), name = "/" + "😀".repeat(300);
  f.backend.listing = async () => page([name]); await f.view.show(); await f.view.select(name, f.view.snapshot.generation);
  f.backend.listing = async (_h, filter) => {
    if (!filter.query) return page(["/other"]);
    assert.equal([...filter.query].length, 256, "bounded query uses a prefix of the name");
    return { ...page([name]), entries: [row(name, "fresh-long-name")] };
  };
  await f.view.refresh();
  assert.equal(f.view.snapshot.details?.selected_rows[0].row_id, "fresh-long-name"); assert.equal(f.view.snapshot.selected, name);
});

for (const code of ["INVENTORY_EXPIRED", "INVENTORY_CHANGED", "SESSION_EXPIRED"]) test(`${code} stays invalid after a refused reopen until a replacement page is admitted`, async t => {
  const f = fixture(t); await f.view.show(); await f.view.select("/first", f.view.snapshot.generation);
  const original = f.view.snapshot.page, expiry = Date.parse(f.view.snapshot.summary!.expires_at);
  f.clock.advance(61000); assert.ok(f.clock.now() < expiry, "idle/session invalidation precedes the absolute deadline");
  f.backend.failure = new LocalControlError("Inventory capacity busy", 413, "RESOURCE_LIMIT", true);
  f.backend.listing = async () => { throw new LocalControlError("Inventory invalid", 410, code, true); };
  await f.view.navigate(1, f.view.snapshot.generation); await settle(f.view);
  assert.equal(f.view.snapshot.expired, true, "a capacity refusal cannot revive a known-invalid snapshot");
  assert.equal(f.view.snapshot.page, original); assert.equal(f.view.snapshot.selected, "/first");
  assert.match(f.view.snapshot.reason!, /capacity/); assert.equal(f.clock.timers.size, 0, "no reopen loop");
  const calls = f.backend.calls.length, inspected = f.backend.inspected.length;
  await f.view.navigate(1, f.view.snapshot.generation); await f.view.select("/first", f.view.snapshot.generation); await f.view.filter("new", "");
  assert.equal(f.backend.calls.length, calls); assert.equal(f.backend.inspected.length, inspected);
  f.clock.advance(30000); f.backend.failure = undefined; f.backend.listing = async () => page(["/first"]);
  await f.view.refresh(); assert.equal(f.view.snapshot.expired, false); assert.equal(f.view.snapshot.summary!.inventory_id, "i1");
});
test("invalidation during replacement details remains known without scheduling another automatic reopen", async t => {
  const f = fixture(t); await f.view.show(); await f.view.select("/first", f.view.snapshot.generation);
  f.backend.details = async () => { throw new LocalControlError("Changed since listing", 409, "INVENTORY_CHANGED", true); };
  await f.view.refresh(); await settle(f.view);
  assert.equal(f.view.snapshot.expired, true); assert.equal(f.clock.timers.size, 0); assert.equal(f.backend.opened.length, 2);
  assert.equal(f.view.snapshot.selected, "/first"); assert.match(f.view.snapshot.reason!, /Changed since listing/);
});
