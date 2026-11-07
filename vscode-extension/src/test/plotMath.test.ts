// Tests for the plot's arithmetic (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";

// eslint-disable-next-line @typescript-eslint/no-var-requires
const PlotMath = require(path.join(__dirname, "..", "..", "media", "plotMath.js")) as {
  SAMPLE_BUDGET: number;
  MAX_BUCKETS: number;
  planRequest(series: { sampleCount: number; logStart: number; logEnd: number }, view: { start: number; end: number; widthPx: number }, budget?: number): { mode: string; startTime?: number; endTime?: number; maxPoints?: number };
  planCovers(plan: { mode: string; startTime?: number; endTime?: number } | undefined, view: { start: number; end: number }): boolean;
  requestKey(name: string, plan: { mode: string; startTime?: number; endTime?: number; maxPoints?: number }): string;
  drawMode(sampling: string | undefined, bucketed: boolean): string;
  lowerBound(times: Float64Array, t: number): number;
  visibleRange(times: Float64Array, t0: number, t1: number): [number, number];
  reduce(times: Float64Array, values: Float64Array, t0: number, t1: number, widthPx: number): { times: Float64Array; values: Float64Array; reduced: boolean };
  zoom(view: { start: number; end: number }, factor: number, about: number, logStart: number, logEnd: number): { start: number; end: number };
  pan(view: { start: number; end: number }, fraction: number, logStart: number, logEnd: number): { start: number; end: number };
  indexAtOrBefore(times: Float64Array, t: number): number;
  fieldTransform(lengthM: number, widthM: number, canvasW: number, canvasH: number, margin?: number): { scale: number; x0: number; y0: number; width: number; height: number; toX(x: number): number; toY(y: number): number };
  markColumns(times: number[], t0: number, t1: number, widthPx: number): number[];
};

test("a series within the budget is fetched whole; over it, the window exactly when it fits, else bucketed at a few per pixel", () => {
  const small = { sampleCount: 30_000, logStart: 0, logEnd: 600 };
  assert.deepEqual(PlotMath.planRequest(small, { start: 10, end: 20, widthPx: 800 }), { mode: "full" });
  const huge = { sampleCount: 36_000_000, logStart: 0, logEnd: 3600 }; // 10 kHz for an hour
  assert.deepEqual(PlotMath.planRequest(huge, { start: 0, end: 3600, widthPx: 800 }),
    { mode: "bucketed", startTime: 0, endTime: 3600, maxPoints: 3200 });
  // A window of 100 s holds a million samples: the budget allows it exactly
  assert.deepEqual(PlotMath.planRequest(huge, { start: 100, end: 200, widthPx: 800 }),
    { mode: "window", startTime: 100, endTime: 200 });
  assert.equal(PlotMath.planRequest(huge, { start: 0, end: 3600, widthPx: 100_000 }).maxPoints, PlotMath.MAX_BUCKETS, "capped at the server's limit");
  assert.equal(PlotMath.planRequest(huge, { start: 0, end: 3600, widthPx: 10 }).maxPoints, 100, "never fewer than a hundred");
  assert.deepEqual(PlotMath.planRequest({ sampleCount: 10, logStart: 0, logEnd: 1 }, { start: 0, end: 1, widthPx: 1 }, 5),
    { mode: "bucketed", startTime: 0, endTime: 1, maxPoints: 100 }, "a budget of five on ten samples");
  assert.equal(PlotMath.SAMPLE_BUDGET, 4_000_000);
  // The budget is the boundary: a series of exactly that many samples is fetched whole
  const span = { logStart: 0, logEnd: 100 };
  assert.deepEqual(PlotMath.planRequest({ sampleCount: 1000, ...span }, { start: 0, end: 100, widthPx: 800 }, 1000), { mode: "full" });
  assert.notEqual(PlotMath.planRequest({ sampleCount: 1001, ...span }, { start: 0, end: 100, widthPx: 800 }, 1000).mode, "full");
  assert.deepEqual(PlotMath.planRequest({ sampleCount: 999, ...span }, { start: 0, end: 100, widthPx: 800 }, 1000), { mode: "full" });
});

test("a full fetch covers any view; a window only views inside it; buckets never", () => {
  assert.ok(PlotMath.planCovers({ mode: "full" }, { start: 0, end: 1e9 }));
  assert.ok(PlotMath.planCovers({ mode: "window", startTime: 100, endTime: 200 }, { start: 120, end: 180 }));
  assert.ok(!PlotMath.planCovers({ mode: "window", startTime: 100, endTime: 200 }, { start: 90, end: 180 }));
  assert.ok(!PlotMath.planCovers({ mode: "bucketed", startTime: 100, endTime: 200 }, { start: 100, end: 200 }));
  assert.ok(!PlotMath.planCovers(undefined, { start: 0, end: 1 }));
  assert.equal(PlotMath.requestKey("/A", { mode: "full" }), "/A|full");
  assert.equal(PlotMath.requestKey("/A", { mode: "bucketed", startTime: 1, endTime: 2, maxPoints: 300 }), "/A|bucketed|1|2|300");
});

test("change-only sampling is drawn as steps, buckets as a band, the rest as lines", () => {
  assert.equal(PlotMath.drawMode("change_only", false), "steps");
  assert.equal(PlotMath.drawMode("periodic", false), "lines");
  assert.equal(PlotMath.drawMode("event", false), "lines");
  assert.equal(PlotMath.drawMode(undefined, false), "lines");
  assert.equal(PlotMath.drawMode("change_only", true), "band");
});

test("the visible slice holds the samples in the window and one on each side", () => {
  const times = Float64Array.from([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
  assert.deepEqual(PlotMath.visibleRange(times, 3, 5), [2, 7]);
  assert.deepEqual(PlotMath.visibleRange(times, -5, 100), [0, 10]);
  assert.deepEqual(PlotMath.visibleRange(times, 3.5, 3.7), [3, 5], "a window between samples still shows the hold");
  assert.deepEqual(PlotMath.visibleRange(times, 20, 30), [9, 10], "past the end: the last sample's hold");
  assert.equal(PlotMath.lowerBound(times, 4.5), 5);
  assert.equal(PlotMath.indexAtOrBefore(times, 4.5), 4);
  assert.equal(PlotMath.indexAtOrBefore(times, 4), 4);
  assert.equal(PlotMath.indexAtOrBefore(times, -1), -1);
});

test("few samples pass through unreduced; many are reduced to first, min, max, last per pixel column, in time order", () => {
  const n = 1000;
  const times = new Float64Array(n);
  const values = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    times[i] = i * 0.01; // 10 s at 100 Hz
    values[i] = Math.sin(i / 20);
  }
  values[357] = 50; // a spike one sample wide
  values[612] = -50;
  const few = PlotMath.reduce(times, values, 0, 10, 400);
  assert.equal(few.reduced, false, "1000 samples over 400 px is under four per pixel");
  assert.equal(few.times.length, n);
  const reduced = PlotMath.reduce(times, values, 0, 10, 10);
  assert.ok(reduced.reduced);
  assert.ok(reduced.times.length <= 40, `${reduced.times.length} points for 10 columns`);
  assert.ok(reduced.values.includes(50), "the spike survives");
  assert.ok(reduced.values.includes(-50));
  for (let i = 1; i < reduced.times.length; i++) assert.ok(reduced.times[i] >= reduced.times[i - 1], "time order");
  // Each column keeps its first and last sample
  assert.equal(reduced.times[0], 0);
  assert.equal(reduced.times[reduced.times.length - 1], times[n - 1]);
  // The exact extremes of a column are the column's min and max
  const column3 = { lo: 300, hi: 400 }; // 3 s to 4 s at 10 px over 10 s
  let min = Infinity;
  let max = -Infinity;
  for (let i = column3.lo; i < column3.hi; i++) { min = Math.min(min, values[i]); max = Math.max(max, values[i]); }
  const inColumn = [...reduced.times.keys()].filter((k) => reduced.times[k] >= 3 && reduced.times[k] < 4).map((k) => reduced.values[k]);
  assert.ok(inColumn.includes(min) && inColumn.includes(max), `column 3 keeps ${min} and ${max}: ${inColumn}`);
});

test("NaN samples are kept as a column's first or last but never as an extreme", () => {
  const times = Float64Array.from([0, 1, 2, 3, 4, 5, 6, 7]);
  const values = Float64Array.from([NaN, 1, 9, 2, NaN, 3, 0, NaN]);
  const reduced = PlotMath.reduce(times, values, 0, 7, 1);
  assert.ok(reduced.reduced);
  // One column: first (NaN at 0), min (0 at 6), max (9 at 2), last (NaN at 7), in time order
  assert.deepEqual([...reduced.times], [0, 2, 6, 7]);
  assert.ok(Number.isNaN(reduced.values[0]) && reduced.values[1] === 9 && reduced.values[2] === 0 && Number.isNaN(reduced.values[3]));
});

test("zoom keeps the point under the cursor in place and stays inside the log; pan stops at the ends", () => {
  const z = PlotMath.zoom({ start: 100, end: 200 }, 0.5, 150, 0, 1000);
  assert.deepEqual(z, { start: 125, end: 175 });
  const edge = PlotMath.zoom({ start: 0, end: 100 }, 2, 0, 0, 1000);
  assert.deepEqual(edge, { start: 0, end: 200 });
  assert.deepEqual(PlotMath.zoom({ start: 0, end: 100 }, 100, 50, 0, 1000), { start: 0, end: 1000 }, "zooming out past the log shows the log");
  assert.deepEqual(PlotMath.pan({ start: 100, end: 200 }, 0.1, 0, 1000), { start: 110, end: 210 });
  assert.deepEqual(PlotMath.pan({ start: 950, end: 1000 }, 0.5, 0, 1000), { start: 950, end: 1000 });
  assert.deepEqual(PlotMath.pan({ start: 0, end: 50 }, -1, 0, 1000), { start: 0, end: 50 });
});

test("the field fits the canvas with its long side along the width, and y points up", () => {
  const t = PlotMath.fieldTransform(16.54, 8.07, 800, 400, 0);
  assert.ok(Math.abs(t.scale - 800 / 16.54) < 1e-9, "limited by the width");
  assert.equal(t.toX(0), 0);
  assert.ok(Math.abs(t.toX(16.54) - 800) < 1e-9);
  assert.ok(Math.abs(t.toY(0) - (200 + 8.07 * t.scale / 2)) < 1e-9, "y = 0 is the bottom edge of the centered field");
  assert.ok(t.toY(8.07) < t.toY(0), "y increases upward");
  const tall = PlotMath.fieldTransform(16.54, 8.07, 200, 800, 0);
  assert.ok(Math.abs(tall.scale - 200 / 16.54) < 1e-9, "still limited by the width");
});

test("timeline marks are at most one per pixel column, inside the window, in order", () => {
  const times = [1, 1.01, 1.02, 5, 5.5, 9, 20];
  assert.deepEqual(PlotMath.markColumns(times, 0, 10, 10), [1, 5, 9]);
  assert.deepEqual(PlotMath.markColumns(times, 0, 10, 1000), [1, 1.01, 1.02, 5, 5.5, 9]);
  assert.deepEqual(PlotMath.markColumns(times, 12, 30, 10), [20]);
  assert.deepEqual(PlotMath.markColumns([], 0, 10, 10), []);
});
