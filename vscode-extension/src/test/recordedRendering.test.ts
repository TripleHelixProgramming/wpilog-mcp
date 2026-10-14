/** Actual host painter and bundled renderer, independent of the data-key implementation. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vm from "node:vm";
import { addPanel, addSignals, RecordedConfiguration, ViewGeneration } from "../visualization/recordedModel";
const loader = require("node:module"), original = loader._load;
let RecordedPreview: typeof import("../visualization/recordedPreview").RecordedPreview;
try { loader._load = (name: string, ...args: any[]) => name === "vscode" ? {} : original(name, ...args); RecordedPreview = require("../visualization/recordedPreview").RecordedPreview; }
finally { loader._load = original; }
function configuration(): RecordedConfiguration {
  const blank = JSON.parse(fs.readFileSync(path.resolve(__dirname, "../../../src/main/resources/visualization/blank-v1.json"), "utf8"));
  let config = addPanel(addPanel(blank, "plot", "time_series"), "table", "latest");
  for (const panel of config.panels) config = addSignals(config, panel.panel_id, [{ name: "/value", type: "double", sample_count: 2 }], () => "r1");
  return config;
}
function hostFixture() {
  const preview: any = Object.create(RecordedPreview.prototype), messages: any[] = [], loads: { signal: AbortSignal; done(): void }[] = [];
  const controller = { snapshot: { view_id: "view", revision: 1, source_ref: { kind: "unmanaged", source_id: "source" }, configuration: configuration() }, pending: false };
  const page: any = { document: { controller }, panel: { visible: true, title: "View", webview: { postMessage: async (message: any) => { messages.push(message); } } }, generation: new ViewGeneration(), key: "", ready: true, closed: false };
  preview.pages = new Map([["view", page]]); preview.rendered = new Map(); preview.targetEntries = () => {};
  preview.loadData = (_page: unknown, _snapshot: unknown, signal: AbortSignal) => new Promise<void>(done => loads.push({ signal, done }));
  const paint = () => preview.paint(page.document);
  const edit = (change: (config: RecordedConfiguration) => void) => { const next = structuredClone(controller.snapshot); next.revision++; change(next.configuration); controller.snapshot = next; paint(); };
  return { page, controller, messages, loads, paint, edit };
}
test("title, geometry, style and revision-only paints retain the same pending or loaded query", async () => {
  const f = hostFixture(); f.paint(); const key = f.page.key;
  f.edit(c => { c.panels[0].title = "Battery"; });
  f.edit(c => { c.layout.items[0].w = 11; });
  f.edit(c => { c.panels[0].series[0].color = "#ffffff"; c.panels[0].display.line_width = 3; });
  assert.equal(f.loads.length, 1, "presentation edits must not redownload Arrow or cursor values");
  assert.equal(f.loads[0].signal.aborted, false, "an in-flight answer still belongs to this query");
  assert.equal(f.page.key, key);
  assert.deepEqual(f.messages.filter(m => m.type === "snapshot").map(m => m.snapshot.revision), [1, 2, 3, 4]);
  f.loads[0].done(); await f.page.rendering; f.edit(() => {});
  assert.equal(f.loads.length, 1, "a completed answer survives a revision-only checkpoint");
});
test("series membership, selector, source and recorded range changes cancel only the old query", () => {
  const f = hostFixture(); f.paint(); let count = 1;
  for (const change of [
    (c: RecordedConfiguration) => { c.time.recorded = { mode: "fixed", start_sec: 0, end_sec: 1 }; },
    (c: RecordedConfiguration) => { c.requirements[0].field_path = "/x"; },
    (c: RecordedConfiguration) => { c.requirements[0].accepted_entry_types = ["float"]; },
    (c: RecordedConfiguration) => { c.panels[0].series = []; },
  ]) {
    const previous = f.page.key; f.edit(change); count++;
    assert.equal(f.loads.length, count); assert.notEqual(f.page.key, previous);
    assert.equal(f.loads[count - 2].signal.aborted, true);
  }
  f.controller.snapshot.source_ref.source_id = "replacement"; f.paint();
  assert.equal(f.loads.length, ++count, "source identity participates without a configuration change");
  f.page.panel.visible = false; f.edit(c => { c.time.recorded = { mode: "full" }; });
  assert.equal(f.loads.length, count, "hidden views do not load");
  f.page.panel.visible = true; f.paint(); assert.equal(f.loads.length, count + 1);
});
/** Small DOM/plot doubles run the shipped script and capture every chart data replacement. */
function renderer(overview = false) {
  class Element {
    children: Element[] = []; dataset: any = {}; value = ""; textContent = ""; selectedOptions: unknown[] = [];
    clientWidth = 400; clientHeight = 240; className = "";
    append(...items: Element[]) { this.children.push(...items); }
    replaceChildren(...items: Element[]) { this.children = items; }
    setAttribute() {} addEventListener() {} querySelectorAll() { return []; }
    text(): string { return this.textContent + this.children.map(c => c.text()).join(""); }
  }
  const elements = new Map<string, Element>(), body = new Element(); body.dataset.overview = String(overview);
  const get = (id: string) => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id)!; };
  let receive: (event: any) => void = () => {}; const replacements: number[][][] = [], messages: any[] = [], charts: Plot[] = [];
  const timers = new Map<number, () => void>(); let timerId = 0;
  class Plot {
    series: any[]; axes: any[]; cursor = { left: 20, top: 20, event: null as object | null }; hooks: any;
    constructor(options: any, points: number[][]) { this.series = options.series; this.axes = [{ stroke: () => "white" }]; this.hooks = options.hooks; charts.push(this); this.setData(points); }
    setData(points: number[][]) { replacements.push(points); this.cursorHook(); }
    setSize() { this.cursorHook(); }
    cursorHook() { this.hooks.setCursor.forEach((hook: any) => hook(this)); }
    posToVal() { return 0.25; } valToPos() { return 20; } setCursor() {}
    destroy() {} static join(tables: number[][][]) { return tables[0]; }
  }
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, "../../media/visualization.js"), "utf8"), {
    document: { body, getElementById: get, createElement: () => new Element() },
    window: { addEventListener: (type: string, handler: any) => { if (type === "message") receive = handler; } },
    acquireVsCodeApi: () => ({ postMessage: (message: any) => messages.push(message) }), getComputedStyle: () => ({ color: "white" }),
    GridStack: { init: () => ({ on() {}, batchUpdate() {}, makeWidget() {}, update() {}, removeWidget() {} }) },
    ResizeObserver: class { observe() {} disconnect() {} }, uPlot: Plot,
    Plot: { seriesOf: () => ({ count: 2, bucketed: false, timestamps: [0, 1], values: [7, 8] }) },
    setTimeout: (callback: () => void) => { timers.set(++timerId, callback); return timerId; },
    clearTimeout: (id: number) => timers.delete(id),
  });
  return { send: (data: any) => receive({ data }), get, replacements, messages, charts,
    flush: () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()); } };
}
test("the webview retains samples and values for presentation revisions and rejects an old query", () => {
  const r = renderer(); const snapshot = { view_id: "view", revision: 1, configuration: configuration() };
  r.send({ type: "snapshot", generation: "query1", snapshot });
  r.send({ type: "series", generation: "query1", requirement_id: "r1", name: "/value", bytes: [] });
  r.send({ type: "value", generation: "query1", requirement_id: "r1", result: { value: { type: "int64", value: "8" }, timestamp_us: "1000000" } });
  const before = r.replacements.length;
  const next = structuredClone(snapshot); next.revision++; next.configuration.panels[0].title = "Battery"; next.configuration.layout.items[0].w = 11;
  r.send({ type: "snapshot", generation: "query1", snapshot: next });
  assert.ok(r.replacements.slice(before).every(points => points[0].length === 2), "no intermediate empty chart on a layout edit");
  assert.ok(r.get("grid").text().includes("1000000"), "cursor values survive layout edits too");
  next.configuration.time.recorded = { mode: "fixed", start_sec: 0, end_sec: 0.5 };
  r.send({ type: "snapshot", generation: "query2", snapshot: next });
  assert.equal(r.replacements.at(-1)![0].length, 0, "new queries invalidate old samples");
  const after = r.replacements.length;
  r.send({ type: "series", generation: "query1", requirement_id: "r1", name: "/value", bytes: [] });
  assert.equal(r.replacements.length, after, "late data from an old query cannot render");
});
test("overview shows unreadable shared records beside its readable saved views", () => {
  const r = renderer(true);
  r.send({ type: "lists", lists: { working_views: [], saved_views: [{ title: "Readable", saved_view_id: "good", store_id: "store" }],
    unreadable: [{ store_id: "store", file: "bad.json", reason: "Unsupported schema version" }] } });
  assert.match(r.get("saved").text(), /Readable/);
  assert.match(r.get("unreadable").text(), /bad.json.*Unsupported schema version/);
});


test("painting or resizing never turns a programmatic uPlot cursor update into a user cursor request", () => {
  const r = renderer(), snapshot = { view_id: "view", revision: 1, configuration: configuration() };
  r.send({ type: "snapshot", generation: "query", snapshot });
  r.send({ type: "series", generation: "query", requirement_id: "r1", name: "/value", bytes: [] });
  r.flush();
  const next = structuredClone(snapshot); next.revision++; next.configuration.layout.items[0].w = 11;
  r.send({ type: "snapshot", generation: "query", snapshot: next }); r.flush();
  assert.equal(r.messages.filter(m => m.type === "cursor").length, 0, "programmatic setData/setSize must not request cursor samples");
  r.charts[0].cursor.event = {}; r.charts[0].cursorHook(); r.flush();
  const requests = r.messages.filter(m => m.type === "cursor");
  assert.equal(requests.length, 1, "a real cursor gesture still requests original samples");
  assert.equal(requests[0].at, 0.25);
  // uPlot's deferred redraw can reuse its last input event after the paint call returned.
  r.charts[0].cursorHook(); r.flush();
  assert.equal(r.messages.filter(m => m.type === "cursor").length, 1, "deferred programmatic hooks must not replay the last gesture");
});
