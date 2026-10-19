/** Exercise Entries commands through the extension glue, without opening a user's editor. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import type * as vscode from "vscode";
import type { ServerManager } from "../serverManager";
import { LocalControlError } from "../mcpClient";
import { remoteLogUri } from "../explorer/pitServer";

const disposable = () => ({ dispose() {} });
let dialogs = 0;
const commands = new Map<string, (...args: any[]) => any>();
const calls: string[] = [];
const views = new Map<string, unknown>();
const host = {
  TabInputCustom: class { constructor(public uri: any, public viewType: string) {} },
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
    createTreeView: () => ({ dispose() {}, onDidCollapseElement: disposable }),
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
  const context = { extension: { packageJSON: { version: "test" } }, globalStorageUri: { fsPath: "unused-entries-fixture" }, globalState: { get: () => undefined }, extensionUri: {}, workspaceState: {
    get: (key: string) => saved.get(key),
    update: async (key: string, value: unknown) => { saved.set(key, value); },
  } };
  return new Explorer(context as unknown as vscode.ExtensionContext,
    { appendLine() {} } as unknown as vscode.OutputChannel,
    {} as ServerManager, () => ({ name: "http", url: "http://localhost:2363/mcp" }) as any);
}

test("Logs roots are collapsed choices and do not start either server", async () => {
  const explorer = fixture();
  try {
    explorer.connections.configure([{ id: "local", label: "Laptop", kind: "local" }, { id: "pit", label: "Pit", kind: "remote", url: "http://pit/mcp" }]);
    explorer.clientFor = async () => assert.fail("Collapsed root connected");
    const roots = await explorer.logs.getChildren();
    assert.deepEqual(roots.map(item => item.label), ["Laptop", "Pit"]);
    assert.ok(roots.every(item => item.collapsibleState === host.TreeItemCollapsibleState.Collapsed));
  } finally { explorer.dispose(); }
});
test("expanding a selected connection lists all of its scopes without connecting another source", async () => {
  const explorer = fixture();
  try {
    explorer.connections.configure([{ id: "pit", label: "Pit", kind: "remote", url: "http://pit/mcp" }, { id: "offline", label: "Offline", kind: "remote", url: "http://offline/mcp" }]);
    const requested: string[] = [];
    (explorer.connections as any).context = (id: string) => { requested.push(id); return { definition: { kind: "remote" }, facts: { server_instance_id: "owner" },
      scopes: async () => [{ scope_id: "mirror", path: "/mirror", kind: "mirror", available: false, reason: "Unavailable" }, { scope_id: "logs", path: "/logs", kind: "directory", available: true }] }; };
    const roots = await explorer.logs.getChildren(), scopes = await explorer.logs.getChildren(roots[0]);
    assert.deepEqual(requested, ["pit"]); assert.deepEqual(scopes.map(item => item.spec.scopeId), ["mirror", "logs"]);
    assert.ok(scopes.every(item => item.collapsibleState === host.TreeItemCollapsibleState.Collapsed));
    assert.equal(scopes[0].description, "Unavailable");
  } finally { explorer.dispose(); }
});

test("Folder synchronization reads local ingestion only and preserves scope roots", async () => {
  const explorer = fixture();
  const local = { id: "local", label: "Laptop", kind: "local" } as const;
  const remote = { id: "pit", label: "Pit", kind: "remote", url: "http://pit/mcp" } as const;
  const requests: string[] = [];
  try {
    explorer.connections.configure([local, remote]);
    (explorer.connections as any).context = (id: string) => ({ definition: id === "local" ? local : remote,
      facts: { server_instance_id: "owner" }, scopes: async () => [{ scope_id: "logs", path: "/logs", kind: "directory", available: true }],
      client: async () => ({ requestLocalControl: async (route: string) => {
        assert.equal(id, "local", "Remote browsing must never call the local-ingestion route"); requests.push(route);
        return route.includes("/files?") ? { files: [{ path: "/logs/waiting.wpilog", state: "waiting", reason: "Still growing" }], offset: 0, limit: 100, total: 1 }
          : { policy: { revision: 1, store: "/store", store_id: "store", enabled: true, sources: [] }, pending: 1 };
      } }),
    });
    const roots = await explorer.logs.getChildren();
    const remoteChildren = await explorer.logs.getChildren(roots[1]);
    assert.deepEqual(remoteChildren.map(item => item.spec.scopeId), ["logs"]);
    assert.deepEqual(requests, []);
    const children = await explorer.logs.getChildren(roots[0]);
    assert.equal(children[0].spec.scopeId, "logs");
    const sync = children.find(item => item.node.kind === "sourceSync");
    assert.ok(sync, "Local ingestion must produce the Folder synchronization group");
    assert.match(sync.tooltip as string, /\/store\nEnabled/);
    assert.deepEqual((await explorer.logs.getChildren(sync)).map(item => item.label), ["waiting.wpilog · waiting"]);
    assert.deepEqual(requests, ["/store/local-ingestion", "/store/local-ingestion/files?offset=0&limit=100"]);
    explorer.logs.setFilter("growing");
    assert.ok((await explorer.logs.getChildren(roots[0])).some(item => item.node.kind === "sourceSync"));
  } finally { explorer.dispose(); }
});

test("ingestion errors stay visible without hiding healthy scopes and cancelled reads stay hidden", async () => {
  for (const mode of ["no-policy", "disabled", "status-error", "files-error", "cancelled"]) {
    const explorer = fixture(); const definition = { id: "local", label: "Laptop", kind: "local" } as const;
    let fileReads = 0;
    try {
      explorer.connections.configure([definition]);
      (explorer.connections as any).context = () => ({ definition, facts: { server_instance_id: "owner" },
        scopes: async () => [{ scope_id: "logs", path: "/logs", kind: "directory", available: true }],
        client: async () => ({ requestLocalControl: async (route: string) => {
          if (route.includes("/files?")) { fileReads++; throw new Error("Receipt read failed"); }
          if (mode === "disabled") throw new LocalControlError("Disabled", 409, "FEATURE_DISABLED");
          if (mode === "status-error") throw new Error("Status read failed");
          if (mode === "cancelled") explorer.logs.refresh();
          return { policy: mode === "no-policy" ? null : { revision: 1, store: "/store", store_id: "store", enabled: false, sources: [] } };
        } }),
      });
      const root = (await explorer.logs.getChildren())[0], nodes = await explorer.logs.getChildren(root);
      if (mode === "cancelled") { assert.deepEqual(nodes, []); assert.equal(fileReads, 0); continue; }
      assert.equal(nodes[0].spec.scopeId, "logs");
      assert.equal(nodes.length, mode.endsWith("error") ? 2 : 1);
      if (mode === "status-error") assert.match(nodes[1].label as string, /Status read failed/);
      if (mode === "files-error") {
        assert.equal(nodes[1].node.kind, "sourceSync");
        assert.match((await explorer.logs.getChildren(nodes[1]))[0].label as string, /Receipt read failed/);
        assert.equal(nodes[1].tooltip, "/store\nPaused");
      }
      assert.equal(fileReads, mode === "files-error" ? 1 : 0);
    } finally { explorer.dispose(); }
  }
});

test("the obsolete all-source toggle is not registered", () => {
  const explorer = fixture();
  try { assert.equal(commands.has("wpilog-mcp.explorer.showAllSources"), false); }
  finally { explorer.dispose(); }
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
  const selected = { definition: { id: "local", kind: "local" }, scopeId: "scope" };
  (host.window.tabGroups.activeTabGroup as any).activeTab = { input: new host.TabInputCustom({ scheme: "wpilog-view", query: "connection=local&scope=scope" }, "wpilog-mcp.visualization") };
  const route = (file: string, target: string) => (explorer.preview as any).host.entries(selected, file, target);
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

test("the ordinary editor retains the selected remote listing's follow availability", async () => {
  const explorer = fixture(), messages: any[] = [];
  const spec = { kind: "pit", name: "connection:pit:scope", url: "http://pit/mcp", connectionId: "pit", scopeId: "scope", owner: "owner" } as const;
  explorer.logs.listing = async selected => { assert.equal(selected, spec); return { logs: [{ path: "/remote/current.wpilog", filename: "current.wpilog", friendly_name: "Current", session: { id: "s", path: "/remote", started_at: "", ended_at: "", start_basis: "pit_clock", open: true, open_capture: "/remote/current.wpilog" } }] }; };
  explorer.clientFor = async selected => { assert.equal(selected, spec); return { callTool: async () => ({ entries: [], entry_count: 0 }) } as any; };
  try {
    await (explorer.editor as any).load({ log: { path: "/remote/current.wpilog", spec }, panel: { active: false, webview: { postMessage: async (message: any) => messages.push(message) } } });
    assert.equal(messages.find(message => message.type === "log")?.live, true);
  } finally { explorer.dispose(); }
});
