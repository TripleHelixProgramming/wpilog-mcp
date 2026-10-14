import * as crypto from "crypto";
import * as vscode from "vscode";
import type { EntriesProvider, EntryItem } from "../explorer";
import type { EntryGrouping } from "./entriesTree";
import { entriesViewPage } from "./entriesViewHtml";

/** The page receives presentation data and opaque IDs, never commands or server access. */
interface ViewNode {
  id: string;
  label: string;
  description?: string;
  tooltip?: string;
  kind: EntryItem["node"]["kind"];
  expandable: boolean;
  expanded: boolean;
  children?: ViewNode[];
  details: boolean;
  plotElements: boolean;
}

interface ViewMessage {
  type?: string;
  value?: unknown;
  id?: unknown;
  generation?: unknown;
  filter?: unknown;
}

/**
 * An inline search surface over the existing entry provider. Filtering, log selection and lazy
 * field discovery keep one owner; a generation makes a reply from an earlier search or log inert.
 */
export class EntriesView implements vscode.WebviewViewProvider, vscode.Disposable {
  private view?: vscode.WebviewView;
  private ready = false;
  private disposed = false;
  private generation = 0;
  private filterResetGeneration = 0;
  private nextId = 0;
  private focusPending = false;
  private collapsed = false;
  private applyingPageFilter = false;
  private sourceResetVersion: number;
  private sourceFilter: string;
  private sourceGrouping: EntryGrouping;
  private readonly items = new Map<string, EntryItem>();
  private readonly children = new Map<string, Promise<ViewNode[]>>();
  private readonly subscriptions: vscode.Disposable[];
  private viewSubscriptions: vscode.Disposable[] = [];

  constructor(private readonly source: EntriesProvider, private readonly extensionUri: vscode.Uri) {
    this.sourceResetVersion = source.filterResetVersion;
    this.sourceFilter = source.filterText;
    this.sourceGrouping = source.grouping;
    this.subscriptions = [source.onDidChangeTreeData(() => this.refresh())];
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    if (this.disposed) return;
    this.releaseView();
    this.view = view;
    const media = vscode.Uri.joinPath(this.extensionUri, "media");
    view.webview.options = { enableScripts: true, localResourceRoots: [media] };
    this.viewSubscriptions = [
      view.webview.onDidReceiveMessage((message: ViewMessage) => {
        if (this.view === view && !this.disposed) void this.receive(message);
      }),
      view.onDidDispose(() => { if (this.view === view) this.releaseView(); }),
    ];
    view.webview.html = entriesViewPage({
      cspSource: view.webview.cspSource,
      styleUri: view.webview.asWebviewUri(vscode.Uri.joinPath(media, "entries.css")).toString(),
      scriptUri: view.webview.asWebviewUri(vscode.Uri.joinPath(media, "entries.js")).toString(),
      nonce: crypto.randomBytes(16).toString("hex"),
    });
  }

  /** The title action also works before VS Code has resolved a collapsed or unopened view. */
  async focusFilter(): Promise<void> {
    if (this.disposed) return;
    this.focusPending = true;
    if (!this.view) await vscode.commands.executeCommand("wpilog-mcp.entries.focus");
    if (this.disposed) return;
    this.view?.show(true);
    this.flushFocus();
  }

  async collapseAll(): Promise<void> {
    if (this.disposed) return;
    this.collapsed = true;
    await this.post({ type: "collapseAll" });
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.releaseView();
    for (const subscription of this.subscriptions) subscription.dispose();
  }

  private releaseView(): void {
    this.view = undefined;
    this.ready = false;
    this.invalidate();
    for (const subscription of this.viewSubscriptions) subscription.dispose();
    this.viewSubscriptions = [];
  }

  private invalidate(): number {
    this.items.clear();
    this.children.clear();
    return ++this.generation;
  }

  private current(generation: number): boolean {
    return !this.disposed && this.ready && this.view !== undefined && generation === this.generation;
  }

  /** Invalidate synchronously: an old visible row cannot act on a newly selected log. */
  private refresh(forceReset = false): void {
    const generation = this.invalidate();
    const resetFilter = forceReset || (!this.applyingPageFilter && this.sourceResetVersion !== this.source.filterResetVersion);
    this.sourceResetVersion = this.source.filterResetVersion;
    if (resetFilter) this.filterResetGeneration = generation;
    const filter = this.source.filterText;
    const grouping = this.source.grouping;
    if (resetFilter || filter !== this.sourceFilter || grouping !== this.sourceGrouping) this.collapsed = false;
    this.sourceFilter = filter;
    this.sourceGrouping = grouping;
    if (!this.current(generation)) return;
    void this.post({ type: "loading", generation, filter, grouping, resetFilter });
    void this.readRoots(generation, filter, grouping, resetFilter);
  }

  private async readRoots(generation: number, filter: string, grouping: EntryGrouping, resetFilter: boolean): Promise<void> {
    try {
      const roots = await this.source.getChildren();
      if (!this.current(generation)) return;
      const nodes = await this.serialize(roots, generation, filter.trim() !== "");
      if (!this.current(generation)) return;
      this.applyCollapse(nodes);
      await this.post({ type: "tree", generation, filter, grouping, resetFilter, nodes });
    } catch (error) { this.report(error, generation); }
  }

  private async serialize(items: EntryItem[], generation: number, expandGroups: boolean): Promise<ViewNode[]> {
    if (!this.current(generation)) return [];
    return Promise.all(items.map(async item => {
      const id = `${generation}:${++this.nextId}`;
      this.items.set(id, item);
      const expandable = item.collapsibleState !== undefined && item.collapsibleState !== vscode.TreeItemCollapsibleState.None;
      // Matching name groups are cheap to open. Structs and arrays always await an explicit expand.
      const expanded = !this.collapsed && item.node.kind === "group" &&
        (expandGroups || item.collapsibleState === vscode.TreeItemCollapsibleState.Expanded);
      const node: ViewNode = {
        id,
        label: typeof item.label === "string" ? item.label : item.label?.label ?? item.node.label,
        ...(typeof item.description === "string" ? { description: item.description } : {}),
        ...(typeof item.tooltip === "string" ? { tooltip: item.tooltip } : {}),
        kind: item.node.kind,
        expandable,
        expanded,
        details: item.node.kind === "entry" || item.node.kind === "field",
        plotElements: item.contextValue === "wpilogFieldPooled" && item.node.kind === "field" && !!item.node.elements,
      };
      if (expanded) node.children = await this.readChildren(id, item, generation, expandGroups);
      return node;
    }));
  }

  private readChildren(id: string, item: EntryItem, generation: number, expandGroups: boolean): Promise<ViewNode[]> {
    let pending = this.children.get(id);
    if (!pending) {
      pending = this.source.getChildren(item).then(children =>
        this.current(generation) ? this.serialize(children, generation, expandGroups) : []);
      this.children.set(id, pending);
      // A failed expansion can be retried; a late rejection must not erase a newer generation.
      void pending.catch(() => { if (this.children.get(id) === pending) this.children.delete(id); });
    }
    return pending;
  }

  private applyCollapse(nodes: ViewNode[]): void {
    if (!this.collapsed) return;
    for (const node of nodes) {
      node.expanded = false;
      if (node.children) this.applyCollapse(node.children);
    }
  }

  private async receive(message: ViewMessage): Promise<void> {
    if (!message || typeof message !== "object") return;
    if (message.type === "ready") {
      this.ready = true;
      this.flushFocus();
      this.refresh(true);
      return;
    }
    if (!this.ready) return;
    if (message.type === "filter" || message.type === "grouping") {
      // A query already sent by the old page context cannot undo a log switch or explicit clear.
      // Earlier queries in the same context remain valid while their loading replies are in flight.
      const validValue = message.type === "filter" ? typeof message.value === "string"
        : (message.value === "path" || message.value === "type") && typeof message.filter === "string";
      if (validValue && typeof message.generation === "number" &&
          Number.isInteger(message.generation) && message.generation >= this.filterResetGeneration &&
          message.generation <= this.generation) {
        this.applyingPageFilter = true;
        try {
          if (message.type === "grouping") this.source.setGrouping(message.value as EntryGrouping, message.filter as string);
          else this.source.setFilter(message.value as string);
        }
        finally { this.applyingPageFilter = false; }
      }
      return;
    }
    if (message.type === "collapseAll") {
      if (message.generation === this.generation) this.collapsed = true;
      return;
    }
    if (typeof message.id !== "string") return;
    const item = this.items.get(message.id);
    const generation = this.generation;
    if (!item || (message.generation !== undefined && message.generation !== generation)) return;
    try {
      switch (message.type) {
        case "expand": {
          if (item.collapsibleState === undefined || item.collapsibleState === vscode.TreeItemCollapsibleState.None) return;
          const nodes = await this.readChildren(message.id, item, generation, this.source.filterText.trim() !== "");
          if (!this.current(generation)) return;
          this.applyCollapse(nodes);
          await this.post({ type: "children", generation, id: message.id, nodes });
          break;
        }
        case "activate":
          if (item.command) await vscode.commands.executeCommand(item.command.command, ...(item.command.arguments ?? []));
          break;
        case "details":
          if (item.node.kind === "entry" || item.node.kind === "field") {
            // The editor describes root entries; a numeric field belongs to its containing entry.
            await vscode.commands.executeCommand("wpilog-mcp.explorer.showEntry", item.node.kind === "field" ? item.node.entry.name : item);
          }
          break;
        case "plotElements":
          if (item.contextValue === "wpilogFieldPooled" && item.node.kind === "field" && item.node.elements) {
            await vscode.commands.executeCommand("wpilog-mcp.explorer.plotElements", item);
          }
          break;
      }
    } catch (error) { this.report(error, generation); }
  }

  private flushFocus(): void {
    if (!this.focusPending || !this.ready || !this.view) return;
    this.focusPending = false;
    void this.post({ type: "focusFilter" });
  }

  private report(error: unknown, generation: number): void {
    if (this.current(generation)) void this.post({
      type: "error", generation, message: error instanceof Error ? error.message : String(error),
    });
  }

  private async post(message: unknown): Promise<void> {
    if (this.ready && this.view && !this.disposed) await this.view.webview.postMessage(message);
  }
}
