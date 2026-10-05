/**
 * WPILog Explorer's VS Code side (EXPLORER_PLAN.md §3, §4): the clients to the window's servers,
 * the Logs and Entries views, and the custom editor that opens a log and shows its entries and
 * time range. Every number shown comes from a tool (decision 3): the listing, the entries, and an
 * entry's description are the results of `list_available_logs`, `list_entries`, and
 * `get_entry_info`, drawn as they are. The decisions that need no VS Code API are in
 * `explorer/*.ts` and tested there; this file is the glue.
 */
import * as crypto from "crypto";
import * as path from "path";
import * as vscode from "vscode";
import { McpClient, ToolError } from "./mcpClient";
import { DaemonSpec, ServerManager } from "./serverManager";
import { EntryListing, EntryNode, ListedEntry, buildEntryTree, buildFieldNodes } from "./explorer/entriesTree";
import { LogListing, LogNode, buildLogTree } from "./explorer/logsTree";
import { rankServersForFile } from "./explorer/serverChoice";
import { explorerPage } from "./explorer/webviewHtml";

/** The custom editor's view type, as package.json declares it. */
export const EDITOR_VIEW_TYPE = "wpilog-mcp.explorer";
export const LOGS_VIEW = "wpilog-mcp.logs";
export const ENTRIES_VIEW = "wpilog-mcp.entries";

/** The most logs one listing asks for: the server's page limit. */
const LISTING_LIMIT = 500;

/** A log open in the editor: its file, the server that read it, and its listing. */
interface OpenLog {
  path: string;
  spec?: DaemonSpec;
  listing?: EntryListing;
}

/** The explorer: owns the clients, the views, and the editor, and wires them together. */
export class Explorer implements vscode.Disposable {
  private readonly clients = new Map<string, { url: string; client: McpClient }>();
  readonly logs: LogsProvider;
  readonly entries: EntriesProvider;
  readonly editor: ExplorerEditorProvider;
  private readonly disposables: vscode.Disposable[] = [];

  /**
   * @param windowSpecs the servers the window's folders use, as extension.ts computes them
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
    private readonly serverManager: ServerManager,
    private readonly windowSpecs: () => DaemonSpec[]
  ) {
    this.logs = new LogsProvider(this);
    this.entries = new EntriesProvider(this);
    this.editor = new ExplorerEditorProvider(this);
    this.disposables.push(
      vscode.window.registerTreeDataProvider(LOGS_VIEW, this.logs),
      vscode.window.registerTreeDataProvider(ENTRIES_VIEW, this.entries),
      vscode.window.registerCustomEditorProvider(EDITOR_VIEW_TYPE, this.editor, {
        webviewOptions: { retainContextWhenHidden: true },
        supportsMultipleEditorsPerDocument: false,
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.refreshLogs", () => this.logs.refresh()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.filterLogs", () => this.logs.askFilter()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.clearLogFilter", () => this.logs.setFilter("")),
      vscode.commands.registerCommand("wpilog-mcp.explorer.filterEntries", () => this.entries.askFilter()),
      vscode.commands.registerCommand("wpilog-mcp.explorer.clearEntryFilter", () => this.entries.setFilter("")),
      vscode.commands.registerCommand("wpilog-mcp.explorer.openLog", (logPath: string) =>
        vscode.commands.executeCommand("vscode.openWith", vscode.Uri.file(logPath), EDITOR_VIEW_TYPE)
      ),
      vscode.commands.registerCommand("wpilog-mcp.explorer.revealLog", (item?: LogItem) => {
        if (item?.resourceUri) void vscode.commands.executeCommand("revealFileInOS", item.resourceUri);
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.copyLogPath", (item?: LogItem) => {
        if (item?.resourceUri) void vscode.env.clipboard.writeText(item.resourceUri.fsPath);
      }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.showEntry", (name: string) => this.editor.selectEntry(name)),
      vscode.commands.registerCommand("wpilog-mcp.explorer.refreshLog", () => this.editor.reloadActive())
    );
  }

  get extensionVersion(): string {
    return this.context.extension.packageJSON.version as string;
  }

  get extensionUri(): vscode.Uri {
    return this.context.extensionUri;
  }

  /** The window's servers, in the window's order. */
  specs(): DaemonSpec[] {
    return this.windowSpecs();
  }

  /**
   * A client to a server, which is started first when it is not running. One client per server,
   * replaced when the server's URL changes (a port taken by another program), so a session is
   * never aimed at a port nobody answers on.
   */
  async clientFor(spec: DaemonSpec): Promise<McpClient> {
    const url = await this.serverManager.ensure(spec);
    if (!url) {
      throw new Error(
        `The server ${spec.serverName} could not be started; the WPILog Analyzer output says why.`
      );
    }
    const existing = this.clients.get(spec.name);
    if (existing && existing.url === url) return existing.client;
    if (existing) void existing.client.dispose();
    const client = new McpClient(url, this.extensionVersion);
    this.clients.set(spec.name, { url, client });
    return client;
  }

  /** The servers to try for a file: those whose directories hold it first (serverChoice.ts). */
  serversForFile(filePath: string): DaemonSpec[] {
    return rankServersForFile(
      filePath,
      this.specs().map((spec) => ({ server: spec, logDirs: this.serverManager.logDirsOf(spec) }))
    );
  }

  /** Called when a server was started again or its URL changed: listings may have changed. */
  serversChanged(): void {
    this.logs.refresh();
  }

  log(message: string): void {
    this.output.appendLine(message);
  }

  dispose(): void {
    for (const { client } of this.clients.values()) void client.dispose();
    this.clients.clear();
    for (const d of this.disposables) d.dispose();
  }
}

/** The message of an error, for a note in a tree or the editor. */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** A tree item that remembers the model node it draws. */
class LogItem extends vscode.TreeItem {
  constructor(
    readonly node: LogNode | { kind: "server"; spec: DaemonSpec },
    readonly spec: DaemonSpec,
    collapsibleState: vscode.TreeItemCollapsibleState
  ) {
    super(
      node.kind === "server" ? node.spec.serverName : node.label,
      collapsibleState
    );
    switch (node.kind) {
      case "server":
        this.iconPath = new vscode.ThemeIcon("server");
        this.contextValue = "wpilogServer";
        this.tooltip = `The server ${node.spec.serverName} (${node.spec.name})`;
        break;
      case "event":
        this.iconPath = new vscode.ThemeIcon("calendar");
        break;
      case "date":
        this.iconPath = new vscode.ThemeIcon("history");
        break;
      case "log":
        this.resourceUri = vscode.Uri.file(node.log.path);
        this.description = node.description;
        this.tooltip = node.tooltip;
        this.contextValue = "wpilogLog";
        this.command = {
          command: "wpilog-mcp.explorer.openLog",
          title: "Open in WPILog Explorer",
          arguments: [node.log.path],
        };
        break;
      case "note":
        this.iconPath = new vscode.ThemeIcon("info");
        this.tooltip = node.tooltip;
        break;
    }
  }
}

/** The Logs view: each server's listing, grouped as logsTree.ts decides, with a filter. */
export class LogsProvider implements vscode.TreeDataProvider<LogItem> {
  private readonly changed = new vscode.EventEmitter<LogItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private readonly listings = new Map<string, Promise<LogListing>>();
  private filter = "";

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
      const specs = this.explorer.specs();
      if (specs.length === 1) return this.nodesOf(specs[0]);
      return specs.map(
        (spec) => new LogItem({ kind: "server", spec }, spec, vscode.TreeItemCollapsibleState.Expanded)
      );
    }
    if (element.node.kind === "server") return this.nodesOf(element.spec);
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

  private async nodesOf(spec: DaemonSpec): Promise<LogItem[]> {
    const listing = await this.listing(spec);
    return buildLogTree(listing, this.filter).map(
      (node, index) =>
        new LogItem(node, spec, "children" in node
          ? index === 0 || node.kind !== "event" ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed
          : vscode.TreeItemCollapsibleState.None)
    );
  }

  /** The server's listing, fetched once until the next refresh; a failure is a listing that says so. */
  private listing(spec: DaemonSpec): Promise<LogListing> {
    let pending = this.listings.get(spec.name);
    if (!pending) {
      pending = this.fetch(spec);
      this.listings.set(spec.name, pending);
    }
    return pending;
  }

  private async fetch(spec: DaemonSpec): Promise<LogListing> {
    try {
      const client = await this.explorer.clientFor(spec);
      return (await client.callTool("list_available_logs", { limit: LISTING_LIMIT })) as LogListing;
    } catch (error) {
      if (error instanceof ToolError && error.result) return error.result as LogListing;
      this.explorer.log(`Logs view: ${messageOf(error)}`);
      return { status: "error", error: messageOf(error) };
    }
  }
}

class EntryItem extends vscode.TreeItem {
  constructor(readonly node: EntryNode, collapsibleState: vscode.TreeItemCollapsibleState) {
    super(node.label, collapsibleState);
    switch (node.kind) {
      case "group":
        this.iconPath = vscode.ThemeIcon.Folder;
        this.tooltip = node.path;
        break;
      case "entry":
        this.description = node.description;
        this.tooltip = node.tooltip;
        this.iconPath = new vscode.ThemeIcon(node.expandable ? "symbol-structure" : "symbol-field");
        this.contextValue = "wpilogEntry";
        this.command = { command: "wpilog-mcp.explorer.showEntry", title: "Show Entry", arguments: [node.entry.name] };
        break;
      case "field":
        this.description = node.description;
        this.tooltip = node.entry.name + node.fieldPath;
        this.iconPath = new vscode.ThemeIcon("symbol-number");
        this.contextValue = "wpilogField";
        this.command = { command: "wpilog-mcp.explorer.showEntry", title: "Show Entry", arguments: [node.entry.name] };
        break;
      case "note":
        this.iconPath = new vscode.ThemeIcon("info");
        this.tooltip = node.tooltip;
        break;
    }
  }
}

/** The Entries view: the active log's entries as entriesTree.ts decides, with a filter. */
export class EntriesProvider implements vscode.TreeDataProvider<EntryItem> {
  private readonly changed = new vscode.EventEmitter<EntryItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private active?: OpenLog;
  private filter = "";
  private readonly infos = new Map<string, Promise<Record<string, unknown>>>();

  constructor(private readonly explorer: Explorer) {}

  /** Shows a log's entries: the one the editor just loaded, or none. */
  setActive(log: OpenLog | undefined): void {
    if (log?.path !== this.active?.path) {
      this.infos.clear();
      this.filter = "";
      void vscode.commands.executeCommand("setContext", "wpilog-mcp.entryFilter", false);
    }
    this.active = log;
    void vscode.commands.executeCommand("setContext", "wpilog-mcp.hasActiveLog", log !== undefined);
    this.changed.fire(undefined);
  }

  setFilter(filter: string): void {
    this.filter = filter;
    void vscode.commands.executeCommand("setContext", "wpilog-mcp.entryFilter", filter.trim() !== "");
    this.changed.fire(undefined);
  }

  async askFilter(): Promise<void> {
    const value = await vscode.window.showInputBox({
      title: "Filter entries",
      prompt: "Part of an entry's name, as list_entries takes it (blank shows every entry)",
      value: this.filter,
    });
    if (value !== undefined) this.setFilter(value);
  }

  getTreeItem(element: EntryItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: EntryItem): Promise<EntryItem[]> {
    if (!element) {
      const active = this.active;
      if (!active) {
        return [new EntryItem({ kind: "note", label: "Open a log in WPILog Explorer to list its entries" }, vscode.TreeItemCollapsibleState.None)];
      }
      const listing = await this.listing(active);
      return buildEntryTree(listing).map((node) => this.item(node, true));
    }
    const node = element.node;
    if (node.kind === "group") return node.children.map((child) => this.item(child, false));
    if (node.kind === "entry" && node.expandable) {
      return buildFieldNodes(node.entry, await this.info(node.entry)).map((child) => this.item(child, false));
    }
    return [];
  }

  private item(node: EntryNode, root: boolean): EntryItem {
    const state =
      node.kind === "group"
        ? root ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed
        : node.kind === "entry" && node.expandable
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

  /** An entry's description, fetched once per log. */
  info(entry: ListedEntry): Promise<Record<string, unknown>> {
    const active = this.active;
    if (!active?.spec) return Promise.resolve({ status: "error", error: "The log is not loaded" });
    let pending = this.infos.get(entry.name);
    if (!pending) {
      const spec = active.spec;
      pending = (async () => {
        try {
          const client = await this.explorer.clientFor(spec);
          return await client.callTool("get_entry_info", { path: active.path, name: entry.name });
        } catch (error) {
          if (error instanceof ToolError && error.result) return error.result;
          return { status: "error", error: messageOf(error) };
        }
      })();
      this.infos.set(entry.name, pending);
    }
    return pending;
  }
}

/** A log document: the editor is read-only, so the document is only its file. */
class LogDocument implements vscode.CustomDocument {
  constructor(readonly uri: vscode.Uri) {}
  dispose(): void {}
}

/** One editor: its panel and the log it shows. */
interface Editor {
  panel: vscode.WebviewPanel;
  log: OpenLog;
  ready: boolean;
}

/**
 * The custom editor for `.wpilog` files: a webview that shows the log's time range and entries
 * from `list_entries`, and an entry's description from `get_entry_info` when one is picked, in
 * the editor or in the Entries view. The log is read by the first of the window's servers whose
 * directories hold it (a server reads only inside them); when none can, the editor says what
 * the server said, with its hint.
 */
export class ExplorerEditorProvider implements vscode.CustomReadonlyEditorProvider<LogDocument> {
  private readonly editors = new Map<string, Editor>();
  private activeKey?: string;

  constructor(private readonly explorer: Explorer) {}

  openCustomDocument(uri: vscode.Uri): LogDocument {
    return new LogDocument(uri);
  }

  resolveCustomEditor(document: LogDocument, panel: vscode.WebviewPanel): void {
    const key = panel.webview.toString() + document.uri.toString();
    const media = vscode.Uri.joinPath(this.explorer.extensionUri, "media");
    panel.webview.options = { enableScripts: true, localResourceRoots: [media] };
    panel.webview.html = explorerPage({
      cspSource: panel.webview.cspSource,
      styleUri: panel.webview.asWebviewUri(vscode.Uri.joinPath(media, "explorer.css")).toString(),
      scriptUri: panel.webview.asWebviewUri(vscode.Uri.joinPath(media, "explorer.js")).toString(),
      nonce: crypto.randomBytes(16).toString("hex"),
    });
    const editor: Editor = { panel, log: { path: document.uri.fsPath }, ready: false };
    this.editors.set(key, editor);
    panel.webview.onDidReceiveMessage((message: { type?: string; name?: string }) => {
      if (message.type === "ready") {
        editor.ready = true;
        void this.load(editor);
      } else if (message.type === "entryInfo" && typeof message.name === "string") {
        void this.sendInfo(editor, message.name);
      }
    });
    panel.onDidChangeViewState(() => {
      if (panel.active) this.activate(key);
    });
    panel.onDidDispose(() => {
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

  /** Lists the log through the first server that can read it, and shows the result. */
  private async load(editor: Editor): Promise<void> {
    const { path: logPath } = editor.log;
    const name = path.basename(logPath);
    const post = (message: Record<string, unknown>) => void editor.panel.webview.postMessage(message);
    post({ type: "loading", name, path: logPath, text: "Starting the server…" });
    const specs = this.explorer.serversForFile(logPath);
    if (specs.length === 0) {
      post({ type: "error", name, path: logPath, message: "No server is configured for this window." });
      return;
    }
    let firstError: { message: string; hint?: string } | undefined;
    for (const spec of specs) {
      try {
        const client = await this.explorer.clientFor(spec);
        post({ type: "loading", name, path: logPath, text: `Reading the log with the server ${spec.serverName}…` });
        const listing = (await client.callTool("list_entries", { path: logPath })) as EntryListing;
        editor.log = { path: logPath, spec, listing };
        post({ type: "log", name, path: logPath, listing, server: spec.serverName });
        if (this.editors.get(this.activeKey ?? "") === editor) this.explorer.entries.setActive(editor.log);
        return;
      } catch (error) {
        const hint = error instanceof ToolError ? (error.result?.hint as string | undefined) : undefined;
        firstError ??= { message: messageOf(error), hint };
        this.explorer.log(`Explorer: ${spec.serverName} could not read ${logPath}: ${messageOf(error)}`);
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

  private async sendInfo(editor: Editor, name: string): Promise<void> {
    const info = await this.explorer.entries.info({ name, type: "", sample_count: 0 });
    void editor.panel.webview.postMessage({ type: "entryInfo", name, info });
  }

  /** Shows an entry in the active editor: the Entries view's click. */
  selectEntry(name: string): void {
    const editor = this.editors.get(this.activeKey ?? "");
    if (!editor) return;
    editor.panel.reveal(undefined, true);
    void editor.panel.webview.postMessage({ type: "selectEntry", name });
  }

  /** Reads the active editor's log again: the file changed, or the server was restarted. */
  reloadActive(): void {
    const editor = this.editors.get(this.activeKey ?? "");
    if (editor?.ready) void this.load(editor);
  }
}
