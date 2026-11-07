/** Real editor, product binding/follow path and a 20,000-topic two-member synthetic recording. */
import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import * as assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { McpClient } from "../mcpClient";
import { addPanel, addSignals, Inventory, RecordedConfiguration } from "../visualization/recordedModel";
import { RecordedPreview } from "../visualization/recordedPreview";
import { RecordedDevtools } from "./recordedDevtools";
import { recordingFields } from "./recordingFields";
import { recordingCharts } from "./recordingCharts";
import { recordingHistory } from "./recordingHistory";
const pause = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
async function until(label: string, check: () => boolean | Promise<boolean>, ms = 15000): Promise<void> {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await check()) return; await pause(40); }
  throw new Error("Timed out: " + label);
}
export function activate(context: vscode.ExtensionContext): void {
  const root = process.env.WPILOG_RECORDED_FIXTURE!;
  if (!root || !fs.existsSync(path.join(root, "fixture-only"))) throw new Error("Isolated fixture required");
  const client = new McpClient(process.env.WPILOG_RECORDED_ENDPOINT!, "panel-fixture", async () => ({
    directories: { paths: [{ path: path.join(root, "logs"), team: null }], team: null }, key: null }));
  const preview = new RecordedPreview({ context, client: async () => client, entries() {}, grantStore: async () => {} });
  context.subscriptions.push(preview, { dispose() { void client.dispose(); } });
  const calls: string[] = [], routes: string[] = [], checks: string[] = [];
  const trace: unknown[] = [], observed = new WeakSet<object>();
  const traceTimer = setInterval(() => {
    for (const page of (preview as any).pages.values()) {
      const owner = page.analysis; if (!owner || observed.has(owner)) continue; observed.add(owner);
      for (const name of ["complete", "cursor", "pause", "hide", "show", "invalidate", "round", "dispose"]) {
        const operation = owner[name].bind(owner);
        owner[name] = (...args: any[]) => {
          trace.push({ at: Date.now(), view: page.document.controller.snapshot.view_id, operation: name,
            visible: owner.visible, paused: owner.paused, running: owner.running, dirty: [...owner.dirty], epochs: { ...owner.epochs } });
          return operation(...args);
        };
      }
      const backend = owner.backend;
      owner.backend = async (...args: any[]) => {
        const started = Date.now(), view = page.document.controller.snapshot.view_id;
        try { const result = await backend(...args); trace.push({ started, at: Date.now(), view, route: args[0], body: args[1],
          value: result.value, ordinal: result.ordinal, members: result.prefix?.members }); return result; }
        catch (error) { trace.push({ started, at: Date.now(), view, route: args[0], error: String(error) }); throw error; }
      };
    }
  }, 10);
  const headers = client.followHeaders.bind(client); client.followHeaders = async url => { calls.push(url); return headers(url); };
  let refuseNextAttachment = false, attachmentRefusals = 0;
  const original = client.visualization.bind(client); client.visualization = (async (...args: Parameters<typeof client.visualization>) => {
    routes.push(args[0]);
    if (refuseNextAttachment && args[0].endsWith("/attach")) { refuseNextAttachment = false; attachmentRefusals++; throw new Error("Synthetic transient reattachment refusal"); }
    return original(...args);
  }) as typeof client.visualization;
  const mark = (check: string) => { checks.push(check); fs.writeFileSync(path.join(root, "progress.json"), JSON.stringify({ checks, calls, routes }, null, 2)); };
  const command = async (op: string, value?: string, at_us = 3_000_000) => {
    const id = randomUUID(); trace.push({ at: Date.now(), command: op, value, at_us }); fs.writeFileSync(path.join(root, "command.json"), JSON.stringify({ id, op, value, at_us }));
    await until("fixture " + op, () => { try { return JSON.parse(fs.readFileSync(path.join(root, "command-result.json"), "utf8")).id === id; } catch { return false; } });
    const result = JSON.parse(fs.readFileSync(path.join(root, "command-result.json"), "utf8"));
    if (result.error) throw new Error(result.error);
    return result;
  };
  void (async () => {
    const driver = await RecordedDevtools.open(Number(process.env.WPILOG_RECORDED_DEBUG_PORT));
    let selector = "document.body?.dataset.overview === 'true'";
    const read = (expression: string) => driver.evaluate(selector, expression);
    const click = async (id: string) => { await until("enabled " + id, async () => await read(`(() => { const e = document.getElementById(${JSON.stringify(id)}); if (!e || e.disabled) return false; e.click(); return true; })()`) === true); };
    try {
      const member = vscode.Uri.file(path.join(root, "logs/recording-store/robots/synthetic/sessions/2026-01-01/session/capture.wpilog"));
      await preview.openOverview(member);
      const expectedMiB = Number(process.env.WPILOG_DECODED_MIB ?? 64);
      await preview.identity();
      const facts: any = await preview.backend.capabilities();
      const own = facts.attached_windows.filter((w: any) => w.current_session); assert.equal(own.length, 1);
      const reported = own[0], transport: any = await preview.backend.client();
      assert.ok(transport.sessionId && !JSON.stringify(facts).includes(transport.sessionId), "capabilities must not contain a transport session ID");
      assert.match(reported.client_id, /^window-[0-9a-f]{32}$/);
      assert.equal(reported.decoded_buffer_budget_bytes, expectedMiB * 1048576); assert.equal(reported.basis,"client_reported");
      assert.equal(preview.budgets.decoded.maximum, expectedMiB * 1048576);
      assert.equal(preview.budgets.encoded.maximum,64 * 1048576);
      mark("configured window budget is enforced locally and reported under an opaque window ID through server capabilities");
      await until("large inventory", async () => (await read("document.getElementById('inventory-count').textContent"))?.includes("20,000"));
      await click("blank"); await until("working recording view", () => (preview as any).pages.size === 1);
      const page: any = [...(preview as any).pages.values()][0], id = page.document.controller.snapshot.view_id;
      selector = `document.body?.dataset.viewId === ${JSON.stringify(id)}`;
      await until("blank recording ready", async () => await read("!document.getElementById('live-controls').hidden") === true);
      // Only two inventories are admitted per session. The opening overview is no longer
      // part of this workflow; a later editor-group reveal must not make it a third owner.
      for (const overview of [...(preview as any).overviews.values()] as any[]) {
        await overview.inventory?.hide(); overview.panel.dispose();
      }
      assert.equal((preview as any).overviews.size, 0, "The unused overview must release its inventory before peer workflows");
      assert.ok((await read("document.getElementById('budget').textContent")).includes(`${expectedMiB} MiB`));
      const settled = () => until("gesture persisted and painted", async () => !page.document.controller.pending && await read("document.body.dataset.revision") === String(page.document.controller.snapshot.revision) && await read("document.body.dataset.generation") === page.key);
      await click("plot"); await until("plot created", () => page.document.controller.snapshot.configuration.panels.length === 1); await settled();
      await read("(document.getElementById('recording-picker').open = true, document.getElementById('inventory-search').value = 'NT:/signal/00000', true)");
      await click("inventory-find"); await until("one selected row", () => page.recordingInventory.snapshot.page?.total_matching === 1 && !page.recordingInventory.snapshot.loading);
      await read("(document.querySelector('#inventory-rows button').click(), true)");
      await until("fresh detail", () => !!page.recordingInventory.snapshot.details && !page.recordingInventory.snapshot.loading);
      await click("inventory-add"); await until("plot from bound follow", () => !!preview.rendered.get(id)?.plots);
      assert.ok(page.recordingInventory.snapshot.summary.total_entry_names === 20000);
      mark("20,000-topic two-member overview opens a draft; fresh paged selection binds a real 1 MiB plot");
      for (const button of ["values", "raw"]) {
        const count = page.document.controller.snapshot.configuration.panels.length;
        await click(button); await until("panel persisted", () => page.document.controller.snapshot.configuration.panels.length > count); await settled();
        await read(`(() => { const e = document.getElementById('target'); e.value = ${JSON.stringify("")} || e.options[e.options.length - 1].value; e.dispatchEvent(new Event('change')); return true; })()`);
        await click("inventory-add"); await until("signal persisted", () => page.document.controller.snapshot.configuration.panels.at(-1).series.length > 0); await settled();
      }
      await until("all three panels", async () => await read("document.querySelectorAll('.recorded-values tr').length >= 2 && document.querySelectorAll('.raw-records tr').length >= 4") === true);
      await command("append", "9007199254740993");
      await until("exact 64-bit live value", async () => (await read("document.querySelector('.recorded-values').textContent"))?.includes("9007199254740993"));
      assert.ok((await read("document.querySelector('.raw-records').textContent")).includes("9007199254740993"));
      assert.ok((await read("document.getElementById('clock').textContent")).includes("Displayed prefix"));
      assert.ok(routes.includes("/source/value"));
      assert.equal(routes.filter(r => ["/source/raw", "/source/resolve"].includes(r)).length, 0);
      mark("live append reaches server timestamp-latest and ordinal raw tables without selecting a single member");
      // Open a recorded file beside the active follow using the same window-owned allowance.
      const source = await preview.backend.identify(path.join(root,"logs/a.wpilog"));
      const inventory = await preview.backend.describe(source.source_ref);
      const candidate = inventory.entries.find(e => ["double","float","int64"].includes(e.type) && e.sample_count > 0)!;
      assert.ok(candidate);
      let config = addPanel(facts.blank_configuration as RecordedConfiguration,"recorded-plot","time_series");
      config = addSignals(config,"recorded-plot",[candidate],randomUUID);
      const recorded = await preview.backend.create(source.source_ref,config);
      const beforeRecorded = preview.budgets.decoded.reserved;
      await preview.open(recorded,true);
      await until("recorded peer with live plot",()=>!!preview.rendered.get(recorded.view_id)?.plots);
      const recordedPage:any = (preview as any).pages.get(recorded.view_id);
      assert.equal(recordedPage.recorded.budgets,preview.budgets);
      assert.equal(page.live.budget,preview.budgets.decoded);
      assert.ok(preview.budgets.decoded.reserved>beforeRecorded);
      assert.ok(preview.budgets.decoded.reserved<=expectedMiB*1048576);
      await command("append","41");
      await until("live peer remains current during recorded display",async()=> (await read("document.querySelector('.recorded-values').textContent"))?.includes("41"));
      recordedPage.panel.dispose(); await preview.open(page.document.controller.snapshot);
      mark("recorded and live plots share one decoded allowance; closing the recorded peer leaves live acquisition current");
      await settled(); await pause(1200); const opens = () => calls.filter(url => url.endsWith('/read/v1/follows')).length, before = opens();
      const beforeAnalysis = routes.filter(r => r === "/source/value" || r === "/source/statistics").length;
      await read("(() => { const e = document.querySelector('.panel-title'); e.value = 'Retained recording plot'; e.dispatchEvent(new Event('change')); return true; })()");
      await until("title persisted", () => page.document.controller.snapshot.configuration.panels[0].title === "Retained recording plot"); await settled(); await pause(400); assert.equal(opens(), before);
      assert.equal(routes.filter(r => r === "/source/value" || r === "/source/statistics").length, beforeAnalysis);
      mark("presentation-only title edit keeps admitted follows, analysis and plot data");
      await click("live-pause"); await command("append", "42"); await pause(600);
      assert.ok((await read("document.querySelector('.recorded-values').textContent")).includes("41"));
      await click("live-resume"); await until("resumed latest prefix", async () => (await read("document.querySelector('.recorded-values').textContent"))?.includes("42"));
      assert.equal(opens(), before); mark("pause freezes display while acquisition continues; resume renders newest completed prefix without reopening");
      await click("duplicate"); await until("independent peer", () => (preview as any).pages.size === 2);
      const peer: any = [...(preview as any).pages.values()].find((p: any) => p !== page);
      await until("peer plot", () => !!preview.rendered.get(peer.document.controller.snapshot.view_id)?.plots);
      const afterPeer = opens(); peer.panel.dispose(); await pause(300); assert.equal(opens(), afterPeer);
      await command("append", "43"); await until("uninterrupted original", async () => (await read("document.querySelector('.recorded-values').textContent"))?.includes("43"));
      mark("duplicate opens beside independently; closing it leaves the original peer following");
      await click("live-pause"); await until("paused before server replacement", () => page.live.paused);
      const recoverySnapshot = structuredClone(page.document.controller.snapshot), priorSession = (client as any).sessionId;
      const priorKey = page.key, priorPrefix = await read("document.getElementById('clock').textContent");
      refuseNextAttachment = true; await command("restart");
      await until("same view automatically reattached after transient refusal", () => attachmentRefusals === 1 && (client as any).sessionId !== priorSession
        && !page.recovering && page.key !== priorKey, 30000);
      assert.equal(page.live.paused, true, "Server recovery must not unpause the user's display");
      await click("live-resume");
      await until("recovered original recording data", async () => (await read("document.querySelector('.recorded-values').textContent"))?.includes("43"));
      assert.deepEqual(page.document.controller.snapshot.configuration, recoverySnapshot.configuration);
      assert.equal(page.document.controller.snapshot.view_id, recoverySnapshot.view_id);
      assert.deepEqual(page.document.controller.snapshot.source_ref, recoverySnapshot.source_ref);
      await until("fresh prefix after owner restart", async () => (await read("document.getElementById('clock').textContent")) !== priorPrefix);
      const recoveredFacts: any = await preview.backend.capabilities();
      assert.equal(recoveredFacts.attached_windows.filter((w: any) => w.current_session).length, 1);
      mark("HTTP/session/workspace restart reattaches the same working UUID and configuration after one refused attachment, with fresh buffers and window registration");
      await until("same-clock render timing visible", async () => (await read("document.getElementById('live-timing').textContent"))?.includes("Host page receipt to render acknowledgement"));
      assert.ok(routes.includes("/source/health"));
      assert.ok((await read("document.getElementById('live-timing').textContent")).includes("not source-to-screen latency"));
      mark("visible recording independently polls source health and reports host/renderer timing after animation-frame acknowledgement");
      const previousPrefix = await read("document.getElementById('clock').textContent");
      const text = await vscode.workspace.openTextDocument({ content: "Hidden recording preview fixture" });
      await vscode.window.showTextDocument(text, { viewColumn: page.panel.viewColumn, preview: false });
      await until("hidden lease released", () => (preview as any).broker.resources.jobs === 0);
      const hiddenCalls = calls.length, hiddenAnalysis = routes.filter(r => r === "/source/value" || r === "/source/statistics").length;
      await pause(500); assert.equal(calls.length, hiddenCalls);
      assert.equal(routes.filter(r => r === "/source/value" || r === "/source/statistics").length, hiddenAnalysis);
      await preview.open(page.document.controller.snapshot); await until("revealed plot", () => (preview as any).broker.resources.jobs === 1 && opens() > afterPeer);
      await until("revealed prefix completed", async () => { const now = await read("document.getElementById('clock').textContent"); return now?.includes("Displayed prefix") && now !== previousPrefix; });
      await command("append", "44"); await until("fresh reveal", async () => (await read("document.querySelector('.recorded-values').textContent"))?.includes("44"));
      mark("hide cancels acquisition with no background polling; reveal bootstraps the same anchored recording");
      await command("append", "-8", 500000);
      await until("backward record delivered", async () => (await read("document.querySelector('.raw-records').textContent"))?.includes("-8"));
      await pause(1200);
      const tableValue = () => read("document.querySelector('.recorded-values tr:nth-child(2) td:nth-child(2)').textContent");
      assert.equal(await tableValue(), "44", "last physical record must not replace the timestamp maximum");
      await read("(document.getElementById('cursor').value = '0.75', true)"); await click("at");
      await until("backward timestamp cursor", async () => await tableValue() === "-8");
      assert.equal(await read("document.querySelector('.recorded-values tr:nth-child(2) td:nth-child(3)').textContent"), "500000");
      assert.ok((await read("document.querySelector('.analysis-prefix').textContent")).includes("not the plot frontier"));
      await click("latest"); await until("timestamp maximum restored", async () => await tableValue() === "44");
      mark("server latest ignores a backward physical append; explicit cursor resolves its exact value and independent prefix");
      await click("statistics"); await until("statistics panel persisted", () => page.document.controller.snapshot.configuration.panels.length === 4); await settled();
      await read("(() => { const e = document.getElementById('target'); e.value = e.options[e.options.length - 1].value; e.dispatchEvent(new Event('change')); return true; })()");
      await click("inventory-add"); await until("statistics signal persisted", () => page.document.controller.snapshot.configuration.panels.at(-1).series.length > 0); await settled();
      const stat = (key: string) => read(`(() => { const rows = [...document.querySelectorAll('.recorded-statistics tr')]; return rows.find(r => r.cells[0].textContent === ${JSON.stringify(key)})?.cells[1].textContent; })()`);
      await until("all nine original samples analyzed", async () => await stat("count") === "9");
      await read("(document.getElementById('start').value = '0', document.getElementById('end').value = '1.5', true)"); await click("range");
      await until("fixed active range", () => page.document.controller.snapshot.configuration.time.recorded.mode === "fixed"); await settled();
      await until("three original in-window samples", async () => await stat("count") === "3");
      assert.ok(Math.abs(Number(await stat("mean")) - (-8 / 3)) < 1e-12, "Fixed recording statistics mean must equal -8/3");
      assert.ok(Math.abs(Number(await stat("std_dev")) - Math.sqrt(64 / 3)) < 1e-12, "Fixed recording sample deviation must equal sqrt(64/3)");
      await until("three physical rows in fixed range", async () => await read("document.querySelectorAll('.raw-records tr').length") === 4);
      mark("recording statistics use all original samples across both members: fixed window count 3, mean -8/3 and sample deviation sqrt(64/3)");
      await recordingFields(preview, driver, page.document.controller.snapshot.source_ref, root, mark);
      await recordingCharts(preview, driver, page.document.controller.snapshot.source_ref, root, mark, command);
      await recordingHistory(preview, driver, page.document.controller.snapshot.source_ref, root, mark, command);
      await preview.open(page.document.controller.snapshot);
      // The history fixture advanced the clock to 120 s. This change-only signal has no
      // finite samples in the last 30 s until the append below; the binding stays unchanged.
      const quietConfiguration = addSignals(addPanel({
        ...(facts.blank_configuration as RecordedConfiguration),
        time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } },
      } as RecordedConfiguration, "quiet-statistics", "statistics"), "quiet-statistics",
        [{ name: "NT:/signal/00000", type: "int64", sample_count: 9 }], randomUUID);
      const quiet = await preview.backend.create(page.document.controller.snapshot.source_ref, quietConfiguration);
      await preview.open(quiet, true);
      const quietPage: any = (preview as any).pages.get(quiet.view_id);
      const quietRead = (expression: string) => driver.evaluate(`document.body?.dataset.viewId === ${JSON.stringify(quiet.view_id)}`, expression);
      await until("quiet window is explained", async () => (await quietRead("document.body.textContent"))?.includes("No finite samples in this scope"));
      const quietRevision = quietPage.document.controller.snapshot.revision;
      await command("append", "61", 119000000);
      await until("quiet window refreshes on new data", async () => await quietRead("(() => { const rows = [...document.querySelectorAll('.recorded-statistics tr')]; return rows.find(r => r.cells[0].textContent === 'mean')?.cells[1].textContent; })()") === "61");
      assert.equal(await quietRead("(() => { const rows = [...document.querySelectorAll('.recorded-statistics tr')]; return rows.find(r => r.cells[0].textContent === 'count')?.cells[1].textContent; })()"), "1");
      assert.equal(quietPage.document.controller.snapshot.revision, quietRevision);
      quietPage.panel.dispose(); await preview.open(page.document.controller.snapshot);
      mark("empty live statistics refresh to count 1 and mean 61 on the next prefix without Retry or a configuration change");
      // This view's recorded policy is full from birth, while its admitted live window is 30 s.
      // Keep both editors visible through finalization: a reveal would rebootstrap on its own.
      const historyConfig = addSignals(addPanel({ ...page.document.controller.snapshot.configuration,
        requirements: [], panels: [], layout: { columns: 12, items: [] },
        time: { recorded: { mode: "full" }, live: { mode: "follow", window_sec: 30 } },
      } as RecordedConfiguration, "finalized-history", "raw"), "finalized-history",
        [{ name: "NT:/signal/00005", type: "double[]", field: "[0]", sample_count: 6 }], randomUUID);
      const history = await preview.backend.create(page.document.controller.snapshot.source_ref, historyConfig);
      await preview.open(history, true);
      const historySelector = `document.body?.dataset.viewId === ${JSON.stringify(history.view_id)}`;
      const historyRead = (code: string) => driver.evaluate(historySelector, code);
      await until("default live window before finalization", async () => await historyRead("document.querySelectorAll('.raw-records tr').length") === 2);
      const historyPage: any = (preview as any).pages.get(history.view_id);
      assert.equal(historyPage.document.controller.snapshot.configuration.time.recorded.mode, "full");
      assert.ok(page.panel.visible && historyPage.panel.visible);
      await command("finalize"); await until("finalized display", async () => (await read("document.getElementById('live-status').textContent"))?.includes("ended"));
      await until("finalized lease released", () => (preview as any).broker.resources.reservedBytes === 0);
      await until("finalized frame rendered", async () => (await read("document.getElementById('clock').textContent"))?.includes("Finalized"));
      await until("fixed analysis survives finalization", async () => await stat("count") === "3");
      await until("rolling peer finalized", async () => (await historyRead("document.getElementById('live-status').textContent"))?.includes("ended"));
      // The ended state can arrive before the final frame enables this control.
      await until("rolling peer full history control ready", async () => await historyRead("(() => { const e = document.getElementById('full'); if (!e || e.disabled) return false; e.click(); return true; })()") === true);
      await until("unchanged full policy reloads earlier physical records", async () => await historyRead("document.querySelectorAll('.raw-records tr').length") === 7);
      const historyTimes: number[] = await historyRead("Array.from(document.querySelectorAll('.raw-records tr')).slice(1).map(row => Number(row.cells[2].textContent))");
      assert.deepEqual(historyTimes.sort((a, b) => a - b), [1000000, 1000000, 1250000, 1500000, 2000000, 120000000]);
      mark("Full recording re-admits all six physical samples, including duplicate times, after a default 30-second follow finalizes with an unchanged full policy");
      historyPage.panel.dispose(); await preview.open(page.document.controller.snapshot);
      await click("full"); await until("full range restored", () => page.document.controller.snapshot.configuration.time.recorded.mode === "full"); await settled();
      await until("ten full records", async () => await read("document.querySelectorAll('.raw-records tr').length") === 11);
      await until("ten full statistical samples", async () => await stat("count") === "10");
      mark("fixed range survives finalization; Full recording restores ten physical records and original statistics");
      const beforeProcess = structuredClone(page.document.controller.snapshot), processKey = page.key;
      const processes = await command("processRestart"); assert.notEqual(processes.pid, processes.previous_pid);
      await until("fresh JVM restores an already finalized working view", () => !page.recovering && page.key !== processKey, 30000);
      await until("exact restored history after process restart", async () => await read("document.querySelectorAll('.raw-records tr').length") === 11);
      await until("restored statistics after process restart", async () => await stat("count") === "10");
      assert.deepEqual(page.document.controller.snapshot, beforeProcess);
      mark("a new Java process restores the same finalized working view, configuration and ten exact raw/statistical records without recreating it");
      await read("(document.getElementById('recording-picker').open = false, document.getElementById('grid').scrollIntoView(), true)");
      await pause(200); fs.writeFileSync(path.join(root, "initial-workbench.png"), await driver.screenshot());
      await read("(document.querySelector('.recorded-statistics').scrollIntoView({ block: 'center' }), true)");
      await pause(100); fs.writeFileSync(path.join(root, "analysis-workbench.png"), await driver.screenshot());
      mark("writer finalization drains the exact recording and releases its follow lease");
      page.panel.dispose(); for (const overview of (preview as any).overviews.values()) overview.panel.dispose();
      await until("all budgets released", () => preview.budgets.encoded.reserved === 0 && preview.budgets.decoded.reserved === 0);
      assert.deepEqual(driver.errors, []); mark("close releases inventory, broker and display accounting with no renderer/CSP errors");
      fs.writeFileSync(path.join(root, "initial-result.json"), JSON.stringify({ version: vscode.version, checks, calls, routes }, null, 2));
    } catch (error) {
      const owners = [...(preview as any).pages.values()].map((p: any) => ({ id: p.document.controller.snapshot.view_id,
        visible: p.panel.visible, closed: p.closed, live_visible: p.live?.visible, live_paused: p.live?.paused,
        analysis_visible: p.analysis?.visible, analysis_paused: p.analysis?.paused, analysis_closed: p.analysis?.closed,
        analysis_running: p.analysis?.running, analysis_dirty: [...(p.analysis?.dirty ?? [])], analysis_blocked: [...(p.analysis?.blocked ?? [])],
        analysis_timer: !!p.analysis?.timer, analysis_active: p.analysis?.active?.kind, analysis_aborted: p.analysis?.active?.abort.signal.aborted,
        buffer_flight: p.analysis?.buffers.flight?.id, buffer_queued: p.analysis?.buffers.queued, buffer_stopped: p.analysis?.buffers.stopped }));
      fs.writeFileSync(path.join(root, "initial-diagnostics.json"), JSON.stringify({ errors: driver.errors, calls, routes, owners,
        queue_running: (preview as any).analysisQueue.running, queue_waiting: (preview as any).analysisQueue.waiting.length,
        body: await read("document.body.innerText") }, null, 2)); throw error;
    } finally { clearInterval(traceTimer); fs.writeFileSync(path.join(root, "analysis-trace.json"), JSON.stringify(trace, null, 2)); driver.close(); preview.dispose(); }
  })().catch(error => fs.writeFileSync(path.join(root, "initial-result.json"), JSON.stringify({ error: String(error), stack: error.stack, checks })))
    .finally(async () => { await pause(250); await vscode.commands.executeCommand("workbench.action.quit"); });
}
