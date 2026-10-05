// Tests for the explorer's MCP client and its protocol (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "http";
import { AddressInfo } from "net";
import { McpClient, ToolError } from "../mcpClient";
import {
  PROTOCOL_VERSION,
  SESSION_HEADER,
  initializeRequest,
  initializedNotification,
  parseToolResponse,
  sessionLost,
  toolCallRequest,
} from "../explorer/mcpProtocol";

// ---- the protocol ----

test("initialize names the client and the protocol version the server speaks; initialized has no id", () => {
  const init = initializeRequest(1, "0.9.1");
  assert.equal(init.method, "initialize");
  assert.equal(init.id, 1);
  assert.deepEqual(init.params, {
    protocolVersion: PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: "wpilog-explorer", version: "0.9.1" },
  });
  assert.equal(PROTOCOL_VERSION, "2025-03-26", "McpMessageHandler.PROTOCOL_VERSION");
  const done = initializedNotification();
  assert.equal(done.method, "notifications/initialized");
  assert.ok(!("id" in done), "a notification has no id, so the server answers 202");
  assert.deepEqual(toolCallRequest(7, "list_entries", { path: "/a.wpilog" }), {
    jsonrpc: "2.0",
    id: 7,
    method: "tools/call",
    params: { name: "list_entries", arguments: { path: "/a.wpilog" } },
  });
});

test("a tool's result is the JSON of its text content; isError makes it a failure with the tool's words", () => {
  const wrapped = (text: string, isError?: boolean) => ({
    jsonrpc: "2.0",
    id: 1,
    result: { content: [{ type: "text", text }], ...(isError ? { isError: true } : {}) },
  });
  assert.deepEqual(parseToolResponse(wrapped('{"success":true,"status":"ok","entry_count":3}')), {
    ok: true,
    result: { success: true, status: "ok", entry_count: 3 },
  });
  const failed = parseToolResponse(wrapped('{"success":false,"status":"error","error":"File not found: /x"}', true));
  assert.deepEqual(failed, {
    ok: false,
    error: "File not found: /x",
    result: { success: false, status: "error", error: "File not found: /x" },
  });
  // The tool base returns an error status as an ordinary result, without the envelope's flag
  const unflagged = parseToolResponse(wrapped('{"success":false,"status":"error","error":"File not found: /x"}'));
  assert.ok(!unflagged.ok && unflagged.error === "File not found: /x" && unflagged.result?.status === "error");
  // A no_match is not an error: the tool answered, and the result says what it looked for
  assert.ok(parseToolResponse(wrapped('{"success":false,"status":"no_match","reason":"none"}')).ok);
  assert.ok(parseToolResponse(wrapped('{"success":false,"status":"not_applicable","reason":"none"}')).ok);
});

test("a JSON-RPC error, no text content, or text that is not JSON is reported, not guessed at", () => {
  const rpcError = parseToolResponse({ jsonrpc: "2.0", id: 1, error: { code: -32601, message: "Unknown method: x" } });
  assert.ok(!rpcError.ok && rpcError.error.includes("Unknown method: x"));
  const noText = parseToolResponse({ jsonrpc: "2.0", id: 1, result: { content: [] } });
  assert.ok(!noText.ok && noText.error.includes("no text content"));
  const notJson = parseToolResponse({ jsonrpc: "2.0", id: 1, result: { content: [{ type: "text", text: "<html>" }] } });
  assert.ok(!notJson.ok && notJson.error.includes("not JSON"));
  const notObject = parseToolResponse({ jsonrpc: "2.0", id: 1, result: { content: [{ type: "text", text: "[1]" }] } });
  assert.ok(!notObject.ok && notObject.error.includes("not an object"));
  assert.ok(!parseToolResponse(null).ok);
  assert.ok(!parseToolResponse("text").ok);
});

test("a 404 with a session means the session is gone; without one it is just a 404", () => {
  assert.ok(sessionLost(404, true));
  assert.ok(!sessionLost(404, false));
  assert.ok(!sessionLost(200, true));
  assert.ok(!sessionLost(500, true));
});

// ---- the client, against a server that behaves as the transport does ----

interface FakeServer {
  url: string;
  requests: { method: string; session?: string; body: unknown }[];
  sessions: Set<string>;
  /** Forgets every session, as a restart would. */
  restart(): void;
  close(): Promise<void>;
}

/** A server that speaks the transport's HTTP: sessions, 202 for notifications, 404 for a lost session. */
function fakeServer(handleTool: (name: string, args: Record<string, unknown>) => unknown): Promise<FakeServer> {
  const requests: FakeServer["requests"] = [];
  const sessions = new Set<string>();
  let nextSession = 1;
  const server = http.createServer((request, response) => {
    let text = "";
    request.on("data", (chunk: string) => (text += chunk));
    request.on("end", () => {
      const session = request.headers[SESSION_HEADER.toLowerCase()] as string | undefined;
      if (request.method === "DELETE") {
        requests.push({ method: "DELETE", session, body: undefined });
        response.writeHead(sessions.delete(session ?? "") ? 200 : 404).end();
        return;
      }
      const message = JSON.parse(text) as { id?: number; method: string; params?: { name: string; arguments: Record<string, unknown> } };
      requests.push({ method: message.method, session, body: message });
      const reply = (status: number, body: unknown, headers: Record<string, string> = {}) => {
        response.writeHead(status, { "Content-Type": "application/json", ...headers });
        response.end(JSON.stringify(body));
      };
      if (message.method === "initialize") {
        const id = `s${nextSession++}`;
        sessions.add(id);
        reply(200, { jsonrpc: "2.0", id: message.id, result: { protocolVersion: PROTOCOL_VERSION, capabilities: { tools: {} } } },
          { [SESSION_HEADER]: id });
        return;
      }
      if (!session) {
        response.writeHead(400, { "Content-Type": "application/json" }).end(JSON.stringify({ error: "Missing header" }));
        return;
      }
      if (!sessions.has(session)) {
        response.writeHead(404, { "Content-Type": "application/json" }).end(JSON.stringify({ error: "Session not found or expired" }));
        return;
      }
      if (message.id === undefined) {
        response.writeHead(202).end();
        return;
      }
      // As the real server: an error status is an ordinary result, with no isError flag
      const result = handleTool(message.params!.name, message.params!.arguments);
      reply(200, { jsonrpc: "2.0", id: message.id, result: { content: [{ type: "text", text: JSON.stringify(result) }] } });
    });
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const port = (server.address() as AddressInfo).port;
      resolve({
        url: `http://127.0.0.1:${port}/mcp`,
        requests,
        sessions,
        restart: () => sessions.clear(),
        close: () => new Promise((done) => server.close(() => done())),
      });
    });
  });
}

test("the client initializes once, then calls tools with the session header, and deletes the session on dispose", async () => {
  const server = await fakeServer((name, args) => ({ success: true, status: "ok", tool: name, args }));
  try {
    const client = new McpClient(server.url, "0.9.1");
    assert.ok(!client.connected);
    const [a, b] = await Promise.all([
      client.callTool("list_entries", { path: "/a.wpilog" }),
      client.callTool("get_entry_info", { path: "/a.wpilog", name: "/x" }),
    ]);
    assert.deepEqual(a, { success: true, status: "ok", tool: "list_entries", args: { path: "/a.wpilog" } });
    assert.equal(b.tool, "get_entry_info");
    assert.ok(client.connected);
    const methods = server.requests.map((r) => r.method);
    assert.equal(methods.filter((m) => m === "initialize").length, 1, "two calls at once share one handshake");
    assert.equal(methods[1], "notifications/initialized");
    const calls = server.requests.filter((r) => r.method === "tools/call");
    assert.equal(calls.length, 2);
    assert.ok(calls.every((r) => r.session === "s1"), "every call carries the session");
    await client.dispose();
    assert.equal(server.requests.at(-1)?.method, "DELETE");
    assert.equal(server.sessions.size, 0, "the session is gone, so an idle server may exit");
    await assert.rejects(client.callTool("list_entries"), /disposed/);
  } finally {
    await server.close();
  }
});

test("an error result rejects with the tool's own message and result; the server's refusal with its message", async () => {
  const server = await fakeServer((name) =>
    name === "list_entries"
      ? { success: false, status: "error", error: "File not found: /missing.wpilog", hint: "list_available_logs" }
      : { success: true, status: "ok" }
  );
  try {
    const client = new McpClient(server.url, "0.9.1");
    await assert.rejects(client.callTool("list_entries", { path: "/missing.wpilog" }), (error: unknown) => {
      assert.ok(error instanceof ToolError);
      assert.equal(error.message, "File not found: /missing.wpilog");
      assert.equal(error.tool, "list_entries");
      assert.equal(error.result?.hint, "list_available_logs");
      return true;
    });
    assert.ok(client.connected, "a tool error does not end the session");
    await client.dispose();
  } finally {
    await server.close();
  }
});

test("a session the server forgot (a restart) is replaced once and the call sent again", async () => {
  const server = await fakeServer(() => ({ success: true, status: "ok" }));
  try {
    const client = new McpClient(server.url, "0.9.1");
    await client.callTool("ping_tool");
    server.restart();
    const result = await client.callTool("list_entries", { path: "/a.wpilog" });
    assert.equal(result.status, "ok");
    const methods = server.requests.map((r) => r.method);
    assert.deepEqual(methods, [
      "initialize", "notifications/initialized", "tools/call",
      "tools/call", // answered 404
      "initialize", "notifications/initialized", "tools/call",
    ]);
    const sessions = server.requests.filter((r) => r.method === "tools/call").map((r) => r.session);
    assert.deepEqual(sessions, ["s1", "s1", "s2"]);
    await client.dispose();
  } finally {
    await server.close();
  }
});

test("a server that cannot be reached fails the call with the endpoint in the message", async () => {
  const server = await fakeServer(() => ({}));
  await server.close();
  const client = new McpClient(server.url, "0.9.1");
  await assert.rejects(client.callTool("list_entries"), (error: unknown) => {
    assert.ok(error instanceof ToolError);
    assert.ok(error.message.includes(server.url), error.message);
    assert.ok(error.message.includes("could not be reached"), error.message);
    return true;
  });
  assert.ok(!client.connected);
  await client.dispose();
});

test("a server that is not wpilog-mcp (no session header on initialize) is reported, not used", async () => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "text/html" }).end("<html>hello</html>");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const port = (server.address() as AddressInfo).port;
    const client = new McpClient(`http://127.0.0.1:${port}/mcp`, "0.9.1");
    await assert.rejects(client.callTool("list_entries"), /did not open a session/);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
