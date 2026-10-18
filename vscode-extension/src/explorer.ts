import { RecordedPreview } from "./visualization/recordedPreview";
import { EntryInfo } from "./explorer/entryInfo";
import { HttpHeaders, noHeaders } from "./pitCredential";
/**
 * WPILog Explorer's VS Code side (EXPLORER_PLAN.md §3, §4): the client to the shared server,
 * the Logs and Entries views, and the custom editor that opens a log and shows its entries and
 * time range. Every number shown comes from a tool (decision 3): the listing, the entries, and an
 * entry's description are the results of `list_available_logs`, `list_entries`, and
 * `get_entry_info`, drawn as they are. The decisions that need no VS Code API are in
 * `explorer/*.ts` and tested there; this file is the glue.
 */
import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import * as vscode from "vscode";
import { DataClient, DataError, TooLargeError } from "./dataClient";
import { McpClient, ToolError, LocalControlError } from "./mcpClient";
import { SessionRegistration } from "./directoryLease";
import { DaemonSpec, ServerManager } from "./serverManager";
import { EntryGrouping, EntryListing, EntryNode, ListedEntry, buildEntryTree, buildFieldNodes, elementNodes, elementPaths } from "./explorer/entriesTree";
import { ListedLog, LogListing, LogNode, buildLogTree } from "./explorer/logsTree";
import { Choices, MOVE_CHOICES, ORGANIZE_CHOICES, OrganizeOffer, containsPath, importDestinationError, importPlan, offerFor, organizeFolders, rememberChoice, robotItems, robotNameError, withImportDirectory } from "./explorer/organize";
import { completeListing, progressText, resultDetails, resultSummary, runImport, sameRobotMessages } from "./explorer/importJobs";
import { organizeSources } from "./explorer/organizeSources";
import { DATA_STORE_ENABLED_CONTEXT, IngestionPolicy, IngestionSource, IngestionStatus, LocalIngestionClient, dataStoreDisabled, ingestionPolicyError, ingestionReview, sourceCandidates, temporaryDirectoryRoots } from "./explorer/localIngestion";
import { MirrorStatus, PitOverview, mirroredCopy, originOf, pitEndpoint, pitLogTree, pitOverview, readRemoteLogUri, remoteLogUri } from "./explorer/pitServer";
import { SESSION_END_MS, endWithin } from "./sessionEnd";
import { Selection, parseExplorerUri, openValidated, selectionPrompt } from "./explorer/selection";
import { notebook } from "./explorer/notebook";
import { explorerPage } from "./explorer/webviewHtml";
import { EntriesView } from "./explorer/entriesView";

/** The custom editor's view type, as package.json declares it. */
export const EDITOR_VIEW_TYPE = "wpilog-mcp.explorer";
export const LOGS_VIEW = "wpilog-mcp.logs";
export const ENTRIES_VIEW = "wpilog-mcp.entries";

const NEVER_STORE_SETUP_KEY = "wpilog-mcp.neverOfferDataStore";
const NEVER_ORGANIZE_KEY = "wpilog-mcp.neverOrganizeFolders";
const ENTRY_GROUPING_KEY = "wpilog-mcp.entryGrouping";
/** How many of an array's elements "Plot Every Element" plots: a pane can hold that many. */
const ELEMENTS_PLOTTED = 16;
/** How many console matches one request lists. */
const CONSOLE_LIMIT = 500;

/** A log open in the editor: its file, the server that read it, and its listing. */
export type ExplorerSpec = DaemonSpec | { kind: "pit"; name: string; url: string };

interface OpenLog {
  path: string;
  spec?: ExplorerSpec;
  source?: ListedLog;
  copy?: string;
  listing?: EntryListing;
}

/** The explorer: owns the clients, the views, and the editor, and wires them together. */
export class Explorer implements vscode.Disposable {
  private readonly clients = new Map<string, { url: string; client: McpClient }>();
  /** The data endpoint's client: one memory of streams for every editor. */
  readonly data = new DataClient(32, 256 * 1024 * 1024, url => this.headersFor(url));
  readonly logs: LogsProvider;
  readonly entries: EntriesProvider;
  private readonly entriesView: EntriesView;
  readonly editor: ExplorerEditorProvider;
  readonly preview: RecordedPreview;
  private readonly disposables: vscode.Disposable[] = [];
  private choices: Choices;
  /** The mirror's state, which extension.ts polls; a pit file's overview reports its progress. */
  mirrorStatus: () => MirrorStatus = () => ({ state: "disabled" });
  private offering = false;
  private offeredStore = false;

  /**
   * @param windowSpec the server, with the projects it serves, as extension.ts computes it
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
    private readonly serverManager: ServerManager,
    private readonly windowSpec: () => DaemonSpec,
    private readonly windowDirectories: () => string[] | undefined = () => undefined,
    private readonly registration?: () => Promise<SessionRegistration>,
    private readonly pitUrl: () => string | undefined = () => undefined,
    private readonly headersFor: HttpHeaders = noHeaders,
    private readonly permanentDirectories: () => string[] = () => []
  ) {
    void vscode.commands.executeCommand("setContext", DATA_STORE_ENABLED_CONTEXT, false);
    this.choices = { never: context.globalState.get<string[]>(NEVER_ORGANIZE_KEY) ?? [], deferred: [], offered: {} };
    this.logs = new LogsProvider(this);
    this.entries = new EntriesProvider(this, context.workspaceState);
    this.entriesView = new EntriesView(this.entries, context.extensionUri);
    this.editor = new ExplorerEditorProvider(this);
    let previewLog: OpenLog | undefined;
    this.preview = new RecordedPreview({ context, client: () => this.clientFor(this.spec()),
      entries: (file, target) => {
        if (previewLog?.path !== file) previewLog = file ? { path: file, spec: this.spec() } : undefined;
        this.entries.setActive(previewLog, target);
      },
      grantStore: async folder => {
        const key = `wpilog-mcp.visualizationStores.${this.spec().name}`;
        const roots = context.workspaceState.get<string[]>(key) ?? [];
        if (!roots.includes(folder)) await context.workspaceState.update(key, [...roots, folder]);
        await (await this.clientFor(this.spec())).refreshRegistration();
      },
      pitOverview: (uri, listed) => this.pitOverview(uri, listed),
    });
    this.disposables.push(this.preview,
      // Custom editor URIs are documents, not paths on this laptop. No file contents cross this provider.
      vscode.workspace.registerFileSystemProvider("wpilog-pit", {
        onDidChangeFile: new vscode.EventEmitter<vscode.FileChangeEvent[]>().event,
        watch: () => new vscode.Disposable(() => {}),
        stat: uri => { readRemoteLogUri(uri); return { type: vscode.FileType.File, ctime: 0, mtime: 0, size: 0 }; },
        readDirectory: () => [],
        readFile: () => { throw vscode.FileSystemError.Unavailable("Open this remote log with WPILog Explorer"); },
        writeFile: () => { throw vscode.FileSystemError.NoPermissions("Pit log documents are read-only"); },
        createDirectory: () => { throw vscode.FileSystemError.NoPermissions(); },
        delete: () => { throw vscode.FileSystemError.NoPermissions(); },
        rename: () => { throw vscode.FileSystemError.NoPermissions(); },
      }, { isReadonly: true, isCaseSensitive: true }),
      vscode.window.registerTreeDataProvider(LOGS_VIEW, this.logs),
      this.entriesView,
      vscode.window.registerWebviewViewProvider(ENTRIES_VIEW, this.entriesView, {
        webviewOptions: { retainContextWhenHidden: true },
      }),
      vscode.window.registerCustomEditorProvider(EDITOR_VIEW_TYPE, this.editor, {
        webviewOptions: { retainContextWhenHidden: true },
        supportsMultipleEditorsPerDocument: false,
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.setupDataStore", () => this.storeAction(() => this.setupDataStore())),
      vscode.commands.registerCommand("wpilog-mcp.explorer.manageDataStore", () => this.storeAction(() => this.manageDataStore())),
      vscode.commands.registerCommand("wpilog-mcp.explorer.syncDataStore", () => this.storeAction(async () => {
        await (await this.ingestionClient()).scan(); this.logs.refresh();
        void vscode.window.showInformationMessage("Data-store scan requested. Pending files appear in the Logs view.");
      })),
      vscode.commands.registerCommand("wpilog-mcp.explorer.pauseDataStore", () => this.storeAction(() => this.pauseDataStore())),
      vscode.commands.registerCommand("wpilog-mcp.explorer.showAllSources", () => this.storeAction(async () => this.logs.toggleSources())),
      vscode.commands.registerCommand("wpilog-mcp.explorer.organizeLogs", () => this.organizeOnDemand()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.importLogs", (item?: LogItem) => this.importItem(item)),
      vscode.commands.registerCommand("wpilog-mcp.explorer.assignRobot", (item?: LogItem) => this.importItem(item, true)),
      vscode.commands.registerCommand("wpilog-mcp.explorer.refreshLogs", () => this.logs.refresh()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.filterLogs", () => this.logs.askFilter()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.clearLogFilter", () => this.logs.setFilter("")),
      vscode.commands.registerCommand("wpilog-mcp.explorer.filterEntries", () => this.entriesView.focusFilter()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.clearEntryFilter", () => this.entries.setFilter("")),
      vscode.commands.registerCommand("wpilog-mcp.explorer.collapseEntries", () => this.entriesView.collapseAll()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.openLog", (logPath: string, spec?: ExplorerSpec, listed?: ListedLog) => this.openLog(logPath, spec, listed)),
      vscode.window.registerUriHandler({ handleUri: async uri => {
        try {
          const selection = parseExplorerUri(uri.toString());
          try { await this.openLog(selection.path); }
          catch (localError) {
            const pit = this.pitSpec(); if (!pit) throw localError;
            // The URL carries no server or credentials. Only the configured pit may be tried.
            try { await this.openLog(selection.path, pit); }
            catch (pitError) { throw new Error(`${messageOf(localError)}; pit server: ${messageOf(pitError)}`); }
          }
          await this.editor.applySelection(selection);
        }
        catch (error) { void vscode.window.showErrorMessage(messageOf(error)); }
      } }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.openInNotebook", () => this.editor.openNotebook()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.askAboutSelection", (context?: { webviewSection?: string }) => this.editor.askSelection(context?.webviewSection)),
      vscode.commands.registerCommand("wpilog-mcp.explorer.revealLog", (item?: LogItem) => {
        if (item?.resourceUri?.scheme === "file") void vscode.commands.executeCommand("revealFileInOS", item.resourceUri);
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.copyLogPath", (item?: LogItem) => {
        if (item?.node.kind === "log") void vscode.env.clipboard.writeText(item.node.log.path);
        else if (item?.resourceUri) void vscode.env.clipboard.writeText(item.resourceUri.fsPath);
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.showEntry", (nameOrItem: string | EntryItem) =>
        this.routeEntry(entryNameOf(nameOrItem), true)),
      vscode.commands.registerCommand("wpilog-mcp.explorer.plotEntry", (nameOrItem: string | EntryItem) =>
        this.routeEntry(entryNameOf(nameOrItem), false)),
      vscode.commands.registerCommand("wpilog-mcp.explorer.plotElements", async (item?: EntryItem) => {
        const node = item?.node;
        if (node?.kind !== "field" || !node.elements) return;
        for (const name of elementPaths(node.entry.name, node.fieldPath, node.elements, ELEMENTS_PLOTTED)) {
          await this.routeEntry(name, false);
        }
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.refreshLog", () => this.editor.reloadActive())
    );
  }

  /** The Logs tree and URI handler share server admission before opening a document. */
  private async routeEntry(name: string, details: boolean): Promise<void> {
    try {
      if (await this.preview.routeSignal(name)) return;
      if (details) this.editor.selectEntry(name); else this.editor.plotEntry(name);
    } catch (error) { void vscode.window.showErrorMessage(messageOf(error)); }
  }

  async openLog(logPath: string, spec: ExplorerSpec = this.spec(), listed?: ListedLog): Promise<void> {
    await openValidated({ path: logPath, entries: [], kind: "time_series" },
      async file => (await this.clientFor(spec)).callTool("list_entries", { path: file, limit: 1 }),
      async () => { await vscode.commands.executeCommand("vscode.openWith", spec.kind === "pit"
        ? vscode.Uri.parse(remoteLogUri(spec.url, logPath, listed?.session)) : vscode.Uri.file(logPath), EDITOR_VIEW_TYPE); });
  }

  /** Discovery may offer setup, but only a completed review authorizes persistent ingestion. */
  async offerOrganizing(listing?: LogListing): Promise<void> {
    if (this.offering) return;
    try {
      // Availability belongs to the named server, including windows that already declined setup.
      // Its background check must not make a command appear busy while ordinary logs load.
      const status = await this.ingestionStatus();
      if (this.offering || this.offeredStore || this.context.globalState.get<boolean>(NEVER_STORE_SETUP_KEY)) return;
      this.offering = true;
      try {
        const current = listing ?? await this.logs.listing(this.spec());
        if (current.status === "error" || !(current.logs ?? []).some(log => !log.store)) return;
        if (status.policy?.store) return;
        this.offeredStore = true;
        const choice = await vscode.window.showInformationMessage(
          "Create a data store that keeps your chosen log folders organized as new files arrive? Originals stay unless you explicitly choose Move.",
          "Set Up Data Store", "Not now", "Don't offer again");
        if (choice === "Don't offer again") await this.context.globalState.update(NEVER_STORE_SETUP_KEY, true);
        if (choice === "Set Up Data Store") await this.setupDataStore(current);
      } finally { this.offering = false; }
    } catch (error) {
      // An older server must still list logs; an explicit setup command gives its full refusal.
      if (!dataStoreDisabled(error)) this.log(`Data-store setup offer: ${messageOf(error)}`);
    }
  }

  private async ingestionClient(): Promise<LocalIngestionClient> { return new LocalIngestionClient(await this.clientFor(this.spec())); }
  private async ingestionStatus(): Promise<IngestionStatus> {
    try {
      const status = await (await this.ingestionClient()).status();
      await vscode.commands.executeCommand("setContext", DATA_STORE_ENABLED_CONTEXT, true);
      return status;
    } catch (error) {
      await vscode.commands.executeCommand("setContext", DATA_STORE_ENABLED_CONTEXT, false);
      throw error;
    }
  }

  private async storeAction(action: () => Promise<void>): Promise<void> {
    if (this.offering) return;
    this.offering = true;
    try {
      // Menu enablement alone does not guard commands invoked by another extension or a keybinding.
      await this.ingestionStatus();
      await action();
    }
    catch (error) {
      if (dataStoreDisabled(error)) this.reportImportError(error);
      else if (error instanceof LocalControlError && error.status === 409) {
        this.logs.refresh();
        this.reportImportError(`Data-store settings changed in another editor or could not be saved. Nothing was overwritten. Open Manage Data Store to review them. ${error.message}`);
      } else this.reportImportError(error);
    } finally { this.offering = false; }
  }

  private async setupDataStore(supplied?: LogListing): Promise<void> {
    const control = await this.ingestionClient();
    const status = await control.status();
    const listing = supplied ?? await this.logs.listing(this.spec());
    let store = status.policy?.store;
    if (store) {
      const choice = await vscode.window.showQuickPick([
        { label: "Keep this data store", description: store, keep: true },
        { label: "Choose another data store…", description: "Previously imported files stay in the old store", keep: false },
      ], { title: "Set Up Data Store" });
      if (!choice) return;
      if (!choice.keep) store = undefined;
    }
    if (!store) {
      const folders = await vscode.window.showOpenDialog({ title: "Choose the data-store folder", openLabel: "Use as data store",
        canSelectFolders: true, canSelectFiles: false, canSelectMany: false });
      if (!folders?.length) return;
      if (folders[0].scheme !== "file") throw new Error("Choose a local folder for the data store.");
      store = await fs.promises.realpath(folders[0].fsPath);
    }
    const canonical = async (folder: string) => fs.promises.realpath(folder).catch(() => path.resolve(folder));
    const userRoots = await Promise.all(this.permanentDirectories().map(canonical));
    const temporary = await Promise.all(temporaryDirectoryRoots(process.platform, os.tmpdir()).map(canonical));
    const normalized: LogListing = { ...listing, log_directories: await Promise.all((listing.log_directories ?? listing.log_directory_paths ?? []).map(async dir =>
      typeof dir === "string" ? canonical(dir) : { ...dir, path: await canonical(dir.path) })) };
    const candidates = sourceCandidates(normalized, userRoots, temporary, store, status.policy?.store ? status.policy : null);
    const picked = await vscode.window.showQuickPick([...candidates.map(candidate => ({ label: candidate.path,
      description: candidate.origin as string, picked: candidate.selected, candidate })),
      { label: "Choose another source folder…", description: "Explicitly include a local folder", picked: false, candidate: undefined }], {
      title: "Choose source folders", canPickMany: true,
      placeHolder: "Only checked folders will synchronize. Project, session and temporary folders require your choice.",
    });
    if (picked === undefined) return;
    const selected = picked.flatMap(item => item.candidate ? [item.candidate] : []);
    if (picked.some(item => !item.candidate)) {
      const extra = await vscode.window.showOpenDialog({ title: "Choose source folders", openLabel: "Include these sources",
        canSelectFolders: true, canSelectFiles: false, canSelectMany: true });
      if (!extra?.length) return;
      for (const folder of extra) {
        if (folder.scheme !== "file") throw new Error("Choose local source folders.");
        selected.push({ path: await fs.promises.realpath(folder.fsPath), origin: "project or session", selected: true });
      }
    }
    const sources: IngestionSource[] = [];
    for (const candidate of selected) {
      const item = { candidate };
      const previous = status.policy?.sources.find(source => source.path === item.candidate.path);
      const mode = await vscode.window.showQuickPick([
        { label: "Copy and keep originals", description: "Safe for folders where logs may still be written", mode: "copy" as const },
        { label: "Move finished files", description: "Remove verified originals; this folder must contain only finished logs", mode: "move" as const },
      ], { title: `Source: ${item.candidate.path}`, placeHolder: previous ? `Current mode: ${previous.mode}. Choose the ongoing policy.` : "Choose the ongoing policy; Copy is the default." });
      if (!mode) return;
      if (mode.mode === "move") {
        const confirm = await vscode.window.showWarningMessage(
          `Use Move only if no program writes logs in ${item.candidate.path}. This is an ongoing removal policy; verified duplicates are retained.`,
          { modal: true }, "This source contains only finished files");
        if (confirm !== "This source contains only finished files") return;
      }
      sources.push({ path: item.candidate.path, mode: mode.mode, enabled: true, completed_files: mode.mode === "move",
        stated_robot: previous?.stated_robot ?? null });
    }
    const error = ingestionPolicyError(store, sources, normalized); if (error) throw new Error(error);
    const policy: IngestionPolicy = { revision: status.policy?.revision ?? 0, enabled: true, store,
      store_id: status.policy?.store === store ? status.policy.store_id : null, sources };
    const approved = await vscode.window.showInformationMessage(ingestionReview(policy), { modal: true }, "Save and Start Syncing");
    if (approved !== "Save and Start Syncing") return;
    await control.save(policy);
    this.logs.refresh();
    void vscode.window.showInformationMessage("Data store configured. The shared server will keep synchronizing these folders after VS Code closes.");
  }

  private async pauseDataStore(): Promise<void> {
    const control = await this.ingestionClient(), status = await control.status();
    if (!status.policy?.store) { await this.setupDataStore(); return; }
    await control.save({ ...status.policy, enabled: !status.policy.enabled }, !status.policy.enabled);
    this.logs.refresh();
    void vscode.window.showInformationMessage(status.policy.enabled ? "Data-store synchronization paused. Existing files stay available." : "Data-store synchronization resumed.");
  }

  private async manageDataStore(): Promise<void> {
    const control = await this.ingestionClient(), status = await control.status();
    if (!status.policy?.store) { await this.setupDataStore(); return; }
    const choice = await vscode.window.showQuickPick([
      { label: "Review store and source folders…", action: "configure" }, { label: "Sync now", action: "sync" },
      { label: status.policy.enabled ? "Pause synchronization" : "Resume synchronization", action: "pause" },
      { label: "Show source status", action: "status" }, { label: "Show all sources / preferred store", action: "view" },
    ], { title: "Manage Data Store", placeHolder: status.policy.store });
    if (!choice) return;
    if (choice.action === "configure") await this.setupDataStore();
    if (choice.action === "sync") { await control.scan(); this.logs.refresh(); }
    if (choice.action === "pause") await this.pauseDataStore();
    if (choice.action === "view") this.logs.toggleSources();
    if (choice.action === "status") {
      const detail = [status.policy.enabled ? "Synchronization enabled" : "Synchronization paused", status.policy.store,
        ...(status.sources ?? []).map(source => `${source.path}: ${source.state ?? "unknown"}${source.reason ? ` — ${source.reason}` : ""}`)].join("\n");
      this.log(detail);
      const page = await control.files();
      for (const file of page.files) this.log(`${file.path}: ${file.state}${file.destination ? ` → ${file.destination}` : ""}${file.reason ? `; ${file.reason}` : ""}`);
      if (page.total > page.files.length) this.log(`Showing ${page.files.length} of ${page.total} receipts; GET /store/local-ingestion/files supports offset and limit.`);
      void vscode.window.showInformationMessage(`${detail}\nThe output channel shows ${page.files.length} of ${page.total} file receipts.`);
    }
  }

  private foldersForWindow(listing: LogListing): string[] {
    const wanted = this.spec().kind === "standalone" ? undefined : this.windowDirectories();
    return organizeFolders(listing).filter(folder => !wanted?.length || wanted.some(w => containsPath(w, folder)));
  }

  private async organizeOnDemand(): Promise<void> {
    if (this.offering) return;
    this.offering = true;
    try {
      const listing = await this.logs.listing(this.spec());
      const folders = this.foldersForWindow(listing);
      const folder = folders.length === 1 ? folders[0] : (await vscode.window.showQuickPick(
        folders.map(folder => ({ label: path.basename(folder), description: folder, folder })),
        { title: "Organize logs", placeHolder: "Choose the log directory" }))?.folder;
      if (!folder) return;
      const offer = offerFor(listing, folder, this.choices, true);
      if (offer) await this.showOffer(offer, listing);
      else void vscode.window.showInformationMessage(`No logs need organizing in ${folder}.`);
    } catch (error) {
      this.reportImportError(error);
    } finally {
      this.offering = false;
    }
  }

  private async showOffer(offer: OrganizeOffer, listing: LogListing): Promise<void> {
    const choice = await vscode.window.showInformationMessage(`${offer.message}\n${offer.folder}`, ...ORGANIZE_CHOICES);
    this.choices = rememberChoice(this.choices, offer, choice);
    await this.context.globalState.update(NEVER_ORGANIZE_KEY, this.choices.never);
    if (choice !== "Organize") return;
    const mode = await vscode.window.showQuickPick(MOVE_CHOICES,
      { title: "Organize logs", placeHolder: `Source: ${offer.folder}. Choose where the organized logs will go.` });
    if (!mode) return;
    const destination = mode.mode === "import" ? await this.pickImportStore(listing, offer.folder) : offer.folder;
    if (!destination) return;
    const robot = await this.pickRobot(listing, destination);
    const plan = importPlan(offer, choice, mode.mode, robot, destination);
    if (!plan) return;
    const paths = await organizeSources(offer, (listing.stores ?? []).map(s => s.path), async folder =>
      (await fs.promises.readdir(folder, { withFileTypes: true })).map(entry => ({ name: entry.name,
        directory: entry.isDirectory(), file: entry.isFile(), symlink: entry.isSymbolicLink() })));
    await this.importBatch(plan.store, paths, plan.move, plan.stated_robot, false, mode.mode === "import");
  }

  /** Choosing a directory grants only that root; the Java store still owns all file operations. */
  private async pickImportStore(listing: LogListing, source: string): Promise<string | undefined> {
    const stores = (listing.stores ?? []).filter(store => !store.mirror && !containsPath(store.path, source));
    let target: string | undefined;
    if (stores.length) {
      const chosen = await vscode.window.showQuickPick([
        { label: "Choose another folder…", description: "Create a data store or use one already on disk", store: undefined as string | undefined },
        ...stores.map(store => ({ label: path.basename(store.path), description: store.path, store: store.path })),
      ], { title: "Import copies into a data store", placeHolder: "Original files stay in the source folder; identical content is not imported twice" });
      if (!chosen) return;
      target = chosen.store;
    }
    if (!target) {
      const selected = await vscode.window.showOpenDialog({ title: "Choose a folder for the log data store",
        openLabel: "Use as log store", canSelectFiles: false, canSelectFolders: true, canSelectMany: false });
      if (!selected?.length) return;
      if (selected[0].scheme !== "file") throw new Error("Choose a local folder for the log data store.");
      target = selected[0].fsPath;
    }
    const destination = await fs.promises.realpath(target);
    const reason = importDestinationError(await fs.promises.realpath(source), destination, listing);
    if (reason) throw new Error(reason);
    return destination;
  }

  private async pickRobot(listing: LogListing, store: string, assigning = false): Promise<string | null | undefined> {
    const picks = robotItems(listing, store).filter(p => !assigning || p.action !== "later");
    const choice = await vscode.window.showQuickPick(picks, { title: "Which robot?", placeHolder: "Use logged identity when available; otherwise this choice applies" });
    if (!choice) return undefined;
    if (choice.action === "later") return null;
    if (choice.action === "robot") return choice.robot;
    return vscode.window.showInputBox({ title: "New robot", prompt: "Letters, digits, dots, hyphens, and underscores",
      validateInput: robotNameError, ignoreFocusOut: true });
  }

  /** Inline group actions and assignment use the same explicit robot/move choices as an offer. */
  private async importItem(item?: LogItem, assigning = false): Promise<void> {
    if (!item || item.spec.kind === "pit" || item.node.readOnly) return;
    try {
      const node = item.node;
      if (node.kind === "log" && !node.log.store || node.kind === "directory") {
        const listing = await this.logs.listing(item.spec);
        const stores = (listing.stores ?? []).filter(store => !store.mirror);
        const target = await this.pickImportStore(listing, node.kind === "log" ? path.dirname(node.log.path) : node.folder);
        if (!target) return;
        const robot = await this.pickRobot(listing, target);
        if (robot === undefined) return;
        const offer = node.kind === "directory" ? offerFor(listing, node.folder, this.choices, true) : undefined;
        const files = node.kind === "log" ? [node.log.path] : offer ? await organizeSources(offer,
          stores.map(s => s.path), async folder => (await fs.promises.readdir(folder, { withFileTypes: true }))
            .map(e => ({ name: e.name, directory: e.isDirectory(), file: e.isFile(), symlink: e.isSymbolicLink() }))) : [];
        if (files.length) await this.importBatch(target, files, false, robot, false, true);
        return;
      }
      if (node.kind === "store") {
        const listing = await this.logs.listing(item.spec);
        const offer = offerFor(listing, node.folder, this.choices, true);
        if (offer) await this.showOffer(offer, listing);
        return;
      }
      if (node.kind !== "imports" && node.kind !== "importFile") return;
      const files = node.kind === "imports" ? node.files : [node.file];
      const paths = files.filter(f => f.state !== "importing").map(f => f.path);
      if (!paths.length) {
        void vscode.window.showInformationMessage("There are no files ready for this action. Inbox files import automatically after they stop growing.");
        return;
      }
      const store = node.kind === "imports" ? node.store : node.file.store;
      const assignment = assigning || node.group === "unassigned";
      const listing = await this.logs.listing(item.spec);
      const mode = assignment ? { mode: "move", move: true } : await vscode.window.showQuickPick(MOVE_CHOICES,
        { title: "Organize files", placeHolder: `Source store: ${store}. Choose where the organized logs will go.` });
      if (!mode) return;
      const target = mode.mode === "import" ? await this.pickImportStore(listing, store) : store;
      if (!target) return;
      const robot = await this.pickRobot(listing, target, assignment);
      if (robot === undefined) return;
      await this.importBatch(target, paths, mode.move, robot, assignment, mode.mode === "import");
    } catch (error) {
      this.reportImportError(error);
    }
  }

  private async importBatch(store: string, paths: string[], move: boolean, robot: string | null,
    assignment = false, rememberStore = false): Promise<void> {
    const client = await this.clientFor(this.spec());
    try {
      if (rememberStore) {
        const config = vscode.workspace.getConfiguration("wpilog-mcp");
        const previous = config.inspect<string[]>("additionalLogDirectories")?.globalValue ?? [];
        const directories = withImportDirectory(previous, store);
        if (directories !== previous) await config.update("additionalLogDirectories", directories, vscode.ConfigurationTarget.Global);
        // Await this client's serialized lease update, rather than racing the settings event.
        await client.refreshRegistration();
      }
      const result = await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: assignment ? "Assigning logs" : rememberStore ? "Importing copies" : "Organizing logs", cancellable: false }, progress =>
        runImport(client.endpoint, { store, paths, move, stated_robot: robot }, job => {
          progress.report({ message: progressText(job) });
        }, assignment));
      resultDetails(result).forEach(line => this.output.appendLine(line));
      void vscode.window.showInformationMessage(resultSummary(result));
      for (const message of sameRobotMessages(result)) void vscode.window.showInformationMessage(message);
    } finally {
      this.logs.refresh();
    }
  }

  private reportImportError(error: unknown): void {
    this.log(messageOf(error));
    void vscode.window.showErrorMessage(messageOf(error));
  }

  get extensionVersion(): string {
    return this.context.extension.packageJSON.version as string;
  }

  get extensionUri(): vscode.Uri {
    return this.context.extensionUri;
  }

  /** The server, with the projects it serves as they are now. */
  spec(): DaemonSpec {
    return this.windowSpec();
  }

  /**
   * A client to the server, which is started first when it is not running. One client, replaced
   * when the server's URL changes (a port taken by another program), so a session is never
   * aimed at a port nobody answers on.
   */
  async clientFor(spec: ExplorerSpec): Promise<McpClient> {
    const url = spec.kind === "pit" ? spec.url : await this.serverManager.ensure(spec);
    if (!url) {
      throw new Error("The server could not be started; the WPILog Analyzer output says why.");
    }
    const existing = this.clients.get(spec.name);
    if (existing && existing.url === url) return existing.client;
    if (existing) {
      void existing.client.dispose();
      // A server at a new URL was started again, perhaps with other directories: its streams are new
      this.data.clear();
    }
    const registration = spec.kind === "pit" ? undefined : async (): Promise<SessionRegistration> => {
      const base = this.registration ? await this.registration() : { directories: { paths: [], team: null }, key: null };
      const roots = this.context.workspaceState.get<string[]>(`wpilog-mcp.visualizationStores.${spec.name}`) ?? [];
      return { ...base, directories: { ...base.directories, paths: [...base.directories.paths,
        ...roots.map(path => ({ path, team: null }))] } };
    };
    const client = new McpClient(url, this.extensionVersion, registration, this.headersFor);
    this.clients.set(spec.name, { url, client });
    return client;
  }

  pitSpec(): Extract<ExplorerSpec, { kind: "pit" }> | undefined {
    const url = pitEndpoint(this.pitUrl());
    return url ? { kind: "pit", name: `pit:${url}`, url } : undefined;
  }

  /** A pit file's overview opens on this laptop: the pit's listing says what the file is, this
   * window's listing what the laptop holds. A REV item opens the log it is listed under. */
  async pitOverview(uri: vscode.Uri, listed?: ListedLog): Promise<PitOverview> {
    const remote = readRemoteLogUri(uri)!; const pit = this.pitSpec();
    if (!pit) return { message: "Set wpilog-mcp.pitServerUrl to read the pit's files on this laptop.", actions: [] };
    const origin = originOf(remote.url);
    const changed: PitOverview = { message: "This log belongs to a different pit server, or the configured connection changed. Restore wpilog-mcp.pitServerUrl to this log's server, or open a log from the configured pit.", actions: [] };
    const current = () => { const configured = this.pitSpec(); return configured && originOf(configured.url) === origin; };
    if (originOf(pit.url) !== origin) return changed;
    const target = listed?.kind === "revlog" && listed.wpilog ? listed.wpilog : remote.path;
    const pitListing = await this.logs.listing(pit);
    if (!current()) return changed;
    const name = target.replace(/\\/g, "/").split("/").pop() ?? target;
    const file = pitListing.logs?.find(log => log.path === target)
      ?? { path: target, filename: name, friendly_name: name, session: listed?.session ?? remote.session };
    const local = await this.logs.listing(this.spec());
    if (!current()) return changed;
    return pitOverview(file, local, origin, this.mirrorStatus(), Date.now());
  }

  async offlineCopy(source: ListedLog, url: string): Promise<ListedLog | undefined> {
    return mirroredCopy(source, await this.logs.listing(this.spec()), originOf(url));
  }

  /** Called when the server was started again or its URL changed: listings may have changed. */
  serversChanged(): void {
    this.logs.refresh();
  }

  log(message: string): void {
    this.output.appendLine(message);
  }

  /** Ends every server session this window holds, waiting at most `boundMs`. Each client
   * refuses new requests first, then ends its session, which releases the window's views. */
  endSessions(boundMs: number): Promise<boolean> {
    const clients = [...this.clients.values()].map(entry => entry.client);
    this.clients.clear();
    return endWithin(clients.map(client => client.dispose()), boundMs);
  }

  dispose(): void {
    void this.endSessions(SESSION_END_MS);
    for (const d of this.disposables) d.dispose();
  }
}

/** The message of an error, for a note in a tree or the editor. */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** The entry (with its field path, for a field) a command was given: a name, or a tree item. */
function entryNameOf(nameOrItem: string | EntryItem): string {
  if (typeof nameOrItem === "string") return nameOrItem;
  const node = nameOrItem.node;
  if (node.kind === "entry") return node.entry.name;
  if (node.kind === "field") return node.entry.name + node.fieldPath;
  return "";
}

/** A tree item that remembers the model node it draws. */
class LogItem extends vscode.TreeItem {
  constructor(
    readonly node: LogNode,
    readonly spec: ExplorerSpec,
    collapsibleState: vscode.TreeItemCollapsibleState
  ) {
    super(node.label, collapsibleState);
    switch (node.kind) {
      case "store":
      case "directory":
        this.iconPath = vscode.ThemeIcon.Folder;
        this.tooltip = node.tooltip ?? node.folder;
        this.description = node.description;
        this.contextValue = "wpilogDirectory";
        break;
      case "sourceSync":
        this.iconPath = new vscode.ThemeIcon("sync");
        this.tooltip = node.tooltip;
        break;
      case "robot":
      case "session":
        this.iconPath = new vscode.ThemeIcon(node.kind === "robot" ? "circuit-board" : "history");
        this.tooltip = node.tooltip;
        break;
      case "imports":
        this.iconPath = new vscode.ThemeIcon(node.group === "inbox" ? "inbox" : "folder");
        this.description = String(node.files.length);
        this.contextValue = node.group === "unassigned" ? "wpilogUnassignedGroup" : "wpilogImportGroup";
        break;
      case "importFile":
        this.resourceUri = vscode.Uri.file(node.file.path);
        this.description = node.description;
        this.tooltip = node.tooltip;
        this.contextValue = node.group === "unassigned" ? "wpilogUnassigned" : "wpilogImportFile";
        if (node.file.kind === "wpilog" || path.extname(node.file.path).toLowerCase() === ".wpilog") {
          this.command = { command: "wpilog-mcp.explorer.openLog", title: "Open in WPILog Explorer", arguments: [node.file.path] };
        }
        break;
      case "event":
        this.iconPath = new vscode.ThemeIcon("calendar");
        break;
      case "date":
        this.iconPath = new vscode.ThemeIcon("history");
        break;
      case "log":
        this.resourceUri = spec.kind === "pit" ? vscode.Uri.parse(remoteLogUri(spec.url, node.log.path, node.log.session)) : vscode.Uri.file(node.log.path);
        this.description = node.description;
        this.tooltip = node.tooltip;
        this.contextValue = node.log.store || node.log.kind === "revlog" ? "wpilogLog" : "wpilogPlainLog";
        this.command = {
          command: "wpilog-mcp.explorer.openLog",
          title: "Open in WPILog Explorer",
          arguments: [node.log.wpilog ?? node.log.path, spec, node.log],
        };
        break;
      case "note":
        this.iconPath = new vscode.ThemeIcon("info");
        this.tooltip = node.tooltip;
        break;
    }
    if (node.readOnly) this.contextValue = node.kind === "log" ? "wpilogLog" : "wpilogMirrorGroup";
    if (spec.kind === "pit") this.contextValue = node.kind === "log" ? "wpilogPitLog" : "wpilogPitGroup";
  }
}

/** The Logs view: the server's listing, grouped as logsTree.ts decides, with a filter. */
export class LogsProvider implements vscode.TreeDataProvider<LogItem> {
  private readonly changed = new vscode.EventEmitter<LogItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private readonly listings = new Map<string, Promise<LogListing>>();
  private filter = "";
  private allSources = false;

  toggleSources(): void {
    this.allSources = !this.allSources; this.refresh();
    void vscode.window.showInformationMessage(this.allSources ? "Showing all source files, including imported originals." : "Showing the preferred store and sources still awaiting import.");
  }

  constructor(private readonly explorer: Explorer) {}

  refresh(): void {
    this.listings.clear();
    this.changed.fire(undefined);
  }

  setFilter(filter: string): void {
    this.filter = filter;
    void vscode.commands.executeCommand("setContext", "wpilog-mcp.logFilter", filter.trim() !== "");
    this.changed.fire(undefined);
  }

  async askFilter(): Promise<void> {
    const value = await vscode.window.showInputBox({
      title: "Filter logs",
      prompt: "Part of a log's name, event, or match (blank shows every log)",
      value: this.filter,
    });
    if (value !== undefined) this.setFilter(value);
  }

  getTreeItem(element: LogItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: LogItem): Promise<LogItem[]> {
    if (!element) {
      const local = this.explorer.spec(), pit = this.explorer.pitSpec();
      if (!pit || pit.kind !== "pit") return this.nodesOf(local);
      const [here, there] = await Promise.all([this.listing(local), this.listing(pit)]);
      return [new LogItem({ kind: "directory", label: "This laptop", folder: "", children: buildLogTree(here, this.filter) }, local, vscode.TreeItemCollapsibleState.Expanded),
        new LogItem({ kind: "directory", label: "Pit server", folder: pit.url, children: pitLogTree(there, here, originOf(pit.url), this.filter) }, pit, vscode.TreeItemCollapsibleState.Expanded)];
    }
    if ("children" in element.node) {
      return element.node.children.map(
        (child, index) =>
          new LogItem(child, element.spec, "children" in child
            ? index === 0 ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed
            : vscode.TreeItemCollapsibleState.None)
      );
    }
    return [];
  }

  private async nodesOf(spec: ExplorerSpec): Promise<LogItem[]> {
    const listing = await this.listing(spec);
    return buildLogTree(listing, this.filter).map(
      (node, index) =>
        new LogItem(node, spec, "children" in node
          ? index === 0 || node.kind !== "event" ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed
          : vscode.TreeItemCollapsibleState.None)
    );
  }

  /** The server's listing, fetched once until the next refresh; a failure is a listing that says so. */
  listing(spec: ExplorerSpec): Promise<LogListing> {
    let pending = this.listings.get(spec.name);
    if (!pending) {
      pending = this.fetch(spec);
      this.listings.set(spec.name, pending);
    }
    return pending;
  }

  private async fetch(spec: ExplorerSpec): Promise<LogListing> {
    try {
      const client = await this.explorer.clientFor(spec);
      const listing = await completeListing(args => client.callTool("list_available_logs", this.allSources ? { ...args, view: "all" } : args) as Promise<LogListing>);
      // Do not hold up the tree while the person considers an offer.
      if (spec.kind !== "pit") void this.explorer.offerOrganizing(listing);
      return listing;
    } catch (error) {
      if (error instanceof ToolError && error.result) return error.result as LogListing;
      this.explorer.log(`Logs view: ${messageOf(error)}`);
      return { status: "error", error: messageOf(error) };
    }
  }
}

export class EntryItem extends vscode.TreeItem {
  constructor(readonly node: EntryNode, collapsibleState: vscode.TreeItemCollapsibleState) {
    super(node.label, collapsibleState);
    switch (node.kind) {
      case "group":
        this.iconPath = vscode.ThemeIcon.Folder;
        this.tooltip = node.path;
        this.description = node.description;
        break;
      case "entry":
        this.description = node.description;
        this.tooltip = node.tooltip;
        this.iconPath = new vscode.ThemeIcon(node.expandable ? "symbol-structure" : "symbol-field");
        this.contextValue = "wpilogEntry";
        // A click plots a number; a struct or array has fields to plot beneath it, so a click shows it
        this.command = node.expandable || !plottable(node.entry.type)
          ? { command: "wpilog-mcp.explorer.showEntry", title: "Show Entry", arguments: [node.entry.name] }
          : { command: "wpilog-mcp.explorer.plotEntry", title: "Plot Entry", arguments: [node.entry.name] };
        break;
      case "field":
        this.description = node.description;
        this.tooltip = node.entry.name + node.fieldPath;
        this.iconPath = new vscode.ThemeIcon(node.elements ? "symbol-array" : "symbol-number");
        // A [*] path pools every element: it expands to them, and a click shows the entry
        this.contextValue = node.elements ? "wpilogFieldPooled" : "wpilogField";
        this.command = node.fieldPath.includes("[*]")
          ? { command: "wpilog-mcp.explorer.showEntry", title: "Show Entry", arguments: [node.entry.name] }
          : { command: "wpilog-mcp.explorer.plotEntry", title: "Plot Field", arguments: [node.entry.name + node.fieldPath] };
        break;
      case "note":
        this.iconPath = new vscode.ThemeIcon("info");
        this.tooltip = node.tooltip;
        break;
    }
  }
}

/** Whether an entry's type is plotted as it is: a number or a boolean. */
function plottable(type: string): boolean {
  return type === "double" || type === "float" || type === "int64" || type === "boolean";
}

/** The Entries view: the active log's entries as entriesTree.ts decides, with a filter. */
export class EntriesProvider implements vscode.TreeDataProvider<EntryItem> {
  private readonly changed = new vscode.EventEmitter<EntryItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private active?: OpenLog;
  private activeListing?: EntryListing;
  targetLabel = "";
  private filter = "";
  private filterReset = 0;
  private groupingValue: EntryGrouping;
  private readonly infos = new EntryInfo<ExplorerSpec>(async (spec, logPath, name) => {
    try {
      const client = await this.explorer.clientFor(spec);
      return await client.callTool("get_entry_info", { path: logPath, name });
    } catch (error) {
      if (error instanceof ToolError && error.result) return error.result;
      return { status: "error", error: messageOf(error) };
    }
  });

  constructor(private readonly explorer: Explorer,
    private readonly state?: Pick<vscode.Memento, "get" | "update">) {
    this.groupingValue = state?.get(ENTRY_GROUPING_KEY) === "type" ? "type" : "path";
  }

  /** The sidebar displays the same query that the server uses for the entry listing. */
  get filterText(): string { return this.filter; }
  /** Explicit clears and log changes also cancel a query still being typed in the sidebar. */
  get filterResetVersion(): number { return this.filterReset; }
  get grouping(): EntryGrouping { return this.groupingValue; }

  /** Apply the visible query with a grouping change, even if its debounce has not fired yet. */
  setGrouping(grouping: EntryGrouping, filter = this.filter): void {
    if (grouping === this.groupingValue && filter === this.filter) return;
    const changedGrouping = grouping !== this.groupingValue;
    this.groupingValue = grouping;
    if (filter !== this.filter) this.updateFilter(filter);
    if (changedGrouping && this.state) {
      void this.state.update(ENTRY_GROUPING_KEY, grouping).then(undefined,
        error => this.explorer.log(`Could not save Entries grouping: ${messageOf(error)}`));
    }
    this.changed.fire(undefined);
  }

  /** Shows a log's entries: the one the editor just loaded, or none. */
  setActive(log: OpenLog | undefined, target = ""): void {
    // Returning focus from the sidebar to this editor does not change its listing or search.
    if (log === this.active && log?.listing === this.activeListing && this.targetLabel === target) return;
    this.targetLabel = target;
    if (log?.path !== this.active?.path || log?.spec !== this.active?.spec) {
      this.updateFilter("");
    }
    this.active = log;
    this.activeListing = log?.listing;
    void vscode.commands.executeCommand("setContext", "wpilog-mcp.hasActiveLog", log !== undefined);
    this.changed.fire(undefined);
  }

  setFilter(filter: string): void {
    this.updateFilter(filter);
    this.changed.fire(undefined);
  }

  private updateFilter(filter: string): void {
    this.filter = filter;
    this.filterReset++;
    void vscode.commands.executeCommand("setContext", "wpilog-mcp.entryFilter", filter.trim() !== "");
  }

  getTreeItem(element: EntryItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: EntryItem): Promise<EntryItem[]> {
    if (!element) {
      const active = this.active;
      const grouping = this.grouping;
      if (!active) {
        return [new EntryItem({ kind: "note", label: "Open a log in WPILog Explorer to list its entries" }, vscode.TreeItemCollapsibleState.None)];
      }
      const listing = await this.listing(active);
      return buildEntryTree(listing, grouping).map((node) => this.item(node, grouping === "path"));
    }
    const node = element.node;
    if (node.kind === "group") return node.children.map((child) => this.item(child, false));
    if (node.kind === "entry" && node.expandable) {
      return buildFieldNodes(node.entry, await this.info(node.entry)).map((child) => this.item(child, false));
    }
    if (node.kind === "field" && node.elements) {
      return elementNodes(node).map((child) => this.item(child, false));
    }
    return [];
  }

  private item(node: EntryNode, root: boolean): EntryItem {
    const state =
      node.kind === "group"
        ? root ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed
        : (node.kind === "entry" && node.expandable) || (node.kind === "field" && node.elements)
          ? vscode.TreeItemCollapsibleState.Collapsed
          : vscode.TreeItemCollapsibleState.None;
    return new EntryItem(node, state);
  }

  /** The listing: the editor's own when there is no filter, else list_entries with the pattern. */
  private async listing(active: OpenLog): Promise<EntryListing> {
    const pattern = this.filter.trim();
    if (pattern === "" && active.listing) return active.listing;
    if (!active.spec) return { status: "error", error: "The log is not loaded" };
    try {
      const client = await this.explorer.clientFor(active.spec);
      const args: Record<string, unknown> = { path: active.path };
      if (pattern !== "") args.pattern = pattern;
      return (await client.callTool("list_entries", args)) as EntryListing;
    } catch (error) {
      if (error instanceof ToolError && error.result) return error.result as EntryListing;
      return { status: "error", error: messageOf(error) };
    }
  }

  /** An entry's description belongs to the editor that requested it. */
  info(entry: ListedEntry, log = this.active): Promise<Record<string, unknown>> {
    return this.infos.read(log, entry.name);
  }

}

/** A log document: the editor is read-only, so the document is only its file. */
class LogDocument implements vscode.CustomDocument {
  constructor(readonly uri: vscode.Uri) {}
  dispose(): void {}
}

/** A message from the webview. */
interface WebviewMessage {
  type?: string;
  name?: string;
  requestId?: number;
  startTime?: number;
  endTime?: number;
  maxPoints?: number;
  pattern?: string;
  level?: string;
  enabled?: boolean;
  follow?: boolean;
  offset?: number;
  selection?: Selection;
  rows?: number;
  error?: string;
}

/** One editor: its panel and the log it shows. */
interface Editor {
  panel: vscode.WebviewPanel;
  log: OpenLog;
  ready: boolean;
  followTimer?: ReturnType<typeof setInterval>;
  following?: boolean;
  refreshing?: boolean;
  loaded: Promise<void>;
  loadedResolve: () => void;
  requests: Map<number, (message: WebviewMessage) => void>;
  dataResult?: (message: WebviewMessage) => void;
}

/**
 * The custom editor for `.wpilog` files: a webview that shows the log's time range and entries
 * from `list_entries`, and an entry's description from `get_entry_info` when one is picked, in
 * the editor or in the Entries view. The log is read by the server, which reads only inside its
 * log directories; when it cannot, the editor says what the server said, with its hint.
 */
export class ExplorerEditorProvider implements vscode.CustomReadonlyEditorProvider<LogDocument> {
  private readonly editors = new Map<string, Editor>();
  private activeKey?: string;
  private requestId = 0;

  constructor(private readonly explorer: Explorer) {}

  openCustomDocument(uri: vscode.Uri): LogDocument {
    return new LogDocument(uri);
  }

  resolveCustomEditor(document: LogDocument, panel: vscode.WebviewPanel): void {
    const key = panel.webview.toString() + document.uri.toString();
    const media = vscode.Uri.joinPath(this.explorer.extensionUri, "media");
    panel.webview.options = { enableScripts: true, localResourceRoots: [media] };
    const asset = (...segments: string[]) => panel.webview.asWebviewUri(vscode.Uri.joinPath(media, ...segments)).toString();
    panel.webview.html = explorerPage({
      cspSource: panel.webview.cspSource,
      styleUri: asset("explorer.css"),
      scriptUri: asset("explorer.js"),
      plot: {
        styleUri: asset("vendor", "uPlot.min.css"),
        scriptUris: [asset("vendor", "uPlot.iife.min.js"), asset("arrowStream.js"), asset("plotMath.js"), asset("follow.js"), asset("dataView.js"), asset("chartSpec.js"), asset("chartPane.js"), asset("plot.js"), asset("console.js"), asset("field.js"), asset("rev.js")],
      },
      data: { scriptUri: asset("dataPane.mjs"), styleUri: asset("vendor", "perspective", "pro.css") },
      nonce: crypto.randomBytes(16).toString("hex"),
    });
    const remote = readRemoteLogUri(document.uri);
    let loadedResolve!: () => void;
    const loaded = new Promise<void>(resolve => { loadedResolve = resolve; });
    const editor: Editor = { panel, loaded, loadedResolve, requests: new Map(), log: remote ? { path: remote.path,
      spec: { kind: "pit", name: `pit:${remote.url}`, url: remote.url },
      source: { path: remote.path, filename: remote.path.replace(/\\/g, "/").split("/").pop()!, friendly_name: "Pit session", session: remote.session } }
      : { path: document.uri.fsPath }, ready: false };
    this.editors.set(key, editor);
    panel.webview.onDidReceiveMessage((message: WebviewMessage) => {
      switch (message.type) {
        case "ready":
          editor.ready = true;
          void this.load(editor).finally(editor.loadedResolve);
          break;
        case "selection":
          if (message.requestId !== undefined) { editor.requests.get(message.requestId)?.(message); editor.requests.delete(message.requestId); }
          break;
        case "dataViewState":
          editor.dataResult?.(message); editor.dataResult = undefined;
          break;
        case "follow":
          clearInterval(editor.followTimer); editor.following = message.enabled === true;
          if (editor.following) editor.followTimer = setInterval(() => void this.refreshLive(editor), 1000);
          break;
        case "entryInfo":
          if (typeof message.name === "string") void this.sendInfo(editor, message.name);
          break;
        case "fetch":
          void this.sendData(editor, message);
          break;
        case "timeline":
          void this.sendTimeline(editor);
          break;
        case "statistics":
          void this.sendStatistics(editor, message);
          break;
        case "console":
          void this.sendConsole(editor, message);
          break;
        case "field":
          void this.sendField(editor);
          break;
        case "chart":
          if (message.selection) void this.sendChart(editor, message.selection);
          break;
        case "rev":
          void this.sendRev(editor);
          break;
        default:
          break;
      }
    });
    panel.onDidChangeViewState(() => {
      if (panel.active) this.activate(key);
    });
    panel.onDidDispose(() => {
      clearInterval(editor.followTimer); editor.following = false;
      this.editors.delete(key);
      if (this.activeKey === key) {
        this.activeKey = undefined;
        this.explorer.entries.setActive(undefined);
      }
    });
    if (panel.active) this.activate(key);
  }

  private activate(key: string): void {
    this.activeKey = key;
    const editor = this.editors.get(key);
    this.explorer.entries.setActive(editor?.log);
  }

  /**
   * Lists the log through the server and shows the result. A file outside the server's log
   * directories is refused by the server itself, with its own words and hint.
   */
  private async load(editor: Editor): Promise<void> {
    editor.following = false; clearInterval(editor.followTimer);
    const { path: logPath } = editor.log;
    const name = path.basename(logPath);
    const post = (message: Record<string, unknown>) => void editor.panel.webview.postMessage(message);
    post({ type: "loading", name, path: logPath, text: "Starting the server…" });
    const spec = editor.log.spec ?? this.explorer.spec();
    let firstError: { message: string; hint?: string } | undefined;
    try {
      const client = await this.explorer.clientFor(spec);
      post({ type: "loading", name, path: logPath, text: "Reading the log…" });
      const listing = (await client.callTool("list_entries", { path: logPath })) as EntryListing;
      editor.log = { ...editor.log, path: logPath, spec, listing };
      post({ type: "log", name, path: logPath, listing, copy: editor.log.copy ?? (spec.kind === "pit" ? "Reading the pit server" : "Reading this laptop"), live: spec.kind === "pit" && editor.log.source?.session?.open === true });
      if (editor.panel.active && this.editors.get(this.activeKey ?? "") === editor) this.explorer.entries.setActive(editor.log);
      return;
    } catch (error) {
      const hint = error instanceof ToolError ? (error.result?.hint as string | undefined) : undefined;
      firstError = { message: messageOf(error), hint };
      this.explorer.log(`Explorer: the server could not read ${logPath}: ${messageOf(error)}`);
      if (spec.kind === "pit" && editor.log.source && !(error instanceof ToolError && error.result)) {
        const copy = await this.explorer.offlineCopy(editor.log.source, spec.url);
        if (copy) {
          editor.log = { path: copy.path, spec: this.explorer.spec(), source: copy,
            copy: `Reading the offline mirror · last synchronized ${copy.session?.last_sync ?? "unknown"}` };
          await this.load(editor); return;
        }
      }
    }
    post({
      type: "error",
      name,
      path: logPath,
      message: firstError?.message ?? "The log could not be read.",
      hint:
        firstError?.hint ??
        "A server reads only the files inside its log directories: add this file's folder to " +
          "wpilog-mcp.logDirectory or wpilog-mcp.additionalLogDirectories, or move the file there.",
    });
  }

  private async refreshLive(editor: Editor): Promise<void> {
    if (!editor.following || !editor.panel.visible || editor.refreshing || editor.log.spec?.kind !== "pit") return;
    editor.refreshing = true;
    try {
      const client = await this.explorer.clientFor(editor.log.spec);
      const listing = await client.callTool("list_entries", { path: editor.log.path }) as EntryListing;
      if (editor.following) {
        editor.log.listing = listing;
        if (editor.panel.active && this.editors.get(this.activeKey ?? "") === editor) this.explorer.entries.setActive(editor.log);
        void editor.panel.webview.postMessage({ type: "live", listing });
      }
    } catch (error) {
      editor.following = false; clearInterval(editor.followTimer);
      void editor.panel.webview.postMessage({ type: "followStopped", reason: messageOf(error) });
      // The ordinary load path checks exact origin/session identity before offering the mirror.
      await this.load(editor);
    } finally { editor.refreshing = false; }
  }

  private async sendInfo(editor: Editor, name: string): Promise<void> {
    const log = editor.log, listing = log.listing;
    const info = await this.explorer.entries.info({ name, type: "", sample_count: 0 }, log);
    if (editor.log !== log || log.listing !== listing) return;
    void editor.panel.webview.postMessage({ type: "entryInfo", name, info });
  }

  /**
   * The plot's data: the stream's bytes from the data endpoint of the server that read the log,
   * handed to the webview as they came. A refusal keeps the server's words; over the size cap,
   * the plot asks again for buckets.
   */
  private async sendData(editor: Editor, message: WebviewMessage): Promise<void> {
    const { requestId, name } = message;
    if (typeof requestId !== "number" || typeof name !== "string") return;
    const post = (reply: Record<string, unknown>) => void editor.panel.webview.postMessage(reply);
    const spec = editor.log.spec;
    if (!spec) {
      post({ type: "dataError", requestId, name, message: "The log is not loaded" });
      return;
    }
    try {
      const client = await this.explorer.clientFor(spec);
      const response = await this.explorer.data.fetch(client.endpoint, {
        path: editor.log.path,
        names: [name],
        startTime: typeof message.startTime === "number" ? message.startTime : undefined,
        endTime: typeof message.endTime === "number" ? message.endTime : undefined,
        maxPoints: typeof message.maxPoints === "number" ? message.maxPoints : undefined,
      });
      post({ type: "data", requestId, name, bytes: response.bytes, fromCache: response.fromCache });
    } catch (error) {
      this.explorer.log(`Explorer: ${name} from ${editor.log.path}: ${messageOf(error)}`);
      post({
        type: "dataError",
        requestId,
        name,
        message: messageOf(error),
        tooLarge: error instanceof TooLargeError,
        status: error instanceof DataError ? error.status : undefined,
      });
    }
  }

  /** The timeline's phases and events, each from its tool; one that fails is sent as its result. */
  private async sendTimeline(editor: Editor): Promise<void> {
    const spec = editor.log.spec;
    if (!spec) return;
    const call = async (tool: string): Promise<Record<string, unknown> | null> => {
      try {
        const client = await this.explorer.clientFor(spec);
        return await client.callTool(tool, { path: editor.log.path });
      } catch (error) {
        if (error instanceof ToolError && error.result) return error.result;
        this.explorer.log(`Explorer: ${tool} on ${editor.log.path}: ${messageOf(error)}`);
        return null;
      }
    };
    const [phases, ds] = await Promise.all([call("get_match_phases"), call("get_ds_timeline")]);
    void editor.panel.webview.postMessage({ type: "timeline", phases, ds });
  }

  /** get_statistics over the visible window, as the server states it. */
  private async sendStatistics(editor: Editor, message: WebviewMessage): Promise<void> {
    const { requestId, name } = message;
    if (typeof requestId !== "number" || typeof name !== "string") return;
    const spec = editor.log.spec;
    if (!spec) return;
    let result: Record<string, unknown>;
    try {
      const client = await this.explorer.clientFor(spec);
      const args: Record<string, unknown> = { path: editor.log.path, name };
      if (typeof message.startTime === "number") args.start_time = message.startTime;
      if (typeof message.endTime === "number") args.end_time = message.endTime;
      result = await client.callTool("get_statistics", args);
    } catch (error) {
      result = error instanceof ToolError && error.result ? error.result : { status: "error", error: messageOf(error) };
    }
    void editor.panel.webview.postMessage({ type: "statistics", requestId, name, result });
  }

  /** The console pane's lines: search_strings over the window, repeats collapsed, as the server lists them. */
  private async sendConsole(editor: Editor, message: WebviewMessage): Promise<void> {
    const { requestId } = message;
    if (typeof requestId !== "number") return;
    const spec = editor.log.spec;
    if (!spec) return;
    let result: Record<string, unknown>;
    try {
      const client = await this.explorer.clientFor(spec);
      const args: Record<string, unknown> = { path: editor.log.path, limit: CONSOLE_LIMIT, collapse_repeats: message.follow !== true };
      if (typeof message.offset === "number") args.offset = message.offset;
      if (typeof message.pattern === "string" && message.pattern.trim() !== "") args.pattern = message.pattern.trim();
      if (typeof message.level === "string" && message.level !== "any") args.level = message.level;
      if (typeof message.startTime === "number") args.start_time = message.startTime;
      if (typeof message.endTime === "number") args.end_time = message.endTime;
      result = await client.callTool("search_strings", args);
    } catch (error) {
      result = error instanceof ToolError && error.result ? error.result : { status: "error", error: messageOf(error) };
    }
    void editor.panel.webview.postMessage({ type: "console", requestId, result });
  }

  /**
   * What the field view needs: the robot pose the signal resolver finds, with its candidates
   * (the server does not guess: a heuristic match has no entry and the person picks), the
   * season the log was recorded in, and that season's field geometry from the bundled game data.
   */
  private async sendField(editor: Editor): Promise<void> {
    const spec = editor.log.spec;
    if (!spec) return;
    const call = async (tool: string, args: Record<string, unknown>): Promise<Record<string, unknown> | null> => {
      try {
        const client = await this.explorer.clientFor(spec);
        return await client.callTool(tool, args);
      } catch (error) {
        if (error instanceof ToolError && error.result) return error.result;
        this.explorer.log(`Explorer: ${tool} on ${editor.log.path}: ${messageOf(error)}`);
        return null;
      }
    };
    const [signals, phases] = await Promise.all([
      call("resolve_signals", { path: editor.log.path, roles: ["robot_pose"] }),
      call("get_match_phases", { path: editor.log.path }),
    ]);
    const season = (phases?.season as { year?: number } | undefined)?.year;
    const game = await call("get_game_info", typeof season === "number" ? { season } : {});
    const role = (signals?.roles as Record<string, unknown> | undefined)?.robot_pose ?? null;
    void editor.panel.webview.postMessage({
      type: "field",
      pose: role,
      season: typeof season === "number" ? season : (game?.season as number | undefined),
      game: game ? { game_name: game.game_name, source: game.source, manual_version: game.manual_version, field_geometry: game.field_geometry, status: game.status, error: game.error } : null,
    });
  }

  /**
   * The REV pane's listing: the synchronization runs in the background when a log is loaded, so
   * wait for it as a client does (wait_for_sync, bounded), then list the signals with how each
   * bus was aligned; a log with no REV log gets the server's reason.
   */
  private async sendRev(editor: Editor): Promise<void> {
    const spec = editor.log.spec;
    if (!spec) return;
    let result: Record<string, unknown>;
    try {
      const client = await this.explorer.clientFor(spec);
      try {
        await client.callTool("wait_for_sync", { path: editor.log.path, timeout_ms: 30_000 });
      } catch (error) {
        this.explorer.log(`Explorer: wait_for_sync on ${editor.log.path}: ${messageOf(error)}`);
      }
      result = await client.callTool("list_revlog_signals", { path: editor.log.path });
    } catch (error) {
      result = error instanceof ToolError && error.result ? error.result : { status: "error", error: messageOf(error) };
    }
    void editor.panel.webview.postMessage({ type: "rev", result });
  }

  private async sendChart(editor: Editor, selection: Selection): Promise<void> {
    if (!editor.log.spec) return;
    try {
      const client = await this.explorer.clientFor(editor.log.spec);
      const result = await client.callTool("render_chart", { path: editor.log.path, entries: selection.entries,
        start_time: selection.start, end_time: selection.end, kind: selection.kind });
      void editor.panel.webview.postMessage({ type: "chart", result });
    } catch (error) { void editor.panel.webview.postMessage({ type: "chart", result: { error: messageOf(error) } }); }
  }

  /** Plots an entry in the active editor: the Entries view's click. */
  plotEntry(name: string): void {
    const editor = this.editors.get(this.activeKey ?? "");
    if (!editor || !editor.panel.active || name === "") return;
    editor.panel.reveal(undefined, true);
    void editor.panel.webview.postMessage({ type: "plotEntry", name });
  }

  /** Shows an entry in the active editor: the Entries view's click. */
  selectEntry(name: string): void {
    const editor = this.editors.get(this.activeKey ?? "");
    if (!editor || !editor.panel.active) return;
    editor.panel.reveal(undefined, true);
    void editor.panel.webview.postMessage({ type: "selectEntry", name });
  }

  private async current(): Promise<Editor> {
    const editor = this.editors.get(this.activeKey ?? "");
    if (!editor) throw new Error("Open a log in WPILog Explorer first");
    await editor.loaded; return editor;
  }

  async applySelection(selection: Selection): Promise<void> {
    const editor = await this.current();
    await editor.panel.webview.postMessage({ type: "applySelection", selection });
  }

  /** Real-editor tests observe the real worker's table, never a host-side row counter. */
  async inspectData(names: string[], startTime: number, endTime: number, append = false): Promise<number> {
    const editor = await this.current();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { editor.dataResult = undefined; reject(new Error("Data view did not answer")); }, 30_000);
      editor.dataResult = message => { clearTimeout(timer); if (message.error) reject(new Error(message.error)); else resolve(message.rows!); };
      void editor.panel.webview.postMessage({ type: "dataView", names, startTime, endTime, append });
    });
  }

  private async selection(kind?: string): Promise<{ editor: Editor; selection: Selection }> {
    const editor = await this.current(), requestId = ++this.requestId;
    const selection = await new Promise<Selection>((resolve, reject) => {
      const timer = setTimeout(() => { editor.requests.delete(requestId); reject(new Error("The editor did not return its selection")); }, 5000);
      editor.requests.set(requestId, message => { clearTimeout(timer); resolve(message.selection!); });
      void editor.panel.webview.postMessage({ type: "getSelection", requestId, kind });
    });
    return { editor, selection: { ...selection, path: editor.log.path } };
  }

  async openNotebook(): Promise<void> {
    try {
      const { editor, selection } = await this.selection();
      if (!editor.log.spec) throw new Error("The log is not loaded");
      const client = await this.explorer.clientFor(editor.log.spec);
      const arrow = require("../media/arrowStream.js");
      const inputs: Record<string, unknown> = {};
      for (const name of selection.entries) {
        const response = await this.explorer.data.fetch(client.endpoint, { path: selection.path, names: [name], startTime: selection.start, endTime: selection.end });
        inputs[name] = arrow.parsedMetadata(arrow.read(response.bytes)).inputs;
      }
      const content = JSON.stringify(notebook({ ...selection, inputs }, client.endpoint), null, 2) + "\n";
      const basename = path.basename(selection.path).replace(/\.wpilog$/i, "") + ".ipynb";
      const folders = [...(editor.log.spec.kind === "pit" ? [] : [path.dirname(selection.path)]),
        ...(vscode.workspace.workspaceFolders ?? []).filter(f => f.uri.scheme === "file").map(f => f.uri.fsPath)];
      let destination: string | undefined;
      for (const folder of [...new Set(folders)]) {
        try {
          await fs.promises.access(folder, fs.constants.W_OK);
          for (let n = 0; n < 1000; n++) {
            const file = path.join(folder, n === 0 ? basename : basename.replace(/\.ipynb$/, `-${n}.ipynb`));
            try { await fs.promises.writeFile(file, content, { flag: "wx" }); destination = file; break; }
            catch (error) { if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error; }
          }
          if (destination) break;
        } catch { /* Try the workspace when the log's folder cannot be written. */ }
      }
      if (!destination) throw new Error("No writable log folder or workspace folder for the notebook");
      const document = await vscode.workspace.openNotebookDocument(vscode.Uri.file(destination));
      await vscode.window.showNotebookDocument(document);
    } catch (error) { void vscode.window.showErrorMessage(messageOf(error)); }
  }

  async askSelection(kind?: string): Promise<void> {
    try {
      const { selection } = await this.selection(kind);
      const query = selectionPrompt(selection);
      try { await vscode.commands.executeCommand("workbench.action.chat.open", { query }); }
      catch { await vscode.env.clipboard.writeText(query); void vscode.window.showInformationMessage("The selection prompt was copied to the clipboard; paste it into your assistant."); }
    } catch (error) { void vscode.window.showErrorMessage(messageOf(error)); }
  }

  /** Reads the active editor's log again: the file changed, or the server was restarted. */
  reloadActive(): void {
    const editor = this.editors.get(this.activeKey ?? "");
    if (editor?.ready) void this.load(editor);
  }
}
