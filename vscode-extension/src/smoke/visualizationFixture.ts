/** Isolated Stage 1 fixture extension. It never activates the product extension or a user's server. */
import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import { randomUUID } from "node:crypto";
import { Backend, ViewSnapshot } from "../visualization/documentController";
import { VisualizationEditorProvider } from "../visualization/editorProvider";
import { RecoveryJournal } from "../visualization/recoveryJournal";
import { revealStableEditor } from "./visualizationFocus";

export const viewType = "wpilog-stage1.fixture";
export const owner = "00000000-0000-4000-8000-000000000001";
export interface NativeEvent { event: string; uri: string; [key: string]: unknown; }
export interface FixtureApi {
  provider: VisualizationEditorProvider;
  backend: Backend;
  create(saved?: boolean): Promise<vscode.Uri>;
  open(uri: vscode.Uri, column?: vscode.ViewColumn): Promise<void>;
  offline(value: boolean): void;
  journalFailure(value: boolean): void;
  holdSave(): () => void;
  backupCalls: string[];
  nativeEvents: NativeEvent[];
  trace(event: string, uri: vscode.Uri): void;
}
export function activate(context: vscode.ExtensionContext): FixtureApi {
  const root = process.env.WPILOG_VISUALIZATION_FIXTURE!;
  if (!root || !fs.existsSync(path.join(root, "fixture-only"))) throw new Error("An isolated fixture root is required");
  const stateFile = path.join(root, "fixture-views.json");
  const states: Record<string, ViewSnapshot> = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, "utf8")) : {};
  let offline = false, journalFailure = false, waitSave: (() => Promise<void>) | undefined;
  const persist = () => { const temp = stateFile + ".tmp"; fs.writeFileSync(temp, JSON.stringify(states)); fs.renameSync(temp, stateFile); };
  const backend: Backend = {
    async read(id) { if (offline) throw new Error("OFFLINE"); if (!states[id]) throw new Error("VIEW_UNAVAILABLE"); return structuredClone(states[id]); },
    async update(id, revision, configuration) {
      if (offline) throw new Error("OFFLINE");
      if (states[id].revision !== revision) throw new Error("REVISION_CONFLICT");
      states[id] = { ...states[id], revision: revision + 1, configuration: structuredClone(configuration) }; persist();
      return structuredClone(states[id]);
    },
    async save(id, revision) {
      const gate = waitSave; waitSave = undefined; await gate?.();
      if (offline) throw new Error("OFFLINE");
      if (states[id].revision !== revision) throw new Error("REVISION_CONFLICT");
      states[id] = { ...states[id], revision: revision + 1, checkpoint_configuration: structuredClone(states[id].configuration),
        saved_view_ref: { store_id: owner, saved_view_id: states[id].saved_view_ref?.saved_view_id ?? randomUUID(),
          revision: (states[id].saved_view_ref?.revision ?? 0) + 1 } }; persist();
      return structuredClone(states[id]);
    },
  };
  const journal = new RecoveryJournal(path.join(root, "journal"));
  const idFor = (uri: vscode.Uri): string => {
    if (uri.scheme !== "wpilog-view" || uri.authority !== owner || !/^\/[0-9a-f-]{36}\.wpiview$/.test(uri.path)) throw vscode.FileSystemError.FileNotFound(uri);
    return uri.path.slice(1, -8);
  };
  const nativeEvents: NativeEvent[] = [];
  const trace = (event: string, uri: vscode.Uri, snapshot: ViewSnapshot) => nativeEvents.push({
    at: Date.now(), event, uri: uri.toString(), revision: snapshot.revision, configuration: snapshot.configuration,
    windowFocused: vscode.window.state.focused,
    panels: [...provider.panels].map(([key, panel]) => ({ uri: key, active: panel.active, visible: panel.visible, column: panel.viewColumn })),
    tabs: vscode.window.tabGroups.all.flatMap(g => g.tabs).filter(t => t.input instanceof vscode.TabInputCustom)
      .map(t => ({ uri: (t.input as vscode.TabInputCustom).uri.toString(), active: t.isActive, dirty: t.isDirty,
        activeGroup: vscode.window.tabGroups.activeTabGroup.tabs.includes(t) })),
  });
  const provider = new VisualizationEditorProvider(backend, {
    write: value => journalFailure ? Promise.reject(new Error("disk full")) : journal.write(value),
    remove: id => journal.remove(id), read: id => journal.read(id),
  }, { read: uri => backend.read(idFor(uri)), trace });
  const changed = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
  const fileSystem: vscode.FileSystemProvider = {
    onDidChangeFile: changed.event, watch: () => new vscode.Disposable(() => {}),
    async stat(uri) {
      if (uri.path === "/" || uri.path === "/save-as" || uri.path === "/save-as/Fixture store") return { type: vscode.FileType.Directory, size: 0, ctime: 0, mtime: 0 };
      const view = await backend.read(idFor(uri)); return { type: vscode.FileType.File, size: 1, ctime: 0, mtime: view.revision };
    },
    async readDirectory(uri) {
      if (uri.path === "/save-as") return [["Fixture store", vscode.FileType.Directory]];
      if (uri.path === "/save-as/Fixture store") return [];
      return Object.keys(states).map(id => [id + ".wpiview", vscode.FileType.File]);
    },
    async readFile(uri) { return Buffer.from(JSON.stringify(await backend.read(idFor(uri)))); },
    writeFile() { throw vscode.FileSystemError.NoPermissions("Use Save view…"); },
    createDirectory() { throw vscode.FileSystemError.NoPermissions(); }, delete() { throw vscode.FileSystemError.NoPermissions(); },
    rename() { throw vscode.FileSystemError.NoPermissions(); },
  };
  context.subscriptions.push(provider, changed, vscode.workspace.registerFileSystemProvider("wpilog-view", fileSystem, { isCaseSensitive: true }),
    vscode.window.registerCustomEditorProvider(viewType, provider, { supportsMultipleEditorsPerDocument: false }));
  const backupCalls: string[] = [];
  const backup = provider.backupCustomDocument.bind(provider);
  provider.backupCustomDocument = async (doc, backupContext) => { backupCalls.push(doc.uri.toString()); return backup(doc, backupContext); };
  if (process.env.WPILOG_VISUALIZATION_AUTORUN === "1") {
    // Let activation return its API before the suite asks for it. Normal shutdown preserves
    // native dirty backups and editor layout; the extension test runner deliberately does not.
    setTimeout(() => { void import("./visualizationSuite").then(suite => suite.smoke()).catch(console.error)
      .finally(() => { void vscode.commands.executeCommand("workbench.action.quit"); }); }, 100);
  }
  return { provider, backend, backupCalls, nativeEvents,
    trace: (event, uri) => { trace(event, uri, provider.documents.get(uri.toString())!.controller.snapshot); },
    async create(saved = false) {
      const id = randomUUID(); const configuration = { schema_version: 1, requirements: [], panels: [], layout: { columns: 12, items: [] },
        links: { cursor: true, time_range: true }, time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } } };
      states[id] = { view_id: id, revision: 1, configuration, checkpoint_configuration: saved ? structuredClone(configuration) : null,
        saved_view_ref: saved ? { store_id: owner, saved_view_id: randomUUID(), revision: 1 } : null }; persist();
      return vscode.Uri.parse(`wpilog-view://${owner}/${id}.wpiview`);
    },
    async open(uri, column = vscode.ViewColumn.One) {
      const active = () => {
        const tab = vscode.window.tabGroups.activeTabGroup.activeTab;
        return tab?.input instanceof vscode.TabInputCustom && tab.input.uri.toString() === uri.toString()
          && provider.panels.get(uri.toString())?.active === true && provider.ready.has(uri.toString());
      };
      let opened = false;
      await revealStableEditor({
        async reveal() {
          if (!opened) {
            await vscode.commands.executeCommand("vscode.openWith", uri, viewType, { viewColumn: column, preview: false });
            opened = true;
          } else {
            const snapshot = provider.documents.get(uri.toString())?.controller.snapshot;
            if (snapshot) trace("focus:retry", uri, snapshot);
            const panel = provider.panels.get(uri.toString());
            panel?.reveal(panel.viewColumn, false);
          }
        },
        active, now: () => performance.now(), pause: ms => new Promise(resolve => setTimeout(resolve, ms)),
        async acknowledge(remainingMs) {
          const panel = provider.panels.get(uri.toString())!, token = randomUUID();
          trace("focus:probe", uri, provider.documents.get(uri.toString())!.controller.snapshot);
          await new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => { subscription.dispose(); reject(new Error("Timed out: fixture webview focus acknowledgement")); }, remainingMs);
            const subscription = panel.webview.onDidReceiveMessage(message => {
              if (message.type !== "focus-ack" || message.token !== token) return;
              clearTimeout(timer); subscription.dispose(); resolve();
            });
            void panel.webview.postMessage({ type: "focus-probe", token }).then(delivered => {
              if (!delivered) { clearTimeout(timer); subscription.dispose(); reject(new Error("Fixture focus probe reached a disposed webview")); }
            }, error => { clearTimeout(timer); subscription.dispose(); reject(error); });
          });
        },
      });
      trace("focus:ack", uri, provider.documents.get(uri.toString())!.controller.snapshot);
    },
    offline(value) { offline = value; }, journalFailure(value) { journalFailure = value; },
    holdSave() { let release!: () => void; const gate = new Promise<void>(r => { release = r; }); waitSave = () => gate; return release; },
  };
}
