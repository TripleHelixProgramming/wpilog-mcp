// Tests for the settings the extension declares in package.json (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import { overlaySettings } from "../logDirectories";
import { buildServerConfig } from "../mcpJson";

const manifest = JSON.parse(
  fs.readFileSync(path.join(__dirname, "..", "..", "package.json"), "utf8")
);
// configuration is a list of sections, each with its own properties
const settings = Object.assign(
  {},
  ...manifest.contributes.configuration.map((section: { properties: object }) => section.properties)
);

test("the team number is empty until the user sets it: never another team's number", () => {
  const team = settings["wpilog-mcp.teamNumber"];
  assert.equal(team.default, null);
  assert.deepEqual(team.type, ["integer", "null"]);
  assert.equal(team.minimum, 1);
});

test("an untouched team number puts no team in the server's configuration", () => {
  // VS Code reports the default (null) as the user's value when nobody set one
  const merged = overlaySettings({ teamNumber: settings["wpilog-mcp.teamNumber"].default }, {});
  const config = JSON.parse(buildServerConfig({ logDirs: [], teamNumber: merged.teamNumber || 0 }));
  assert.equal(config.servers.default.team, undefined);
});

test("the settings appear in a fixed order: where the logs are, then the team and TBA, then the rest", () => {
  // Without an order, the Settings editor sorts settings by name, which put the additional log
  // directories first and the log directory after them
  const byOrder = Object.entries(settings)
    .map(([key, value]) => ({ key, order: (value as { order?: number }).order }))
    .sort((a, b) => (a.order ?? Infinity) - (b.order ?? Infinity));
  assert.deepEqual(byOrder.map((s) => s.key), [
    "wpilog-mcp.logDirectory",
    "wpilog-mcp.additionalLogDirectories",
    "wpilog-mcp.teamNumber",
    "wpilog-mcp.tbaApiKey",
    "wpilog-mcp.enableForClaudeCode",
    "wpilog-mcp.javaPath",
    "wpilog-mcp.wpiLibYear",
    "wpilog-mcp.maxHeap",
  ]);
  const orders = byOrder.map((s) => s.order);
  assert.ok(orders.every((o) => Number.isInteger(o)), "every setting has an order");
  assert.equal(new Set(orders).size, orders.length, "no two settings share an order");
});

test("the TBA API key field is shown, never synced, and can hold a key from any settings level", () => {
  const key = settings["wpilog-mcp.tbaApiKey"];
  assert.equal(key.type, "string");
  // A deprecated setting is hidden from the Settings editor unless it has a value
  assert.equal(key.deprecationMessage, undefined);
  assert.equal(key.markdownDeprecationMessage, undefined);
  // Settings Sync must not upload a key in the moment before it is moved into secret storage
  assert.equal(key.ignoreSync, true);
  // An application or machine scope would hide a key in a workspace's settings from the extension,
  // which must still find and remove one that versions before 0.9 left there
  assert.ok(key.scope === undefined || key.scope === "window", `scope ${key.scope}`);
});

test("every command a setting's description links to is one the extension declares", () => {
  const declared = new Set(
    manifest.contributes.commands.map((c: { command: string }) => c.command)
  );
  const linked: string[] = [];
  for (const value of Object.values(settings) as { markdownDescription?: string }[]) {
    for (const m of (value.markdownDescription ?? "").matchAll(/\]\(command:([^)?\s]+)/g)) {
      linked.push(m[1]);
    }
  }
  assert.ok(linked.length > 0, "the TBA API key field links its commands");
  for (const command of linked) {
    assert.ok(declared.has(command), `linked command ${command} is declared`);
  }
});

