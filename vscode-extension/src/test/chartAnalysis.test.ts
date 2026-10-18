import { test } from "node:test";
import * as assert from "node:assert/strict";
import { AnalysisQueue, LiveAnalysis } from "../visualization/liveAnalysis";
import { VisualizationBudgets } from "../visualization/byteBudget";
const sleep = (ms = 5) => new Promise<void>(r => setTimeout(r, ms));
async function until(check: () => boolean) { const end = Date.now() + 1500; while (!check()) { if (Date.now() > end) assert.fail("Timed out waiting for chart analysis"); await sleep(); } }
function config(types = ["histogram", "scatter"]): any { return {
  requirements: [{ id: "x", entry: "wrong" }, { id: "y", entry: "wrong" }],
  panels: types.map((type, i) => ({ panel_id: `p${i}`, type, series: (type === "scatter" ? ["x", "y"] : ["x"]).map(requirement_id => ({ requirement_id })) })),
  time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } },
}; }
function binding(): any { return { time_policy: { mode: "follow", window_sec: 30 }, compatibility: { bindings: [
  { requirement_id: "x", state: "resolved", entry: "bound", field_path: "[0]" },
  { requirement_id: "y", state: "resolved", entry: "bound", field_path: "[1]" }] } }; }
function answer(route: string, body: any): any {
  const result: any = { status: "ok", inputs: { ...body }, basis: "admitted_source_prefix",
    prefix: { source_kind: body.source_ref.kind === "session" ? "session" : "file", catalog_revision: "3", finalized: body.source_ref.kind !== "session", members: [{ member_id: "m", committed_byte_boundary: "128" }] } };
  return route === "/source/histogram" ? { ...result, bins: [{ low: 0, high: 2, count: 2 }, { low: 2, high: 4, count: 2 }],
    summary: { count: 4, min: 0, max: 4, mean: 2 }, histogram_rule: "Server bins" }
    : { ...result, points: body.offset ? [[4, 8, 2]] : [[0, 0, 0], [2, 4, 1]], matched: 3, unpaired_x: 1, unpaired_y: 2,
      summary: [{ count: 4 }, { count: 5 }], alignment_rule: "Exact timestamp, one-to-one", offset: body.offset,
      next_offset: body.offset ? 3 : 2, complete: !!body.offset, limits: { points: { total: 3, returned: body.offset ? 1 : 2, limit: body.limit } } };
}
function fixture(t: any, options: { config?: any; file?: boolean; request?: (route: string, body: any, signal: AbortSignal) => Promise<any>; budgets?: VisualizationBudgets; queue?: AnalysisQueue; interval?: number; ack?: boolean } = {}) {
  const view: any = { view_id: "v", source_ref: options.file ? { kind: "unmanaged", source_id: "s" } : { kind: "session", session_id: "s" }, configuration: options.config ?? config() };
  const calls: any[] = [], messages: any[] = [], warnings: any[] = [], budgets = options.budgets ?? new VisualizationBudgets(16);
  let c: LiveAnalysis;
  c = new LiveAnalysis(view, "g", budgets, options.queue ?? new AnalysisQueue(), async (route, body, signal, limits) => {
    calls.push({ route, body, signal, limits, at: performance.now() });
    return options.request ? options.request(route, body, signal) : answer(route, body);
  }, m => { messages.push(m); if (m.delivery_id && options.ack !== false) queueMicrotask(() => c.acknowledge(m.delivery_id)); }, e => warnings.push(e), options.interval ?? 10, 10);
  t.after(() => c.dispose());
  return { c: c as LiveAnalysis & { scatterPage(panelId: string, offset: number): void }, view, calls, messages, warnings, budgets, charts: () => messages.filter(m => ["histogram", "scatter"].includes(m.type)) };
}
test("charts use server-bound fields, one admitted pair and the shared byte reservation", async t => {
  const f = fixture(t); f.c.complete(binding(), false); await until(() => f.charts().length === 2);
  assert.deepEqual(f.calls.map(c => c.route), ["/source/histogram", "/source/scatter"]);
  const [hist, scatter] = f.calls;
  assert.deepEqual(hist.body, { source_ref: f.view.source_ref, entry: "bound", field: "[0]", last_seconds: 30 });
  assert.deepEqual(scatter.body, { source_ref: f.view.source_ref, x_entry: "bound", x_field: "[0]", y_entry: "bound", y_field: "[1]", last_seconds: 30, offset: 0, limit: scatter.body.limit });
  assert.ok(scatter.body.limit >= 1 && scatter.body.limit <= 256); assert.ok(scatter.limits.maxResponseBytes <= 1 << 20);
  assert.equal(scatter.limits.maxRequestBytes, 64 << 10); assert.deepEqual(f.charts()[0].result.bins.map((b: any) => b.count), [2, 2]);
  assert.deepEqual(f.charts()[1].result.points, [[0, 0, 0], [2, 4, 1]]); assert.deepEqual(f.charts()[1].pair, ["x", "y"]);
  assert.ok(f.budgets.decoded.reserved > 0); assert.deepEqual(f.warnings, []); f.c.dispose(); assert.equal(f.budgets.decoded.reserved, 0);
});
test("file charts use the same owner and recorded range without triggering live value reads", async t => {
  const cfg = config(["histogram", "scatter", "latest", "statistics"]); cfg.time.recorded = { mode: "fixed", start_sec: 1, end_sec: 2 };
  const f = fixture(t, { config: cfg, file: true }); f.c.complete(binding(), true); await until(() => f.charts().length === 2);
  assert.equal(f.calls.length, 2); assert.ok(f.calls.every(c => c.body.start_time === 1 && c.body.end_time === 2 && c.body.last_seconds === undefined));
  assert.ok(f.charts().every(m => m.result.status === "ok"));
});
test("chart cadence coalesces prefixes, cursor edits do not refetch, and repeated pairs fetch once", async t => {
  const f = fixture(t, { config: config(["scatter", "scatter"]), interval: 80 });
  f.c.complete(binding(), false); await until(() => f.charts().length === 1); f.c.cursor(1); await sleep(15); assert.equal(f.calls.length, 1);
  for (let i = 0; i < 100; i++) f.c.complete(binding(), false);
  await until(() => f.charts().length === 2); assert.ok(f.calls[1].at - f.calls[0].at >= 65); await sleep(90); assert.equal(f.calls.length, 2);
});
test("chart pause, hide and close cancel stale work and release display bytes", async t => {
  let finish!: (value: any) => void;
  const f = fixture(t, { config: config(["histogram"]), request: (r,b) => f.calls.length === 1 ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(answer(r,b)) });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1); f.c.pause(true);
  finish(answer(f.calls[0].route, f.calls[0].body)); await sleep(30); assert.equal(f.charts().length, 0); assert.equal(f.calls[0].signal.aborted, true);
  f.c.pause(false); await until(() => f.charts().length === 1); f.c.hide(); await until(() => f.budgets.decoded.reserved === 0);
  f.c.show(); await sleep(20); assert.equal(f.calls.length, 2); f.c.complete(binding(), false); await until(() => f.charts().length === 2);
  f.c.dispose(); assert.equal(f.budgets.decoded.reserved, 0); assert.equal(f.budgets.encoded.reserved, 0);
});
test("an incomplete or unresolved scatter pair stays a placeholder without a guessed axis", async t => {
  const cfg = config(["scatter"]); cfg.panels[0].series.pop(); const f = fixture(t, { config: cfg });
  f.c.complete(binding(), false); await sleep(30); assert.equal(f.calls.length, 0);
  const g = fixture(t, { config: config(["scatter"]) }), b = binding(); b.compatibility.bindings[1].state = "unresolved";
  g.c.complete(b, false); await sleep(30); assert.equal(g.calls.length, 0);
});

test("scatter navigation replaces the offset, invalidates a late page and ignores invalid or paused gestures", async t => {
  let finish!: (value: any) => void;
  const f = fixture(t, { config: config(["scatter"]), request: (r,b) => f.calls.length === 1 ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(answer(r,b)) });
  f.c.complete(binding(), false); await until(() => f.calls.length === 1);
  f.c.scatterPage("p0", 2); assert.equal(f.calls[0].signal.aborted, true); finish(answer(f.calls[0].route, f.calls[0].body));
  await until(() => f.charts().length === 1); assert.equal(f.charts()[0].result.offset, 2); assert.deepEqual(f.charts()[0].result.points, [[4,8,2]]);
  for (const n of [-1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) f.c.scatterPage("p0", n);
  f.c.scatterPage("absent", 0); await sleep(25); assert.equal(f.calls.length, 2);
  f.c.pause(true); f.c.scatterPage("p0", 0); await sleep(25); assert.equal(f.calls.length, 2);
  f.c.pause(false); await until(() => f.calls.length === 3); assert.equal(f.calls[2].body.offset, 2);
  f.c.scatterPage("p0", 0); await until(() => f.charts().at(-1)?.result.offset === 0);
});
for (const kind of ["histogram", "scatter"]) test(`${kind} bounds active windows and re-admits Full after finalization`, async t => {
  const f = fixture(t, { config: config([kind]) }), b = binding(); b.time_policy = { mode: "fixed", start_sec: 0, end_sec: 301 };
  f.c.complete(b, false); await until(() => f.charts().length === 1); assert.equal(f.calls.length, 0); assert.match(f.charts()[0].result.error, /1–300/);
  f.c.complete(b, true); await until(() => f.calls.length === 1); assert.equal(f.calls[0].body.last_seconds, undefined); assert.equal(f.calls[0].body.start_time, undefined);
});
for (const [label, mutate] of [
  ["source", (r: any) => r.inputs.source_ref = { kind: "session", session_id: "other" }],
  ["entry", (r: any) => r.inputs.x_entry = "other"],
  ["field", (r: any) => r.inputs.y_field = "[2]"],
  ["source kind", (r: any) => r.prefix.source_kind = "file"],
  ["catalog", (r: any) => delete r.prefix.catalog_revision],
  ["members", (r: any) => r.prefix.members = null],
  ["finalized", (r: any) => r.prefix.finalized = "true"],
  ["basis", (r: any) => r.basis = "plot_buffer"],
  ["status", (r: any) => r.status = "maybe"],
  ["point", (r: any) => r.points[0][0] = Infinity],
  ["timestamp", (r: any) => r.points[0][2] = "1"],
  ["point shape", (r: any) => r.points[0].push(1)],
  ["point cap", (r: any) => r.points = Array.from({ length: 4097 }, () => [0,0,0])],
  ["matched", (r: any) => r.matched = -1],
  ["unpaired", (r: any) => r.unpaired_y = 1.5],
  ["offset", (r: any) => r.offset++],
  ["next offset", (r: any) => r.next_offset = 0],
  ["complete", (r: any) => r.complete = true],
  ["total", (r: any) => r.limits.points.total++],
  ["returned", (r: any) => r.limits.points.returned++],
  ["limit", (r: any) => r.limits.points.limit++],
  ["pair rule", (r: any) => delete r.alignment_rule],
] as const) test(`malformed scatter ${label} refuses the entire response`, async t => {
  const f = fixture(t, { config: config(["scatter"]), request: async (route, body) => { const r = answer(route, body); mutate(r); return r; } });
  f.c.complete(binding(), false); await until(() => f.charts().length === 1);
  assert.equal(f.charts()[0].result.status, "error"); assert.equal(f.charts()[0].result.points, undefined);
});
for (const [label, mutate] of [
  ["bin cap", (r: any) => r.bins = Array.from({ length: 65 }, () => ({ low: 0, high: 1, count: 1 }))],
  ["bin edge", (r: any) => r.bins[0].high = Infinity],
  ["bin range", (r: any) => r.bins[0].low = r.bins[0].high],
  ["bin count", (r: any) => r.bins[0].count = -1],
  ["summary", (r: any) => r.summary.count = null],
  ["bin rule", (r: any) => delete r.histogram_rule],
  ["empty remedy", (r: any) => { r.status = "no_match"; r.bins = []; }],
] as const) test(`malformed histogram ${label} refuses the entire response`, async t => {
  const f = fixture(t, { config: config(["histogram"]), request: async (route, body) => { const r = answer(route, body); mutate(r); return r; } });
  f.c.complete(binding(), false); await until(() => f.charts().length === 1); assert.equal(f.charts()[0].result.status, "error");
});
test("empty and partial chart evidence is preserved with the server remedy and diagnostics", async t => {
  const f = fixture(t, { request: async (route, body) => {
    const r = answer(route,body);
    if (route.endsWith("histogram")) Object.assign(r, { status: "no_match", hint: "Choose another interval", bins: [], summary: { count: 0 } });
    else Object.assign(r, { status: "partial", problems: [{ member_id: "b", reason: "Malformed record" }], skipped: [{ reason: "One record" }] });
    return r;
  } });
  f.c.complete(binding(), false); await until(() => f.charts().length === 2);
  assert.equal(f.charts()[0].result.hint, "Choose another interval"); assert.equal(f.charts()[1].result.problems[0].member_id, "b");
});
test("charts share the queue with a live value peer and cancelled waiting work performs no I/O", async t => {
  const queue = new AnalysisQueue(), budgets = new VisualizationBudgets(16); let finish!: () => void;
  const busy = queue.run(new AbortController().signal, () => new Promise<void>(r => { finish = r; }));
  const a = fixture(t, { config: config(["histogram"]), queue, budgets, file: true }), b = fixture(t, { config: config(["scatter"]), queue, budgets });
  a.c.complete(binding(), true); b.c.complete(binding(), false); await sleep(30); assert.equal(a.calls.length + b.calls.length, 0);
  a.c.hide(); finish(); await busy; await until(() => b.charts().length === 1); assert.equal(a.calls.length, 0);
  b.c.dispose(); a.c.dispose(); assert.equal(budgets.decoded.reserved, 0); assert.equal(budgets.encoded.reserved, 0);
});
test("a peer's reserved bytes prevent chart I/O and repeated pairs reserve for each display copy", async t => {
  const budgets = new VisualizationBudgets(16), release = budgets.decoded.reserve(budgets.decoded.maximum - 100);
  const f = fixture(t, { budgets }); f.c.complete(binding(), false); await until(() => f.warnings.length > 0); assert.equal(f.calls.length, 0);
  assert.equal(budgets.decoded.reserved, budgets.decoded.maximum - 100); release(); f.c.refresh(); await until(() => f.charts().length === 2);
  const a = fixture(t, { config: config(["scatter"]) }), b = fixture(t, { config: config(["scatter", "scatter"]) });
  a.c.complete(binding(), false); b.c.complete(binding(), false); await until(() => a.charts().length === 1 && b.charts().length === 1);
  assert.equal(b.calls.length, 1); assert.ok(b.budgets.decoded.reserved > a.budgets.decoded.reserved);
});
test("chart handoff charges bytes until acknowledgement and hide clear also needs acknowledgement", async t => {
  const f = fixture(t, { config: config(["scatter"]), ack: false }); f.c.complete(binding(), false); await until(() => f.charts().length === 1);
  const retained = f.budgets.decoded.reserved; assert.ok(retained > 0 && f.budgets.encoded.reserved > 0);
  f.c.acknowledge("foreign"); assert.ok(f.budgets.encoded.reserved > 0); f.c.acknowledge(f.charts()[0].delivery_id);
  assert.equal(f.budgets.encoded.reserved, 0); f.c.hide(); await until(() => f.messages.some(m => m.type === "analysisClear"));
  assert.equal(f.budgets.decoded.reserved, retained); f.c.acknowledge(f.messages.find(m => m.type === "analysisClear").delivery_id); assert.equal(f.budgets.decoded.reserved, 0);
});
test("transient chart admission retries but permanent refusal waits for Refresh chart", async t => {
  let state = "busy";
  const f = fixture(t, { config: config(["histogram"]), request: async (r,b) => {
    if (state === "busy") { state = "ok"; throw Object.assign(new Error("Chart slots busy"), { retryable: true }); }
    if (state === "denied") throw Object.assign(new Error("Source refused"), { retryable: false });
    return answer(r,b);
  } });
  f.c.complete(binding(), false); await until(() => f.charts().length === 2); assert.equal(f.charts()[1].result.status, "ok");
  state = "denied"; f.c.complete(binding(), false); await until(() => f.charts().length === 3);
  state = "ok"; f.c.complete(binding(), false); await sleep(30); assert.equal(f.calls.length, 3);
  f.c.refresh(); await until(() => f.charts().length === 4); assert.equal(f.charts()[3].result.status, "ok");
});
