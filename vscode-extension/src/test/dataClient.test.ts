// Tests for the data client and its requests (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "http";
import { AddressInfo } from "net";
import { DataClient, DataError, TooLargeError } from "../dataClient";
import { classifyStatus, dataEndpointOf, dataQuery, dataUrl, errorMessage } from "../explorer/dataRequest";

// ---- the requests ----

test("the data endpoint is beside the MCP endpoint, and a request's query is canonical", () => {
  assert.equal(dataEndpointOf("http://127.0.0.1:41234/mcp"), "http://127.0.0.1:41234/data/entries");
  assert.equal(dataEndpointOf("http://pit:2363/mcp?x=1#f"), "http://pit:2363/data/entries");
  const query = dataQuery({ path: "/logs/a b.wpilog", names: ["/A/B", "/C.x"], startTime: 20, endTime: 40.5, maxPoints: 1234.7 });
  assert.equal(query, "path=%2Flogs%2Fa+b.wpilog&names=%2FA%2FB%2C%2FC.x&start_time=20&end_time=40.5&max_points=1234");
  assert.equal(dataQuery({ path: "/l", names: ["/x"] }), "path=%2Fl&names=%2Fx", "nothing optional is sent when unset");
  assert.equal(dataQuery({ path: "/l", names: ["/x"], format: "csv" }), "path=%2Fl&names=%2Fx&format=csv");
  assert.equal(dataQuery({ path: "/l", names: ["/x"], maxPoints: 0 }), "path=%2Fl&names=%2Fx&max_points=1", "at least one bucket");
  assert.equal(dataUrl("http://127.0.0.1:1/mcp", { path: "/l", names: ["/x"] }), "http://127.0.0.1:1/data/entries?path=%2Fl&names=%2Fx");
});

test("a status is data, unchanged, too large, or an error, and an error body's words are kept", () => {
  assert.deepEqual(classifyStatus(200), { kind: "data" });
  assert.deepEqual(classifyStatus(304), { kind: "unchanged" });
  assert.deepEqual(classifyStatus(413), { kind: "too_large" });
  assert.deepEqual(classifyStatus(404), { kind: "error" });
  assert.equal(errorMessage(404, '{"error":"Entry not found: /x","hint":"list_entries lists them"}'),
    "Entry not found: /x (list_entries lists them)");
  assert.equal(errorMessage(403, '{"error":"Forbidden: invalid origin"}'), "Forbidden: invalid origin");
  assert.equal(errorMessage(502, "<html>bad gateway</html>"), "The server answered HTTP 502: <html>bad gateway</html>");
  assert.equal(errorMessage(500, ""), "The server answered HTTP 500");
});

// ---- the client, against a server that behaves as the endpoint does ----

interface Fake {
  mcpUrl: string;
  requests: { query: string; ifNoneMatch?: string }[];
  etag: string;
  body: Uint8Array;
  close(): Promise<void>;
}

function fakeEndpoint(): Promise<Fake> {
  const fake: Partial<Fake> = { requests: [], etag: '"v1"', body: new Uint8Array([0xff, 0xff, 0xff, 0xff, 0, 0, 0, 0]) };
  const server = http.createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    const ifNoneMatch = request.headers["if-none-match"];
    fake.requests!.push({ query: url.search.slice(1), ifNoneMatch: typeof ifNoneMatch === "string" ? ifNoneMatch : undefined });
    if (url.pathname !== "/data/entries") {
      response.writeHead(404).end();
      return;
    }
    const names = url.searchParams.get("names") ?? "";
    if (names === "/Missing") {
      response.writeHead(404, { "Content-Type": "application/json" }).end(JSON.stringify({ error: "Entry not found: /Missing", hint: "list_entries lists the log's entries" }));
      return;
    }
    if (names === "/Huge" && !url.searchParams.has("max_points")) {
      response.writeHead(413, { "Content-Type": "application/json" }).end(JSON.stringify({ error: "The response would be about 900000000 bytes (50000000 rows), over the cap of 536870912", rows: 50000000, bytes: 900000000, hint: "pass max_points" }));
      return;
    }
    if (ifNoneMatch === fake.etag) {
      response.writeHead(304, { ETag: fake.etag! }).end();
      return;
    }
    response.writeHead(200, { "Content-Type": "application/vnd.apache.arrow.stream", ETag: fake.etag! });
    response.end(Buffer.from(fake.body!));
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      fake.mcpUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`;
      fake.close = () => new Promise((done) => server.close(() => done()));
      resolve(fake as Fake);
    });
  });
}

test("the bytes come back as sent; the same request again sends If-None-Match and is answered from memory", async () => {
  const fake = await fakeEndpoint();
  try {
    const client = new DataClient();
    const request = { path: "/l.wpilog", names: ["/A"], startTime: 1, endTime: 2 };
    const first = await client.fetch(fake.mcpUrl, request);
    assert.deepEqual([...first.bytes], [...fake.body]);
    assert.equal(first.fromCache, false);
    assert.equal(first.etag, '"v1"');
    const again = await client.fetch(fake.mcpUrl, { ...request });
    assert.equal(again.fromCache, true, "a 304: the remembered bytes");
    assert.deepEqual([...again.bytes], [...fake.body]);
    assert.equal(fake.requests.length, 2);
    assert.equal(fake.requests[0].ifNoneMatch, undefined);
    assert.equal(fake.requests[1].ifNoneMatch, '"v1"');
    assert.equal(fake.requests[0].query, fake.requests[1].query, "one canonical query");
    // The file changed: a new ETag, new bytes
    fake.etag = '"v2"';
    fake.body = new Uint8Array([1, 2, 3]);
    const changed = await client.fetch(fake.mcpUrl, request);
    assert.equal(changed.fromCache, false);
    assert.deepEqual([...changed.bytes], [1, 2, 3]);
    assert.equal(fake.requests[2].ifNoneMatch, '"v1"');
    client.clear();
    assert.equal(client.size, 0);
  } finally {
    await fake.close();
  }
});

test("a refusal carries the server's words: a missing entry is an error, over the cap is too large with the counts", async () => {
  const fake = await fakeEndpoint();
  try {
    const client = new DataClient();
    await assert.rejects(client.fetch(fake.mcpUrl, { path: "/l", names: ["/Missing"] }), (error: unknown) => {
      assert.ok(error instanceof DataError);
      assert.equal(error.status, 404);
      assert.equal(error.message, "Entry not found: /Missing (list_entries lists the log's entries)");
      return true;
    });
    await assert.rejects(client.fetch(fake.mcpUrl, { path: "/l", names: ["/Huge"] }), (error: unknown) => {
      assert.ok(error instanceof TooLargeError);
      assert.equal(error.rows, 50000000);
      assert.equal(error.bytes, 900000000);
      assert.ok(error.message.includes("max_points"));
      return true;
    });
    const bucketed = await client.fetch(fake.mcpUrl, { path: "/l", names: ["/Huge"], maxPoints: 2000 });
    assert.equal(bucketed.fromCache, false);
  } finally {
    await fake.close();
  }
});

test("the memory is bounded by entries and by bytes, oldest out first", async () => {
  const fake = await fakeEndpoint();
  try {
    const small = new DataClient(2, 1000);
    await small.fetch(fake.mcpUrl, { path: "/l", names: ["/A"] });
    await small.fetch(fake.mcpUrl, { path: "/l", names: ["/B"] });
    await small.fetch(fake.mcpUrl, { path: "/l", names: ["/C"] });
    assert.equal(small.size, 2);
    const a = await small.fetch(fake.mcpUrl, { path: "/l", names: ["/A"] });
    assert.equal(a.fromCache, false, "the oldest was forgotten");
    const c = await small.fetch(fake.mcpUrl, { path: "/l", names: ["/C"] });
    assert.equal(c.fromCache, true);
    const tiny = new DataClient(10, 4);
    const r = await tiny.fetch(fake.mcpUrl, { path: "/l", names: ["/A"] });
    assert.equal(r.bytes.byteLength, 8);
    assert.equal(tiny.size, 0, "a response over the byte limit is not remembered");
  } finally {
    await fake.close();
  }
});

test("a server that cannot be reached fails with a message that says so", async () => {
  const fake = await fakeEndpoint();
  await fake.close();
  const client = new DataClient();
  await assert.rejects(client.fetch(fake.mcpUrl, { path: "/l", names: ["/A"] }), (error: unknown) => {
    assert.ok(error instanceof DataError);
    assert.ok(error.message.includes("could not be reached"), error.message);
    return true;
  });
});
