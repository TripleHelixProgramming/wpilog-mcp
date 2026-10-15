import { test } from "node:test";
import * as assert from "node:assert/strict";
import { LiveBroker, FollowConsumer, FollowDelivery, FollowState } from "../visualization/liveBroker";
import { FollowTransport } from "../visualization/followClient";
import { FollowError, FollowLease, FollowSource, FollowWindow, FollowSelection } from "../visualization/followProtocol";

const source: FollowSource = { kind: "session", origin_store_id: "11111111-1111-4111-8111-111111111111",
  session_id: "22222222-2222-4222-8222-222222222222", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
const pause = (ms = 1) => new Promise<void>(r => setTimeout(r, ms));
async function until(predicate: () => boolean): Promise<void> { for (let i = 0; i < 500; i++) { if (predicate()) return; await pause(2); } assert.fail("Condition did not settle"); }
function deferred() { let resolve!: () => void; const promise = new Promise<void>(r => resolve = r); return { promise, resolve }; }
function frame(lease: FollowLease, cursor: string, n = 0, options: { frontier?: boolean; ended?: boolean; bootstrap?: boolean; prefix?: string; entries?: string[] } = {}): any {
  return { api_version: 1, server_epoch: lease.server_epoch, follow_id: lease.id, cursor, next_cursor: `c${n + 1}`, prefix_id: options.prefix ?? "p1",
    bootstrap: options.bootstrap ?? true, frontier_complete: true, ended: options.ended ?? false,
    ...(options.frontier === false ? {} : { frontier: { recording_id: "recording", catalog_revision: "1", finalized: options.ended ?? false,
      members: [{ member_id: "m", generation: "0", snapshot: { entries: {}, sample_counts: {} } }] } }),
    batches: (options.entries ?? ["x"]).map(entry => ({ member_id: "m", entry, field: null, next_ordinal: n + 1,
      batch: { kind: "batch", samples: [{ ordinal: n, timestamp_us: n ? "-1" : "100", record_offset: String(100 + n * 20), payload_bytes: "8", value: { type: "i64", value: "9007199254740993" } }],
        problems: [], records_visited: 1, payload_bytes: "8", complete: true } })) };
}
class Fake implements FollowTransport {
  readonly pageBytes = 4096;
  opens: { source: FollowSource; windows: readonly FollowWindow[]; selections: readonly FollowSelection[] }[] = [];
  polls: { cursor: string; id: string }[] = []; closes: string[] = []; aborted = 0;
  handle: (l: FollowLease, c: string, s: AbortSignal, n: number) => Promise<unknown> = async (l, c, s, n) => {
    if (n === 0) return frame(l, c);
    return this.wait(s);
  };
  async open(source: FollowSource, selections: readonly FollowSelection[], windows: readonly FollowWindow[], _signal: AbortSignal): Promise<FollowLease> {
    this.opens.push({ source, selections, windows }); return { id: `lease${this.opens.length}`, server_epoch: "epoch", cursor: "c0" };
  }
  async next(l: FollowLease, c: string, s: AbortSignal): Promise<unknown> { const n = this.polls.length; this.polls.push({ cursor: c, id: l.id }); return this.handle(l, c, s, n); }
  async close(l: FollowLease): Promise<void> { this.closes.push(l.id); }
  wait(signal: AbortSignal): Promise<unknown> { return new Promise((_, reject) => {
    const abort = () => { this.aborted++; const e = new Error("abort"); e.name = "AbortError"; reject(e); };
    if (signal.aborted) abort(); else signal.addEventListener("abort", abort, { once: true });
  }); }
}
function consumer(id: string) {
  const pages: FollowDelivery[] = [], states: FollowState[] = []; let active = true;
  const value: FollowConsumer = { identity: { viewId: id, generation: 3, revision: 7, queryKey: "query" }, current: () => active,
    state: s => states.push(s), page: p => { pages.push(p); } };
  return { value, pages, states, stale: () => active = false };
}
function fixture(t: any, options = {}) { const transport = new Fake(), broker = new LiveBroker(transport, { retryBaseMs: 1, retryMaxMs: 2, random: () => 0, ...options });
  t.after(() => broker.dispose()); return { transport, broker }; }

test("oversized repeated windows refuse synchronously without reserving or opening a job", t => {
  const { transport, broker } = fixture(t);
  const windows = Array.from({ length: 1000 }, () => ({ start_us: "1000000000000000000", end_us: "1000000000000000001", end_inclusive: false }));
  assert.throws(() => broker.subscribe(source, windows, [{ entry: "x" }], consumer("a").value),
    (e: unknown) => e instanceof FollowError && e.code === "RESOURCE_LIMIT" && /64 KiB/.test(e.message));
  assert.deepEqual(broker.resources, { jobs: 0, consumers: 0, reservedBytes: 0 }); assert.equal(transport.opens.length, 0);
});
test("oversized union refuses before changing an admitted peer", async t => {
  const { transport, broker } = fixture(t), a = consumer("a"), b = consumer("b");
  const selections = (prefix: string) => Array.from({ length: 20 }, (_, i) => ({ entry: prefix + i + "é".repeat(900) }));
  transport.handle = async (_l, _c, signal) => transport.wait(signal);
  broker.subscribe(source, [], selections("a"), a.value); await until(() => transport.polls.length === 1);
  const resources = broker.resources, states = [...a.states];
  assert.throws(() => broker.subscribe(source, [], selections("b"), b.value),
    (e: unknown) => e instanceof FollowError && e.code === "RESOURCE_LIMIT" && /64 KiB/.test(e.message));
  await pause(5);
  assert.deepEqual(broker.resources, resources); assert.deepEqual(a.states, states); assert.deepEqual(b.states, []);
  assert.equal(transport.opens.length, 1); assert.equal(transport.closes.length, 0); assert.equal(transport.aborted, 0);
});

test("broker passes its transport page budget to protocol traversal", async t => {
  const { transport, broker } = fixture(t), a = consumer("a");
  transport.handle = async (l, c, s, n) => {
    if (n !== 0) return transport.wait(s);
    const v = frame(l, c); v.frontier.members[0].snapshot.extra = Array(transport.pageBytes).fill(null); return v;
  };
  broker.subscribe(source, [], [{ entry: "x" }], a.value);
  await until(() => a.states.some(s => s.kind === "error") || a.pages.length > 0);
  assert.equal(a.pages.length, 0);
  assert.equal(a.states.at(-1)?.code, "INVALID_RESPONSE");
  await until(() => transport.closes.length === 1);
});
test("broker coalesces consumers, unions entries and retains one follow after one closes", async t => {
  const { transport, broker } = fixture(t), a = consumer("a"), b = consumer("b");
  transport.handle = async (l, c, s, n) => n === 0 ? frame(l, c, 0, { entries: ["x", "y"] }) : transport.wait(s);
  const sa = broker.subscribe(source, [], [{ entry: "x" }], a.value);
  const sb = broker.subscribe(source, [], [{ entry: "y" }, { entry: "x" }], b.value);
  await until(() => a.pages.length === 1 && b.pages.length === 1);
  assert.equal(transport.opens.length, 1); assert.deepEqual(transport.opens[0].selections, [{ entry: "x" }, { entry: "y" }]);
  assert.deepEqual(a.pages[0].page.batches.map(x => x.entry), ["x"]); assert.equal(b.pages[0].page.batches.length, 2);
  assert.equal(a.pages[0].page.batches[0], b.pages[0].page.batches[0], "Share immutable data, not copies");
  assert.ok(Object.isFrozen(a.pages[0].page.batches[0].batch.samples[0].value));
  sa.dispose(); await pause(5); assert.equal(transport.closes.length, 0);
  sb.dispose(); await until(() => transport.closes.length === 1);
  assert.equal(transport.aborted, 1); await until(() => broker.resources.jobs === 0);
  assert.deepEqual(broker.resources, { jobs: 0, consumers: 0, reservedBytes: 0 });
});
test("broker acknowledges only after all consumers consume, including backward timestamps and tagged integers", async t => {
  const { transport, broker } = fixture(t), a = consumer("a"), b = consumer("b"), hold = deferred();
  a.value.page = async p => { a.pages.push(p); await hold.promise; };
  transport.handle = async (l, c, s, n) => n < 2 ? frame(l, c, n, { frontier: n === 0, ended: false }) : transport.wait(s);
  broker.subscribe(source, [], [{ entry: "x" }], a.value); broker.subscribe(source, [], [{ entry: "x" }], b.value);
  await until(() => b.pages.length === 1); assert.equal(transport.polls.length, 1);
  hold.resolve(); await until(() => b.pages.length === 2);
  assert.deepEqual(transport.polls.slice(0, 2).map(x => x.cursor), ["c0", "c1"]);
  assert.equal(b.pages[1].page.batches[0].batch.samples[0].timestamp_us, "-1");
  assert.deepEqual(b.pages[1].page.batches[0].batch.samples[0].value, { type: "i64", value: "9007199254740993" });
  assert.equal(b.pages[0].frontier, b.pages[1].frontier);
});
test("transient pressure and a lost response retry exactly the submitted cursor", async t => {
  const { transport, broker } = fixture(t), a = consumer("a");
  transport.handle = async (l, c, s, n) => {
    if (n === 0) throw new Error("socket ended mid-page");
    if (n === 1) return frame(l, c);
    if (n === 2) throw new FollowError("RESOURCE_LIMIT", "arbitrary text", 413, true);
    if (n === 3) return frame(l, c, 1);
    return transport.wait(s);
  };
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => a.pages.length === 2);
  assert.deepEqual(transport.polls.slice(0, 4).map(x => x.cursor), ["c0", "c0", "c1", "c1"]);
  assert.equal(transport.opens.length, 1); assert.equal(a.states.filter(x => x.kind === "retrying").length, 2);
});
test("terminal page is consumed then deleted without an empty-page spin", async t => {
  const { transport, broker } = fixture(t), a = consumer("a"), hold = deferred();
  a.value.page = async p => { a.pages.push(p); await hold.promise; };
  transport.handle = async (l, c) => frame(l, c, 0, { ended: true });
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => a.pages.length === 1);
  assert.equal(transport.closes.length, 0); hold.resolve(); await until(() => transport.closes.length === 1);
  assert.equal(transport.polls.length, 1); assert.equal(a.states.at(-1)?.kind, "ended");
});
test("expiry resets explicitly and reopens the same anchored source and window", async t => {
  const { transport, broker } = fixture(t), a = consumer("a"), windows = [{ start_us: "10", end_us: "20", end_inclusive: false }];
  transport.handle = async (l, c, s, n) => n === 0 ? frame(l, c) : n === 1 ? Promise.reject(new FollowError("CURSOR_EXPIRED", "restart", 410)) : n === 2 ? frame(l, c) : transport.wait(s);
  broker.subscribe(source, windows, [{ entry: "x" }], a.value); await until(() => transport.opens.length === 2 && a.pages.length === 2);
  assert.deepEqual(transport.opens.map(x => x.source), [source, source]); assert.deepEqual(transport.opens.map(x => x.windows), [windows, windows]);
  assert.deepEqual(a.pages.map(p => p.reset), [1, 2]); assert.ok(a.states.some(s => s.kind === "reset" && s.code === "CURSOR_EXPIRED"));
});
test("joining an active source resets all consumers; different windows and sources remain independent", async t => {
  const { transport, broker } = fixture(t), a = consumer("a"), b = consumer("b");
  transport.handle = async (l, c, s) => c === "c0" ? frame(l, c, 0, { entries: ["x"] }) : transport.wait(s);
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => a.pages.length === 1);
  broker.subscribe(source, [], [{ entry: "x" }], b.value); await until(() => b.pages.length === 1);
  assert.equal(transport.opens.length, 2); assert.equal(transport.closes[0], "lease1"); assert.deepEqual(a.pages.map(p => p.reset), [1, 2]);
  broker.subscribe(source, [{ start_us: "0", end_us: "100", end_inclusive: true }], [{ entry: "x" }], consumer("c").value);
  broker.subscribe({ ...source, session_id: "33333333-3333-4333-8333-333333333333" }, [], [{ entry: "x" }], consumer("d").value);
  await until(() => transport.opens.length === 4); assert.equal(broker.resources.jobs, 3);
});
test("stale generation never receives a page and slow consumers do not create a queue", async t => {
  const { transport, broker } = fixture(t, { consumerTimeoutMs: 15 }), a = consumer("a"), b = consumer("b");
  a.value.page = async p => { a.pages.push(p); await new Promise(() => {}); };
  broker.subscribe(source, [], [{ entry: "x" }], a.value); broker.subscribe(source, [], [{ entry: "x" }], b.value);
  await until(() => a.states.some(s => s.code === "CONSUMER_TIMEOUT"));
  assert.equal(b.pages.length, 1); assert.equal(a.pages.length, 1); assert.equal(broker.resources.consumers, 1);
  b.stale(); await broker.dispose(); assert.equal(b.pages.length, 1);
});
for (const code of ["HISTORY_UNAVAILABLE", "ACCESS_DENIED", "RESOURCE_LIMIT"]) test(`nonretryable ${code} closes and reports without switching recordings`, async t => {
  const { transport, broker } = fixture(t), a = consumer("a");
  transport.handle = async () => { throw new FollowError(code, "explicit refusal", code === "RESOURCE_LIMIT" ? 413 : 409); };
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => a.states.some(s => s.kind === "error"));
  assert.equal(a.states.at(-1)?.code, code); assert.equal(transport.opens.length, 1); assert.equal(a.pages.length, 0);
});
test("admission enforces a window-wide reservation and releases it when a source closes", async t => {
  const { broker } = fixture(t, { encodedBudgetBytes: 8192 }); const a = consumer("a");
  const sub = broker.subscribe(source, [], [{ entry: "x" }], a.value);
  assert.throws(() => broker.subscribe({ ...source, session_id: "other" }, [], [{ entry: "x" }], consumer("b").value), /budget/i);
  assert.equal(broker.resources.reservedBytes, 8192); sub.dispose(); await until(() => broker.resources.reservedBytes === 0);
});
test("invalid page is never delivered or acknowledged", async t => {
  const { transport, broker } = fixture(t), a = consumer("a");
  transport.handle = async (l, c) => ({ ...frame(l, c), server_epoch: "foreign" });
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => a.states.some(s => s.kind === "error"));
  assert.equal(a.pages.length, 0); assert.deepEqual(transport.polls.map(p => p.cursor), ["c0"]); assert.equal(a.states.at(-1)?.code, "INVALID_RESPONSE");
});

test("204 retains the cursor and never marks a source ended", async t => {
  const { broker, transport } = fixture(t), a = consumer("a");
  transport.handle = async (l, c, s, n) => n === 0 ? undefined : n === 1 ? frame(l, c) : transport.wait(s);
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => a.pages.length === 1);
  assert.deepEqual(transport.polls.slice(0, 2).map(p => p.cursor), ["c0", "c0"]);
  assert.ok(!a.states.some(s => s.kind === "ended"));
});
test("stale asynchronous replies are dropped before callback delivery", async t => {
  const { broker, transport } = fixture(t), a = consumer("a"), hold = deferred();
  transport.handle = async (l, c) => { await hold.promise; return frame(l, c); };
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => transport.polls.length === 1);
  a.stale(); hold.resolve(); await until(() => transport.closes.length === 1);
  assert.equal(a.pages.length, 0); assert.equal(broker.resources.consumers, 0);
});
test("a late create reply is deleted after the last consumer cancels", async t => {
  const { broker, transport } = fixture(t), a = consumer("a"), hold = deferred();
  const open = transport.open.bind(transport);
  transport.open = async (...args) => { const result = await open(...args); await hold.promise; return result; };
  const sub = broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => transport.opens.length === 1);
  sub.dispose(); hold.resolve(); await until(() => transport.closes.length === 1);
  assert.equal(transport.polls.length, 0); assert.equal(a.pages.length, 0);
});
test("a rejected consumer is removed without acknowledging before the healthy consumer", async t => {
  const { broker, transport } = fixture(t), a = consumer("a"), b = consumer("b"), hold = deferred();
  a.value.page = () => { throw new Error("renderer failed"); };
  b.value.page = async p => { b.pages.push(p); await hold.promise; };
  broker.subscribe(source, [], [{ entry: "x" }], a.value); broker.subscribe(source, [], [{ entry: "x" }], b.value);
  await until(() => a.states.some(s => s.code === "CONSUMER_FAILED")); assert.equal(transport.polls.length, 1);
  hold.resolve(); await until(() => transport.polls.length === 2); assert.equal(broker.resources.consumers, 1);
});
test("consumer identity is captured, and abandoned generations release a blocked page", async t => {
  const { broker, transport } = fixture(t), a = consumer("a"), b = consumer("b");
  a.value.page = async p => { a.pages.push(p); await new Promise(() => {}); };
  const sa = broker.subscribe(source, [], [{ entry: "x" }], a.value); broker.subscribe(source, [], [{ entry: "x" }], b.value);
  (b.value.identity as any).revision = 8;
  await until(() => b.pages.length === 1); sa.dispose(); await until(() => transport.polls.length === 2);
  assert.deepEqual(b.pages[0].identity, { viewId: "b", generation: 3, revision: 7, queryKey: "query" });
});
test("refresh after a terminal page reopens only the same reference", async t => {
  const { broker, transport } = fixture(t), a = consumer("a");
  transport.handle = async (l, c) => frame(l, c, 0, { ended: true });
  const sub = broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => transport.closes.length === 1);
  sub.refresh(); await until(() => transport.closes.length === 2);
  assert.deepEqual(a.pages.map(p => p.reset), [1, 2]); assert.deepEqual(transport.opens.map(o => o.source), [source, source]);
});
test("entry projections are distinct but identical queries coalesce despite object property order", async t => {
  const { broker, transport } = fixture(t), a = consumer("a"), b = consumer("b");
  transport.handle = async (_l, _c, s) => transport.wait(s);
  broker.subscribe(source, [], [{ entry: "x", field: "a" }, { entry: "x", field: "b" }], a.value);
  broker.subscribe({ anchor_member: source.anchor_member, session_id: source.session_id, origin_store_id: source.origin_store_id, kind: "session" }, [], [{ field: "a", entry: "x" }], b.value);
  await until(() => transport.opens.length === 1);
  assert.deepEqual(transport.opens[0].selections, [{ entry: "x", field: "a" }, { entry: "x", field: "b" }]);
  assert.equal(broker.resources.jobs, 1);
});
test("source, query and consumer caps reject admission without mutating existing work", async t => {
  const { broker, transport } = fixture(t), a = consumer("a"); transport.handle = async (_l, _c, s) => transport.wait(s);
  assert.throws(() => broker.subscribe({ ...source, kind: "robot" } as any, [], [{ entry: "x" }], a.value), /anchored/);
  assert.throws(() => broker.subscribe(source, [], [], a.value), /selection/);
  broker.subscribe(source, [], Array.from({ length: 256 }, (_, i) => ({ entry: `entry${i}` })), a.value);
  assert.throws(() => broker.subscribe(source, [], [{ entry: "too-many" }], consumer("b").value), /selection/);
  await until(() => transport.opens.length === 1); assert.equal(transport.opens[0].selections.length, 256);
  await broker.dispose(); assert.throws(() => broker.subscribe(source, [], [{ entry: "x" }], a.value), /closed/);
});

test("an orderly server shutdown resets the same recording after CLOSED", async t => {
  const { broker, transport } = fixture(t), a = consumer("a");
  transport.handle = async (l, c, s, n) => n === 0 ? frame(l, c) : n === 1 ? Promise.reject(new FollowError("CLOSED", "Server stopping", 410)) : n === 2 ? frame(l, c) : transport.wait(s);
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => transport.opens.length === 2 && a.pages.length === 2);
  assert.ok(a.states.some(s => s.kind === "reset" && s.code === "CLOSED"));
  assert.deepEqual(transport.opens.map(o => o.source), [source, source]);
});

for (const bootstrap of [true, false]) test(`incomplete ${bootstrap ? "bootstrap" : "delta"} reports catch-up instead of following`, async t => {
  const { broker, transport } = fixture(t), a = consumer("a");
  transport.handle = async (l, c, s, n) => n === 0 ? { ...frame(l, c, 0, { bootstrap }), frontier_complete: false } : transport.wait(s);
  broker.subscribe(source, [], [{ entry: "x" }], a.value); await until(() => transport.polls.length === 2);
  assert.equal(a.states.at(-1)?.kind, bootstrap ? "bootstrapping" : "catching_up");
});
