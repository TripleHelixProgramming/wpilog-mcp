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
