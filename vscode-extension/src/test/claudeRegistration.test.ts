import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { claudeArgs, claudeCommand, claudeCommandText, claudePitArgs, claudePitCommand, claudePitCommandText, findClaude } from "../claudeRegistration";
import { ignoreProjectFile, projectFileOffer, projectFileText } from "../projectFile";

for (const platform of ["linux", "darwin", "win32"] as const) {
  test(`Claude registration contains only the user-scope bridge on ${platform}`, () => {
    const launcher = platform === "win32" ? "C:\\My Home\\.wpilog-mcp\\bin\\wpilog-mcp.bat" : "/my home/.wpilog-mcp/bin/wpilog-mcp";
    const args = ["mcp", "add", "--scope", "user", "wpilog-analyzer", "--",
      ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "http"];
    assert.deepEqual(claudeArgs(launcher, platform), args);
    const command = claudeCommand("claude", launcher, platform);
    assert.equal(command.command, platform === "win32" ? "cmd.exe" : "claude");
    assert.equal(command.windowsVerbatimArguments, platform === "win32");
    if (platform !== "win32") assert.deepEqual(command.args, args);
    else assert.deepEqual(command.args, ["/d", "/s", "/c",
      `""claude" "mcp" "add" "--scope" "user" "wpilog-analyzer" "--" "cmd" "/c" "${launcher}" "connect" "http""`]);
    const quoted = platform === "win32" ? args.map(word => `"${word}"`) : args.map(word => `'${word}'`);
    assert.equal(claudeCommandText(launcher, platform), `claude ${quoted.join(" ")}`);
    assert.ok(!/--logdir|--config|tba|secret/.test(JSON.stringify(command)));
  });
}

test("Claude lookup finds native executables or Windows shims and reports absence", () => {
  assert.equal(findClaude("/a:/my bin", "linux", p => p === "/my bin/claude", path.posix), "/my bin/claude");
  const expected = path.win32.join("C:\\My Bin", "claude.cmd");
  assert.equal(findClaude("C:\\Empty;C:\\My Bin", "win32", p => p === expected, path.win32), expected);
  assert.equal(findClaude("/a", "linux", () => false, path.posix), undefined);
});

test("project YAML is offered once only for own directories and never replaces an existing file", () => {
  const own = { logDirectory: "logs" };
  assert.equal(projectFileOffer(own, undefined, false), true);
  assert.equal(projectFileOffer({ additionalLogDirectories: ["sim"] }, undefined, false), true);
  assert.equal(projectFileOffer(own, undefined, true), false);
  assert.equal(projectFileOffer(own, "", false), false);
  assert.equal(projectFileOffer(own, undefined, false, "tracked"), false);
  assert.equal(projectFileOffer(own, undefined, false, "ignored"), true);
  assert.equal(projectFileOffer(own, undefined, false, "untracked"), true);
  assert.equal(projectFileOffer({ teamNumber: 2363 }, undefined, false), false);
  assert.equal(projectFileOffer({ logDirectory: " ", additionalLogDirectories: [7, ""] }, undefined, false), false);
});

test("project YAML carries only directories and a valid stated team, with portable escaping", () => {
  const own = { logDirectory: 'logs/#"first"', additionalLogDirectories: ["C:\\Logs\\sim", "", 8], teamNumber: 2363,
    tbaKey: "never-written", servers: { http: { port: 99 } } };
  const text = projectFileText(own);
  assert.deepEqual(text.split("\n"), ["# Directories leased by wpilog-mcp connect from this project.",
    'logdir: ["logs/#\\"first\\"","C:\\\\Logs\\\\sim"]', "team: 2363", ""]);
  assert.ok(!projectFileText({ logDirectory: "logs" }).includes("team:"));
  for (const teamNumber of [0, -1, NaN, 1.5]) {
    assert.ok(!projectFileText({ logDirectory: "logs", teamNumber }).includes("team:"));
  }
  assert.ok(!/never-written|servers|port|tba/.test(text));
  assert.equal(ignoreProjectFile("build/\r\n"), "build/\r\n.wpilog-mcp.yaml\r\n");
  assert.equal(ignoreProjectFile("/.wpilog-mcp.yaml\n"), "/.wpilog-mcp.yaml\n");
});


for (const platform of ["linux", "darwin", "win32"] as const) test(`pit registration is a second user-scope URL bridge on ${platform}`, () => {
  const launcher = platform === "win32" ? "C:\\Home\\wpilog-mcp.bat" : "/my home/wpilog-mcp";
  const url = "http://pit:2363/mcp";
  const args = ["mcp", "add", "--scope", "user", "wpilog-pit", "--",
    ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "--url", url];
  assert.deepEqual(claudePitArgs(launcher, url, platform), args);
  const command = claudePitCommand("claude", launcher, url, platform);
  assert.equal(command.command, platform === "win32" ? "cmd.exe" : "claude");
  if (platform !== "win32") assert.deepEqual(command.args, args);
  const quoted = platform === "win32" ? args.map(word => `"${word}"`) : args.map(word => `'${word}'`);
  assert.equal(claudePitCommandText(launcher, url, platform), `claude ${quoted.join(" ")}`);
  assert.ok(!/--logdir|--config|tba|password/.test(JSON.stringify(command)));
});
