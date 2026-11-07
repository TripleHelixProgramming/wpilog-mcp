import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import * as fs from "node:fs";
import * as vm from "node:vm";
const Follow = require(path.join(__dirname, "..", "..", "media", "follow.js"));
const data = (times: number[], values: number[]) => ({ timestamps: new Float64Array(times), columns: { value: new Float64Array(values) }, count: times.length });
test("follow replaces the inclusive boundary without dropping newly arrived duplicate timestamps", () => {
  const before = data([1, 2, 2], [10, 20, 21]), after = data([2, 2, 2, 3], [20, 21, 22, 30]);
  const joined = Follow.append(before, after, 2, 0, 100);
  assert.deepEqual([...joined.timestamps], [1, 2, 2, 2, 3]); assert.deepEqual([...joined.columns.value], [10, 20, 21, 22, 30]);
  const retry = Follow.append(joined, after, 2, 0, 100); assert.deepEqual([...retry.columns.value], [10, 20, 21, 22, 30]);
  assert.deepEqual([...Follow.append(before, data([], []), 3, 0, 100).columns.value], [10, 20, 21]);
});
test("follow bounds memory, retains a hold before the window and follows the latest time", () => {
  const joined = Follow.append(data([0, 1, 2, 3], [0, 10, 20, 30]), data([3, 4, 5], [30, 40, 50]), 3, 2.5, 100);
  assert.deepEqual([...joined.timestamps], [2, 3, 4, 5]);
  assert.deepEqual([...Follow.append(null, data([1, 2, 3, 4], [1, 2, 3, 4]), 0, 0, 2).timestamps], [3, 4]);
  assert.deepEqual(Follow.windowAt({ start: 8, end: 10 }, 0, 15), { start: 13, end: 15 });
  assert.deepEqual(Follow.windowAt({ start: 0, end: 100 }, 3, 10), { start: 3, end: 10 });
  const result = Follow.consoleMatches([{ timestamp_sec: 1 }, { timestamp_sec: 2 }], [{ timestamp_sec: 2 }, { timestamp_sec: 2 }, { timestamp_sec: 3 }], 2, 0, 3);
  assert.deepEqual(result.map((m: { timestamp_sec: number }) => m.timestamp_sec), [2, 2, 3]);
});
test("the plot asks from the last timestamp, appends the response and moves its visible window", () => {
  const Plot = vm.runInNewContext(fs.readFileSync(path.join(__dirname, "..", "..", "media", "plot.js"), "utf8") + "\nPlot", { Follow, PlotMath: { SAMPLE_BUDGET: 100 } });
  const plot = Object.create(Plot.prototype), sent: unknown[] = [];
  const series = { name: "value", pending: null, data: data([1, 2], [10, 20]), sampleCount: 2, bucketed: false };
  plot.log = { start: 0, end: 2, entries: new Map() }; plot.view = { start: 0, end: 2 };
  plot.panes = [{ series: [series] }]; plot.host = { post: (m: unknown) => sent.push(m) };
  plot.nextRequest = 1; plot.requests = new Map(); plot.setView = (start: number, end: number) => { plot.view = { start, end }; };
  plot.drawTimeline = () => {}; plot.drawChips = () => {}; plot.rebuildChart = () => {}; plot.paneOf = () => plot.panes[0];
  plot.follow(3, [{ name: "value", sample_count: 4 }]);
  assert.equal((sent[0] as any).startTime, 2); assert.equal((sent[0] as any).endTime, 3);
  assert.deepEqual(JSON.parse(JSON.stringify(plot.view)), { start: 1, end: 3 });
  Plot.seriesOf = () => ({ data: data([2, 2, 3], [20, 21, 30]), metadata: {}, bucketed: false });
  plot.onData({ requestId: 1, name: "value", bytes: new Uint8Array() });
  assert.deepEqual([...series.data.columns.value], [10, 20, 21, 30]);
});

test("the console follows from its last completed boundary and pages without losing equal timestamps", () => {
  const ConsolePane = vm.runInNewContext(fs.readFileSync(path.join(__dirname, "..", "..", "media", "console.js"), "utf8") + "\nConsolePane", { Follow });
  const pane = Object.create(ConsolePane.prototype), sent: any[] = [];
  pane.plot = { log: { start: 0, end: 10 }, view: { start: 0, end: 10 }, setMarks: () => {} };
  pane.pattern = { value: "" }; pane.level = { value: "any" }; pane.windowOnly = { checked: true };
  pane.count = { textContent: "" }; pane.list = { replaceChildren: () => {}, append: () => {} };
  pane.host = { post: (message: any) => sent.push(message) }; pane.request = 0;
  pane.following = true; pane.followEnd = 8;
  pane.draw = () => {};
  pane.follow();
  assert.equal(sent[0].startTime, 8); assert.equal(sent[0].endTime, 10); assert.equal(sent[0].follow, true);
  pane.onResult({ requestId: 1, result: { status: "ok", matches: [{ timestamp_sec: 8 }], has_more: true } });
  assert.equal(sent[1].offset, 1); assert.equal(sent[1].startTime, 8); assert.equal(sent[1].endTime, 10);
  assert.equal(pane.followEnd, 8, "advance only after the final page");
  // A poll with no new matches must keep earlier visible matches as a successful view.
  pane.onResult({ requestId: 1, result: { status: "no_match", matches: [{ timestamp_sec: 8 }, { timestamp_sec: 9 }], has_more: false } });
  assert.equal(pane.followEnd, 10); assert.equal(pane.pendingFollow, false);
  assert.equal(pane.result.status, "ok");
  assert.deepEqual(JSON.parse(JSON.stringify(pane.result.matches.map((m: any) => m.timestamp_sec))), [8, 8, 9]);
});
