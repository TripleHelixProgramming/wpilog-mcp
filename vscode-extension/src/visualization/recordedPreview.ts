import * as vscode from "vscode";
import { randomBytes, randomUUID } from "node:crypto";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { McpClient } from "../mcpClient";
import { SourceContext } from "../connections";
import { readSourceUri, sourceUri } from "../sourceUri";
import { DataClient } from "../dataClient";
import { VisualizationEditorProvider, ViewDocument } from "./editorProvider";
import { RecoveryJournal } from "./recoveryJournal";
import { RecordedClient, SourceSummary, StoreChoice } from "./recordedClient";
import { addPanel, addSignals, sourceContent, Inventory, layoutEdit, LayoutAction, RecordedConfiguration, RecordedView, Compatibility, Binding, TemplateItem, TemplateRef, Signal, SourceRef, ViewGeneration, recordedDataKey, requireRecordedPreview } from "./recordedModel";
import { InventoryPresentation } from "./inventoryPresentation";
import { InventoryClient } from "./inventoryClient";
import { InventoryOverview, OverviewClock } from "./inventoryOverview";
import { VisualizationBudgets } from "./byteBudget";
import { recordedPage } from "./recordedPage";
import { FollowClient } from "./followClient";
import { FollowLease, FollowSource } from "./followProtocol";
import { LiveBroker } from "./liveBroker";
import { LiveView, liveDataKey } from "./liveView";
import { ListedLog } from "../explorer/logsTree";
import { ViewRecovery } from "./viewRecovery";
import { SourceHealthMonitor } from "./sourceHealth";
import { LivePresentation } from "./livePresentation";
import { RecordedBuffers, recordedPlan } from "./recordedBuffers";
import { displayCopies, displayMultiplier } from "./recordedModel";
import { viewBinding } from "./viewFollowProtocol";
import { AnalysisQueue, LiveAnalysis } from "./liveAnalysis";
import { recordingSelection, checkRecordingField } from "./recordingSelection";
import { historyRange, followingWindow } from "./timeNavigation";

export const OVERVIEW_TYPE = "wpilog-mcp.sourceOverview", VIEW_TYPE = "wpilog-mcp.visualization";
export interface PreviewHost {
  context: vscode.ExtensionContext;
  source?: SourceContext;
  managed?: boolean;
  budgets?: VisualizationBudgets;
  analysisQueue?: AnalysisQueue;
  client(): Promise<McpClient>;
  entries(path: string | undefined, target: string): void;
  grantStore(path: string): Promise<void>;
  overviewClock?: OverviewClock;
  dialogs?: Pick<typeof vscode.window, "showQuickPick" | "showOpenDialog" | "showInputBox" | "showSaveDialog" | "showWarningMessage">;
}
interface ViewPage {
  replicaWatch?: { dispose(): void }; replicaKey?: string; replicaSource?: string;
  document: ViewDocument; panel: vscode.WebviewPanel; generation: ViewGeneration; key: string;
  inventory?: Inventory; activePanel?: string; ready: boolean; closed: boolean;
  serial: Promise<void>; cursor?: number; cursorGeneration: number; valueAbort?: AbortController;
  dataKey?: string; rendering?: Promise<void>; dataSignal?: AbortSignal; bindings?: Map<string, Binding>;
  recorded?: RecordedBuffers; hiddenTimer?: NodeJS.Timeout;
  live?: LiveView; analysis?: LiveAnalysis; recordingInventory?: InventoryOverview; delivery?: InventoryPresentation;
  recovery?: ViewRecovery<RecordedView>; recovering?: boolean; paused?: boolean;
  selectionSerial?: number; selectionCheck?: AbortController; selectionCancel?: () => void;
}
interface OverviewPage {
  replicaWatch?: { dispose(): void }; replicaKey?: string; replicaSource?: string;
  replicaRefresh?: boolean;
  uri: vscode.Uri; panel: vscode.WebviewPanel; summary?: SourceSummary; ready: boolean; closed: boolean;
  generation: number; initialization?: Promise<void>; abort: AbortController; inventory?: InventoryOverview; delivery?: InventoryPresentation;
}

/** Preview ownership is per working UUID. Entries never routes through a remembered hidden view. */
export class RecordedPreview implements vscode.Disposable {
  readonly budgets: VisualizationBudgets;
  readonly backend: RecordedClient;
  readonly provider: VisualizationEditorProvider;
  readonly rendered = new Map<string, { plots: number; rows: number; width: number; height: number }>();
  private readonly pages = new Map<string, ViewPage>();
  private readonly overviews = new Map<string, OverviewPage>();
  private readonly disposables: vscode.Disposable[] = [];
  private readonly data = new DataClient(0, 0, async url => (await this.host.client()).dataHeaders(url), 1024 * 1024);
  private owner?: string;
  private stateWarning?: string;
  private entriesOwned = false;
  private controlClient?: McpClient;
  private controlSubscription?: { dispose(): void };
  private broker?: LiveBroker;
  private readonly healthMonitor = new SourceHealthMonitor((source_ref, signal) => this.backend.call("/source/health", { source_ref }, signal,
    { maxRequestBytes: 64 << 10, maxResponseBytes: 16 << 10, timeoutMs: 5000 }));
  private readonly analysisQueue: AnalysisQueue;
  private readonly followOwners = new WeakMap<FollowLease, FollowClient>();
  private liveBroker(): LiveBroker {
    return this.broker ??= new LiveBroker({ pageBytes: 1 << 20, frontierScope: "queries",
      open: async () => { throw new Error("Recording panels require a bound working view"); },
      openView: async (source, request, signal) => {
        const owner = await this.host.client();
        const client = new FollowClient(owner.endpoint, url => owner.followHeaders(url), 1 << 20, "queries");
        const lease = await client.openView(source, { ...request, ...(this.host.source ? { api_version: 2 as const } : {}) }, signal); this.followOwners.set(lease, client); return lease;
      },
      next: (lease, cursor, signal) => this.followOwners.get(lease)!.next(lease, cursor, signal),
      close: lease => this.followOwners.get(lease)!.close(lease),
    }, { encodedBudget: this.budgets.encoded, decodedBudget: this.budgets.decoded });
  }
  private analysisFor(page: ViewPage): LiveAnalysis {
    const key = page.key;
    return page.analysis ??= new LiveAnalysis(page.document.controller.snapshot as RecordedView, key, this.budgets, this.analysisQueue,
      (route, body, signal, limit) => this.backend.call(route, body, signal, limit),
      message => { if (!page.closed) return page.panel.webview.postMessage(message); },
      error => { if (!page.closed && page.key === key) this.warning(page.panel, error); });
  }
  private liveFor(page: ViewPage): LiveView {
    if (page.live) return page.live;
    // Each data generation owns its presentation. Late queued renderer messages keep the old
    // key even after a signal edit; acknowledging them cannot release the new owner's frame.
    const key = page.key;
    this.analysisFor(page);
    const presentation = new LivePresentation(this.budgets.decoded,
      (delivery_id, frame) => { if (!page.closed) void page.panel.webview.postMessage({ type: "liveFrame", generation: key, delivery_id, frame }); },
      reason => page.live?.fail(reason), 250, 5000, displayMultiplier(page.document.controller.snapshot.configuration as RecordedConfiguration),
      timing => { if (!page.closed) void page.panel.webview.postMessage({ type: "liveTiming", generation: key, timing }); });
    page.live = new LiveView(this.liveBroker(), this.budgets.decoded, presentation,
      async (view, signal) => viewBinding(await this.backend.call(`/views/${view.view_id}/bindings`, { expected_revision: view.revision }, signal),
        { view_id: view.view_id, expected_revision: view.revision }, view.source_ref as unknown as FollowSource),
      state => { if (!page.closed) void page.panel.webview.postMessage({ type: "liveState", generation: key, state }); },
      5000, 30000, page.analysis, this.healthMonitor);
    if (page.paused) { page.analysis?.pause(true); page.live.pause(true); }
    if (!page.recordingInventory) {
      page.delivery = new InventoryPresentation(this.budgets.decoded,
        (delivery_id, state) => { if (!page.closed) void page.panel.webview.postMessage({ type: "inventoryPage", delivery_id, state }); },
        reason => { void page.recordingInventory?.hide(); this.warning(page.panel, reason); });
      page.recordingInventory = new InventoryOverview((page.document.controller.snapshot as RecordedView).source_ref,
        new InventoryClient(() => this.host.client(), this.budgets.encoded, this.host.source ? 2 : 1), this.budgets.decoded,
        state => { if (!page.closed && page.panel.visible) page.delivery?.offer(state); });
    }
    void page.recordingInventory.show();
    return page.live;
  }
  private dialogs() { return this.host.dialogs ?? vscode.window; }
  private async controls(): Promise<void> {
    const client = await this.host.client(); if (client === this.controlClient) return;
    this.controlSubscription?.dispose(); this.controlClient = client;
    this.controlSubscription = client.onVisualization(event => {
      for (const page of this.pages.values()) {
        const id = page.document.controller.snapshot.view_id;
        if (event.kind !== "resync_required" && (event.view_id !== id || Number(event.revision) <= page.document.controller.snapshot.revision)) continue;
        if (event.kind === "resync_required" || page.recovering) { this.recoverPage(page); continue; }
        this.cancelSelection(page);
        page.serial = page.serial.then(async () => {
          if (page.closed) return;
          const incoming = await this.backend.read(id);
          await page.document.controller.agentChanged(incoming); this.paint(page.document);
        }).catch(error => this.warning(page.panel, error));
      }
    });
  }
  private recoverPage(page: ViewPage): void {
    page.recovery ??= new ViewRecovery<RecordedView>({
      suspend: () => {
        page.recovering = true; this.cancelSelection(page); page.generation.cancel(); page.valueAbort?.abort();
        page.analysis?.invalidate(); page.live?.suspend("Server session changed; restoring this view's attachment");
        void page.recordingInventory?.hide();
      },
      read: async signal => {
        const limits = { timeoutMs: 5000, maxResponseBytes: 4 << 20 };
        const caps = await this.backend.call<{ server_instance_id: string }>("/capabilities", undefined, signal, limits);
        if (caps.server_instance_id !== this.owner) throw new Error("This view belongs to another server identity; reconnect to its original owner. The configuration is retained.");
        await this.registerWindow(signal);
        return this.backend.call<RecordedView>(`/views/${page.document.controller.snapshot.view_id}/attach`, {}, signal, limits);
      },
      apply: async (incoming, signal) => {
        const operation = page.serial.then(async () => {
          if (page.closed || signal.aborted) return;
          await page.document.controller.agentChanged(incoming);
          if (page.closed || signal.aborted) return;
          page.recovering = false;
          // Rebootstrap uses the current configuration and source anchor, never the dead lease.
          // A fresh rendering generation rejects messages queued by the previous server session.
          page.dataKey = undefined; this.paint(page.document);
          if (page.panel.visible) void page.recordingInventory?.show();
        });
        page.serial = operation.catch(() => {}); await operation;
      },
      failed: error => { if (!page.closed) this.warning(page.panel, `Restoring view: ${String(error)}. Retrying; configuration retained.`); },
    });
    page.recovery.start();
  }
  constructor(private host: PreviewHost) {
    this.analysisQueue = host.analysisQueue ?? new AnalysisQueue();
    const configured = vscode.workspace.getConfiguration("wpilog-mcp").get<unknown>("visualization.decodedBufferMiB", 64);
    try { this.budgets = host.budgets ?? new VisualizationBudgets(configured); }
    catch (error) { this.budgets = new VisualizationBudgets(); void vscode.window.showWarningMessage(`${error}. Using 64 MiB until window reload.`); }
    this.backend = new RecordedClient(() => host.client(), host.source);
    this.provider = new VisualizationEditorProvider(this.backend,
      new RecoveryJournal(path.join((host.context.storageUri ?? host.context.globalStorageUri).fsPath, "visualization-drafts",
        ...(host.source ? [host.source.definition.id] : []))), {
        read: async uri => { const id = await this.idFor(uri); return this.backend.attach(id); },
        render: (doc, panel) => this.render(doc, panel), paint: doc => this.paint(doc),
        closed: doc => { const id = doc.controller.snapshot.view_id; this.closePage(id); void this.backend.detach(id).catch(() => {}); },
      });
    const changed = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
    this.disposables.push(changed, this.provider,
      vscode.window.tabGroups.onDidChangeTabs(() => this.targetEntries()),
      vscode.window.tabGroups.onDidChangeTabGroups(() => this.targetEntries()));
    if (!host.managed) this.disposables.push(
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
      }, { supportsMultipleEditorsPerDocument: false, webviewOptions: { retainContextWhenHidden: false } }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.openSourceOverview", (item?: vscode.Uri | { resourceUri?: vscode.Uri; node?: { log?: ListedLog } }) =>
        this.openOverview(item instanceof vscode.Uri ? item : item?.resourceUri, item instanceof vscode.Uri ? undefined : item?.node?.log)),
    );
  }
  private async registerWindow(signal?: AbortSignal): Promise<void> {
    const facts = await this.backend.call<{ decoded_buffer_budget_bytes: number; basis: string }>("/window", { decoded_buffer_budget_bytes: this.budgets.decoded.maximum }, signal, { timeoutMs: 5000 });
    if (facts.decoded_buffer_budget_bytes !== this.budgets.decoded.maximum || facts.basis !== "client_reported") throw new Error("Server did not acknowledge this window's decoded allowance; update the server and retry");
  }
  async identity(): Promise<string> {
    const caps = await this.backend.capabilities();
    if (this.owner && this.owner !== caps.server_instance_id) throw new Error("This view belongs to another server identity; reconnect to its original owner");
    this.owner = caps.server_instance_id;
    await this.registerWindow();
    this.stateWarning = caps.working_state === "ephemeral" ? `Drafts will not survive a server restart: ${caps.working_state_reason ?? "durable state is unavailable"}` : undefined;
    if (this.pages.size) await this.controls();
    return this.owner;
  }
  async idFor(uri: vscode.Uri): Promise<string> {
    const owner = await this.identity();
    if (uri.scheme !== "wpilog-view" || uri.authority !== owner || !/^\/[0-9a-f-]{36}\.wpiview$/.test(uri.path)) throw vscode.FileSystemError.Unavailable("This view belongs to another server; reconnect to its owner");
    return uri.path.slice(1, -8);
  }
  async open(view: RecordedView, beside = false): Promise<vscode.Uri> {
    this.host.source?.requireReference(view.source_ref);
    const query = this.host.source ? `?${new URLSearchParams({ connection: this.host.source.definition.id, scope: this.host.source.scopeId! })}` : "";
    const uri = vscode.Uri.parse(`wpilog-view://${await this.identity()}/${view.view_id}.wpiview${query}`);
    await vscode.commands.executeCommand("vscode.openWith", uri, VIEW_TYPE, { viewColumn: beside ? vscode.ViewColumn.Beside : vscode.ViewColumn.Active, preview: false });
    return uri;
  }
  async openOverview(uri?: vscode.Uri, listed?: ListedLog): Promise<void> {
    if (!uri) {
      const active = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
      if (active instanceof vscode.TabInputCustom || active instanceof vscode.TabInputText) uri = active.uri;
    }
    if (uri?.scheme === "wpilog-view") {
      const view = await this.backend.read(await this.idFor(uri));
      if (this.host.source) uri = vscode.Uri.parse(sourceUri({ connection: this.host.source.definition.id, owner: this.owner!,
        scope: this.host.source.scopeId!, path: "/source.wpilog", reference: view.source_ref }));
      else {
        const resolved = await this.backend.call<{ path: string }>("/source/resolve", { source_ref: view.source_ref }); uri = this.sourceUri(resolved.path);
      }
    }
    if (uri?.scheme === "wpilog-pit") throw new Error("Open this log from its configured connection in Logs");
    if (!uri || !["file", "wpilog-source"].includes(uri.scheme) || !uri.path.toLowerCase().endsWith(".wpilog")) {
      void vscode.window.showInformationMessage("Choose a WPILOG in a configured source for the overview."); return;
    }
    await vscode.commands.executeCommand("vscode.openWith", uri, OVERVIEW_TYPE, { preview: false });
  }
  private sourceUri(file: string): vscode.Uri {
    const source = this.host.source;
    return source ? vscode.Uri.parse(sourceUri({ connection: source.definition.id, owner: source.facts!.server_instance_id, scope: source.scopeId!, path: file })) : vscode.Uri.file(file);
  }
  private displayTitle(title: string): string { return this.host?.source ? `${this.host.source.definition.label} · ${title}` : title; }
  private pageHtml(panel: vscode.WebviewPanel, overview: boolean): string {
    const media = vscode.Uri.joinPath(this.host.context.extensionUri, "media");
    panel.webview.options = { enableScripts: true, localResourceRoots: [media] };
    return recordedPage(name => panel.webview.asWebviewUri(vscode.Uri.joinPath(media, name)).toString(), panel.webview.cspSource, randomBytes(18).toString("hex"), overview);
  }
  renderOverview(uri: vscode.Uri, panel: vscode.WebviewPanel): void {
    const page: OverviewPage = { uri, panel, ready: false, closed: false, generation: 0, abort: new AbortController() };
    this.overviews.set(uri.toString(), page); panel.title = this.displayTitle(path.basename(uri.fsPath) + " · Overview");
    panel.webview.onDidReceiveMessage(message => { void this.overviewMessage(page, message).catch(error => this.warning(panel, error)); });
    panel.onDidChangeViewState(() => {
      if (panel.visible) { if (page.ready) void page.inventory?.show(); }
      else { page.replicaWatch?.dispose(); page.replicaWatch = undefined; this.cancelOverviewInitialization(page); void page.inventory?.hide(); page.delivery?.dispose(); page.delivery = undefined; }
      this.targetEntries();
    });
    panel.onDidDispose(() => { page.closed = true; page.replicaWatch?.dispose(); this.cancelOverviewInitialization(page); page.delivery?.dispose(); void page.inventory?.dispose(); this.overviews.delete(uri.toString()); this.targetEntries(); });
    panel.webview.html = this.pageHtml(panel, true); this.targetEntries();
  }
  private delivery(page: OverviewPage): InventoryPresentation {
    return page.delivery ??= new InventoryPresentation(this.budgets.decoded,
      (delivery_id, state) => { if (!page.closed) void page.panel.webview.postMessage({ type: "inventoryPage", delivery_id, state }); },
      reason => { void page.inventory?.hide(); if (!page.closed) this.warning(page.panel, reason); });
  }
  private cancelOverviewInitialization(page: OverviewPage): void {
    page.generation++; page.ready = false; page.abort.abort(); page.initialization = undefined;
  }
  /** A recreated renderer joins one initialization per visible generation. Old catalog replies
   * cannot publish an owner, and summary delivery never opens a full-recording fallback. */
  private initializeOverview(page: OverviewPage): Promise<void> {
    if (page.closed || !page.panel.visible) return Promise.resolve();
    page.ready = true;
    if (page.initialization) return page.initialization;
    const generation = page.generation, abort = new AbortController(); page.abort = abort;
    const current = () => !page.closed && page.panel.visible && generation === page.generation;
    const task = (async () => {
      if (!page.summary) {
        await this.identity(); if (!current()) return;
        if (this.stateWarning) this.warning(page.panel, this.stateWarning);
        const address = page.uri.scheme === "wpilog-source" ? readSourceUri(page.uri.toString()) : undefined;
        if (address?.reference) this.host.source?.requireReference(address.reference);
        const summary: SourceSummary = address?.reference ? { source_ref: address.reference, path: "", label: "Selected source", catalog: null, inventory_state: "not_requested" }
          : await this.backend.identify(address?.path ?? page.uri.fsPath, abort.signal);
        if (!current()) return;
        page.summary = summary; this.targetEntries();
      }
      // Publish the single owner synchronously with its source, before awaiting the renderer.
      if (sourceContent(page.summary.source_ref).kind === "session" && !page.inventory) {
        page.inventory = new InventoryOverview(page.summary.source_ref, new InventoryClient(() => this.host.client(), this.budgets.encoded, this.host.source ? 2 : 1),
          this.budgets.decoded, state => { if (!page.closed && page.panel.visible) page.delivery?.offer(state); }, this.host.overviewClock);
      }
      await page.panel.webview.postMessage({ type: "summary", summary: page.summary });
      if (!current()) return;
      if (sourceContent(page.summary.source_ref).kind === "session") {
        this.delivery(page).offer(page.inventory!.snapshot); void page.inventory!.show();
      } else {
        const inventory = await this.backend.describe(page.summary.source_ref, abort.signal);
        if (!current()) return;
        void page.panel.webview.postMessage({ type: "inventory", inventory });
        page.replicaKey = this.replicaKey(inventory.replica);
        this.watchReplica(page, page.summary.source_ref, () => {
          if (page.initialization) page.replicaRefresh = true;
          else void this.initializeOverview(page).catch(error => this.warning(page.panel, error));
        }, inventory.replica);
      }
      if (current()) await this.refreshOverview(page);
    })().catch(error => { if (current()) throw error; });
    page.initialization = task;
    const finished = () => {
      if (page.initialization !== task) return;
      page.initialization = undefined;
      if (page.replicaRefresh) {
        page.replicaRefresh = false;
        void this.initializeOverview(page).catch(error => this.warning(page.panel, error));
      }
    };
    void task.then(finished, finished);
    return task;
  }
  private async overviewMessage(page: OverviewPage, message: any): Promise<void> {
    if (page.closed) return;
    if (message.type === "inventoryRendered") { page.delivery?.acknowledge(String(message.delivery_id)); return; }
    if (message.type === "ready") {
      await this.initializeOverview(page);
    } else if (message.type === "refresh") {
      if (page.summary && sourceContent(page.summary.source_ref).kind !== "session") await this.initializeOverview(page);
      else { await this.refreshOverview(page); await page.inventory?.refresh(); }
    }
    else if (message.type === "inventoryRefresh") {
      if (page.inventory?.snapshot.hidden && page.panel.visible) await page.inventory.show(); else await page.inventory?.refresh();
    }
    else if (message.type === "inventoryFilter") await page.inventory?.filter(String(message.query ?? ""), String(message.exact_type ?? ""), message.unavailable === true);
    else if (message.type === "inventoryNext" || message.type === "inventoryPrevious") await page.inventory?.navigate(message.type === "inventoryNext" ? 1 : -1, message.inventory_generation);
    else if (message.type === "inventorySelect") await page.inventory?.select(String(message.name), message.inventory_generation);
    else if (message.type === "templateSearch" || message.type === "templateCheck" || message.type === "applyTemplate" || message.type === "importTemplate" || message.type === "exportTemplate") await this.templateMessage(page, message);
    else if (message.type === "blank" && page.summary) await this.open(await this.backend.create(page.summary.source_ref));
    else if (message.type === "resume") await this.open(await this.backend.read(String(message.view_id)));
    else if (message.type === "savedPage" && page.summary) {
      const lists = await this.backend.lists(page.summary.source_ref, typeof message.after === "string" ? message.after : undefined);
      if (!page.closed) await page.panel.webview.postMessage({ type: "lists", lists });
    }
    else if (message.type === "openSaved") await this.open(await this.backend.call<RecordedView>("/saved/open", {
      saved_view_ref: message.saved_view_ref, request_id: randomUUID(),
    }));
  }
  private async refreshOverview(page: OverviewPage): Promise<void> {
    if (!page.summary || page.closed) return;
    const lists = await this.backend.lists(page.summary.source_ref);
    if (!page.closed) { await page.panel.webview.postMessage({ type: "lists", lists }); await this.templateList(page.panel); }
  }
  private async render(document: ViewDocument, panel: vscode.WebviewPanel): Promise<void> {
    const id = document.controller.snapshot.view_id;
    const page: ViewPage = { document, panel, generation: new ViewGeneration(), key: "", ready: false, closed: false, serial: Promise.resolve(), cursorGeneration: 0 };
    this.pages.set(id, page); panel.title = this.displayTitle(`Visualization ${id.slice(0, 8)}`);
    await this.controls();
    panel.webview.onDidReceiveMessage(message => {
      if (message.type === "rendered") { if (message.generation === page.key) this.rendered.set(id, message); return; }
      if (message.type === "recordedRendered") { page.recorded?.acknowledge(String(message.delivery_id)); page.analysis?.acknowledge(String(message.delivery_id)); return; }
      if (message.type === "liveRendered") { page.live?.presentation.acknowledge(String(message.delivery_id), message); return; }
      if (message.type === "inventoryRendered") { page.delivery?.acknowledge(String(message.delivery_id)); return; }
      // Cancel immediately, before queued gestures: a slow field check must not save intent
      // superseded by typing, selection, target, layout or agent changes.
      const selection = this.cancelSelection(page);
      const task = page.serial.then(() => this.message(page, message, selection));
      page.serial = task.catch(error => {
        if (!page.closed && message.type === "addInventorySignal" && message.field_path !== undefined)
          void panel.webview.postMessage({ type: "fieldSelection", generation: message.generation, inventory_generation: message.inventory_generation,
            field_request: message.field_request, field_path: message.field_path, state: "error", reason: String(error) });
        this.warning(panel, error);
      });
    });
    panel.onDidChangeViewState(() => {
      void panel.webview.postMessage({ type: "visibility", visible: panel.visible });
      clearTimeout(page.hiddenTimer);
      if (!panel.visible) {
        page.replicaWatch?.dispose(); page.replicaWatch = undefined;
        this.cancelSelection(page);
        page.valueAbort?.abort(); page.analysis?.hide(); page.live?.hide(); void page.recordingInventory?.hide();
        if (!page.live) {
          page.generation.cancel(); page.dataKey = undefined;
          page.hiddenTimer = setTimeout(() => { void page.recorded?.clear(page.key).catch(error => this.warning(panel, error)); }, 30000);
        }
      } else if (page.ready && !page.recovering) { this.paint(document); page.analysis?.show(); page.live?.show(); void page.recordingInventory?.show(); }
      this.targetEntries();
    });
    panel.onDidDispose(() => this.closePage(id));
    panel.webview.html = this.pageHtml(panel, false);
  }
  private closePage(id: string): void {
    const page = this.pages.get(id); if (!page) return;
    page.replicaWatch?.dispose(); page.replicaWatch = undefined;
    page.closed = true; page.recovery?.dispose(); this.cancelSelection(page); clearTimeout(page.hiddenTimer); page.recorded?.dispose(); page.generation.cancel(); page.valueAbort?.abort(); page.analysis?.dispose(); page.live?.dispose(); page.delivery?.dispose(); void page.recordingInventory?.dispose(); this.pages.delete(id); this.rendered.delete(id); this.targetEntries();
    if (!this.pages.size) { this.controlSubscription?.dispose(); this.controlSubscription = undefined; this.controlClient = undefined; }
    for (const overview of this.overviews.values()) void this.refreshOverview(overview).catch(() => {});
  }
  private paint(document: ViewDocument, rebind = false): void {
    const page = this.pages.get(document.controller.snapshot.view_id);
    if (!page?.ready || page.closed || page.recovering) return;
    const snapshot = document.controller.snapshot as RecordedView;
    if (page.panel.visible) {
      try { requireRecordedPreview(snapshot.configuration as RecordedConfiguration); }
      catch (error) { this.warning(page.panel, error); return; }
      const recording = sourceContent(snapshot.source_ref).kind === "session";
      this.watchReplica(page, snapshot.source_ref, () => {
        page.generation.cancel(); page.valueAbort?.abort(); page.inventory = undefined; page.bindings = undefined; page.dataKey = undefined;
        this.paint(document, true);
      }, page.inventory?.replica);
      const dataKey = recording ? liveDataKey(snapshot) : recordedDataKey(snapshot.source_ref, snapshot.configuration as RecordedConfiguration);
      let load: { key: string; signal: AbortSignal } | undefined;
      if (page.dataKey !== dataKey) {
        page.analysis?.dispose(); page.analysis = undefined;
        page.live?.dispose(); page.live = undefined;
        this.rendered.delete(snapshot.view_id); page.valueAbort?.abort();
        load = page.generation.begin(snapshot.view_id, snapshot.revision);
        page.key = load.key; page.dataKey = dataKey; page.dataSignal = load.signal;
      }
      // A title, resize, style or checkpoint still publishes its new revision for gesture CAS,
      // while an in-flight answer remains valid for the unchanged query.
      void page.panel.webview.postMessage({ type: "snapshot", generation: page.key, snapshot, warning: [this.stateWarning, document.controller.warning].filter(Boolean).join("\n"),
        pending: document.controller.pending, title: page.panel.title, label: recording ? "Anchored recording · panel preview" : page.inventory?.label ?? "Loading source…", decoded_buffer_budget_bytes: this.budgets?.decoded.maximum });
      if (recording) this.liveFor(page).update(snapshot, document.controller.pending, rebind);
      else if (load) {
        const { key, signal } = load;
        page.rendering = this.loadData(page, snapshot, signal).catch(error => { if (page.generation.current(key)) this.warning(page.panel, error); });
      }
    } else void page.panel.webview.postMessage({ type: "warning", message: [this.stateWarning, document.controller.warning].filter(Boolean).join("\n") });
    this.targetEntries();
  }
  private recordedFor(page: ViewPage): RecordedBuffers {
    return page.recorded ??= new RecordedBuffers(this.budgets, message => page.panel.webview.postMessage(message));
  }
  private replicaKey(replica: Inventory["replica"]): string | undefined {
    return replica ? JSON.stringify([replica.generation, replica.content_sha256, replica.authority_state ?? replica.verification_basis, replica.server_epoch]) : undefined;
  }
  private watchReplica(page: ViewPage | OverviewPage, source: SourceRef, refresh: () => void, admitted?: Inventory["replica"]): void {
    const selected = JSON.stringify(source);
    if (sourceContent(source).kind !== "file_version" || page.replicaSource !== selected) {
      page.replicaWatch?.dispose(); page.replicaWatch = undefined; page.replicaKey = undefined; page.replicaSource = selected;
    }
    if (page.replicaKey === undefined && admitted) page.replicaKey = this.replicaKey(admitted);
    if (sourceContent(source).kind !== "file_version" || page.replicaWatch || !page.panel.visible || page.closed) return;
    page.replicaWatch = this.healthMonitor.watch(source, observation => {
      if (page.closed || !page.panel.visible) return;
      if (observation.kind !== "ok" || !observation.health.replica) {
        this.warning(page.panel, observation.reason); return;
      }
      const replica = observation.health.replica;
      const key = this.replicaKey(replica);
      const old = page.replicaKey; page.replicaKey = key;
      if (old !== undefined && old !== key) {
        refresh(); this.warning(page.panel, "Replica content changed or its authority basis changed; refreshed the same selected source.");
      }
    });
  }
  private async loadData(page: ViewPage, view: RecordedView, signal: AbortSignal): Promise<void> {
    const key = page.key, buffers = this.recordedFor(page);
    await buffers.clear(key);
    if (signal.aborted || !page.generation.current(key)) return;
    if (!page.inventory) page.inventory = await this.backend.describe(view.source_ref, signal);
    if (!page.generation.current(key)) return;
    // A health reply can precede this description. The next poll compares with the generation
    // actually admitted for display, so it cannot adopt a replacement as an unseen baseline.
    page.replicaKey = this.replicaKey(page.inventory.replica);
    await page.panel.webview.postMessage({ type: "inventory", inventory: page.inventory }); this.targetEntries();
    const config = view.configuration as RecordedConfiguration;
    requireRecordedPreview(config);
    const client = await this.host.client();
    const resolved = await this.backend.call<{ path: string }>("/source/resolve", { source_ref: view.source_ref }, signal);
    const compatibility = await this.backend.call<Compatibility>("/templates/check", { source_ref: view.source_ref, configuration: config }, signal);
    if (!page.generation.current(key)) return;
    page.bindings = new Map(compatibility.bindings.map(b => [b.requirement_id, b]));
    await page.panel.webview.postMessage({ type: "bindings", generation: key, result: compatibility });
    if (config.panels.some(p => p.type === "histogram" || p.type === "scatter"))
      this.analysisFor(page).complete({ compatibility, time_policy: config.time.recorded }, true);
    const used = new Set(config.panels.filter(p => p.type === "time_series").flatMap(p => p.series.map(s => s.requirement_id)));
    for (const requirement of config.requirements.filter(r => used.has(r.id))) {
      if (!page.generation.current(key) || !page.panel.visible) return;
      const binding = page.bindings.get(requirement.id); if (binding?.state !== "resolved" || !binding.entry) continue;
      const name = binding.entry + (binding.field_path ?? ""), time = config.time.recorded;
      const copies = displayCopies(config, requirement.id, "time_series");
      await buffers.load(`series:${requirement.id}`, key, signal, () => recordedPlan(this.budgets, used.size, copies), async limit => {
        const response = await this.data.fetch(client.endpoint, { path: resolved.path, ...(this.host.source ? { sourceRef: view.source_ref, scopeId: this.host.source.scopeId } : {}), names: [name], maxPoints: limit.maxPoints,
          ...(time.mode === "fixed" ? { startTime: time.start_sec, endTime: time.end_sec } : {}) }, signal, limit.responseBytes);
        await this.backend.call("/source/resolve", { source_ref: view.source_ref }, signal);
        return { type: "series", requirement_id: requirement.id, name, bytes: response.bytes };
      }, () => !page.closed && page.panel.visible && page.generation.current(key));
    }
    for (const type of ["statistics", "raw"] as const) {
      const needed = new Set(config.panels.filter(p => p.type === type).flatMap(p => p.series.map(s => s.requirement_id)));
      for (const id of needed) await this.loadAnalysis(page, id, type, 0, signal, key);
    }
    if (page.generation.current(key)) await this.loadValues(page);
  }
  private async loadAnalysis(page: ViewPage, requirement: string, type: "statistics" | "raw", first: number, signal?: AbortSignal, key = page.key): Promise<void> {
    if (page.closed || !page.panel.visible || !page.generation.current(key)) return;
    const binding = page.bindings?.get(requirement); if (binding?.state !== "resolved" || !binding.entry) return;
    const view = page.document.controller.snapshot as RecordedView, time = (view.configuration as RecordedConfiguration).time.recorded;
    await this.recordedFor(page).load(`${type}:${requirement}`, key, signal ?? new AbortController().signal,
      () => recordedPlan(this.budgets, 1, displayCopies(view.configuration as RecordedConfiguration, requirement, type)), async limit => {
        const result = await this.backend.call(`/source/${type}`, { source_ref: view.source_ref, entry: binding.entry,
          ...(binding.field_path ? { field: binding.field_path } : {}), ...(time.mode === "fixed" ? { start_time: time.start_sec, end_time: time.end_sec } : {}),
          ...(type === "raw" ? { first_ordinal: first, limit: limit.rows } : {}) }, signal, { maxResponseBytes: limit.responseBytes, timeoutMs: 30000 });
        return { type, requirement_id: requirement, result };
      }, () => !page.closed && page.panel.visible && page.generation.current(key));
  }
  private async loadValues(page: ViewPage): Promise<void> {
    page.valueAbort?.abort(); const abort = new AbortController(); page.valueAbort = abort;
    const generation = ++page.cursorGeneration, key = page.key, view = page.document.controller.snapshot as RecordedView;
    const config = view.configuration as RecordedConfiguration;
    const needed = new Set(config.panels.filter(p => p.type === "latest").flatMap(p => p.series.map(s => s.requirement_id)));
    for (const requirement of config.requirements.filter(r => needed.has(r.id))) {
      if (page.closed || !page.panel.visible || generation !== page.cursorGeneration || !page.generation.current(key)) return;
      const binding = page.bindings?.get(requirement.id); if (binding?.state !== "resolved" || !binding.entry) continue;
      await this.recordedFor(page).load(`value:${requirement.id}`, key, abort.signal, () => recordedPlan(this.budgets, 1, displayCopies(config, requirement.id, "latest")), async limit => {
        const result = await this.backend.call("/source/value", { source_ref: view.source_ref, entry: binding.entry,
          ...(binding.field_path ? { field: binding.field_path } : {}), ...(page.cursor === undefined ? {} : { at_sec: page.cursor }) }, abort.signal,
          { maxResponseBytes: limit.responseBytes, timeoutMs: 30000 });
        return { type: "value", requirement_id: requirement.id, result };
      }, () => !page.closed && page.panel.visible && generation === page.cursorGeneration && page.generation.current(key));
    }
  }
  private cancelSelection(page: ViewPage): number {
    page.selectionCancel?.(); page.selectionCancel = undefined;
    page.selectionCheck?.abort(); page.selectionCheck = undefined;
    return page.selectionSerial = (page.selectionSerial ?? 0) + 1;
  }
  private async message(page: ViewPage, message: any, selection = page.selectionSerial ?? 0): Promise<void> {
    if (page.closed) return;
    if (message.type === "ready") { page.ready = true; this.provider.ready.add(page.document.uri.toString()); this.paint(page.document); await this.templateList(page.panel); return; }
    if (message.view_id !== page.document.controller.snapshot.view_id || message.generation !== page.key) return;
    const controller = page.document.controller, view = controller.snapshot as RecordedView;
    if (message.type === "inventoryRefresh") { if (page.recordingInventory?.snapshot.hidden) await page.recordingInventory.show(); else await page.recordingInventory?.refresh(); return; }
    if (message.type === "inventoryFilter") { await page.recordingInventory?.filter(String(message.query ?? ""), String(message.exact_type ?? ""), message.unavailable === true); return; }
    if (message.type === "inventoryNext" || message.type === "inventoryPrevious") { await page.recordingInventory?.navigate(message.type === "inventoryNext" ? 1 : -1, message.inventory_generation); return; }
    if (message.type === "inventorySelect") { await page.recordingInventory?.select(String(message.name), message.inventory_generation); return; }
    if (message.type === "livePause") { page.paused = message.paused === true; page.analysis?.pause(message.paused === true); page.live?.pause(message.paused === true); return; }
    if (message.type === "liveRetry") { page.analysis?.refresh(); page.live?.refresh(); return; }
    if (["templateSearch", "templateCheck", "applyTemplate", "replaceTemplate", "saveTemplate", "updateTemplate", "importTemplate", "exportTemplate"].includes(message.type)) { await this.templateMessage(page, message); return; }
    if (message.type === "analysisRetry") { page.analysis?.refresh(); return; }
    if (message.type === "scatterPage") { page.analysis?.scatterPage(String(message.panel_id), message.offset); return; }
    if (message.type === "rawPage") { if (sourceContent(view.source_ref).kind !== "session") await this.loadAnalysis(page, String(message.requirement_id), "raw", Number(message.first_ordinal), page.dataSignal); return; }
    if (message.type === "target") {
      if ((view.configuration as RecordedConfiguration).panels.some(p => p.panel_id === message.panel_id)) page.activePanel = message.panel_id;
      this.targetEntries(); return;
    }
    if (message.type === "cursor") {
      if (message.at !== undefined && (typeof message.at !== "number" || !Number.isFinite(message.at))) return;
      if (sourceContent(view.source_ref).kind === "session") { page.analysis?.cursor(message.at); return; }
      page.cursor = message.at; void this.loadValues(page).catch(error => { if (!page.valueAbort?.signal.aborted) this.warning(page.panel, error); }); return;
    }
    if (message.type === "overview") {
      if (sourceContent(view.source_ref).kind === "session") {
        const overview = [...this.overviews.values()].find(p => JSON.stringify(p.summary?.source_ref) === JSON.stringify(view.source_ref));
        if (overview) await this.openOverview(overview.uri); else this.warning(page.panel, "Use Open Source Overview on a member WPILOG to inspect this recording");
      } else { const resolved = await this.backend.call<{ path: string }>("/source/resolve", { source_ref: view.source_ref }); await this.openOverview(vscode.Uri.file(resolved.path)); }
      return;
    }
    if (message.type === "duplicate") { await this.open(await this.backend.create(view.source_ref, view.configuration), true); return; }
    if (message.type === "save") { await this.save(page); return; }
    if (message.type === "undo") { await controller.undoDraft(); this.paint(page.document); return; }
    if (message.type === "redo") { await controller.redoDraft(); this.paint(page.document); return; }
    if (message.revision !== view.revision) throw new Error("The view changed; retry this gesture against the current layout");
    let config = structuredClone(view.configuration) as RecordedConfiguration;
    let fieldAdded: (() => void) | undefined;
    if (message.type === "addPanel") {
      if (!["time_series", "latest", "statistics", "raw", "histogram", "scatter"].includes(message.panel_type)) throw new Error("Unsupported preview panel");
      const id = randomUUID(); config = addPanel(config, id, message.panel_type); page.activePanel = id;
    } else if (message.type === "scatterSwap") {
      const panel = config.panels.find(p => p.panel_id === message.panel_id && p.type === "scatter");
      if (!panel || panel.series.length !== 2) return;
      panel.series.reverse();
    } else if (message.type === "addInventorySignal") {
      const state = page.recordingInventory?.snapshot;
      const signal = recordingSelection(state, message.inventory_generation, message.field_path);
      config = addSignals(config, String(message.panel_id), [signal], randomUUID);
      if (signal.field !== undefined) {
        if (selection !== (page.selectionSerial ?? 0) || !page.panel.visible) return;
        const abort = new AbortController(); page.selectionCheck = abort;
        const key = page.key, revision = view.revision, detail = state!.details!.detail_revision;
        const current = () => !abort.signal.aborted && !page.closed && page.panel.visible && page.key === key
          && controller.snapshot.revision === revision && selection === (page.selectionSerial ?? 0)
          && page.recordingInventory?.snapshot.generation === message.inventory_generation
          && page.recordingInventory?.snapshot.selected === signal.name && page.recordingInventory?.snapshot.details?.detail_revision === detail
          && !page.recordingInventory?.snapshot.loading && !page.recordingInventory?.snapshot.expired && !page.recordingInventory?.snapshot.hidden;
        const publish = (status: string, reason: string) => { if (current()) void page.panel.webview.postMessage({ type: "fieldSelection", generation: key,
          inventory_generation: message.inventory_generation, field_request: message.field_request, field_path: signal.field, state: status, reason }); };
        page.selectionCancel = () => publish("cancelled", "Field check cancelled by another gesture; check the current selection again");
        try {
          publish("checking", "Checking this exact field across recording members…");
          const requirement = config.requirements.find(r => r.entry === signal.name && r.field_path === signal.field)!;
          const reason = await checkRecordingField(this.backend, this.budgets, view.source_ref, config, requirement, abort.signal);
          if (!current()) return;
          fieldAdded = () => {
            if (!page.closed && page.panel.visible && selection === (page.selectionSerial ?? 0))
              void page.panel.webview.postMessage({ type: "fieldSelection", generation: key, inventory_generation: message.inventory_generation,
                field_request: message.field_request, field_path: signal.field, state: "added", reason });
          };
        } catch (error) { if (!current()) return; publish("error", String(error)); throw error; }
        finally { if (page.selectionCheck === abort) { page.selectionCheck = undefined; page.selectionCancel = undefined; } }
      }
      page.activePanel = message.panel_id;
    } else if (message.type === "addSignals") {
      const names = Array.isArray(message.names) ? message.names : [];
      const signals = names.map((name: unknown) => this.signal(page, String(name)));
      config = addSignals(config, String(message.panel_id), signals, randomUUID); page.activePanel = message.panel_id;
    } else if (message.type === "layout") config = layoutEdit(config, String(message.panel_id), message.action as LayoutAction);
    else if (message.type === "geometry") config.layout.items = message.items;
    else if (message.type === "title") {
      const panel = config.panels.find(p => p.panel_id === message.panel_id); if (!panel) return; panel.title = String(message.title);
    } else if (message.type === "range" || message.type === "timeNavigate") {
      if (message.type === "timeNavigate" && typeof message.action !== "string") throw new Error("Name a history navigation action");
      if (message.panel_id !== undefined && (!config.links.time_range || !config.panels.some(p => p.panel_id === message.panel_id && p.type === "time_series")))
        throw new Error("Drag a linked time-series plot, or use the view's explicit range controls");
      const range = historyRange(message.start, message.end, message.type === "timeNavigate" ? message.action : undefined);
      if (sourceContent(view.source_ref).kind === "session") config.time.live = range;
      config.time.recorded = range;
    } else if (message.type === "full") config.time.recorded = { mode: "full" };
    else if (message.type === "liveFollow") config.time.live = { mode: "follow", window_sec: followingWindow(message.window_sec) };
    else if (message.type === "reset") {
      const answer = await this.dialogs().showWarningMessage("Reset this draft to its initial layout?", { modal: true }, "Reset draft");
      if (answer !== "Reset draft") return; config = view.initial_configuration as RecordedConfiguration;
    } else return;
    await controller.edit(config, "Edit recorded view"); fieldAdded?.();
    // Finalization does not change the configuration key: recorded may already be full while
    // the admitted lease still holds only the former live window. This gesture must re-admit.
    this.paint(page.document, message.type === "full");
    // A different query starts fresh owners. Reapplying the same interval keeps them, but is
    // still an explicit request to display that interval rather than remain paused.
    if (["range", "timeNavigate", "full", "liveFollow"].includes(message.type)) {
      page.paused = false;
      page.live?.pause(false);
      page.analysis?.pause(false);
    }
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
    const destination = await this.chooseDestination(page.panel.title); if (!destination) return;
    const { title } = destination;
    this.backend.destinations.set(view.view_id, { title });
    await controller.promote(); page.panel.title = this.displayTitle(title); this.paint(page.document);
    for (const overview of this.overviews.values()) void this.refreshOverview(overview).catch(() => {});
  }
  private async requireLibrary(): Promise<void> {
    const capabilities = await this.backend.capabilities();
    if (!capabilities.library?.available) throw new Error("This server has no durable visualization library. Start a named server with persistent state to save definitions.");
  }
  private async chooseDestination(initialTitle: string): Promise<{ title: string } | undefined> {
    await this.requireLibrary();
    const title = await this.dialogs().showInputBox({ title: "Save to server library", prompt: "A title for this definition", value: initialTitle, validateInput: v => !v.trim() || v.length > 256 ? "Use 1–256 characters" : undefined });
    return title ? { title } : undefined;
  }
  private async templateList(panel: vscode.WebviewPanel, query = "", after?: string): Promise<void> {
    const catalog = await this.backend.call<{ templates: TemplateItem[] }>("/templates/list", { query, ...(after ? { after } : {}) });
    // The renderer sends the complete immutable reference; asynchronous searches cannot retarget it by index.
    await panel.webview.postMessage({ type: "templates", catalog });
  }
  private async templateMessage(page: ViewPage | OverviewPage, message: any): Promise<void> {
    if (message.type === "templateSearch") { await this.templateList(page.panel, String(message.query ?? ""), typeof message.after === "string" ? message.after : undefined); return; }
    const view = "document" in page ? page.document.controller.snapshot as RecordedView : undefined;
    const source = view?.source_ref ?? (page as OverviewPage).summary?.source_ref; if (!source) return;
    const ref = message.template_ref as TemplateRef;
    if (message.type === "templateCheck") {
      const result = await this.backend.call<Compatibility>("/templates/check", { source_ref: source, template_ref: ref });
      await page.panel.webview.postMessage({ type: "templateCompatibility", template_ref: ref, result }); return;
    }
    if (message.type === "applyTemplate" || message.type === "replaceTemplate") {
      const item = await this.backend.call<TemplateItem>("/templates/get", { template_ref: ref });
      if (message.type === "replaceTemplate" && view && "document" in page) {
        const answer = await this.dialogs().showWarningMessage("Replace this layout? Its current configuration may have unsaved changes.", { modal: true }, "Save then replace", "Discard and replace");
        if (!answer) return;
        if (answer === "Save then replace") { await this.save(page); if (!(page.document.controller.snapshot as RecordedView).saved_view_ref || (page.document.controller.snapshot as RecordedView).has_unsaved_changes || page.document.controller.pending) return; }
        await page.document.controller.edit(item.template.configuration, "Replace with template"); this.paint(page.document);
      } else await this.open(await this.backend.call<RecordedView>("/views", { source_ref: source, template_ref: ref, request_id: randomUUID() }), true);
      return;
    }
    if (message.type === "saveTemplate" && view) {
      const destination = await this.chooseDestination("New template"); if (!destination) return;
      let configuration = structuredClone(view.configuration) as RecordedConfiguration;
      if (configuration.requirements.some(r => r.selector_kind === "role")) {
        const mode = await this.dialogs().showQuickPick(["Exact entry names", "Keep explicit roles"], { title: "Reusable signal selectors" }); if (!mode) return;
        if (mode === "Exact entry names") {
          const check = await this.backend.call<Compatibility>("/templates/check", { source_ref: source, configuration });
          configuration.requirements = configuration.requirements.map(r => {
            const binding = check.bindings.find(b => b.requirement_id === r.id);
            if (!binding?.entry || binding.state !== "resolved") throw new Error("Resolve every role before saving exact entry names, or explicitly keep roles");
            const { role, ...rest } = r; return { ...rest, selector_kind: "entry", entry: binding.entry };
          });
        }
      }
      await this.backend.call("/templates/create", { name: destination.title, description: "", configuration, request_id: randomUUID() });
    } else if (message.type === "updateTemplate" && view) {
      if (ref?.origin !== "library") throw new Error("Choose a server-library template to update; built-ins are immutable");
      const item = await this.backend.call<TemplateItem>("/templates/get", { template_ref: ref });
      if (await this.dialogs().showWarningMessage(`Update ${item.template.name} revision ${ref.revision}? Open views retain their own configurations.`, { modal: true }, "Update template") !== "Update template") return;
      await this.backend.call("/templates/update", { template_ref: ref, expected_revision: ref.revision, name: item.template.name, description: item.template.description, configuration: view.configuration, request_id: randomUUID() });
    } else if (message.type === "exportTemplate") {
      const result = await this.backend.call<{ envelope_json: string }>("/templates/export", { template_ref: ref });
      const uri = await this.dialogs().showSaveDialog({ title: "Export template", filters: { "Visualization template": ["json"] } });
      if (uri) await vscode.workspace.fs.writeFile(uri, Buffer.from(result.envelope_json + "\n"));
    } else if (message.type === "importTemplate") {
      const files = await this.dialogs().showOpenDialog({ title: "Import template", canSelectFiles: true, canSelectFolders: false, canSelectMany: false, filters: { "Visualization template": ["json"] } }); if (!files?.[0]) return;
      if ((await vscode.workspace.fs.stat(files[0])).size > 1024 * 1024) throw new Error("Template exceeds 1 MiB");
      const bytes = await vscode.workspace.fs.readFile(files[0]); if (bytes.byteLength > 1024 * 1024) throw new Error("Template exceeds 1 MiB");
      await this.requireLibrary();
      const envelope = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      const preview = await this.backend.call<{ can_update: boolean; name: string; expected_revision: number | null }>("/templates/import-preview", { envelope_json: envelope });
      let mode = "copy";
      if (preview.can_update) {
        const choice = await this.dialogs().showQuickPick(["Import copy", "Update existing"], { title: `${preview.name}: a newer revision is available` });
        if (!choice) return; mode = choice === "Update existing" ? "update" : "copy";
      }
      await this.backend.call("/templates/import", { envelope_json: envelope, request_id: randomUUID(), mode,
        ...(mode === "update" ? { expected_revision: preview.expected_revision } : {}) });
    }
    await this.templateList(page.panel);
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
      if (!this.host.managed && this.entriesOwned && (!(input instanceof vscode.TabInputCustom) || input.viewType !== "wpilog-mcp.explorer")) this.host.entries(undefined, "Open a view to choose the Entries target");
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
    const source = "uri" in active ? active.summary?.source_ref : (active.document.controller.snapshot as RecordedView).source_ref;
    if (source && sourceContent(source).kind === "session") { this.warning(active.panel, "Use Recording signals in the visualization: Inspect the signal, then Add inspected signal"); return true; }
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
  dispose(): void {
    this.controlSubscription?.dispose(); this.healthMonitor.dispose(); for (const overview of this.overviews.values()) { overview.closed = true; this.cancelOverviewInitialization(overview); overview.delivery?.dispose(); void overview.inventory?.dispose(); }
    this.overviews.clear(); for (const id of [...this.pages.keys()]) this.closePage(id); void this.broker?.dispose(); for (const d of this.disposables) d.dispose(); }
}
