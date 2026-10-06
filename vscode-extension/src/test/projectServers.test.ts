import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import { Backoff, MAX_START_ATTEMPTS, classifyHealth, healthUrl, serverLogPath, serverUrl } from "../projectServers";

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

test("a server's log is where the server writes it, under the home folder, by the daemon's name", () => {
  assert.equal(serverLogPath("/home/me", "vscode-team", path.posix), "/home/me/.wpilog-mcp/logs/vscode-team.log");
  assert.equal(serverLogPath("C:\\Users\\me", "vscode-default", path.win32),
    "C:\\Users\\me\\.wpilog-mcp\\logs\\vscode-default.log");
});
