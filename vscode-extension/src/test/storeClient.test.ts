import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { StoreClient, rememberedPeers, syncSummary } from "../explorer/storeClient";
import { mirrorRequest } from "../explorer/pitServer";
import * as path from "node:path";

async function server(handler: http.RequestListener) {
  const server = http.createServer(handler); await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address() as import("net").AddressInfo;
  return { server, url: `http://127.0.0.1:${address.port}/mcp`, close: () => new Promise<void>(resolve => server.close(() => resolve())) };
}
test("mirror requests use local controls and exact configuration and session IDs", async () => {
  const seen: { route: string | undefined; method: string | undefined; body: any }[] = [];
  const host = await server(async (request, response) => {
    const chunks: Buffer[] = []; for await (const chunk of request) chunks.push(chunk);
    const text = Buffer.concat(chunks).toString(); seen.push({ route: request.url, method: request.method, body: text ? JSON.parse(text) : null });
    response.end(JSON.stringify({ state: "synchronized", age_sec: 2 }));
  });
  try {
    const client = new StoreClient(host.url); const config = mirrorRequest("http://pit:2363", {}, "/storage", path.posix)!;
    assert.equal((await client.configure(config)).state, "synchronized"); await client.status(); await client.syncNow();
    await client.pin("boot-1", true); await client.pin("boot-1", false); await client.disable();
    assert.deepEqual(seen, [
      { route: "/store/mirror/configure", method: "POST", body: config }, { route: "/store/mirror", method: "GET", body: null },
      { route: "/store/mirror/sync", method: "POST", body: {} },
      { route: "/store/mirror/pin_session", method: "POST", body: { session_id: "boot-1" } },
      { route: "/store/mirror/unpin_session", method: "POST", body: { session_id: "boot-1" } },
      { route: "/store/mirror", method: "DELETE", body: null },
    ]);
  } finally { await host.close(); }
});
test("peer sync polls the accepted job once without resubmitting and reports all result counts", async () => {
  const result = { sessions_created: ["boot"], files_copied: [{ path: "/store/file", bytes: 2048 }], files_present: [{ path: "/store/other" }], conflicts: ["name"], refusals: [], stopped: [] };
  let posts = 0, polls = 0; const bodies: unknown[] = [];
  const host = await server(async (request, response) => {
    if (request.method === "POST") {
      posts++; const chunks: Buffer[] = []; for await (const chunk of request) chunks.push(chunk);
      bodies.push(JSON.parse(Buffer.concat(chunks).toString())); response.statusCode = 202; response.end(JSON.stringify({ job_id: "one", url: "/store/sync/one" }));
    } else if (request.url === "/store/sync") response.end(JSON.stringify({ stores: [{ path: "/store", id: "local", peers: ["http://laptop:2363"] }], unreadable: [] }));
    else { assert.equal(request.url, "/store/sync/one"); polls++; response.end(JSON.stringify({ job_id: "one", state: polls === 1 ? "running" : "done", result: polls === 1 ? null : result })); }
  });
  try {
    const client = new StoreClient(host.url); const target = (await client.targets()).stores[0];
    assert.deepEqual(rememberedPeers(target), ["http://laptop:2363"]);
    const sleeps: number[] = [], states: string[] = [];
    assert.deepEqual(await client.sync(target.path, rememberedPeers(target)[0], job => states.push(job.state), async ms => { sleeps.push(ms); }), result);
    assert.equal(posts, 1); assert.equal(polls, 2); assert.deepEqual(bodies, [{ store: "/store", url: "http://laptop:2363" }]);
    assert.deepEqual(states, ["running", "done"]); assert.deepEqual(sleeps, [500]);
    assert.equal(syncSummary(result), "1 sessions created, 1 files copied (2.0 KB), 1 present, 1 conflicts, 0 refusals, 0 interrupted");
  } finally { await host.close(); }
});
test("controls preserve refusal reasons, reject foreign poll URLs and bound a stalled response", async () => {
  let mode = "refuse", requests = 0;
  const host = await server((request, response) => {
    requests++;
    if (mode === "refuse") { response.statusCode = 409; response.end('{"error":"mirror already running"}'); }
    else if (mode === "foreign") { response.statusCode = 202; response.end('{"job_id":"one","url":"http://other/store/sync/one"}'); }
    else { response.writeHead(200); response.write("{"); }
  });
  try {
    const client = new StoreClient(host.url, 100);
    await assert.rejects(client.syncNow(), /mirror already running/);
    mode = "foreign"; await assert.rejects(client.sync("/store", "http://peer", () => {}), /invalid sync job URL/); assert.equal(requests, 2);
    mode = "stall"; await assert.rejects(client.status(), /timed out/);
  } finally { host.server.closeAllConnections(); await host.close(); }
});
