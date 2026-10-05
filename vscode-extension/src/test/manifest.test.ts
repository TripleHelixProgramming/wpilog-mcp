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
    "wpilog-mcp.servers",
    "wpilog-mcp.serverName",
    "wpilog-mcp.javaPath",
    "wpilog-mcp.wpiLibYear",
    "wpilog-mcp.maxHeap",
    "wpilog-mcp.idleExitMinutes",
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


test("the commands are the ones the README names, each under the extension's or the explorer's category", () => {
  const commands = manifest.contributes.commands as { command: string; title: string; category: string }[];
  assert.deepEqual(commands.map((c) => c.command), [
    "wpilog-mcp.setTbaApiKey",
    "wpilog-mcp.clearTbaApiKey",
    "wpilog-mcp.addToClaudeCode",
    "wpilog-mcp.showServerLog",
    "wpilog-mcp.restartServer",
    "wpilog-mcp.explorer.refreshLogs",
    "wpilog-mcp.explorer.filterLogs",
    "wpilog-mcp.explorer.clearLogFilter",
    "wpilog-mcp.explorer.filterEntries",
    "wpilog-mcp.explorer.clearEntryFilter",
    "wpilog-mcp.explorer.openLog",
    "wpilog-mcp.explorer.revealLog",
    "wpilog-mcp.explorer.copyLogPath",
    "wpilog-mcp.explorer.showEntry",
    "wpilog-mcp.explorer.refreshLog",
  ]);
  for (const c of commands) {
    const explorer = c.command.startsWith("wpilog-mcp.explorer.");
    assert.equal(c.category, explorer ? "WPILog Explorer" : "WPILog Analyzer", c.command);
    assert.ok(c.title.length > 0, c.command);
  }
});

// ---- WPILog Explorer: the container, the views, the editor, and the menus ----

test("the explorer is one activity bar container with the Logs and Entries views, each with an icon", () => {
  const containers = manifest.contributes.viewsContainers.activitybar as { id: string; title: string; icon: string }[];
  assert.deepEqual(containers.map((c) => [c.id, c.title]), [["wpilog-explorer", "WPILog Explorer"]]);
  assert.ok(fs.existsSync(path.join(__dirname, "..", "..", containers[0].icon)), "the container's icon is in the package");
  const views = manifest.contributes.views["wpilog-explorer"] as { id: string; name: string; icon: string }[];
  assert.deepEqual(views.map((v) => [v.id, v.name]), [["wpilog-mcp.logs", "Logs"], ["wpilog-mcp.entries", "Entries"]]);
  for (const view of views) assert.ok(fs.existsSync(path.join(__dirname, "..", "..", view.icon)), view.id);
  const welcome = manifest.contributes.viewsWelcome as { view: string; when: string }[];
  assert.deepEqual(welcome.map((w) => [w.view, w.when]), [["wpilog-mcp.entries", "!wpilog-mcp.hasActiveLog"]],
    "the Entries view says how to get entries until a log is open");
});

test("the custom editor opens .wpilog files by default, read-only, and leaves other editors offered", () => {
  const editors = manifest.contributes.customEditors as { viewType: string; displayName: string; selector: { filenamePattern: string }[]; priority: string }[];
  assert.equal(editors.length, 1);
  assert.equal(editors[0].viewType, "wpilog-mcp.explorer");
  assert.equal(editors[0].displayName, "WPILog Explorer");
  assert.deepEqual(editors[0].selector, [{ filenamePattern: "*.wpilog" }]);
  assert.equal(editors[0].priority, "default", "a double-click opens the explorer; Open With still offers the rest");
  // The page's assets ship in the package
  for (const asset of ["media/explorer.css", "media/explorer.js"]) {
    assert.ok(fs.existsSync(path.join(__dirname, "..", "..", asset)), asset);
  }
  const ignore = fs.readFileSync(path.join(__dirname, "..", "..", ".vscodeignore"), "utf8");
  assert.ok(!/^media/m.test(ignore) && !/^images/m.test(ignore), ".vscodeignore keeps media/ and images/");
});

test("every menu names a declared command, and every view-title button has an icon", () => {
  const declared = new Map(
    (manifest.contributes.commands as { command: string; icon?: string }[]).map((c) => [c.command, c])
  );
  const menus = manifest.contributes.menus as Record<string, { command: string; when?: string }[]>;
  assert.deepEqual(Object.keys(menus).sort(), ["commandPalette", "editor/title", "view/item/context", "view/title"]);
  for (const [menu, items] of Object.entries(menus)) {
    for (const item of items) {
      assert.ok(declared.has(item.command), `${menu}: ${item.command}`);
      if (menu === "view/title" || menu === "editor/title") {
        assert.ok(declared.get(item.command)?.icon, `${item.command} has an icon for its button`);
        assert.ok(item.when?.includes("view == ") || item.when?.includes("activeCustomEditorId"), `${item.command} is scoped to a view or the editor`);
      }
    }
  }
  // Commands that take a tree item or a path are not in the palette, where nothing supplies one
  const hidden = menus.commandPalette.filter((m) => m.when === "false").map((m) => m.command);
  assert.deepEqual(hidden, [
    "wpilog-mcp.explorer.openLog",
    "wpilog-mcp.explorer.revealLog",
    "wpilog-mcp.explorer.copyLogPath",
    "wpilog-mcp.explorer.showEntry",
  ]);
});

test("the idle exit is a whole number of minutes, thirty by default, and zero turns it off", () => {
  const idle = settings["wpilog-mcp.idleExitMinutes"];
  assert.equal(idle.type, "integer");
  assert.equal(idle.default, 30);
  assert.equal(idle.minimum, 0);
});

test("a server name is a Workspace setting that names a file and a daemon, so its shape is checked", () => {
  const name = settings["wpilog-mcp.serverName"];
  assert.equal(name.type, "string");
  assert.equal(name.default, "");
  assert.equal(name.scope, "resource");
  const pattern = new RegExp(name.pattern);
  for (const good of ["", "team", "Rebuilt2026", "a.b-c_d"]) assert.ok(pattern.test(good), good);
  for (const bad of ["-x", "a b", "../x", "x/y", "a".repeat(65)]) assert.ok(!pattern.test(bad), bad);
});

test("the servers are a User setting: a list of named servers, each name shaped as a server name is", () => {
  const servers = settings["wpilog-mcp.servers"];
  assert.equal(servers.type, "array");
  assert.deepEqual(servers.default, [], "one server, the default, until the user adds another");
  assert.equal(servers.scope, "application", "defined once per user, not per workspace");
  const item = servers.items;
  assert.equal(item.type, "object");
  assert.deepEqual(item.required, ["name"]);
  assert.deepEqual(Object.keys(item.properties), ["name", "logDirectory", "teamNumber"]);
  assert.equal(item.additionalProperties, false);
  assert.equal(item.properties.teamNumber.minimum, 1);
  // The same shape the Server Name setting accepts, so a name typed in one matches the other
  const namePattern = new RegExp(item.properties.name.pattern);
  const projectPattern = new RegExp(settings["wpilog-mcp.serverName"].pattern);
  for (const name of ["team", "Rebuilt2026", "a.b-c_d", "-x", "a b", "../x", "a".repeat(65)]) {
    assert.equal(namePattern.test(name), projectPattern.test(name), name);
  }
  assert.ok(!namePattern.test(""), "a server must have a name; a project's blank name means the default");
});
