/** Native time gestures must re-admit historical samples, not just clip a rolling buffer. */
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { randomUUID } from "node:crypto";
import { RecordedPreview } from "../visualization/recordedPreview";
import { addPanel, addSignals, RecordedConfiguration } from "../visualization/recordedModel";
import { RecordedDevtools } from "./recordedDevtools";
const pause = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
async function until(label: string, check: () => boolean | Promise<boolean>): Promise<void> {
  const end = Date.now() + 15000;
  while (Date.now() < end) { if (await check()) return; await pause(40); }
  throw new Error("Timed out: " + label);
}
export async function recordingHistory(preview: RecordedPreview, driver: RecordedDevtools, source: any, root: string,
  mark: (check: string) => void, command: (op: string) => Promise<void>): Promise<void> {
  await command("historyAdvance");
  const file = await preview.backend.identify(path.join(root, "logs/chart-pairs.wpilog"));
  // Earlier fixture phases deliberately create split groups. Collapse those groups so these
  // screenshots show a usable editor width, while each hide/reveal still uses the real UI.
  await vscode.commands.executeCommand("workbench.action.joinAllGroups");
  for (const recording of [true, false]) {
    const view = await preview.backend.create(recording ? source : file.source_ref); await preview.open(view);
    const selector = `document.body?.dataset.viewId === ${JSON.stringify(view.view_id)}`;
    const read = (code: string) => driver.evaluate(selector, code);
    let page: any;
    const originalCall = preview.backend.call.bind(preview.backend);
    const reductions: { body: any; result: any }[] = [];
    preview.backend.call = (async (route: string, body: any, ...rest: any[]) => {
      const result = await (originalCall as any)(route, body, ...rest);
      if (route === "/source/reduction") reductions.push({ body, result });
      return result;
    }) as typeof preview.backend.call;
    try {
      await until("history view ready", () => !!(page = (preview as any).pages.get(view.view_id))?.ready);
      await until("history plot probe installed", async () => await read(`(() => {
        if (!window.__historyPlots) {
          window.__historyPlots = [];
          window.uPlot = new Proxy(window.uPlot, { construct(Type, args) { const plot = new Type(...args); window.__historyPlots.push(new WeakRef(plot)); return plot; } });
        } return true;
      })()`) === true);
      const blank: any = view.configuration;
      let config: RecordedConfiguration = addPanel(addPanel(blank, "history-plot", "time_series"), "history-raw", "raw");
      for (const p of config.panels) config = addSignals(config, p.panel_id,
        [recording ? { name: "NT:/signal/00005", type: "double[]", field: "[0]", sample_count: 5 } : { name: "/x", type: "double", sample_count: 600 }], randomUUID);
      config.time.live = { mode: "follow", window_sec: 45 };
      if (!recording) config.time.recorded = { mode: "fixed", start_sec: 0, end_sec: 0.599 };
      await page.document.controller.edit(config, "Set up synthetic history fixture"); (preview as any).paint(page.document);
      const plot = "window.__historyPlots.map(r => r.deref()).find(p => p?.root?.isConnected)";
      const points = () => read(`(() => { const p = ${plot}; return p ? p.data.map(a => Array.from(a)) : []; })()`);
      let expected = [[1, 2], [1, 8], [1.5, 20], [2, 14], [120, 30]];
      // Independent integer bucket oracle from the generated input, not the server's output.
      const reduced = async () => {
        const range = page.document.controller.snapshot.configuration.time.recorded;
        const response = reductions.filter(r => r.body.start_time === range.start_sec && r.body.end_time === range.end_sec).at(-1);
        if (!response) return false;
        const start = Math.round(range.start_sec * 1e6), end = Math.round(range.end_sec * 1e6), n = response.body.max_points;
        const groups = new Map<number, number[]>();
        for (const [at, value] of expected) { const time = Math.round(at * 1e6); if (time < start || time > end) continue;
          const index = end === start ? 0 : Math.min(n - 1, Math.floor((time - start) * n / (end - start)));
          const group = groups.get(index) ?? []; group.push(value); groups.set(index, group);
        }
        const keys = [...groups.keys()].sort((a, b) => a - b), values = keys.map(k => groups.get(k)!);
        const oracle = [keys.map(k => (start + Math.ceil(k * (end - start) / n)) / 1e6),
          values.map(v => v.reduce((a, b) => a + b, 0) / v.length), values.map(v => Math.max(...v)), values.map(v => Math.min(...v))];
        return response.result.sample_count === values.reduce((sum, v) => sum + v.length, 0)
          && JSON.stringify(await points()) === JSON.stringify(oracle);
      };
      const settled = () => until("history gesture painted", async () => !page.document.controller.pending
        && await read("document.body.dataset.revision") === String(page.document.controller.snapshot.revision)
        && await read("document.body.dataset.generation") === page.key);
      const key = async (id: string) => {
        await until("history control " + id, async () => await read(`!!document.getElementById(${JSON.stringify(id)}) && !document.getElementById(${JSON.stringify(id)}).disabled`) === true);
        await read(`(() => { const e = document.getElementById(${JSON.stringify(id)}); const details = e.closest('details'); if (details) details.open = true; return true; })()`);
        await driver.enter(selector, "#" + id);
      };
      const mutate = async (id: string) => { const before = page.document.controller.snapshot.revision; await key(id); await until("history revision", () => page.document.controller.snapshot.revision > before); await settled(); };
      await until("history initial samples", async () => (await points())[0]?.length === (recording ? 1 : 600));
      if (recording) {
        assert.deepEqual(await points(), [[120], [30]]);
        for (let i = 0; i < 3; i++) await mutate("time-zoom-out");
        await until("zoom reduces old samples beyond the rolling buffer", reduced);
        assert.match(await read("document.getElementById('time-mode').textContent"), /Inspect History/);
        mark("native keyboard zoom reboots three historical intervals and recovers every original sample in independently checked buckets beyond the 45-second rolling display");
      } else {
        await mutate("time-zoom-in"); await until("file zoom narrowed samples", async () => (await points())[0]?.length === 300);
        const p = await points(); assert.equal(Math.round(p[0][0] * 1e6), 150000); assert.equal(Math.round(p[0].at(-1) * 1e6), 449000);
        assert.equal(p[1][0], 150); assert.equal(p[1].at(-1), 449);
        mark("native file Zoom in requests the exact 0.14975–0.44925 interval: 300 original x=i samples");
      }
      await read(`(document.getElementById('start').value = '0', document.getElementById('end').value = '${recording ? 3 : 0.6}', true)`);
      await mutate("range"); await until("range samples", recording ? reduced : async () => (await points())[0]?.some((t: number) => Math.round(t * 1e6) === 599000));
      const beforeDrag = page.document.controller.snapshot.revision;
      // Drive uPlot's real mouse handlers, with geometry taken from its real CSS-pixel plot.
      await read(`(async () => { const p = ${plot}; p.root.scrollIntoView({ block: 'center' });
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))); return true; })()`);
      const drag = await read(`(() => {
        const p = ${plot}; if (!p) return false;
        p.syncRect(); const r = p.over.getBoundingClientRect();
        const width = p.bbox.width / devicePixelRatio;
        const start = Math.round(r.left + width / 4), end = Math.round(r.left + 3 * width / 4), y = r.top + r.height / 2;
        p.over.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: start, clientY: y }));
        p.over.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0, buttons: 1, clientX: start, clientY: y }));
        p.over.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, buttons: 1, movementX: end - start, clientX: end, clientY: y }));
        document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, button: 0, clientX: end, clientY: y }));
        return { start: (start - r.left) / width, end: (end - r.left) / width, width };
      })()`); assert.ok(drag.width > 0);
      await until("drag persisted", () => page.document.controller.snapshot.revision > beforeDrag); await settled();
      const actual = page.document.controller.snapshot.configuration.time.recorded;
      const span = recording ? 3 : 0.6;
      assert.ok(Math.abs(actual.start_sec - drag.start * span) <= 0.000001);
      assert.ok(Math.abs(actual.end_sec - drag.end * span) <= 0.000001);
      const times = recording ? [1, 1.5, 2] : Array.from({ length: 600 }, (_, i) => i / 1000).filter(t => t >= actual.start_sec && t <= actual.end_sec);
      await until("drag reloaded original values", recording ? reduced : async () => (await points())[0]?.length === times.length);
      if (!recording) assert.deepEqual((await points())[0].map((t: number) => Math.round(t * 1e6)), times.map(t => Math.round(t * 1e6)));
      mark(`native ${recording ? "recording" : "file"} horizontal uPlot drag persists its clock interval and reloads data through the product reader`);
      if (recording) {
        const range = structuredClone(actual); await command("historyLate");
        expected.push([1.25, 26]);
        await until("late historical record recomputes old buckets", reduced);
        assert.deepEqual(page.document.controller.snapshot.configuration.time.recorded, range);
        const axes = await read(`(() => { const p = ${plot}; return [p.scales.x.min, p.scales.x.max]; })()`);
        assert.deepEqual(axes, [range.start_sec, range.end_sec]);
        await read("(document.querySelector('.time-picker').open = false, document.getElementById('time-mode').previousElementSibling.scrollIntoView({block:'start'}), true)");
        await pause(100); fs.writeFileSync(path.join(root, "history-inspect.png"), await driver.screenshot());
        const text = await vscode.workspace.openTextDocument({ content: "Restore history window choice" });
        await vscode.window.showTextDocument(text, { viewColumn: page.panel.viewColumn, preview: false });
        await until("history view hidden", () => !page.panel.visible);
        await preview.open(page.document.controller.snapshot); await until("history view revealed", () => page.panel.visible);
        await until("restored history choice", async () => Number(await read("document.getElementById('live-window').value")) === 45);
        assert.deepEqual(page.document.controller.snapshot.configuration.time.recorded, range);
        await key("live-pause"); const revision = page.document.controller.snapshot.revision;
        await key("time-earlier"); await until("navigation exits pause", () => page.document.controller.snapshot.revision > revision && !page.live.paused); await settled();
        await mutate("live-follow");
        assert.deepEqual(page.document.controller.snapshot.configuration.time.live, { mode: "follow", window_sec: 45 });
        await until("following samples restored", async () => (await points())[0]?.length === 1 && (await points())[0][0] === 120);
        mark("late in-window record updates Inspect History without moving its axis; navigation exits Pause and Resume following restores 45 seconds");
        await command("denseHistory");
        const dense = addSignals(addPanel(blank, "dense", "time_series"), "dense",
          [{ name: "NT:/signal/00020", type: "int64", sample_count: 5003 }], randomUUID);
        dense.time.live = { mode: "fixed", start_sec: 0, end_sec: 5 }; dense.time.recorded = { mode: "fixed", start_sec: 0, end_sec: 5 };
        expected = [[1, 20], [1, 20], [2, 21], ...Array.from({ length: 5000 }, (_, i) => [i / 1000, i === 10 ? 10000 : 0])];
        await page.document.controller.edit(dense, "Inspect dense synthetic history"); (preview as any).paint(page.document); await settled();
        await until("dense full-history extrema and means match independent buckets", reduced);
        assert.ok((await points())[2].includes(10000), "early peak beyond the old 4096-point tail survives");
        assert.match(await read("document.querySelector('.panel-note').textContent"), /5,003 original samples/);
        mark("dense 5003-record native history preserves the early 10000 spike and every count/mean/min/max against an independent bucket oracle");
      } else {
        await mutate("time-later"); const range = page.document.controller.snapshot.configuration.time.recorded;
        assert.ok(Math.abs(range.start_sec - 0.3) < 0.01); assert.ok(Math.abs(range.end_sec - 0.6) < 0.01);
        await mutate("full"); await until("full file restored", async () => (await points())[0]?.length === 600);
        mark("native file Later pans half a window and Full recording restores all 600 samples");
        await read("(document.getElementById('start').value = '2', document.getElementById('end').value = '3', true)");
        await mutate("range"); await until("empty historical file interval", async () => (await points())[0]?.length === 0);
        const axes = await read(`(() => { const p = ${plot}; return [p.scales.x.min, p.scales.x.max]; })()`);
        assert.deepEqual(axes, [2, 3]);
        mark("empty historical file intervals retain their requested axis and render without an exception");
      }
      await settled();
      await read("(document.querySelector('.time-picker').open = false, document.getElementById('time-mode').previousElementSibling.scrollIntoView({block:'start'}), true)");
      await pause(100); fs.writeFileSync(path.join(root, recording ? "history-recording.png" : "history-file.png"), await driver.screenshot());
      assert.ok(preview.budgets.decoded.reserved <= preview.budgets.decoded.maximum);
    } catch (error) { fs.writeFileSync(path.join(root, "history-diagnostics.json"), JSON.stringify({ recording, snapshot: page?.document.controller.snapshot,
      body: await read("document.body.innerText"), errors: driver.errors }, null, 2)); throw error; }
    finally { preview.backend.call = originalCall; page?.panel.dispose(); }
  }
}
