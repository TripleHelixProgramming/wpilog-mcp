/** Policy choices are independent of VS Code and of the server's import implementation. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import * as http from "node:http";
import { McpClient, LocalControlError } from "../mcpClient";
import { IngestionPolicy, IngestionSource, LocalIngestionClient, ingestionPolicyError, ingestionReview, sourceCandidates, temporaryDirectoryRoots } from "../explorer/localIngestion";
import { SESSION_HEADER } from "../explorer/mcpProtocol";

const source = (folder: string, mode: "copy" | "move" = "copy"): IngestionSource =>
  ({ path: folder, mode, enabled: true, completed_files: mode === "move", stated_robot: null });

test("first setup preselects server and User roots, never projects, leased scratch folders or temporary roots", () => {
  const result = sourceCandidates({ log_directories: [
    { path: "/server", origin: "configured", team: null },
    { path: "/user", origin: "leased", team: null },
    { path: "/project/logs", origin: "leased", team: null },
    { path: "/tmp/configured", origin: "configured", team: null },
    { path: "/store", origin: "leased", team: null },
    { path: "/mirror", origin: "configured", team: null },
  ], stores: [{ path: "/mirror", mirror: true }] }, ["/user"], ["/tmp"], "/store", null, path.posix);
  assert.deepEqual(result.map(item => [item.path, item.origin, item.selected]), [
    ["/server", "configured", true], ["/user", "user", true], ["/project/logs", "project or session", false], ["/tmp/configured", "temporary", false],
  ]);
});

test("an existing policy selects only approved sources; new configured folders need approval", () => {
  const policy: IngestionPolicy = { revision: 7, enabled: true, store: "/store", store_id: "id", sources: [source("/old"), { ...source("/disabled"), enabled: false }] };
  const items = sourceCandidates({ log_directories: [{ path: "/new", origin: "configured", team: null }] }, ["/new-user"], [], "/store", policy, path.posix);
  assert.deepEqual(items.map(item => [item.path, item.selected]), [["/new", false], ["/new-user", false], ["/old", true], ["/disabled", false]]);
});

test("Windows aliases compare by path rules and source/destination overlaps are refused", () => {
  const items = sourceCandidates({ log_directories: ["C:\\LOGS"] }, ["c:/logs/"], [], "C:\\store", null, path.win32);
  assert.equal(items.length, 1); assert.equal(items[0].selected, true);
  assert.match(ingestionPolicyError("C:\\store", [source("c:/store/logs")], {}, path.win32)!, /cannot contain/);
  assert.match(ingestionPolicyError("C:\\store", [source("C:\\logs"), source("c:/logs/nested")], {}, path.win32)!, /overlap/);
  assert.equal(ingestionPolicyError("/source/store", [source("/source")], {}, path.posix), undefined, "the server excludes the destination subtree");
  assert.match(ingestionPolicyError("/store", [{ ...source("/source", "move"), completed_files: false }], {}, path.posix)!, /finished files/);
  assert.match(ingestionPolicyError("/store", [source("/mirror/child")], { stores: [{ path: "/mirror", mirror: true }] }, path.posix)!, /cannot be/);
});

test("final review names ongoing removal, lifetime and duplicate retention", () => {
  const text = ingestionReview({ revision: 0, enabled: true, store: "/destination", store_id: null,
    sources: [source("/original"), source("/finished", "move")] });
  assert.match(text, /Copy; keep originals: \/original/); assert.match(text, /Move finished files: \/finished/);
  assert.match(text, /after VS Code closes/); assert.match(text, /duplicate source files stay/);
});

async function controlFixture(action: (client: McpClient, state: { requests: { route: string; method: string; session?: string; body: any }[]; failSave: boolean }) => Promise<void>): Promise<void> {
  const state = { requests: [] as { route: string; method: string; session?: string; body: any }[], failSave: false };
  const server = http.createServer(async (request, response) => {
    let text = ""; for await (const chunk of request) text += chunk;
    const body = text ? JSON.parse(text) : undefined;
    const session = request.headers[SESSION_HEADER.toLowerCase()] as string | undefined;
    state.requests.push({ route: request.url!, method: request.method!, session, body });
    response.setHeader("Content-Type", "application/json");
    if (body?.method === "initialize") { response.setHeader(SESSION_HEADER, "fixture-session"); response.end(JSON.stringify({ result: {} })); return; }
    if (request.method === "DELETE") { response.end("{}"); return; }
    if (!session) { response.writeHead(400); response.end('{"error":"session required"}'); return; }
    if (request.url === "/store/local-ingestion" && request.method === "PUT") {
      if (state.failSave) { response.writeHead(409); response.end('{"code":"CONFLICT","error":"Policy revision changed"}'); return; }
      response.end(JSON.stringify({ policy: { ...body, revision: body.revision + 1 }, sources: [] })); return;
    }
    if (request.url?.startsWith("/store/local-ingestion")) { response.end('{"policy":null,"sources":[]}'); return; }
    response.end("{}");
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const endpoint = `http://127.0.0.1:${(server.address() as { port: number }).port}/mcp`;
  const client = new McpClient(endpoint, "fixture", async () => ({ directories: { paths: [{ path: "/ordinary", team: null }], team: null }, key: null }));
  try { await action(client, state); }
  finally { await client.dispose(); server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())); }
}

test("the setup save uses the existing session, admits reviewed roots before PUT and restores the lease", async () => {
  await controlFixture(async (client, state) => {
    const control = new LocalIngestionClient(client);
    await control.status();
    const policy: IngestionPolicy = { revision: 0, enabled: true, store: "/chosen", store_id: null, sources: [source("/external")] };
    assert.equal((await control.save(policy)).policy?.revision, 1);
    const save = state.requests.findIndex(request => request.method === "PUT");
    const before = state.requests.slice(0, save).filter(request => request.route === "/directories").at(-1)!;
    const after = state.requests.slice(save + 1).find(request => request.route === "/directories")!;
    assert.deepEqual(before.body.paths.map((entry: any) => entry.path), ["/ordinary", "/chosen", "/external"]);
    assert.deepEqual(after.body.paths.map((entry: any) => entry.path), ["/ordinary"]);
    assert.equal(state.requests.filter(request => request.body?.method === "initialize").length, 1);
    assert.ok(state.requests.filter(request => request.route.startsWith("/store/")).every(request => request.session === "fixture-session"));
    assert.deepEqual(state.requests[save].body, policy);
  });
});

test("a stale policy fails without retry, restores temporary access and keeps later controls usable", async () => {
  await controlFixture(async (client, state) => {
    state.failSave = true;
    const control = new LocalIngestionClient(client);
    await assert.rejects(control.save({ revision: 3, enabled: true, store: "/chosen", store_id: null, sources: [source("/external")] }),
      error => error instanceof LocalControlError && error.status === 409 && error.message === "Policy revision changed");
    assert.equal(state.requests.filter(request => request.method === "PUT").length, 1);
    assert.deepEqual(state.requests.filter(request => request.route === "/directories").at(-1)!.body.paths.map((entry: any) => entry.path), ["/ordinary"]);
    await control.status(); await control.scan(); await control.files(100, 25);
    assert.ok(state.requests.some(request => request.route === "/store/local-ingestion/files?offset=100&limit=25"));
  });
});


test("temporary source defaults include POSIX shared scratch roots as well as per-user TMPDIR", () => {
  assert.deepEqual(temporaryDirectoryRoots("darwin", "/private/user/T", {}), ["/private/user/T", "/tmp", "/var/tmp"]);
  assert.deepEqual(temporaryDirectoryRoots("win32", "C:\\Temp", { TMP: "D:\\Temp", TEMP: "C:\\Temp" }), ["C:\\Temp", "D:\\Temp"]);
});
