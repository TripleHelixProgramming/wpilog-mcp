/** Product chart controls against independent synthetic values, in an isolated native editor. */
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { RecordedPreview } from "../visualization/recordedPreview";
import { RecordedDevtools } from "./recordedDevtools";
const pause = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
async function until(label: string, check: () => boolean | Promise<boolean>): Promise<void> {
  const end = Date.now() + 15000;
  while (Date.now() < end) { if (await check()) return; await pause(40); }
  throw new Error("Timed out: " + label);
}
/** WPILOG format written independently of the server: two declarations, then x=i and y=2i
 * at i milliseconds. This makes page coordinates and bin totals independently checkable. */
function chartFile(file: string): void {
  const bytes: Buffer[] = [Buffer.from("WPILOG", "ascii"), Buffer.from([0, 1, 0, 0, 0, 0])];
  const text = (s: string) => { const value = Buffer.from(s), size = Buffer.alloc(4); size.writeUInt32LE(value.length); return Buffer.concat([size, value]); };
  const record = (entry: number, us: number, data: Buffer) => {
    const header = Buffer.alloc(17); header[0] = 0x7f; header.writeUInt32LE(entry, 1); header.writeUInt32LE(data.length, 5); header.writeBigUInt64LE(BigInt(us), 9); bytes.push(header, data);
  };
  for (let id = 1; id <= 2; id++) { const head = Buffer.alloc(5); head[0] = 0; head.writeUInt32LE(id, 1); record(0, 0, Buffer.concat([head, text(id === 1 ? "/x" : "/y"), text("double"), text("")])); }
  for (let i = 0; i < 600; i++) for (let id = 1; id <= 2; id++) { const value = Buffer.alloc(8); value.writeDoubleLE(id * i); record(id, i * 1000, value); }
  fs.writeFileSync(file, Buffer.concat(bytes));
}
export async function recordingCharts(preview: RecordedPreview, driver: RecordedDevtools, source: any, root: string, mark: (check: string) => void, command: (op: string) => Promise<void>): Promise<void> {
  const file = path.join(root, "logs/chart-pairs.wpilog"); chartFile(file);
  const fileSource = await preview.backend.identify(file);
  for (const recording of [true, false]) {
    const view = await preview.backend.create(recording ? source : fileSource.source_ref); await preview.open(view, true);
    const selector = `document.body?.dataset.viewId === ${JSON.stringify(view.view_id)}`;
    const read = (code: string) => driver.evaluate(selector, code);
    let page: any;
    try {
      await until("chart view ready", () => !!(page = (preview as any).pages.get(view.view_id))?.ready);
      await until("chart drawing probe installed", async () => await read(`(() => {
        if (!window.__chartDrawing) {
          window.__chartDrawing = { arcs: 0, plots: [] };
          const arc = Path2D.prototype.arc;
          Path2D.prototype.arc = function(...args) { window.__chartDrawing.arcs++; return arc.apply(this, args); };
          window.uPlot = new Proxy(window.uPlot, { construct(Type, args) { const plot = new Type(...args); window.__chartDrawing.plots.push(new WeakRef(plot)); return plot; } });
        } return true;
      })()`) === true);
      const settled = () => until("chart gesture painted", async () => !page.document.controller.pending && await read("document.body.dataset.revision") === String(page.document.controller.snapshot.revision) && await read("document.body.dataset.generation") === page.key);
      const click = async (id: string) => until("chart control " + id, async () => await read(`(() => { const e = document.getElementById(${JSON.stringify(id)}); if (!e || e.disabled) return false; e.click(); return true; })()`) === true);
      const panelButton = async (label: string) => until(label, async () => await read(`(() => { const b = [...document.querySelectorAll('#grid button')].find(b => b.textContent === ${JSON.stringify(label)} && !b.disabled); if (!b) return false; b.click(); return true; })()`) === true);
      const addPanel = async (button: string) => {
        const count = page.document.controller.snapshot.configuration.panels.length;
        await click(button); await until("chart panel persisted", () => page.document.controller.snapshot.configuration.panels.length === count + 1); await settled();
        await read("(() => { const e = document.getElementById('target'); e.value = e.options[e.options.length - 1].value; e.dispatchEvent(new Event('change')); return true; })()");
      };
      const add = async (field: string) => {
        await settled(); const revision = page.document.controller.snapshot.revision;
        if (recording) {
          await read(`(() => { const e = document.getElementById('inventory-field'); e.value = ${JSON.stringify(field)}; e.dispatchEvent(new Event('input')); return true; })()`);
          await click("inventory-field-add");
        } else {
          await read(`(() => { for (const o of document.getElementById('signals').options) o.selected = o.value === ${JSON.stringify(field)}; return true; })()`); await click("add");
        }
        await until("chart signal persisted", () => page.document.controller.snapshot.revision > revision); await settled();
      };
      if (recording) {
        const inventoryGeneration = page.recordingInventory.snapshot.generation;
        await read("(document.getElementById('recording-picker').open = true, document.getElementById('inventory-search').value = 'NT:/signal/00005', true)");
        await click("inventory-find"); await until("chart selected row painted", async () => !page.recordingInventory.snapshot.loading
          && page.recordingInventory.snapshot.generation > inventoryGeneration && !page.delivery.flight && !page.delivery.pending
          && page.recordingInventory.snapshot.page?.entries[0]?.name === "NT:/signal/00005"
          && await read("(() => { const row = document.querySelector('#inventory-rows tr'); if (row?.cells[0]?.textContent !== 'NT:/signal/00005') return false; const button = row.querySelector('button'); if (!button || button.disabled) return false; button.click(); return true; })()") === true);
        await until("chart fresh field row", () => !page.recordingInventory.snapshot.loading && !!page.recordingInventory.snapshot.details);
      } else await until("chart file signals", () => !!page.inventory?.entries?.length);
      await addPanel("histogram"); await add(recording ? "[0]" : "/x");
      await addPanel("scatter"); await add(recording ? "[0]" : "/x"); await add(recording ? "[1]" : "/y");
      const tables = () => read("[...document.querySelectorAll('#grid table')].map(t => [...t.rows].map(r => [...r.cells].map(c => c.textContent))).filter(t => ['Low','X'].includes(t[0]?.[0]))");
      await until("native histogram and scatter", async () => (await tables())?.length === 2 && (preview.rendered.get(view.view_id)?.plots ?? 0) === 2);
      await until("scatter points really drawn", async () => await read("window.__chartDrawing.arcs >= 3") === true);
      const drawing = await read("window.__chartDrawing.plots.map(r => r.deref()).filter(p => p?.root.isConnected).map(p => ({ kind:p.previewKind, x:p.scales.x, y:p.scales.y }))");
      const histogramDrawing = drawing.find((p: any) => p.kind === "histogram");
      assert.equal(histogramDrawing.y.min, 0); assert.equal(histogramDrawing.x.min, recording ? 2 : 0); assert.equal(histogramDrawing.x.max, recording ? 14 : 599);
      const rows = await tables(), hist = rows.find((t: any) => t[0][0] === "Low"), scatter = rows.find((t: any) => t[0][0] === "X");
      if (recording) {
        assert.deepEqual(hist, [["Low","High","Count"],["2","8","1"],["8","14","2"]]);
        assert.deepEqual(scatter, [["X","Y","Robot time (s)"],["2","6","1"],["8","12","1"],["14","18","2"]]);
        assert.match(await read("document.getElementById('grid').textContent"), /3 matched; 0 unpaired X; 0 unpaired Y/);
        mark("native recording histogram bins [2,8):1 and [8,14]:2; scatter pairs (2,6),(8,12),(14,18) keep duplicate times across two members");
        await click("live-pause");
        assert.equal(await read("[...document.querySelectorAll('#grid button')].filter(b => b.textContent === 'Refresh chart / Retry').every(b => b.disabled)"), true);
        await command("chartAppend"); await pause(1100);
        assert.deepEqual(await tables(), rows, "Pause freezes chart measurements while acquisition consumes a backward append");
        await click("live-resume");
        await until("four chart pairs after resume", async () => (await tables()).find((t: any) => t[0][0] === "X")?.length === 5);
        const after = await tables();
        assert.deepEqual(after.find((t: any) => t[0][0] === "Low"), [["Low","High","Count"],["2","11","2"],["11","20","2"]]);
        assert.deepEqual(after.find((t: any) => t[0][0] === "X"), [["X","Y","Robot time (s)"],["2","6","1"],["8","12","1"],["20","24","1.5"],["14","18","2"]]);
        mark("native paused charts stay fixed during a backward append; resume recomputes bins [2,11):2,[11,20]:2 and four exact pairs");
      } else {
        assert.equal(hist.slice(1).reduce((n: number, r: string[]) => n + Number(r[2]), 0), 600);
        const pageSize = scatter.length - 1; assert.ok(pageSize > 0 && pageSize <= 256 && pageSize < 600);
        assert.deepEqual(scatter[1], ["0","0","0"]);
        assert.deepEqual(scatter.at(-1), [String(pageSize - 1), String(2 * (pageSize - 1)), String((pageSize - 1) / 1000)]);
        await panelButton("Next pairs"); await until("next chart page", async () => (await tables()).find((t: any) => t[0][0] === "X")?.[1]?.[0] === String(pageSize));
        const next = (await tables()).find((t: any) => t[0][0] === "X"); assert.equal(next[1][1], String(pageSize * 2)); assert.ok(next.length <= 257);
        await panelButton("First pairs"); await until("first chart page restored", async () => (await tables()).find((t: any) => t[0][0] === "X")?.[1]?.[0] === "0");
        mark("native file histogram counts all 600 samples; scatter pages use x=i,y=2i,t=i/1000 and replace rather than append");
      }
      await until("chart queue settled", () => !page.analysis.running && !page.analysis.timer);
      const generation = page.key, owner = page.analysis, before = owner.started;
      await read("(() => { const e = document.querySelector('.panel-title'); e.value = 'Retained chart'; e.dispatchEvent(new Event('change')); return true; })()");
      await until("chart title saved", () => page.document.controller.snapshot.configuration.panels[0].title === "Retained chart"); await settled(); await pause(150);
      assert.equal(page.key, generation); assert.equal(page.analysis, owner); assert.equal(owner.started, before, "title must not request charts again");
      await panelButton("Swap X and Y"); await until("swapped chart query", () => page.key !== generation); await settled();
      await until("swapped axes", async () => {
        const rows = (await tables()).find((t: any) => t[0][0] === "X"); return rows?.[2]?.[0] === (recording ? "12" : "2");
      });
      const swapped = (await tables()).find((t: any) => t[0][0] === "X"); assert.deepEqual(swapped[2], recording ? ["12","8","1"] : ["2","1","0.001"]);
      mark(`native ${recording ? "recording" : "file"} presentation keeps chart requests flat; Swap X and Y persists and rebinds the ordered pair`);
      const text = await vscode.workspace.openTextDocument({ content: "Hidden chart fixture" });
      await vscode.window.showTextDocument(text, { viewColumn: page.panel.viewColumn, preview: false }); await until("charts hidden", () => page.analysis?.visible === false);
      await preview.open(page.document.controller.snapshot); await until("charts revealed", () => page.panel.visible && page.analysis?.visible);
      await until("revealed chart tables", async () => (await tables())?.length === 2 && (await tables()).every((t: any) => t.length > 1));
      await read("(document.getElementById('recording-picker').open = false, document.getElementById('grid').scrollIntoView({block:'start'}), true)");
      await pause(150); fs.writeFileSync(path.join(root, recording ? "recording-charts.png" : "file-charts.png"), await driver.screenshot());
      mark(`native ${recording ? "recording" : "file"} hide/reveal re-admits charts; retained bytes remain inside the shared window allowance`);
      assert.ok(preview.budgets.decoded.reserved <= preview.budgets.decoded.maximum);
    } catch (error) { fs.writeFileSync(path.join(root, "chart-diagnostics.json"), JSON.stringify({ recording, body: await read("document.body.innerText"), errors: driver.errors }, null, 2)); throw error; }
    finally { page?.panel.dispose(); }
  }
}
