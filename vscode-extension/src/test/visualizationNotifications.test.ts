import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { McpClient } from "../mcpClient";

async function until(check: () => boolean): Promise<void> {
  const deadline = Date.now() + 12000;
  while (!check()) { if (Date.now() > deadline) throw new Error("notification condition timed out"); await new Promise(r => setTimeout(r, 20)); }
}
test("control events negotiate, reconnect with replay id, deduplicate and recover a replaced session after a failed handshake", async t => {
  const events: Record<string, unknown>[] = [], replay: (string | undefined)[] = [];
  let attempts = 0, gets = 0, negotiated = false, held: http.ServerResponse | undefined;
  const event = (id: number) => `id: epoch:${id}\ndata: ${JSON.stringify({ jsonrpc: "2.0", method: "notifications/experimental/visualization", params: { kind: "view_revision_changed", revision: id } })}\n\n`;
  const server = http.createServer(async (req, res) => {
    if (req.method === "DELETE") { res.end(); return; }
    if (req.method === "GET") {
      replay.push(req.headers["last-event-id"] as string | undefined); gets++;
      if (gets === 3) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      if (gets === 1) res.end(event(1));
      else if (gets === 2) res.end(event(1) + event(2));
      else { res.write(event(3)); held = res; }
      return;
    }
    let text = ""; for await (const chunk of req) text += chunk; const message = JSON.parse(text);
    if (message.method === "initialize") {
      attempts++; negotiated = message.params.capabilities.experimental["wpilog/visualizationNotifications"] === true;
      if (attempts === 2) { res.writeHead(503); res.end("restart in progress"); return; }
      res.writeHead(200, { "Mcp-Session-Id": `session-${attempts}`, "Content-Type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { capabilities: {}, serverInfo: { name: "wpilog-mcp", version: "fixture" }, protocolVersion: "2025-03-26" } }));
    } else { res.writeHead(202); res.end(); }
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const client = new McpClient(`http://127.0.0.1:${(server.address() as any).port}/mcp`, "fixture");
  t.after(async () => { held?.end(); await client.dispose(); server.closeAllConnections(); server.close(); });
  const subscription = client.onVisualization(value => events.push(value));
  await until(() => events.some(e => e.revision === 3));
  assert.equal(negotiated, true); assert.ok(attempts >= 3);
  assert.deepEqual(events.filter(e => e.revision).map(e => e.revision), [1, 2, 3]);
  assert.equal(replay[1], "epoch:1"); assert.equal(replay[2], "epoch:2"); assert.equal(replay[3], undefined);
  assert.ok(events.filter(e => e.kind === "resync_required").length >= 2);
  subscription.dispose(); const before = gets; await new Promise(r => setTimeout(r, 450)); assert.equal(gets, before);
});
