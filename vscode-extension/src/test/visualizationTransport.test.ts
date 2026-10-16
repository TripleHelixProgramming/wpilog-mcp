import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import { McpClient } from "../mcpClient";
import { DataClient } from "../dataClient";

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
