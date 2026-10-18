import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ViewGeneration } from "../visualization/recordedModel";
const loader = require("node:module"), original = loader._load;
let Preview: any;
try { loader._load = (name: string, ...args: any[]) => name === "vscode" ? {} : original(name, ...args); Preview = require("../visualization/recordedPreview").RecordedPreview; }
finally { loader._load = original; }
test("recording views route to their bound controller without single-file inventory or value reads", () => {
  const calls: any[] = [], preview = Object.create(Preview.prototype);
  const config = { requirements: [], panels: [], layout: { columns: 12, items: [] }, time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } }, links: { cursor: true, time_range: true } };
  const controller = { snapshot: { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: config }, pending: false };
  const page: any = { document: { controller }, panel: { visible: true, webview: { postMessage: () => {} } }, generation: new ViewGeneration(), key: "", ready: true, closed: false };
  preview.pages = new Map([["view", page]]); preview.rendered = new Map(); preview.targetEntries = () => {};
  preview.liveFor = () => ({ update: (...args: any[]) => calls.push(args), show() {} });
  preview.loadData = () => { calls.push("single-file read"); return Promise.resolve(); };
  preview.paint(page.document);
  assert.equal(calls.length, 1); assert.notEqual(calls[0], "single-file read"); assert.equal(calls[0][0], controller.snapshot);
});
test("recording fixed-range edits retain the same interval when reopened after finalization", async () => {
  const preview = Object.create(Preview.prototype); preview.paint = () => {};
  const configuration: any = { time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } } };
  let edited: any;
  const controller = { snapshot: { view_id: "v", revision: 1, source_ref: { kind: "session" }, configuration }, edit: async (c: any) => { edited = c; } };
  await preview.message({ closed: false, key: "k", document: { controller } }, { type: "range", view_id: "v", revision: 1, generation: "k", start: 2, end: 5 });
  assert.deepEqual(edited.time.live, { mode: "fixed", start_sec: 2, end_sec: 5 });
  assert.deepEqual(edited.time.recorded, { mode: "fixed", start_sec: 2, end_sec: 5 });
});
test("control resynchronization reattaches metadata before reopening recording acquisition", async () => {
  const preview = Object.create(Preview.prototype), calls: string[] = []; let event: (e: any) => void = () => {};
  const client = { onVisualization: (f: any) => { event = f; return { dispose() {} }; } };
  preview.host = { client: async () => client }; preview.paint = () => calls.push("paint"); preview.warning = (_p: any, e: any) => assert.fail(String(e));
  const snapshot = { view_id: "v", revision: 1 }; const controller = { snapshot, agentChanged: async () => calls.push("apply") };
  const page = { closed: false, serial: Promise.resolve(), document: { controller }, live: { refresh: () => calls.push("follow") } };
  preview.registerWindow = async () => { calls.push("budget"); };
  preview.pages = new Map([["v", page]]); preview.backend = { attach: async () => { calls.push("attach"); return snapshot; } };
  await preview.controls(); event({ kind: "resync_required" }); await page.serial;
  assert.deepEqual(calls, ["budget", "attach", "apply", "paint", "follow"]);
});

test("recording cursor and pause gestures reach the recording analysis owner without a single-file read", async () => {
  const preview = Object.create(Preview.prototype), calls: any[] = [];
  preview.loadValues = () => assert.fail("recorded file lookup");
  const page = { closed: false, key: "q", document: { controller: { snapshot: { view_id: "v", revision: 1, source_ref: { kind: "session" } } } },
    analysis: { cursor: (at: number) => calls.push(["cursor", at]), pause: (paused: boolean) => calls.push(["pause", paused]), refresh: () => calls.push(["refresh"]) },
    live: { pause() {}, refresh() {} } };
  for (const extra of [{ type: "cursor", at: 1.25 }, { type: "livePause", paused: true }, { type: "liveRetry" }])
    await preview.message(page, { view_id: "v", generation: "q", revision: 1, ...extra });
  assert.deepEqual(calls, [["cursor", 1.25], ["pause", true], ["refresh"]]);
  await preview.message(page, { view_id: "v", generation: "q", type: "cursor", at: NaN });
  assert.equal(calls.length, 3, "non-finite cursor messages cannot enter analysis");
});
test("recording statistics panels can be created through the same persisted layout gesture", async () => {
  const preview = Object.create(Preview.prototype); preview.paint = () => {};
  let edited: any;
  const controller = { snapshot: { view_id: "v", revision: 1, source_ref: { kind: "session" }, configuration: {
    panels: [], layout: { items: [] }, requirements: [] } }, edit: async (c: any) => { edited = c; } };
  await preview.message({ closed: false, key: "q", document: { controller } }, { view_id: "v", generation: "q", revision: 1, type: "addPanel", panel_type: "statistics" });
  assert.equal(edited.panels[0].type, "statistics");
});

function selectionFixture(type = "struct:Pose") {
  const preview = Object.create(Preview.prototype), calls: any[] = [], edits: any[] = [], messages: any[] = [];
  const blank = require("node:fs").readFileSync(require("node:path").resolve(__dirname, "../../../src/main/resources/visualization/blank-v1.json"), "utf8");
  const { addPanel } = require("../visualization/recordedModel"), { VisualizationBudgets } = require("../visualization/byteBudget");
  const source = { kind: "session", session_id: "recording", anchor_member: "member" };
  const controller = { snapshot: { view_id: "v", revision: 1, source_ref: source, configuration: addPanel(JSON.parse(blank), "p", "time_series") },
    edit: async (config: any) => { edits.push(config); } };
  const row = { row_id: "row", name: "/pose", type, conflict: false, unavailable: false, members: [] };
  const state = { generation: 8, loading: false, hidden: false, expired: false, selected: "/pose",
    details: { detail_revision: "proof", selected_rows: [row] } };
  const page: any = { closed: false, key: "q", document: { controller }, panel: { visible: true, webview: { postMessage: (m: any) => messages.push(m) } }, recordingInventory: { snapshot: state } };
  preview.paint = () => {}; preview.budgets = new VisualizationBudgets();
  const reply = (body: any) => { const r = body.configuration.requirements[0]; return { source_ref: source, binding_basis: "fresh_recording_prefix", compatibility: "compatible",
    bindings: [{ requirement_id: r.id, state: "resolved", entry: r.entry, declared_type: type, field_path: r.field_path, warnings: [] }] }; };
  preview.backend = { call: async (...args: any[]) => { calls.push(args); return reply(args[1]); } };
  const message = { view_id: "v", revision: 1, generation: "q", type: "addInventorySignal", panel_id: "p", inventory_generation: 8, field_path: ".translation.x", field_request: 1 };
  return { preview, page, controller, state, calls, edits, messages, message, reply, run: (extra = {}) => preview.message(page, { ...message, ...extra }) };
}
test("recording picker checks an exact numeric field through shared compatibility before editing", async () => {
  const f = selectionFixture(); await f.run();
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0][0], "/templates/check");
  const body = f.calls[0][1], r = body.configuration.requirements[0];
  assert.equal(body.source_ref, f.controller.snapshot.source_ref);
  assert.equal(body.configuration.requirements.length, 1); assert.equal(body.configuration.panels.length, 0);
  assert.equal(r.entry, "/pose"); assert.equal(r.field_path, ".translation.x"); assert.equal(r.value_type, "number");
  assert.deepEqual(r.accepted_entry_types, ["struct:Pose"]);
  assert.equal(f.edits.length, 1); assert.equal(f.edits[0].requirements[0].field_path, ".translation.x");
  assert.equal(f.calls[0][3].maxResponseBytes, 1 << 20); assert.equal(f.calls[0][3].maxRequestBytes, 64 << 10); assert.equal(f.calls[0][3].timeoutMs, 15000);
  assert.equal(f.preview.budgets.decoded.reserved, 0); assert.equal(f.preview.budgets.encoded.reserved, 0);
});
test("raw panels still use numeric field requirements when selecting a structured leaf", async () => {
  const f = selectionFixture(); f.controller.snapshot.configuration.panels[0].type = "raw";
  await f.run(); assert.equal(f.edits[0].requirements[0].value_type, "number");
});
test("field refusal shows member evidence and never saves an unresolved selection", async () => {
  const f = selectionFixture("double[]");
  f.preview.backend.call = async (_route: string, body: any) => { const reply = f.reply(body); reply.compatibility = "incompatible";
    Object.assign(reply.bindings[0], { state: "unresolved", reason: "Member evidence disagrees", problems: [{ member_id: "rollover", reason: "No numeric sample at [9]" }] }); return reply; };
  await assert.rejects(f.run({ field_path: "[9]" }), /rollover.*No numeric sample/); assert.equal(f.edits.length, 0);
  assert.equal(f.preview.budgets.decoded.reserved, 0); assert.equal(f.preview.budgets.encoded.reserved, 0);
});
test("field input is passed literally to the server rather than guessed from schemas or sample values", async () => {
  const f = selectionFixture("double[]"); await f.run({ field_path: "[2]" });
  assert.equal(f.calls[0][1].configuration.requirements[0].field_path, "[2]");
  assert.equal(f.edits[0].requirements[0].field_path, "[2]");
});

for (const [label, change] of [
  ["view revision", (f: any) => f.controller.snapshot.revision++],
  ["query generation", (f: any) => f.page.key = "replacement"],
  ["inventory generation", (f: any) => f.state.generation++],
  ["selected name", (f: any) => f.state.selected = "/other"],
  ["detail evidence", (f: any) => f.state.details.detail_revision = "new-proof"],
  ["inventory expiry", (f: any) => f.state.expired = true],
  ["inventory loading", (f: any) => f.state.loading = true],
  ["inventory hidden", (f: any) => f.state.hidden = true],
  ["hidden panel", (f: any) => f.page.panel.visible = false],
  ["closed panel", (f: any) => f.page.closed = true],
  ["new gesture", (f: any) => f.preview.cancelSelection(f.page)],
] as const) test(`an uncancellable field reply cannot edit after a changed ${label}`, async () => {
  const f = selectionFixture(); let complete: () => void = () => {};
  f.preview.backend.call = (_route: string, body: any) => new Promise(resolve => { complete = () => resolve(f.reply(body)); });
  const task = f.run(); void task.catch(() => {}); change(f); complete(); await task;
  assert.equal(f.edits.length, 0); assert.equal(f.preview.budgets.decoded.reserved, 0); assert.equal(f.preview.budgets.encoded.reserved, 0);
  assert.equal(f.messages.some((m: any) => m.state === "added"), false);
});
for (const [label, change] of [
  ["source", (r: any) => r.source_ref = { kind: "session", session_id: "other" }],
  ["basis", (r: any) => r.binding_basis = "cached"],
  ["field", (r: any) => r.bindings[0].field_path = ".other"],
  ["requirement", (r: any) => r.bindings[0].requirement_id = "other"],
  ["entry", (r: any) => r.bindings[0].entry = "/other"],
  ["type", (r: any) => r.bindings[0].declared_type = "float"],
  ["state", (r: any) => r.bindings[0].state = "approximate"],
  ["compatibility", (r: any) => r.compatibility = "partial"],
  ["extra binding", (r: any) => r.bindings.push(r.bindings[0])],
] as const) test(`field admission refuses an incorrect response ${label}`, async () => {
  const f = selectionFixture(); f.preview.backend.call = async (_route: string, body: any) => { const reply = f.reply(body); change(reply); return reply; };
  await assert.rejects(f.run(), /Invalid recording field response/); assert.equal(f.edits.length, 0);
  assert.equal(f.preview.budgets.decoded.reserved, 0); assert.equal(f.preview.budgets.encoded.reserved, 0);
});
for (const field of ["", " ", 4, ".".repeat(513)]) test(`invalid field input ${JSON.stringify(field).slice(0, 32)} never starts a check`, async () => {
  const f = selectionFixture(); await assert.rejects(f.run({ field_path: field }), /1–512/); assert.equal(f.calls.length, 0); assert.equal(f.edits.length, 0);
});
test("field admission reserves shared bytes before I/O, releases on failure and sizes the 16 MiB window", async () => {
  const { VisualizationBudgets } = require("../visualization/byteBudget");
  const f = selectionFixture(); f.preview.budgets = new VisualizationBudgets(16);
  f.preview.backend.call = async (_route: string, _body: any, signal: AbortSignal, options: any) => {
    assert.equal(options.maxResponseBytes, 256 << 10); assert.equal(f.preview.budgets.encoded.reserved, 512 << 10);
    assert.equal(f.preview.budgets.decoded.reserved, 4 << 20); assert.equal(signal.aborted, false); throw new Error("Service busy; retry");
  };
  await assert.rejects(f.run(), /Service busy/); assert.equal(f.edits.length, 0);
  assert.equal(f.preview.budgets.decoded.reserved, 0); assert.equal(f.preview.budgets.encoded.reserved, 0);
  const held = f.preview.budgets.decoded.reserve(16 << 20);
  f.preview.backend.call = () => assert.fail("No request fits while the shared allowance is full");
  await assert.rejects(f.run(), /Window byte budget/); held(); assert.equal(f.preview.budgets.encoded.reserved, 0);
});
test("field results exceeding the reserved decoded allowance cannot be admitted", async () => {
  const f = selectionFixture(); f.preview.backend.call = async (_route: string, body: any) => ({ ...f.reply(body), overflow: "x".repeat(9 << 20) });
  await assert.rejects(f.run(), /exceeds this window/); assert.equal(f.edits.length, 0); assert.equal(f.preview.budgets.decoded.reserved, 0);
});
test("a checked field is one reusable numeric requirement across raw, latest and statistics panels", async () => {
  const { addPanel, addSignals } = require("../visualization/recordedModel"); const f = selectionFixture();
  f.controller.snapshot.configuration.panels[0].type = "raw"; await f.run();
  let config = f.edits[0];
  for (const type of ["latest", "statistics"]) config = addSignals(addPanel(config, type, type), type, [{ name: "/pose", type: "struct:Pose", field: ".translation.x" }], () => assert.fail("field must be reused"));
  assert.equal(config.requirements.length, 1); assert.equal(config.requirements[0].value_type, "number");
  assert.equal(config.panels[2].series[0].requirement_id, config.panels[0].series[0].requirement_id);
});
test("render lifecycle cancels field requests immediately even while the view message queue is busy", async () => {
  const f = selectionFixture(); let receive: (m: any) => void = () => {}, visible: () => void = () => {}, close: () => void = () => {};
  f.preview.pages = new Map(); f.preview.rendered = new Map(); f.preview.overviews = new Map(); f.preview.targetEntries = () => {}; f.preview.pageHtml = () => "fixture";
  Object.assign(f.page.panel, { onDidChangeViewState: (fn: any) => visible = fn, onDidDispose: (fn: any) => close = fn });
  f.page.panel.webview.onDidReceiveMessage = (fn: any) => receive = fn;
  await f.preview.render(f.page.document, f.page.panel);
  const page = f.preview.pages.get("v"); page.key = "q"; page.recordingInventory = { snapshot: f.state, hide() {}, dispose() {} };
  let abort = new AbortController(); page.selectionCheck = abort;
  let finish: () => void = () => {}; page.serial = new Promise<void>(done => finish = done);
  receive({ type: "cancelFieldSelection", view_id: "v", generation: "q" }); assert.equal(abort.signal.aborted, true, "typing cancels before queued gestures finish");
  abort = new AbortController(); page.selectionCheck = abort; f.page.panel.visible = false; visible(); assert.equal(abort.signal.aborted, true, "hide cancels");
  abort = new AbortController(); page.selectionCheck = abort; close(); assert.equal(abort.signal.aborted, true, "close cancels");
  finish(); await page.serial;
});


test("cancelling a field check with another gesture releases its inline busy state", async () => {
  const f = selectionFixture(); let finish: () => void = () => {};
  f.preview.backend.call = (_r: string, body: any) => new Promise(resolve => finish = () => resolve(f.reply(body)));
  const task = f.run(); void task.catch(() => {}); f.preview.cancelSelection(f.page);
  assert.equal(f.messages.at(-1).state, "cancelled"); assert.equal(f.messages.at(-1).field_request, 1);
  finish(); await task; assert.equal(f.edits.length, 0);
});

for (const [label, change] of [
  ["loading", (f: any) => f.state.loading = true], ["expired", (f: any) => f.state.expired = true],
  ["hidden", (f: any) => f.state.hidden = true], ["generation", (f: any) => f.state.generation++],
  ["selection", (f: any) => f.state.selected = "/other"], ["missing detail", (f: any) => f.state.details = undefined],
  ["conflict", (f: any) => f.state.details.selected_rows[0].conflict = true],
  ["unavailable", (f: any) => f.state.details.selected_rows[0].unavailable = true],
  ["type", (f: any) => f.state.details.selected_rows[0].type = null],
] as const) test(`field selection requires a fresh inspected row: ${label}`, async () => {
  const f = selectionFixture(); change(f); await assert.rejects(f.run(), /Inspect a current/); assert.equal(f.calls.length, 0); assert.equal(f.edits.length, 0);
});
test("whole-entry selection remains separate and a composite gives the inline-field remedy", async () => {
  const f = selectionFixture("double"); await f.run({ field_path: undefined }); assert.equal(f.calls.length, 0);
  assert.equal(f.edits[0].requirements[0].field_path, null);
  const structured = selectionFixture(); await assert.rejects(structured.run({ field_path: undefined }), /Select a numeric field or array index first/);
});

test("a compatible field is not reported added until the revisioned edit succeeds", async () => {
  const f = selectionFixture(); f.controller.edit = async () => { throw new Error("REVISION_CONFLICT"); };
  await assert.rejects(f.run(), /REVISION_CONFLICT/);
  assert.equal(f.messages.some((m: any) => m.state === "added"), false);
});

test("chart gestures persist the panel and axis order while page navigation stays transient", async () => {
  const preview = Object.create(Preview.prototype), calls: any[] = [];
  preview.paint = () => {};
  const configuration: any = { requirements: [], panels: [], layout: { items: [] } };
  const controller = { snapshot: { view_id: "v", revision: 1, source_ref: { kind: "session" }, configuration }, edit: async (c: any) => { controller.snapshot.configuration = c; calls.push(["edit", c]); } };
  const page = { closed: false, key: "q", document: { controller }, analysis: { scatterPage: (...args: any[]) => calls.push(["page", ...args]), refresh: () => calls.push(["retry"]) } };
  const send = (extra: any) => preview.message(page, { view_id: "v", generation: "q", revision: 1, ...extra });
  for (const type of ["histogram", "scatter"]) await send({ type: "addPanel", panel_type: type });
  const scatter = controller.snapshot.configuration.panels[1]; scatter.series = [{ requirement_id: "x" }, { requirement_id: "y" }];
  await send({ type: "scatterSwap", panel_id: scatter.panel_id });
  assert.deepEqual(controller.snapshot.configuration.panels[1].series.map((s: any) => s.requirement_id), ["y", "x"]);
  await send({ type: "scatterPage", panel_id: scatter.panel_id, offset: 64 }); await send({ type: "analysisRetry" });
  assert.equal(calls.filter(c => c[0] === "edit").length, 3); assert.deepEqual(calls.slice(-2), [["page", scatter.panel_id, 64], ["retry"]]);
  await preview.message(page, { view_id: "v", generation: "old", type: "scatterPage", offset: 0 }); assert.equal(calls.length, 5);
});

function navigationFixture(recording = true) {
  const preview = Object.create(Preview.prototype), edits: any[] = []; preview.paint = () => {};
  const configuration = { panels: [{ panel_id: "p", type: "time_series", series: [] }], links: { cursor: true, time_range: true },
    time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 45 } } };
  const controller: any = { snapshot: { view_id: "v", revision: 7, source_ref: { kind: recording ? "session" : "unmanaged" }, configuration },
    edit: async (c: any) => { edits.push(c); controller.snapshot = { ...controller.snapshot, revision: 8, configuration: c }; } };
  const page = { closed: false, key: "q", document: { controller } };
  return { edits, controller, run: (extra: any) => preview.message(page, { view_id: "v", revision: 7, generation: "q", ...extra }) };
}
test("Full recording explicitly rebinds the view even when its recorded policy was already full", async () => {
  const preview = Object.create(Preview.prototype), paints: any[] = [], resumed: boolean[] = [];
  preview.paint = (...args: any[]) => paints.push(args);
  const controller = { snapshot: { view_id: "v", revision: 1, source_ref: { kind: "session" }, configuration: {
    time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } },
  } }, edit: async () => {} };
  const page = { closed: false, key: "q", document: { controller }, live: { pause: (p: boolean) => resumed.push(p) } };
  await preview.message(page, { type: "full", view_id: "v", revision: 1, generation: "q" });
  assert.deepEqual(paints, [[page.document, true]], "the explicit gesture must bypass query-key reuse");
  assert.deepEqual(resumed, [false]);
});
for (const [action, expected] of [
  ["earlier", [0, 20]], ["later", [20, 40]], ["zoom_in", [15, 25]], ["zoom_out", [0, 40]],
] as const) test(`history ${action} edits both recording policies using the displayed interval`, async () => {
  const f = navigationFixture(); await f.run({ type: "timeNavigate", action, start: 10, end: 30 });
  assert.equal(f.edits.length, 1);
  assert.deepEqual(f.edits[0].time.live, { mode: "fixed", start_sec: expected[0], end_sec: expected[1] });
  assert.deepEqual(f.edits[0].time.recorded, f.edits[0].time.live);
});
test("file history gestures preserve the unused live policy", async () => {
  const f = navigationFixture(false); await f.run({ type: "timeNavigate", action: "earlier", start: -4, end: 0 });
  assert.equal(f.edits.length, 1); assert.deepEqual(f.edits[0].time.recorded, { mode: "fixed", start_sec: -6, end_sec: -2 });
  assert.deepEqual(f.edits[0].time.live, { mode: "follow", window_sec: 45 });
});
for (const [start, end] of [[NaN, 2], [0, Infinity], [2, 1], [0, 9_007_199_255], ["1", 2], [null, 2]])
  test(`history range refuses invalid robot-clock bounds ${start}..${end}`, async () => {
    const f = navigationFixture(); await assert.rejects(f.run({ type: "range", start, end }), /finite.*robot|ordered.*robot/); assert.equal(f.edits.length, 0);
  });
test("a drag gesture cannot change an unlinked or non-time panel", async () => {
  for (const change of [(c: any) => c.links.time_range = false, (c: any) => c.panels[0].type = "scatter", (c: any) => c.panels = []]) {
    const f = navigationFixture(); change(f.controller.snapshot.configuration);
    await assert.rejects(f.run({ type: "range", panel_id: "p", start: 1, end: 2 }), /linked time-series/); assert.equal(f.edits.length, 0);
  }
});
test("a current range survives late navigation from another generation or revision", async () => {
  const f = navigationFixture(); await f.run({ type: "timeNavigate", action: "later", start: 10, end: 30, generation: "old" }); assert.equal(f.edits.length, 0);
  await assert.rejects(f.run({ type: "timeNavigate", action: "later", start: 10, end: 30, revision: 6 }), /view changed/); assert.equal(f.edits.length, 0);
});
for (const window_sec of [0, 301, NaN, Infinity, "30"]) test(`resume following refuses invalid window ${window_sec}`, async () => {
  const f = navigationFixture(); await assert.rejects(f.run({ type: "liveFollow", window_sec }), /1–300/); assert.equal(f.edits.length, 0);
});
test("history rejects invalid actions, collapsed zooms and out-of-domain panning", async () => {
  for (const message of [
    { action: "guess", start: 0, end: 2 }, { action: "zoom_in", start: 1, end: 1 },
    { action: "later", start: 9_007_199_252, end: 9_007_199_254 },
  ]) { const f = navigationFixture(); await assert.rejects(f.run({ type: "timeNavigate", ...message }), /history|robot/); assert.equal(f.edits.length, 0); }
});

test("reapplying an unchanged time interval explicitly resumes a paused view", async () => {
  const preview = Object.create(Preview.prototype), resumed: string[] = []; preview.paint = () => {};
  const range = { mode: "fixed", start_sec: 1, end_sec: 2 };
  const controller = { snapshot: { view_id: "v", revision: 1, source_ref: { kind: "session" }, configuration: { time: { recorded: range, live: range } } }, edit: async () => {} };
  const page = { closed: false, key: "q", document: { controller },
    live: { pause: (value: boolean) => { assert.equal(value, false); resumed.push("plot"); } },
    analysis: { pause: (value: boolean) => { assert.equal(value, false); resumed.push("analysis"); } } };
  await preview.message(page, { view_id: "v", revision: 1, generation: "q", type: "range", start: 1, end: 2 });
  assert.deepEqual(resumed.sort(), ["analysis", "plot"]);
});
test("a navigation message must name its action", async () => {
  const f = navigationFixture(); await assert.rejects(f.run({ type: "timeNavigate", start: 1, end: 2 }), /history/); assert.equal(f.edits.length, 0);
});

test("a plot range is persisted in native microseconds rather than arbitrary pixel precision", async () => {
  const f = navigationFixture(); await f.run({ type: "range", start: 1.1234564, end: 2.1234567, panel_id: "p" });
  assert.deepEqual(f.edits[0].time.live, { mode: "fixed", start_sec: 1.123456, end_sec: 2.123457 });
});
