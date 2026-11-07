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
  return { preview, page, controller, messages, loads, paint, edit };
}
test("a replica generation change cancels the old query and reloads the same qualified source", () => {
  const f = hostFixture(); let notify: (value: any) => void = () => {};
  f.preview.healthMonitor = { watch: (_source: unknown, callback: (value: any) => void) => { notify = callback; return { dispose() {} }; } };
  (f.controller.snapshot as any).source_ref = { schema_version: 2, server_instance_id: "server", scope_id: "mirror", content: { kind: "file_version", master_store_id: "master", file_id: "file", file_version: "version" } };
  f.paint(); const original = structuredClone(f.controller.snapshot.source_ref);
  notify({ kind: "ok", health: { replica: { generation: "old", content_sha256: "a".repeat(64), verification_basis: "provisional" } } });
  notify({ kind: "ok", health: { replica: { generation: "new", content_sha256: "b".repeat(64), verification_basis: "verified" } } });
  assert.equal(f.loads.length, 2, "new bytes require a fresh query and binding admission");
  assert.equal(f.loads[0].signal.aborted, true); assert.deepEqual(f.controller.snapshot.source_ref, original);
  assert.ok(f.messages.some((m: any) => m.type === "warning" && /Replica content changed/.test(m.message)));
});
test("the first health reply compares with the bytes already displayed", () => {
  const f = hostFixture(); let notify: (value: any) => void = () => {};
  f.preview.healthMonitor = { watch: (_source: unknown, callback: (value: any) => void) => { notify = callback; return { dispose() {} }; } };
  (f.controller.snapshot as any).source_ref = { schema_version: 2, server_instance_id: "server", scope_id: "mirror", content: { kind: "file_version", master_store_id: "master", file_id: "file", file_version: "version" } };
  f.page.inventory = { replica: { generation: "old", content_sha256: "a".repeat(64), verification_basis: "provisional" } };
  f.paint();
  notify({ kind: "ok", health: { replica: { generation: "new", content_sha256: "b".repeat(64), verification_basis: "verified" } } });
  assert.equal(f.loads.length, 2, "a replacement before the first poll must not become the unchanged baseline");
  assert.equal(f.loads[0].signal.aborted, true);
});
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
test("adding a repeated panel re-admits its changed buffer shape before displaying another copy", () => {
  const f=hostFixture();f.paint();const old=f.page.key;
  f.edit(c=>{const panel=structuredClone(c.panels[1]);panel.panel_id="second-table";c.panels.push(panel);});
  assert.equal(f.loads.length,2,"repeated content needs a new admission plan");
  assert.notEqual(f.page.key,old);assert.equal(f.loads[0].signal.aborted,true);
});
/** Small DOM/plot doubles run the shipped script and capture every chart data replacement. */
function renderer(overview = false, uiState: any = {}) {
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
  const pointRanges: any[] = [];
  const timers = new Map<number, () => void>(); let timerId = 0;
  let now = 100; const animations: (() => void)[] = [];
  class Plot {
    series: any[]; axes: any[]; cursor = { left: 20, top: 20, event: null as object | null }; hooks: any;
    select = { left: 0, width: 0, top: 0, height: 0 };
    options: any; data: any; scales: any = {}; cursorSets = 0; destroyed = false;
    constructor(options: any, points: number[][]) { this.options = options; this.series = options.series; this.axes = [{ stroke: () => "white" }]; this.hooks = options.hooks ?? { setCursor: [] }; charts.push(this); this.setData(points); }
    setData(points: number[][]) { this.data = points; replacements.push(points); this.cursorHook(); }
    setSize() { this.cursorHook(); }
    cursorHook() { this.hooks.setCursor.forEach((hook: any) => hook(this)); }
    setSelect(value: any) { Object.assign(this.select, value); }
    setScale(key: string, range: any) { this.scales[key] = range; } posToVal(pixel = 20) { return pixel === 20 ? 0.25 : pixel / 100; } valToPos() { return 20; } setCursor() { this.cursorSets++; }
    destroy() { this.destroyed = true; } static join(tables: number[][][]) { return tables[0]; }
    static paths = { bars: () => () => null, points: () => (_u: any, index: number, first: number, last: number) => { pointRanges.push([index, first, last]); return {}; } };
  }
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, "../../media/visualization.js"), "utf8"), {
    document: { body, getElementById: get, createElement: () => new Element() },
    window: { addEventListener: (type: string, handler: any) => { if (type === "message") receive = handler; } },
    acquireVsCodeApi: () => ({ postMessage: (message: any) => messages.push(message), getState: () => uiState, setState: (value: any) => { Object.assign(uiState, value); } }), getComputedStyle: () => ({ color: "white" }),
    GridStack: { init: () => ({ on() {}, batchUpdate() {}, makeWidget() {}, update() {}, removeWidget() {} }) },
    ResizeObserver: class { observe() {} disconnect() {} }, uPlot: Plot,
    Plot: { seriesOf: () => ({ count: 2, bucketed: false, timestamps: [0, 1], values: [7, 8] }) },
    setTimeout: (callback: () => void) => { timers.set(++timerId, callback); return timerId; },
    clearTimeout: (id: number) => timers.delete(id),
    performance: { now: () => now }, requestAnimationFrame: (callback: () => void) => animations.push(callback),
  });
  return { send: (data: any) => receive({ data }), get, replacements, messages, charts, pointRanges,
    animationFrame: () => { now += 16; const pending = animations.splice(0); pending.forEach(callback => callback()); },
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
test("replica previews disclose provisional and master-disproved bytes", () => {
  const r = renderer(true);
  const inventory = { entries: [], clock_basis: "Robot time", replica: { authority_state: "provisional", content_sha256: "a".repeat(64), generation: "first" } };
  r.send({ type: "inventory", inventory });
  assert.match(r.get("inventory").text(), /Provisional replica/);
  assert.match(r.get("inventory").text(), /aaaaaaaa/);
  r.send({ type: "inventory", inventory: { ...inventory, replica: { ...inventory.replica, authority_state: "authoritative_mismatch" } } });
  assert.match(r.get("inventory").text(), /Master disproved these bytes/);
});

test("a recording overview offers the bounded recording panel preview", () => {
  const r = renderer(true);
  r.send({ type: "summary", summary: { source_ref: { kind: "session" }, label: "Synthetic recording", size_bytes: 12 } });
  assert.equal(r.get("blank").disabled, false, "blank layouts now have a recording controller");
  assert.equal(r.get("template-open").disabled, false);
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
  assert.match(r.get("warning").text(), /Recording panel preview/);
  r.send({ type: "templates", catalog: { templates: [{ template_ref: { origin: "bundled", revision: 1, template_id: "fixture" }, template: { name: "Fixture" } }] } });
  r.get("templates").value = "0"; (r.get("templates") as any).onchange();
  assert.equal(r.messages.some(m => m.type === "templateCheck"), true);
  assert.match(r.get("template-status").text(), /Checking compatibility/);
});

test("a qualified recording overview retains recording controls and clock facts", () => {
  const r = renderer(true);
  r.send({ type: "summary", summary: { source_ref: { schema_version: 2, server_instance_id: "server", scope_id: "scope", content: { kind: "session" } }, label: "Synthetic recording", size_bytes: 12 } });
  assert.equal(r.get("blank").disabled, false, "blank layouts now have a recording controller");
  assert.equal(r.get("template-open").disabled, false);
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
  assert.match(r.get("warning").text(), /Recording panel preview/);
  r.send({ type: "templates", catalog: { templates: [{ template_ref: { origin: "bundled", revision: 1, template_id: "fixture" }, template: { name: "Fixture" } }] } });
  r.get("templates").value = "0"; (r.get("templates") as any).onchange();
  assert.equal(r.messages.some(m => m.type === "templateCheck"), true);
  assert.match(r.get("template-status").text(), /Checking compatibility/);
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

test("paged recording inventory replaces its visible rows while panel creation stays available", () => {
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
  assert.equal(r.get("blank").disabled, false);
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


test("recording saved and unsaved views can be opened in the panel preview", () => {
  const r = renderer(true);
  const lists = { working_views: [{ view_id: "draft-1234", saved_view_ref: null, detached_expires_at: "tomorrow" }],
    saved_views: [{ title: "Saved recording", saved_view_id: "saved", store_id: "store" }], unreadable: [] };
  r.send({ type: "summary", summary: { source_ref: { kind: "session" }, label: "Capture" } });
  r.send({ type: "lists", lists });
  for (const id of ["saved", "drafts"]) {
    const row = r.get(id).children[0], action = row.children.find(e => e.textContent === (id === "saved" ? "Open new view" : "Resume"))!;
    assert.ok(action, "saved and unsaved metadata remain listed"); assert.equal(action.disabled, false, "recording panels use their owned bound follow");
    const before = r.messages.length; (action as any).onclick(); assert.equal(r.messages.length, before + 1);
  }
  r.send({ type: "summary", summary: { source_ref: { kind: "recorded" }, label: "File" } }); r.send({ type: "lists", lists });
  for (const id of ["saved", "drafts"]) {
    const row = r.get(id).children[0], action = row.children.find(e => e.textContent === (id === "saved" ? "Open new view" : "Resume"))!;
    assert.equal(action.disabled, false); const before = r.messages.length; (action as any).onclick(); assert.equal(r.messages.length, before + 1);
  }
});

test("recording renderer keeps exact server values apart from delivered frames and acknowledges both", () => {
  const r = renderer(); const config = configuration(), snapshot = { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: config };
  r.send({ type: "snapshot", generation: "live1", snapshot });
  const frame = { prefix: "prefix-one", finalized: false, range: { start: 0, end: 2 }, clock: { display_basis: "last_sample", right_edge_sec: 2 },
    binding: { compatibility: { bindings: [{ requirement_id: "r1", state: "resolved", entry: "/value", reason: "Exact", warnings: [] }] } },
    series: { r1: { timestamps: [0, 1], values: [7, null], count: 2, trimmed: 3, duplicate_times: 1, unplottable: 1 } },
    values: { r1: { value: { type: "i64", value: "9007199254740993" }, timestamp_us: "1000000", ordinal: 4, member_id: "one" } }, raw: {} };
  r.send({ type: "liveFrame", generation: "live1", delivery_id: "owner:1", frame });
  assert.deepEqual(JSON.parse(JSON.stringify(r.replacements.at(-1))), [[0, 1], [7, null]]);
  assert.doesNotMatch(r.get("grid").text(), /9007199254740993/, "physical delivery must not stand in for latest by timestamp");
  assert.match(r.get("clock").text(), /prefix-one/); r.animationFrame(); r.animationFrame(); assert.equal(r.messages.at(-1).type, "liveRendered"); assert.equal(r.messages.at(-1).delivery_id, "owner:1");
  const before = r.replacements.length;
  r.send({ type: "liveFrame", generation: "stale", delivery_id: "old:2", frame: null });
  assert.equal(r.replacements.length, before); assert.equal(r.messages.at(-1).delivery_id, "old:2", "stale owner's bytes can be released without painting");
  (r.get("live-pause") as any).onclick(); assert.equal(r.messages.at(-1).type, "livePause"); assert.equal(r.messages.at(-1).paused, true);
  (r.get("at") as any).onclick(); assert.equal(r.messages.at(-1).type, "cursor", "the host routes recording cursors to shared recording analysis");
  r.send({ type: "liveFrame", generation: "live1", delivery_id: "owner:3", frame: null });
  assert.match(r.get("clock").text(), /No retained/); assert.equal(r.messages.at(-1).delivery_id, "owner:3");
});
test("recording picker enables adding only a current unambiguous inspected row", () => {
  const r = renderer(), state: any = { generation: 1, loading: false, expired: false, hidden: false, selected: "x", details: { selected_rows: [{ name: "x", type: "int64", conflict: false, unavailable: false }], descriptors: [], schema_problems: [] } };
  r.send({ type: "inventoryPage", state }); assert.equal(r.get("inventory-add").disabled, false);
  for (const key of ["loading", "expired", "hidden"]) { r.send({ type: "inventoryPage", state: { ...state, [key]: true } }); assert.equal(r.get("inventory-add").disabled, true, key); }
  for (const changed of [{ name: "other" }, { conflict: true }, { unavailable: true }, { type: null }]) {
    r.send({ type: "inventoryPage", state: { ...state, details: { ...state.details, selected_rows: [{ ...state.details.selected_rows[0], ...changed }] } } });
    assert.equal(r.get("inventory-add").disabled, true);
  }
});

test("recorded buffers acknowledge rendering and clearing, including discarded stale replies", () => {
  const r = renderer(), snapshot = { view_id: "view", revision: 1, configuration: configuration() };
  r.send({ type: "snapshot", generation: "q", snapshot });
  r.send({ type: "series", generation: "q", requirement_id: "r1", name: "/value", bytes: [], delivery_id: "one", point_limit: 3 });
  assert.equal(r.messages.at(-1).type, "recordedRendered"); assert.equal(r.messages.at(-1).delivery_id,"one");
  r.send({ type: "recordedClear", generation: "q", delivery_id: "clear" });
  assert.equal(r.replacements.at(-1)![0].length,0); assert.equal(r.messages.at(-1).delivery_id,"clear");
  const count=r.replacements.length;
  r.send({ type: "series", generation: "old", requirement_id: "r1", bytes: [], delivery_id: "stale" });
  assert.equal(r.replacements.length,count); assert.equal(r.messages.at(-1).delivery_id,"stale");
});
test("recorded renderer refuses samples beyond the admitted point count without acknowledging ownership", () => {
  const r=renderer(), snapshot={view_id:"v",revision:1,configuration:configuration()};
  r.send({type:"snapshot",generation:"q",snapshot,decoded_buffer_budget_bytes:16777216});
  assert.match(r.get("budget").text(),/16 MiB/);
  assert.throws(()=>r.send({type:"series",generation:"q",requirement_id:"r1",name:"/value",bytes:[],delivery_id:"over",point_limit:1}),/point limit/);
  assert.ok(!r.messages.some(m=>m.delivery_id==="over"));
});
for (const live of [false,true]) test(`hidden ${live ? "live" : "recorded"} clear removes retained table DOM before acknowledging release`,()=>{
  const r=renderer(),snapshot={view_id:"v",revision:1,source_ref:{kind:live?"session":"unmanaged"},configuration:configuration()};
  r.send({type:"snapshot",generation:"q",snapshot});
  r.send({type:"value",generation:"q",requirement_id:"r1",result:{value:{type:"int64",value:"123456789"},timestamp_us:"1000000"}});
  assert.ok(r.get("grid").text().includes("123456789"));
  r.send({type:"visibility",visible:false});
  r.send(live?{type:"liveFrame",generation:"q",frame:null,delivery_id:"clear"}:{type:"recordedClear",generation:"q",delivery_id:"clear"});
  assert.ok(!r.get("grid").text().includes("123456789"),"hidden DOM still owns the data");
  assert.equal(r.messages.at(-1).delivery_id,"clear");
});


test("recording analysis enables cursor and statistics and never derives them from a live frame", () => {
  assert.doesNotMatch(recordedPage(s => s, "csp", "nonce", false), /statistics and historical cursor values are not available/);
  const r = renderer(), config = addPanel(configuration(), "stats", "statistics");
  config.panels.at(-1)!.series = [{ requirement_id: "r1", axis_id: "y", color: "#ffffff" }];
  const snapshot = { view_id: "v", revision: 1, source_ref: { kind: "session" }, configuration: config };
  r.send({ type: "snapshot", generation: "q", snapshot });
  for (const id of ["cursor", "at", "latest", "statistics"]) assert.equal(r.get(id).disabled, false, id);
  r.send({ type: "analysisState", generation: "q", value_epoch: 2, statistics_epoch: 3 });
  const prefix = { catalog_revision: "7", finalized: false, members: [{ member_id: "one", committed_byte_boundary: "128" }] };
  r.send({ type: "value", generation: "q", analysis_epoch: 2, requirement_id: "r1", result: {
    value: { type: "i64", value: "9007199254740993" }, timestamp_us: "9000000", prefix, problems: [], problem_count: 0 }, delivery_id: "value:1" });
  r.send({ type: "statistics", generation: "q", analysis_epoch: 3, requirement_id: "r1", result: { mean: 2.5, count: 4,
    basis: "admitted_recording_prefix", prefix, inputs: { window: { start: 1, end: 4 } } }, delivery_id: "stats:1" });
  assert.match(r.get("grid").text(), /9007199254740993/); assert.match(r.get("grid").text(), /2.5/);
  assert.match(r.get("grid").text(), /Independent analysis prefix/); assert.match(r.get("grid").text(), /catalog 7/);
  assert.match(r.get("grid").text(), /not the plot frontier/);
  const frame = { prefix: "plot", clock: { right_edge_sec: 10 }, binding: { compatibility: { bindings: [] } }, series: {}, raw: {},
    values: { r1: { value: { type: "i64", value: "111" }, timestamp_us: "1000000" } } };
  r.send({ type: "liveFrame", generation: "q", frame, delivery_id: "live:1" });
  assert.match(r.get("grid").text(), /9007199254740993/, "plot updates cannot clear or replace the independently admitted value");
  assert.match(r.get("grid").text(), /2.5/);
  r.send({ type: "value", generation: "q", analysis_epoch: 1, requirement_id: "r1", result: { value: { type: "i64", value: "222" } }, delivery_id: "old:1" });
  r.send({ type: "statistics", generation: "q", analysis_epoch: 2, requirement_id: "r1", result: { mean: 999 }, delivery_id: "old:2" });
  assert.doesNotMatch(r.get("grid").text(), /222|999/); assert.equal(r.messages.at(-1).delivery_id, "old:2");
  r.send({ type: "value", generation: "q", analysis_epoch: 2, requirement_id: "r1", result: { status: "error", error: "Read refused: select a shorter interval" }, delivery_id: "error:1" });
  assert.match(r.get("grid").text(), /Read refused: select a shorter interval/);
  r.send({ type: "visibility", visible: false });
  r.send({ type: "analysisClear", generation: "q", delivery_id: "analysis:clear" });
  assert.doesNotMatch(r.get("grid").text(), /2.5|Read refused/); assert.equal(r.messages.at(-1).delivery_id, "analysis:clear");
});


test("recording picker sends an explicit field path and renders only the matching check result", () => {
  const r = renderer(), state: any = { generation: 8, loading: false, expired: false, hidden: false, selected: "/pose",
    details: { detail_revision: "proof", selected_rows: [{ name: "/pose", type: "struct:Pose", conflict: false, unavailable: false }], descriptors: [], schema_problems: [] } };
  r.send({ type: "snapshot", generation: "q", snapshot: { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: configuration() } });
  r.send({ type: "inventoryPage", state });
  r.get("inventory-field").value = ".translation.x";
  (r.get("inventory-field") as any).oninput(); (r.get("inventory-field-add") as any).onclick();
  const sent = r.messages.at(-1); assert.equal(sent.type, "addInventorySignal"); assert.equal(sent.field_path, ".translation.x");
  assert.equal(sent.inventory_generation, 8); assert.ok(Number.isSafeInteger(sent.field_request));
  assert.equal(r.get("inventory-field-add").disabled, true, "duplicate clicks cannot queue checks");
  r.send({ type: "fieldSelection", generation: "q", inventory_generation: 8, field_request: sent.field_request, field_path: ".translation.x", state: "error", reason: "Member schema disagrees" });
  assert.match(r.get("inventory-field-status").text(), /Member schema disagrees/);
  assert.equal(r.get("inventory-field-add").disabled, false);
  r.get("inventory-field").value = "[1]"; (r.get("inventory-field") as any).oninput();
  r.send({ type: "fieldSelection", generation: "q", inventory_generation: 8, field_request: sent.field_request, field_path: ".translation.x", state: "error", reason: "STALE" });
  assert.doesNotMatch(r.get("inventory-field-status").text(), /STALE/);
  r.send({ type: "inventoryPage", state: { ...state, generation: 9, selected: "/other", details: undefined } });
  assert.equal(r.get("inventory-field").value, "", "a new selected name cannot inherit the old field");
  assert.equal(r.get("inventory-field-add").disabled, true);
});
test("recording field controls are inline, labeled and explain scalar paths without promising inference", () => {
  const html = recordedPage(s => s, "csp", "nonce", false);
  assert.match(html, /Numeric field or array index/); assert.match(html, /id="inventory-field"[^>]*maxlength="512"/);
  assert.match(html, /Check and add field/); assert.match(html, /id="inventory-field-status" role="status"/);
});


test("field controls disable on stale inventory and Enter uses the same explicit gesture", () => {
  const r = renderer(), base: any = { generation: 3, loading: false, expired: false, hidden: false, selected: "/a",
    details: { detail_revision: "p", selected_rows: [{ name: "/a", type: "double[]", conflict: false, unavailable: false }], descriptors: [], schema_problems: [] } };
  r.send({ type: "inventoryPage", state: base }); r.get("inventory-field").value = "[0]"; (r.get("inventory-field") as any).oninput();
  for (const flag of ["loading", "expired", "hidden"]) { r.send({ type: "inventoryPage", state: { ...base, [flag]: true } }); assert.equal(r.get("inventory-field-add").disabled, true); }
  r.send({ type: "inventoryPage", state: base }); assert.equal(r.get("inventory-field-add").disabled, false);
  (r.get("inventory-field") as any).onkeydown({ key: "Enter", preventDefault() {} });
  assert.equal(r.messages.at(-1).field_path, "[0]"); const request = r.messages.at(-1).field_request;
  r.send({ type: "fieldSelection", generation: "", inventory_generation: 2, field_request: request, field_path: "[0]", state: "error", reason: "OLD INVENTORY" });
  assert.doesNotMatch(r.get("inventory-field-status").text(), /OLD INVENTORY/);
  r.send({ type: "fieldSelection", generation: "old", inventory_generation: 3, field_request: request, field_path: "[0]", state: "error", reason: "OLD QUERY" });
  assert.doesNotMatch(r.get("inventory-field-status").text(), /OLD QUERY/);
  r.send({ type: "fieldSelection", generation: "", inventory_generation: 3, field_request: request, field_path: "[0]", state: "cancelled", reason: "Cancelled" });
  assert.equal(r.get("inventory-field-add").disabled, false); assert.match(r.get("inventory-field-status").text(), /Cancelled/);
});

test("chart controls and renderer display server bins and ordered pairs without time cursor coupling", () => {
  const html = recordedPage(n => n, "local", "nonce", false);
  assert.match(html, /id="histogram"/); assert.match(html, /id="scatter"/);
  const r = renderer(), config = configuration();
  config.panels[0].type = "histogram" as any; config.panels[1].type = "scatter" as any;
  config.requirements.push({ ...config.requirements[0], id: "r2", entry: "/y" });
  config.panels[1].series.push({ ...config.panels[1].series[0], requirement_id: "r2" });
  r.send({ type: "snapshot", generation: "q", snapshot: { view_id: "v", revision: 1, configuration: config } });
  const proof = { prefix: { source_kind: "file", finalized: true, members: [] }, inputs: {} };
  r.send({ type: "histogram", generation: "q", requirement_id: "r1", result: { ...proof, status: "ok", histogram_rule: "Server bin rule", summary: { count: 4 }, bins: [{ low: 0, high: 2, count: 2 }, { low: 2, high: 4, count: 2 }] } });
  r.send({ type: "scatter", generation: "q", pair: ["r1", "r2"], result: { ...proof, status: "ok", alignment_rule: "Server pairing rule", points: [[2, 4, 1], [0, 0, 2]], matched: 3, unpaired_x: 1, unpaired_y: 2, offset: 0, next_offset: 2, complete: false, limits: { points: { total: 3, returned: 2 } } } });
  assert.deepEqual(JSON.parse(JSON.stringify(r.replacements.at(-1))), [null, [[2, 0], [4, 0]]], "scatter retains the server order; no interpolation or joining by X");
  assert.ok(r.replacements.some(p => JSON.stringify(p) === '[null,[[1,3],[2,2]]]'), "server bin centers and counts go to the bundled renderer");
  assert.match(r.get("grid").text(), /Server bin rule/); assert.match(r.get("grid").text(), /Server pairing rule/);
  assert.match(r.get("grid").text(), /3.*matched.*1.*unpaired X.*2.*unpaired Y/);
  assert.ok(r.charts.every(c => c.options.mode === 2));
  assert.ok(r.charts.every(c => !c.hooks.setSelect), "value axes do not accept time drag gestures");
  (r.get("at") as any).onclick(); assert.ok(r.charts.every(c => c.cursorSets === 0), "value axes are never a time cursor target");
  const buttons = (e: any): any[] => [e, ...e.children.flatMap(buttons)];
  buttons(r.get("grid")).find((e: any) => e.textContent === "Next pairs").onclick();
  assert.equal(r.messages.at(-1).type, "scatterPage"); assert.equal(r.messages.at(-1).offset, 2);
  r.send({ type: "scatter", generation: "q", pair: ["r1", "r2"], result: { ...proof, status: "ok", alignment_rule: "Server pairing rule", points: [[8, 16, 3]], matched: 3, unpaired_x: 1, unpaired_y: 2, offset: 2, next_offset: null, complete: true, limits: { points: { total: 3, returned: 1 } } } });
  assert.deepEqual(JSON.parse(JSON.stringify(r.replacements.at(-1))), [null, [[8], [16]]], "fresh pages replace, never combine prefixes");
  r.send({ type: "analysisState", generation: "q", value_epoch: 1, statistics_epoch: 1, histogram_epoch: 1, scatter_epoch: 1 });
  const before = r.replacements.length;
  r.send({ type: "histogram", generation: "q", analysis_epoch: 0, delivery_id: "old", requirement_id: "r1", result: { bins: [] } });
  assert.equal(r.replacements.length, before); assert.equal(r.messages.at(-1).delivery_id, "old");
  r.send({ type: "visibility", visible: false }); r.send({ type: "analysisClear", generation: "q", delivery_id: "clear" });
  assert.ok(r.charts.every(c => c.destroyed), "hidden charts release plot storage before acknowledging clear");
  assert.doesNotMatch(r.get("grid").text(), /Server bin rule|Server pairing rule/);
});


test("scatter mode-2 points draw every coordinate with explicit per-series bounds", () => {
  const r = renderer(), config = configuration(); config.panels = [config.panels[0]];
  config.panels[0].type = "scatter" as any; config.requirements.push({ ...config.requirements[0], id: "r2", entry: "/y" });
  config.panels[0].series.push({ ...config.panels[0].series[0], requirement_id: "r2" });
  r.send({ type: "snapshot", generation: "q", snapshot: { view_id: "v", revision: 1, configuration: config } });
  r.send({ type: "scatter", generation: "q", pair: ["r1","r2"], result: { status: "ok", points: [[2,6,1],[8,12,1],[14,18,2]], matched: 3, unpaired_x: 0, unpaired_y: 0, complete: true } });
  const chart = r.charts.at(-1)!;
  assert.equal(typeof chart.options.series[1].points.paths, "function", "mode 2 has no shared time-series indices; it needs its own point bounds");
  chart.options.series[1].points.paths(chart, 1, null, null);
  assert.deepEqual(r.pointRanges, [[1,0,2]]);
});
test("histogram display includes outer bin edges and a zero count baseline", () => {
  const r = renderer(), config = configuration(); config.panels = [config.panels[0]]; config.panels[0].type = "histogram" as any;
  r.send({ type: "snapshot", generation: "q", snapshot: { view_id: "v", revision: 1, configuration: config } });
  r.send({ type: "histogram", generation: "q", requirement_id: "r1", result: { status: "ok", bins: [{low:2,high:8,count:1},{low:8,high:14,count:2}] } });
  const chart = r.charts.at(-1)!;
  assert.equal(chart.scales.x?.min, 2); assert.equal(chart.scales.x?.max, 14); assert.equal(chart.scales.y?.min, 0); assert.ok(chart.scales.y?.max >= 2);
});

function historyRenderer(recording = false, state: any = {}) {
  const r = renderer(false, state), config: any = configuration(); config.time.recorded = { mode: "fixed", start_sec: 10, end_sec: 30 };
  if (recording) config.time.live = { mode: "fixed", start_sec: 10, end_sec: 30 };
  const snapshot = { view_id: "v", revision: 1, source_ref: { kind: recording ? "session" : "unmanaged" }, configuration: config };
  r.send({ type: "snapshot", generation: "q", snapshot });
  r.send({ type: "series", generation: "q", requirement_id: "r1", name: "/value", bytes: [] });
  return { ...r, snapshot };
}
test("history controls expose a fixed time axis and keyboard pan/zoom over that interval", () => {
  const r = historyRenderer(); assert.equal(r.charts[0].scales.x.min, 10); assert.equal(r.charts[0].scales.x.max, 30);
  assert.match(r.get("time-mode").textContent, /Inspect History.*10.*30/);
  for (const [id, action] of [["time-earlier", "earlier"], ["time-later", "later"], ["time-zoom-in", "zoom_in"], ["time-zoom-out", "zoom_out"]]) {
    (r.get(id) as any).onclick(); const m = r.messages.at(-1); assert.equal(m.type, "timeNavigate");
    assert.equal(m.action, action); assert.equal(m.start, 10); assert.equal(m.end, 30);
    r.send({ type: "snapshot", generation: "q", snapshot: r.snapshot });
  }
});
test("plot drag selects an interval once through the persisted range path instead of local scaling", () => {
  const r = historyRenderer(), chart = r.charts[0];
  assert.equal(chart.options.cursor?.drag.setScale, false);
  chart.select = { left: 100, width: 200, top: 0, height: 200 }; chart.cursor.event = { type: "mouseup" };
  chart.hooks.setSelect[0](chart);
  r.send({ type: "snapshot", generation: "q", snapshot: r.snapshot });
  chart.select = { left: 100, width: 200, top: 0, height: 200 }; chart.hooks.setSelect[0](chart);
  const gestures = r.messages.filter(m => m.type === "range"); assert.equal(gestures.length, 1);
  assert.equal(gestures[0].panel_id, "plot"); assert.equal(gestures[0].start, 1); assert.equal(gestures[0].end, 3);
  r.flush(); assert.equal(r.messages.filter(m => m.type === "cursor").length, 0, "drag is not a cursor lookup");
});
test("unlinked plots, redraws and pending or hidden views cannot issue a historical gesture", () => {
  for (const mode of ["unlinked", "redraw", "move", "pending", "hidden"]) {
    const r = historyRenderer(), chart = r.charts[0];
    if (mode === "unlinked") r.snapshot.configuration.links.time_range = false;
    if (mode === "pending") r.send({ type: "snapshot", generation: "q", snapshot: r.snapshot, pending: true });
    if (mode === "hidden") r.send({ type: "visibility", visible: false });
    chart.select = { left: 100, width: 200, top: 0, height: 200 }; chart.cursor.event = mode === "redraw" ? null : { type: mode === "move" ? "mousemove" : "mouseup" };
    assert.equal(typeof chart.hooks.setSelect?.[0], "function"); chart.hooks.setSelect[0](chart);
    assert.equal(r.messages.filter(m => m.type === "range").length, 0, mode);
  }
});
test("resume following restores this view's UI window choice and validates empty range inputs", () => {
  const state: any = { view_id: "v", following_window_sec: 45 }, r = historyRenderer(true, state);
  assert.equal(Number(r.get("live-window").value), 45);
  (r.get("live-follow") as any).onclick(); assert.equal(r.messages.at(-1).window_sec, 45);
  r.send({ type: "snapshot", generation: "q", snapshot: r.snapshot });
  r.get("start").value = ""; r.get("end").value = "2"; (r.get("range") as any).onclick();
  assert.equal(r.messages.filter(m => m.type === "range").length, 0); assert.match(r.get("warning").textContent, /Start.*End/);
});
test("history navigation has persistent keyboard controls and Resume following", () => {
  const page = recordedPage(s => s, "local", "nonce", false);
  for (const id of ["time-earlier", "time-later", "time-zoom-in", "time-zoom-out", "time-mode"]) assert.ok(page.includes(`id="${id}"`), id);
  assert.ok(page.includes("Resume following"));
});

test("navigation is serialized until a snapshot or explained refusal and never blocks retry", () => {
  const r = historyRenderer(); (r.get("time-earlier") as any).onclick(); (r.get("time-earlier") as any).onclick();
  assert.equal(r.messages.filter(m => m.type === "timeNavigate").length, 1); assert.equal(r.get("time-earlier").disabled, true);
  r.send({ type: "warning", message: "Source unavailable; retry" }); assert.equal(r.get("time-earlier").disabled, false);
  (r.get("time-earlier") as any).onclick(); assert.equal(r.messages.filter(m => m.type === "timeNavigate").length, 2);
  r.send({ type: "snapshot", generation: "q", snapshot: r.snapshot, pending: true });
  (r.get("time-earlier") as any).onclick(); assert.equal(r.messages.filter(m => m.type === "timeNavigate").length, 2);
});
test("a restored following-window choice belongs to one view and subsequent follow edits persist it", () => {
  const state: any = { view_id: "other", following_window_sec: 45 }, r = historyRenderer(true, state);
  assert.equal(Number(r.get("live-window").value), 30);
  r.snapshot.configuration.time.live = { mode: "follow", window_sec: 60 };
  r.send({ type: "snapshot", generation: "new", snapshot: r.snapshot });
  assert.equal(Number(r.get("live-window").value), 60); assert.equal(state.view_id, "v"); assert.equal(state.following_window_sec, 60);
  r.get("live-window").value = "50"; (r.get("live-window") as any).onchange(); assert.equal(state.following_window_sec, 50);
  r.get("live-window").value = "500"; (r.get("live-window") as any).onchange(); assert.equal(state.following_window_sec, 50);
});
test("a pointer drag is not a timed cursor request, and short selections do not zoom", () => {
  const r = historyRenderer(), chart = r.charts[0];
  chart.cursor.event = { type: "mousemove", buttons: 1 }; chart.hooks.setCursor[0](chart); r.flush();
  assert.equal(r.messages.filter(m => m.type === "cursor").length, 0);
  chart.cursor.event = { type: "mouseup" }; chart.select = { left: 100, width: 1, top: 0, height: 200 }; chart.hooks.setSelect[0](chart);
  assert.equal(r.messages.filter(m => m.type === "range").length, 0);
});

test("invalid restored following state defaults to 30 and hidden pan controls stay disabled", () => {
  for (const choice of [NaN, 0, 301, "45"]) {
    const r = historyRenderer(true, { view_id: "v", following_window_sec: choice });
    assert.equal(Number(r.get("live-window").value), 30);
    r.send({ type: "visibility", visible: false }); assert.equal(r.get("time-zoom-in").disabled, true);
    (r.get("time-zoom-in") as any).onclick(); assert.equal(r.messages.filter(m => m.type === "timeNavigate").length, 0);
  }
});


test("live frames acknowledge after two animation frames in the renderer clock, but stale or hidden frames never claim paint", () => {
  const r = renderer(), snapshot = { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: configuration() };
  r.send({ type: "snapshot", generation: "q", snapshot });
  const frame = { prefix: "p", clock: { right_edge_sec: 1 }, binding: { compatibility: { bindings: [] } }, series: {}, raw: {} };
  r.send({ type: "liveFrame", generation: "q", frame, delivery_id: "one" });
  assert.equal(r.messages.filter(m => m.type === "liveRendered").length, 0);
  r.animationFrame(); assert.equal(r.messages.filter(m => m.type === "liveRendered").length, 0);
  r.animationFrame(); assert.equal(r.messages.at(-1).rendered, true); assert.equal(r.messages.at(-1).render_elapsed_ms, 32);
  r.send({ type: "liveFrame", generation: "q", frame, delivery_id: "two" });
  r.send({ type: "visibility", visible: false });
  assert.equal(r.messages.at(-1).delivery_id, "two"); assert.equal(r.messages.at(-1).rendered, false);
  const acknowledgements = r.messages.filter(m => m.type === "liveRendered").length;
  r.animationFrame(); r.animationFrame();
  assert.equal(r.messages.filter(m => m.type === "liveRendered").length, acknowledgements, "a hidden frame releases the host without waiting for suspended animation frames, exactly once");
  r.send({ type: "liveFrame", generation: "old", frame, delivery_id: "stale" });
  assert.equal(r.messages.at(-1).delivery_id, "stale"); assert.equal(r.messages.at(-1).rendered, false);
});

test("replacing a live frame releases the old paint acknowledgement without cancelling the current frame", () => {
  const r = renderer(), snapshot = { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: configuration() };
  r.send({ type: "snapshot", generation: "q", snapshot });
  const frame = { prefix: "p", clock: {}, binding: { compatibility: { bindings: [] } }, series: {}, raw: {} };
  r.send({ type: "liveFrame", generation: "q", frame, delivery_id: "one" }); r.animationFrame();
  r.send({ type: "liveFrame", generation: "q", frame, delivery_id: "two" });
  const released = r.messages.filter(m => m.type === "liveRendered");
  assert.equal(released.at(-1)?.delivery_id, "one", JSON.stringify(r.messages)); assert.equal(released.at(-1)?.rendered, false);
  r.send({ type: "liveFrame", generation: "old", frame, delivery_id: "stale" });
  r.animationFrame(); r.animationFrame();
  assert.deepEqual(r.messages.filter(m => m.type === "liveRendered").map(m => [m.delivery_id, m.rendered]), [["one", false], ["stale", false], ["two", true]]);
});

test("recording history renders server extrema instead of the incremental tail and releases on clear", () => {
  const r = renderer(), config = configuration(); config.time.live = { mode: "fixed", start_sec: 0, end_sec: 100 };
  config.time.recorded = { mode: "full" };
  const snapshot = { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: config };
  r.send({ type: "snapshot", generation: "q", snapshot });
  r.send({ type: "analysisState", generation: "q", value_epoch: 0, statistics_epoch: 0, histogram_epoch: 0, scatter_epoch: 0, reduction_epoch: 1, history: true });
  const result = { status: "ok", sample_count: 10000, finite_count: 10000, unplottable_count: 0, problem_count: 0,
    window: { start_us: "0", end_us: "100000000" }, prefix: { source_kind: "session", catalog_revision: "4", finalized: true, members: [] },
    buckets: [{ start_us: "0", end_us: "50000000", min: -8, max: 10000, mean: 1, count: 6000 },
      { start_us: "50000000", end_us: "100000000", min: 0, max: 2, mean: 1, count: 4000 }] };
  r.send({ type: "reduction", generation: "q", requirement_id: "r1", analysis_epoch: 1, delivery_id: "history:1", result });
  assert.ok(r.replacements.length > 0, "a history reduction must draw a chart");
  assert.deepEqual(JSON.parse(JSON.stringify(r.replacements.at(-1))), [[0, 50], [1, 1], [10000, 2], [-8, 0]]);
  assert.match(r.get("grid").text(), /10,000.*original samples/);
  const frame = { prefix: "newer", finalized: true, clock: {}, binding: { compatibility: { bindings: [] } }, raw: {},
    range: { start: 70, end: 100 }, series: { r1: { timestamps: [99], values: [2], count: 1, bucketed: false } } };
  r.send({ type: "liveFrame", generation: "q", frame, delivery_id: "tail:1" });
  assert.deepEqual(JSON.parse(JSON.stringify(r.replacements.at(-1))), [[0, 50], [1, 1], [10000, 2], [-8, 0]], "follow frames cannot replace proven history");
  assert.match(r.get("time-mode").text(), /0 to 100/, "full history keeps the reduction's range, not the prior follow window");
  r.send({ type: "analysisClear", generation: "q", delivery_id: "history:clear" });
  assert.equal(r.charts.filter(c => !c.destroyed).length, 0);
  assert.ok(r.messages.some(m => m.type === "recordedRendered" && m.delivery_id === "history:clear"));
});

test("a historical view never flashes its truncated follow tail before analysis starts", () => {
  const r = renderer(), config = configuration(); config.time.live = { mode: "fixed", start_sec: 0, end_sec: 100 };
  r.send({ type: "snapshot", generation: "q", snapshot: { view_id: "view", revision: 1, source_ref: { kind: "session" }, configuration: config } });
  r.send({ type: "liveFrame", generation: "q", delivery_id: "early", frame: {
    prefix: "p", finalized: false, clock: {}, range: { start: 0, end: 100 }, binding: { compatibility: { bindings: [] } }, raw: {},
    series: { r1: { timestamps: [99], values: [1], count: 1, bucketed: false } }
  } });
  assert.equal(r.charts.filter(c => !c.destroyed).length, 0, "a late reduction must remain a loading state, not a full-history tail");
  assert.match(r.get("grid").text(), /Loading full-interval history/);
});
