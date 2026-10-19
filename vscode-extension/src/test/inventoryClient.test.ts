import { test } from "node:test";
import * as assert from "node:assert/strict";
import { InventoryClient, InventoryHandle } from "../visualization/inventoryClient";
import { ByteBudget } from "../visualization/byteBudget";
import { McpClient } from "../mcpClient";
import * as http from "node:http";
import { AddressInfo } from "node:net";
const source = { kind: "session", session_id: "s" };
function fixture() {
  const calls: { route: string; body: any; options: any }[] = [], budget = new ByteBudget();
  const summary = { inventory_mode: "paged", inventory_id: "i", server_epoch: "e", source_ref: source, expires_at: new Date(Date.now() + 300000).toISOString(), idle_expiry_ms: 60000,
    total_entry_names: 20000, conflicting_entry_names: 0, unavailable_entry_names: 0, members: [] };
  let reply: (route: string) => any = route => route === "/capabilities" ? { recording_inventory_paging: true } : route === "/source/describe" ? summary : {};
  const mcp = { visualization: async (route: string, body: any, _signal: any, options: any) => {
    calls.push({ route, body, options }); options.captureSession?.("session1"); assert.equal(budget.reserved, 2 << 20); return reply(route);
  } } as unknown as McpClient;
  const client = new InventoryClient(async () => mcp, budget);
  return { client, budget, calls, summary, setReply: (r: typeof reply) => { reply = r; } };
}
test("inventory negotiates paging before open, bounds every request and releases encoded reservations", async () => {
  const f = fixture(), h = await f.client.open(source, new AbortController().signal);
  assert.deepEqual(f.calls.map(c => c.route), ["/capabilities", "/source/describe"]);
  assert.equal(f.calls[1].body.inventory_mode, "paged"); assert.equal(h.session, "session1");
  for (const c of f.calls) { assert.equal(c.options.maxResponseBytes, 1 << 20); assert.equal(c.options.maxRequestBytes, 64 << 10); assert.equal(c.options.timeoutMs, 15000); }
  await f.client.close(h); assert.equal(f.calls.at(-1)?.options.session, "session1"); assert.equal(f.budget.reserved, 0);
});
for (const problem of ["unsupported", "missing echo", "foreign source", "full entries", "invalid expiry"] as const)
  test(`inventory refuses ${problem} without using a legacy inventory`, async () => {
    const f = fixture();
    if (problem === "unsupported") f.setReply(() => ({}));
    if (problem === "missing echo") Object.assign(f.summary, { inventory_mode: "legacy" });
    if (problem === "foreign source") Object.assign(f.summary, { source_ref: { ...source, session_id: "other" } });
    if (problem === "full entries") Object.assign(f.summary, { entries: [] });
    if (problem === "invalid expiry") f.summary.expires_at = "bad";
    await assert.rejects(f.client.open(source, new AbortController().signal), /update the server/);
    if (problem === "unsupported") assert.equal(f.calls.length, 1);
    else assert.equal(f.calls.at(-1)?.route, "/source/inventory/close");
    assert.equal(f.budget.reserved, 0);
  });
test("shared encoded budget refuses before requesting capabilities", async () => {
  const f = fixture(), release = f.budget.reserve(63 << 20);
  await assert.rejects(f.client.open(source, new AbortController().signal), /byte budget/); assert.equal(f.calls.length, 0); release();
});
test("cancelled late open closes its new handle in the original session", async () => {
  const f = fixture(), abort = new AbortController();
  f.setReply(route => { if (route === "/source/describe") abort.abort(); return route === "/capabilities" ? { recording_inventory_paging: true } : f.summary; });
  await assert.rejects(f.client.open(source, abort.signal), /cancelled/); assert.equal(f.calls.at(-1)?.route, "/source/inventory/close");
  assert.equal(f.calls.at(-1)?.options.session, "session1"); assert.equal(f.budget.reserved, 0);
});
test("cancelling an inventory open after server admission still receives and closes the opaque handle", async t => {
  const f = fixture(), held = new Set<string>(), closed: string[] = [];
  let admitted!: () => void, deliver!: () => void;
  const entered = new Promise<void>(resolve => admitted = resolve);
  const server = http.createServer((request, response) => {
    let body = ""; request.on("data", chunk => body += chunk); request.on("end", () => {
      const value = body ? JSON.parse(body) : {};
      if (request.url === "/mcp") {
        if (value.method === "initialize") response.setHeader("Mcp-Session-Id", "owner");
        response.end(JSON.stringify({ jsonrpc: "2.0", id: value.id, result: {} }));
      } else if (request.url === "/visualizations/v1/capabilities") response.end('{"recording_inventory_paging":true}');
      else if (request.url === "/visualizations/v1/source/describe") {
        held.add(f.summary.inventory_id); deliver = () => response.end(JSON.stringify(f.summary)); admitted();
      } else if (request.url === "/visualizations/v1/source/inventory/close") {
        assert.equal(request.headers["mcp-session-id"], "owner"); closed.push(value.inventory_id); held.delete(value.inventory_id); response.end("{}");
      } else { response.statusCode = 404; response.end("{}"); }
    });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const mcp = new McpClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, "inventory-cancellation");
  t.after(async () => { await mcp.dispose(); server.closeAllConnections(); server.close(); });
  const budget = new ByteBudget(), client = new InventoryClient(async () => mcp, budget), abort = new AbortController();
  const opened = client.open(source, abort.signal); void opened.catch(() => {});
  await entered; assert.equal(held.size, 1); abort.abort(); deliver();
  await assert.rejects(opened, /cancel|abort/i);
  assert.deepEqual(closed, [f.summary.inventory_id], "logical cancellation must reclaim an already-created server handle");
  assert.equal(held.size, 0); assert.equal(budget.reserved, 0);
});
test("cancellation after capabilities prevents a new inventory admission", async () => {
  const f = fixture(), abort = new AbortController();
  f.setReply(() => { abort.abort(); return { recording_inventory_paging: true }; });
  await assert.rejects(f.client.open(source, abort.signal), /cancel|abort/i);
  assert.deepEqual(f.calls.map(c => c.route), ["/capabilities"]); assert.equal(f.budget.reserved, 0);
});
test("inventory list validates rows, cursor and exact sample counts before use", async () => {
  const f = fixture(), h = await f.client.open(source, new AbortController().signal);
  const valid: any = { entries: [{ row_id: "r", name: "/x", type: "int64", unavailable: false, conflict: false, sample_count: "9007199254740993", members: [] }],
    inventory_id: "i", server_epoch: "e", total_matching: 1, total_entry_names: 20000, unavailable_excluded: true, unavailable_entry_names: 0, complete: true, next_cursor: null, returned: 1 };
  f.setReply(() => valid); const signal = new AbortController().signal, filter = { query: "/x", types: [], unavailable: false };
  assert.equal((await f.client.list(h, filter, undefined, signal)).entries[0].sample_count, "9007199254740993");
  await f.client.list(h, filter, "cursor", signal); assert.deepEqual(f.calls.at(-1)?.body, { inventory_id: "i", cursor: "cursor" });
  for (const mutate of [(v: any) => v.returned = 2, (v: any) => v.complete = false, (v: any) => v.entries[0].sample_count = 9007199254740992,
    (v: any) => { v.entries[0].unavailable = "false"; v.entries[0].display_preview = "/x"; v.entries[0].reason = "fixture"; }, (v: any) => v.inventory_id = "foreign", (v: any) => v.server_epoch = "other", (v: any) => v.entries = Array(257).fill(v.entries[0])]) {
    const value = structuredClone(valid); mutate(value); f.setReply(() => value);
    await assert.rejects(f.client.list(h, filter, undefined, signal), /Invalid paged/);
  }
});
test("selected details must describe the exact selected row with fresh evidence", async () => {
  const f = fixture(), h = await f.client.open(source, new AbortController().signal);
  const r = { row_id: "r", name: "/x", type: "int64", sample_count: "2", members: [], unavailable: false, conflict: false };
  const valid = { inventory_id: "i", server_epoch: "e", selected_rows: [r], count_basis: "fresh_admission", counts_grew_since_inventory: false, detail_revision: "d", descriptors: [], schema_problems: [] };
  f.setReply(() => valid); assert.equal((await f.client.details(h, r, new AbortController().signal)).detail_revision, "d");
  f.setReply(() => ({ ...valid, selected_rows: [{ ...r, name: "/other" }] }));
  await assert.rejects(f.client.details(h, r, new AbortController().signal), /Invalid paged/);
});

test("unavailable traversal omits ordinary filters without losing them for the return to searchable rows", async () => {
  const f = fixture(), h = await f.client.open(source, new AbortController().signal), signal = new AbortController().signal;
  f.setReply(() => ({ inventory_id: "i", server_epoch: "e", entries: [], returned: 0, total_matching: 0, total_entry_names: 20000,
    unavailable_excluded: false, unavailable_entry_names: 0, complete: true, next_cursor: null }));
  const filter = { query: "literal.*", types: ["struct:Pose2d"], unavailable: true };
  await f.client.list(h, filter, undefined, signal);
  assert.deepEqual(f.calls.at(-1)?.body, { inventory_id: "i", unavailable: true, limit: 256 }, "unavailable previews cannot be filtered by full name or type");
  await f.client.list(h, { ...filter, unavailable: false }, undefined, signal);
  assert.deepEqual(f.calls.at(-1)?.body, { inventory_id: "i", query: "literal.*", types: ["struct:Pose2d"], unavailable: false, limit: 256 });
});
