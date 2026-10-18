/** Exercise Entries commands through the extension glue, without opening a user's editor. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import type * as vscode from "vscode";
import type { ServerManager } from "../serverManager";
import { remoteLogUri } from "../explorer/pitServer";

const disposable = () => ({ dispose() {} });
let dialogs = 0;
const commands = new Map<string, (...args: any[]) => any>();
const calls: string[] = [];
const views = new Map<string, unknown>();
const host = {
  TreeItem: class {
    constructor(public label: string, public collapsibleState: number) {}
  },
  TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },
  ThemeIcon: class { static Folder = {}; constructor(public id: string) {} },
  EventEmitter: class {
    private listeners: ((event: unknown) => void)[] = [];
    event = (listener: (event: unknown) => void) => {
      this.listeners.push(listener); return disposable();
    };
    fire(event: unknown) { for (const listener of this.listeners) listener(event); }
    dispose() {}
  },
  Disposable: class { dispose() {} },
  workspace: { registerFileSystemProvider: disposable, getConfiguration: () => ({ get: (_key: string, fallback: unknown) => fallback }) },
  commands: {
    registerCommand(name: string, action: (...args: any[]) => any) {
      commands.set(name, action); return disposable();
    },
    async executeCommand(name: string) { calls.push(name); },
  },
  window: {
    tabGroups: { activeTabGroup: {}, onDidChangeTabs: disposable, onDidChangeTabGroups: disposable },
    registerTreeDataProvider: disposable,
    registerWebviewViewProvider(id: string, provider: unknown) { views.set(id, provider); return disposable(); },
    registerCustomEditorProvider: disposable,
    registerUriHandler: disposable,
    async showInputBox() { dialogs++; return undefined; },
  },
};

const loader = require("node:module") as { _load: (...args: any[]) => any };
const originalLoad = loader._load;
let Explorer: typeof import("../explorer").Explorer;
try {
  loader._load = (name, ...args) => name === "vscode" ? host : originalLoad(name, ...args);
  Explorer = require("../explorer").Explorer;
} finally { loader._load = originalLoad; }

function fixture(savedGrouping?: unknown) {
  dialogs = 0; commands.clear(); calls.length = 0; views.clear();
  const saved = new Map<string, unknown>([["wpilog-mcp.entryGrouping", savedGrouping]]);
  const context = { globalStorageUri: { fsPath: "unused-entries-fixture" }, globalState: { get: () => undefined }, extensionUri: {}, workspaceState: {
    get: (key: string) => saved.get(key),
    update: async (key: string, value: unknown) => { saved.set(key, value); },
  } };
  return new Explorer(context as unknown as vscode.ExtensionContext,
    { appendLine() {} } as unknown as vscode.OutputChannel,
    {} as ServerManager, () => ({ name: "http", url: "http://localhost:2363/mcp" }) as any);
}

function pitUri(url: string): vscode.Uri {
  const link = remoteLogUri(url, "/shared/capture.wpilog");
  return { scheme: "wpilog-pit", query: decodeURIComponent(link.slice(link.indexOf("?") + 1)) } as vscode.Uri;
}
for (const configured of ["http://pit-b:2363/mcp", "http://pit-a:2363/other/mcp"])
  test(`pit overview refuses a document from another configured connection: ${configured}`, async () => {
    const explorer = fixture(), requested: string[] = [];
    (explorer as any).pitSpec = () => ({ kind: "pit", name: "pit", url: configured });
    explorer.logs.listing = async spec => { requested.push((spec as { url?: string }).url ?? "local"); return { logs: [] }; };
    try {
      const result = await explorer.pitOverview(pitUri("http://pit-a:2363/mcp"));
      assert.deepEqual(requested, [], "reject the origin before reading another server's catalog");
      assert.ok("message" in result); assert.match(result.message, /different pit server|changed/i);
    } finally { explorer.dispose(); }
  });
test("pit overview refuses a connection changed while its catalog request was in flight", async () => {
  const explorer = fixture(), requested: string[] = []; let url = "http://pit-a:2363/mcp";
  (explorer as any).pitSpec = () => ({ kind: "pit", name: "pit", url });
  explorer.logs.listing = async spec => { requested.push((spec as { url?: string }).url ?? "local"); url = "http://pit-b:2363/mcp"; return { logs: [] }; };
  try {
    const result = await explorer.pitOverview(pitUri("http://pit-a:2363/mcp"));
    assert.deepEqual(requested, ["http://pit-a:2363/mcp"], "do not resolve local data after the connection changes");
    assert.ok("message" in result); assert.match(result.message, /different pit server|changed/i);
  } finally { explorer.dispose(); }
});
test("pit overview rechecks the connection after reading the local catalog", async () => {
  const explorer = fixture(); let url = "http://pit-a:2363/mcp", calls = 0;
  (explorer as any).pitSpec = () => ({ kind: "pit", name: "pit", url });
  explorer.logs.listing = async () => { if (++calls === 2) url = "http://pit-b:2363/mcp"; return { logs: [] }; };
  try {
    const result = await explorer.pitOverview(pitUri("http://pit-a:2363/mcp"));
    assert.equal(calls, 2); assert.ok("message" in result); assert.match(result.message, /different pit server|changed/i);
  } finally { explorer.dispose(); }
});
test("pit overview accepts equivalent connection URLs and resolves that origin's mirror copy", async () => {
  const explorer = fixture(), requested: string[] = [];
  (explorer as any).pitSpec = () => ({ kind: "pit", name: "pit", url: "http://PIT-A:80/mcp/" });
  const session = { id: "same", path: "/shared", started_at: "", ended_at: "", start_basis: "log" };
  const remote = { path: "/shared/capture.wpilog", filename: "capture.wpilog", friendly_name: "source", session };
  const copy = { ...remote, path: "/mirror/capture.wpilog", session: { ...session, path: "/mirror", origin: "http://pit-a" } };
  explorer.logs.listing = async spec => { requested.push(spec!.kind); return { logs: [spec!.kind === "pit" ? remote : copy] }; };
  try {
    assert.deepEqual(await explorer.pitOverview(pitUri("http://pit-a/mcp")), { path: copy.path });
    assert.equal(requested.length, 2);
  } finally { explorer.dispose(); }
});

test("Entries filter focuses an inline field without opening Quick Input", async () => {
  const explorer = fixture();
  try {
    await commands.get("wpilog-mcp.explorer.filterEntries")!();
    assert.equal(dialogs, 0, "filtering must not open a command-palette-style input");
    assert.ok(calls.includes("wpilog-mcp.entries.focus"), "the Entries view receives focus");
    assert.ok(views.has("wpilog-mcp.entries"), "the inline filter belongs to Entries itself");
  } finally { explorer.dispose(); }
});

test("Entries exposes Collapse All independently of filtering", async () => {
  const explorer = fixture();
  try {
    assert.equal(typeof commands.get("wpilog-mcp.explorer.collapseEntries"), "function");
    await commands.get("wpilog-mcp.explorer.collapseEntries")!();
    assert.equal(dialogs, 0);
  } finally { explorer.dispose(); }
});

test("Entries filtering sends the full path to the server before any branch is opened", async () => {
  const explorer = fixture();
  const requests: unknown[] = [];
  (explorer as any).clientFor = async () => ({ callTool: async (tool: string, args: unknown) => {
    requests.push([tool, args]);
    return { entries: [{ name: "/Drive/Odometry/Position", type: "double", sample_count: 3 }] };
  } });
  try {
    explorer.entries.setActive({ path: "/synthetic.wpilog", spec: { name: "http" } as any,
      listing: { entries: [{ name: "/Unrelated/Current", type: "double", sample_count: 2 }] } });
    explorer.entries.setFilter("Drive/Odometry");
    const roots = await explorer.entries.getChildren();
    assert.deepEqual(requests, [["list_entries", { path: "/synthetic.wpilog", pattern: "Drive/Odometry" }]]);
    assert.equal(roots[0].node.label, "Drive");
    const groups = await explorer.entries.getChildren(roots[0]);
    const leaves = await explorer.entries.getChildren(groups[0]);
    assert.equal(leaves[0].node.label, "Position");
    explorer.entries.setFilter("");
    assert.equal((await explorer.entries.getChildren())[0].node.label, "Unrelated");
    assert.equal(requests.length, 1, "clearing restores the log's original listing");
  } finally { explorer.dispose(); }
});

test("Entries keeps focus changes inert but refreshes live listings and resets new-log searches", () => {
  const explorer = fixture();
  let refreshes = 0;
  explorer.entries.onDidChangeTreeData(() => { refreshes++; });
  const log = { path: "/synthetic.wpilog", spec: { name: "http" } as any, listing: { entries: [] } };
  try {
    explorer.entries.setActive(log);
    explorer.entries.setFilter("Drive");
    const version = explorer.entries.filterResetVersion;
    explorer.entries.setActive(log);
    assert.equal(refreshes, 2, "returning editor focus does not rebuild the tree");
    assert.equal(explorer.entries.filterText, "Drive");
    log.listing = { entries: [] };
    explorer.entries.setActive(log);
    assert.equal(refreshes, 3, "a new live listing still refreshes");
    assert.equal(explorer.entries.filterResetVersion, version);
    explorer.entries.setFilter("");
    explorer.entries.setFilter("");
    assert.equal(explorer.entries.filterResetVersion, version + 2,
      "even a repeated explicit clear cancels a pending unsent query");
    explorer.entries.setActive({ ...log, path: "/another.wpilog" });
    assert.equal(explorer.entries.filterResetVersion, version + 3);
  } finally { explorer.dispose(); }
});

test("type grouping retains full signal names, lazy fields, and the selected mode across logs", async () => {
  const explorer = fixture("type");
  const requests: [string, any][] = [];
  (explorer as any).clientFor = async () => ({ callTool: async (tool: string, args: any) => {
    requests.push([tool, args]);
    return tool === "get_entry_info" ? { numeric_leaf_paths: [".x"] }
      : { entries: [{ name: "/Arm/State", type: "struct:Pose", sample_count: 2 }] };
  } });
  try {
    explorer.entries.setActive({ path: "/synthetic.wpilog", spec: { name: "http" } as any,
      listing: { entries: [
        { name: "/Drive/State", type: "double", sample_count: 4 },
        { name: "/Arm/State", type: "struct:Pose", sample_count: 2 },
      ] } });
    assert.equal(explorer.entries.grouping, "type", "restore the workspace's choice");
    const roots = await explorer.entries.getChildren();
    assert.deepEqual(roots.map(root => root.label), ["double", "struct:Pose"]);
    assert.ok(roots.every(root => root.collapsibleState === host.TreeItemCollapsibleState.Collapsed));
    const entries = await explorer.entries.getChildren(roots[1]);
    assert.equal(entries[0].label, "/Arm/State");
    assert.equal(requests.length, 0, "grouping does not request struct schemas");
    const fields = await explorer.entries.getChildren(entries[0]);
    assert.equal(fields[0].label, ".x");
    assert.deepEqual(requests, [["get_entry_info", { path: "/synthetic.wpilog", name: "/Arm/State" }]]);
    explorer.entries.setGrouping("path", "Arm");
    assert.equal(explorer.entries.filterText, "Arm", "the pending inline query and mode change together");
    assert.equal((await explorer.entries.getChildren())[0].label, "Arm");
    assert.deepEqual(requests[1], ["list_entries", { path: "/synthetic.wpilog", pattern: "Arm" }]);
    explorer.entries.setGrouping("type");
    explorer.entries.setActive({ path: "/next.wpilog", listing: { entries: [] } });
    assert.equal(explorer.entries.grouping, "type");
    assert.equal(explorer.entries.filterText, "", "the query still resets for a different log");
  } finally { explorer.dispose(); }
});

test("grouping changes are saved to workspace state and unknown saved values default to path", async () => {
  const explorer = fixture("obsolete-choice");
  const saved: [string, unknown][] = [];
  // This is the same context used by the provider, kept separate from shared global preferences.
  const state = (explorer as any).context.workspaceState;
  const update = state.update;
  state.update = async (key: string, value: unknown) => { saved.push([key, value]); await update(key, value); };
  try {
    assert.equal(explorer.entries.grouping, "path");
    explorer.entries.setGrouping("type");
    explorer.entries.setGrouping("type");
    explorer.entries.setGrouping("path");
    assert.deepEqual(saved, [["wpilog-mcp.entryGrouping", "type"], ["wpilog-mcp.entryGrouping", "path"]]);
    assert.equal(state.get("wpilog-mcp.entryGrouping"), "path");
  } finally { explorer.dispose(); }
});

test("a hidden legacy editor cannot reclaim Entries or receive a later Entries command", async () => {
  const explorer = fixture();
  const provider: any = explorer.editor;
  let activations = 0, reveals = 0;
  explorer.entries.setActive = () => { activations++; };
  (explorer as any).clientFor = async () => ({ callTool: async () => ({ entries: [] }) });
  const editor = { log: { path: "/fixture.wpilog", spec: { name: "http" } }, panel: { active: false, visible: false,
    reveal: () => { reveals++; }, webview: { postMessage: () => true } } };
  provider.editors.set("hidden", editor); provider.activeKey = "hidden";
  try {
    await provider.load(editor); provider.plotEntry("/v"); provider.selectEntry("/v");
    assert.equal(activations, 0); assert.equal(reveals, 0);
  } finally { explorer.dispose(); }
});

test("returning from the legacy editor restores the preview Entries target without resetting its filter", () => {
  const explorer = fixture();
  const route = (explorer.preview as any).host.entries;
  try {
    route("/a.wpilog", "A · Plot");
    explorer.entries.setActive({ path: "/b.wpilog", spec: { name: "http" } as any });
    route("/a.wpilog", "A · Plot");
    assert.equal((explorer.entries as any).active.path, "/a.wpilog");
    assert.equal(explorer.entries.targetLabel, "A · Plot");
    explorer.entries.setFilter("Drive");
    route("/a.wpilog", "A · Plot");
    assert.equal(explorer.entries.filterText, "Drive", "repeated focus in the same target is inert");
  } finally { explorer.dispose(); }
});
