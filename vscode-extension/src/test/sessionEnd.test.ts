// Tests for ending a window's server sessions as it closes (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "http";
import { AddressInfo } from "net";
import { McpClient } from "../mcpClient";
import { PROTOCOL_VERSION, SESSION_HEADER } from "../explorer/mcpProtocol";
import { SESSION_END_MS, endWithin } from "../sessionEnd";

/** A server that opens sessions and either ends them on DELETE or never answers it. */
async function server(answerDelete: boolean): Promise<{ url: string; sessions: Set<string>; deletes: string[]; close(): Promise<void> }> {
  const sessions = new Set<string>(), deletes: string[] = [], held: http.ServerResponse[] = [];
  let next = 1;
  const http1 = http.createServer((request, response) => {
    let body = "";
    request.on("data", (chunk: string) => (body += chunk));
    request.on("end", () => {
      const session = request.headers[SESSION_HEADER.toLowerCase()] as string | undefined;
      if (request.method === "DELETE") {
        deletes.push(session ?? "");
        if (!answerDelete) { held.push(response); return; }
        response.writeHead(sessions.delete(session ?? "") ? 200 : 404).end(); return;
      }
      const message = JSON.parse(body) as { id?: number; method: string };
      if (message.method === "initialize") {
        const id = `s${next++}`; sessions.add(id);
        response.writeHead(200, { "Content-Type": "application/json", [SESSION_HEADER]: id })
          .end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { protocolVersion: PROTOCOL_VERSION, capabilities: { tools: {} } } }));
        return;
      }
      if (message.id === undefined) { response.writeHead(202).end(); return; }
      response.writeHead(200, { "Content-Type": "application/json" })
        .end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { content: [{ type: "text", text: JSON.stringify({ success: true, status: "ok" }) }] } }));
    });
  });
  await new Promise<void>(resolve => http1.listen(0, "127.0.0.1", () => resolve()));
  return {
    url: `http://127.0.0.1:${(http1.address() as AddressInfo).port}/mcp`, sessions, deletes,
    close: () => { for (const response of held) response.destroy(); http1.closeAllConnections(); return new Promise(done => http1.close(() => done())); },
  };
}

test("the bound waits for every ending and no longer, and a failed ending counts as finished", async () => {
  assert.equal(await endWithin([Promise.resolve(), Promise.reject(new Error("unreachable")), new Promise(r => setTimeout(r, 20))], 1_000), true);
  const started = Date.now();
  assert.equal(await endWithin([Promise.resolve(), new Promise(() => {})], 150), false);
  const waited = Date.now() - started;
  assert.ok(waited >= 140 && waited < 1_000, `waited ${waited} ms for a 150 ms bound`);
  assert.equal(await endWithin([], 150), true);
  assert.ok(SESSION_END_MS <= 3_000, "inside the editor's shutdown allowance");
});

test("a closing window ends its sessions on the server before it is shut down, and sends nothing after", async () => {
  const pit = await server(true), local = await server(true);
  try {
    const clients = [new McpClient(pit.url, "0.10.0"), new McpClient(local.url, "0.10.0")];
    for (const client of clients) await client.callTool("list_entries", {});
    assert.equal(pit.sessions.size + local.sessions.size, 2);
    assert.equal(await endWithin(clients.map(client => client.dispose()), SESSION_END_MS), true);
    assert.deepEqual([pit.deletes, local.deletes], [["s1"], ["s1"]]);
    assert.equal(pit.sessions.size + local.sessions.size, 0, "both servers released the window's views with its sessions");
    await assert.rejects(clients[0].callTool("list_entries", {}), /disposed/);
  } finally { await pit.close(); await local.close(); }
});

test("a server that never answers the ending does not hold the closing window past its bound", async () => {
  const silent = await server(false);
  try {
    const client = new McpClient(silent.url, "0.10.0");
    await client.callTool("list_entries", {});
    const started = Date.now();
    assert.equal(await endWithin([client.dispose()], 200), false);
    assert.ok(Date.now() - started < 1_500, "the window closes on time");
    assert.deepEqual(silent.deletes, ["s1"], "the ending was sent");
  } finally { await silent.close(); }
});
