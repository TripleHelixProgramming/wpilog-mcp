import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import { McpClient } from "../mcpClient";
import { DataClient } from "../dataClient";

test("native view follows borrow the registered MCP session only on its own follow route", async t => {
  let opens = 0, credentials = 0;
  const server = http.createServer((req, res) => {
    let text = ""; req.on("data", c => text += c); req.on("end", () => {
      const body = text ? JSON.parse(text) : {};
      if (body.method === "initialize") { opens++; res.setHeader("Mcp-Session-Id", "owned"); }
      res.end(JSON.stringify({ jsonrpc: "2.0", id: body.id, result: {} }));
    });
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const endpoint = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`;
  const client = new McpClient(endpoint, "test", undefined, async () => { credentials++; return { Authorization: "synthetic" }; });
  t.after(async () => { await client.dispose(); server.closeAllConnections(); server.close(); });
  for (const path of ["http://outside.invalid/read/v1/follows", new URL("/wrong", endpoint).href])
    await assert.rejects(() => (client as any).followHeaders(path), /origin|route/);
  assert.equal(credentials, 0); assert.equal(opens, 0);
  const headers = await (client as any).followHeaders(new URL("/read/v1/follows", endpoint).href);
  assert.deepEqual(headers, { Authorization: "synthetic", "Mcp-Session-Id": "owned" });
  await (client as any).followHeaders(new URL("/read/v1/follows/lease/next", endpoint).href);
  assert.equal(opens, 1, "follow cannot create a second directory/key lease");
});

test("preview uses registered sessions and re-registers only after an explicit expiry", async t => {
  const calls: string[] = []; let opens = 0;
  const server = http.createServer((req, res) => {
    let text = ""; req.on("data", c => text += c); req.on("end", () => {
      if (req.url === "/mcp") {
        const body = text ? JSON.parse(text) : {};
        if (body.method === "initialize") { opens++; res.setHeader("Mcp-Session-Id", `s${opens}`); }
        res.end(JSON.stringify({ jsonrpc: "2.0", id: body.id, result: {} })); return;
      }
      const id = req.headers["mcp-session-id"] as string; calls.push(id);
      if (id === "s1") { res.statusCode = 404; res.end('{"code":"SESSION_EXPIRED","error":"expired"}'); }
      else res.end('{"client_id":"s2"}');
    });
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const client = new McpClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, "test");
  t.after(async () => { await client.dispose(); await new Promise<void>(r => server.close(() => r())); });
  assert.deepEqual(await (client as any).visualization("/capabilities"), { client_id: "s2" });
  assert.deepEqual(calls, ["s1", "s2"]);
  await assert.rejects(() => (client as any).visualization("https://outside.invalid"), /route/);
});

test("closing a preview cancels its pending Arrow request", async t => {
  let entered!: () => void; const seen = new Promise<void>(r => entered = r);
  let disconnected!: () => void; const closed = new Promise<void>(r => disconnected = r);
  const server = http.createServer((req, res) => { res.on("close", disconnected); entered(); });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const controller = new AbortController();
  const client = new DataClient(0, 0);
  const result = (client.fetch as any)(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, { path: "/fixture.wpilog", names: ["/v"] }, controller.signal);
  await seen; controller.abort();
  // Bound the old-code failure, which otherwise leaves a 10-minute request waiting.
  const deadline = setTimeout(() => server.closeAllConnections(), 250); t.after(() => clearTimeout(deadline));
  const start = Date.now(); await assert.rejects(result, /abort|cancel/i); await closed;
  assert.ok(Date.now() - start < 200, "cancellation must destroy the request, not wait for the server timeout");
});

test("preview rejects an oversized Arrow reply before accumulating it", async t => {
  const server = http.createServer((_req, res) => { res.end(Buffer.alloc(128)); });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const client = new (DataClient as any)(0, 0, undefined, 64);
  await assert.rejects(client.fetch(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, { path: "/fixture.wpilog", names: ["/v"] }), /response.*limit/i);
});

for (const which of ["reply", "request", "owned session", "deadline"] as const) test(`inventory transport bounds ${which}`, async t => {
  let calls = 0, opens = 0;
  const server = http.createServer((req, res) => {
    let text = ""; req.on("data", c => text += c); req.on("end", () => {
      if (req.url === "/mcp") {
        const body = text ? JSON.parse(text) : {};
        if (body.method === "initialize") res.setHeader("Mcp-Session-Id", `s${++opens}`);
        res.end(JSON.stringify({ jsonrpc: "2.0", id: body.id, result: {} })); return;
      }
      calls++;
      if (which === "owned session" && req.headers["mcp-session-id"] === "s1") {
        res.statusCode = 404; res.end('{"code":"SESSION_EXPIRED","error":"expired"}');
      } else if (which === "reply") res.end(JSON.stringify({ text: "é".repeat(600_000) }));
      else if (which === "deadline") { const timer = setInterval(() => res.write(" "), 10); res.on("close", () => clearInterval(timer)); }
      else res.end("{}");
    });
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const client = new McpClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, "test");
  t.after(async () => { server.closeAllConnections(); await client.dispose(); server.close(); });
  const controller = new AbortController();
  const guard = setTimeout(() => controller.abort(), 500); t.after(() => clearTimeout(guard));
  const options = { maxResponseBytes: 1 << 20, maxRequestBytes: 64 << 10, timeoutMs: 80,
    ...(which === "owned session" ? { session: "s1" } : {}) };
  const start = Date.now();
  await assert.rejects((client.visualization as any).call(client, "/source/entries/list",
    which === "request" ? { query: "é".repeat(40_000) } : {}, controller.signal, options),
    which === "reply" ? /byte budget/ : which === "request" ? /request.*byte budget/i : which === "owned session" ? /SESSION_EXPIRED/ : /deadline/i);
  if (which === "request") assert.equal(calls, 0, "refuse before sending the oversized body");
  if (which === "owned session") assert.equal(calls, 1, "a handle cannot migrate to a new MCP session");
  if (which === "deadline") assert.ok(Date.now() - start < 350, "trickled bytes cannot extend the deadline");
});

test("inventory refuses malformed retryable flags rather than treating them as admission advice", async t => {
  const server = http.createServer((req, res) => { req.resume(); req.on("end", () => { res.statusCode = 409; res.end('{"code":"INVENTORY_CHANGED","error":"changed","retryable":"yes"}'); }); });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const client = new McpClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, "test");
  await assert.rejects(client.visualization("/source/entries/list", {}, undefined, { session: "s" }), /Invalid visualization refusal retryable/);
});

/** A small reply can arrive whole in the chunk that crosses the budget; the destroy that follows
 * does not stop its end event, which must refuse rather than resolve with what was collected. */
test("a visualization reply over its byte budget is refused even when it arrives whole", async t => {
  const server = http.createServer((req, res) => {
    let text = ""; req.on("data", c => text += c); req.on("end", () => {
      if (req.url === "/mcp") { res.setHeader("Mcp-Session-Id", "s1"); res.end(JSON.stringify({ jsonrpc: "2.0", id: text ? JSON.parse(text).id : null, result: {} })); return; }
      res.setHeader("Content-Type", "application/json"); res.end(JSON.stringify({ capabilities: "x".repeat(64) }));
    });
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const client = new McpClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`, "test");
  t.after(async () => { await client.dispose(); server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); });
  await assert.rejects(client.visualization("/capabilities", undefined, undefined, { maxResponseBytes: 16 }), /byte budget/);
});
