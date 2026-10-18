/** Real editor and Java inventory owner, including the real five-minute absolute expiry. */
import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import * as assert from "node:assert/strict";
import { McpClient } from "../mcpClient";
import { RecordedPreview } from "../visualization/recordedPreview";
import { RecordedDevtools } from "./recordedDevtools";
import { InventoryOverview } from "../visualization/inventoryOverview";
const pause = (ms: number) => new Promise<void>(r => setTimeout(r, ms));
async function until(label: string, check: () => boolean | Promise<boolean>, ms = 15000): Promise<void> {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await check()) return; await pause(50); }
  throw new Error("Timed out: " + label);
}
export function activate(context: vscode.ExtensionContext): void {
  const root = process.env.WPILOG_RECORDED_FIXTURE!;
  if (!root || !fs.existsSync(path.join(root, "fixture-only"))) throw new Error("Isolated fixture required");
  const client = new McpClient(process.env.WPILOG_RECORDED_ENDPOINT!, "overview-fixture", async () => ({
    directories: { paths: [{ path: path.join(root, "logs"), team: null }], team: null }, key: null }));
  const expiryTimers = new Map<ReturnType<typeof setTimeout>, () => void>();
  const preview = new RecordedPreview({ context, client: async () => client, entries() {}, grantStore: async () => {}, overviewClock: {
    now: Date.now, later(fn, ms) { const timer = setTimeout(() => { expiryTimers.delete(timer); fn(); }, ms); expiryTimers.set(timer, fn); return timer; },
    cancel(timer) { clearTimeout(timer as ReturnType<typeof setTimeout>); expiryTimers.delete(timer as ReturnType<typeof setTimeout>); },
  } });
  context.subscriptions.push(preview, { dispose() { void client.dispose(); } });
  const calls: { route: string; body: any; bytes?: number }[] = [], original = client.visualization.bind(client);
  let holdQuery: string | undefined, withheld: (() => void) | undefined;
  client.visualization = (async (route: string, body?: any, signal?: AbortSignal, options?: any) => {
    const record = { route, body, bytes: undefined as number | undefined }; calls.push(record);
    const result = await original(route, body, signal, options); record.bytes = Buffer.byteLength(JSON.stringify(result));
    if (route === "/source/entries/list" && holdQuery !== undefined && body.query === holdQuery) await new Promise<void>(resolve => withheld = resolve);
    return result;
  }) as typeof client.visualization;
  const checks: string[] = [];
  const mark = (check: string) => { checks.push(check); fs.writeFileSync(path.join(root, "progress.json"), JSON.stringify({ at: new Date().toISOString(), checks, calls }, null, 2)); };
  void (async () => {
    const driver = await RecordedDevtools.open(Number(process.env.WPILOG_RECORDED_DEBUG_PORT));
    const uri = vscode.Uri.file(path.join(root, "logs/recording-store/robots/synthetic/sessions/2026-01-01/session/capture.wpilog"));
    const selector = "document.body?.dataset.overview === 'true'";
    const read = (expression: string) => driver.evaluate(selector, expression);
    const click = async (id: string) => { await until("enabled control " + id, async () => await read(`(() => {
      const control = document.getElementById(${JSON.stringify(id)}); if (!control || control.disabled) return false;
      control.click(); return true;
    })()`) === true); };
    const filter = async (query: string, type = "") => {
      await read(`(document.getElementById('inventory-search').value=${JSON.stringify(query)}, document.getElementById('inventory-type').value=${JSON.stringify(type)}, true)`);
      await driver.enter(selector, "#inventory-find");
    };
    try {
      // Recreated renderers send ready while a previous catalog identification can still return.
      // Hold real catalog replies, including their cancellation race, across native hide/reveal.
      const identify = preview.backend.identify.bind(preview.backend), catalogReplies: (() => void)[] = [], owners: InventoryOverview[] = [];
      preview.backend.identify = async (...args) => { const summary = await identify(...args); return new Promise(resolve => catalogReplies.push(() => resolve(summary))); };
      try {
        await preview.openOverview(uri); await until("initial catalog withheld", () => catalogReplies.length === 1);
        const opening = (preview as any).overviews.get(uri.toString()); let current: InventoryOverview | undefined;
        Object.defineProperty(opening, "inventory", { get: () => current, set: (owner: InventoryOverview) => { current = owner; owners.push(owner); } });
        const hidden = await vscode.workspace.openTextDocument({ content: "Initialization hide/reveal regression" });
        await vscode.window.showTextDocument(hidden, { viewColumn: opening.panel.viewColumn, preview: false });
        await until("initialization hidden", () => !opening.panel.visible);
        await preview.openOverview(uri); await until("replacement catalog withheld", () => catalogReplies.length === 2);
        catalogReplies[0](); await pause(150); catalogReplies[1]();
        await until("one initialized page", () => !!opening.inventory?.snapshot.page && !opening.inventory.snapshot.loading);
        assert.equal(owners.length, 1, "hide/reveal during catalog identification must admit exactly one owner");
        const id = opening.inventory.snapshot.summary.inventory_id, scheduled = [...expiryTimers.values()];
        opening.panel.dispose();
        await until("initialization owner closed", () => calls.some(c => c.route === "/source/inventory/close" && c.body.inventory_id === id));
        await until("initialization memory released", () => preview.budgets.encoded.reserved === 0 && preview.budgets.decoded.reserved === 0);
        assert.equal(expiryTimers.size, 0);
        const opened = calls.filter(c => c.route === "/source/describe").length;
        scheduled.forEach(fn => fn()); await pause(200);
        assert.equal(calls.filter(c => c.route === "/source/describe").length, opened, "closed owners cannot reopen even if a cancelled timer fires");
        mark("native hide/reveal during catalog identification admits one owner; close releases bytes and prevents later opens");
      } finally { preview.backend.identify = identify; catalogReplies.forEach(finish => finish()); for (const owner of owners) await owner.dispose(); }
      const discoveryStart = calls.length;
      const summary = await preview.backend.identify(uri.fsPath); assert.equal(summary.source_ref.kind, "session");
      await assert.rejects(preview.backend.describe(summary.source_ref), /RESOURCE_LIMIT/, "20,000 names exceed the former 4 MiB inventory");
      await preview.openOverview(uri);
      await until("bounded overview", () => !!(preview as any).overviews.get(uri.toString())?.inventory?.snapshot.page);
      const host = (preview as any).overviews.get(uri.toString()), view: InventoryOverview = host.inventory;
      const ready = () => until("overview settled", () => !view.snapshot.loading);
      await ready();
      await until("20,000 shown", async () => (await read("document.getElementById('inventory-count').textContent"))?.includes("20,000"));
      assert.equal(view.snapshot.summary?.total_entry_names, 20000); assert.equal(view.snapshot.summary?.members.length, 2);
      assert.equal(view.snapshot.page?.entries.length, 256); assert.equal(view.snapshot.page?.entries[0].name, "NT:/signal/00000");
      assert.equal(view.snapshot.page?.entries[0].sample_count, "3");
      assert.equal(await read("document.querySelectorAll('#inventory-rows tr').length"), 256);
      assert.equal(await read("document.getElementById('blank').disabled"), false);
      assert.equal(await read("document.getElementById('template-open').disabled"), false);
      assert.equal(calls.slice(discoveryStart).filter(c => c.route === "/source/entries/list").length, 1, "no background enumeration");
      mark("20,000 names across two members open as one 256-row page beyond the legacy cap; recording panel actions are available");
      await driver.enter(selector, "#inventory-next"); await until("next page", () => view.snapshot.pageNumber === 2 && !view.snapshot.loading);
      assert.equal(view.snapshot.page?.entries[0].name, "NT:/signal/00256");
      await driver.enter(selector, "#inventory-previous"); await until("previous page", () => view.snapshot.pageNumber === 1 && !view.snapshot.loading);
      assert.ok(view.retainedPages <= 2); mark("keyboard Previous/Next replace the row window with exact expected names");
      await filter("NT:/signal/1999", "int64"); await until("exact filter", () => !view.snapshot.loading && view.snapshot.page?.total_matching === 10);
      assert.deepEqual(view.snapshot.page?.entries.map(r => r.name), Array.from({ length: 10 }, (_, n) => `NT:/signal/1999${n}`));
      await filter("NT:/signal/1999", "double"); await until("no match", () => !view.snapshot.loading && view.snapshot.page?.total_matching === 0);
      assert.match(await read("document.getElementById('inventory-count').textContent"), /No matches/);
      mark("literal search and exact type filtering report true totals and a no-match remedy");
      await click("inventory-unavailable");
      assert.equal(await read("document.getElementById('inventory-search').disabled"), true);
      assert.equal(await read("document.getElementById('inventory-type').disabled"), true);
      await click("inventory-find"); await until("unavailable traversal", () => view.snapshot.filter.unavailable && !view.snapshot.loading);
      assert.equal(view.snapshot.reason, undefined); assert.equal(view.snapshot.page?.total_matching, 0, "this synthetic source has no unavailable declarations");
      await until("unavailable mode rendered", async () => (await read("document.getElementById('inventory-count').textContent"))?.includes("Unavailable declarations only"));
      const unavailableRequest = calls.filter(c => c.route === "/source/entries/list").at(-1)!.body;
      assert.equal(unavailableRequest.unavailable, true); assert.equal(unavailableRequest.query, undefined); assert.equal(unavailableRequest.types, undefined);
      await click("inventory-unavailable"); assert.equal(await read("document.getElementById('inventory-search').disabled"), false);
      assert.equal(await read("document.getElementById('inventory-search').value"), "NT:/signal/1999");
      assert.equal(await read("document.getElementById('inventory-type').value"), "double");
      await click("inventory-find"); await until("ordinary traversal restored", () => !view.snapshot.filter.unavailable && !view.snapshot.loading);
      assert.equal(view.snapshot.reason, undefined); assert.equal(view.snapshot.filter.query, "NT:/signal/1999"); assert.deepEqual(view.snapshot.filter.types, ["double"]);
      mark("unavailable mode disables and omits name/type filters, labels the exclusive count, then restores ordinary filters");
      await filter("NT:/signal/00000", "int64"); await until("single row", () => !view.snapshot.loading && view.snapshot.page?.total_matching === 1);
      await read("(document.querySelector('#inventory-rows button').click(), true)"); await until("fresh selection", () => !!view.snapshot.details && !view.snapshot.loading);
      assert.equal(view.snapshot.details?.descriptors.length, 2);
      await until("fresh details rendered", async () => (await read("document.getElementById('inventory-detail').textContent"))?.includes("Fresh member details"));
      const inventoryId = view.snapshot.summary!.inventory_id;
      const args = { api_version: 1, inventory_id: inventoryId, query: "NT:/signal/00000", types: ["int64"], unavailable: false, limit: 256 };
      const mcp: any = await client.callTool("visualization_source_entries_list", args);
      assert.deepEqual(mcp.page.entries, view.snapshot.page!.entries); assert.equal(mcp.page.total_matching, 1);
      mark("selected details are fresh for both members and MCP returns the same rows as the HTTP-backed overview");
      // Delay an actual server reply at the adapter boundary, then change the filter.
      holdQuery = "NT:/signal/1999"; await filter(holdQuery); await until("withheld real reply", () => !!withheld);
      await filter("NT:/signal/00000", "int64"); holdQuery = undefined; withheld!(); withheld = undefined;
      await until("replacement filter", () => !view.snapshot.loading && view.snapshot.page?.total_matching === 1);
      assert.equal(view.snapshot.page?.entries[0].name, "NT:/signal/00000"); mark("an obsolete real-server reply is rejected after a newer filter gesture");
      const extra: any = await client.visualization("/source/describe", { source_ref: summary.source_ref, inventory_mode: "paged" });
      await click("inventory-retry"); await until("capacity refusal", () => !view.snapshot.loading && !!view.snapshot.reason);
      assert.match(view.snapshot.reason!, /RESOURCE_LIMIT/); assert.equal(view.snapshot.summary?.inventory_id, inventoryId); assert.equal(view.snapshot.page?.total_matching, 1);
      await client.visualization("/source/inventory/close", { inventory_id: extra.inventory_id });
      await pause(Math.max(1, view.snapshot.retryAt - Date.now()) + 100); await click("inventory-retry");
      await until("successful retry", () => !view.snapshot.loading && view.snapshot.summary?.inventory_id !== inventoryId);
      assert.equal(view.snapshot.selected, "NT:/signal/00000"); assert.equal(view.snapshot.details?.count_basis, "fresh_admission");
      mark("real per-session capacity refusal preserves readable rows; Retry replaces the snapshot and revalidates selection");
      const beforeHide = view.snapshot.summary!.inventory_id;
      const text = await vscode.workspace.openTextDocument({ content: "Hidden inventory fixture" });
      await vscode.window.showTextDocument(text, { viewColumn: host.panel.viewColumn, preview: false });
      await until("hidden release", () => view.snapshot.hidden && calls.some(c => c.route === "/source/inventory/close" && c.body.inventory_id === beforeHide));
      await pause(250); const hiddenRequests = calls.length; await pause(500); assert.equal(calls.length, hiddenRequests, "hidden inventories have no heartbeat");
      assert.equal((preview as any).budgets.decoded.reserved, 0);
      await preview.openOverview(uri); await until("reveal opens", () => !view.snapshot.loading && !!view.snapshot.details && view.snapshot.summary?.inventory_id !== beforeHide);
      await until("filter controls restored", async () => await read("document.getElementById('inventory-search').value") === "NT:/signal/00000");
      assert.equal(await read("document.getElementById('inventory-type').value"), "int64");
      mark("hide closes the handle and releases rows with zero background requests; reveal opens fresh and restores exact selection");
      const opensBeforeExpiry = calls.filter(c => c.route === "/source/describe" && c.body?.inventory_mode === "paged").length;
      const beforeExpiry = view.snapshot.summary!.inventory_id, expires = Date.parse(view.snapshot.summary!.expires_at), started = Date.now();
      fs.writeFileSync(path.join(root, "expiry-wait.json"), JSON.stringify({ beforeExpiry, expires_at: new Date(expires).toISOString(), started_at: new Date(started).toISOString() }));
      await until("real absolute expiry refresh", () => !view.snapshot.loading && !!view.snapshot.summary && view.snapshot.summary.inventory_id !== beforeExpiry, Math.max(1000, expires - Date.now()) + 20000);
      assert.ok(Date.now() >= expires, "the real server's hard expiry passed");
      assert.equal(calls.filter(c => c.route === "/source/describe" && c.body?.inventory_mode === "paged").length, opensBeforeExpiry + 1, "exactly one automatic replacement");
      assert.equal(view.snapshot.selected, "NT:/signal/00000"); assert.equal(view.snapshot.filter.query, "NT:/signal/00000");
      assert.equal(view.snapshot.details?.count_basis, "fresh_admission");
      mark("real five-minute visible expiry opens exactly one replacement with filter and selection retained and details revalidated");
      fs.writeFileSync(path.join(root, "initial-workbench.png"), await driver.screenshot());
      const last = view.snapshot.summary!.inventory_id; host.panel.dispose();
      await until("closed handle", () => calls.some(c => c.route === "/source/inventory/close" && c.body.inventory_id === last));
      await until("all acquisition bytes released", () => (preview as any).budgets.encoded.reserved === 0);
      assert.equal((preview as any).budgets.decoded.reserved, 0); assert.deepEqual(driver.errors, []);
      assert.ok(calls.filter(c => c.route.startsWith("/source/entries/")).every(c => c.bytes === undefined || c.bytes <= 1 << 20));
      mark("close releases the handle and both budgets; bounded responses and no renderer/CSP errors");
      fs.writeFileSync(path.join(root, "initial-result.json"), JSON.stringify({ version: vscode.version, checks, expiryWaitMs: Date.now() - started, calls }, null, 2));
    } catch (error) {
      fs.writeFileSync(path.join(root, "initial-diagnostics.json"), JSON.stringify({ errors: driver.errors, calls, body: await read("document.body.innerText") }, null, 2)); throw error;
    } finally { driver.close(); }
  })().catch(error => fs.writeFileSync(path.join(root, "initial-result.json"), JSON.stringify({ error: String(error), stack: error.stack, checks })))
    .finally(async () => { await pause(250); await vscode.commands.executeCommand("workbench.action.quit"); });
}
