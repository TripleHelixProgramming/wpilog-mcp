/** Native M3 routing gate: real Explorer and rendered controls; only isolated synthetic servers. */
import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import * as assert from "node:assert/strict";
import { Explorer } from "../explorer";
import { ServerManager } from "../serverManager";
import { RecordedPreview } from "../visualization/recordedPreview";
import { RecordedConfiguration } from "../visualization/recordedModel";
import { RecordedDevtools } from "./recordedDevtools";

const pause = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
async function until(label: string, check: () => boolean | Promise<boolean>) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) { if (await check()) return; await pause(50); }
  throw new Error("Timed out: " + label);
}
export function activate(context: vscode.ExtensionContext): void {
  const root = process.env.WPILOG_CONNECTION_FIXTURE!;
  if (!root || !fs.existsSync(path.join(root, "fixture-only"))) throw new Error("Isolated connection fixture required");
  let localCalls = 0;
  const output = vscode.window.createOutputChannel("M3 fixture");
  const manager = { ensure: async () => { localCalls++; throw new Error("Laptop daemon forbidden in this fixture"); } } as unknown as ServerManager;
  const explorer = new Explorer(context, output, manager, () => ({ kind: "standalone", name: "unused", directory: root } as any),
    () => [], undefined, () => undefined, async () => ({ Forwarded: "for=192.0.2.1" }));
  context.subscriptions.push(output, explorer);
  const checks: string[] = [];
  void (async () => {
    let driver: RecordedDevtools | undefined;
    try {
      driver = await RecordedDevtools.open(Number(process.env.WPILOG_RECORDED_DEBUG_PORT));
      const roots = await explorer.logs.getChildren();
      assert.deepEqual(roots.map(item => item.label), ["Remote A", "Remote B", "Offline"]);
      assert.ok(roots.every(item => item.collapsibleState === vscode.TreeItemCollapsibleState.Collapsed));
      assert.equal(localCalls, 0); checks.push("remote-only collapsed connection roots require no laptop daemon");
      const documents: { preview: RecordedPreview; id: string; uri: vscode.Uri }[] = [];
      for (const item of roots.slice(0, 2)) {
        const scopes = await explorer.logs.getChildren(item); assert.equal(scopes.length, 1);
        const logs: any[] = [];
        async function visit(node: any): Promise<void> {
          if (node.node.kind === "log") logs.push(node);
          else for (const child of await explorer.logs.getChildren(node)) await visit(child);
        }
        const listing = await explorer.logs.listing(scopes[0].spec);
        fs.writeFileSync(path.join(root, `listing-${item.spec.connectionId}.json`), JSON.stringify(listing, null, 2));
        await visit(scopes[0]); assert.equal(logs.length, 1, JSON.stringify(listing));
        fs.writeFileSync(path.join(root, `uri-${item.spec.connectionId}.txt`), String(logs[0].resourceUri));
        await explorer.resolveSource(logs[0].resourceUri);
        await explorer.preview.openOverview(logs[0].resourceUri);
        const children = (explorer.preview as any).children as Map<unknown, RecordedPreview>;
        const preview = [...children.values()].at(-1)!;
        await until("overview inventory", async () => !!(await driver!.evaluate("document.body?.dataset.overview === 'true'", "document.getElementById('inventory')?.textContent"))?.includes("signals"));
        await driver.evaluate("document.body?.dataset.overview === 'true'", "(document.getElementById('blank').click(), true)");
        await until("qualified native document", () => preview.provider.documents.size === 1);
        const doc = [...preview.provider.documents.values()][0], id = doc.controller.snapshot.view_id;
        await until("renderer ready", () => preview.provider.ready.has(doc.uri.toString()));
        const selector = `document.body?.dataset.viewId === ${JSON.stringify(id)}`;
        for (const [index, button] of ["plot", "values"].entries()) {
          await driver.evaluate(selector, `(document.getElementById('${button}').click(), true)`);
          await until("panel edit acknowledged", () => (doc.controller.snapshot.configuration as RecordedConfiguration).panels.length === index + 1 && !doc.controller.pending);
        }
        await until("two panels", () => (doc.controller.snapshot.configuration as RecordedConfiguration).panels.length === 2);
        for (const panel of (doc.controller.snapshot.configuration as RecordedConfiguration).panels) {
          await driver.evaluate(selector, `(() => { document.getElementById('target').value=${JSON.stringify(panel.panel_id)}; for(const o of document.getElementById('signals').options) o.selected=o.value==='NT:/y'; document.getElementById('add').click(); return true; })()`);
          await until("selected source signal", () => (doc.controller.snapshot.configuration as RecordedConfiguration).panels.find(p => p.panel_id === panel.panel_id)!.series.length === 1);
        }
        await until("direct plot and value rendering", () => (preview.rendered.get(id)?.plots ?? 0) === 1 && (preview.rendered.get(id)?.rows ?? 0) === 2);
        assert.match(await driver.evaluate(selector, "document.querySelector('.recorded-values')?.textContent"), /0/);
        // First save uses the explicit product action; native Save never chooses a draft destination.
        (preview as any).host.dialogs = { showInputBox: async () => String(item.label) };
        await driver.evaluate(selector, "(document.getElementById('save').click(), true)");
        await until("library save receipt", () => !!doc.controller.snapshot.saved_view_ref);
        const editedTitle = String(item.label) + " edited";
        await driver.evaluate(selector, `(() => { const input=document.querySelector('.panel-title'); input.value=${JSON.stringify(editedTitle)}; input.dispatchEvent(new Event('change')); return true; })()`);
        await until("saved view edit acknowledged", () => (doc.controller.snapshot.configuration as RecordedConfiguration).panels[0].title === editedTitle && !doc.controller.pending);
        await vscode.commands.executeCommand("workbench.action.files.save");
        await until("native save checkpoint", () => (doc.controller.snapshot.checkpoint_configuration as RecordedConfiguration)?.panels[0].title === editedTitle && !doc.controller.saving);
        assert.equal(new URLSearchParams(doc.uri.query).get("connection"), item.spec.connectionId);
        documents.push({ preview, id, uri: doc.uri });
        checks.push(`${item.label}: Logs scope, overview, blank layout, plot/table controls and server-library Save`);
      }
      assert.notEqual(await documents[0].preview.identity(), await documents[1].preview.identity());
      assert.equal((documents[0].preview as any).budgets, (documents[1].preview as any).budgets);
      assert.equal((documents[0].preview as any).analysisQueue, (documents[1].preview as any).analysisQueue);
      for (const [index, document] of documents.entries()) {
        const library: any = await document.preview.backend.call("/library/list", { kind: "view" });
        assert.equal(library.total_matching, 1); assert.match(JSON.stringify(library), new RegExp(index === 0 ? "Remote A" : "Remote B"));
      }
      checks.push("simultaneous owners keep separate server libraries and share window budgets and analysis workers");
      const first = documents[0]; await vscode.commands.executeCommand("vscode.openWith", first.uri, "wpilog-mcp.visualization");
      await until("first owner active", () => vscode.window.tabGroups.activeTabGroup.activeTab?.input instanceof vscode.TabInputCustom
        && (vscode.window.tabGroups.activeTabGroup.activeTab!.input as vscode.TabInputCustom).uri.toString() === first.uri.toString());
      assert.equal(localCalls, 0); assert.equal(driver.errors.length, 0, JSON.stringify(driver.errors));
      checks.push("switching existing views retains fixed connection and source; zero local starts or renderer errors");
      fs.writeFileSync(path.join(root, "result.json"), JSON.stringify({ checks, localCalls }, null, 2));
    } catch (error) {
      const dom = await driver?.evaluate("document.body?.dataset.overview === 'true' || document.body?.dataset.viewId", "document.body?.innerText");
      const pages = [...((explorer.preview as any).children as Map<unknown, any>).values()].map(p => ({ owner: p.owner, overviews: [...p.overviews.values()].map((o: any) => ({ ready: o.ready, closed: o.closed, uri: o.uri.toString(), summary: o.summary })) }));
      fs.writeFileSync(path.join(root, "result.json"), JSON.stringify({ checks, dom, pages, rendererErrors: driver?.errors, error: String(error), stack: (error as Error).stack }, null, 2));
    }
    finally { driver?.close(); explorer.dispose(); await vscode.commands.executeCommand("workbench.action.quit"); }
  })();
}
