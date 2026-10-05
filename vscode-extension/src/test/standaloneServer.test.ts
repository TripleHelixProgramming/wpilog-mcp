// Tests for using the standalone install's server (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import {
  STANDALONE_SERVER,
  buildStandaloneEntry,
  findStandaloneInstall,
  launcherCommand,
  olderVersion,
  portInPidFile,
  standaloneStartArgs,
  standaloneStopArgs,
} from "../standaloneServer";
import { entryUsesConnect } from "../projectServers";
import { mergeServerEntry, otherWpilogServer } from "../mcpJson";

const fileSet = (...files: string[]) => (file: string) => files.includes(file);

test("the install is found under the home folder, with the launcher and servers.yaml, as the installers lay it out", () => {
  const install = findStandaloneInstall("/home/me", "linux",
    fileSet("/home/me/.wpilog-mcp/bin/wpilog-mcp", "/home/me/.wpilog-mcp/servers.yaml"), path.posix);
  assert.deepEqual(install, {
    installDir: "/home/me/.wpilog-mcp",
    launcher: "/home/me/.wpilog-mcp/bin/wpilog-mcp",
    configPath: "/home/me/.wpilog-mcp/servers.yaml",
    pidFile: "/home/me/.wpilog-mcp/run/http.pid",
    missing: undefined,
  });
  assert.equal(STANDALONE_SERVER, "http", "the server the standalone guide starts for every client");
});

test("on Windows the launcher is the batch file, and the paths follow Windows rules", () => {
  const install = findStandaloneInstall("C:\\Users\\Jo Smith", "win32",
    fileSet("C:\\Users\\Jo Smith\\.wpilog-mcp\\bin\\wpilog-mcp.bat", "C:\\Users\\Jo Smith\\.wpilog-mcp\\servers.yaml"), path.win32);
  assert.equal(install.launcher, "C:\\Users\\Jo Smith\\.wpilog-mcp\\bin\\wpilog-mcp.bat");
  assert.equal(install.configPath, "C:\\Users\\Jo Smith\\.wpilog-mcp\\servers.yaml");
  assert.equal(install.pidFile, "C:\\Users\\Jo Smith\\.wpilog-mcp\\run\\http.pid");
  assert.equal(install.missing, undefined);
});

test("an older install's servers.json is read when there is no servers.yaml", () => {
  const install = findStandaloneInstall("/home/me", "darwin",
    fileSet("/home/me/.wpilog-mcp/bin/wpilog-mcp", "/home/me/.wpilog-mcp/servers.json"), path.posix);
  assert.equal(install.configPath, "/home/me/.wpilog-mcp/servers.json");
  assert.equal(install.missing, undefined);
  const both = findStandaloneInstall("/home/me", "darwin",
    fileSet("/home/me/.wpilog-mcp/bin/wpilog-mcp", "/home/me/.wpilog-mcp/servers.json", "/home/me/.wpilog-mcp/servers.yaml"), path.posix);
  assert.equal(both.configPath, "/home/me/.wpilog-mcp/servers.yaml", "the YAML file first, as the server reads them");
});

test("an install without its launcher or its configuration file says what is missing", () => {
  const none = findStandaloneInstall("/home/me", "linux", () => false, path.posix);
  assert.equal(none.missing, "no launcher at /home/me/.wpilog-mcp/bin/wpilog-mcp");
  const noConfig = findStandaloneInstall("/home/me", "linux", fileSet("/home/me/.wpilog-mcp/bin/wpilog-mcp"), path.posix);
  assert.equal(noConfig.missing, "no configuration file at /home/me/.wpilog-mcp/servers.yaml");
  assert.equal(noConfig.configPath, "/home/me/.wpilog-mcp/servers.yaml", "the file the installer would write");
});

test("the port is the PID file's second line, and a file that is not one records no server", () => {
  assert.equal(portInPidFile("12345\n41234\n"), 41234);
  assert.equal(portInPidFile("12345\r\n2363\r\nSTARTING\r\n"), 2363, "a start in progress, Windows line endings");
  assert.equal(portInPidFile("12345\n41234"), 41234, "no trailing newline");
  assert.equal(portInPidFile("12345\n"), undefined, "one line");
  assert.equal(portInPidFile("12345"), undefined);
  assert.equal(portInPidFile(""), undefined);
  assert.equal(portInPidFile(undefined), undefined, "no file");
  assert.equal(portInPidFile("x\ny\n"), undefined);
  assert.equal(portInPidFile("1\n0\n"), undefined, "no port is 0");
  assert.equal(portInPidFile("1\n70000\n"), undefined);
  assert.equal(portInPidFile("1\n41234.5\n"), undefined);
});

test("the launcher runs as it is on macOS and Linux, and under cmd.exe on Windows with every word quoted", () => {
  assert.deepEqual(launcherCommand("/home/me/.wpilog-mcp/bin/wpilog-mcp", ["start", "http"], "linux"),
    { command: "/home/me/.wpilog-mcp/bin/wpilog-mcp", args: ["start", "http"], windowsVerbatimArguments: false });
  assert.deepEqual(launcherCommand("/Users/me/.wpilog-mcp/bin/wpilog-mcp", ["stop", "http"], "darwin"),
    { command: "/Users/me/.wpilog-mcp/bin/wpilog-mcp", args: ["stop", "http"], windowsVerbatimArguments: false });
  const windows = launcherCommand("C:\\Users\\Jo Smith\\.wpilog-mcp\\bin\\wpilog-mcp.bat",
    ["start", "http", "--config", "C:\\Users\\Jo Smith\\.wpilog-mcp\\servers.yaml"], "win32");
  assert.equal(windows.command, "cmd.exe");
  assert.deepEqual(windows.args, ["/d", "/s", "/c",
    '""C:\\Users\\Jo Smith\\.wpilog-mcp\\bin\\wpilog-mcp.bat" "start" "http" "--config" "C:\\Users\\Jo Smith\\.wpilog-mcp\\servers.yaml""']);
  assert.equal(windows.windowsVerbatimArguments, true, "the command line goes to cmd.exe as written");
});

test("start and stop name the http server and the install's configuration file, so no other file is read", () => {
  assert.deepEqual(standaloneStartArgs("/home/me/.wpilog-mcp/servers.yaml"),
    ["start", "http", "--config", "/home/me/.wpilog-mcp/servers.yaml"]);
  assert.deepEqual(standaloneStopArgs("/home/me/.wpilog-mcp/servers.yaml"),
    ["stop", "http", "--config", "/home/me/.wpilog-mcp/servers.yaml"]);
});

test("Claude Code's entry runs the install's bridge to the same server from the same file, under cmd on Windows", () => {
  const posix = buildStandaloneEntry("/home/me/.wpilog-mcp/bin/wpilog-mcp", "/home/me/.wpilog-mcp/servers.yaml", "linux");
  assert.deepEqual(posix, {
    command: "/home/me/.wpilog-mcp/bin/wpilog-mcp",
    args: ["connect", "http", "--config", "/home/me/.wpilog-mcp/servers.yaml"],
  });
  const windows = buildStandaloneEntry("C:\\Users\\me\\.wpilog-mcp\\bin\\wpilog-mcp.bat", "C:\\Users\\me\\.wpilog-mcp\\servers.yaml", "win32");
  assert.deepEqual(windows, {
    command: "cmd",
    args: ["/c", "C:\\Users\\me\\.wpilog-mcp\\bin\\wpilog-mcp.bat", "connect", "http", "--config", "C:\\Users\\me\\.wpilog-mcp\\servers.yaml"],
  });
  assert.equal(posix.env, undefined, "no settings and no key in the entry");
  // Written into .mcp.json, the entry is the extension's (no second wpilog-mcp entry is seen
  // beside it) and counts as a bridge, so no per-project file is kept for it
  for (const entry of [posix, windows]) {
    const edit = mergeServerEntry(undefined, entry);
    assert.ok(edit.ok);
    assert.ok(entryUsesConnect(edit.text));
    assert.equal(otherWpilogServer(edit.text), undefined);
  }
});

test("a server is older than the extension by the version's numbers; a suffix does not count", () => {
  assert.ok(olderVersion("0.9.1", "0.9.2"));
  assert.ok(olderVersion("0.9.1", "0.10.0"));
  assert.ok(olderVersion("0.9.9", "1.0.0"));
  assert.ok(!olderVersion("0.9.2", "0.9.2"));
  assert.ok(!olderVersion("0.9.2", "0.9.2-dev3"), "the same numbers with a suffix");
  assert.ok(!olderVersion("0.9.2-dev3", "0.9.2"));
  assert.ok(!olderVersion("0.10.0", "0.9.2"), "newer is not older");
  assert.ok(!olderVersion("1.0", "1.0.0"), "a missing part is zero");
  assert.ok(olderVersion(undefined, "0.9.2"), "a server too old to report a version");
});
