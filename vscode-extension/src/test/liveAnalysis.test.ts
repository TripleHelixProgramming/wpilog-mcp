import { test } from "node:test";
import * as assert from "node:assert/strict";
import { AnalysisQueue, LiveAnalysis } from "../visualization/liveAnalysis";
import { VisualizationBudgets } from "../visualization/byteBudget";
const sleep = (ms = 10) => new Promise<void>(r => setTimeout(r, ms));
async function until(check: () => boolean) { const end = Date.now() + 2000; while (!check()) { if (Date.now() > end) assert.fail("Timed out waiting for analysis"); await sleep(2); } }
function pending<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => resolve = r); return { promise, resolve }; }
function view(types = ["latest", "statistics"]): any { return { view_id: "v", revision: 1, source_ref: { kind: "session", session_id: "session" }, configuration: {
  requirements: [{ id: "r", entry: "x", field_path: null }], panels: types.map(type => ({ type, series: [{ requirement_id: "r" }] })),
  time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } } } }; }
function binding(time: any = { mode: "follow", window_sec: 30 }): any { return { time_policy: time, compatibility: { bindings: [
  { requirement_id: "r", state: "resolved", entry: "x", field_path: null, warnings: [] }] } }; }
function answer(body: any): any { return { inputs: { ...body, field: body.field ?? null },
  prefix: { recording_id: "recording", catalog_revision: "3", finalized: false, members: [{ member_id: "a", committed_byte_boundary: "128" }, { member_id: "b", committed_byte_boundary: "256" }] },
  value: { type: "i64", value: "9007199254740993" }, timestamp_us: "2000000", ordinal: "1", member_id: "a",
  mean: 2.5, count: 4, problem_count: 0 }; }
function fixture(t: any, options: { v?: any; request?: (route: string, body: any, signal: AbortSignal) => Promise<any>; ack?: boolean; budgets?: VisualizationBudgets; queue?: AnalysisQueue; interval?: number } = {}) {
  const messages: any[] = [], calls: any[] = [], warnings: unknown[] = [], budgets = options.budgets ?? new VisualizationBudgets(16);
  let c: LiveAnalysis;
  c = new LiveAnalysis(options.v ?? view(), "q", budgets, options.queue ?? new AnalysisQueue(), async (route, body, signal, limits) => {
    calls.push({ route, body, signal, limits }); return options.request ? options.request(route, body, signal) : answer(body);
  }, m => { messages.push(m); if (m.delivery_id && options.ack !== false) queueMicrotask(() => c.acknowledge(m.delivery_id)); }, e => warnings.push(e), options.interval ?? 10, 15);
  t.after(() => c.dispose());
  return { c, messages, calls, warnings, budgets, values: () => messages.filter(m => m.type === "value"), stats: () => messages.filter(m => m.type === "statistics") };
}
test("recording analysis uses server-bound selectors, shared exact reads and server-resolved trailing windows", async t => {
  const v = view(); v.configuration.requirements[0].entry = "untrusted-name";
  const f = fixture(t, { v }), b = binding(); b.compatibility.bindings[0].entry = "bound"; b.compatibility.bindings[0].field_path = ".volts";
  f.c.complete(b, false); await until(() => f.stats().length === 1);
  assert.deepEqual(f.calls.map(c => [c.route, c.body]), [
    ["/source/value", { source_ref: v.source_ref, entry: "bound", field: ".volts" }],
    ["/source/statistics", { source_ref: v.source_ref, entry: "bound", field: ".volts", last_seconds: 30 }]]);
  assert.equal(f.values()[0].result.value.value, "9007199254740993"); assert.equal(f.stats()[0].result.mean, 2.5);
  assert.equal(f.values()[0].result.prefix.members.length, 2);
  assert.ok(f.calls.every(c => c.limits.maxResponseBytes <= 1 << 20 && c.limits.timeoutMs === 15000));
  assert.deepEqual(f.warnings, []); f.c.dispose(); assert.equal(f.budgets.decoded.reserved, 0); assert.equal(f.budgets.encoded.reserved, 0);
});
test("complete-frontier bursts coalesce while one round is in flight", async t => {
  const p = pending<any>(), f = fixture(t, { v: view(["latest"]), request: async (_r, body) => f.calls.length === 1 ? p.promise : answer(body) });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1);
  for (let i = 0; i < 100; i++) f.c.complete(binding(), false);
  assert.equal(f.calls.length, 1); p.resolve(answer(f.calls[0].body)); await until(() => f.calls.length === 2); await sleep(30);
  assert.equal(f.calls.length, 2, "one newest pending round replaces the burst"); assert.equal(f.values().length, 2);
});
test("automatic reads keep their minimum cadence even when completed prefixes arrive rapidly", async t => {
  const times: number[] = [], f = fixture(t, { v: view(["latest"]), interval: 80, request: async (_r, body) => { times.push(performance.now()); return answer(body); } });
  f.c.complete(binding(), false); await until(() => f.values().length === 1);
  f.c.complete(binding(), false); await until(() => f.values().length === 2);
  assert.ok(times[1] - times[0] >= 65, "a burst cannot remove the minimum read cadence");
});
test("cursor supersession rejects even an uncancellable old reply and leaves statistics alone", async t => {
  const p = pending<any>(), f = fixture(t, { request: async (_r, body) => body.at_sec === 1 ? p.promise : answer(body) });
  f.c.complete(binding(), false); await until(() => f.stats().length === 1);
  f.c.cursor(1); await until(() => f.calls.length === 3); const old = f.calls[2];
  for (let i = 2; i <= 20; i++) f.c.cursor(i);
  assert.equal(old.signal.aborted, true); p.resolve(answer(old.body)); await until(() => f.calls.length === 4); await sleep(20);
  assert.equal(f.calls[3].body.at_sec, 20); assert.equal(f.stats().length, 1); assert.equal(f.values().length, 2);
  assert.equal(f.values().at(-1).result.inputs.at_sec, 20);
});
test("pause freezes analysis, resume refreshes once and hidden views await a fresh completed prefix", async t => {
  const p = pending<any>(), f = fixture(t, { v: view(["latest"]), request: async (_r, body) => f.calls.length === 1 ? p.promise : answer(body) });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1); f.c.pause(true);
  f.c.cursor(12); for (let i = 0; i < 10; i++) f.c.complete(binding(), false);
  assert.equal(f.calls[0].signal.aborted, true); p.resolve(answer(f.calls[0].body)); await sleep(20);
  assert.equal(f.values().length, 0); assert.equal(f.calls.length, 1);
  f.c.pause(false); await until(() => f.values().length === 1); assert.equal(f.calls[1].body.at_sec, undefined);
  assert.ok(f.budgets.decoded.reserved > 0); f.c.hide(); await sleep(35);
  assert.equal(f.budgets.decoded.reserved, 0); assert.equal(f.budgets.encoded.reserved, 0);
  const before = f.calls.length; f.c.complete(binding(), false); f.c.show(); await sleep(20); assert.equal(f.calls.length, before);
  f.c.complete(binding(), false); await until(() => f.calls.length === before + 1);
});
test("close aborts an active read and releases late materialization without rendering", async t => {
  const p = pending<any>(), f = fixture(t, { v: view(["latest"]), request: () => p.promise });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1); assert.ok(f.budgets.decoded.reserved > 0);
  f.c.dispose(); assert.equal(f.calls[0].signal.aborted, true); p.resolve(answer(f.calls[0].body));
  await until(() => f.budgets.decoded.reserved === 0); assert.equal(f.values().length, 0); assert.equal(f.budgets.encoded.reserved, 0);
});
test("invalidation refuses a reply admitted for a prior binding and restarts on fresh evidence", async t => {
  const p = pending<any>(), f = fixture(t, { v: view(["latest"]), request: async (_r, body) => body.entry === "x" ? p.promise : answer(body) });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1); f.c.invalidate();
  const next = binding(); next.compatibility.bindings[0].entry = "y"; f.c.complete(next, false);
  p.resolve(answer(f.calls[0].body)); await until(() => f.values().length === 1);
  assert.equal(f.values()[0].result.inputs.entry, "y"); assert.equal(f.calls[0].signal.aborted, true);
});
test("an invalidated round cannot start its remaining statistics under the replacement binding", async t => {
  const p = pending<any>(), f = fixture(t, { request: async (_r, body) => body.entry === "x" ? p.promise : answer(body) });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1); f.c.invalidate();
  const next = binding(); next.compatibility.bindings[0].entry = "y"; f.c.complete(next, false);
  p.resolve(answer(f.calls[0].body)); await until(() => f.values().length === 1 && f.stats().length > 0); await sleep(30);
  assert.equal(f.stats().length, 1, "only the new round may start replacement statistics");
  assert.deepEqual(f.calls.map(c => [c.route, c.body.entry]), [["/source/value", "x"], ["/source/value", "y"], ["/source/statistics", "y"]]);
});
test("one window fairly serializes peers and cancellation removes a queued read before I/O", async t => {
  const queue = new AnalysisQueue(), budgets = new VisualizationBudgets(16), p = pending<any>();
  const a = fixture(t, { queue, budgets, v: view(["latest"]), request: () => p.promise });
  const b = fixture(t, { queue, budgets, v: view(["latest"]) });
  const c = fixture(t, { queue, budgets, v: view(["latest"]) });
  a.c.complete(binding(), false); await until(() => a.calls.length === 1);
  b.c.complete(binding(), false); c.c.complete(binding(), false); await sleep(15);
  assert.equal(b.calls.length + c.calls.length, 0); b.c.hide(); p.resolve(answer(a.calls[0].body));
  await until(() => c.values().length === 1); assert.equal(b.calls.length, 0); assert.equal(a.values().length, 1);
  for (const f of [a, b, c]) f.c.dispose(); assert.equal(budgets.decoded.reserved, 0); assert.equal(budgets.encoded.reserved, 0);
});
test("renderer acknowledgement retains analysis bytes and foreign acknowledgements cannot release them", async t => {
  const f = fixture(t, { v: view(["latest"]), ack: false }); f.c.complete(binding(), false); await until(() => f.values().length === 1);
  const retained = f.budgets.decoded.reserved; assert.ok(retained > 0); assert.ok(f.budgets.encoded.reserved > 0);
  f.c.acknowledge("other:1"); assert.equal(f.budgets.decoded.reserved, retained); assert.ok(f.budgets.encoded.reserved > 0);
  f.c.acknowledge(f.values()[0].delivery_id); assert.equal(f.budgets.decoded.reserved, retained); assert.equal(f.budgets.encoded.reserved, 0);
  f.c.hide(); await until(() => f.messages.some(m => m.type === "analysisClear"));
  assert.equal(f.budgets.decoded.reserved, retained); f.c.acknowledge(f.messages.find(m => m.type === "analysisClear").delivery_id);
  assert.equal(f.budgets.decoded.reserved, 0);
});
for (const end of [0.5, 301]) test(`active statistics refuse a ${end} second fixed window and accept it after finalization`, async t => {
  const v = view(["statistics"]); v.configuration.time.recorded = { mode: "fixed", start_sec: 0, end_sec: end };
  const f = fixture(t, { v }), b = binding(v.configuration.time.recorded);
  f.c.complete(b, false); await until(() => f.stats().length === 1); assert.equal(f.calls.length, 0); assert.match(f.stats()[0].result.error, /1–300/);
  f.c.complete(b, true); await until(() => f.calls.length === 1); assert.deepEqual(f.calls[0].body, { source_ref: v.source_ref, entry: "x", start_time: 0, end_time: end });
});
test("finalized statistics use the recorded Full policy rather than the old follow interval", async t => {
  const f = fixture(t, { v: view(["statistics"]) }); f.c.complete(binding(), true); await until(() => f.stats().length === 1);
  assert.equal(f.calls[0].body.last_seconds, undefined); assert.equal(f.calls[0].body.start_time, undefined);
});
test("transient admission refusal retries at the cadence and permanent refusal requires Retry", async t => {
  let code = "busy";
  const f = fixture(t, { v: view(["statistics"]), request: async (_r, body) => {
    if (code === "busy") { code = "once"; throw Object.assign(new Error("Two analyses are in progress"), { retryable: true }); }
    if (code === "permanent") throw Object.assign(new Error("Exact sample limit"), { retryable: false });
    return answer(body);
  } });
  f.c.complete(binding(), false); await until(() => f.stats().length === 2); assert.match(f.stats()[0].result.error, /Two analyses/); assert.equal(f.stats()[1].result.mean, 2.5);
  code = "permanent"; f.c.complete(binding(), false); await until(() => f.stats().length === 3);
  const before = f.calls.length; for (let i = 0; i < 5; i++) f.c.complete(binding(), false); await sleep(30); assert.equal(f.calls.length, before);
  code = "ok"; f.c.refresh(); await until(() => f.stats().length === 4); assert.equal(f.stats().at(-1).result.mean, 2.5);
});
test("an empty statistics window waits for a new prefix and recovers without Retry", async t => {
  let finite = false;
  const f = fixture(t, { v: view(["statistics"]), request: async (_route, body) => {
    if (finite) return answer(body);
    const result = answer(body);
    delete result.mean;
    return { ...result, status: "no_match", count: 0, looked_for: ["x"], hint: "Wait for new samples" };
  } });
  const sameBinding = binding();
  f.c.complete(sameBinding, false); await until(() => f.stats().length === 1);
  assert.equal(f.stats()[0].result.status, "no_match"); assert.equal(f.stats()[0].result.count, 0);
  assert.equal(f.stats()[0].result.mean, undefined);
  await sleep(30); assert.equal(f.calls.length, 1, "empty data must not poll without a new completed prefix");
  finite = true; f.c.complete(sameBinding, false);
  await until(() => f.stats().length === 2);
  assert.equal(f.stats()[1].result.count, 4); assert.equal(f.stats()[1].result.mean, 2.5);
  assert.equal(f.stats()[1].result.inputs.last_seconds, 30); assert.deepEqual(f.warnings, []);
});
for (const change of ["source", "entry", "field", "prefix"]) test(`foreign ${change} analysis response is an explained refusal`, async t => {
  const f = fixture(t, { v: view(["latest"]), request: async (_r, body) => {
    const result = answer(body);
    if (change === "source") result.inputs.source_ref = { kind: "session", session_id: "foreign" };
    if (change === "entry") result.inputs.entry = "other";
    if (change === "field") result.inputs.field = ".other";
    if (change === "prefix") delete result.prefix;
    return result;
  } });
  f.c.complete(binding(), false); await until(() => f.values().length === 1);
  assert.equal(f.values()[0].result.status, "error"); assert.equal(f.values()[0].result.value, undefined);
});
test("unresolved requirements remain placeholders and a declared one starts fresh analysis", async t => {
  const f = fixture(t), b = binding(); b.compatibility.bindings[0].state = "unresolved";
  f.c.complete(b, false); await sleep(20); assert.equal(f.calls.length, 0);
  f.c.complete(binding(), false); await until(() => f.stats().length === 1);
});
test("local byte refusal cannot evict a peer or perform an unreserved read", async t => {
  const budgets = new VisualizationBudgets(16), release = budgets.decoded.reserve(budgets.decoded.maximum - 100);
  const f = fixture(t, { budgets }); f.c.complete(binding(), false); await until(() => f.warnings.length > 0);
  assert.equal(f.calls.length, 0); assert.equal(budgets.decoded.reserved, budgets.decoded.maximum - 100);
  release(); f.c.refresh(); await until(() => f.stats().length === 1); f.c.dispose(); assert.equal(budgets.decoded.reserved, 0);
});
test("repeated panels read once per requirement and reserve for every display copy", async t => {
  const single = fixture(t, { v: view(["latest"]) }), repeated = fixture(t, { v: view(["latest", "latest", "latest"]) });
  single.c.complete(binding(), false); repeated.c.complete(binding(), false); await until(() => single.values().length === 1 && repeated.values().length === 1);
  assert.equal(repeated.calls.length, 1); assert.ok(repeated.budgets.decoded.reserved > single.budgets.decoded.reserved);
});
test("queued analysis capacity is bounded and a later peer still runs after a cancellation", async () => {
  const queue = new AnalysisQueue(), p = pending<void>(), active = queue.run(new AbortController().signal, () => p.promise);
  let ran = 0; const aborters = Array.from({ length: 32 }, () => new AbortController());
  const jobs = aborters.map(a => queue.run(a.signal, async () => { ran++; }).catch(() => {}));
  let refusal: unknown;
  const overflow = queue.run(new AbortController().signal, async () => {}).catch(e => { refusal = e; });
  try {
    await sleep(2); assert.match(String(refusal), /32 views/);
    aborters[0].abort(); const last = queue.run(new AbortController().signal, async () => { ran++; });
    p.resolve(); await Promise.all([active, ...jobs, last]); assert.equal(ran, 32);
  } finally { p.resolve(); await Promise.all([active, ...jobs, overflow]); }
});

function reductionAnswer(body: any): any {
  return { ...answer(body), status: "ok", basis: "admitted_source_prefix", reduction_rule: "time_buckets_min_max_mean",
    buckets: [{ start_us: "0", end_us: "1000000", count: 10000, finite_count: 10000, min: 0, max: 10000, mean: 1, first: 0, last: 0 }],
    sample_count: 10000, finite_count: 10000, unplottable_count: 0, max_points: body.max_points,
    window: { start_us: "0", end_us: "1000000" }, records_visited: 10000, problem_count: 0, problems: [], problems_truncated: false,
    prefix: { ...answer(body).prefix, source_kind: "session" } };
}
test("fixed recording history reads server reductions with byte-derived points and keeps early spikes", async t => {
  const v = view(["time_series"]), f = fixture(t, { v, request: async (_r, body) => reductionAnswer(body) });
  const fixed = { mode: "fixed", start_sec: 0, end_sec: 1 }; v.configuration.time.live = fixed;
  f.c.complete(binding(fixed), false); await until(() => f.messages.some(m => m.type === "reduction"));
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].route, "/source/reduction");
  assert.equal(f.calls[0].body.start_time, 0); assert.equal(f.calls[0].body.end_time, 1);
  assert.ok(f.calls[0].body.max_points >= 1 && f.calls[0].body.max_points <= 4096);
  assert.ok(f.calls[0].body.max_points * 512 + 8192 <= f.calls[0].limits.maxResponseBytes);
  assert.equal(f.messages.find(m => m.type === "reduction").result.buckets[0].max, 10000);
  assert.equal(f.messages.find(m => m.type === "reduction").result.sample_count, 10000);
});
test("follow stays incremental, finalization enables full reduction and new prefixes coalesce", async t => {
  const p = pending<any>(), f = fixture(t, { v: view(["time_series"]), request: async (_r, body) => f.calls.length === 1 ? p.promise : reductionAnswer(body) });
  f.c.complete(binding(), false); await sleep(30); assert.equal(f.calls.length, 0);
  f.c.complete(binding(), true); await until(() => f.calls.length === 1);
  assert.equal(f.calls[0].body.start_time, undefined); assert.equal(f.calls[0].body.end_time, undefined);
  for (let i = 0; i < 50; i++) f.c.complete(binding(), true);
  p.resolve(reductionAnswer(f.calls[0].body)); await until(() => f.messages.filter(m => m.type === "reduction").length === 2);
  await sleep(30); assert.equal(f.calls.length, 2);
});

for (const change of ["source", "entry", "field", "range", "points", "bytes", "prefix", "counts", "order", "nonfinite", "diagnostics"]) test(`history reduction rejects malformed ${change} before display`, async t => {
  const f = fixture(t, { v: view(["time_series"]), request: async (_route, body) => {
    const r = reductionAnswer(body);
    if (change === "source") r.inputs.source_ref = { kind: "session", session_id: "other" };
    if (change === "entry") r.inputs.entry = "other";
    if (change === "field") r.inputs.field = "[1]";
    if (change === "range") r.window.start_us = "1";
    if (change === "points") r.max_points++;
    if (change === "bytes") r.inputs.max_bytes++;
    if (change === "prefix") r.prefix.catalog_revision = "invalid";
    if (change === "counts") r.sample_count++;
    if (change === "order") r.buckets[0].end_us = "-1";
    if (change === "nonfinite") r.buckets[0].mean = Infinity;
    if (change === "diagnostics") r.problems_truncated = true;
    return r;
  } });
  f.c.complete(binding({ mode: "fixed", start_sec: 0, end_sec: 1 }), false);
  await until(() => f.messages.some(m => m.type === "reduction"));
  const r = f.messages.find(m => m.type === "reduction").result;
  assert.equal(r.status, "error"); assert.match(r.error, /Invalid reduction/); assert.equal(r.buckets, undefined);
});
for (const stop of ["pause", "hide", "invalidate", "dispose"] as const) test(`history ${stop} rejects late responses and releases the reserved request`, async t => {
  const p = pending<any>(), f = fixture(t, { v: view(["time_series"]), request: () => p.promise });
  f.c.complete(binding({ mode: "fixed", start_sec: 0, end_sec: 1 }), false);
  await until(() => f.calls.length === 1); assert.ok(f.budgets.decoded.reserved > 0);
  if (stop === "pause") f.c.pause(true); else f.c[stop]();
  assert.equal(f.calls[0].signal.aborted, true); p.resolve(reductionAnswer(f.calls[0].body)); await until(() => f.budgets.encoded.reserved === 0);
  assert.equal(f.messages.filter(m => m.type === "reduction").length, 0); f.c.dispose(); assert.equal(f.budgets.decoded.reserved, 0);
});
test("empty historical ranges refresh on a completed prefix and budget refusals cannot evict a peer", async t => {
  let empty = true;
  const f = fixture(t, { v: view(["time_series"]), request: async (_r, body) => {
    const r = reductionAnswer(body); if (empty) Object.assign(r, { status: "no_match", buckets: [], sample_count: 0, finite_count: 0, hint: "Wait or widen" }); return r;
  } });
  f.c.complete(binding(), true); await until(() => f.messages.some(m => m.type === "reduction"));
  assert.equal(f.messages.find(m => m.type === "reduction").result.status, "no_match");
  empty = false; f.c.complete(binding(), true); await until(() => f.messages.filter(m => m.type === "reduction").length === 2);
  assert.equal(f.messages.filter(m => m.type === "reduction").at(-1).result.sample_count, 10000);
  f.c.dispose(); const release = f.budgets.decoded.reserve(f.budgets.decoded.maximum - 100);
  const peer = fixture(t, { v: view(["time_series"]), budgets: f.budgets }); peer.c.complete(binding(), true);
  await until(() => peer.warnings.length > 0); assert.equal(peer.calls.length, 0); assert.equal(f.budgets.decoded.reserved, f.budgets.decoded.maximum - 100); release();
});

test("repeated history panels query once and reserve every renderer copy", async t => {
  const a = fixture(t, { v: view(["time_series"]), request: async (_r, body) => reductionAnswer(body) });
  const b = fixture(t, { v: view(["time_series", "time_series", "time_series"]), request: async (_r, body) => reductionAnswer(body) });
  a.c.complete(binding(), true); b.c.complete(binding(), true);
  await until(() => [a, b].every(f => f.messages.some(m => m.type === "reduction")));
  assert.equal(a.calls.length, 1); assert.equal(b.calls.length, 1);
  assert.ok(b.budgets.decoded.reserved > a.budgets.decoded.reserved);
  a.c.dispose(); b.c.dispose(); assert.equal(a.budgets.decoded.reserved + b.budgets.decoded.reserved, 0);
});
