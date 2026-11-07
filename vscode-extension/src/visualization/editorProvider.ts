import * as vscode from "vscode";
import { randomBytes } from "node:crypto";
import { Backend, DocumentController, Journal, Recovery, ViewSnapshot } from "./documentController";

export interface ViewDocument extends vscode.CustomDocument { controller: DocumentController; }
export interface DocumentResources {
  read(uri: vscode.Uri): Promise<ViewSnapshot>;
  render?(document: ViewDocument, panel: vscode.WebviewPanel): Promise<void>;
  paint?(document: ViewDocument): void;
  closed?(document: ViewDocument): void;
  trace?(event: string, uri: vscode.Uri, snapshot: ViewSnapshot): void;
  /** May be enabled only after the native picker/replacement/recovery gates pass. */
  saveAs?(document: ViewSnapshot, destination: vscode.Uri): Promise<void>;
}

/** Native lifecycle owner, independent of the fixture or recorded-preview renderer. */
export class VisualizationEditorProvider implements vscode.CustomEditorProvider<ViewDocument>, vscode.Disposable {
  private events = new vscode.EventEmitter<vscode.CustomDocumentEditEvent<ViewDocument>>();
  readonly onDidChangeCustomDocument = this.events.event;
  readonly documents = new Map<string, ViewDocument>();
  readonly panels = new Map<string, vscode.WebviewPanel>();
  readonly ready = new Set<string>();
  private dirty = new Map<string, boolean>();
  private tabs: vscode.Disposable;
  constructor(private backend: Backend, private journal: Journal, private resources: DocumentResources) {
    this.tabs = vscode.window.tabGroups.onDidChangeTabs(event => {
      for (const tab of event.changed) {
        if (!(tab.input instanceof vscode.TabInputCustom)) continue;
        const key = tab.input.uri.toString(), wasDirty = this.dirty.get(key);
        this.dirty.set(key, tab.isDirty);
        const doc = this.documents.get(key);
        if (wasDirty && !tab.isDirty && doc?.controller.saving) {
          void doc.controller.nativeSaveCompleted().then(() => this.paint(doc), error => {
            doc.controller.warning = String(error); this.paint(doc);
          });
        }
      }
    });
  }
  async openCustomDocument(uri: vscode.Uri, context: vscode.CustomDocumentOpenContext): Promise<ViewDocument> {
    const snapshot = await this.resources.read(uri);
    let document!: ViewDocument;
    const controller = new DocumentController(snapshot, this.backend, this.journal, edit => {
      this.dirty.set(uri.toString(), true);
      this.resources.trace?.("edit:emit", uri, controller.snapshot);
      this.events.fire({ document, label: edit.label,
        undo: async () => {
          this.resources.trace?.("undo:called", uri, controller.snapshot);
          await edit.undo(); this.resources.trace?.("undo:done", uri, controller.snapshot); this.paint(document);
        },
        redo: async () => {
          this.resources.trace?.("redo:called", uri, controller.snapshot);
          await edit.redo(); this.resources.trace?.("redo:done", uri, controller.snapshot); this.paint(document);
        } });
      this.paint(document);
    }, () => this.paint(document));
    document = { uri, controller, dispose: () => { controller.dispose(); this.resources.closed?.(document); this.documents.delete(uri.toString()); this.panels.delete(uri.toString()); this.ready.delete(uri.toString()); } };
    this.documents.set(uri.toString(), document);
    const recovery = await this.journal.read?.(snapshot.view_id);
    if (recovery) {
      try { await controller.recover(recovery); } catch (error) { controller.warning = String(error); }
    }
    if (context.backupId) {
      try {
        const backup = JSON.parse(Buffer.from(await vscode.workspace.fs.readFile(vscode.Uri.parse(context.backupId))).toString()) as Recovery;
        await controller.recover(backup);
      } catch (error) { controller.warning = String(error); }
    }
    return document;
  }
  async resolveCustomEditor(document: ViewDocument, panel: vscode.WebviewPanel): Promise<void> {
    this.panels.set(document.uri.toString(), panel);
    if (this.resources.render) { await this.resources.render(document, panel); return; }
    panel.webview.options = { enableScripts: true, localResourceRoots: [] };
    const nonce = randomBytes(18).toString("hex");
    panel.webview.html = `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}';"></head><body>
      <h1>Visualization lifecycle fixture</h1><button id="edit">Toggle linked cursor</button><pre id="state"></pre><p role="status" id="warning"></p>
      <script nonce="${nonce}">const api=acquireVsCodeApi(); document.querySelector('#edit').onclick=()=>api.postMessage({type:'edit'});
      window.addEventListener('message',event=>{
        if(event.data.type==='focus-probe') {
          document.querySelector('#edit').focus();
          api.postMessage({type:'focus-ack',token:event.data.token,focused:document.hasFocus()});return;
        }
        document.querySelector('#state').textContent=JSON.stringify(event.data.snapshot,null,2);document.querySelector('#warning').textContent=event.data.warning||'';
      });api.postMessage({type:'ready'});</script></body></html>`;
    panel.webview.onDidReceiveMessage(async message => {
      try {
        if (message.type === "ready") this.ready.add(document.uri.toString());
        if (message.type === "edit") {
          const config = structuredClone(document.controller.snapshot.configuration);
          const links = config.links as { cursor: boolean }; links.cursor = !links.cursor;
          await document.controller.edit(config, "Toggle linked cursor");
        }
      } catch (error) { document.controller.warning = String(error); }
      this.paint(document);
    });
    this.paint(document);
  }
  paint(document: ViewDocument): void {
    if (this.resources.paint) { this.resources.paint(document); return; }
    void this.panels.get(document.uri.toString())?.webview.postMessage({ snapshot: document.controller.snapshot, warning: document.controller.warning });
  }
  async saveCustomDocument(document: ViewDocument): Promise<void> { await document.controller.save(); }
  async saveCustomDocumentAs(document: ViewDocument, destination: vscode.Uri): Promise<void> {
    if (!this.resources.saveAs) throw new Error("Use Save view… to choose a title in the server library");
    await this.resources.saveAs(document.controller.snapshot, destination);
  }
  async revertCustomDocument(document: ViewDocument): Promise<void> { await document.controller.revert(); this.paint(document); }
  async backupCustomDocument(document: ViewDocument, context: vscode.CustomDocumentBackupContext): Promise<vscode.CustomDocumentBackup> {
    const value: Recovery = { view_id: document.controller.snapshot.view_id, base_revision: document.controller.snapshot.revision,
      configuration: document.controller.snapshot.configuration };
    await vscode.workspace.fs.writeFile(context.destination, Buffer.from(JSON.stringify(value)));
    return { id: context.destination.toString(), delete: () => { void vscode.workspace.fs.delete(context.destination).then(undefined, () => {}); } };
  }
  dispose(): void { this.tabs.dispose(); this.events.dispose(); }
}
