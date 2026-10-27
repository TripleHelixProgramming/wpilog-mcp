import { test } from "node:test";
import * as assert from "node:assert/strict";
import { SourceHealthMonitor, sourceHealth } from "../visualization/sourceHealth";
const source: any = { kind: "session", origin_store_id: "store", session_id: "session", anchor_member: { original_name: "capture.wpilog", recording_started_at: "start" } };
const health = () => ({ source_ref: source, acquisition_state: "recording", history_state: "live", reason: "Quiet is connected", connected: true, clock_synchronized: true, last_value_age_ms: 600000 });
const pause = (ms = 15) => new Promise(r => setTimeout(r, ms));
const until = async (condition: () => boolean, ms = 3000) => {
  for (const end = Date.now() + ms; !condition(); await pause(5)) if (Date.now() > end) throw new Error("Timed out waiting");
};
const refusal = (retryAfterMs?: number) =>
  Object.assign(new Error("FILE_CHANGED: Store changed while reading its catalog; retry the read"), { retryable: true, retryAfterMs });
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
test("a retryable refusal is asked again after the server's suggested wait and shows nothing meanwhile", async () => {
  // A heartbeat of a minute: only the suggested wait can bring the second check within the test.
  const reads: { resolve(value: unknown): void; reject(error: Error): void }[] = [], a: any[] = [];
  const monitor = new SourceHealthMonitor(() => new Promise((resolve, reject) => reads.push({ resolve, reject })), 60_000);
  try {
    monitor.watch(source, s => a.push(s));
    reads[0].reject(refusal(5)); await until(() => reads.length === 2);
    assert.equal(a.length, 0, "A passing refusal is not shown as unavailable");
    reads[1].resolve(health()); await until(() => a.length === 1); assert.equal(a[0].kind, "ok");
  } finally { monitor.dispose(); }
});
test("a retryable refusal between two answers leaves the views' status as it was", async () => {
  const reads: { resolve(value: unknown): void; reject(error: Error): void }[] = [], a: any[] = [];
  const monitor = new SourceHealthMonitor(() => new Promise((resolve, reject) => reads.push({ resolve, reject })), 50);
  try {
    monitor.watch(source, s => a.push(s));
    reads[0].resolve(health()); await until(() => reads.length === 2);
    reads[1].reject(refusal(5)); await until(() => reads.length === 3);
    reads[2].resolve(health()); await until(() => a.length === 2);
    assert.deepEqual(a.map(o => o.kind), ["ok", "ok"]);
  } finally { monitor.dispose(); }
});
test("a retryable refusal that lasts a heartbeat is shown as unavailable", async () => {
  let reads = 0; const a: any[] = [], started = Date.now();
  const monitor = new SourceHealthMonitor(() => { reads++; return Promise.reject(refusal(5)); }, 100);
  try {
    monitor.watch(source, s => a.push(s));
    await until(() => a.length === 1);
    assert.equal(a[0].kind, "unavailable"); assert.match(a[0].reason, /FILE_CHANGED/);
    assert.ok(Date.now() - started >= 100, "Not before the refusal has lasted a heartbeat"); assert.ok(reads > 2, "It was asked again meanwhile");
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
