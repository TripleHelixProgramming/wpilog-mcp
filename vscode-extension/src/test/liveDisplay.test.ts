import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ByteBudget, decodedBytes } from "../visualization/byteBudget";
import { LiveDisplay, plotNumber } from "../visualization/liveDisplay";
import { LivePresentation } from "../visualization/livePresentation";
import { LiveView, liveDataKey } from "../visualization/liveView";

const sleep = (n = 10) => new Promise(r => setTimeout(r, n));
function binding(): any { return { compatibility: { bindings: [{ requirement_id: "r", state: "resolved", entry: "x", field_path: null, warnings: [] }] },
  queries: [{ entry: "x" }], follow_ready: true, reason: "Ready", time_policy: { mode: "follow", window_sec: 30 },
  clock: { basis: "robot_estimate", robot_time_sec: 100, last_sample_time_sec: 100, right_edge_sec: 100, waiting_for_time_sync: false } }; }
function config(): any { return { requirements: [{ id: "r", selector_kind: "entry", entry: "x", field_path: null, accepted_entry_types: ["int64"] }],
  panels: ["time_series", "latest", "raw"].map(type => ({ panel_id: type, type, series: [{ requirement_id: "r" }] })),
  time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } } }; }
function view(): any { return { view_id: "v", revision: 1, configuration: config(), source_ref: { kind: "session" } }; }
function sample(ordinal: number, seconds: number, value = String(ordinal)): any { return { ordinal, timestamp_us: String(seconds * 1e6), value: { type: "i64", value } }; }
function delivery(cursor: string, samples: any[], clock = binding().clock, finalized = false, complete = true): any { return {
  frontier: { clock, finalized }, page: { cursor, prefix_id: cursor, frontier_complete: complete,
    batches: [{ member_id: "m", entry: "x", field: null, batch: { records_visited: samples.length, problems: [], samples } }] } }; }
test("display keeps native integer text, physical raw order and an explicit plot projection", () => {
  const budget = new ByteBudget(), d = new LiveDisplay(binding(), config(), budget);
  const f = d.consume(delivery("a", [sample(0, 99, "9007199254740993"), sample(1, 98, "7"), sample(2, 98, "8"), sample(3, 100, "-2")]))!;
  assert.deepEqual(f.raw.r.samples.map(s => s.ordinal), [0, 1, 2, 3]); assert.equal((f.raw.r.samples[0].value as any).value, "9007199254740993");
  assert.deepEqual(f.series.r.timestamps, [98, 99, 100]); assert.deepEqual(f.series.r.values, [8, null, -2]);
  assert.equal(f.series.r.duplicate_times, 1); assert.equal(f.series.r.unplottable, 1); assert.equal(f.values.r?.ordinal, 3);
  assert.ok(budget.reserved > 0); d.dispose(); assert.equal(budget.reserved, 0);
});
test("plot conversion checks independent IEEE bits and refuses unsafe integers", () => {
  assert.equal(plotNumber({ type: "f64", value: "3ff8000000000000" }), 1.5);
  assert.equal(plotNumber({ type: "f32", value: "c0200000" }), -2.5);
  assert.ok(Object.is(plotNumber({ type: "f64", value: "8000000000000000" }), -0));
  for (const value of [{ type: "f64", value: "7ff0000000000000" }, { type: "f64", value: "7ff8000000000001" }, { type: "i64", value: "9007199254740993" }, { type: "string", value: "12" }]) assert.equal(plotNumber(value), null);
  assert.equal(plotNumber({ type: "bool", value: true }), 1);
});
test("plot and raw caps are independent and cursor replay does not duplicate records", () => {
  const d = new LiveDisplay(binding(), config(), new ByteBudget(), 3, 2);
  const page = delivery("a", [sample(0, 95), sample(1, 96), sample(2, 97), sample(3, 98), sample(4, 99)]), f = d.consume(page)!;
  assert.deepEqual(f.series.r.values, [2, 3, 4]); assert.equal(f.series.r.trimmed, 2);
  assert.deepEqual(f.raw.r.samples.map(s => s.ordinal), [3, 4]); assert.equal(f.raw.r.records_visited, 5);
  assert.equal(d.consume(page), f); d.dispose();
});
test("one recorded edge trims quiet series and holds during a time-sync gap without wall time", () => {
  const d = new LiveDisplay(binding(), config(), new ByteBudget());
  d.consume(delivery("a", [sample(0, 75), sample(1, 95)]));
  const quiet = delivery("b", [], { ...binding().clock, right_edge_sec: 110, robot_time_sec: 110 }); quiet.page.batches = [];
  let f = d.consume(quiet)!;
  assert.deepEqual(f.range, { start: 80, end: 110 }); assert.deepEqual(f.series.r.timestamps, [95]);
  f = d.consume(delivery("c", [], { basis: "last_sample", robot_time_sec: null, last_sample_time_sec: 95, right_edge_sec: 95, waiting_for_time_sync: true }))!;
  assert.equal(f.clock.display_basis, "held_robot_edge"); assert.equal(f.range?.end, 110);
  f = d.consume(delivery("d", [], { basis: "last_sample", robot_time_sec: null, last_sample_time_sec: 95, right_edge_sec: 95, waiting_for_time_sync: true }, true))!;
  assert.equal(f.range?.end, 95); assert.equal(f.finalized, true); d.dispose();
});
test("display budget refusal leaves its old allocation releasable", () => {
  const b = binding(), budget = new ByteBudget(decodedBytes(b) + 1), d = new LiveDisplay(b, config(), budget);
  assert.throws(() => d.consume(delivery("a", [sample(0, 99)])), /budget/); d.dispose(); assert.equal(budget.reserved, 0);
});
test("presentation retains visible accounting, coalesces and requires its own acknowledgement", async () => {
  const b = new ByteBudget(), sent: any[] = [], f: any = { series: { x: [1] } }, failures: string[] = [];
  const p = new LivePresentation(b, (id, frame) => sent.push({ id, frame }), e => failures.push(e), 25, 200);
  p.offer(f); const first = b.reserved; assert.equal(first, 2 * decodedBytes(f));
  p.offer({ ...f, prefix: "b" }); p.offer({ ...f, prefix: "c" }); p.acknowledge("foreign"); await sleep(40); assert.equal(sent.length, 1); assert.equal(b.reserved, first);
  p.acknowledge(sent[0].id);
  await sleep(10); assert.equal(sent.length, 2); assert.equal(sent[1].frame.prefix, "c"); assert.ok(b.reserved > first);
  p.acknowledge(sent[1].id); assert.equal(b.reserved, 2 * decodedBytes(sent[1].frame), "acknowledged renderer memory remains owned"); p.offer(null); assert.equal(sent.length, 3); p.acknowledge(sent[2].id);
  assert.equal(b.reserved, 0); assert.deepEqual(failures, []); p.dispose();
});
test("stalled renderer stops acquisition and retains charged bytes until close", async () => {
  const b = new ByteBudget(), sent: string[] = [], failures: string[] = [], p = new LivePresentation(b, id => sent.push(id), e => failures.push(e), 1, 15);
  p.offer({ prefix: "a" } as any); await sleep(30); p.offer({ prefix: "b" } as any);
  assert.equal(failures.length, 1); assert.equal(sent.length, 1); assert.ok(b.reserved > 0); p.dispose(); assert.equal(b.reserved, 0);
});
test("frame latency uses host receipt to matching render acknowledgement and renderer-local duration", () => {
  let now = 1000; const sent: string[] = [], measurements: any[] = [];
  const p: LivePresentation = new (LivePresentation as any)(new ByteBudget(), (id: string) => sent.push(id), () => {}, 1, 100, 0,
    (value: any) => measurements.push(value), () => now);
  try {
    p.offer({ prefix: "prefix-a", receivedAtMs: 990 } as any); now = 1020;
    (p.acknowledge as any)("foreign", { rendered: true, render_elapsed_ms: 3 }); assert.equal(measurements.length, 0);
    (p.acknowledge as any)(sent[0], { rendered: true, render_elapsed_ms: 3 });
    assert.equal(measurements.length, 1);
    assert.deepEqual(measurements[0], { prefix_id: "prefix-a", delivery_id: sent[0], receipt_to_ack_ms: 30, post_to_ack_ms: 20, renderer_elapsed_ms: 3 });
    p.offer(null); (p.acknowledge as any)(sent[1], { rendered: true, render_elapsed_ms: 1 }); assert.equal(measurements.length, 1);
  } finally { p.dispose(); }
});
function controller(bind = async () => binding(), hiddenMs = 20, analysis?: { complete(binding: any, finalized: boolean): void; invalidate(): void }, health?: any) {
  const sinks: any[] = [], closed: number[] = [], states: any[] = [], frames: any[] = [], budget = new ByteBudget();
  const broker: any = { subscribeView: (_s: any, _r: any, sink: any) => { const i = sinks.length; sinks.push(sink); return { dispose: () => closed.push(i) }; } };
  let p: LivePresentation;
  p = new LivePresentation(budget, (id, frame) => { frames.push(frame); queueMicrotask(() => p.acknowledge(id)); }, () => {}, 1, 100);
  const c: LiveView = new (LiveView as any)(broker, budget, p, bind, (s: any) => states.push(s), 5, hiddenMs, analysis, health);
  return { c, sinks, closed, states, frames, budget };
}
test("health reopens a drained same-recording follow but never switches to another recording", async () => {
  const listeners: ((value: any) => void)[] = []; let released = 0;
  const health = { watch: (_: any, changed: (v: any) => void) => { listeners.push(changed); return { dispose: () => released++ }; } };
  const f = controller(undefined, 20, undefined, health);
  try {
    f.c.update(view()); assert.equal(listeners.length, 1, "Visible views observe independent source health");
    f.sinks[0].binding(binding()); f.sinks[0].page(delivery("a", [sample(0, 98)], undefined, true));
    f.sinks[0].state({ kind: "ended", reason: "Drained" }); f.c.pause(true);
    listeners[0]({ kind: "ok", health: { acquisition_state: "reconnecting", history_state: "finalized" }, reason: "Disconnected" });
    assert.equal(f.sinks.length, 1);
    listeners[0]({ kind: "ok", health: { acquisition_state: "recording", history_state: "live" }, reason: "Same recording resumed" });
    assert.equal(f.sinks.length, 2, "A completed lease cannot see the reopened member on its own");
    assert.equal(f.states.at(-1).paused, true); assert.equal(listeners.length, 1, "Rebootstrap does not restart the health owner");
    f.sinks[1].binding(binding()); f.sinks[1].page(delivery("b", [sample(0, 98), sample(1, 99)]));
    await sleep(); f.c.pause(false); await sleep();
    assert.deepEqual(f.frames.at(-1).raw.r.samples.map((s: any) => s.ordinal), [0, 1], "Rebootstrap replaces buffers rather than appending duplicates");
    f.sinks[1].state({ kind: "ended", reason: "Finalized" });
    listeners[0]({ kind: "ok", health: { acquisition_state: "inactive", history_state: "finalized" }, reason: "Another recording" });
    assert.equal(f.sinks.length, 2);
    f.c.hide(); assert.equal(released, 1);
    listeners[0]({ kind: "ok", health: { acquisition_state: "recording", history_state: "live" }, reason: "Late" });
    assert.equal(f.sinks.length, 2);
    f.c.show(); assert.equal(listeners.length, 2);
    const afterReveal = f.states.length;
    listeners[0]({ kind: "unavailable", reason: "Reply from the retired watcher" });
    assert.equal(f.states.length, afterReveal, "Old health replies cannot label a revealed generation");
  } finally { f.c.dispose(); }
  assert.equal(released, 2); assert.equal(f.budget.reserved, 0);
});
test("health admission pressure stays visible without failing an independent data follow", () => {
  const f = controller(undefined, 20, undefined, { watch() { throw new Error("Health source capacity reached"); } });
  try {
    assert.doesNotThrow(() => f.c.update(view())); assert.equal(f.sinks.length, 1);
    assert.equal(f.states.at(-1).health.kind, "unavailable"); assert.match(f.states.at(-1).health.reason, /capacity/);
  } finally { f.c.dispose(); }
});
test("presentation revisions reuse acquisition; selection changes invalidate old callbacks", async () => {
  const f = controller(); let v = view(); f.c.update(v); f.sinks[0].binding(binding());
  const key = liveDataKey(v); v = structuredClone(v); v.revision++; v.configuration.panels[0].title = "Rename"; f.c.update(v);
  assert.equal(liveDataKey(v), key); assert.equal(f.sinks.length, 1);
  v = structuredClone(v); v.revision++; v.configuration.requirements[0].entry = "y"; f.c.update(v);
  assert.deepEqual(f.closed, [0]); assert.equal(f.sinks.length, 2);
  const before = f.frames.length; f.sinks[0].page(delivery("stale", [sample(0, 99)])); assert.equal(f.frames.length, before);
  assert.equal(f.sinks[0].current(), false); f.c.dispose(); assert.equal(f.budget.reserved, 0);
});
test("explicit full history rebinds a finalized rolling view even when its data key is unchanged", async () => {
  const f = controller(), v = view();
  try {
    f.c.update(v); f.sinks[0].binding(binding());
    f.sinks[0].page(delivery("ended", [sample(0, 75), sample(1, 100)], undefined, true));
    await sleep(); assert.deepEqual(f.frames.at(-1).range, { start: 70, end: 100 });
    const key = liveDataKey(v); v.revision++;
    (f.c.update as any)(v, false, true);
    assert.equal(liveDataKey(v), key);
    assert.equal(f.sinks.length, 2, "Full recording must acquire the earlier samples");
    assert.deepEqual(f.closed, [0]); assert.equal(f.sinks[0].current(), false);
    const full = binding(); full.time_policy = { mode: "full" }; f.sinks[1].binding(full);
    f.sinks[1].page(delivery("full", [sample(0, 1, "2"), sample(1, 75, "3"), sample(2, 100, "4")], undefined, true));
    await sleep(); assert.equal(f.frames.at(-1).range, undefined);
    assert.deepEqual(f.frames.at(-1).series.r.timestamps, [1, 75, 100]);
    assert.deepEqual(f.frames.at(-1).series.r.values, [2, 3, 4]);
  } finally { f.c.dispose(); }
  assert.equal(f.budget.reserved, 0);
});
test("pause consumes completed frontiers without publishing and resumes at the newest prefix", async () => {
  const f = controller(); f.c.update(view()); f.sinks[0].binding(binding()); await sleep(); f.c.pause(true);
  const before = f.frames.length;
  f.sinks[0].page(delivery("partial", [sample(0, 98)], undefined, false, false));
  f.sinks[0].page(delivery("complete", [sample(1, 99)])); await sleep(); assert.equal(f.frames.length, before);
  f.c.pause(false); await sleep(); assert.equal(f.frames.at(-1).prefix, "complete"); assert.equal(f.frames.at(-1).raw.r.records_visited, 2);
  assert.equal(f.sinks.length, 1); f.c.dispose(); assert.equal(f.budget.reserved, 0);
});
test("resuming a paused display preserves a disconnected or finalized acquisition state", () => {
  const f = controller();
  try {
    f.c.update(view()); f.sinks[0].binding(binding());
    f.sinks[0].state({ kind: "retrying", reason: "Connection lost; retaining the cursor" });
    f.c.pause(true); f.c.pause(false);
    assert.equal(f.states.at(-1).kind, "retrying"); assert.match(f.states.at(-1).reason, /Connection lost/);
    f.sinks[0].state({ kind: "ended", reason: "Finalized recording drained" });
    f.c.pause(true); f.c.pause(false); assert.equal(f.states.at(-1).kind, "ended");
  } finally { f.c.dispose(); }
});
test("hide cancels immediately, ages out display, and close rejects every late callback", async () => {
  const f = controller(); f.c.update(view()); f.sinks[0].binding(binding()); f.sinks[0].page(delivery("a", [sample(0, 99)])); await sleep();
  f.c.hide(); assert.deepEqual(f.closed, [0]); await sleep(40); assert.equal(f.budget.reserved, 0);
  f.c.show(); assert.equal(f.sinks.length, 2); f.c.dispose(); const before = f.frames.length;
  f.sinks[1].binding(binding()); f.sinks[1].page(delivery("late", [sample(1, 99)])); assert.equal(f.frames.length, before); assert.equal(f.budget.reserved, 0);
});
test("missing declarations retry in sequence and a ready binding replaces only this subscription", async () => {
  let calls = 0; const f = controller(async () => { calls++; return binding(); }); f.c.update(view());
  f.sinks[0].state({ kind: "error", code: "NO_RESOLVED_BINDINGS", reason: "Absent" });
  await sleep(30); assert.equal(calls, 1); assert.equal(f.sinks.length, 2); assert.deepEqual(f.closed, [0]); f.c.dispose();
});
test("a presentation revision during a missing-binding check schedules the current revision again", async () => {
  let resolve: ((v: any) => void) | undefined, calls = 0;
  const f = controller(async () => { calls++; if (calls === 1) return new Promise(r => resolve = r); return binding(); });
  const v = view(); f.c.update(v); f.sinks[0].state({ kind: "error", code: "NO_RESOLVED_BINDINGS", reason: "Absent" });
  await sleep(15); assert.ok(resolve); v.revision++; v.configuration.panels[0].title = "Changed while waiting"; f.c.update(v);
  resolve!(binding()); await sleep(30); assert.equal(calls, 2); assert.equal(f.sinks.length, 2); f.c.dispose();
});
test("blank recording layouts wait for signals without opening or polling bindings", async () => {
  let checks = 0; const f = controller(async () => { checks++; return binding(); }), v = view(); v.configuration.panels = [];
  f.c.update(v); await sleep(20); assert.equal(f.sinks.length, 0); assert.equal(checks, 0); f.c.dispose();
});
test("statistics-only recording layouts acquire clock and binding evidence", () => {
  const f = controller(), v = view(); v.configuration.panels = [{ panel_id: "stats", type: "statistics", series: [{ requirement_id: "r" }] }];
  f.c.update(v); assert.equal(f.sinks.length, 1); f.c.dispose();
});
test("analysis is notified only by complete current prefixes and is invalidated on a reset", () => {
  const complete: any[] = []; let invalidated = 0;
  const f = controller(undefined, 20, { complete: (b, ended) => complete.push({ b, ended }), invalidate: () => { invalidated++; } });
  f.c.update(view()); f.sinks[0].binding(binding());
  f.sinks[0].page(delivery("partial", [sample(0, 99)], undefined, false, false)); assert.equal(complete.length, 0);
  f.sinks[0].page(delivery("complete", [sample(1, 100)], undefined, true));
  assert.equal(complete.length, 1); assert.equal(complete[0].ended, true); assert.equal(complete[0].b.compatibility.bindings[0].entry, "x");
  const before = invalidated; f.sinks[0].state({ kind: "reset", reason: "New epoch" }); assert.ok(invalidated > before);
  f.c.hide(); f.sinks[0].page(delivery("late", [sample(2, 100)])); assert.equal(complete.length, 1); f.c.dispose();
});
test("decode gaps retain explicit member and ordinal diagnostics at a bounded detail cap", () => {
  const d = new LiveDisplay(binding(), config(), new ByteBudget()); const p = delivery("gap", []);
  p.page.batches[0].batch.records_visited = 40;
  p.page.batches[0].batch.problems = Array.from({ length: 40 }, (_, ordinal) => ({ ordinal, code: "RESOURCE_LIMIT", reason: "oversized" }));
  const f = d.consume(p)!;
  assert.equal(f.raw.r.problems, 40); assert.equal((f.raw.r as any).issues.length, 32);
  assert.deepEqual((f.raw.r as any).issues[0], { member_id: "m", ordinal: 8, code: "RESOURCE_LIMIT", reason: "oversized" });
  assert.equal((f.series.r as any).problems, 40); d.dispose();
});
test("pausing removes an already queued display replacement without stopping the in-flight acknowledgement", async () => {
  const b = new ByteBudget(), frames: any[] = [], ids: string[] = [];
  const p = new LivePresentation(b, (id, f) => { ids.push(id); frames.push(f); }, () => {}, 20, 100);
  p.offer({ prefix: "visible" } as any); p.offer({ prefix: "pending" } as any);
  (p as any).hold(); p.acknowledge(ids[0]); await sleep(30);
  assert.equal(frames.length, 1); assert.equal(b.reserved, 2 * decodedBytes({ prefix: "visible" }));
  p.dispose(); assert.equal(b.reserved, 0);
});
test("panels publish only a complete common frontier and account their retained displayed prefix", async () => {
  const f = controller(); f.c.update(view()); f.sinks[0].binding(binding()); await sleep(); const before = f.frames.length;
  f.sinks[0].page(delivery("partial", [sample(0, 98)], undefined, false, false)); await sleep();
  assert.equal(f.frames.length, before, "partial selections cannot update some panels ahead of others");
  f.sinks[0].page(delivery("complete", [sample(1, 99)])); await sleep();
  assert.equal(f.frames.at(-1).prefix, "complete"); assert.ok(f.budget.reserved >= 4 * decodedBytes(f.frames.at(-1)), "model, last completed prefix, host delivery and visible renderer remain accounted");
  f.c.dispose(); assert.equal(f.budget.reserved, 0);
});
test("raw and delivered-value buffers honor fixed and advancing ranges including empty windows", () => {
  const b = binding(); b.time_policy = { mode: "fixed", start_sec: 1, end_sec: 2 };
  const d = new LiveDisplay(b, config(), new ByteBudget());
  const f = d.consume(delivery("a", [sample(0, 0), sample(1, 1), sample(2, 2), sample(3, 3)]))!;
  assert.deepEqual(f.raw.r.samples.map(r => r.ordinal), [1, 2]); assert.equal(f.values.r?.ordinal, 2); assert.equal(f.raw.r.records_visited, 4); d.dispose();
  const live = new LiveDisplay(binding(), config(), new ByteBudget()); live.consume(delivery("a", [sample(0, 99)]));
  const quiet = delivery("b", [], { ...binding().clock, right_edge_sec: 200, robot_time_sec: 200 }); quiet.page.batches = [];
  const empty = live.consume(quiet)!; assert.deepEqual(empty.raw.r.samples, []); assert.equal(empty.values.r, null); assert.deepEqual(empty.series.r.timestamps, []); live.dispose();
});
