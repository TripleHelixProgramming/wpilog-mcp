import { test, TestContext } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import { McpClient } from "../mcpClient";
import { SessionRegistration } from "../directoryLease";

function latch() {
  let release!: () => void;
  const promise = new Promise<void>(resolve => { release = resolve; });
  return { promise, release };
}

async function fixture(t: TestContext) {
  const records: { route: string; session?: string; body: any }[] = [];
  const sessions = new Map<string, { directories?: unknown; key?: unknown }>();
  const failure = { route: "", status: 403 };
  let next = 0;
  const server = http.createServer((request, response) => {
    let text = "";
    request.on("data", chunk => { text += chunk; });
    request.on("end", () => {
      const session = request.headers["mcp-session-id"] as string | undefined;
      const body = text ? JSON.parse(text) : undefined;
      const route = request.method === "DELETE" ? "DELETE" : request.url !== "/mcp" ? request.url! : body.method;
      records.push({ route, session, body });
      const reply = (status: number, value: unknown) => response.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(value));
      if (route === "initialize") {
        const id = `s${++next}`;
        sessions.set(id, {});
        response.setHeader("Mcp-Session-Id", id);
        reply(200, { jsonrpc: "2.0", id: body.id, result: {} });
      } else if (!session || !sessions.has(session)) reply(404, { error: "Session not found" });
      else if (route === "DELETE") { sessions.delete(session); reply(200, {}); }
      else if (route === failure.route) reply(failure.status, { error: "Refused /outside synthetic-private-key", hint: "Choose an existing directory." });
      else if (route === "/directories") { sessions.get(session)!.directories = body; reply(200, {}); }
      else if (route === "/tba-key") { sessions.get(session)!.key = body.key; reply(200, {}); }
      else if (route === "notifications/initialized") response.writeHead(202).end();
      else if (route === "ping") reply(200, { jsonrpc: "2.0", id: body.id, result: {} });
      else if (!sessions.get(session)!.directories) reply(400, { error: "The tool reached a session before its directory registration" });
      else {
        reply(200, { jsonrpc: "2.0", id: body.id, result: { content: [{ type: "text", text: '{"status":"ok"}' }] } });
      }
    });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise<void>(resolve => server.close(() => resolve())));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`;
  return { url, records, sessions, failure };
}
const initial = (): SessionRegistration => ({ directories: { paths: [{ path: "/project/logs", team: 2363 }], team: null }, key: "synthetic-private-key" });

test("tools wait for the lease; settings replace it, unchanged inputs do not, and disposal releases it", async t => {
  const server = await fixture(t);
  let value = initial();
  const client = new McpClient(server.url, "test", async () => value);
  t.after(() => client.dispose());
  await Promise.all([client.callTool("list_available_logs"), client.callTool("list_available_logs")]);
  assert.deepEqual(server.records.slice(0, 4).map(r => r.route), ["initialize", "notifications/initialized", "/directories", "/tba-key"]);
  assert.equal(server.sessions.size, 1);
  assert.deepEqual(server.sessions.get("s1"), { directories: initial().directories, key: "synthetic-private-key" });
  await client.refreshRegistration();
  assert.equal(server.records.filter(r => r.route === "/directories").length, 1);
  value = { directories: { paths: [{ path: "/new/logs", team: 9999 }], team: null }, key: null };
  await client.refreshRegistration();
  assert.deepEqual(server.sessions.get("s1"), { directories: value.directories, key: null });
  assert.equal(server.records.filter(r => r.route === "initialize").length, 1, "no session or server restart on a settings/key change");
  assert.ok(server.records.filter(r => r.route === "tools/call").every(r => !JSON.stringify(r.body).includes("synthetic-private-key")));
  await client.dispose();
  assert.equal(server.sessions.size, 0);
});

test("a lost session is registered again on tools, refresh, and heartbeat, including unchanged inputs", async t => {
  const server = await fixture(t);
  const client = new McpClient(server.url, "test", async () => initial());
  t.after(() => client.dispose());
  await client.refreshRegistration();
  for (const reconnect of [() => client.callTool("list_available_logs"), () => client.refreshRegistration(), () => client.keepAlive()]) {
    server.sessions.clear();
    await reconnect();
    assert.equal(server.sessions.size, 1);
    assert.deepEqual([...server.sessions.values()][0], { directories: initial().directories, key: "synthetic-private-key" });
  }
  assert.equal(server.records.filter(r => r.route === "/directories").length, 4);
});

test("an update queued during handshake cannot be lost behind its older registration", async t => {
  const server = await fixture(t);
  const entered = latch();
  const release = latch();
  let value = initial();
  let first = true;
  const client = new McpClient(server.url, "test", async () => {
    const snapshot = structuredClone(value);
    if (first) { first = false; entered.release(); await release.promise; }
    return snapshot;
  });
  t.after(() => client.dispose());
  const opening = client.callTool("list_available_logs");
  await entered.promise;
  value = { directories: { paths: [], team: null }, key: null };
  const updating = client.refreshRegistration();
  release.release();
  await Promise.all([opening, updating]);
  assert.deepEqual([...server.sessions.values()][0], { directories: value.directories, key: null });
  assert.equal(server.records.filter(r => r.route === "initialize").length, 1);
});

test("overlapping lease refreshes keep the newest inputs and tools wait for their completion", async t => {
  const server = await fixture(t);
  const entered = latch();
  const release = latch();
  let value = initial();
  let block = false;
  const client = new McpClient(server.url, "test", async () => {
    const snapshot = structuredClone(value);
    if (block) { block = false; entered.release(); await release.promise; }
    return snapshot;
  });
  t.after(() => client.dispose());
  await client.refreshRegistration();
  // Queue behavior is independent of network scheduling. Complete exchanges synchronously
  // after the real handshake; setImmediate below drains promise callbacks, not a timed delay.
  const requests: { route: string; body: any }[] = [];
  (client as unknown as { exchange(endpoint: string, method: string, body: any): Promise<unknown> }).exchange =
    async (endpoint, _method, body) => {
      const route = new URL(endpoint).pathname;
      requests.push({ route, body });
      return { status: 200, body: JSON.stringify({ jsonrpc: "2.0", id: body?.id,
        result: { content: [{ type: "text", text: '{"status":"ok"}' }] } }) };
    };
  block = true;
  value = { ...initial(), key: "intermediate" };
  const older = client.refreshRegistration();
  await entered.promise;
  value = { directories: { paths: [], team: null }, key: null };
  const newer = client.refreshRegistration();
  const tool = client.callTool("list_available_logs");
  await new Promise<void>(resolve => setImmediate(resolve));
  const overtook = requests.filter(request => request.route !== "/mcp" || request.body.method === "tools/call").length;
  release.release();
  await Promise.all([older, newer, tool]);
  assert.equal(overtook, 0, "neither a newer registration nor a tool can overtake the blocked older refresh");
  assert.deepEqual(requests.filter(request => request.route === "/tba-key").map(request => request.body.key), ["intermediate", null]);
  assert.equal(requests.at(-1)?.body.method, "tools/call");
});

for (const route of ["/directories", "/tba-key"]) {
  test(`a refused ${route} closes the new session; key errors cannot echo a secret`, async t => {
    const server = await fixture(t);
    server.failure.route = route;
    const client = new McpClient(server.url, "test", async () => initial());
    t.after(() => client.dispose());
    await assert.rejects(client.callTool("list_available_logs"), error => {
      const message = String(error);
      if (route === "/directories") assert.match(message, /Refused \/outside.*Choose an existing directory/);
      else { assert.match(message, /HTTP 403/); assert.ok(!message.includes("synthetic-private-key")); }
      return true;
    });
    assert.equal(server.sessions.size, 0);
    assert.equal(server.records.filter(r => r.route === "tools/call").length, 0);
  });
}

test("disposal during initialization leaves no registered session or key", async t => {
  const server = await fixture(t);
  const entered = latch();
  const release = latch();
  const client = new McpClient(server.url, "test", async () => { entered.release(); await release.promise; return initial(); });
  const opening = client.callTool("list_available_logs");
  const rejected = assert.rejects(opening, /disposed/);
  await entered.promise;
  const disposing = client.dispose();
  release.release();
  await Promise.all([rejected, disposing]);
  assert.equal(server.sessions.size, 0);
  assert.equal(client.connected, false);
});
