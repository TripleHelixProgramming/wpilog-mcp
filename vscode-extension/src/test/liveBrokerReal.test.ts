import { test } from "node:test";
import * as assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createInterface } from "node:readline";
import { FollowClient } from "../visualization/followClient";
import { LiveBroker, FollowDelivery, FollowState } from "../visualization/liveBroker";
import { FollowSource } from "../visualization/followProtocol";

const classpath = process.env.WPILOG_FOLLOW_CLASSPATH;
const wait = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
async function until(fn: () => boolean, label: string): Promise<void> {
  for (let n = 0; n < 1500; n++) { if (fn()) return; await wait(10); } assert.fail(`Timed out: ${label}`);
}
test("shared broker follows the real capture/server through bootstrap, append, restart and finalization", { skip: !classpath, timeout: 60000 }, async t => {
  const temp = await mkdtemp(join(tmpdir(), "wpilog-broker-")), replies: any[] = [], stderr: string[] = [];
  const child = spawn(process.env.WPILOG_FOLLOW_JAVA ?? "java", ["-Xmx256m", `-Duser.home=${temp}`, "-cp", classpath!,
    "org.triplehelix.wpilogmcp.fixtures.FollowBrokerFixture", join(temp, "store")],
    { env: { ...process.env, WPILOG_DISK_CACHE_DIR: join(temp, "cache"), TBA_API_KEY: "" }, stdio: ["pipe", "pipe", "pipe"] });
  let exited = false; child.once("exit", () => exited = true); child.on("error", error => stderr.push(String(error)));
  const lines = createInterface({ input: child.stdout }); lines.on("line", line => { if (line.startsWith("BROKER ")) replies.push(JSON.parse(line.slice(7))); });
  child.stderr.on("data", b => { if (stderr.length < 100) stderr.push(String(b)); });
  let broker: LiveBroker | undefined;
  t.after(async () => {
    await broker?.dispose(); child.stdin.end();
    for (let n = 0; n < 200 && !exited; n++) await wait(10);
    if (!exited) { child.kill(); for (let n = 0; n < 200 && !exited; n++) await wait(10); }
    lines.close(); await rm(temp, { recursive: true, force: true });
  });
  await until(() => replies.length > 0 || exited, "fixture startup"); assert.ok(replies.length, stderr.join(""));
  const port = replies.shift().port;
  const command = async (request: object) => {
    child.stdin.write(JSON.stringify(request) + "\n"); await until(() => replies.length > 0 || exited, JSON.stringify(request));
    assert.ok(replies.length, stderr.join("")); return replies.shift();
  };
  const source: FollowSource = { kind: "session", origin_store_id: "11111111-1111-4111-8111-111111111111",
    session_id: "22222222-2222-4222-8222-222222222222", anchor_member: { original_name: "capture.wpilog", recording_started_at: "2026-01-01T00:00:00Z" } };
  await command({ op: "arrays" });
  const arrayClient = new FollowClient(`http://127.0.0.1:${port}/mcp`), arrayStates: FollowState[] = [];
  let arrays = 0, arrayPages = 0, maxNodes = 0;
  broker = new LiveBroker({ pageBytes: arrayClient.pageBytes, open: (...args) => arrayClient.open(...args),
    close: (...args) => arrayClient.close(...args), next: async (...args) => {
      const result = await arrayClient.next(...args);
      if (result !== undefined) {
        const encoded = JSON.stringify(result); let nodes = 0;
        JSON.parse(encoded, (_key, value) => { nodes++; return value; });
        maxNodes = Math.max(maxNodes, nodes); arrayPages++;
        assert.ok(Buffer.byteLength(encoded) <= arrayClient.pageBytes);
        t.diagnostic(`Real array page ${arrayPages}: ${nodes} JSON nodes, ${Buffer.byteLength(encoded)} bytes`);
      }
      return result;
    } });
  const arraySubscription = broker.subscribe(source, [], [{ entry: "NT:/arrays" }], {
    identity: { viewId: "arrays", generation: 1, revision: 1, queryKey: "arrays" }, current: () => true,
    state: state => arrayStates.push(state), page: delivery => {
      for (const batch of delivery.page.batches) {
        assert.equal(batch.entry, "NT:/arrays"); assert.deepEqual(batch.batch.problems, []);
        for (const sample of batch.batch.samples) {
          assert.equal(sample.ordinal, arrays); assert.equal(sample.timestamp_us, String(arrays));
          const expected = Array.from({ length: 19 }, (_, column) => {
            const bits = Buffer.alloc(8); bits.writeDoubleLE(arrays * 32 + column + 0.25);
            return { type: "f64", value: bits.readBigUInt64LE().toString(16) };
          });
          assert.deepEqual(sample.value, { type: "f64[]", value: expected }); arrays++;
        }
      }
    }
  });
  await until(() => arrays === 4096 || arrayStates.some(s => s.kind === "error"), "full array bootstrap");
  assert.deepEqual(arrayStates.filter(s => s.kind === "error"), [], "A byte-bounded server array page must not stop the broker");
  assert.equal(arrays, 4096); assert.ok(maxNodes > 262_144, "Real server pages cross the old node ceiling");
  arraySubscription.dispose(); await broker.dispose();
  t.diagnostic(`Real array bootstrap: ${arrays} records, ${arrays * 19} independently checked double bit patterns across ${arrayPages} pages`);
  const client = new FollowClient(`http://127.0.0.1:${port}/mcp`, undefined, 64 << 10);
  let opens = 0, admitted = 0, closes = 0, polls = 0, lost: string | undefined, replayed = false;
  broker = new LiveBroker({ pageBytes: client.pageBytes,
    open: async (...args) => { opens++; const lease = await client.open(...args); admitted++; return lease; }, next: async (...args) => {
      polls++; const result = await client.next(...args);
      if (!lost && result !== undefined) { lost = JSON.stringify(result); throw new Error("Synthetic lost complete HTTP response"); }
      if (!replayed && result !== undefined) { assert.equal(JSON.stringify(result), lost, "Exact retained replay before delivery"); replayed = true; }
      return result;
    },
    close: (...args) => { closes++; return client.close(...args); } }, { retryBaseMs: 10, retryMaxMs: 50 });
  const a: FollowDelivery[] = [], b: FollowDelivery[] = [], states: FollowState[] = [];
  const received = (pages: FollowDelivery[]) => pages.flatMap(p => p.page.batches.filter(b => b.entry === "NT:/x").flatMap(b => b.batch.samples));
  const exact = (pages: FollowDelivery[], count: number) => {
    const samples = received(pages); assert.equal(samples.length, count);
    for (const [n, sample] of samples.entries()) {
      assert.equal(sample.ordinal, n); assert.equal(sample.timestamp_us, n % 2 ? "50" : "100");
      assert.deepEqual(sample.value, { type: "i64", value: String(9007199254740993n + BigInt(n)) });
    }
  };
  let release!: () => void, first = true;
  const hold = new Promise<void>(r => release = r);
  const sa = broker.subscribe(source, [], [{ entry: "NT:/x" }], {
    identity: { viewId: "a", generation: 1, revision: 1, queryKey: "x" }, current: () => true, state: s => states.push(s),
    page: async p => { a.push(p); if (first) { first = false; await hold; } }
  });
  const sb = broker.subscribe(source, [], [{ entry: "NT:/x" }, { entry: "NT:/y" }, { entry: "NT:/late" }, { entry: "NT:/raw" }], {
    identity: { viewId: "b", generation: 2, revision: 4, queryKey: "all" }, current: () => true, state: s => states.push(s), page: p => { b.push(p); }
  });
  await until(() => received(b).length === 1, "initial page"); assert.equal(opens, 1); assert.equal(polls, 2); assert.ok(replayed);
  assert.deepEqual(b[0].page.batches.find(b => b.entry === "NT:/y")!.batch.samples[0].value, { type: "f64", value: "8000000000000000" });
  await command({ op: "append", first: 1, count: 1000 }); assert.equal(polls, 2, "Append during unacknowledged bootstrap"); release();
  await until(() => received(b).length === 1001, "ordinal catch-up");
  assert.deepEqual(received(a), received(b)); assert.ok(b.length > 2, "64 KiB pages must require continuation");
  exact(b, 1001);
  sa.dispose(); assert.equal(closes, 0);
  await command({ op: "metadata" }); await until(() => b.some(p => JSON.stringify(p.frontier).includes("NT:/late")), "metadata-only publication");
  await command({ op: "gap" }); await until(() => b.some(p => p.page.batches.some(b => b.batch.problems.length)), "oversized raw gap");
  const gap = b.flatMap(p => p.page.batches).find(b => b.entry === "NT:/raw")!;
  assert.equal(gap.batch.samples.length, 0); assert.equal(gap.batch.problems[0].code, "RESOURCE_LIMIT"); assert.equal(gap.batch.problems[0].ordinal, 0);
  await command({ op: "restart" });
  await until(() => opens >= 2 && b.some(p => p.reset === 2 && p.page.frontier_complete), "explicit same-recording reset");
  assert.ok(states.some(s => ["CURSOR_EXPIRED", "CLOSED"].includes(s.code ?? "") && s.kind === "reset"));
  const after = b.filter(p => p.reset === 2); assert.equal(received(after).length, 1001);
  await command({ op: "append", first: 1001, count: 1 });
  await until(() => received(b.filter(p => p.reset === 2)).length === 1002, "post-restart delta");
  exact(b.filter(p => p.reset === 2), 1002);
  const final = await command({ op: "finalize" });
  await until(() => states.some(s => s.kind === "ended") && closes >= 2, "final drain and cleanup");
  assert.ok(JSON.stringify(b.at(-1)!.frontier).includes(final.hash)); assert.equal(b.at(-1)!.page.ended, true);
  const endPolls = polls; await wait(100); assert.equal(polls, endPolls, "No terminal polling spin");
  await until(() => broker!.resources.reservedBytes === 0, "finished source releases its reservation");
  assert.equal(closes, admitted, "Every admitted lease was retired");
  sb.dispose(); await broker.dispose(); assert.deepEqual(broker.resources, { jobs: 0, consumers: 0, reservedBytes: 0 });
  t.diagnostic(`Real transport: ${opens} create attempts, ${admitted} leases, ${polls} polls, ${closes} DELETEs; 1001 bootstrap/delta integers checked exactly before reset, 1002 after reset; metadata, raw gap, signed zero and final hash checked.`);
});
