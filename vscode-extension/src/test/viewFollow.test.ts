import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import { FollowClient } from "../visualization/followClient";
import { FollowError } from "../visualization/followProtocol";
import { LiveBroker } from "../visualization/liveBroker";

const source = { kind: "session" as const, origin_store_id: "11111111-1111-4111-8111-111111111111",
  session_id: "22222222-2222-4222-8222-222222222222", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
const view = { view_id: "33333333-3333-4333-8333-333333333333", expected_revision: 7 };
function response(): any {
  return { api_version: 1, id: "lease", server_epoch: "epoch", cursor: "c0", idle_timeout_sec: 60, max_wait_ms: 25000,
    source_ref: source, frontier_scope: "queries", max_page_bytes: 1 << 20,
    view_binding: { api_version: 1, view_id: view.view_id, revision: 7, source_ref: source, frontier_scope: "queries", max_page_bytes: 1 << 20,
      clock: { basis: "last_sample", right_edge_sec: 2, robot_time_sec: null, last_sample_time_sec: 2, waiting_for_time_sync: true },
      time_policy: { mode: "follow", window_sec: 30 }, follow_ready: true, reason: "Pinned",
      compatibility: { compatibility: "partial", bindings: [
        { requirement_id: "a", state: "resolved", entry: "x", field_path: null, reason: "Explicit", warnings: [] },
        { requirement_id: "b", state: "unresolved", entry: null, field_path: null, reason: "Absent", warnings: [] }] },
      queries: [{ entry: "x", windows: [{ start_us: "-28000000", end_us: "2000000", end_inclusive: true }], order: "file", batch_records: 4096, batch_bytes: 1 << 20 }] } };
}
async function server(t: any, mutate: (value: any) => void = () => {}) {
  const requests: any[] = [], deletes: string[] = [];
  const s = http.createServer((req, res) => {
    let data = ""; req.on("data", b => data += b); req.on("end", () => {
      if (req.method === "DELETE") { deletes.push(req.url!); res.writeHead(204).end(); return; }
      requests.push({ body: JSON.parse(data), session: req.headers['mcp-session-id'] });
      const value = response(); mutate(value); res.writeHead(201).end(JSON.stringify(value));
    });
  });
  await new Promise<void>(r => s.listen(0, "127.0.0.1", r));
  t.after(() => { s.closeAllConnections(); s.close(); });
  return { requests, deletes, client: new FollowClient(`http://127.0.0.1:${(s.address() as AddressInfo).port}/mcp`, async () => ({ 'Mcp-Session-Id': 'owned' }), 1 << 20, "queries") };
}
test("view follow opens only the expected revision and uses the returned server queries", async t => {
  const f = await server(t);
  const lease = await (f.client as any).openView(source, view, new AbortController().signal);
  assert.deepEqual(f.requests, [{ body: { view_binding: view }, session: "owned" }]);
  assert.equal(lease.view_binding.revision, 7); assert.equal(lease.view_binding.queries[0].entry, "x");
  assert.equal(lease.view_binding.compatibility.bindings[1].state, "unresolved");
  assert.equal(lease.frontier_scope, "queries"); assert.ok(Object.isFrozen(lease.view_binding.queries));
  await f.client.close(lease); assert.equal(f.deletes.length, 1);
});
for (const bad of ["view", "revision", "source", "page", "scope", "query", "duplicate", "window", "not ready"]) test(`view follow rejects ${bad} mismatch and releases its created lease`, async t => {
  const f = await server(t, v => {
    const b = v.view_binding;
    if (bad === "view") b.view_id = "44444444-4444-4444-8444-444444444444";
    if (bad === "revision") b.revision++;
    if (bad === "source") b.source_ref = { ...source, session_id: "other" };
    if (bad === "page") v.max_page_bytes = 4 << 20;
    if (bad === "scope") b.frontier_scope = "all";
    if (bad === "query") b.queries[0].entry = "unrequested";
    if (bad === "duplicate") b.queries.push(b.queries[0]);
    if (bad === "window") b.queries[0].windows[0].end_us = "1.5";
    if (bad === "not ready") b.follow_ready = false;
  });
  await assert.rejects((f.client as any).openView(source, view, new AbortController().signal), (e: unknown) => e instanceof FollowError && e.code === "INVALID_RESPONSE");
  assert.deepEqual(f.deletes, ["/read/v1/follows/lease"]);
});
test("bound broker uses direct admission, publishes binding before pages and leaves a peer uninterrupted", async () => {
  const opens: any[] = [], closed: string[] = [], events: string[] = [];
  const transport: any = { pageBytes: 1 << 20, frontierScope: "queries", open: () => assert.fail("No raw open or separate binding check"),
    openView: async (_source: unknown, request: any) => { opens.push(request); return { ...response(), ...{ view_binding: { ...response().view_binding, view_id: request.view_id } }, id: 'lease' + opens.length }; },
    next: async (l: any, cursor: string, signal: AbortSignal) => {
      if (cursor !== "c0") return new Promise((_, reject) => signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true }));
      return { api_version: 1, server_epoch: "epoch", follow_id: l.id, cursor, next_cursor: "c1", prefix_id: "p", bootstrap: true, frontier_complete: true, ended: false,
        frontier: { clock: response().view_binding.clock, recording_id: "r", catalog_revision: "1", finalized: false, frontier_scope: "queries", members: [{ member_id: "m", generation: "1", snapshot: { entries: { x: {} }, sample_counts: { x: "1" } } }] },
        batches: [{ member_id: "m", entry: "x", field: null, next_ordinal: 1, batch: { kind: "batch", records_visited: 1, payload_bytes: "8", complete: true, problems: [],
          samples: [{ ordinal: 0, timestamp_us: "2000000", record_offset: "30", payload_bytes: "8", value: { type: "i64", value: "9007199254740993" } }] } }] };
    }, close: async (l: any) => { closed.push(l.id); } };
  const broker = new LiveBroker(transport), peers: any[] = [];
  const subscribe = (id: string) => (broker as any).subscribeView(source, { ...view, view_id: id }, {
    identity: { viewId: id, revision: 7, generation: 1, queryKey: "binding" }, current: () => true,
    state: (s: any) => events.push(id + ":" + s.kind), binding: (b: any) => { assert.equal(b.view_id, id); events.push(id + ":binding"); },
    page: (p: any) => { assert.deepEqual(p.page.batches[0].batch.samples[0].value, { type: "i64", value: "9007199254740993" }); events.push(id + ":page"); } });
  try {
    peers.push(subscribe(view.view_id)); await new Promise(r => setTimeout(r, 15));
    const first = [...events]; peers.push(subscribe("44444444-4444-4444-8444-444444444444")); await new Promise(r => setTimeout(r, 15));
    assert.equal(opens.length, 2); assert.deepEqual(events.filter(e => e.startsWith(view.view_id)), first); assert.equal(closed.length, 0);
    assert.ok(first.includes(view.view_id + ":binding")); assert.ok(first.includes(view.view_id + ":page"));
    assert.ok(first.indexOf(view.view_id + ":binding") < first.indexOf(view.view_id + ":page"));
    assert.equal(broker.resources.reservedBytes, 4 << 20);
    peers[1].dispose(); await new Promise(r => setTimeout(r, 10)); assert.deepEqual(closed, ["lease2"]);
  } finally { peers.forEach(p => p.dispose()); await broker.dispose(); }
  assert.equal(broker.resources.reservedBytes, 0); assert.deepEqual(closed.sort(), ["lease1", "lease2"]);
});

test("bound broker refuses a decoded-budget overflow once and releases the lease", async () => {
  const { ByteBudget } = await import("../visualization/byteBudget");
  const budget = new ByteBudget(1), states: any[] = []; let closes = 0, bindings = 0;
  const transport: any = { pageBytes: 1 << 20, frontierScope: "queries", openView: async () => response(),
    next: async () => { throw new FollowError("END", "Unexpected next"); }, close: async () => { closes++; } };
  const broker = new LiveBroker(transport, { decodedBudget: budget } as any);
  broker.subscribeView(source, view, { identity: { viewId: view.view_id, revision: 7, generation: 1, queryKey: "q" }, current: () => true,
    binding: () => { bindings++; }, page: () => {}, state: s => states.push(s) });
  await new Promise(r => setTimeout(r, 25)); await broker.dispose();
  assert.equal(bindings, 0); assert.equal(closes, 1); assert.equal(budget.reserved, 0);
  assert.deepEqual(states.filter(s => s.kind === "error").map(s => s.code), ["CLIENT_BUDGET"]);
});
test("a bound follow refuses a prefix without recorded-clock evidence", async () => {
  const states: any[] = []; let delivered = 0;
  const transport: any = { pageBytes: 1 << 20, frontierScope: "queries", openView: async () => response(),
    next: async (lease: any, cursor: string) => ({ api_version: 1, server_epoch: "epoch", follow_id: lease.id, cursor, next_cursor: "c1", prefix_id: "p", bootstrap: true, frontier_complete: true, ended: true,
      frontier: { recording_id: "r", catalog_revision: "1", finalized: true, frontier_scope: "queries", members: [{ member_id: "m", generation: "1", snapshot: { entries: { x: {} }, sample_counts: { x: "0" } } }] }, batches: [] }), close: async () => {} };
  const broker = new LiveBroker(transport);
  broker.subscribeView(source, view, { identity: { viewId: view.view_id, revision: 7, generation: 1, queryKey: "q" }, current: () => true,
    page: () => { delivered++; }, state: s => states.push(s) });
  await new Promise(r => setTimeout(r, 20)); await broker.dispose();
  assert.equal(delivered, 0); assert.deepEqual(states.filter(s => s.kind === "error").map(s => s.code), ["INVALID_RESPONSE"]);
});
