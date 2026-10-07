import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { createHash } from "node:crypto";
import { AddressInfo } from "node:net";
import { uploadLog } from "../explorer/upload";
import { StoreClient } from "../explorer/storeClient";

test("upload streams exact bytes and source hash, selects a store, and polls once without resubmitting", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "wpilog-upload-"));
  const file = path.join(directory, "synthetic café.wpilog"), bytes = Buffer.alloc(200000);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  await fs.writeFile(file, bytes);
  let posts = 0, polls = 0; const result = { files: [], same_robots: [] };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url!, "http://127.0.0.1");
    if (url.pathname === "/store") { res.end(JSON.stringify({ stores: [{ id: "a", mirror: false }, { id: "b", mirror: true }], unreadable: [{ path: "broken", reason: "not JSON" }] })); return; }
    if (req.method === "POST") {
      posts++; assert.equal(url.pathname, "/store/import"); assert.equal(url.searchParams.get("store"), "a");
      assert.equal(url.searchParams.get("filename"), path.basename(file));
      assert.equal(req.headers["content-type"], "application/octet-stream"); assert.equal(Number(req.headers["content-length"]), bytes.length);
      assert.equal(req.headers["x-wpilog-sha256"], createHash("sha256").update(bytes).digest("hex"));
      const chunks: Buffer[] = []; for await (const chunk of req) chunks.push(chunk); assert.deepEqual(Buffer.concat(chunks), bytes);
      res.writeHead(202); res.end(JSON.stringify({ job_id: "one", url: "/store/import/one" }));
    } else { polls++; assert.equal(url.pathname, "/store/import/one"); res.end(JSON.stringify({ state: "done", result })); }
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    const endpoint = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`;
    assert.deepEqual(await new StoreClient(endpoint).uploadTargets(), { stores: [{ id: "a", mirror: false }], unreadable: [{ path: "broken", reason: "not JSON" }] });
    assert.deepEqual(await uploadLog(endpoint, file, "a", () => {}), result);
    assert.deepEqual(await fs.readFile(file), bytes); assert.equal(posts, 1); assert.equal(polls, 1);
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); await fs.rm(directory, { recursive: true }); }
});

test("upload preserves refusal text and never retries an uncertain POST or follows a foreign polling URL", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "wpilog-upload-")), file = path.join(directory, "test.wpilog");
  await fs.writeFile(file, "generated");
  let mode = 0, posts = 0;
  const server = http.createServer((req, res) => {
    posts++; req.resume();
    if (mode === 0) { res.writeHead(413); res.end(JSON.stringify({ error: "over limit", hint: "windowed mapping is later" })); }
    else if (mode === 1) req.socket.destroy();
    else { res.writeHead(202); res.end(JSON.stringify({ job_id: "one", url: "http://untrusted.invalid/store/import/one" })); }
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    const endpoint = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`;
    await assert.rejects(uploadLog(endpoint, file, "a", () => {}), /over limit\nwindowed mapping is later/);
    mode = 1; await assert.rejects(uploadLog(endpoint, file, "a", () => {}));
    mode = 2; await assert.rejects(uploadLog(endpoint, file, "a", () => {}), /invalid import job URL/);
    assert.equal(posts, 3);
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); await fs.rm(directory, { recursive: true }); }
});
