import * as vscode from "vscode";
import { SourceContext } from "../connections";
import { readSourceUri } from "../sourceUri";
import { RecordedPreview, OVERVIEW_TYPE, VIEW_TYPE } from "./recordedPreview";
import { ViewDocument } from "./editorProvider";
import { VisualizationBudgets } from "./byteBudget";
import { AnalysisQueue } from "./liveAnalysis";

export interface ConnectedPreviewHost {
  context: vscode.ExtensionContext;
  source(uri?: vscode.Uri): Promise<{ context: SourceContext; uri: vscode.Uri }>;
  entries(source: SourceContext, path: string | undefined, target: string): void;
}

/** VS Code registers one provider per view type, while every document retains its own source
 * context. No global active client participates in async requests or editor recovery. */
export class ConnectedPreviews implements vscode.Disposable {
  private readonly children = new Map<SourceContext, RecordedPreview>();
  private readonly events = new vscode.EventEmitter<vscode.CustomDocumentEditEvent<ViewDocument>>();
  private readonly documents = new WeakMap<ViewDocument, RecordedPreview>();
  private readonly disposables: vscode.Disposable[] = [];
  private readonly budgets: VisualizationBudgets;
  private readonly analysisQueue = new AnalysisQueue();
  constructor(private readonly host: ConnectedPreviewHost) {
    try { this.budgets = new VisualizationBudgets(vscode.workspace.getConfiguration("wpilog-mcp").get("visualization.decodedBufferMiB", 64)); }
    catch (error) { this.budgets = new VisualizationBudgets(); void vscode.window.showWarningMessage(`${error}. Using 64 MiB until window reload.`); }
    const changed = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
    const readonly = () => { throw vscode.FileSystemError.NoPermissions("Source documents are read-only"); };
    this.disposables.push(changed, this.events,
      vscode.workspace.registerFileSystemProvider("wpilog-source", {
        onDidChangeFile: changed.event, watch: () => new vscode.Disposable(() => {}),
        stat: uri => { readSourceUri(uri.toString()); return { type: vscode.FileType.File, size: 0, ctime: 0, mtime: 0 }; },
        readFile: () => { throw vscode.FileSystemError.Unavailable("Open with WPILog Explorer"); }, readDirectory: () => [],
        writeFile: readonly, createDirectory: readonly, rename: readonly, delete: readonly,
      }, { isReadonly: true, isCaseSensitive: true }),
      vscode.workspace.registerFileSystemProvider("wpilog-view", {
        onDidChangeFile: changed.event, watch: () => new vscode.Disposable(() => {}),
        stat: async uri => { await (await this.forUri(uri)).idFor(uri); return { type: vscode.FileType.File, size: 0, ctime: 0, mtime: 0 }; },
        readFile: async uri => { const child = await this.forUri(uri); return Buffer.from(JSON.stringify(await child.backend.read(await child.idFor(uri)))); },
        readDirectory: () => [], writeFile: readonly, createDirectory: readonly, rename: readonly, delete: readonly,
      }, { isCaseSensitive: true }),
      vscode.window.registerCustomEditorProvider(VIEW_TYPE, {
        onDidChangeCustomDocument: this.events.event,
        openCustomDocument: async (uri: vscode.Uri, options: vscode.CustomDocumentOpenContext) => {
          const child = await this.forUri(uri), document = await child.provider.openCustomDocument(uri, options);
          this.documents.set(document, child); return document;
        },
        resolveCustomEditor: (doc: ViewDocument, panel: vscode.WebviewPanel) => this.owner(doc).provider.resolveCustomEditor(doc, panel),
        saveCustomDocument: (doc: ViewDocument) => this.owner(doc).provider.saveCustomDocument(doc),
        saveCustomDocumentAs: (doc: ViewDocument, destination: vscode.Uri) => this.owner(doc).provider.saveCustomDocumentAs(doc, destination),
        revertCustomDocument: (doc: ViewDocument) => this.owner(doc).provider.revertCustomDocument(doc),
        backupCustomDocument: (doc: ViewDocument, context: vscode.CustomDocumentBackupContext) => this.owner(doc).provider.backupCustomDocument(doc, context),
      }, { supportsMultipleEditorsPerDocument: false, webviewOptions: { retainContextWhenHidden: true } }),
      vscode.window.registerCustomEditorProvider(OVERVIEW_TYPE, {
        openCustomDocument: (uri: vscode.Uri) => ({ uri, dispose() {} }),
        resolveCustomEditor: async (doc: vscode.CustomDocument, panel: vscode.WebviewPanel) => {
          const selected = await this.host.source(doc.uri); this.child(selected.context).renderOverview(selected.uri, panel);
        },
      }, { supportsMultipleEditorsPerDocument: false, webviewOptions: { retainContextWhenHidden: false } }),
      vscode.commands.registerCommand("wpilog-mcp.explorer.openSourceOverview", (item?: vscode.Uri | { resourceUri?: vscode.Uri }) =>
        this.openOverview(item instanceof vscode.Uri ? item : item?.resourceUri)),
    );
  }
  private owner(document: ViewDocument): RecordedPreview { const child = this.documents.get(document); if (!child) throw new Error("This view's connection is no longer open"); return child; }
  private child(source: SourceContext): RecordedPreview {
    let child = this.children.get(source);
    if (!child) {
      child = new RecordedPreview({ context: this.host.context, source, managed: true, budgets: this.budgets, analysisQueue: this.analysisQueue,
        client: () => source.client(), entries: (file, target) => this.host.entries(source, file, target),
        grantStore: async () => { throw new Error("Save layouts in the selected server's visualization library"); } });
      this.disposables.push(child.provider.onDidChangeCustomDocument(event => this.events.fire(event)));
      this.children.set(source, child);
    }
    return child;
  }
  private async forUri(uri: vscode.Uri): Promise<RecordedPreview> { return this.child((await this.host.source(uri)).context); }
  async openOverview(uri?: vscode.Uri): Promise<void> {
    try {
      if (!uri) {
        const input = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
        if (input instanceof vscode.TabInputCustom || input instanceof vscode.TabInputText) uri = input.uri;
      }
      const selected = await this.host.source(uri); await this.child(selected.context).openOverview(selected.uri);
    } catch (error) { void vscode.window.showErrorMessage(String(error)); }
  }
  async routeSignal(name: string): Promise<boolean> {
    for (const child of this.children.values()) if (await child.routeSignal(name)) return true;
    return false;
  }
  dispose(): void { for (const child of this.children.values()) child.dispose(); this.children.clear(); for (const disposable of this.disposables) disposable.dispose(); }
}
