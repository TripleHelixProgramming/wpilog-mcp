import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import { FollowClient, FOLLOW_PAGE_BYTES } from "../visualization/followClient";
import { FollowError, FollowLease, freezeJson, page } from "../visualization/followProtocol";
const lease: FollowLease = { id: "lease", server_epoch: "epoch", cursor: "c0" };
async function server(t: any, handler: http.RequestListener) {
  const s = http.createServer(handler); await new Promise<void>(r => s.listen(0, "127.0.0.1", r));
  t.after(() => { s.closeAllConnections(); s.close(); }); return `http://127.0.0.1:${(s.address() as AddressInfo).port}/mcp`;
}
for (const kind of ["windows", "utf8", "cursor"]) test(`HTTP follow refuses oversized ${kind} before credentials or network`, async t => {
  const source = { kind: "session" as const, origin_store_id: "store", session_id: "session", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
  let requests = 0, credentials = 0;
  const endpoint = await server(t, (req, res) => {
    requests++; req.resume(); req.on("end", () => {
      if (kind === "cursor") { res.statusCode = 204; res.end(); }
      else { res.statusCode = 201; res.end(JSON.stringify({ api_version: 1, id: "lease", server_epoch: "epoch", cursor: "c0", idle_timeout_sec: 60, max_wait_ms: 25000, source_ref: source })); }
    });
  });
  const client = new FollowClient(endpoint, async () => { credentials++; return {}; });
  const windows = kind === "windows" ? Array.from({ length: 1000 }, () => ({ start_us: "1000000000000000000", end_us: "1000000000000000001", end_inclusive: false })) : [];
  const call = kind === "cursor" ? client.next(lease, "x".repeat(65536), new AbortController().signal)
    : client.open(source, [{ entry: kind === "utf8" ? "é".repeat(33000) : "x" }], windows, new AbortController().signal);
  await assert.rejects(call, (e: unknown) => e instanceof FollowError && e.code === "RESOURCE_LIMIT" && /64 KiB/.test(e.message) && !e.retryable);
  assert.equal(credentials, 0); assert.equal(requests, 0);
});
test("HTTP follow accepts exactly 64 KiB of encoded UTF-8", async t => {
  const source = { kind: "session" as const, origin_store_id: "store", session_id: "session", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
  // The oracle uses the wire contract, independently of the production serializer.
  const body = { source_ref: source, queries: [{ entry: "", order: "file", windows: [] }], max_records: 4096, max_page_bytes: 1048576 };
  const padding = 65536 - Buffer.byteLength(JSON.stringify(body));
  body.queries[0].entry = "é".repeat(Math.floor(padding / 2)) + "x".repeat(padding % 2);
  let received = 0;
  const endpoint = await server(t, (req, res) => {
    req.on("data", (chunk: Buffer) => received += chunk.length); req.on("end", () => {
      res.statusCode = 201; res.end(JSON.stringify({ api_version: 1, id: "lease", server_epoch: "epoch", cursor: "c0", idle_timeout_sec: 60, max_wait_ms: 25000, source_ref: source }));
    });
  });
  const client = new FollowClient(endpoint, undefined, 1048576);
  await client.open(source, [{ entry: body.queries[0].entry }], [], new AbortController().signal);
  assert.equal(received, 65536);
  await assert.rejects(client.open(source, [{ entry: body.queries[0].entry + "x" }], [], new AbortController().signal), /64 KiB/);
  assert.equal(received, 65536);
});
test("HTTP follow cancellation disconnects a pending long poll", { timeout: 1000 }, async t => {
  let entered!: () => void; const seen = new Promise<void>(r => entered = r);
  let closed!: () => void; const gone = new Promise<void>(r => closed = r);
  const endpoint = await server(t, (_req, res) => { res.on("close", closed); entered(); });
  const client = new FollowClient(endpoint), controller = new AbortController();
  const pending = client.next(lease, "c0", controller.signal); await seen;
  controller.abort(); await assert.rejects(pending, /cancel/i); await gone;
});
test("HTTP follows bound the response before parsing it", async t => {
  const endpoint = await server(t, (_req, res) => res.end(Buffer.alloc(1025)));
  await assert.rejects(new FollowClient(endpoint, undefined, 1024).next(lease, "c0", new AbortController().signal), /byte limit/);
});
for (const [status, body, code, retryable] of [
  [413, '{"code":"RESOURCE_LIMIT","message":"pressure","retryable":true}', "RESOURCE_LIMIT", true],
  [413, '{"code":"RESOURCE_LIMIT","message":"fixed","retryable":false}', "RESOURCE_LIMIT", false],
  [413, '{"code":"RESOURCE_LIMIT","message":"old server"}', "INVALID_RESPONSE", false],
  [429, 'busy proxy', "BUSY", true], [503, 'unavailable proxy', "BUSY", true],
  [401, 'Authentication required', "ACCESS_DENIED", false],
  [403, 'Forbidden', "ACCESS_DENIED", false],
  [410, '{"code":"CURSOR_EXPIRED","message":"old lease"}', "CURSOR_EXPIRED", false],
] as const) test(`HTTP ${status} classifies ${code}/${retryable} without message guessing`, async t => {
  const endpoint = await server(t, (_req, res) => { res.statusCode = status; res.end(body); });
  await assert.rejects(new FollowClient(endpoint).next(lease, "c0", new AbortController().signal), (e: any) => e instanceof FollowError && e.code === code && e.retryable === retryable);
});
test("credentials stay on the configured origin; redirects are not followed", async t => {
  let calls = 0, authorization: string | undefined, requested = "";
  const endpoint = await server(t, (req, res) => { calls++; authorization = req.headers.authorization;
    res.statusCode = 302; res.setHeader("Location", "http://outside.invalid/read/v1/follows"); res.end('{}'); });
  const client = new FollowClient(endpoint, async url => { requested = url; return { Authorization: "Basic synthetic" }; });
  await assert.rejects(client.next(lease, "c0", new AbortController().signal));
  assert.equal(calls, 1); assert.equal(authorization, "Basic synthetic"); assert.equal(new URL(requested).origin, new URL(endpoint).origin);
  assert.throws(() => new FollowClient("http://user:secret@localhost/mcp"), /credentials/);
});
function valid(): any {
  return { api_version: 1, server_epoch: "epoch", follow_id: "lease", cursor: "c0", next_cursor: "c1", prefix_id: "p",
    bootstrap: true, frontier_complete: true, ended: false,
    frontier: { recording_id: "r", catalog_revision: "0", finalized: false, members: [{ member_id: "m", generation: "0", snapshot: { entries: {}, sample_counts: {} } }] },
    batches: [{ member_id: "m", entry: "x", field: null, next_ordinal: 1, batch: { kind: "batch", samples: [],
      problems: [{ code: "RESOURCE_LIMIT", message: "omitted", entry: "x", ordinal: 0, record_offset: 80, payload_bytes: "40000" }], records_visited: 1, payload_bytes: "40000", complete: true } }] };
}
test("wire keeps a native oversized-record gap and its safe numeric offset", () => {
  const checked = page(valid(), lease, "c0", new Set(['["x",null]']));
  assert.deepEqual(checked.page.batches[0].batch.problems[0], valid().batches[0].batch.problems[0]);
});
test("wire accepts and freezes a full page of 4096 nineteen-element double arrays", t => {
  const v = valid(), b = v.batches[0];
  b.next_ordinal = 4096;
  b.batch = { kind: "batch", records_visited: 4096, payload_bytes: String(4096 * 19 * 8), complete: true, problems: [],
    samples: Array.from({ length: 4096 }, (_, n) => ({ ordinal: n, timestamp_us: String(n), record_offset: String(n * 160),
      payload_bytes: "152", value: { type: "f64[]", value: Array.from({ length: 19 }, () => ({ type: "f64", value: "3ff0000000000000" })) } })) };
  const encoded = JSON.stringify(v); let nodes = 0;
  JSON.parse(encoded, (_key, value) => { nodes++; return value; });
  assert.ok(nodes > 262_144, "Exercise the rejected primitive-count boundary");
  assert.ok(Buffer.byteLength(encoded) < FOLLOW_PAGE_BYTES, "The full page fits the negotiated byte limit");
  t.diagnostic(`Full array page: 4096 records, ${4096 * 19} elements, ${nodes} JSON nodes, ${Buffer.byteLength(encoded)} bytes`);
  const checked = page(v, lease, "c0", new Set(['["x",null]']));
  assert.equal(checked.page.batches[0].batch.samples.length, 4096);
  for (const [n, sample] of checked.page.batches[0].batch.samples.entries()) {
    assert.equal(sample.ordinal, n); assert.equal(sample.timestamp_us, String(n));
    assert.deepEqual(sample.value, { type: "f64[]", value: Array.from({ length: 19 }, () => ({ type: "f64", value: "3ff0000000000000" })) });
    const value = sample.value as { value: readonly object[] };
    assert.ok(Object.isFrozen(sample) && Object.isFrozen(value) && Object.isFrozen(value.value));
    assert.ok(value.value.every(Object.isFrozen));
  }
});
test("wire traversal counts primitives against its byte budget and retains its depth limit", () => {
  assert.deepEqual(freezeJson([null, {}], 3), [null, {}]);
  assert.throws(() => freezeJson([null, {}], 2), /Follow structure limit exceeded/);
  let nested: unknown = null;
  for (let depth = 0; depth < 96; depth++) nested = [nested];
  assert.doesNotThrow(() => freezeJson(nested));
  assert.throws(() => freezeJson([nested]), /Follow structure limit exceeded/);
});
test("wire uses the negotiated page budget for traversal", () => {
  const v = valid(); v.frontier.members[0].snapshot.extra = Array(1024).fill(null);
  assert.throws(() => page(v, lease, "c0", new Set(['["x",null]']), undefined, 1024), /Follow structure limit exceeded/);
  assert.doesNotThrow(() => page(v, lease, "c0", new Set(['["x",null]']), undefined, 4096));
});
for (const [name, mutate] of Object.entries({
  epoch: (v: any): unknown => v.server_epoch = "other", cursor: (v: any): unknown => v.cursor = "other", version: (v: any): unknown => v.api_version = 2,
  frontier: (v: any): unknown => delete v.frontier, work: (v: any): unknown => v.batches[0].batch.records_visited = 4097,
  selection: (v: any): unknown => v.batches[0].entry = "other", member: (v: any): unknown => v.batches[0].member_id = "other",
  terminal: (v: any): unknown => v.ended = true, ordinal: (v: any): unknown => v.batches[0].batch.problems[0].ordinal = 1,
  lossyOffset: (v: any): unknown => v.batches[0].batch.problems[0].record_offset = 9007199254740992,
})) test(`wire refuses ${name} corruption`, () => {
  const v = valid(); mutate(v); assert.throws(() => page(v, lease, "c0", new Set(['["x",null]'])), FollowError);
});

test("cancellation also releases a stalled credential lookup", async () => {
  const controller = new AbortController();
  const client = new FollowClient("http://127.0.0.1:9/mcp", async () => new Promise(() => {}));
  const pending = client.next(lease, "c0", controller.signal); controller.abort();
  let timer!: ReturnType<typeof setTimeout>;
  try { await assert.rejects(Promise.race([pending, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error("Credential cancellation deadline missed")), 200); })]), /Follow cancelled/); }
  finally { clearTimeout(timer); }
});

test("HTTP follow rejects malformed UTF-8 instead of replacing sample text", async t => {
  const endpoint = await server(t, (_req, res) => res.end(Buffer.concat([Buffer.from('{"text":"'), Buffer.from([0xff]), Buffer.from('"}')])));
  await assert.rejects(new FollowClient(endpoint).next(lease, "c0", new AbortController().signal), /Invalid follow JSON/);
});

test("HTTP creation preserves the anchored query and cleanup uses DELETE", async t => {
  const calls: { method: string; path: string; body: any }[] = [];
  const source = { kind: "session" as const, origin_store_id: "store", session_id: "session", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
  const endpoint = await server(t, (req, res) => {
    let body = ""; req.on("data", c => body += c); req.on("end", () => {
      calls.push({ method: req.method!, path: req.url!, body: body ? JSON.parse(body) : undefined });
      if (req.method === "DELETE") { res.statusCode = 204; res.end(); }
      else { res.statusCode = 201; res.end(JSON.stringify({ api_version: 1, id: "lease", server_epoch: "epoch", cursor: "c0", idle_timeout_sec: 60, max_wait_ms: 25000, source_ref: source })); }
    });
  });
  const client = new FollowClient(endpoint, undefined, 65536), windows = [{ start_us: "50", end_us: "100", end_inclusive: true }];
  const opened = await client.open(source, [{ entry: "x", field: "v" }], windows, new AbortController().signal); await client.close(opened);
  assert.deepEqual(calls, [{ method: "POST", path: "/read/v1/follows", body: { source_ref: source,
    queries: [{ entry: "x", field: "v", order: "file", windows }], max_records: 4096, max_page_bytes: 65536 } },
  { method: "DELETE", path: "/read/v1/follows/lease", body: undefined }]);
});

test("wire refuses frontier continuation under a different prefix", () => {
  const v = valid(); delete v.frontier;
  assert.throws(() => page(v, lease, "c0", new Set(['["x",null]']), { prefix: "old-prefix", frontier: valid().frontier }), /Missing follow frontier/);
});
