import { test } from "node:test";
import * as assert from "node:assert/strict";
import { ViewRecovery } from "../visualization/viewRecovery";
const pause = (ms = 25) => new Promise(r => setTimeout(r, ms));
test("resync retries the complete failed reattachment without changing the recovered identity or configuration", async () => {
  let attempts = 0, suspended = 0; const failures: unknown[] = [], applied: unknown[] = [];
  const configuration = { view_id: "same", revision: 7, configuration: { title: "Recovered" } };
  const owner = new ViewRecovery({ suspend: () => suspended++, read: async () => {
    if (++attempts < 3) throw new Error("Window registration or attachment temporarily unavailable"); return configuration;
  }, apply: async value => { applied.push(value); }, failed: e => failures.push(e) }, 2);
  try {
    owner.start(); await pause();
    assert.equal(attempts, 3); assert.equal(suspended, 1); assert.equal(failures.length, 2); assert.deepEqual(applied, [configuration]);
    await pause(); assert.equal(attempts, 3, "Successful reattachment stops retrying");
  } finally { owner.dispose(); }
});
test("a newer resync aborts and ignores the old reply; closing cancels in-flight recovery and retries", async () => {
  const pending: { resolve(value: number): void; signal: AbortSignal }[] = [], applied: number[] = [], failed: unknown[] = [];
  const owner = new ViewRecovery({ suspend() {}, read: signal => new Promise<number>(resolve => pending.push({ resolve, signal })),
    apply: async value => { applied.push(value); }, failed: e => failed.push(e) }, 2);
  owner.start(); owner.start(); assert.equal(pending[0].signal.aborted, true);
  pending[0].resolve(1); pending[1].resolve(2); await pause(); assert.deepEqual(applied, [2]);
  owner.start(); owner.dispose(); assert.equal(pending[2].signal.aborted, true);
  pending[2].resolve(3); await pause(); assert.deepEqual(applied, [2]); assert.deepEqual(failed, []);
  owner.start(); assert.equal(pending.length, 3);
});
test("apply failure retains recovery until it succeeds, and one owner's failure does not suspend a peer", async () => {
  let applies = 0, peerReads = 0;
  const owner = new ViewRecovery({ suspend() {}, read: async () => "layout", apply: async () => { if (++applies === 1) throw new Error("CAS conflict retained"); }, failed() {} }, 2);
  const peer = new ViewRecovery({ suspend() {}, read: async () => ++peerReads, apply: async () => {}, failed() {} }, 2);
  try { owner.start(); peer.start(); await pause(); assert.equal(applies, 2); assert.equal(peerReads, 1); }
  finally { owner.dispose(); peer.dispose(); }
});
