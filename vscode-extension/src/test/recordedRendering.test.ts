/** Actual host painter and bundled renderer, independent of the data-key implementation. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vm from "node:vm";
import { addPanel, addSignals, RecordedConfiguration, ViewGeneration } from "../visualization/recordedModel";
import { InventoryOverview } from "../visualization/inventoryOverview";
import { InventoryBackend, InventoryHandle } from "../visualization/inventoryClient";
import { ByteBudget } from "../visualization/byteBudget";
import { LocalControlError } from "../mcpClient";
import { recordedPage } from "../visualization/recordedPage";
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
    clientWidth = 400; clientHeight = 240; className = ""; disabled = false;
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

test("a recording overview is explicitly inspection-only until live panels are wired", () => {
  const r = renderer(true);
  r.send({ type: "summary", summary: { source_ref: { kind: "session" }, label: "Synthetic recording", size_bytes: 12 } });
  assert.equal(r.get("blank").disabled, true, "do not offer a view the editor cannot render yet");
  assert.equal(r.get("template-open").disabled, true);
  r.send({ type: "inventory", inventory: { source_kind: "session", entries: [], members: [{ member_id: "one" }], entry_conflicts: [{ name: "/x" }], duration_sec: 2, clock: { basis: "last_sample", waiting_for_time_sync: true }, clock_basis: "Robot time" } });
  assert.match(r.get("status").text(), /Recording inspected/);
  assert.match(r.get("inventory").text(), /1 member.*1 conflicting/);
  assert.match(r.get("inventory").text(), /Last sample; waiting for time sync/);
  assert.doesNotMatch(r.get("inventory").text(), /last_sample|clock\.basis/);
  for (const [basis, waiting, label] of [
    ["robot_estimate", false, "Robot clock estimate"], ["last_sample", false, "Last recorded sample"],
    ["waiting_for_time_sync", true, "Waiting for time sync"], ["unavailable", false, "No recorded samples"],
  ] as const) {
    r.send({ type: "inventory", inventory: { source_kind: "session", entries: [], members: [], entry_conflicts: [],
      clock: { basis, waiting_for_time_sync: waiting }, clock_basis: "clock.basis is a protocol tag" } });
    assert.ok(r.get("inventory").text().includes(label), basis);
    assert.doesNotMatch(r.get("inventory").text(), /clock\.basis/);
  }
  assert.match(r.get("warning").text(), /existing Explorer/);
  r.send({ type: "templates", catalog: { templates: [{ template_ref: { origin: "bundled", revision: 1, template_id: "fixture" }, template: { name: "Fixture" } }] } });
  r.get("templates").value = "0"; (r.get("templates") as any).onchange();
  assert.equal(r.messages.some(m => m.type === "templateCheck"), false);
  assert.match(r.get("template-status").text(), /Live template binding is not available/);
});

test("recorded overview actions remain enabled after receiving recorded facts", () => {
  const r = renderer(true);
  r.send({ type: "summary", summary: { source_ref: { kind: "session" }, label: "Capture", size_bytes: 12 } });
  r.send({ type: "summary", summary: { source_ref: { kind: "recorded" }, label: "File", size_bytes: 12 } });
  assert.equal(r.get("blank").disabled, false); assert.equal(r.get("template-open").disabled, false);
  r.send({ type: "inventory", inventory: { entries: [], duration_sec: 2, clock_basis: "Recorded clock" } });
  assert.equal(r.get("status").text(), "Recorded source ready");
  assert.equal(r.get("warning").text(), "");
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

test("paged recording inventory replaces its visible rows without enabling live bindings", () => {
  const r = renderer(true);
  r.send({ type: "summary", summary: { source_ref: { kind: "session" }, label: "Large recording" } });
  r.send({ type: "inventoryPage", state: { generation: 1, loading: false, expired: false, selected: "/one", previous: false, next: true,
    summary: { total_entry_names: 20000, members: [{}, {}], duration_sec: 2, start_time_sec: 1, end_time_sec: 3, damaged: true, clock: { basis: "last_sample" } }, page: { total_matching: 20000, unavailable_excluded: true, unavailable_entry_names: 0,
      entries: [{ name: "/one", type: "int64", sample_count: "3", row_id: "r1", members: [] }] } } });
  assert.match(r.get("inventory-count").text(), /20,000/);
  assert.match(r.get("inventory").text(), /Duration 2 s/);
  assert.match(r.get("inventory").text(), /Recorded bounds 1.*3 s/);
  assert.match(r.get("inventory").text(), /Incomplete or damaged/);
  assert.match(r.get("inventory-rows").text(), /\/one/);
  assert.equal(r.get("inventory-previous").disabled, true);
  assert.equal(r.get("inventory-next").disabled, false);
  assert.equal(r.get("blank").disabled, true);
  r.send({ type: "inventoryPage", state: { generation: 2, loading: false, expired: false, previous: true, next: false,
    page: { total_matching: 1, unavailable_excluded: true, unavailable_entry_names: 0, entries: [{ name: "/two", type: "double", sample_count: "2", row_id: "r2", members: [] }] } } });
  assert.doesNotMatch(r.get("inventory-rows").text(), /\/one/);
  assert.match(r.get("inventory-rows").text(), /\/two/);
  (r.get("inventory-next") as any).onclick();
  assert.equal(r.messages.at(-1).type, "inventoryNext");
  assert.equal(r.messages.at(-1).inventory_generation, 2);
});

test("overview filter keys and unavailable previews cannot become inferred names", () => {
  const r = renderer(true);
  r.get("inventory-search").value = "literal.*"; r.get("inventory-type").value = "struct:Pose2d";
  (r.get("inventory-unavailable") as any).checked = true;
  (r.get("inventory-search") as any).onkeydown({ key: "Enter", preventDefault() {} });
  assert.deepEqual(JSON.parse(JSON.stringify(r.messages.at(-1))), { type: "inventoryFilter", generation: "", query: "literal.*", exact_type: "struct:Pose2d", unavailable: true });
  const state = { generation: 5, loading: false, expired: false, previous: false, next: false,
    page: { total_matching: 1, total_entry_names: 1, unavailable_excluded: false, unavailable_entry_names: 1,
      entries: [{ unavailable: true, conflict: false, display_preview: "/huge", reason: "Too long", sample_count: null, members: [] }] } };
  r.send({ type: "inventoryPage", delivery_id: "owner:5", state });
  assert.match(r.get("inventory-rows").text(), /unavailable: Too long/);
  const tr = r.get("inventory-rows").children[0]; assert.equal(tr.children.at(-1)?.children[0].disabled, true);
  assert.equal(r.messages.at(-1).type, "inventoryRendered"); assert.equal(r.messages.at(-1).delivery_id, "owner:5");
  r.send({ type: "inventoryPage", delivery_id: "owner:4", state: { ...state, generation: 4, page: { ...state.page, entries: [] } } });
  assert.match(r.get("inventory-rows").text(), /huge/, "obsolete renderer state cannot replace rows");
  assert.equal(r.messages.at(-1).delivery_id, "owner:4", "discarded deliveries still release their reservation");
});

test("a recreated overview restores filter controls without overwriting an unapplied edit", () => {
  const r = renderer(true), state = { generation: 8, loading: false, hidden: false, expired: false,
    filter: { query: "needle", types: ["int64"], unavailable: true } };
  r.send({ type: "inventoryPage", state });
  assert.equal(r.get("inventory-search").value, "needle"); assert.equal(r.get("inventory-type").value, "int64");
  assert.equal((r.get("inventory-unavailable") as any).checked, true);
  r.get("inventory-search").value = "typing a new filter";
  r.send({ type: "inventoryPage", state: { ...state, generation: 9, loading: true } });
  assert.equal(r.get("inventory-search").value, "typing a new filter");
});

test("a real expiry followed by refused admission leaves renderer rows readable but actions disabled", async t => {
  const r = renderer(true), now = Date.now(), source = { kind: "session", session_id: "synthetic" };
  let invalid = false;
  const backend: InventoryBackend = {
    open: async () => { if (invalid) throw new LocalControlError("Capacity refused", 413, "RESOURCE_LIMIT", true);
      return { summary: { inventory_mode: "paged", inventory_id: "old", server_epoch: "epoch", expires_at: new Date(now + 300000).toISOString(),
        idle_expiry_ms: 60000, source_ref: source, total_entry_names: 2, conflicting_entry_names: 0, unavailable_entry_names: 0, members: [], clock: {} }, session: "client", client: {} } as unknown as InventoryHandle; },
    list: async () => { if (invalid) throw new LocalControlError("Idle expired", 410, "INVENTORY_EXPIRED", true);
      return { entries: [{ name: "/known", row_id: "old-row", type: "int64", sample_count: "3", conflict: false, unavailable: false, members: [] }],
        returned: 1, total_matching: 2, total_entry_names: 2, unavailable_excluded: true, unavailable_entry_names: 0, complete: false, next_cursor: "next" }; },
    details: async () => { throw new Error("unexpected detail read"); }, close: async () => {},
  };
  const view = new InventoryOverview(source, backend, new ByteBudget(), state => r.send({ type: "inventoryPage", state }),
    { now: () => now, later: () => ({}), cancel() {} }); t.after(() => view.dispose());
  await view.show(); invalid = true; await view.navigate(1, view.snapshot.generation);
  for (let i = 0; i < 10; i++) await new Promise<void>(resolve => setImmediate(resolve));
  assert.match(r.get("inventory-age").text(), /Expired; Retry/);
  assert.match(r.get("inventory-reason").text(), /Capacity refused/); assert.match(r.get("inventory-rows").text(), /\/known/);
  assert.equal(r.get("inventory-next").disabled, true); assert.equal(r.get("inventory-previous").disabled, true);
  assert.equal(r.get("inventory-find").disabled, true, "expired rows can only be refreshed, not queried again");
  assert.equal(r.get("inventory-rows").children[0].children.at(-1)!.children[0].disabled, true);
});

test("unavailable mode is exclusive and restores ordinary filters after switching back", () => {
  const html = recordedPage(s => s, "csp", "nonce", true), r = renderer(true);
  assert.match(html, /Show unavailable declarations/); assert.doesNotMatch(html, /Include unavailable/);
  const state = { generation: 1, loading: false, expired: false, hidden: false, previous: false, next: false,
    filter: { query: "literal.*", types: ["double"], unavailable: true },
    page: { entries: [], total_matching: 0, total_entry_names: 20000, unavailable_entry_names: 0, unavailable_excluded: false } };
  r.send({ type: "inventoryPage", state });
  assert.equal(r.get("inventory-search").disabled, true); assert.equal(r.get("inventory-type").disabled, true);
  assert.equal(r.get("inventory-search").value, "literal.*"); assert.equal(r.get("inventory-type").value, "double");
  assert.match(r.get("inventory-count").text(), /Unavailable declarations only/);
  assert.match(r.get("inventory-count").text(), /0 shown of 0 unavailable declarations/);
  assert.match(r.get("inventory-count").text(), /turn off this mode/);
  assert.doesNotMatch(r.get("inventory-count").text(), /Applied search/);
  (r.get("inventory-unavailable") as any).checked = false; (r.get("inventory-unavailable") as any).onchange();
  assert.equal(r.get("inventory-search").disabled, false); assert.equal(r.get("inventory-type").disabled, false);
  (r.get("inventory-find") as any).onclick();
  assert.equal(r.messages.at(-1).query, "literal.*"); assert.equal(r.messages.at(-1).exact_type, "double"); assert.equal(r.messages.at(-1).unavailable, false);
});


test("recording view metadata stays visible with opening disabled until panel wiring", () => {
  const r = renderer(true);
  const lists = { working_views: [{ view_id: "draft-1234", saved_view_ref: null, detached_expires_at: "tomorrow" }],
    saved_views: [{ title: "Saved recording", saved_view_id: "saved", store_id: "store" }], unreadable: [] };
  r.send({ type: "summary", summary: { source_ref: { kind: "session" }, label: "Capture" } });
  r.send({ type: "lists", lists });
  for (const id of ["saved", "drafts"]) {
    const row = r.get(id).children[0], action = row.children.find(e => e.textContent === (id === "saved" ? "Open new view" : "Resume"))!;
    assert.ok(action, "saved and unsaved metadata remain listed"); assert.equal(action.disabled, true, "recording panels are not enabled by API metadata creation");
    assert.match(row.text(), /existing Explorer/);
    const before = r.messages.length; (action as any).onclick(); assert.equal(r.messages.length, before);
  }
  r.send({ type: "summary", summary: { source_ref: { kind: "recorded" }, label: "File" } }); r.send({ type: "lists", lists });
  for (const id of ["saved", "drafts"]) {
    const row = r.get(id).children[0], action = row.children.find(e => e.textContent === (id === "saved" ? "Open new view" : "Resume"))!;
    assert.equal(action.disabled, false); const before = r.messages.length; (action as any).onclick(); assert.equal(r.messages.length, before + 1);
  }
});
