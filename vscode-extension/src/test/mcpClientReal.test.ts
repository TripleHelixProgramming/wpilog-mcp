// The explorer's client against the real server JAR over its HTTP transport: npm test
//
// Runs when a server JAR is at hand (the bundled copy under server/, or the one the Gradle build
// leaves under ../build/libs, which CI builds before the extension's tests) and `java` is on the
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

function findJar(): string | undefined {
  const bundled = path.join(__dirname, "..", "..", "server", "wpilog-mcp-all.jar");
  const libs = path.join(__dirname, "..", "..", "..", "build", "libs");
  if (!fs.existsSync(libs)) return fs.existsSync(bundled) ? bundled : undefined;
  const jars = fs.readdirSync(libs).filter((name) => /^wpilog-mcp-.*-all\.jar$/.test(name)).sort();
  return jars.length > 0 ? path.join(libs, jars[jars.length - 1]) : fs.existsSync(bundled) ? bundled : undefined;
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

const jar = findJar();
const runnable = jar !== undefined && javaAvailable();

test("the client works the real server: a session, a listing, an error result, and the session's end", { skip: !runnable && "no server JAR or no java" }, async () => {
  const logDir = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-explorer-"));
  const port = await freePort();
  const server = spawn("java", ["-Xmx256m", "-jar", jar!, "--http", "--port", String(port), "-logdir", logDir], {
    stdio: ["ignore", "ignore", "pipe"],
    windowsHide: true,
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
    const listing = await client.callTool("list_available_logs", { limit: 5 });
    assert.ok(["ok", "no_match"].includes(listing.status as string), JSON.stringify(listing));
    const directories = listing.log_directory_paths as string[];
    assert.equal(directories.length, 1);
    assert.equal(fs.realpathSync(directories[0]).toLowerCase(), fs.realpathSync(logDir).toLowerCase());
    assert.equal((await health(port))?.sessions, 1, "the explorer holds one session");

    await assert.rejects(client.callTool("list_entries", { path: path.join(logDir, "missing.wpilog") }), (error: unknown) => {
      assert.ok(error instanceof ToolError);
      assert.equal(error.result?.status, "error");
      assert.ok(error.message.length > 0);
      return true;
    });
    await assert.rejects(client.callTool("no_such_tool"), ToolError);

    await client.dispose();
    assert.equal((await health(port))?.sessions, 0, "the session is deleted, so an idle server may exit");
  } finally {
    server.kill();
    await new Promise((resolve) => server.once("exit", resolve));
    fs.rmSync(logDir, { recursive: true, force: true });
  }
});
