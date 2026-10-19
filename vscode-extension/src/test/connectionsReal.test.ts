import { test, TestContext } from "node:test";
import * as assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createInterface } from "node:readline";
import { randomUUID } from "node:crypto";
import { ConnectionRegistry, connectionDefinitions } from "../connections";
import { RecordedClient } from "../visualization/recordedClient";
import { InventoryClient } from "../visualization/inventoryClient";
import { ByteBudget } from "../visualization/byteBudget";
import { FollowClient } from "../visualization/followClient";
import { FollowSource } from "../visualization/followProtocol";
import { DataClient } from "../dataClient";

const classpath = process.env.WPILOG_FOLLOW_CLASSPATH;
const wait = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
async function fixture(t: TestContext) {
  const root = await mkdtemp(join(tmpdir(), "wpilog-direct-")), replies: any[] = [], errors: string[] = [];
  const processChild = spawn(process.env.WPILOG_FOLLOW_JAVA ?? "java", ["-Xmx256m", `-Duser.home=${root}`, "-cp", classpath!,
    "org.triplehelix.wpilogmcp.fixtures.FollowBrokerFixture", join(root, "store"), "direct"],
    { env: { ...process.env, WPILOG_DISK_CACHE_DIR: join(root, "cache"), TBA_API_KEY: "" }, stdio: ["pipe", "pipe", "pipe"] });
  let exited = false; processChild.once("exit", () => exited = true); processChild.on("error", error => errors.push(String(error)));
  processChild.stderr.on("data", data => { if (errors.length < 100) errors.push(String(data)); });
  const lines = createInterface({ input: processChild.stdout }); lines.on("line", line => { if (line.startsWith("BROKER ")) replies.push(JSON.parse(line.slice(7))); });
  t.after(async () => { processChild.stdin.end(); for (let n = 0; n < 200 && !exited; n++) await wait(10); if (!exited) processChild.kill(); lines.close(); await rm(root, { recursive: true, force: true }); });
  async function next() { for (let n = 0; n < 1500 && !replies.length && !exited; n++) await wait(10); assert.ok(replies.length, errors.join("")); return replies.shift(); }
  const ready = await next();
  return { ...ready, url: `http://127.0.0.1:${ready.port}/mcp`, command: async (value: object) => { processChild.stdin.write(JSON.stringify(value) + "\n"); return next(); } };
}

test("direct remote contexts browse, page, follow, analyze and save on two real server owners without a laptop daemon", { skip: !classpath, timeout: 90000 }, async t => {
  const a = await fixture(t), b = await fixture(t), pins = new Map<string, string>(); let local = 0;
  const registry = new ConnectionRegistry({ version: "m3-test", localEndpoint: async () => { local++; throw new Error("No laptop daemon is allowed"); },
    headersFor: async () => ({ Forwarded: "for=192.0.2.1" }), pinned: id => pins.get(id), pin: async (id, owner) => { pins.set(id, owner); } });
  t.after(() => registry.dispose());
  registry.configure(connectionDefinitions([{ id: "a", label: "A", kind: "remote", url: a.url }, { id: "b", label: "B", kind: "remote", url: b.url },
    { id: "offline", label: "Unavailable", kind: "remote", url: "http://127.0.0.1:1/mcp" }]));
  const scopesA = await registry.context("a").scopes(), scopesB = await registry.context("b").scopes();
  assert.equal(scopesA.length, 1); assert.equal(scopesB.length, 1);
  const ca = registry.context("a", scopesA[0].scope_id), cb = registry.context("b", scopesB[0].scope_id);
  const listing: any = await (await ca.client()).callTool("list_available_logs", { view: "all" });
  assert.equal(listing.logs.length, 1); assert.equal(listing.logs[0].path, a.file);
  const ba = new RecordedClient(() => ca.client(), ca), bb = new RecordedClient(() => cb.client(), cb);
  const membersA = await a.command({ op: "topics", count: 300 }); await b.command({ op: "topics", count: 300 });
  const sa = await ba.identify(a.file), sb = await bb.identify(b.file);
  assert.equal(sa.source_ref.schema_version, 2); assert.equal(sb.source_ref.schema_version, 2);
  assert.notEqual(sa.source_ref.server_instance_id, sb.source_ref.server_instance_id);
  assert.deepEqual(sa.source_ref.content, sb.source_ref.content, "identical recording ids remain distinct serving owners");
  const budget = new ByteBudget(8 << 20), inventory = new InventoryClient(() => ca.client(), budget, 2), signal = new AbortController().signal;
  const handle = await inventory.open(sa.source_ref, signal), filter = { query: "", types: [], unavailable: false };
  const names: string[] = []; let cursor: string | undefined;
  do { const page = await inventory.list(handle, filter, cursor, signal); names.push(...page.entries.map(entry => entry.name!)); cursor = page.next_cursor ?? undefined; } while (cursor);
  const expected = ["NT:/x", "NT:/y", ...Array.from({ length: 298 }, (_, n) => `NT:/inputs/synthetic/subsystem/signal-${String(n).padStart(5, "0")}`)].sort();
  assert.deepEqual(names, expected); assert.equal(new Set(names).size, 300);
  await inventory.close(handle); assert.equal(budget.reserved, 0);
  const configuration = structuredClone(a.configuration);
  configuration.requirements = [{ id: "x", selector_kind: "entry", entry: "NT:/y", accepted_entry_types: ["double"], value_type: "number", field_path: null, unit: null }];
  const view = await ba.create(sa.source_ref, configuration); await ba.attach(view.view_id);
  const binding: any = await ba.call(`/views/${view.view_id}/bindings`, { expected_revision: view.revision });
  assert.equal(binding.follow_ready, true, JSON.stringify(binding.compatibility));
  const owner = await ca.client(), follow = new FollowClient(owner.endpoint, url => owner.followHeaders(url), 1 << 20, "queries");
  await assert.rejects(follow.openView(sa.source_ref as unknown as FollowSource, { view_id: view.view_id, expected_revision: view.revision }, signal), /403|local|denied/i);
  const bindingRoute = new URL("/read/v1/follows", owner.endpoint);
  const sessionHeaders = await owner.followHeaders(bindingRoute.href); delete sessionHeaders.Forwarded;
  for (const version of [1, 3]) {
    const refused = await fetch(bindingRoute, { method: "POST", headers: { ...sessionHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ view_binding: { view_id: view.view_id, expected_revision: view.revision, api_version: version } }) });
    assert.equal(refused.status, 400, await refused.text());
  }
  const lease = await follow.openView(sa.source_ref as unknown as FollowSource, { view_id: view.view_id, expected_revision: view.revision, api_version: 2 }, signal);
  try {
    const page: any = await follow.next(lease, lease.cursor, signal);
    assert.equal(page.follow_id, lease.id); assert.ok(page.batches.some((batch: any) => batch.entry === "NT:/y"));
  } finally { await follow.close(lease); }
  for (const backend of [ba, bb]) {
    const source = backend === ba ? sa.source_ref : sb.source_ref;
    const result: any = await backend.call("/source/statistics", { source_ref: source, entry: "NT:/y" });
    assert.equal(result.count, 1); assert.equal(result.mean, 0);
    const histogram: any = await backend.call("/source/histogram", { source_ref: source, entry: "NT:/y", start_time: 0, end_time: 1 });
    assert.equal(histogram.summary.count, 1); assert.equal(histogram.summary.mean, 0);
  }
  await assert.rejects(bb.describe(sa.source_ref), /another server or scope/);
  ba.destinations.set(view.view_id, { title: "Remote saved layout" }); const saved = await ba.save(view.view_id, view.revision);
  assert.ok(saved.saved_view_ref); const library: any = await bb.call("/library/list", { kind: "view" }); assert.equal(library.total_matching, 0);
  const firstEpoch = ca.facts!.server_epoch; await a.command({ op: "restart" });
  const recovered = await ba.read(view.view_id); assert.equal(recovered.revision, saved.revision); assert.notEqual(ca.facts!.server_epoch, firstEpoch);
  const finalized = await a.command({ op: "finalize" }); assert.match(finalized.hash, /^[0-9a-f]{64}$/);
  const path = a.file.replace(/capture\.wpilog$/, "rollover.wpilog");
  // Finalized member plotting uses the qualified single-file reference and direct Arrow/CSV route.
  const file = await ba.identify(path);
  assert.equal((file.source_ref.content as any).content_sha256, membersA.rollover_hash);
  const data = new DataClient(0, 0, url => owner.dataHeaders(url));
  const bytes = await data.fetch(owner.endpoint, { path, sourceRef: file.source_ref, scopeId: ca.scopeId, names: ["NT:/x"], format: "csv" });
  assert.match(Buffer.from(bytes.bytes).toString(), /-7/); assert.equal(local, 0);
});
