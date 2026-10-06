import { test } from "node:test";
import * as assert from "node:assert/strict";
import { InstallActionInput, InstallSummary, installAction, installArgs, launcherVersion, parseInstallSummary } from "../standaloneInstall";

for (const [label, text, expected] of [
  ["sh", "#!/bin/sh\n# wpilog-mcp 0.9.1 launcher\nexec java", "0.9.1"],
  ["batch", "@echo off\r\nsetlocal EnableDelayedExpansion\r\nREM wpilog-mcp 1.10.0-dev3 launcher\r\n", "1.10.0-dev3"],
  ["no final newline", "# wpilog-mcp 2.0 launcher", "2.0"],
  ["case insensitive batch", "rem wpilog-mcp 2.0 launcher\n", "2.0"],
  ["missing", "", undefined],
  ["ordinary script", "#!/bin/sh\nexec java -jar old.jar", undefined],
  ["embedded text", "echo '# wpilog-mcp 1.0 launcher'", undefined],
  ["wrong marker", "# wpilog-mcp 1.0 server", undefined],
  ["bad version", "# wpilog-mcp ../../bad launcher", undefined],
  ["incomplete suffix", "# wpilog-mcp 1.0-dev. launcher", undefined],
  ["marker has extra text", "# wpilog-mcp 1.0 launcher extra", undefined],
] as const) {
  test(`launcher marker: ${label}`, () => assert.equal(launcherVersion(text), expected));
}

test("install arguments seed User directories and team, never keys, refresh, or extension bootstrap", () => {
  const seed = { logDirs: ["/logs with spaces", "C:\\Logs\\archive", "", "  "], teamNumber: 2363,
    tbaKey: "not-for-the-process-list", tbaApiKey: "nor-this-key", withExtension: true,
    vsix: "/never-bootstrap-from-the-extension.vsix", refresh: true };
  assert.deepEqual(installArgs(seed), ["install", "--json", "--logdir", "/logs with spaces",
    "--logdir", "C:\\Logs\\archive", "--team", "2363"]);
});

test("an update carries no seeds and never forces an older version current", () => {
  assert.deepEqual(installArgs(), ["install", "--json"]);
});

for (const team of [undefined, null, 0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
  test(`unset or invalid team is not seeded: ${String(team)}`, () => {
    assert.deepEqual(installArgs({ teamNumber: team }), ["install", "--json"]);
  });
}

const fresh: InstallSummary = {
  install_dir: "/home/me/.wpilog-mcp",
  installed_version: "1.2.0",
  launcher_version_before: null,
  launcher_version_after: "1.2.0",
  repointed: true,
  config_created: true,
  config_path: "/home/me/.wpilog-mcp/servers.yaml",
  launcher_path: "/home/me/.wpilog-mcp/bin/wpilog-mcp",
  path_hint: "/home/me/.wpilog-mcp/bin",
};
for (const [label, summary] of [
  ["fresh", fresh],
  ["update", { ...fresh, launcher_version_before: "1.1.9", config_created: false, path_hint: null }],
  ["equal no-op", { ...fresh, launcher_version_before: "1.2.0", repointed: false, config_created: false }],
  ["newer hand install wins", { ...fresh, launcher_version_before: "2.0.0", launcher_version_after: "2.0.0", repointed: false }],
  ["Windows paths", { ...fresh, install_dir: "C:\\Users\\Me\\.wpilog-mcp",
    launcher_path: "C:\\Users\\Me\\.wpilog-mcp\\bin\\wpilog-mcp.bat", config_path: "C:\\Users\\Me\\.wpilog-mcp\\servers.json" }],
] as const) {
  test(`install summary: ${label}`, () => assert.deepEqual(parseInstallSummary(JSON.stringify(summary)), summary));
}

for (const [label, json] of [
  ["invalid JSON", "not JSON"], ["null", "null"], ["array", "[]"], ["number", "42"], ["empty object", "{}"],
  ["extra output", JSON.stringify(fresh) + "\ninstalled!"],
  ["repointed to another version", JSON.stringify({ ...fresh, launcher_version_after: "2.0" })],
  ["kept no launcher", JSON.stringify({ ...fresh, repointed: false })],
  ["kept a different launcher", JSON.stringify({ ...fresh, repointed: false, launcher_version_before: "1.1.0" })],
]) {
  test(`refuse malformed install summary: ${label}`, () => {
    assert.throws(() => parseInstallSummary(json), /Invalid install summary/);
  });
}
for (const field of Object.keys(fresh)) {
  for (const bad of [undefined, 42, [], "", " ", ...(field === "path_hint" || field === "launcher_version_before" ? [] : [null])]) {
    test(`refuse malformed install summary field ${field}: ${JSON.stringify(bad)}`, () => {
      assert.throws(() => parseInstallSummary(JSON.stringify({ ...fresh, [field]: bad })), /Invalid install summary/);
    });
  }
}
for (const field of ["installed_version", "launcher_version_before", "launcher_version_after"]) {
  test(`refuse malformed install summary version ${field}`, () => {
    assert.throws(() => parseInstallSummary(JSON.stringify({ ...fresh, [field]: "not-a-version" })), /Invalid install summary/);
  });
}

test("a version with a trailing newline is malformed", () => {
  assert.throws(() => parseInstallSummary(JSON.stringify({ ...fresh, launcher_version_before: "1.0.0\n" })),
    /Invalid install summary/);
});

for (const [missing, current, expected] of [
  [true, undefined, "offer"], [true, "1.0.0", "offer"],
  [false, "1.9.9", "update"], [false, undefined, "update"],
  [false, "1.10.0", "none"], [false, "2.0.0", "none"], [false, "1.10.0-dev2", "none"],
] as const) {
  const input: InstallActionInput = { missing, launcherVersion: current, extensionVersion: "1.10.0" };
  test(`shared install action: ${JSON.stringify(input)} -> ${expected}`, () => assert.equal(installAction(input), expected));
}
