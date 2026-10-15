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
async function realFixture(t: import("node:test").TestContext, cleanup: () => Promise<void> | void) {
  const temp = await mkdtemp(join(tmpdir(), "wpilog-broker-")), replies: any[] = [], stderr: string[] = [];
  const child = spawn(process.env.WPILOG_FOLLOW_JAVA ?? "java", ["-Xmx256m", `-Duser.home=${temp}`, "-cp", classpath!,
    "org.triplehelix.wpilogmcp.fixtures.FollowBrokerFixture", join(temp, "store")],
    { env: { ...process.env, WPILOG_DISK_CACHE_DIR: join(temp, "cache"), TBA_API_KEY: "" }, stdio: ["pipe", "pipe", "pipe"] });
  let exited = false; child.once("exit", () => exited = true); child.on("error", error => stderr.push(String(error)));
  const lines = createInterface({ input: child.stdout }); lines.on("line", line => { if (line.startsWith("BROKER ")) replies.push(JSON.parse(line.slice(7))); });
  child.stderr.on("data", b => { if (stderr.length < 100) stderr.push(String(b)); });
  t.after(async () => {
    await cleanup(); child.stdin.end();
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
  return { port, command, source };
}
test("queried one-MiB broker follows a large two-member recording through growth and finalization", { skip: !classpath, timeout: 60000 }, async t => {
  let broker: LiveBroker | undefined;
  const { port, command, source } = await realFixture(t, () => broker?.dispose());
  const info = await command({ op: "topics", count: 10000 });
  const client = new FollowClient(`http://127.0.0.1:${port}/mcp`, undefined, 1 << 20, "queries");
  const pages: FollowDelivery[] = [], peer: FollowDelivery[] = [], states: FollowState[] = [], peerStates: FollowState[] = [];
  broker = new LiveBroker(client, { retryBaseMs: 10, retryMaxMs: 50 });
  const subscribe = (id: string, output: FollowDelivery[], status: FollowState[]) => broker!.subscribe(source, [], [{ entry: "NT:/x" }, { entry: "NT:/late" }], {
    identity: { viewId: id, generation: 1, revision: 1, queryKey: "x-late" }, current: () => true, state: s => status.push(s), page: p => {
      assert.equal(p.frontier.frontier_scope, "queries"); assert.ok(Buffer.byteLength(JSON.stringify(p.page)) <= 1 << 20);
      assert.equal(p.frontier.members.length, 2);
      for (const m of p.frontier.members) {
        const entries = m.snapshot.entries as Record<string, unknown>;
        assert.ok(Object.keys(entries).every(name => ["NT:/x", "NT:/late"].includes(name)));
        assert.deepEqual(m.snapshot.schemas, []);
      }
      output.push(p);
    }
  });
  const values = (p: FollowDelivery[]) => p.flatMap(p => p.page.batches.filter(b => b.member_id === "capture.wpilog" && b.entry === "NT:/x").flatMap(b => b.batch.samples));
  const sa = subscribe("a", pages, states);
  await until(() => values(pages).length === 1 || states.some(s => s.kind === "error"), "large recording bootstrap");
  assert.deepEqual(states.filter(s => s.kind === "error"), []);
  assert.deepEqual(pages.flatMap(p => p.page.batches.filter(b => b.member_id === "rollover.wpilog").flatMap(b => b.batch.samples)).map(s => [s.ordinal, s.timestamp_us, s.value]), [[0, "-10", { type: "i64", value: "-7" }]]);
  const sb = subscribe("b", peer, peerStates); await until(() => values(peer).length === 1, "independent selected peer");
  assert.equal(broker.resources.reservedBytes, 4 << 20);
  await command({ op: "append", first: 1, count: 100 });
  await until(() => values(pages).length === 101 && values(peer).length === 101, "selected deltas");
  await command({ op: "metadata" });
  await until(() => pages.some(p => JSON.stringify(p.frontier).includes('"NT:/late"')) && peer.some(p => JSON.stringify(p.frontier).includes('"NT:/late"')), "selected late declaration");
  const final = await command({ op: "finalize" });
  await until(() => states.some(s => s.kind === "ended") && peerStates.some(s => s.kind === "ended"), "selected terminal pages");
  for (const seen of [pages, peer]) {
    assert.ok(seen.every(p => p.reset === 1));
    assert.deepEqual(seen.flatMap(p => p.page.batches.filter(b => b.member_id === "rollover.wpilog").flatMap(b => b.batch.samples))
      .map(s => [s.ordinal, s.timestamp_us, s.value]), [[0, "-10", { type: "i64", value: "-7" }]]);
    for (const [n, sample] of values(seen).entries()) {
      assert.equal(sample.ordinal, n); assert.equal(sample.timestamp_us, n % 2 ? "50" : "100");
      assert.deepEqual(sample.value, { type: "i64", value: String(9007199254740993n + BigInt(n)) });
    }
    const members = seen.at(-1)!.frontier.members as readonly any[];
    assert.equal(members.find(m => m.member_id === "capture.wpilog").content_sha256, final.hash);
    assert.equal(members.find(m => m.member_id === "rollover.wpilog").content_sha256, info.rollover_hash);
  }
  assert.deepEqual([...states, ...peerStates].filter(s => s.kind === "error"), []);
  sa.dispose(); sb.dispose(); await broker.dispose(); assert.equal(broker.resources.reservedBytes, 0);
  t.diagnostic(`Queried follow: 10000 topics/member, 2 members, 1 MiB pages, ${pages.length + peer.length} pages, 204 exact samples across uninterrupted independent peers`);
});
test("shared broker follows the real capture/server through bootstrap, append, restart and finalization", { skip: !classpath, timeout: 60000 }, async t => {
  let broker: LiveBroker | undefined;
  const { port, command, source } = await realFixture(t, () => broker?.dispose());
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

test("1 MiB opened jobs keep peers uninterrupted across join, refresh, reveal and server replacement", { skip: !classpath, timeout: 60000 }, async t => {
  let broker: LiveBroker | undefined;
  const { port, command, source } = await realFixture(t, () => broker?.dispose());
  const client = new FollowClient(`http://127.0.0.1:${port}/mcp`, undefined, 1 << 20);
  const opened: string[] = [], closed: string[] = [], acknowledged: string[] = [];
  const lostCleanup: { id: string; reason: string }[] = [];
  broker = new LiveBroker({ pageBytes: client.pageBytes,
    open: async (...args) => { const lease = await client.open(...args); opened.push(lease.id); return lease; },
    next: (...args) => client.next(...args), close: async lease => {
      closed.push(lease.id);
      try { await client.close(lease); acknowledged.push(lease.id); }
      catch (error) { lostCleanup.push({ id: lease.id, reason: String(error) }); throw error; }
    }
  }, { retryBaseMs: 10, retryMaxMs: 50 });
  function mount(id: string, entries = ["NT:/x"], wider = false) {
    const pages: FollowDelivery[] = [], states: FollowState[] = [];
    const subscription = broker!.subscribe(source, wider ? [{ start_us: "0", end_us: "200", end_inclusive: true }] : [], entries.map(entry => ({ entry })), {
      identity: { viewId: id, generation: 1, revision: 1, queryKey: id }, current: () => true,
      state: state => states.push(state), page: page => { pages.push(page); }
    });
    return { pages, states, subscription };
  }
  type View = ReturnType<typeof mount>;
  const resets = (view: View) => view.states.filter(s => s.kind === "reset").map(s => s.reset);
  const samples = (view: View, reset: number) => view.pages.filter(p => p.reset === reset).flatMap(p => p.page.batches.filter(b => b.entry === "NT:/x").flatMap(b => b.batch.samples));
  const exact = (view: View, reset: number) => {
    const values = samples(view, reset); assert.equal(values.length, 41);
    for (const [n, value] of values.entries()) {
      assert.equal(value.ordinal, n); assert.equal(value.timestamp_us, n % 2 ? "50" : "100");
      assert.deepEqual(value.value, { type: "i64", value: String(9007199254740993n + BigInt(n)) });
    }
  };
  const a = mount("a"), b = mount("b", ["NT:/x", "NT:/late"]);
  await until(() => b.pages.length > 0, "shared initial page"); assert.equal(opened.length, 1);
  const peerLease = b.pages[0].page.follow_id;
  const c = mount("c", ["NT:/x", "NT:/y"]); await until(() => c.pages.length > 0, "late new-selection bootstrap");
  assert.deepEqual(resets(b), [1]); assert.deepEqual(resets(a), [1]); assert.equal(closed.length, 0);
  a.subscription.refresh(); await until(() => a.pages.some(p => p.reset === 2), "isolated refresh");
  assert.deepEqual(resets(b), [1]); assert.equal(closed.length, 0);
  c.subscription.dispose(); await until(() => closed.includes(c.pages[0].page.follow_id), "hidden subscription retired");
  const d = mount("revealed", ["NT:/x", "NT:/y"]), e = mount("wider", ["NT:/x"], true);
  await until(() => d.pages.length > 0 && e.pages.length > 0, "reveal and wider window bootstrap");
  assert.deepEqual(resets(b), [1]); assert.deepEqual(resets(a), [1, 2]); assert.equal(opened.length, 5);
  await command({ op: "append", first: 1, count: 40 });
  await until(() => samples(b, 1).length === 41 && samples(a, 2).length === 41 && samples(d, 1).length === 41 && samples(e, 1).length === 41, "uninterrupted ordinal deltas");
  exact(b, 1); exact(a, 2); exact(d, 1); exact(e, 1);
  assert.ok(b.pages.every(p => p.page.follow_id === peerLease));
  await command({ op: "metadata" }); await until(() => b.pages.some(p => JSON.stringify(p.frontier).includes("NT:/late")), "metadata reaches uninterrupted peer");
  const replacing = new Set([a, b, d, e].map(v => v.pages.at(-1)!.page.follow_id));
  await command({ op: "restart" }); const f = mount("join during replacement");
  await until(() => samples(b, 2).length === 41 && samples(a, 3).length === 41 && samples(d, 2).length === 41 && samples(e, 2).length === 41 && samples(f, 1).length === 41, "replacement with a new peer");
  for (const [view, reset] of [[b, 2], [a, 3], [d, 2], [e, 2], [f, 1]] as const) exact(view, reset);
  assert.deepEqual(resets(b), [1, 2]); assert.deepEqual(resets(a), [1, 2, 3]);
  const final = await command({ op: "finalize" }), g = mount("join finalized");
  const views = [a, b, d, e, f, g];
  await until(() => views.every(v => v.states.some(s => s.kind === "ended")) && broker!.resources.reservedBytes === 0, "all independent leases finalize");
  exact(g, 1);
  for (const view of views) {
    assert.equal(view.pages.at(-1)!.page.ended, true); assert.ok(JSON.stringify(view.pages.at(-1)!.frontier).includes(final.hash));
    assert.deepEqual(view.states.filter(s => s.kind === "error"), []);
  }
  assert.equal(new Set(closed).size, opened.length); assert.equal(closed.length, opened.length);
  assert.equal(acknowledged.length + lostCleanup.length, opened.length);
  assert.ok(lostCleanup.every(item => replacing.has(item.id)), "Only shutdown of the old server can lose a cleanup reply");
  await broker.dispose(); assert.deepEqual(broker.resources, { jobs: 0, consumers: 0, reservedBytes: 0 });
  t.diagnostic(`Uninterrupted peers at 1 MiB: ${opened.length} leases, each DELETE attempted once; ${acknowledged.length} acknowledgements, ${lostCleanup.length} replies lost during server replacement; 410 exact ordinal/int64/timestamp tuples across 10 generations; joins, refresh, reveal, wider window, metadata, replacement and final hashes checked.`);
});
