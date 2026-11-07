import { test } from "node:test";
import * as assert from "node:assert/strict";
import type * as vscode from "vscode";
import type { EntriesProvider, EntryItem } from "../explorer";

const commands: { name: string; args: unknown[] }[] = [];
const host = {
  TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },
  Uri: { joinPath: (base: { toString(): string }, ...parts: string[]) => uri(`${base.toString()}/${parts.join("/")}`) },
  commands: { executeCommand: async (name: string, ...args: unknown[]) => { commands.push({ name, args }); } },
};
const loader = require("node:module") as { _load: (...args: any[]) => any };
const originalLoad = loader._load;
let EntriesView: typeof import("../explorer/entriesView").EntriesView;
try {
  loader._load = (name, ...args) => name === "vscode" ? host : originalLoad(name, ...args);
  EntriesView = require("../explorer/entriesView").EntriesView;
} finally { loader._load = originalLoad; }

function uri(value: string): vscode.Uri { return { toString: () => value } as vscode.Uri; }
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const drain = () => new Promise<void>(resolve => setImmediate(resolve));

function entry(name: string, expandable = false): EntryItem {
  return { node: { kind: "entry", label: name, description: "double · 5", tooltip: name,
    entry: { name, type: expandable ? "struct:Pose" : "double", sample_count: 5 }, expandable },
    label: name, description: "double · 5", tooltip: name, contextValue: "wpilogEntry",
    collapsibleState: expandable ? 1 : 0,
    command: { command: expandable ? "wpilog-mcp.explorer.showEntry" : "wpilog-mcp.explorer.plotEntry", title: "Open", arguments: [name] },
  } as EntryItem;
}
function group(label: string, expanded = false): EntryItem {
  return { node: { kind: "group", label, path: `/${label}`, children: [] }, label,
    tooltip: `/${label}`, collapsibleState: expanded ? 2 : 1 } as EntryItem;
}
function field(pooled = false): EntryItem {
  return { node: { kind: "field", label: pooled ? "[*].x" : ".x", description: "double", fieldPath: pooled ? "[*].x" : ".x",
    entry: { name: "/Pose", type: "struct:Pose[]", sample_count: 5 }, ...(pooled ? { elements: 2 } : {}) },
    label: pooled ? "[*].x" : ".x", contextValue: pooled ? "wpilogFieldPooled" : "wpilogField",
    collapsibleState: pooled ? 1 : 0,
    command: { command: pooled ? "wpilog-mcp.explorer.showEntry" : "wpilog-mcp.explorer.plotEntry", title: "Open", arguments: [pooled ? "/Pose" : "/Pose.x"] },
  } as EntryItem;
}

function fixture() {
  commands.length = 0;
  const messages: any[] = [];
  const listeners = new Set<() => void>();
  const childMap = new Map<EntryItem, EntryItem[]>();
  const reads: (EntryItem | undefined)[] = [];
  let filter = "";
  let grouping: "path" | "type" = "path";
  let filterResetVersion = 0;
  let roots: EntryItem[] = [];
  let read: ((item?: EntryItem) => Promise<EntryItem[]>) | undefined;
  let receive: ((message: unknown) => unknown) | undefined;
  let viewDispose: (() => void) | undefined;
  let shows = 0;
  const source = {
    get filterText() { return filter; },
    get filterResetVersion() { return filterResetVersion; },
    get grouping() { return grouping; },
    onDidChangeTreeData: (listener: () => void) => { listeners.add(listener); return { dispose: () => listeners.delete(listener) }; },
    setFilter: (value: string) => { filter = value; filterResetVersion++; for (const listener of listeners) listener(); },
    setGrouping: (value: "path" | "type", query = filter) => {
      grouping = value;
      if (query !== filter) { filter = query; filterResetVersion++; }
      for (const listener of listeners) listener();
    },
    getChildren: async (item?: EntryItem) => { reads.push(item); return read ? read(item) : item ? childMap.get(item) ?? [] : roots; },
  } as unknown as EntriesProvider;
  const view = {
    webview: {
      cspSource: "vscode-resource:", options: {}, html: "",
      asWebviewUri: (value: vscode.Uri) => value,
      postMessage: async (message: unknown) => { messages.push(structuredClone(message)); return true; },
      onDidReceiveMessage: (listener: (message: unknown) => unknown) => {
        receive = listener; return { dispose: () => { receive = undefined; } };
      },
    },
    onDidDispose: (listener: () => void) => { viewDispose = listener; return { dispose: () => { viewDispose = undefined; } }; },
    show: (preserveFocus: boolean) => { assert.equal(preserveFocus, true); shows++; },
  } as unknown as vscode.WebviewView;
  const bridge = new EntriesView(source, uri("extension:/wpilog"));
  return {
    bridge, source, view, messages, childMap, reads,
    set roots(value: EntryItem[]) { roots = value; },
    set read(value: (item?: EntryItem) => Promise<EntryItem[]>) { read = value; },
    get shows() { return shows; },
    get listeners() { return listeners.size; },
    get disposedView() { return !receive && !viewDispose; },
    refresh: (reset = false) => { if (reset) filterResetVersion++; for (const listener of listeners) listener(); },
    resolve: () => bridge.resolveWebviewView(view),
    send: async (message: Record<string, unknown>) => {
      receive?.((message.type === "filter" || message.type === "grouping") && message.generation === undefined
        ? { ...message, generation: messages.filter(value => value.type === "loading").at(-1)?.generation }
        : message);
      await drain();
    },
    latest: (type: string) => messages.filter(message => message.type === type).at(-1),
  };
}

test("Entries bridge opens ordinary root groups, searches nested groups, and keeps struct fields lazy", async () => {
  const f = fixture();
  const root = group("Robot", true), nested = group("Drive"), pose = entry("/Robot/Drive/Pose", true);
  f.roots = [root]; f.childMap.set(root, [nested]); f.childMap.set(nested, [pose]); f.childMap.set(pose, [field()]);
  f.resolve(); await f.send({ type: "ready" });
  let tree = f.latest("tree");
  assert.equal(tree.nodes[0].expanded, true);
  assert.equal(tree.nodes[0].children[0].expanded, false);
  assert.equal(tree.nodes[0].children[0].children, undefined);
  assert.deepEqual(f.reads, [undefined, root]);
  await f.send({ type: "filter", value: "Pose" });
  tree = f.latest("tree");
  assert.equal(f.source.filterText, "Pose"); assert.equal(tree.filter, "Pose");
  assert.equal(tree.nodes[0].children[0].expanded, true);
  const node = tree.nodes[0].children[0].children[0];
  assert.equal(node.label, "/Robot/Drive/Pose"); assert.equal(node.expanded, false);
  assert.equal(node.children, undefined);
  assert.equal(f.reads.includes(pose), false, "searching must not request every entry's struct schema");
  await f.send({ type: "expand", id: node.id, generation: tree.generation });
  assert.equal(f.latest("children").nodes[0].label, ".x");
  assert.equal(f.reads.filter(item => item === pose).length, 1);
  await f.send({ type: "expand", id: node.id });
  assert.equal(f.reads.filter(item => item === pose).length, 1, "repeat expansion shares the provider request");
  f.bridge.dispose();
});

test("Entries bridge admits only current mapped nodes and keeps existing command arguments", async () => {
  const f = fixture(); const scalar = entry("/Voltage"), pooled = field(true), leaf = field();
  f.roots = [scalar, pooled, leaf, group("Robot")]; f.resolve(); await f.send({ type: "ready" });
  const tree = f.latest("tree"), [voltage, all, one, folder] = tree.nodes;
  assert.deepEqual(tree.nodes.map((node: any) => [node.details, node.plotElements]), [[true, false], [true, true], [true, false], [false, false]]);
  await f.send({ type: "activate", id: voltage.id, command: "untrusted.command", arguments: ["untrusted"] });
  await f.send({ type: "details", id: one.id });
  await f.send({ type: "plotElements", id: all.id });
  assert.deepEqual(commands, [
    { name: "wpilog-mcp.explorer.plotEntry", args: ["/Voltage"] },
    { name: "wpilog-mcp.explorer.showEntry", args: ["/Pose"] },
    { name: "wpilog-mcp.explorer.plotElements", args: [pooled] },
  ]);
  await f.send({ type: "activate", id: "made-up" });
  await f.send({ type: "activate", id: voltage.id, generation: tree.generation - 1 });
  await f.send({ type: "details", id: folder.id }); await f.send({ type: "plotElements", id: one.id });
  assert.equal(commands.length, 3);
  f.source.setFilter("new"); await f.send({ type: "activate", id: voltage.id });
  assert.equal(commands.length, 3, "a row from the previous search cannot act on the new log");
  assert.notEqual(f.latest("tree").nodes[0].id, voltage.id);
  f.bridge.dispose();
});

test("out-of-order root and field replies cannot restore an old search or log", async () => {
  const f = fixture(); const oldRoot = deferred<EntryItem[]>(), oldFields = deferred<EntryItem[]>();
  let first = true; const current = entry("/Current", true);
  f.read = async item => item ? oldFields.promise : first ? (first = false, oldRoot.promise) : [current];
  f.resolve(); await f.send({ type: "ready" }); await f.send({ type: "filter", value: "Current" });
  const tree = f.latest("tree"); oldRoot.resolve([entry("/Old")]); await drain();
  assert.equal(f.messages.filter(message => message.type === "tree").length, 1);
  await f.send({ type: "expand", id: tree.nodes[0].id });
  f.source.setFilter("Another log"); await drain(); oldFields.resolve([field()]); await drain();
  assert.equal(f.messages.filter(message => message.type === "children").length, 0);
  assert.equal(f.latest("tree").filter, "Another log");
  assert.equal(f.reads.filter(item => item === current).length, 1);
  f.bridge.dispose();
});

test("filter focus waits for the page and collapse all also covers in-flight group expansion", async () => {
  const f = fixture(); const root = group("Robot", true), children = deferred<EntryItem[]>();
  f.read = async item => item ? children.promise : [root];
  await f.bridge.focusFilter();
  assert.deepEqual(commands, [{ name: "wpilog-mcp.entries.focus", args: [] }]);
  f.resolve(); await f.send({ type: "ready" }); assert.ok(f.latest("focusFilter"));
  await f.bridge.focusFilter(); assert.equal(f.shows, 1); assert.equal(commands.length, 1);
  await f.bridge.collapseAll(); children.resolve([entry("/Robot/Voltage")]); await drain();
  assert.ok(f.latest("collapseAll")); assert.equal(f.latest("tree").nodes[0].expanded, false);
  f.bridge.dispose();
});

test("provider errors reach the view, stale failures do not, and dispose detaches all listeners", async () => {
  const f = fixture(); const old = deferred<EntryItem[]>(); let first = true;
  f.read = async () => first ? (first = false, old.promise) : Promise.reject(new Error("Current listing failed"));
  f.resolve(); await f.send({ type: "ready" }); f.source.setFilter("current"); await drain();
  assert.equal(f.latest("error").message, "Current listing failed");
  old.reject(new Error("Old listing failed")); await drain();
  assert.equal(f.messages.filter(message => message.type === "error").length, 1);
  f.bridge.dispose(); assert.equal(f.listeners, 0); assert.ok(f.disposedView);
  const count = f.messages.length;
  f.source.setFilter("disposed"); await f.send({ type: "ready" });
  await f.bridge.focusFilter(); await f.bridge.collapseAll();
  assert.equal(f.messages.length, count); assert.equal(commands.length, 0);
});

test("only authoritative log and filter changes reset the page's pending search input", async () => {
  const f = fixture(); f.roots = [entry("/Voltage")]; f.resolve(); await f.send({ type: "ready" });
  assert.equal(f.latest("loading").resetFilter, true);
  assert.equal(f.latest("tree").resetFilter, true);
  await f.send({ type: "filter", value: "Voltage" });
  assert.equal(f.latest("loading").resetFilter, false);
  assert.equal(f.latest("tree").resetFilter, false);
  f.refresh(); await drain();
  assert.equal(f.latest("loading").resetFilter, false, "same-log refresh preserves a not-yet-submitted edit");
  f.source.setFilter(""); await drain();
  assert.equal(f.latest("loading").resetFilter, true, "the external clear command replaces the page's query");
  assert.equal(f.latest("loading").filter, "");
  const generation = f.latest("loading").generation;
  f.source.setFilter(""); await drain();
  assert.equal(f.latest("loading").resetFilter, true, "clear also cancels pending typing while source was already blank");
  assert.ok(f.latest("loading").generation > generation);
  f.refresh(true); await drain();
  assert.equal(f.latest("tree").resetFilter, true, "switching between unfiltered logs also cancels pending typing");
  f.bridge.dispose();
});

test("a filter already sent before a log switch or external clear cannot replace the new query", async () => {
  const f = fixture(); f.roots = [entry("/Voltage")]; f.resolve(); await f.send({ type: "ready" });
  const initial = f.latest("tree").generation;
  await f.send({ type: "filter", value: "V", generation: initial });
  await f.send({ type: "filter", value: "Voltage", generation: initial });
  assert.equal(f.source.filterText, "Voltage", "rapid typing remains valid before its loading replies arrive");
  const old = f.latest("tree").generation;
  f.source.setFilter(""); await drain();
  const cleared = f.latest("tree").generation;
  await f.send({ type: "filter", value: "Old delayed query", generation: old });
  assert.equal(f.source.filterText, "");
  assert.equal(f.latest("tree").generation, cleared, "an old message must not issue a fresh listing");
  await f.send({ type: "filter", value: "New query", generation: cleared });
  assert.equal(f.source.filterText, "New query");
  const beforeSwitch = f.latest("tree").generation;
  f.refresh(true); await drain();
  await f.send({ type: "filter", value: "Previous log query", generation: beforeSwitch });
  await f.send({ type: "filter", value: "Missing context", generation: null });
  await f.send({ type: "filter", value: "Future context", generation: beforeSwitch + 100 });
  assert.equal(f.source.filterText, "New query");
  f.bridge.dispose();
});

test("both Collapse All controls stay collapsed on live refresh until the log or query changes", async () => {
  const f = fixture(); const root = group("Robot", true);
  f.roots = [root]; f.childMap.set(root, [entry("/Robot/Voltage")]); f.resolve(); await f.send({ type: "ready" });
  await f.bridge.collapseAll(); f.refresh(); await drain();
  assert.equal(f.latest("tree").nodes[0].expanded, false, "live updates retain title Collapse All");
  await f.send({ type: "filter", value: "Voltage" });
  assert.equal(f.latest("tree").nodes[0].expanded, true, "new searches show their matching hierarchy");
  await f.send({ type: "collapseAll", generation: f.latest("tree").generation });
  f.refresh(); await drain();
  assert.equal(f.latest("tree").nodes[0].expanded, false, "live updates retain inline Collapse All");
  f.refresh(true); await drain();
  assert.equal(f.latest("tree").nodes[0].expanded, true, "a different log restores its initial hierarchy");
  await f.send({ type: "collapseAll", generation: f.latest("tree").generation - 1 });
  f.refresh(); await drain();
  assert.equal(f.latest("tree").nodes[0].expanded, true, "an old page cannot collapse the new log");
  f.bridge.dispose();
});

test("grouping shares the current query, invalidates old rows, and rejects a previous log's choice", async () => {
  const f = fixture(); f.roots = [entry("/Drive/Speed")]; f.resolve(); await f.send({ type: "ready" });
  const initial = f.latest("tree");
  assert.equal(initial.grouping, "path");
  await f.send({ type: "grouping", value: "type", filter: "Drive", generation: initial.generation });
  assert.equal(f.source.grouping, "type");
  assert.equal(f.source.filterText, "Drive");
  assert.equal(f.latest("tree").grouping, "type");
  assert.equal(f.latest("loading").resetFilter, false, "a grouping change preserves newer typing");
  await f.send({ type: "activate", id: initial.nodes[0].id, generation: initial.generation });
  assert.equal(commands.length, 0, "old path rows cannot act after regrouping");
  // Rapid clicks are from the same still-visible context until the host reply arrives.
  await f.send({ type: "grouping", value: "path", filter: "Drive/Speed", generation: initial.generation });
  assert.equal(f.source.grouping, "path");
  assert.equal(f.source.filterText, "Drive/Speed");
  const beforeSwitch = f.latest("tree").generation;
  f.refresh(true); await drain();
  await f.send({ type: "grouping", value: "type", filter: "Old log query", generation: beforeSwitch });
  await f.send({ type: "grouping", value: "invalid", filter: "" });
  await f.send({ type: "grouping", value: "type", filter: 7 });
  assert.equal(f.source.grouping, "path");
  assert.equal(f.source.filterText, "Drive/Speed");
  f.bridge.dispose();
});

test("a delayed path listing cannot replace a completed type grouping", async () => {
  const f = fixture(), old = deferred<EntryItem[]>();
  let first = true;
  f.read = async () => first ? (first = false, old.promise) : [group("double")];
  f.resolve(); await f.send({ type: "ready" });
  await f.send({ type: "grouping", value: "type", filter: "" });
  const current = f.latest("tree");
  old.resolve([group("Drive")]); await drain();
  assert.equal(f.latest("tree"), current);
  assert.equal(current.grouping, "type");
  assert.equal(current.nodes[0].label, "double");
  f.bridge.dispose();
});
