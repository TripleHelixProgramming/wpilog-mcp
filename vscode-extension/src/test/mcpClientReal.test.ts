import { LocalIngestionClient } from "../explorer/localIngestion";
import { folderSynchronization } from "../explorer/logsTree";
import { StoreClient } from "../explorer/storeClient";
import { runImport } from "../explorer/importJobs";
// The explorer's client against the real server JAR over its HTTP transport: npm test
//
// Runs when the current project's server JAR is under ../build/libs (CI builds it before the
// extension's tests) and `java` is on the
// path; otherwise it is skipped, so `npm test` still needs nothing but the extension. It drives
// the transport as the explorer will: a session opened by initialize, the session header on each
// call, a result and an error result, and the session deleted on dispose, so the server's own
// health check counts no session afterwards.
import { test } from "node:test";
import * as assert from "node:assert/strict";
import { spawn, spawnSync } from "child_process";
import * as fs from "fs";
import * as http from "http";
import * as net from "net";
import * as os from "os";
import * as path from "path";
import { McpClient, ToolError } from "../mcpClient";

const expectedVersion = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "..", "package.json"), "utf8")).version as string;
function findJar(): string | undefined {
  const libs = path.join(__dirname, "..", "..", "..", "build", "libs");
  const current = path.join(libs, `wpilog-mcp-${expectedVersion}-all.jar`);
  return fs.existsSync(current) ? current : undefined;
}

function javaAvailable(): boolean {
  return spawnSync("java", ["-version"], { stdio: "ignore", windowsHide: true }).status === 0;
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const port = (server.address() as net.AddressInfo).port;
      server.close(() => resolve(port));
    });
  });
}

function health(port: number): Promise<{ status?: string; sessions?: number } | undefined> {
  return new Promise((resolve) => {
    const request = http.get(`http://127.0.0.1:${port}/health`, { timeout: 1000 }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk: string) => (body += chunk));
      response.on("end", () => {
        try {
          resolve(JSON.parse(body));
        } catch {
          resolve(undefined);
        }
      });
    });
    request.on("timeout", () => request.destroy());
    request.on("error", () => resolve(undefined));
  });
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
// The server reports real paths as the OS resolves them (Java's toRealPath). Node's own
// realpathSync keeps a Windows short-name alias such as RUNNER~1, so compare what the OS says,
// and compare without case, since Windows paths differ only in the case they were typed with.
const canonical = (p: string) => fs.realpathSync.native(p).toLowerCase();

const jar = findJar();
const runnable = jar !== undefined && javaAvailable();

test("the client works the real server: a session, a listing, an error result, and the session's end", { skip: !runnable && "no server JAR or no java" }, async () => {
  const logDir = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-explorer-"));
  const leaseDir = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-lease-"));
  const port = await freePort();
  const server = spawn("java", ["-Xmx256m", `-Duser.home=${logDir}`, "-jar", jar!, "--http", "--port", String(port), "-logdir", logDir], {
    cwd: logDir,
    stdio: ["ignore", "ignore", "pipe"],
    windowsHide: true,
    env: { ...process.env, TBA_API_KEY: "", WPILOG_DISK_CACHE_DIR: path.join(logDir, "cache") },
  });
  let stderr = "";
  server.stderr?.setEncoding("utf8");
  server.stderr?.on("data", (chunk: string) => (stderr += chunk));
  try {
    let up = false;
    for (let i = 0; i < 300 && !up; i++) {
      if (server.exitCode !== null) break;
      up = (await health(port))?.status === "ok";
      if (!up) await sleep(200);
    }
    assert.ok(up, `the server did not come up on ${port}:\n${stderr}`);

    const client = new McpClient(`http://127.0.0.1:${port}/mcp`, "0.0.0-test");
    assert.equal((await client.callTool("health_check")).server_version, expectedVersion, "Integration tests must exercise the current project artifact");
    const listing = await client.callTool("list_available_logs", { limit: 5 });
    assert.ok(["ok", "no_match"].includes(listing.status as string), JSON.stringify(listing));
    const directories = listing.log_directory_paths as string[];
    assert.equal(directories.length, 1);
    assert.equal(canonical(directories[0]), canonical(logDir));
    assert.equal((await health(port))?.sessions, 1, "the explorer holds one session");

    await assert.rejects(client.callTool("list_entries", { path: path.join(logDir, "missing.wpilog") }), (error: unknown) => {
      assert.ok(error instanceof ToolError);
      assert.equal(error.result?.status, "error");
      assert.ok(error.message.length > 0);
      return true;
    });
    await assert.rejects(client.callTool("no_such_tool"), ToolError);

    const key = "synthetic-extension-key";
    const holder = new McpClient(client.endpoint, "test", async () => ({
      directories: { paths: [{ path: leaseDir, team: 9999 }], team: null }, key,
    }));
    await holder.refreshRegistration();
    await assert.rejects(client.callTool("list_available_logs"), (error: unknown) => {
      assert.ok(error instanceof ToolError); assert.equal(error.result?.code, "SOURCE_SELECTION_REQUIRED"); return true;
    });
    const choices = (await client.callTool("list_source_scopes")).source_scopes as { scope_id: string; path: string }[];
    assert.equal(choices.length, 2);
    const selected = choices.find(scope => canonical(scope.path) === canonical(leaseDir))!;
    const withLease = await client.callTool("list_available_logs", { scope_id: selected.scope_id });
    const origins = withLease.log_directories as { path: string; origin: string; team: number | null }[];
    assert.equal(origins.length, 1);
    const leased = origins.find(dir => dir.origin === "leased")!;
    assert.equal(canonical(leased.path), canonical(leaseDir));
    assert.equal(leased.team, 9999);
    // The local picker sees leased stores too; the network door still exports permanent roots only.
    for (const store of [logDir, leaseDir]) await runImport(client.endpoint, { store, paths: [], move: false, stated_robot: null }, () => {});
    const targets = await new StoreClient(client.endpoint).targets();
    assert.deepEqual(targets.stores.map(store => canonical(store.path)).sort(), [canonical(logDir), canonical(leaseDir)].sort());
    assert.match((await client.callTool("get_server_guide")).server_location as string, /serves its configured files and mirrors/);

    assert.ok(!JSON.stringify(withLease).includes(key));
    // No logs are present, so availability can be observed without calling the live TBA API.
    assert.equal((withLease.tba_enrichment as { available: boolean }).available, true);
    assert.equal((await client.callTool("health_check")).tba_available, true);
    await holder.dispose();
    assert.equal((await client.callTool("health_check")).tba_available, false);
    assert.equal(((await client.callTool("list_available_logs")).tba_enrichment as { available: boolean }).available, false);
    assert.equal(((await client.callTool("list_available_logs")).log_directories as unknown[]).length, 1);
    await assert.rejects(client.callTool("list_entries", { path: path.join(leaseDir, "missing.wpilog") }), /outside|not allowed|Access denied/);
    assert.ok(!stderr.includes(key));
    await client.dispose();
    assert.equal((await health(port))?.sessions, 0, "the session is deleted, so an idle server may exit");
  } finally {
    server.kill();
    await new Promise((resolve) => server.once("exit", resolve));
    fs.rmSync(leaseDir, { recursive: true, force: true });
    fs.rmSync(logDir, { recursive: true, force: true });
  }
});


test("real local-ingestion status and receipts populate Folder synchronization", { skip: !runnable && "no server JAR or no java", timeout: 30000 }, async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-ingestion-tree-"));
  const source = path.join(root, "source"), store = path.join(root, "store");
  fs.mkdirSync(source); fs.mkdirSync(store);
  const file = path.join(source, "synthetic.wpilog");
  fs.writeFileSync(file, "Synthetic invalid header; never a robot log");
  const port = await freePort(), config = path.join(root, "servers.json");
  fs.writeFileSync(config, JSON.stringify({ servers: { fixture: { transport: "http", port,
    logdir: [source, store], experimental_data_store: true } } }));
  const server = spawn("java", ["-Xmx256m", `-Duser.home=${root}`, "-jar", jar!, "run", "fixture", "--config", config], {
    cwd: root, stdio: ["ignore", "ignore", "pipe"], windowsHide: true,
    env: { ...process.env, TBA_API_KEY: "", WPILOG_DISK_CACHE_DIR: path.join(root, "cache") },
  });
  let stderr = ""; server.stderr?.on("data", chunk => stderr += chunk);
  const client = new McpClient(`http://127.0.0.1:${port}/mcp`, "ingestion-contract-test");
  try {
    for (let n = 0; n < 100 && (await health(port))?.status !== "ok" && server.exitCode === null; n++) await sleep(100);
    assert.equal((await health(port))?.status, "ok", stderr);
    assert.equal((await client.callTool("health_check")).server_version, expectedVersion);
    const control = new LocalIngestionClient(client), initial = await control.status();
    assert.equal(folderSynchronization(initial), undefined);
    await control.save({ revision: initial.policy!.revision, enabled: true, store, store_id: null,
      sources: [{ path: source, mode: "copy", enabled: true, completed_files: false, stated_robot: null }] });
    await control.scan();
    let page = await control.files();
    for (let n = 0; n < 100 && !page.files.length; n++) { await sleep(50); page = await control.files(); }
    assert.equal(page.total, 1, JSON.stringify(page)); assert.equal(page.files.length, 1);
    const status = await control.status(), group = folderSynchronization(status, page)!;
    assert.ok(group && "children" in group, "Real status.policy and page.files must build the group");
    assert.equal(group.tooltip, `${status.policy!.store}\nEnabled`);
    assert.deepEqual(group.children.map(node => node.label), [`synthetic.wpilog · ${page.files[0].state}`]);
    assert.ok(group.children[0].kind === "note");
    assert.equal(group.children[0].tooltip, [page.files[0].path, page.files[0].reason].filter(Boolean).join("\n"));
    assert.equal(canonical(page.files[0].path), canonical(file));
  } finally {
    await client.dispose();
    if (server.exitCode === null) { server.kill(); await new Promise(resolve => server.once("exit", resolve)); }
    fs.rmSync(root, { recursive: true, force: true });
  }
});
