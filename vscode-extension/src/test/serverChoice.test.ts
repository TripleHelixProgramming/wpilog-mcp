// Tests for choosing the server that opens a log (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import { fileInside, rankServersForFile } from "../explorer/serverChoice";
import { contentSecurityPolicy, explorerPage } from "../explorer/webviewHtml";

test("a file is inside a directory when a folder below it holds the file, not when the name merely starts alike", () => {
  assert.ok(fileInside("/logs/a.wpilog", "/logs", path.posix));
  assert.ok(fileInside("/logs/vache/session_3/a.wpilog", "/logs/", path.posix));
  assert.ok(!fileInside("/logs2/a.wpilog", "/logs", path.posix), "/logs2 is not under /logs");
  assert.ok(!fileInside("/logs", "/logs", path.posix), "the directory itself is not a file inside it");
  assert.ok(!fileInside("/other/a.wpilog", "/logs", path.posix));
  assert.ok(fileInside("C:\\Users\\me\\riologs\\a.wpilog", "c:/users/ME/riologs", path.win32), "Windows ignores case and slashes");
  assert.ok(!fileInside("D:\\riologs\\a.wpilog", "C:\\riologs", path.win32));
});

test("servers whose directories hold the file come first, then the others, none left out", () => {
  const def = { server: "default", logDirs: ["/home/me/riologs"] };
  const team = { server: "team", logDirs: ["/team/logs", "/archive"] };
  const none = { server: "empty", logDirs: [] };
  assert.deepEqual(rankServersForFile("/archive/2025/a.wpilog", [def, team, none], path.posix), ["team", "default", "empty"]);
  assert.deepEqual(rankServersForFile("/home/me/riologs/a.wpilog", [def, team, none], path.posix), ["default", "team", "empty"]);
  assert.deepEqual(rankServersForFile("/elsewhere/a.wpilog", [def, team, none], path.posix), ["default", "team", "empty"],
    "a file nobody holds is still tried everywhere, in the window's order");
  assert.deepEqual(rankServersForFile("/x.wpilog", [], path.posix), []);
});

test("the editor's page allows only its own style and script, and no connection at all", () => {
  const assets = { cspSource: "vscode-webview-resource:", styleUri: "s.css", scriptUri: "e.js", nonce: "abc123" };
  const policy = contentSecurityPolicy(assets);
  assert.ok(policy.startsWith("default-src 'none'"));
  assert.ok(!/connect-src/.test(policy), "nothing may fetch: the host is the client");
  assert.ok(policy.includes("script-src 'nonce-abc123'"));
  assert.ok(!policy.includes("'unsafe-inline'") && !policy.includes("'unsafe-eval'"));
  const page = explorerPage(assets);
  assert.ok(page.includes(`content="${policy}"`));
  assert.ok(page.includes('<script nonce="abc123" src="e.js">'));
  assert.ok(page.includes('href="s.css"'));
  assert.ok(!/<script(?![^>]*nonce=)/.test(page), "every script carries the nonce");
});
