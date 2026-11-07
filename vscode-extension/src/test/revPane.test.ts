// Tests for the REV pane's grouping (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";

// eslint-disable-next-line @typescript-eslint/no-var-requires
const Rev = require(path.join(__dirname, "..", "..", "media", "rev.js")) as {
  groupSignals(result: unknown): {
    note: string | null; hint: string | null; overall: string | null; accuracyMs: string | null; warnings?: string[];
    buses: { bus: string; sync: { method: string; confidence: string; offsetSeconds?: number; aligned: boolean }; devices: { device: string; signals: { key: string; signal: string; unit: string; sampleCount: number }[] }[] }[];
  };
  syncLabel(sync: { method?: string; confidence?: string; offsetSeconds?: number; aligned?: boolean } | null): string;
};

const listing = {
  status: "ok",
  signal_count: 4,
  revlog_count: 2,
  overall_sync_confidence: "medium",
  signals: [
    { key: "REV/rio/SparkMax_3/Velocity", device: "SparkMax_3", signal: "Velocity", unit: "rpm", sample_count: 7500, can_bus: "rio", sync_method: "CROSS_CORRELATION", timestamps_aligned: true, offset_seconds: 15.3, sync_confidence: "high" },
    { key: "REV/rio/SparkMax_3/AppliedOutput", device: "SparkMax_3", signal: "AppliedOutput", unit: "duty_cycle", sample_count: 7500, can_bus: "rio", sync_method: "CROSS_CORRELATION", timestamps_aligned: true, offset_seconds: 15.3, sync_confidence: "high" },
    { key: "REV/rio/SparkMax_1/OutputCurrent", device: "SparkMax_1", signal: "OutputCurrent", unit: "A", sample_count: 5000, can_bus: "rio", sync_method: "CROSS_CORRELATION", timestamps_aligned: true, offset_seconds: 15.3, sync_confidence: "high" },
    { key: "REV/canivore/SparkMax_7/Velocity", device: "SparkMax_7", signal: "Velocity", unit: "rpm", sample_count: 100, can_bus: "canivore", sync_method: "FAILED", timestamps_aligned: false, sync_confidence: "failed" },
  ],
  warnings: ["Bus canivore could not be synchronized"],
  _metadata: { timing_accuracy_ms: "unknown" },
};

test("signals are grouped by bus and device in name order, each bus with its synchronization", () => {
  const g = Rev.groupSignals(listing);
  assert.equal(g.note, null);
  assert.deepEqual(g.buses.map((b) => b.bus), ["canivore", "rio"]);
  const rio = g.buses[1];
  assert.deepEqual(rio.devices.map((d) => d.device), ["SparkMax_1", "SparkMax_3"]);
  assert.deepEqual(rio.devices[1].signals.map((s) => s.signal), ["AppliedOutput", "Velocity"]);
  assert.deepEqual(rio.devices[1].signals[0], { key: "REV/rio/SparkMax_3/AppliedOutput", signal: "AppliedOutput", unit: "duty_cycle", sampleCount: 7500 });
  assert.deepEqual(rio.sync, { method: "CROSS_CORRELATION", confidence: "high", offsetSeconds: 15.3, aligned: true });
  assert.ok(!g.buses[0].sync.aligned, "the failed bus is not aligned");
  assert.equal(g.overall, "medium");
  assert.equal(g.accuracyMs, "unknown");
  assert.deepEqual(g.warnings, ["Bus canivore could not be synchronized"]);
});

test("a result that is not ok is one note with the server's reason and hint", () => {
  const none = Rev.groupSignals({ status: "not_applicable", reason: "No REV log (.revlog) files were found for this wpilog.", hint: "Revlogs are discovered by recording time" });
  assert.equal(none.note, "No REV log (.revlog) files were found for this wpilog.");
  assert.equal(none.hint, "Revlogs are discovered by recording time");
  assert.deepEqual(none.buses, []);
  assert.equal(Rev.groupSignals({ status: "error", error: "boom" }).note, "boom");
  assert.equal(Rev.groupSignals(undefined).note, "No REV signals");
});

test("a bus's synchronization reads as its method, offset, and confidence", () => {
  assert.equal(Rev.syncLabel({ method: "CROSS_CORRELATION", confidence: "high", offsetSeconds: 15.3, aligned: true }), "cross-correlation, offset +15.300 s, high confidence");
  assert.equal(Rev.syncLabel({ method: "SYSTEM_TIME_ONLY", confidence: "low", offsetSeconds: -0.25, aligned: true }), "wall clock only, offset -0.250 s, low confidence");
  assert.equal(Rev.syncLabel({ method: "FAILED", confidence: "failed", aligned: false }), "not synchronized, failed confidence");
  assert.equal(Rev.syncLabel(null), "");
});
