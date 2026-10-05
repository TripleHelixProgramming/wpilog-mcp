// Tests for the servers' decisions (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import {
  Backoff,
  DEFAULT_SERVER,
  MAX_START_ATTEMPTS,
  SERVER_NAME_PATTERN,
  ServerDefinition,
  buildDaemonConfig,
  classifyHealth,
  daemonIsCurrent,
  daemonNameFor,
  definedServers,
  entryUsesConnect,
  healthUrl,
  keepPort,
  portInConfig,
  resolveServer,
  serverFor,
  serverLogPath,
  serverNameFor,
  serverUrl,
  startCommand,
  stopCommand,
  unionLogDirectories,
} from "../projectServers";
import { buildServerEntry, mergeServerEntry } from "../mcpJson";

test("a server's daemon is named for it, apart from a standalone install's servers", () => {
  assert.equal(daemonNameFor("default"), "vscode-default");
  assert.equal(daemonNameFor("team"), "vscode-team");
  assert.equal(DEFAULT_SERVER, "default");
});

test("a project asks for the server its setting names, or the default when the setting is blank or unusable", () => {
  assert.equal(serverNameFor("team"), "team");
  assert.equal(serverNameFor("  team  "), "team");
  assert.equal(serverNameFor("Team"), "Team", "case is kept: another server");
  assert.equal(serverNameFor(""), "default");
  assert.equal(serverNameFor(undefined), "default");
  assert.equal(serverNameFor(42), "default");
  assert.equal(serverNameFor("../escape"), "default", "a name is a file name");
  assert.equal(serverNameFor("with space"), "default");
  assert.equal(serverNameFor("-leading"), "default");
  assert.equal(serverNameFor("a".repeat(65)), "default");
  assert.equal(serverNameFor("a".repeat(64)), "a".repeat(64));
  assert.ok(SERVER_NAME_PATTERN.test("Rebuilt2026.v2_x-y"));
});

test("the servers defined are the default, from the User settings, and the setting's usable entries", () => {
  const { servers, skipped } = definedServers(
    { logDirectory: "~/riologs", additionalLogDirectories: ["/archive", "", 7], teamNumber: 2363 },
    [
      { name: "team", logDirectory: "/team/logs", teamNumber: 2363 },
      { name: " practice ", logDirectory: "/practice" },
      { name: "nodir" },
      { name: "bad name" },
      { logDirectory: "/no/name" },
      { name: "team", logDirectory: "/again" },
      null,
      "team",
      { name: "zero", teamNumber: 0 },
    ]
  );
  assert.deepEqual([...servers.keys()], ["default", "team", "practice", "nodir", "zero"]);
  assert.deepEqual(servers.get("default"), {
    name: "default",
    logDirectory: ["~/riologs", "/archive"].join(path.delimiter),
    teamNumber: 2363,
  });
  assert.deepEqual(servers.get("team"), { name: "team", logDirectory: "/team/logs", teamNumber: 2363 },
    "the first of two entries with one name wins");
  assert.deepEqual(servers.get("practice"), { name: "practice", logDirectory: "/practice", teamNumber: undefined });
  assert.deepEqual(servers.get("nodir"), { name: "nodir", logDirectory: undefined, teamNumber: undefined });
  assert.deepEqual(servers.get("zero"), { name: "zero", logDirectory: undefined, teamNumber: undefined });
  assert.deepEqual(skipped, ['"bad name"', "{\"logDirectory\":\"/no/name\"}", '"team"', "null", '"team"']);
});

test("with nothing set there is one server, the default, with no directory and no team", () => {
  const { servers, skipped } = definedServers({ teamNumber: null as unknown as number }, undefined);
  assert.deepEqual([...servers.values()], [{ name: "default", logDirectory: undefined, teamNumber: undefined }]);
  assert.deepEqual(skipped, []);
  assert.deepEqual([...definedServers({}, "not a list").servers.keys()], ["default"]);
});

test("an entry named default replaces the default server's definition", () => {
  const { servers } = definedServers({ logDirectory: "/user" }, [{ name: "default", logDirectory: "/other", teamNumber: 1 }]);
  assert.deepEqual([...servers.keys()], ["default"]);
  assert.deepEqual(servers.get("default"), { name: "default", logDirectory: "/other", teamNumber: 1 });
});

test("a project gets the server it asks for when defined, else the default, and says when the name is unknown", () => {
  const { servers } = definedServers({ logDirectory: "/user" }, [{ name: "team", logDirectory: "/team" }]);
  assert.deepEqual(serverFor("team", servers), { server: servers.get("team"), unknown: false });
  assert.deepEqual(serverFor("default", servers), { server: servers.get("default"), unknown: false });
  assert.deepEqual(serverFor("nobody", servers), { server: servers.get("default"), unknown: true });
});

test("a server's directories are its own and its projects', each project's resolved as its own settings say", () => {
  const server: ServerDefinition = { name: "team", logDirectory: "/team/logs:/archive", teamNumber: 2363 };
  const resolved = resolveServer(
    server,
    [
      // A project with no settings of its own: the server's directories, which are absolute, once
      { folderPath: "/th/Rebuilt", own: {} },
      // One with its own main directory and additional list: relative paths inside it
      { folderPath: "/th/Practice", own: { logDirectory: "/practice", additionalLogDirectories: ["logs", "/archive/"] } },
      // One with only a team number: the server's directories
      { folderPath: "/th/Other", own: { teamNumber: 9999 } },
    ],
    path.posix
  );
  assert.deepEqual(resolved.logDirs, ["/team/logs", "/archive", "/practice", "/th/Practice/logs"]);
  assert.equal(resolved.teamNumber, 9999, "the first team a project sets");
});

test("a project's own additional list replaces the server's additional directories, as a Workspace list does", () => {
  const server: ServerDefinition = { name: "team", logDirectory: "/team/logs:/archive" };
  const resolved = resolveServer(server, [{ folderPath: "/p", own: { additionalLogDirectories: [] } }], path.posix);
  assert.deepEqual(resolved.logDirs, ["/team/logs", "/archive"], "the server's own stay; the project adds none");
  const main = resolveServer(server, [{ folderPath: "/p", own: { logDirectory: "/mine" } }], path.posix);
  assert.deepEqual(main.logDirs, ["/team/logs", "/archive", "/mine"]);
  assert.equal(main.teamNumber, 0, "no team anywhere");
});

test("a server's own relative directory names nothing; a project's names a folder inside it", () => {
  const server: ServerDefinition = { name: "x", logDirectory: "logs" };
  assert.deepEqual(resolveServer(server, [], path.posix).logDirs, []);
  assert.deepEqual(resolveServer(server, [{ folderPath: "/p", own: {} }], path.posix).logDirs, ["/p/logs"],
    "the project resolves the server's relative main directory inside itself");
});

test("a server's directories are split as PATH is, on Windows too, and the team is the server's when no project sets one", () => {
  const server: ServerDefinition = { name: "w", logDirectory: "C:\\robot\\logs;D:\\archive", teamNumber: 2363 };
  const resolved = resolveServer(server, [{ folderPath: "C:\\th\\Rebuilt", own: { additionalLogDirectories: ["c:/robot/logs/"] } }], path.win32);
  assert.deepEqual(resolved.logDirs, ["C:\\robot\\logs", "D:\\archive"], "one folder under two names counts once");
  assert.equal(resolved.teamNumber, 2363);
});

test("a server with no directory and no projects lists nothing", () => {
  assert.deepEqual(resolveServer({ name: "empty" }, [], path.posix), { logDirs: [], teamNumber: 0 });
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
