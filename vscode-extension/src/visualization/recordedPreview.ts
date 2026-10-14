import * as vscode from "vscode";
import { randomBytes, randomUUID } from "node:crypto";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { McpClient } from "../mcpClient";
import { runImport } from "../explorer/importJobs";
import { DataClient } from "../dataClient";
import { VisualizationEditorProvider, ViewDocument } from "./editorProvider";
import { RecoveryJournal } from "./recoveryJournal";
import { RecordedClient, SourceSummary, StoreChoice } from "./recordedClient";
import { addPanel, addSignals, Inventory, layoutEdit, LayoutAction, RecordedConfiguration, RecordedView, Signal, SourceRef, ViewGeneration, recordedDataKey, requireRecordedPreview } from "./recordedModel";
import { recordedPage } from "./recordedPage";

export const OVERVIEW_TYPE = "wpilog-mcp.sourceOverview", VIEW_TYPE = "wpilog-mcp.visualization";
export interface PreviewHost {
  context: vscode.ExtensionContext;
  client(): Promise<McpClient>;
  entries(path: string | undefined, target: string): void;
  grantStore(path: string): Promise<void>;
}
interface ViewPage {
  document: ViewDocument; panel: vscode.WebviewPanel; generation: ViewGeneration; key: string;
  inventory?: Inventory; activePanel?: string; ready: boolean; closed: boolean;
  serial: Promise<void>; cursor?: number; cursorGeneration: number; valueAbort?: AbortController;
  dataKey?: string; rendering?: Promise<void>;
}
interface OverviewPage { uri: vscode.Uri; panel: vscode.WebviewPanel; summary?: SourceSummary; ready: boolean; closed: boolean; abort: AbortController; }

/** Preview ownership is per working UUID. Entries never routes through a remembered hidden view. */
export class RecordedPreview implements vscode.Disposable {
  readonly backend: RecordedClient;
  readonly provider: VisualizationEditorProvider;
  readonly rendered = new Map<string, { plots: number; rows: number; width: number; height: number }>();
  private readonly pages = new Map<string, ViewPage>();
  private readonly overviews = new Map<string, OverviewPage>();
  private readonly disposables: vscode.Disposable[] = [];
  private readonly data = new DataClient(0, 0, undefined, 1024 * 1024);
  private owner?: string;
  private stateWarning?: string;
  private entriesOwned = false;
  constructor(private host: PreviewHost) {
    this.backend = new RecordedClient(() => host.client());
    this.provider = new VisualizationEditorProvider(this.backend,
      new RecoveryJournal(path.join((host.context.storageUri ?? host.context.globalStorageUri).fsPath, "visualization-drafts")), {
        read: async uri => { const id = await this.idFor(uri); return this.backend.attach(id); },
        render: (doc, panel) => this.render(doc, panel), paint: doc => this.paint(doc),
        closed: doc => { const id = doc.controller.snapshot.view_id; this.closePage(id); void this.backend.detach(id).catch(() => {}); },
      });
    const changed = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
    this.disposables.push(changed, this.provider,
      vscode.workspace.registerFileSystemProvider("wpilog-view", {
        onDidChangeFile: changed.event, watch: () => new vscode.Disposable(() => {}),
        stat: async uri => { await this.idFor(uri); return { type: vscode.FileType.File, size: 0, ctime: 0, mtime: 0 }; },
        readFile: async uri => Buffer.from(JSON.stringify(await this.backend.read(await this.idFor(uri)))),
        readDirectory: () => [], writeFile: () => { throw vscode.FileSystemError.NoPermissions("Use Save view"); },
        createDirectory: () => { throw vscode.FileSystemError.NoPermissions(); }, rename: () => { throw vscode.FileSystemError.NoPermissions(); }, delete: () => { throw vscode.FileSystemError.NoPermissions(); },
      }, { isCaseSensitive: true }),
      vscode.window.registerCustomEditorProvider(VIEW_TYPE, this.provider, { supportsMultipleEditorsPerDocument: false, webviewOptions: { retainContextWhenHidden: true } }),
      vscode.window.registerCustomEditorProvider(OVERVIEW_TYPE, {
        openCustomDocument: (uri: vscode.Uri) => ({ uri, dispose() {} }),
        resolveCustomEditor: (doc: vscode.CustomDocument, panel: vscode.WebviewPanel) => this.renderOverview(doc.uri, panel),
      }, { supportsMultipleEditorsPerDocument: false, webviewOptions: { retainContextWhenHidden: true } }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.openSourceOverview", (item?: vscode.Uri | { resourceUri?: vscode.Uri }) => this.openOverview(item instanceof vscode.Uri ? item : item?.resourceUri)),
      vscode.window.tabGroups.onDidChangeTabs(() => this.targetEntries()),
      vscode.window.tabGroups.onDidChangeTabGroups(() => this.targetEntries()),
    );
  }
  async identity(): Promise<string> {
    const caps = await this.backend.capabilities(); this.owner = caps.server_instance_id;
    this.stateWarning = caps.working_state === "ephemeral" ? `Drafts will not survive a server restart: ${caps.working_state_reason ?? "durable state is unavailable"}` : undefined;
    return this.owner;
  }
  async idFor(uri: vscode.Uri): Promise<string> {
    const owner = await this.identity();
    if (uri.scheme !== "wpilog-view" || uri.authority !== owner || !/^\/[0-9a-f-]{36}\.wpiview$/.test(uri.path)) throw vscode.FileSystemError.Unavailable("This view belongs to another local server; reconnect to its owner");
    return uri.path.slice(1, -8);
  }
  async open(view: RecordedView, beside = false): Promise<vscode.Uri> {
    const uri = vscode.Uri.parse(`wpilog-view://${await this.identity()}/${view.view_id}.wpiview`);
    await vscode.commands.executeCommand("vscode.openWith", uri, VIEW_TYPE, { viewColumn: beside ? vscode.ViewColumn.Beside : vscode.ViewColumn.Active, preview: false });
    return uri;
  }
  async openOverview(uri?: vscode.Uri): Promise<void> {
    if (!uri) {
      const active = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
      if (active instanceof vscode.TabInputCustom || active instanceof vscode.TabInputText) uri = active.uri;
      if (uri?.scheme === "wpilog-view") {
        const view = await this.backend.read(await this.idFor(uri));
        const resolved = await this.backend.call<{ path: string }>("/source/resolve", { source_ref: view.source_ref }); uri = vscode.Uri.file(resolved.path);
      }
    }
    if (!uri || uri.scheme !== "file" || !uri.path.toLowerCase().endsWith(".wpilog")) {
      void vscode.window.showInformationMessage("Choose a local recorded WPILOG for the preview. Remote and live views arrive in a later stage."); return;
    }
    await vscode.commands.executeCommand("vscode.openWith", uri, OVERVIEW_TYPE, { preview: false });
  }
  private pageHtml(panel: vscode.WebviewPanel, overview: boolean): string {
    const media = vscode.Uri.joinPath(this.host.context.extensionUri, "media");
    panel.webview.options = { enableScripts: true, localResourceRoots: [media] };
    return recordedPage(name => panel.webview.asWebviewUri(vscode.Uri.joinPath(media, name)).toString(), panel.webview.cspSource, randomBytes(18).toString("hex"), overview);
  }
  private renderOverview(uri: vscode.Uri, panel: vscode.WebviewPanel): void {
    const page: OverviewPage = { uri, panel, ready: false, closed: false, abort: new AbortController() };
    this.overviews.set(uri.toString(), page); panel.title = path.basename(uri.fsPath) + " · Overview";
    panel.webview.onDidReceiveMessage(message => { void this.overviewMessage(page, message).catch(error => this.warning(panel, error)); });
    panel.onDidChangeViewState(() => this.targetEntries());
    panel.onDidDispose(() => { page.closed = true; page.abort.abort(); this.overviews.delete(uri.toString()); this.targetEntries(); });
    panel.webview.html = this.pageHtml(panel, true); this.targetEntries();
  }
  private async overviewMessage(page: OverviewPage, message: any): Promise<void> {
    if (page.closed) return;
    if (message.type === "ready") {
      page.ready = true;
      await this.identity();
      if (this.stateWarning) this.warning(page.panel, this.stateWarning);
      if (page.uri.scheme !== "file") throw new Error("Remote preview is not available yet; use the existing editor");
      page.summary = await this.backend.identify(page.uri.fsPath);
      if (page.closed) return;
      await page.panel.webview.postMessage({ type: "summary", summary: page.summary });
      this.targetEntries();
      // Metadata is already visible while this separate request builds the source index.
      void this.backend.describe(page.summary.source_ref, page.abort.signal).then(inventory => {
        if (!page.closed) void page.panel.webview.postMessage({ type: "inventory", inventory });
      }, error => { if (!page.closed) this.warning(page.panel, `Signal inventory failed: ${error}`); });
      await this.refreshOverview(page);
    } else if (message.type === "refresh") await this.refreshOverview(page);
    else if (message.type === "blank" && page.summary) await this.open(await this.backend.create(page.summary.source_ref));
    else if (message.type === "resume") await this.open(await this.backend.read(String(message.view_id)));
    else if (message.type === "openSaved") await this.open(await this.backend.call<RecordedView>("/saved/open", {
      saved_view_id: message.saved_view_id, store_id: message.store_id, request_id: randomUUID(),
    }));
  }
  private async refreshOverview(page: OverviewPage): Promise<void> {
    if (!page.summary || page.closed) return;
    const lists = await this.backend.lists(page.summary.source_ref);
    if (!page.closed) await page.panel.webview.postMessage({ type: "lists", lists });
  }
  private async render(document: ViewDocument, panel: vscode.WebviewPanel): Promise<void> {
    const id = document.controller.snapshot.view_id;
    const page: ViewPage = { document, panel, generation: new ViewGeneration(), key: "", ready: false, closed: false, serial: Promise.resolve(), cursorGeneration: 0 };
    this.pages.set(id, page); panel.title = `Visualization ${id.slice(0, 8)}`;
    panel.webview.onDidReceiveMessage(message => {
      if (message.type === "rendered") { if (message.generation === page.key) this.rendered.set(id, message); return; }
      const task = page.serial.then(() => this.message(page, message));
      page.serial = task.catch(error => this.warning(panel, error));
    });
    panel.onDidChangeViewState(() => {
      void panel.webview.postMessage({ type: "visibility", visible: panel.visible });
      if (!panel.visible) { page.generation.cancel(); page.valueAbort?.abort(); page.dataKey = undefined; }
      else if (page.ready) this.paint(document);
      this.targetEntries();
    });
    panel.onDidDispose(() => this.closePage(id));
    panel.webview.html = this.pageHtml(panel, false);
  }
  private closePage(id: string): void {
    const page = this.pages.get(id); if (!page) return;
    page.closed = true; page.generation.cancel(); page.valueAbort?.abort(); this.pages.delete(id); this.rendered.delete(id); this.targetEntries();
    for (const overview of this.overviews.values()) void this.refreshOverview(overview).catch(() => {});
  }
  private paint(document: ViewDocument): void {
    const page = this.pages.get(document.controller.snapshot.view_id);
    if (!page?.ready || page.closed) return;
    const snapshot = document.controller.snapshot as RecordedView;
    if (page.panel.visible) {
      try { requireRecordedPreview(snapshot.configuration as RecordedConfiguration); }
      catch (error) { this.warning(page.panel, error); return; }
      const dataKey = recordedDataKey(snapshot.source_ref, snapshot.configuration as RecordedConfiguration);
      let load: { key: string; signal: AbortSignal } | undefined;
      if (page.dataKey !== dataKey) {
        this.rendered.delete(snapshot.view_id); page.valueAbort?.abort();
        load = page.generation.begin(snapshot.view_id, snapshot.revision);
        page.key = load.key; page.dataKey = dataKey;
      }
      // A title, resize, style or checkpoint still publishes its new revision for gesture CAS,
      // while an in-flight answer remains valid for the unchanged query.
      void page.panel.webview.postMessage({ type: "snapshot", generation: page.key, snapshot, warning: [this.stateWarning, document.controller.warning].filter(Boolean).join("\n"),
        pending: document.controller.pending, title: page.panel.title, label: page.inventory?.label ?? "Loading source…" });
      if (load) {
        const { key, signal } = load;
        page.rendering = this.loadData(page, snapshot, signal).catch(error => { if (page.generation.current(key)) this.warning(page.panel, error); });
      }
    } else void page.panel.webview.postMessage({ type: "warning", message: [this.stateWarning, document.controller.warning].filter(Boolean).join("\n") });
    this.targetEntries();
  }
  private async loadData(page: ViewPage, view: RecordedView, signal: AbortSignal): Promise<void> {
    const key = page.key;
    if (!page.inventory) page.inventory = await this.backend.describe(view.source_ref, signal);
    if (!page.generation.current(key)) return;
    await page.panel.webview.postMessage({ type: "inventory", inventory: page.inventory }); this.targetEntries();
    const config = view.configuration as RecordedConfiguration;
    requireRecordedPreview(config);
    const client = await this.host.client();
    const resolved = await this.backend.call<{ path: string }>("/source/resolve", { source_ref: view.source_ref }, signal);
    const used = new Set(config.panels.filter(p => p.type === "time_series").flatMap(p => p.series.map(s => s.requirement_id)));
    for (const requirement of config.requirements.filter(r => used.has(r.id))) {
      if (!page.generation.current(key) || !page.panel.visible) return;
      const entry = page.inventory.entries.find(e => e.name === requirement.entry);
      if (!entry || !requirement.accepted_entry_types.includes(entry.type)) throw new Error(`Signal unavailable or declared type changed: ${requirement.entry}`);
      const name = requirement.entry + (requirement.field_path ?? ""), time = config.time.recorded;
      const response = await this.data.fetch(client.endpoint, { path: resolved.path, names: [name], maxPoints: 4096,
        ...(time.mode === "fixed" ? { startTime: time.start_sec, endTime: time.end_sec } : {}) }, signal);
      await this.backend.call("/source/resolve", { source_ref: view.source_ref }, signal);
      if (!page.generation.current(key) || !page.panel.visible) return;
      await page.panel.webview.postMessage({ type: "series", generation: key, requirement_id: requirement.id, name, bytes: response.bytes });
    }
    if (page.generation.current(key)) await this.loadValues(page);
  }
  private async loadValues(page: ViewPage): Promise<void> {
    page.valueAbort?.abort(); const abort = new AbortController(); page.valueAbort = abort;
    const generation = ++page.cursorGeneration, key = page.key, view = page.document.controller.snapshot as RecordedView;
    const config = view.configuration as RecordedConfiguration;
    const needed = new Set(config.panels.filter(p => p.type === "latest").flatMap(p => p.series.map(s => s.requirement_id)));
    for (const requirement of config.requirements.filter(r => needed.has(r.id))) {
      if (page.closed || !page.panel.visible || generation !== page.cursorGeneration || !page.generation.current(key)) return;
      const entry = page.inventory?.entries.find(e => e.name === requirement.entry);
      if (!entry || !requirement.accepted_entry_types.includes(entry.type)) throw new Error(`Signal unavailable or declared type changed: ${requirement.entry}`);
      const result = await this.backend.call("/source/value", { source_ref: view.source_ref, entry: requirement.entry,
        ...(requirement.field_path ? { field: requirement.field_path } : {}), ...(page.cursor === undefined ? {} : { at_sec: page.cursor }) }, abort.signal);
      if (page.closed || generation !== page.cursorGeneration || !page.generation.current(key)) return;
      await page.panel.webview.postMessage({ type: "value", generation: key, requirement_id: requirement.id, result });
    }
  }
  private async message(page: ViewPage, message: any): Promise<void> {
    if (page.closed) return;
    if (message.type === "ready") { page.ready = true; this.provider.ready.add(page.document.uri.toString()); this.paint(page.document); return; }
    if (message.view_id !== page.document.controller.snapshot.view_id || message.generation !== page.key) return;
    const controller = page.document.controller, view = controller.snapshot as RecordedView;
    if (message.type === "target") {
      if ((view.configuration as RecordedConfiguration).panels.some(p => p.panel_id === message.panel_id)) page.activePanel = message.panel_id;
      this.targetEntries(); return;
    }
    if (message.type === "cursor") { page.cursor = message.at; void this.loadValues(page).catch(error => { if (!page.valueAbort?.signal.aborted) this.warning(page.panel, error); }); return; }
    if (message.type === "overview") { const resolved = await this.backend.call<{ path: string }>("/source/resolve", { source_ref: view.source_ref }); await this.openOverview(vscode.Uri.file(resolved.path)); return; }
    if (message.type === "duplicate") { await this.open(await this.backend.create(view.source_ref, view.configuration), true); return; }
    if (message.type === "save") { await this.save(page); return; }
    if (message.type === "undo") { await controller.undoDraft(); this.paint(page.document); return; }
    if (message.type === "redo") { await controller.redoDraft(); this.paint(page.document); return; }
    if (message.revision !== view.revision) throw new Error("The view changed; retry this gesture against the current layout");
    let config = structuredClone(view.configuration) as RecordedConfiguration;
    if (message.type === "addPanel") {
      if (!["time_series", "latest"].includes(message.panel_type)) throw new Error("Unsupported preview panel");
      const id = randomUUID(); config = addPanel(config, id, message.panel_type); page.activePanel = id;
    } else if (message.type === "addSignals") {
      const names = Array.isArray(message.names) ? message.names : [];
      const signals = names.map((name: unknown) => this.signal(page, String(name)));
      config = addSignals(config, String(message.panel_id), signals, randomUUID); page.activePanel = message.panel_id;
    } else if (message.type === "layout") config = layoutEdit(config, String(message.panel_id), message.action as LayoutAction);
    else if (message.type === "geometry") config.layout.items = message.items;
    else if (message.type === "title") {
      const panel = config.panels.find(p => p.panel_id === message.panel_id); if (!panel) return; panel.title = String(message.title);
    } else if (message.type === "range") config.time.recorded = { mode: "fixed", start_sec: message.start, end_sec: message.end };
    else if (message.type === "full") config.time.recorded = { mode: "full" };
    else if (message.type === "reset") {
      const answer = await vscode.window.showWarningMessage("Reset this draft to its initial layout?", { modal: true }, "Reset draft");
      if (answer !== "Reset draft") return; config = view.initial_configuration as RecordedConfiguration;
    } else return;
    await controller.edit(config, "Edit recorded view"); this.paint(page.document);
  }
  private signal(page: ViewPage, name: string): Signal {
    const entries = page.inventory?.entries ?? [];
    const entry = entries.find(e => e.name === name) ?? [...entries].sort((a, b) => b.name.length - a.name.length).find(e => name.startsWith(e.name + ".") || name.startsWith(e.name + "["));
    if (!entry) throw new Error("The selected signal is no longer in this source");
    return { ...entry, ...(entry.name === name ? {} : { field: name.slice(entry.name.length) }) };
  }
  private async save(page: ViewPage): Promise<void> {
    const controller = page.document.controller, view = controller.snapshot;
    if (view.saved_view_ref) { await vscode.commands.executeCommand("workbench.action.files.save"); return; }
    const stores = await this.backend.call<{ stores: StoreChoice[] }>("/stores");
    const picked = await vscode.window.showQuickPick([...stores.stores.filter(s => s.writable).map(s => ({ label: s.label, description: s.path, store: s })), { label: "Create store…", description: "Create metadata storage without automatic enrollment", store: undefined }], { title: "Save view destination" });
    if (!picked) return;
    let storePath: string | undefined;
    if (!picked.store) {
      const folder = await vscode.window.showOpenDialog({ canSelectFolders: true, canSelectFiles: false, canSelectMany: false, openLabel: "Create store here" });
      if (!folder?.[0]) return;
      if (folder[0].scheme !== "file") throw new Error("Choose a local folder for the visualization store");
      storePath = await fs.realpath(folder[0].fsPath);
    }
    const title = await vscode.window.showInputBox({ title: "Save view", prompt: "A title for this source-bound layout", value: page.panel.title, validateInput: v => !v.trim() || v.length > 256 ? "Use 1–256 characters" : undefined });
    if (!title) return;
    let storeId = picked.store?.store_id;
    if (storePath) {
      await this.host.grantStore(storePath);
      const client = await this.host.client();
      await runImport(client.endpoint, { store: storePath, paths: [], move: false, stated_robot: null }, () => {});
      const available = await this.backend.call<{ stores: StoreChoice[] }>("/stores");
      storeId = available.stores.find(s => path.resolve(s.path) === path.resolve(storePath!))?.store_id;
      if (!storeId) throw new Error("The created store could not be found; refresh the overview and retry");
    }
    this.backend.destinations.set(view.view_id, { store_id: storeId, title });
    await controller.promote(); page.panel.title = title; this.paint(page.document);
    for (const overview of this.overviews.values()) void this.refreshOverview(overview).catch(() => {});
  }
  private active(): ViewPage | OverviewPage | undefined {
    const input = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
    if (!(input instanceof vscode.TabInputCustom)) return;
    if (input.viewType === VIEW_TYPE) return [...this.pages.values()].find(p => p.document.uri.toString() === input.uri.toString());
    if (input.viewType === OVERVIEW_TYPE) return this.overviews.get(input.uri.toString());
  }
  private targetEntries(): void {
    const active = this.active();
    if (!active) {
      const input = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
      if (this.entriesOwned && (!(input instanceof vscode.TabInputCustom) || input.viewType !== "wpilog-mcp.explorer")) this.host.entries(undefined, "Open a view to choose the Entries target");
      this.entriesOwned = false; return;
    }
    this.entriesOwned = true;
    if ("summary" in active || "uri" in active) { const page = active as OverviewPage; this.host.entries(page.summary?.path, "Source overview · New visualization"); }
    else {
      const page = active as ViewPage, config = page.document.controller.snapshot.configuration as RecordedConfiguration;
      const panel = config.panels.find(p => p.panel_id === page.activePanel) ?? config.panels[0];
      page.activePanel = panel?.panel_id; this.host.entries(page.inventory?.path, `${page.panel.title} · ${panel?.title ?? "New plot"}`);
    }
  }
  /** Called by Entries commands after its generation check; captures the target before awaiting. */
  async routeSignal(name: string): Promise<boolean> {
    const active = this.active(); if (!active) return false;
    if ("uri" in active) {
      const summary = active.summary; if (!summary) return true;
      const view = await this.backend.create(summary.source_ref); await this.open(view);
      const deadline = Date.now() + 10000;
      while (Date.now() < deadline) {
        const page = this.pages.get(view.view_id);
        if (page?.inventory) return this.addEntry(page, name);
        await new Promise(resolve => setTimeout(resolve, 25));
      }
      throw new Error("The new view could not load its source; retry Add from Entries when it is ready");
    }
    return this.addEntry(active, name);
  }
  private async addEntry(page: ViewPage, name: string): Promise<boolean> {
    const task = page.serial.then(async () => {
      if (this.active() !== page || page.closed) throw new Error("The Entries target changed; select the signal again in the current view");
      if (!page.inventory) throw new Error("Wait for this view's signal inventory before adding an entry");
      let config = structuredClone(page.document.controller.snapshot.configuration) as RecordedConfiguration;
      let id = page.activePanel ?? config.panels[0]?.panel_id;
      if (!id) { id = randomUUID(); config = addPanel(config, id, "time_series"); }
      config = addSignals(config, id, [this.signal(page, name)], randomUUID);
      page.activePanel = id; await page.document.controller.edit(config, "Add signal from Entries"); this.paint(page.document);
    });
    page.serial = task.catch(() => {}); await task; return true;
  }
  private warning(panel: vscode.WebviewPanel, error: unknown): void { void panel.webview.postMessage({ type: "warning", message: String(error) }); }
  dispose(): void { for (const id of [...this.pages.keys()]) this.closePage(id); for (const d of this.disposables) d.dispose(); }
}
