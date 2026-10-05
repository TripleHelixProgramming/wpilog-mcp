// Tests for the server's decisions (no VS Code needed): npm test
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
  portInConfig,
  resolveServer,
  serverLogPath,
  serverUrl,
  startCommand,
  stopCommand,
  unionLogDirectories,
} from "../projectServers";
import { buildServerEntry, mergeServerEntry } from "../mcpJson";

test("the one daemon is named apart from a standalone install's servers", () => {
  assert.equal(DAEMON_NAME, "vscode-default");
  assert.match(DAEMON_NAME, /^vscode-/, "never the standalone install's default");
});

test("the server's directories are the user's and its projects', each project's resolved as its own settings say", () => {
  const user = { logDirectory: "/team/logs", additionalLogDirectories: ["/archive", "", 7], teamNumber: 2363 };
  const resolved = resolveServer(
    user,
    [
      // A project with no settings of its own: the user's directories, which are absolute, once
      { folderPath: "/th/Rebuilt", own: {} },
      // One with its own main directory and additional list: relative paths inside it
      { folderPath: "/th/Practice", own: { logDirectory: "/practice", additionalLogDirectories: ["logs", "/archive/"] } },
      // One with only a team number: the user's directories
      { folderPath: "/th/Other", own: { teamNumber: 9999 } },
    ],
    path.posix
  );
  assert.deepEqual(resolved.logDirs, ["/team/logs", "/archive", "/practice", "/th/Practice/logs"]);
  assert.equal(resolved.teamNumber, 9999, "the first team a project sets");
});

test("a project's own additional list replaces the user's, as a Workspace list does", () => {
  const user = { logDirectory: "/team/logs", additionalLogDirectories: ["/archive"] };
  const resolved = resolveServer(user, [{ folderPath: "/p", own: { additionalLogDirectories: [] } }], path.posix);
  assert.deepEqual(resolved.logDirs, ["/team/logs", "/archive"], "the user's own stay; the project adds none");
  const main = resolveServer(user, [{ folderPath: "/p", own: { logDirectory: "/mine" } }], path.posix);
  assert.deepEqual(main.logDirs, ["/team/logs", "/archive", "/mine"]);
  assert.equal(main.teamNumber, 0, "no team anywhere");
});

test("a relative directory in the User settings names nothing on its own, and a folder inside each project", () => {
  const user = { logDirectory: "logs" };
  assert.deepEqual(resolveServer(user, [], path.posix).logDirs, []);
  assert.deepEqual(resolveServer(user, [{ folderPath: "/p", own: {} }], path.posix).logDirs, ["/p/logs"],
    "the project resolves the user's relative main directory inside itself");
  assert.deepEqual(
    resolveServer({ additionalLogDirectories: ["sim"] }, [{ folderPath: "/p", own: {} }, { folderPath: "/q", own: {} }], path.posix).logDirs,
    ["/p/sim", "/q/sim"]);
});

test("a blank or whitespace project directory falls back to the user's, and ~ is the home folder", () => {
  const user = { logDirectory: "~/riologs", teamNumber: 2363 };
  const resolved = resolveServer(user, [{ folderPath: "/p", own: { logDirectory: "  " } }], path.posix);
  assert.equal(resolved.logDirs.length, 1);
  assert.ok(resolved.logDirs[0].endsWith("/riologs") && path.posix.isAbsolute(resolved.logDirs[0]));
  assert.equal(resolved.teamNumber, 2363, "the user's team when no project sets one");
});

test("on Windows, one folder under two names counts once, and the team is the user's when no project sets one", () => {
  const user = { logDirectory: "C:\\robot\\logs", additionalLogDirectories: ["D:\\archive"], teamNumber: 2363 };
  const resolved = resolveServer(user, [{ folderPath: "C:\\th\\Rebuilt", own: { additionalLogDirectories: ["c:/robot/logs/"] } }], path.win32);
  assert.deepEqual(resolved.logDirs, ["C:\\robot\\logs", "D:\\archive"]);
  assert.equal(resolved.teamNumber, 2363);
});

test("with nothing set and no project, the server lists nothing and has no team", () => {
  assert.deepEqual(resolveServer({}, [], path.posix), { logDirs: [], teamNumber: 0 });
  assert.deepEqual(resolveServer({ teamNumber: null as unknown as number, additionalLogDirectories: "not a list" }, [], path.posix),
    { logDirs: [], teamNumber: 0 });
});

test("a server's configuration is one http server under its daemon's name, in the standalone's format", () => {
  const config = JSON.parse(buildDaemonConfig({
    name: "vscode-team",
    port: 41234,
    logDirs: ["/logs", "/archive"],
    teamNumber: 2363,
    tbaKey: "the-key",
    cacheDir: "/storage/cache",
    idleExitMinutes: 30,
  }));
  assert.deepEqual(config, {
    servers: {
      "vscode-team": {
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
});

test("the configuration leaves out what is not set, and an idle exit of zero means never", () => {
  const config = JSON.parse(buildDaemonConfig({ name: "x", port: 1, logDirs: [], teamNumber: 0, idleExitMinutes: 0 }));
  assert.deepEqual(config, { servers: { x: { transport: "http", port: 1 } } });
  const text = buildDaemonConfig({ name: "x", port: 1, logDirs: ["C:\\logs"], teamNumber: 0, tbaKey: "", idleExitMinutes: 5 });
  assert.ok(text.endsWith("\n"));
  assert.equal(JSON.parse(text).servers.x.tba_key, undefined);
  assert.deepEqual(JSON.parse(text).servers.x.logdir, ["C:\\logs"]);
});

test("the port a configuration file names is read back, under the daemon's name only", () => {
  const text = buildDaemonConfig({ name: "vscode-team", port: 41234, logDirs: [], teamNumber: 0, idleExitMinutes: 0 });
  assert.equal(portInConfig(text, "vscode-team"), 41234);
  assert.equal(portInConfig(text, "team"), undefined);
  assert.equal(portInConfig(undefined, "vscode-team"), undefined);
  assert.equal(portInConfig("not json", "vscode-team"), undefined);
  assert.equal(portInConfig('{"servers":{"vscode-team":{"port":"41234"}}}', "vscode-team"), undefined, "a number, not text");
});

test("start and stop run the server's own verbs on the named daemon, with the heap first", () => {
  assert.deepEqual(startCommand("4g", "/s/wpilog-mcp-all.jar", "vscode-team", "/s/servers/vscode-team.json"),
    ["-Xmx4g", "-jar", "/s/wpilog-mcp-all.jar", "start", "vscode-team", "--config", "/s/servers/vscode-team.json"]);
  assert.deepEqual(stopCommand("2g", "/s/wpilog-mcp-all.jar", "vscode-team"),
    ["-Xmx2g", "-jar", "/s/wpilog-mcp-all.jar", "stop", "vscode-team"]);
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

test("a port is chosen once and kept while it is free or ours; a stranger on it means another", () => {
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

test("a shared server's directories are every project's, each once, two names for one folder counting once", () => {
  assert.deepEqual(
    unionLogDirectories([["/a", "/b"], ["/b", "/c"], []], path.posix),
    ["/a", "/b", "/c"]);
  assert.deepEqual(
    unionLogDirectories([["C:\\robot\\logs"], ["c:/robot/logs/", "D:\\archive"]], path.win32),
    ["C:\\robot\\logs", "D:\\archive"]);
  assert.deepEqual(unionLogDirectories([]), []);
});

test("an entry that runs the bridge is told from one that runs its own server", () => {
  const bridge = mergeServerEntry(undefined, buildServerEntry("java", "a.jar", "4g", "vscode-team", "/s/vscode-team.json"));
  assert.ok(bridge.ok && entryUsesConnect(bridge.text));
  const own = mergeServerEntry(undefined, {
    command: "java",
    args: ["-Xmx4g", "-jar", "a.jar", "start", "default", "--config", "/s/projects/x.json"],
  });
  assert.ok(own.ok && !entryUsesConnect(own.text));
  assert.ok(!entryUsesConnect(undefined));
  assert.ok(!entryUsesConnect("not json"));
  assert.ok(!entryUsesConnect('{"mcpServers":{"other":{"command":"x","args":["connect","team"]}}}'),
    "another server's entry is not this one's");
});

test("a server's log is where the server writes it, under the home folder, by the daemon's name", () => {
  assert.equal(serverLogPath("/home/me", "vscode-team", path.posix), "/home/me/.wpilog-mcp/logs/vscode-team.log");
  assert.equal(serverLogPath("C:\\Users\\me", "vscode-default", path.win32),
    "C:\\Users\\me\\.wpilog-mcp\\logs\\vscode-default.log");
});
