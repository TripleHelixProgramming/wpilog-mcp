// Tests for the one server's decisions (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import {
  Backoff,
  DAEMON_NAME,
  MAX_START_ATTEMPTS,
  buildDaemonConfig,
  classifyHealth,
  daemonIsCurrent,
  entryUsesConnect,
  healthUrl,
  keepPort,
  serverLogPath,
  serverUrl,
  startCommand,
  stopCommand,
  unionLogDirectories,
} from "../oneServer";
import { buildServerEntry, mergeServerEntry } from "../mcpJson";

test("the daemon's configuration is one http server on the loopback address, in the standalone's format", () => {
  const config = JSON.parse(buildDaemonConfig({
    port: 41234,
    logDirs: ["/logs", "/archive"],
    teamNumber: 2363,
    tbaKey: "the-key",
    cacheDir: "/storage/cache",
    idleExitMinutes: 30,
  }));
  assert.deepEqual(config, {
    servers: {
      vscode: {
        transport: "http",
        port: 41234,
        logdir: ["/logs", "/archive"],
        team: 2363,
        tba_key: "the-key",
        diskcachedir: "/storage/cache",
        idle_exit_minutes: 30,
      },
    },
  });
  assert.equal(DAEMON_NAME, "vscode");
});

test("the configuration leaves out what is not set, and an idle exit of zero means never", () => {
  const config = JSON.parse(buildDaemonConfig({ port: 1, logDirs: [], teamNumber: 0, idleExitMinutes: 0 }));
  assert.deepEqual(config, { servers: { vscode: { transport: "http", port: 1 } } });
  const text = buildDaemonConfig({ port: 1, logDirs: ["C:\\logs"], teamNumber: 0, tbaKey: "", idleExitMinutes: 5 });
  assert.ok(text.endsWith("\n"));
  assert.equal(JSON.parse(text).servers.vscode.tba_key, undefined);
  assert.deepEqual(JSON.parse(text).servers.vscode.logdir, ["C:\\logs"]);
});

test("start and stop run the server's own verbs on the daemon, with the heap first", () => {
  assert.deepEqual(startCommand("4g", "/s/wpilog-mcp-all.jar", "/s/vscode.json"),
    ["-Xmx4g", "-jar", "/s/wpilog-mcp-all.jar", "start", "vscode", "--config", "/s/vscode.json"]);
  assert.deepEqual(stopCommand("2g", "/s/wpilog-mcp-all.jar"),
    ["-Xmx2g", "-jar", "/s/wpilog-mcp-all.jar", "stop", "vscode"]);
});

test("the URLs VS Code's agents and the health probe use are on the loopback address", () => {
  assert.equal(serverUrl(41234), "http://127.0.0.1:41234/mcp");
  assert.equal(healthUrl(41234), "http://127.0.0.1:41234/health");
});

test("the health verdict: nobody, ours with its version, or a stranger", () => {
  assert.deepEqual(classifyHealth(undefined, undefined, { code: "ECONNREFUSED" }), { kind: "free" });
  assert.deepEqual(classifyHealth(undefined, undefined, { code: "ETIMEDOUT" }), { kind: "stranger" });
  assert.deepEqual(classifyHealth(undefined, undefined, {}), { kind: "stranger" });
  assert.deepEqual(classifyHealth(200, '{"status":"ok","sessions":2,"version":"0.9.1","pid":77}'),
    { kind: "ours", version: "0.9.1", pid: 77 });
  // A server from before /health carried a version: ours, version unknown
  assert.deepEqual(classifyHealth(200, '{"status":"ok","sessions":0}'),
    { kind: "ours", version: undefined, pid: undefined });
  assert.deepEqual(classifyHealth(404, "not here"), { kind: "stranger" });
  assert.deepEqual(classifyHealth(200, "<html>"), { kind: "stranger" });
  assert.deepEqual(classifyHealth(200, '{"status":"ok"}'), { kind: "stranger" });
  assert.deepEqual(classifyHealth(200, '["ok"]'), { kind: "stranger" });
  assert.deepEqual(classifyHealth(200, undefined), { kind: "stranger" });
});

test("a daemon is current only when it is ours and of the extension's version", () => {
  assert.ok(daemonIsCurrent({ kind: "ours", version: "0.9.1" }, "0.9.1"));
  assert.ok(!daemonIsCurrent({ kind: "ours", version: "0.9.0" }, "0.9.1"));
  assert.ok(!daemonIsCurrent({ kind: "ours" }, "0.9.1"), "an older daemon reports no version");
  assert.ok(!daemonIsCurrent({ kind: "stranger" }, "0.9.1"));
  assert.ok(!daemonIsCurrent({ kind: "free" }, "0.9.1"));
});

test("the port is chosen once and kept while it is free or ours; a stranger on it means another", () => {
  assert.ok(keepPort(41234, { kind: "free" }));
  assert.ok(keepPort(41234, { kind: "ours", version: "0.9.1" }));
  assert.ok(!keepPort(41234, { kind: "stranger" }));
  assert.ok(!keepPort(undefined, { kind: "free" }), "no port chosen yet");
});

test("the backoff doubles from two seconds to a minute, counts attempts, and starts over on success", () => {
  const backoff = new Backoff();
  assert.deepEqual([backoff.next(), backoff.next(), backoff.next(), backoff.next(), backoff.next(), backoff.next()],
    [2000, 4000, 8000, 16000, 32000, 60000]);
  assert.equal(backoff.next(), 60000, "capped");
  assert.equal(backoff.attempts, 7);
  backoff.reset();
  assert.equal(backoff.attempts, 0);
  assert.equal(backoff.next(), 2000);
  assert.ok(MAX_START_ATTEMPTS >= 3 && MAX_START_ATTEMPTS <= 10, "a few tries, not forever");
});

test("the daemon's directories are every list's, each once, two names for one folder counting once", () => {
  assert.deepEqual(
    unionLogDirectories([["/a", "/b"], ["/b", "/c"], []], path.posix),
    ["/a", "/b", "/c"]);
  assert.deepEqual(
    unionLogDirectories([["C:\\robot\\logs"], ["c:/robot/logs/", "D:\\archive"]], path.win32),
    ["C:\\robot\\logs", "D:\\archive"]);
  assert.deepEqual(unionLogDirectories([]), []);
});

test("an entry that runs the bridge is told from one that runs its own server", () => {
  const bridge = mergeServerEntry(undefined, buildServerEntry("java", "a.jar", "4g", "/s/vscode.json"));
  assert.ok(bridge.ok && entryUsesConnect(bridge.text));
  const own = mergeServerEntry(undefined, {
    command: "java",
    args: ["-Xmx4g", "-jar", "a.jar", "start", "default", "--config", "/s/projects/x.json"],
  });
  assert.ok(own.ok && !entryUsesConnect(own.text));
  assert.ok(!entryUsesConnect(undefined));
  assert.ok(!entryUsesConnect("not json"));
  assert.ok(!entryUsesConnect('{"mcpServers":{"other":{"command":"x","args":["connect","vscode"]}}}'),
    "another server's entry is not this one's");
});

test("the server's log is where the server writes it, under the home folder", () => {
  assert.equal(serverLogPath("/home/me", path.posix), "/home/me/.wpilog-mcp/logs/vscode.log");
  assert.equal(serverLogPath("C:\\Users\\me", path.win32), "C:\\Users\\me\\.wpilog-mcp\\logs\\vscode.log");
});
