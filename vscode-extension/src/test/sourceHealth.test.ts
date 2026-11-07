import { test } from "node:test";
import * as assert from "node:assert/strict";
import { SourceHealthMonitor, sourceHealth } from "../visualization/sourceHealth";
const source: any = { kind: "session", origin_store_id: "store", session_id: "session", anchor_member: { original_name: "capture.wpilog", recording_started_at: "start" } };
const health = () => ({ source_ref: source, acquisition_state: "recording", history_state: "live", reason: "Quiet is connected", connected: true, clock_synchronized: true, last_value_age_ms: 600000 });
const pause = (ms = 15) => new Promise(r => setTimeout(r, ms));
test("health watches share a bounded request, recover after failure and discard late closed replies", async () => {
  const reads: { resolve(value: unknown): void; reject(error: Error): void; signal: AbortSignal }[] = [], a: any[] = [], b: any[] = [];
  const monitor = new SourceHealthMonitor((_, signal) => new Promise((resolve, reject) => reads.push({ resolve, reject, signal })), 5);
  try {
    const first = monitor.watch(source, s => a.push(s)), second = monitor.watch(structuredClone(source), s => b.push(s));
    assert.equal(reads.length, 1); reads[0].resolve(health()); await pause();
    assert.equal(a[0].health.connected, true); assert.equal(b[0].health.last_value_age_ms, 600000);
    assert.equal(reads.length, 2); reads[1].reject(new Error("Server restarting")); await pause();
    assert.equal(a[1].kind, "unavailable"); assert.equal(reads.length, 3);
    first.dispose(); assert.equal(reads[2].signal.aborted, false, "Closing one view cannot interrupt its peer");
    second.dispose(); assert.equal(reads[2].signal.aborted, true);
    reads[2].resolve(health()); await pause(); assert.equal(a.length, 2); assert.equal(b.length, 2); assert.equal(reads.length, 3);
    const next = monitor.watch(source, s => a.push(s)); assert.equal(reads.length, 4);
    monitor.dispose(); assert.equal(reads[3].signal.aborted, true); next.dispose();
    assert.throws(() => monitor.watch(source, () => {}), /closed/);
  } finally { monitor.dispose(); }
});
test("health rejects foreign sources, unknown states and invalid ages instead of calling them connected", () => {
  assert.equal(sourceHealth(health(), source).connected, true);
  for (const invalid of [{ source_ref: { ...source, session_id: "other" } }, { acquisition_state: "guess" }, { history_state: "unknown" },
    { connected: "true" }, { clock_synchronized: undefined }, { last_value_age_ms: -1 }, { last_value_age_ms: Infinity }])
    assert.throws(() => sourceHealth({ ...health(), ...invalid }, source), /Invalid/);
});
test("health source capacity is independent of peer subscriptions and releases on close", () => {
  const monitor = new SourceHealthMonitor(() => new Promise(() => {})), owners: { dispose(): void }[] = [];
  try {
    for (let i = 0; i < 32; i++) owners.push(monitor.watch({ ...source, session_id: String(i) }, () => {}));
    const peer = monitor.watch({ ...source, session_id: "0" }, () => {});
    assert.throws(() => monitor.watch(source, () => {}), /32/);
    owners[0].dispose(); assert.throws(() => monitor.watch(source, () => {}), /32/);
    peer.dispose(); owners.push(monitor.watch(source, () => {}));
  } finally { owners.forEach(o => o.dispose()); monitor.dispose(); }
});
