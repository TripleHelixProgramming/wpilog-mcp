import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { randomUUID } from "node:crypto";
import { ConnectionRegistry, connectionDefinitions } from "../connections";
import { RecordedClient } from "../visualization/recordedClient";
import { DataClient } from "../dataClient";
import { sourceUri, readSourceUri } from "../sourceUri";
import { claudeHttpArgs, sameClaudeRegistration } from "../claudeRegistration";

async function server(t: { after(fn: () => unknown): void }) {
  const state = { owner: randomUUID(), epoch: randomUUID(), calls: [] as { route: string; body: any; headers: http.IncomingHttpHeaders }[], sessions: new Set<string>(),
    scopes: [{ scope_id: randomUUID(), path: "/synthetic", kind: "directory", available: true }], hold: undefined as (() => void) | undefined };
  const app = http.createServer(async (request, response) => {
    let encoded = ""; for await (const chunk of request) encoded += chunk;
    const body = encoded ? JSON.parse(encoded) : undefined, route = request.url!;
    state.calls.push({ route, body, headers: request.headers });
    const send = (value: unknown, status = 200) => { response.writeHead(status, { "Content-Type": "application/json" }); response.end(JSON.stringify(value)); };
    if (request.method === "DELETE") { state.sessions.delete(String(request.headers["mcp-session-id"])); send({}); return; }
    if (body?.method === "initialize") { const id = randomUUID(); state.sessions.add(id); response.setHeader("Mcp-Session-Id", id); send({ result: {} }); return; }
    if (!state.sessions.has(String(request.headers["mcp-session-id"]))) { send({ code: "SESSION_EXPIRED" }, 404); return; }
    if (body?.method === "notifications/initialized") { send({}, 202); return; }
    if (route === "/visualizations/v2/capabilities") { send({ server_instance_id: state.owner, server_epoch: state.epoch, remote_visualization: true }); return; }
    if (route === "/visualizations/v2/source/scopes") {
      const rows = state.scopes.slice(body.offset, body.offset + body.limit), next = body.offset + rows.length;
      send({ server_instance_id: state.owner, source_scopes: rows, offset: body.offset, total: state.scopes.length, has_more: next < state.scopes.length, next_offset: next }); return;
    }
    if (route.startsWith("/data/entries")) { send({ owner: state.owner, scope: new URL(route, "http://fixture").searchParams.get("scope_id") }); return; }
    const result = { status: "ok", owner: state.owner, args: body?.params?.arguments };
    if (body?.params?.name === "held") { state.hold = () => send({ result: { content: [{ type: "text", text: JSON.stringify(result) }] } }); return; }
    send(body?.method === "tools/call" ? { result: { content: [{ type: "text", text: JSON.stringify(result) }] } } : result);
  });
  await new Promise<void>(resolve => app.listen(0, "127.0.0.1", resolve));
  t.after(() => { app.closeAllConnections(); app.close(); });
  return { state, url: `http://127.0.0.1:${(app.address() as { port: number }).port}/mcp` };
}
function registry(t: { after(fn: () => unknown): void }, definitions: unknown) {
  let localCalls = 0; const pins = new Map<string, string>();
  const registry = new ConnectionRegistry({ version: "test", localEndpoint: async () => { localCalls++; throw new Error("Laptop daemon must remain stopped"); },
    pinned: id => pins.get(id), pin: async (id, owner) => { pins.set(id, owner); } });
  registry.configure(connectionDefinitions(definitions)); t.after(() => registry.dispose());
  return { registry, pins, localCalls: () => localCalls };
}
test("configured roots are lazy, remote-only reads and Arrow transport make no laptop calls", async t => {
  const remote = await server(t), scope = remote.state.scopes[0].scope_id;
  const r = registry(t, [{ id: "pit", label: "Pit", kind: "remote", url: remote.url }, { id: "offline", label: "Unavailable", kind: "remote", url: "http://127.0.0.1:1/mcp" }]);
  assert.equal(r.registry.definitions.length, 2); assert.equal(remote.state.calls.length, 0);
  const selected = r.registry.context("pit", scope), client = await selected.client();
  const value = await client.callTool("list_entries", { path: "/synthetic/a.wpilog" });
  assert.deepEqual(value.args, { path: "/synthetic/a.wpilog", scope_id: scope });
  const data = new DataClient(0, 0, url => client.dataHeaders(url));
  const bytes = await data.fetch(client.endpoint, { path: "/synthetic/a.wpilog", names: ["/same"], scopeId: scope });
  assert.deepEqual(JSON.parse(Buffer.from(bytes.bytes).toString()), { owner: remote.state.owner, scope });
  assert.equal(r.localCalls(), 0); assert.equal(remote.state.calls.some(call => call.route === "/directories" || call.route === "/tba-key"), false);
});
test("two servers with overlapping paths retain separate clients, owner pins, scopes and data", async t => {
  const a = await server(t), b = await server(t);
  const r = registry(t, [{ id: "a", label: "A", kind: "remote", url: a.url }, { id: "b", label: "B", kind: "remote", url: b.url }]);
  const ca = r.registry.context("a", a.state.scopes[0].scope_id), cb = r.registry.context("b", b.state.scopes[0].scope_id);
  const values = await Promise.all([ca, cb].map(async c => (await c.client()).callTool("list_entries", { path: "/same.wpilog" })));
  assert.deepEqual(values.map(v => v.owner), [a.state.owner, b.state.owner]);
  assert.notEqual(await ca.client(), await cb.client()); assert.deepEqual([...r.pins.values()].sort(), [a.state.owner, b.state.owner].sort());
  assert.throws(() => ca.requireReference({ schema_version: 2, server_instance_id: b.state.owner, scope_id: ca.scopeId }), /another server or scope/);
  await assert.rejects((await ca.client()).callTool("list_entries", { path: "/same.wpilog", scope_id: cb.scopeId }), /another source scope/);
});
test("restart revalidates the owner before replay and accepts a new epoch only for that owner", async t => {
  const remote = await server(t), r = registry(t, [{ id: "pit", label: "Pit", kind: "remote", url: remote.url }]);
  const context = r.registry.context("pit"), client = await context.client(), first = context.facts!.server_epoch;
  remote.state.sessions.clear(); remote.state.epoch = randomUUID();
  await client.callTool("list_entries", { path: "/a" }); assert.notEqual(context.facts!.server_epoch, first);
  const calls = remote.state.calls.filter(c => c.body?.method === "tools/call").length;
  remote.state.sessions.clear(); remote.state.owner = randomUUID();
  await assert.rejects(client.callTool("list_entries", { path: "/a" }), /different server/);
  assert.equal(remote.state.calls.filter(c => c.body?.method === "tools/call").length, calls + 1, "only the expired-session refusal reached the replacement; no replay");
  assert.equal(remote.state.sessions.size, 0, "failed handshake deleted its provisional session");
});
test("reconfiguration retires old contexts and discards their late replies", async t => {
  const a = await server(t), b = await server(t), r = registry(t, [{ id: "pit", label: "Pit", kind: "remote", url: a.url }]);
  const old = r.registry.context("pit"), client = await old.client();
  const waiting = client.callTool("held");
  while (!a.state.hold) await new Promise(resolve => setTimeout(resolve, 5));
  r.registry.configure(connectionDefinitions([{ id: "pit", label: "Pit", kind: "remote", url: b.url }]));
  a.state.hold(); await assert.rejects(waiting, /connection changed|disposed/);
  await assert.rejects(old.client(), /removed or changed/);
  await assert.rejects(r.registry.context("pit").client(), /different server identity/);
});
test("scope discovery traverses all pages including unavailable choices without a default", async t => {
  const remote = await server(t); remote.state.scopes = Array.from({ length: 130 }, (_, i) => ({ scope_id: randomUUID(), path: `/scope/${i}`, kind: "directory", available: i !== 7 }));
  const r = registry(t, [{ id: "pit", label: "Pit", kind: "remote", url: remote.url }]);
  const scopes = await r.registry.context("pit").scopes(); assert.deepEqual(scopes, remote.state.scopes);
  assert.deepEqual(remote.state.calls.filter(c => c.route.endsWith("/source/scopes")).map(c => c.body.offset), [0, 64, 128]);
});
test("recorded adapter preserves schema 2 and refuses foreign qualification before sending", async t => {
  const remote = await server(t), r = registry(t, [{ id: "pit", label: "Pit", kind: "remote", url: remote.url }]);
  const context = r.registry.context("pit", remote.state.scopes[0].scope_id); await context.client();
  const backend = new RecordedClient(() => context.client(), context);
  const source = { schema_version: 2, server_instance_id: remote.state.owner, scope_id: context.scopeId, content: { kind: "session", session_id: "same" } };
  await backend.call("/source/statistics", { source_ref: source, entry: "/same" });
  assert.deepEqual(remote.state.calls.at(-1)!.body.source_ref, source); assert.equal(remote.state.calls.at(-1)!.route, "/visualizations/v2/source/statistics");
  const count = remote.state.calls.length;
  await assert.rejects(backend.call("/source/statistics", { source_ref: { ...source, scope_id: randomUUID() } }), /another server or scope/);
  assert.equal(remote.state.calls.length, count);
});
test("source document addresses round-trip Unicode paths and never supply destinations", () => {
  const address = { connection: "pit", owner: randomUUID(), scope: randomUUID(), path: "/robot/é+速度 test.wpilog" };
  assert.deepEqual(readSourceUri(sourceUri(address)), address);
  assert.throws(() => readSourceUri(sourceUri(address) + "&url=http://other/mcp"), /Invalid/);
  assert.throws(() => connectionDefinitions([{ id: "pit", label: "Pit", kind: "remote", url: "https://user:password@pit/mcp" }]), /without credentials/);
  assert.throws(() => connectionDefinitions([{ id: "pit", label: "Pit", kind: "remote", url: "https://pit/unrelated" }]), /MCP URL/);
});
test("direct agent registration uses HTTP with no local launcher or daemon", () => {
  const args = claudeHttpArgs("wpilog-pit", "http://pit:2363/mcp");
  assert.deepEqual(args, ["mcp", "add", "--scope", "user", "wpilog-pit", "--transport", "http", "http://pit:2363/mcp"]);
  assert.equal(sameClaudeRegistration("wpilog-pit:\n  Scope: User config (available in all your projects)\n  Type: http\n  URL: http://pit:2363/mcp\n", args), true);
  assert.equal(sameClaudeRegistration("wpilog-pit:\n  Scope: User config (available in all your projects)\n  Type: http\n  URL: http://other/mcp\n", args), false);
});

test("an explicitly selected local scope works while the configured pit is unavailable", async t => {
  const local = await server(t); let starts = 0;
  const r = new ConnectionRegistry({ version: "test", localEndpoint: async () => { starts++; return local.url; }, pinned: () => undefined, pin: async () => {} });
  t.after(() => r.dispose());
  r.configure(connectionDefinitions([{ id: "local", label: "Local", kind: "local" }, { id: "pit", label: "Offline pit", kind: "remote", url: "http://127.0.0.1:1/mcp" }]));
  assert.equal(starts, 0); await r.refreshLocalRegistrations(); assert.equal(starts, 0);
  const scope = local.state.scopes[0].scope_id;
  const value = await (await r.context("local", scope).client()).callTool("list_entries", { path: "/same.wpilog" });
  assert.equal(value.owner, local.state.owner); assert.equal((value.args as any).scope_id, scope); assert.equal(starts, 1);
});

test("data credentials cannot cross a connection's origin or be sent to another endpoint", async t => {
  const remote = await server(t), r = registry(t, [{ id: "pit", label: "Pit", kind: "remote", url: remote.url }]);
  const client = await r.registry.context("pit").client();
  for (const url of ["http://other.invalid/data/entries", new URL("/mcp", remote.url).href, new URL("/data/entries#hidden", remote.url).href])
    await assert.rejects(client.dataHeaders(url), /data endpoint|Data|origin|Invalid/);
});

test("native URI serialization preserves query delimiters and special characters in a source path", () => {
  const address = { connection: "pit", owner: randomUUID(), scope: randomUUID(), path: "/robot/速度 & gain=20% + test.wpilog" };
  const raw = new URL(sourceUri(address));
  // VS Code Uri.parse decodes the query and Uri.toString encodes it as one component.
  raw.search = encodeURIComponent(decodeURIComponent(raw.search.slice(1)));
  assert.deepEqual(readSourceUri(raw.href), address);
});
