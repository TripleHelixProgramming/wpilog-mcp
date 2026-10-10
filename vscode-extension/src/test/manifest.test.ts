// Tests for the settings the extension declares in package.json (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import { overlaySettings } from "../logDirectories";
import { directoryRegistration } from "../directoryLease";

const manifest = JSON.parse(
  fs.readFileSync(path.join(__dirname, "..", "..", "package.json"), "utf8")
);
// configuration is a list of sections, each with its own properties
const settings = Object.assign(
  {},
  ...manifest.contributes.configuration.map((section: { properties: object }) => section.properties)
);

test("the gateway guide stays relative so release packaging pins its tag", () => {
  const guide = fs.readFileSync(path.join(__dirname, "..", "..", "README.md"), "utf8");
  assert.ok(guide.includes("[NT4 gateway configuration](../doc/STANDALONE.md#nt4-gateway-for-dashboards)"));
  assert.doesNotMatch(guide, /https:\/\/github\.com\/TripleHelixProgramming\/wpilog-mcp\/blob\/main\//);
});

test("the real editor smoke is opt-in, packaged out, and runs at both supported endpoints", () => {
  assert.equal(manifest.scripts.test, "tsc -p ./ && node out/test/runTests.js");
  assert.ok(manifest.devDependencies["@vscode/test-electron"], "Electron test runner is development only");
  assert.equal(manifest.dependencies, undefined, "the extension still has no runtime npm dependencies");
  assert.equal(manifest.scripts["test:smoke"], "tsc -p ./ && node out/smoke/run.js");
  const root = path.join(__dirname, "..", "..", "..");
  const workflow = fs.readFileSync(path.join(root, ".github", "workflows", "ci.yml"), "utf8");
  const oldest = manifest.engines.vscode.replace(/^\^/, "");
  assert.ok(workflow.includes(`vscode: ['${oldest}', 'stable']`), "oldest supported and current VS Code");
  assert.match(workflow, /^\s*xvfb-run -a npm run test:smoke\r?$/m, "real Linux Electron, under Xvfb");
  const ignore = fs.readFileSync(path.join(root, "vscode-extension", ".vscodeignore"), "utf8").split(/\r?\n/);
  for (const pattern of ["node_modules/**", "out/smoke/**", ".vscode-test/**"]) assert.ok(ignore.includes(pattern), pattern);
});

test("the team number is empty until the user sets it: never another team's number", () => {
  const team = settings["wpilog-mcp.teamNumber"];
  assert.equal(team.default, null);
  assert.deepEqual(team.type, ["integer", "null"]);
  assert.equal(team.minimum, 1);
});

test("an untouched team number puts no team in the server's configuration", () => {
  // VS Code reports the default (null) as the user's value when nobody set one
  const merged = overlaySettings({ teamNumber: settings["wpilog-mcp.teamNumber"].default }, {});
  assert.equal(directoryRegistration(merged, []).team, null);
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
    "wpilog-mcp.pitServerUrl",
    "wpilog-mcp.mirror.enabled",
    "wpilog-mcp.mirror.folder",
    "wpilog-mcp.mirror.days",
    "wpilog-mcp.mirror.maxSizeGb",
    "wpilog-mcp.mirror.robots",
    "wpilog-mcp.mirror.events",
    "wpilog-mcp.mirror.intervalSec",
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


test("the commands are the ones the README names, each under the extension's or the explorer's category", () => {
  const commands = manifest.contributes.commands as { command: string; title: string; category: string }[];
  assert.deepEqual(commands.map((c) => c.command), [
    "wpilog-mcp.setTbaApiKey",
    "wpilog-mcp.clearTbaApiKey",
    "wpilog-mcp.registerWithClaudeCode",
    "wpilog-mcp.showServerLog",
    "wpilog-mcp.installStandaloneServer",
    "wpilog-mcp.restartServer",
    "wpilog-mcp.registerPitWithClaudeCode",
    "wpilog-mcp.mirrorActions",
    "wpilog-mcp.pinSession",
    "wpilog-mcp.unpinSession",
    "wpilog-mcp.syncNow",
    "wpilog-mcp.openMirrorFolder",
    "wpilog-mcp.syncFromLaptop",
    "wpilog-mcp.uploadToPitServer",
    "wpilog-mcp.setPitProxyCredential",
    "wpilog-mcp.clearPitProxyCredential",
    "wpilog-mcp.explorer.organizeLogs",
    "wpilog-mcp.explorer.importLogs",
    "wpilog-mcp.explorer.assignRobot",
    "wpilog-mcp.explorer.refreshLogs",
    "wpilog-mcp.explorer.filterLogs",
    "wpilog-mcp.explorer.clearLogFilter",
    "wpilog-mcp.explorer.filterEntries",
    "wpilog-mcp.explorer.clearEntryFilter",
    "wpilog-mcp.explorer.openLog",
    "wpilog-mcp.explorer.revealLog",
    "wpilog-mcp.explorer.copyLogPath",
    "wpilog-mcp.explorer.showEntry",
    "wpilog-mcp.explorer.plotEntry",
    "wpilog-mcp.explorer.plotElements",
    "wpilog-mcp.explorer.refreshLog",
    "wpilog-mcp.explorer.openInNotebook",
    "wpilog-mcp.explorer.askAboutSelection",
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
  assert.deepEqual(Object.keys(menus).sort(), ["commandPalette", "editor/title", "view/item/context", "view/title", "webview/context"]);
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
    "wpilog-mcp.explorer.importLogs",
    "wpilog-mcp.explorer.assignRobot",
    "wpilog-mcp.explorer.openLog",
    "wpilog-mcp.explorer.revealLog",
    "wpilog-mcp.explorer.copyLogPath",
    "wpilog-mcp.explorer.showEntry",
    "wpilog-mcp.explorer.plotEntry",
    "wpilog-mcp.explorer.plotElements",
  ]);
});

test("the plot's libraries are bundled with their licenses, at the versions the vendor table pins", () => {
  const root = path.join(__dirname, "..", "..");
  for (const asset of ["media/plot.js", "media/plotMath.js", "media/follow.js", "media/arrowStream.js", "media/console.js", "media/field.js", "media/rev.js", "media/vendor/uPlot.iife.min.js",
    "media/vendor/uPlot.min.css", "media/vendor/uPlot.LICENSE", "media/vendor/README.md"]) {
    assert.ok(fs.existsSync(path.join(root, asset)), asset);
  }
  const table = fs.readFileSync(path.join(root, "media", "vendor", "README.md"), "utf8");
  const pinned = /\| \[uPlot\][^|]*\| ([0-9.]+) \|/.exec(table)?.[1];
  assert.ok(pinned, "the vendor table pins uPlot's version");
  const banner = fs.readFileSync(path.join(root, "media", "vendor", "uPlot.iife.min.js"), "utf8").slice(0, 200);
  assert.ok(banner.includes(`(v${pinned})`), `the bundled uPlot is v${pinned}: ${banner.split("\n")[0]}`);
  assert.ok(fs.readFileSync(path.join(root, "media", "vendor", "uPlot.LICENSE"), "utf8").includes("MIT License"));
  // The webview's scripts never fetch: no fetch, XMLHttpRequest, WebSocket, or import() in them
  for (const script of ["media/explorer.js", "media/plot.js", "media/plotMath.js", "media/follow.js", "media/arrowStream.js", "media/console.js", "media/field.js", "media/rev.js"]) {
    const text = fs.readFileSync(path.join(root, script), "utf8");
    assert.ok(!/\b(fetch|XMLHttpRequest|WebSocket|EventSource)\s*\(/.test(text), `${script} opens no connection`);
  }
});

test("there is one server per computer: no setting defines more, and no project names one", () => {
  // The settings were a pre-release's and are gone (doc/EXPLORER_PLAN.md, decision 5); a user
  // who kept a value in settings.json gets VS Code's own unknown-setting warning there
  assert.equal(settings["wpilog-mcp.servers"], undefined);
  assert.equal(settings["wpilog-mcp.serverName"], undefined);
  for (const [key, setting] of Object.entries(settings)) {
    const text = JSON.stringify(setting);
    assert.ok(!/wpilog-mcp\.servers|serverName|Server Name/.test(text), `${key} refers to a servers setting`);
  }
});


test("organizing has a palette/title command, inline import groups, and unassigned assignment actions", () => {
  const menus = manifest.contributes.menus;
  assert.ok(menus["view/title"].some((m: { command: string; when: string; group: string }) =>
    m.command === "wpilog-mcp.explorer.organizeLogs" && m.when === "view == wpilog-mcp.logs" && m.group === "navigation@3"));
  assert.ok(!menus.commandPalette.some((m: { command: string; when: string }) => m.command === "wpilog-mcp.explorer.organizeLogs" && m.when === "false"));
  assert.deepEqual(menus["view/item/context"].filter((m: { command: string }) =>
    ["wpilog-mcp.explorer.importLogs", "wpilog-mcp.explorer.assignRobot"].includes(m.command)), [
    { command: "wpilog-mcp.explorer.importLogs", when: "view == wpilog-mcp.logs && (viewItem == wpilogImportGroup || viewItem == wpilogImportFile || viewItem == wpilogDirectory || viewItem == wpilogPlainLog)", group: "inline@1" },
    { command: "wpilog-mcp.explorer.assignRobot", when: "view == wpilog-mcp.logs && viewItem == wpilogUnassignedGroup", group: "inline@1" },
    { command: "wpilog-mcp.explorer.assignRobot", when: "view == wpilog-mcp.logs && viewItem == wpilogUnassigned", group: "navigation@1" },
  ]);
});


test("standalone installation has a named palette command linked from the Java setting", () => {
  const id = "wpilog-mcp.installStandaloneServer";
  assert.deepEqual(manifest.contributes.commands.find((command: { command: string }) => command.command === id), {
    command: id, title: "Install Standalone Server", category: "WPILog Analyzer",
  });
  assert.ok(!manifest.contributes.menus.commandPalette.some((item: { command: string; when: string }) =>
    item.command === id && item.when === "false"));
  assert.ok(settings["wpilog-mcp.javaPath"].markdownDescription.includes(`(command:${id})`));
});

test("one server removes obsolete settings and registers Claude Code at user scope", () => {
  assert.equal(settings["wpilog-mcp.useStandaloneServer"], undefined);
  assert.equal(settings["wpilog-mcp.idleExitMinutes"], undefined);
  assert.match(settings["wpilog-mcp.enableForClaudeCode"].markdownDescription, /user.scope/);
  assert.deepEqual(manifest.contributes.commands.find((c: { command: string }) => c.command === "wpilog-mcp.registerWithClaudeCode"),
    { command: "wpilog-mcp.registerWithClaudeCode", title: "Register with Claude Code", category: "WPILog Analyzer" });
});


test("mirror settings pin defaults and links to user controls", () => {
  for (const [key, value] of Object.entries({ pitServerUrl: "", "mirror.enabled": true, "mirror.folder": "", "mirror.days": 14,
    "mirror.maxSizeGb": 20, "mirror.robots": [], "mirror.events": [], "mirror.intervalSec": 30 })) {
    assert.deepEqual(settings[`wpilog-mcp.${key}`]?.default, value, key);
  }
  assert.match(settings["wpilog-mcp.pitServerUrl"].markdownDescription, /registerPitWithClaudeCode/);
  assert.match(settings["wpilog-mcp.mirror.enabled"].markdownDescription, /syncNow/);
});


test("the README names every pit and mirror setting and command", () => {
  const readme = fs.readFileSync(path.join(__dirname, "..", "..", "README.md"), "utf8");
  for (const key of Object.keys(settings).filter(key => key.includes("mirror.") || key.endsWith("pitServerUrl"))) assert.ok(readme.includes(`\`${key}\``), key);
  for (const id of ["pinSession", "unpinSession", "syncNow", "openMirrorFolder", "syncFromLaptop", "registerPitWithClaudeCode", "mirrorActions"]) assert.ok(readme.includes(`\`wpilog-mcp.${id}\``), id);
});

test("the mirror guide explains permanent admission for terminal use with VS Code closed", () => {
  const readme = fs.readFileSync(path.join(__dirname, "..", "..", "README.md"), "utf8");
  assert.match(readme, /With VS Code closed[\s\S]*servers\.yaml[\s\S]*--logdir/);
  assert.match(readme, /For regular offline analysis away from VS Code, use the `mirror` block/);
});


test("selection commands stay in the title and the webview context menu; links activate the handler", () => {
  for (const suffix of ["openInNotebook", "askAboutSelection"]) assert.ok(manifest.contributes.menus["editor/title"].some((m: any) => m.command === "wpilog-mcp.explorer." + suffix));
  assert.deepEqual(manifest.contributes.menus["webview/context"], [{ command: "wpilog-mcp.explorer.askAboutSelection", when: "webviewId == 'wpilog-mcp.explorer'", group: "navigation" }]);
  assert.ok(manifest.activationEvents.includes("onUri"));
});
