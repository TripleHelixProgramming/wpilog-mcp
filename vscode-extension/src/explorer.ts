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
import { DataClient, DataError, TooLargeError } from "./dataClient";
import { McpClient, ToolError } from "./mcpClient";
import { DaemonSpec, ServerManager } from "./serverManager";
import { EntryListing, EntryNode, ListedEntry, buildEntryTree, buildFieldNodes, elementNodes, elementPaths } from "./explorer/entriesTree";
import { LogListing, LogNode, buildLogTree } from "./explorer/logsTree";
import { rankServersForFile } from "./explorer/serverChoice";
import { explorerPage } from "./explorer/webviewHtml";

/** The custom editor's view type, as package.json declares it. */
export const EDITOR_VIEW_TYPE = "wpilog-mcp.explorer";
export const LOGS_VIEW = "wpilog-mcp.logs";
export const ENTRIES_VIEW = "wpilog-mcp.entries";

/** The most logs one listing asks for: the server's page limit. */
const LISTING_LIMIT = 500;
/** How many of an array's elements "Plot Every Element" plots: a pane can hold that many. */
const ELEMENTS_PLOTTED = 16;
/** How many console matches one request lists. */
const CONSOLE_LIMIT = 500;

/** A log open in the editor: its file, the server that read it, and its listing. */
interface OpenLog {
  path: string;
  spec?: DaemonSpec;
  listing?: EntryListing;
}

/** The explorer: owns the clients, the views, and the editor, and wires them together. */
export class Explorer implements vscode.Disposable {
  private readonly clients = new Map<string, { url: string; client: McpClient }>();
  /** The data endpoint's client: one memory of streams for every editor. */
  readonly data = new DataClient();
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
      vscode.commands.registerCommand("wpilog-mcp.explorer.showEntry", (nameOrItem: string | EntryItem) =>
        this.editor.selectEntry(entryNameOf(nameOrItem))),
      vscode.commands.registerCommand("wpilog-mcp.explorer.plotEntry", (nameOrItem: string | EntryItem) =>
        this.editor.plotEntry(entryNameOf(nameOrItem))),
      vscode.commands.registerCommand("wpilog-mcp.explorer.plotElements", (item?: EntryItem) => {
        const node = item?.node;
        if (node?.kind !== "field" || !node.elements) return;
        for (const name of elementPaths(node.entry.name, node.fieldPath, node.elements, ELEMENTS_PLOTTED)) {
          this.editor.plotEntry(name);
        }
      }),
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
    if (existing) {
      void existing.client.dispose();
      // A server at a new URL was started again, perhaps with other directories: its streams are new
      this.data.clear();
    }
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
    const asset = (...segments: string[]) => panel.webview.asWebviewUri(vscode.Uri.joinPath(media, ...segments)).toString();
    panel.webview.html = explorerPage({
      cspSource: panel.webview.cspSource,
      styleUri: asset("explorer.css"),
      scriptUri: asset("explorer.js"),
      plot: {
        styleUri: asset("vendor", "uPlot.min.css"),
        scriptUris: [asset("vendor", "uPlot.iife.min.js"), asset("arrowStream.js"), asset("plotMath.js"), asset("plot.js"), asset("console.js"), asset("field.js")],
      },
      nonce: crypto.randomBytes(16).toString("hex"),
    });
    const editor: Editor = { panel, log: { path: document.uri.fsPath }, ready: false };
    this.editors.set(key, editor);
    panel.webview.onDidReceiveMessage((message: WebviewMessage) => {
      switch (message.type) {
        case "ready":
          editor.ready = true;
          void this.load(editor);
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
        default:
          break;
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
      const args: Record<string, unknown> = { path: editor.log.path, limit: CONSOLE_LIMIT, collapse_repeats: true };
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

  /** Plots an entry in the active editor: the Entries view's click. */
  plotEntry(name: string): void {
    const editor = this.editors.get(this.activeKey ?? "");
    if (!editor || name === "") return;
    editor.panel.reveal(undefined, true);
    void editor.panel.webview.postMessage({ type: "plotEntry", name });
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
