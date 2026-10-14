/** Real recorded-preview components against a real isolated Java process, with synthetic logs. */
import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import * as assert from "node:assert/strict";
import { McpClient } from "../mcpClient";
import { RecordedPreview } from "../visualization/recordedPreview";
import { RecordedView, RecordedConfiguration } from "../visualization/recordedModel";
import { RecordedDevtools } from "./recordedDevtools";

const pause = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
async function until(label: string, check: () => boolean | Promise<boolean>): Promise<void> {
  const deadline = Date.now() + 15000; while (Date.now() < deadline) { if (await check()) return; await pause(50); }
  throw new Error("Timed out: " + label);
}
export function activate(context: vscode.ExtensionContext): void {
  const root = process.env.WPILOG_RECORDED_FIXTURE!;
  if (!root || !fs.existsSync(path.join(root, "fixture-only"))) throw new Error("An isolated recorded fixture is required");
  const client = new McpClient(process.env.WPILOG_RECORDED_ENDPOINT!, "fixture");
  let target = "";
  const preview = new RecordedPreview({ context, client: async () => client, entries: (_path, label) => { target = label; }, grantStore: async () => {} });
  // Count the real product transports; presentation edits must not issue another sample request.
  const requests = { arrow: 0, cursor: 0 };
  const data = (preview as any).data, fetchData = data.fetch.bind(data), call = preview.backend.call.bind(preview.backend);
  data.fetch = (...args: any[]) => { requests.arrow++; return fetchData(...args); };
  preview.backend.call = ((route: string, ...args: any[]) => { if (route === "/source/value") requests.cursor++; return call(route, ...args); }) as typeof preview.backend.call;
  context.subscriptions.push(preview, { dispose: () => { void client.dispose(); } });
  const checks: string[] = [];
  let presentationRequests: { before: typeof requests; after: typeof requests } | undefined;
  void (async () => {
    const phase = process.env.WPILOG_RECORDED_PHASE ?? "initial";
    const driver = await RecordedDevtools.open(Number(process.env.WPILOG_RECORDED_DEBUG_PORT));
    const selector = (id: string) => `document.body?.dataset.viewId === ${JSON.stringify(id)}`;
    const click = async (id: string, element: string) => {
      await until("rendered control " + element, async () => await driver.evaluate(selector(id), `(document.querySelector(${JSON.stringify(element)})?.click(), true)`) === true);
    };
    const ready = async (uri: vscode.Uri) => until("native document and renderer", () => preview.provider.ready.has(uri.toString()));
    try {
      if (phase === "restore") {
        const saved = JSON.parse(fs.readFileSync(path.join(root, "expected.json"), "utf8"));
        assert.equal(await preview.identity(), saved.owner);
        for (const expected of saved.views) {
          const current = await preview.backend.read(expected.view_id); assert.deepEqual(current.configuration, expected.configuration);
          const uri = await preview.open(current, true); await ready(uri);
          await until("restored samples render", () => (preview.rendered.get(current.view_id)?.plots ?? 0) === 1);
        }
        checks.push("real Java and editor restart retain the owner, saved view and both draft UUID/configurations");
      } else {
        await preview.openOverview(vscode.Uri.file(path.join(root, "logs/a.wpilog")));
        await until("overview inventory", async () => (await driver.evaluate("document.body?.dataset.overview === 'true'", "document.getElementById('inventory')?.textContent"))?.includes("signals"));
        assert.ok((await driver.evaluate("document.body?.dataset.overview === 'true'", "document.getElementById('facts')?.textContent")).includes("Unavailable: not recorded"));
        await driver.evaluate("document.body?.dataset.overview === 'true'", "(document.getElementById('blank').click(), true)");
        await until("blank view opened", () => preview.provider.documents.size === 1);
        const aUri = [...preview.provider.documents.values()][0].uri; await ready(aUri);
        const a = preview.provider.documents.get(aUri.toString())!;
        const id = a.controller.snapshot.view_id;
        await click(id, "#plot"); await until("plot persisted", () => (a.controller.snapshot.configuration as RecordedConfiguration).panels.length === 1);
        await click(id, "#values"); await until("value table persisted", () => (a.controller.snapshot.configuration as RecordedConfiguration).panels.length === 2);
        await click(id, "#picker summary");
        for (const panel of (a.controller.snapshot.configuration as RecordedConfiguration).panels) {
          await driver.evaluate(selector(id), `(() => { document.getElementById('target').value=${JSON.stringify(panel.panel_id)}; for (const o of document.getElementById('signals').options) o.selected=o.value==='/Value'; document.getElementById('add').click(); return true; })()`);
          await until("signal persisted", () => (a.controller.snapshot.configuration as RecordedConfiguration).panels.find(p => p.panel_id === panel.panel_id)!.series.length === 1);
        }
        await until("real uPlot and table rendered", () => (preview.rendered.get(id)?.plots ?? 0) === 1 && (preview.rendered.get(id)?.rows ?? 0) === 2);
        const rendered = preview.rendered.get(id)!; assert.ok(rendered.width > 100 && rendered.height > 50);
        assert.ok((rendered as any).axis_colors.every((color: string) => color === (rendered as any).foreground), "plot axes must use the editor foreground, including dark themes");
        await until("latest values loaded", async () => (await driver.evaluate(selector(id), "document.querySelector('.recorded-values')?.textContent"))?.includes("1000000"));
        const text = await driver.evaluate(selector(id), "document.querySelector('.recorded-values')?.textContent"); assert.match(text, /8/); assert.match(text, /1000000/);
        await click(id, ".time-picker summary");
        await driver.evaluate(selector(id), "(document.getElementById('cursor').value='0', true)");
        await click(id, "#at");
        await until("exact cursor sample", async () => await driver.evaluate(selector(id), "document.querySelector('.recorded-values tr:nth-child(2) td:nth-child(2)')?.textContent") === "7");
        await click(id, "#latest");
        await until("latest sample restored", async () => await driver.evaluate(selector(id), "document.querySelector('.recorded-values tr:nth-child(2) td:nth-child(2)')?.textContent") === "8");
        await click(id, ".time-picker summary");
        checks.push("source overview, Blank layout, Add plot/table and multiselect run through rendered controls and the Java owner");
        checks.push("real bundled GridStack and uPlot render Arrow samples plus exact server cursor values under the page CSP");
        await click(id, "#picker summary");
        const beforePresentation = { ...requests };
        await driver.evaluate(selector(id), "(() => { const title=document.querySelector('.panel-title'); title.value='Fixture title'; title.dispatchEvent(new Event('change')); return true; })()");
        await until("title persisted", () => (a.controller.snapshot.configuration as RecordedConfiguration).panels[0].title === "Fixture title");
        await click(id, ".panel-controls summary");
        await driver.enter(selector(id), "button[aria-label='Narrower panel']");
        await click(id, ".panel-controls summary");
        await until("keyboard-equivalent layout persisted", () => (a.controller.snapshot.configuration as RecordedConfiguration).layout.items[0].w === 11);
        await (preview as any).pages.get(id).rendering;
        presentationRequests = { before: beforePresentation, after: { ...requests } };
        assert.deepEqual(presentationRequests.after, presentationRequests.before, "title and keyboard resize must reuse the existing plot and cursor data");
        assert.equal(await driver.evaluate(selector(id), "document.querySelector('.recorded-values tr:nth-child(2) td:nth-child(2)')?.textContent"), "8");
        checks.push("title and keyboard resize retain loaded data with zero additional Arrow or cursor requests");
        const aConfig = structuredClone(a.controller.snapshot.configuration);
        const a2 = await preview.backend.create((a.controller.snapshot as RecordedView).source_ref, aConfig); const a2Uri = await preview.open(a2, true); await ready(a2Uri);
        const bRef = (await preview.backend.identify(path.join(root, "logs/b.wpilog"))).source_ref;
        const b = await preview.backend.create(bRef, aConfig), bUri = await preview.open(b, true); await ready(bUri);
        await until("B render", () => (preview.rendered.get(b.view_id)?.plots ?? 0) === 1);
        await until("B values loaded", async () => (await driver.evaluate(selector(b.view_id), "document.querySelector('.recorded-values')?.textContent"))?.includes("2000000"));
        const bText = await driver.evaluate(selector(b.view_id), "document.querySelector('.recorded-values')?.textContent"); assert.match(bText, /9/); assert.match(bText, /2000000/);
        await click(b.view_id, ".panel-controls summary");
        await click(b.view_id, "button[aria-label='Narrower panel']");
        await click(b.view_id, ".panel-controls summary");
        const bDoc = preview.provider.documents.get(bUri.toString())!;
        await until("B edit", () => (bDoc.controller.snapshot.configuration as RecordedConfiguration).layout.items[0].w === 10);
        assert.deepEqual((await preview.backend.read(id)).configuration, aConfig);
        assert.deepEqual((await preview.backend.read(a2.view_id)).configuration, aConfig);
        assert.ok(target.includes("Visualization")); checks.push("A/A/B views display independent sources; B layout edits do not change either A view");
        // The current B table is the explicit Entries target, even while both A views are visible.
        const bTable = (bDoc.controller.snapshot.configuration as RecordedConfiguration).panels[1];
        await driver.evaluate(selector(b.view_id), `(() => { const t=document.getElementById('target'); t.value=${JSON.stringify(bTable.panel_id)}; t.dispatchEvent(new Event('change')); return true; })()`);
        await until("Entries table target", () => target.includes("Recorded values"));
        assert.equal(await preview.routeSignal("systemTime"), true);
        await until("Entries signal persisted to B table", () => (bDoc.controller.snapshot.configuration as RecordedConfiguration).panels[1].series.length === 2);
        assert.deepEqual((await preview.backend.read(id)).configuration, aConfig);
        assert.deepEqual((await preview.backend.read(a2.view_id)).configuration, aConfig);
        checks.push("Enter resizes a focused panel; Entries routes to B's selected table and leaves both A views unchanged");
        preview.backend.destinations.set(id, { store_path: path.join(root, "logs/store"), title: "Saved fixture" });
        await a.controller.promote(); preview.provider.paint(a);
        assert.ok(a.controller.snapshot.saved_view_ref); assert.equal(a.controller.draftUndoDepth, 0);
        assert.ok(fs.existsSync(path.join(root, "logs/store/store.json")));
        checks.push("real first-store metadata save checkpoints the draft and clears draft undo without enrollment");
        const views = await Promise.all([id, a2.view_id, b.view_id].map(view => preview.backend.read(view)));
        fs.writeFileSync(path.join(root, "expected.json"), JSON.stringify({ owner: await preview.identity(), views }));
      }
      fs.writeFileSync(path.join(root, phase + '-workbench.png'), await driver.screenshot());
      assert.deepEqual(driver.errors, [], "no renderer or CSP errors");
      fs.writeFileSync(path.join(root, phase + '-result.json'), JSON.stringify({ version: vscode.version, checks, presentationRequests, rendered: Object.fromEntries(preview.rendered) }, null, 2));
    } catch (error) {
      fs.writeFileSync(path.join(root, phase + '-diagnostics.json'), JSON.stringify({ errors: driver.errors, rendered: Object.fromEntries(preview.rendered),
        page: await driver.evaluate("!!document.body?.dataset.viewId", "document.body.innerText"),
        geometry: await driver.evaluate("!!document.body?.dataset.viewId", "[...document.querySelectorAll('.panel-body')].map(e=>({width:e.clientWidth,height:e.clientHeight}))") }, null, 2));
      throw error;
    } finally { driver.close(); }
  })().catch(error => {
    fs.writeFileSync(path.join(root, (process.env.WPILOG_RECORDED_PHASE ?? "initial") + '-result.json'), JSON.stringify({ error: String(error), stack: error.stack, checks }));
  }).finally(async () => { await pause(250); await vscode.commands.executeCommand("workbench.action.quit"); });
}
