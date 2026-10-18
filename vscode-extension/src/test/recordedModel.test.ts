import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { addPanel, addSignals, layoutEdit, RecordedConfiguration, ViewGeneration, requireRecordedPreview } from "../visualization/recordedModel";
const blank = (): RecordedConfiguration => JSON.parse(fs.readFileSync(path.resolve(__dirname, "../../../src/main/resources/visualization/blank-v1.json"), "utf8"));
const signal = { name: "/value", type: "double", sample_count: 3 };
test("independent view edits never mutate their source configuration", () => {
  const source = blank(), a = addPanel(source, "a", "time_series"), b = addPanel(source, "b", "latest");
  const edited = addSignals(a, "a", [signal], () => "r1");
  assert.equal(source.panels.length, 0); assert.equal(a.requirements.length, 0); assert.equal(b.requirements.length, 0);
  assert.deepEqual(edited.requirements[0], { id: "r1", selector_kind: "entry", entry: "/value", field_path: null, accepted_entry_types: ["double"], value_type: "number", unit: null });
});
test("keyboard movement reorders whole panels and respects the twelve-column boundaries", () => {
  let config = addPanel(addPanel(blank(), "a", "time_series"), "b", "latest");
  config = layoutEdit(config, "b", "earlier");
  assert.equal(config.layout.items.find(i => i.panel_id === "b")!.y, 0);
  assert.equal(config.layout.items.find(i => i.panel_id === "a")!.y, 5);
  config = layoutEdit(config, "b", "narrower"); config = layoutEdit(config, "b", "right"); config = layoutEdit(config, "b", "wider");
  assert.deepEqual(config.layout.items.find(i => i.panel_id === "b"), { panel_id: "b", x: 1, y: 0, w: 11, h: 5 });
  config = layoutEdit(config, "b", "right"); assert.equal(config.layout.items.find(i => i.panel_id === "b")!.x, 1);
});
test("removing one panel retains requirements used by another, then drops unused ones", () => {
  let config = addPanel(addPanel(blank(), "a", "time_series"), "b", "latest");
  config = addSignals(config, "a", [signal], () => "r1"); config = addSignals(config, "b", [signal], () => "unexpected");
  assert.equal(config.requirements.length, 1);
  config = layoutEdit(config, "a", "remove"); assert.equal(config.requirements.length, 1);
  config = layoutEdit(config, "b", "remove"); assert.equal(config.requirements.length, 0); assert.deepEqual(config.layout.items, []);
});
test("signal types and per-view resource bounds refuse rather than silently coerce or trim", () => {
  let config = addPanel(blank(), "a", "time_series");
  assert.throws(() => addSignals(config, "a", [{ ...signal, type: "string" }], () => "r1"), /value table/);
  assert.throws(() => addSignals(config, "a", [{ ...signal, type: "struct:Thing" }], () => "r1"), /numeric field or array index/);
  let id = 0; config = addSignals(config, "a", Array.from({ length: 32 }, (_, i) => ({ ...signal, name: String(i) })), () => `r${id++}`);
  assert.throws(() => addSignals(config, "a", [signal], () => "r33"), /32 series/);
  assert.equal(config.requirements.length, 32);
});
test("stale data is cancelled on query change, hiding, or disposal without affecting another view", () => {
  const a = new ViewGeneration(), b = new ViewGeneration();
  const first = a.begin("a", 1), other = b.begin("b", 1); const next = a.begin("a", 2);
  assert.equal(b.current(first.key), false, "the same request counter does not identify the same view");
  assert.equal(first.signal.aborted, true); assert.equal(a.current(first.key), false); assert.equal(a.current(next.key), true);
  a.cancel(); assert.equal(next.signal.aborted, true); assert.equal(b.current(other.key), true);
});


test("a recovered definition cannot bypass recorded preview resource admission", () => {
  const config = addPanel(blank(), "a", "time_series");
  config.panels[0].series = Array.from({ length: 33 }, (_, i) => ({ requirement_id: String(i), axis_id: "y", color: "#ffffff" }));
  assert.throws(() => requireRecordedPreview(config), /32 series/);
  config.panels[0].series.pop(); requireRecordedPreview(config);
  (config.panels[0] as any).type = "field2d";
  assert.throws(() => requireRecordedPreview(config), /later visualization stage/);
});

test("histogram and scatter are numeric panels with explicit ordered scatter axes", () => {
  for (const type of ["histogram", "scatter"]) {
    const config = addPanel(blank(), "p", type as any);
    assert.doesNotThrow(() => requireRecordedPreview(config));
    assert.equal(config.panels[0].title, type === "histogram" ? "Histogram" : "Scatter");
    assert.throws(() => addSignals(config, "p", [{ ...signal, type: "string" }], () => "r"), /value table/);
  }
  let id = 0;
  const pair = addSignals(addPanel(blank(), "p", "scatter" as any), "p", [signal, { ...signal, name: "/y" }], () => `r${id++}`);
  assert.deepEqual(pair.panels[0].series.map(s => s.requirement_id), ["r0", "r1"]);
  assert.throws(() => addSignals(pair, "p", [{ ...signal, name: "/z" }], () => "r2"), /two.*X.*Y/);
  pair.panels[0].series.push({ ...pair.panels[0].series[0] });
  assert.throws(() => requireRecordedPreview(pair), /two.*X.*Y/);
});
test("scatter query identity includes ordered pairs and excludes titles and geometry", () => {
  const { recordedDataKey } = require("../visualization/recordedModel");
  let id = 0;
  const config = addSignals(addPanel(blank(), "p", "scatter" as any), "p", [signal, { ...signal, name: "/y" }], () => `r${id++}`);
  const source = { kind: "unmanaged", source_id: "s" }, before = recordedDataKey(source, config);
  config.panels[0].title = "Phase portrait"; config.layout.items[0].w = 6;
  assert.equal(recordedDataKey(source, config), before);
  config.panels[0].series.reverse(); assert.notEqual(recordedDataKey(source, config), before);
});

test("recorded reuse admits statistics and bounded raw panels without enabling later panels", () => {
  for (const type of ["statistics", "raw"] as const) {
    const config = addPanel(blank(), "p", type as any);
    assert.doesNotThrow(() => requireRecordedPreview(config));
    assert.equal(config.panels[0].title, type === "statistics" ? "Statistics" : "Raw records");
  }
});
test("data identity includes explicit role and unit compatibility constraints", () => {
  const { recordedDataKey } = require("../visualization/recordedModel");
  const config = addSignals(addPanel(blank(), "p", "time_series"), "p", [signal], () => "r");
  const source = { kind: "unmanaged", source_id: "a" };
  const r: any = config.requirements[0]; r.selector_kind = "role"; delete r.entry; r.role = "battery_voltage";
  const before = recordedDataKey(source, config); r.role = "total_current";
  assert.notEqual(recordedDataKey(source, config), before);
  const role = recordedDataKey(source, config); r.unit = { symbol: "V", basis: "user_declared" };
  assert.notEqual(recordedDataKey(source, config), role);
});
