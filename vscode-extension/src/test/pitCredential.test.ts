import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { basicCredential, pitSecretKey, pitRegistration, pitHeaders, pitBridgeUrl } from "../pitCredential";
import { McpClient } from "../mcpClient";
import { StoreClient } from "../explorer/storeClient";
import { uploadLog } from "../explorer/upload";
import { DataClient } from "../dataClient";
import { leaseChanged } from "../directoryLease";
import { claudePitArgs } from "../claudeRegistration";

test("proxy secrets are origin-scoped, cleared by lease replacement, and absent from bridge arguments", async () => {
  const secret = basicCredential("synthetic", "password");
  assert.equal(secret, "Basic c3ludGhldGljOnBhc3N3b3Jk");
  assert.equal(pitSecretKey("https://pit.example:443/mcp"), "wpilog-mcp.pitProxy:https://pit.example");
  assert.notEqual(pitSecretKey("https://pit.example"), pitSecretKey("https://pit.example:8443"));
  assert.equal(Buffer.from(secret.substring(6), "base64").toString(), "synthetic:password");
  assert.throws(() => basicCredential("bad:name", "secret"), /username/);
  const saved = new Map([[pitSecretKey("https://pit.example/mcp"), secret]]);
  const secrets = { get: async (key: string) => saved.get(key) };
  const headers = pitHeaders(() => "https://pit.example/mcp", secrets);
  assert.deepEqual(await headers("https://pit.example/store"), { Authorization: secret });
  for (const url of ["http://pit.example/store", "https://pit.example:8443/store", "https://other.example/store"]) assert.deepEqual(await headers(url), {});
  assert.deepEqual(await pitRegistration("https://pit.example/mcp", secrets), { url: "https://pit.example/mcp", authorization: secret });
  const before = { directories: { paths: [], team: null }, key: null, pitCredential: await pitRegistration("https://pit.example/mcp", secrets) };
  saved.clear(); const after = { ...before, pitCredential: await pitRegistration("https://pit.example/mcp", secrets) };
  assert.equal(after.pitCredential.authorization, null); assert.ok(leaseChanged(before, after));
  assert.deepEqual(await pitRegistration(undefined, secrets), { url: "", authorization: null });
  const bridge = pitBridgeUrl("http://127.0.0.1:1234/mcp", "https://pit.example/mcp");
  assert.equal(new URL(bridge).pathname, "/pit-mcp"); assert.equal(new URL(bridge).searchParams.get("url"), "https://pit.example/mcp");
  const args = claudePitArgs("wpilog-mcp", bridge, "linux").join(" "); assert.ok(args.includes("--url"));
  assert.ok(!args.includes(secret)); assert.ok(!args.includes("password"));
});

test("MCP, plots, store discovery, upload and polling send the same scoped credential without redirects", async () => {
  const seen: string[] = []; const secret = basicCredential("synthetic", "password");
  const result = { files: [], same_robots: [] }; let registrations = 0;
  const server = http.createServer(async (req, res) => {
    if (req.headers.authorization !== secret) { req.resume(); res.writeHead(401).end("credential required"); return; }
    seen.push(new URL(req.url!, "http://pit").pathname);
    const chunks: Buffer[] = []; for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString();
    res.setHeader("Content-Type", "application/json");
    if (req.url!.startsWith("/data/entries")) { res.end(Buffer.from([1, 2, 3])); return; }
    if (req.url === "/store") { res.end('{"id":"one","mirror":false}'); return; }
    if (req.url!.startsWith("/store/import?")) { res.writeHead(202); res.end('{"job_id":"one","url":"/store/import/one"}'); return; }
    if (req.url === "/store/import/one") { res.end(JSON.stringify({ state: "done", result })); return; }
    if (["/directories", "/tba-key", "/pit-credential"].includes(req.url!)) {
      if (req.url === "/pit-credential") { assert.equal(JSON.parse(body).authorization, secret); registrations++; }
      res.end('{"status":"ok"}'); return;
    }
    if (req.method === "DELETE" || !JSON.parse(body).id) { res.writeHead(204).end(); return; }
    const rpc = JSON.parse(body); res.setHeader("Mcp-Session-Id", "test-session");
    res.end(JSON.stringify({ jsonrpc: "2.0", id: rpc.id, result: rpc.method === "initialize"
      ? { protocolVersion: "2025-03-26", capabilities: {}, serverInfo: { name: "test", version: "1" } }
      : { content: [{ type: "text", text: '{"success":true,"status":"ok","value":7}' }] } }));
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`;
  const secrets = { get: async () => secret }, headers = pitHeaders(() => url, secrets);
  const client = new McpClient(url, "test", async () => ({ directories: { paths: [], team: null }, key: null, pitCredential: await pitRegistration(url, secrets) }), headers);
  const scratch = await fs.mkdtemp(path.join(os.tmpdir(), "proxy-upload-"));
  try {
    assert.equal((await client.callTool("test", {})).value, 7);
    assert.equal(registrations, 1);
    assert.equal((await new StoreClient(url, 5000, headers).uploadTargets()).stores[0].id, "one");
    assert.deepEqual(Array.from((await new DataClient(1, 100, headers).fetch(url, { path: "/generated", names: ["/x"] })).bytes), [1, 2, 3]);
    const file = path.join(scratch, "fixture.wpilog"); await fs.writeFile(file, "generated");
    assert.deepEqual(await uploadLog(url, file, "one", () => {}, undefined, headers), result);
    for (const route of ["/mcp", "/pit-credential", "/store", "/data/entries", "/store/import", "/store/import/one"]) assert.ok(seen.includes(route), route);
  } finally { await client.dispose(); await new Promise<void>(resolve => server.close(() => resolve())); await fs.rm(scratch, { recursive: true }); }
});
