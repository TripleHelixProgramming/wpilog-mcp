/** Native inline field selection against the real multi-member compatibility/follow owners. */
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { RecordedPreview } from "../visualization/recordedPreview";
import { RecordedDevtools } from "./recordedDevtools";
const pause = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
async function until(label: string, check: () => boolean | Promise<boolean>): Promise<void> {
  const end = Date.now() + 15000;
  while (Date.now() < end) { if (await check()) return; await pause(40); }
  throw new Error("Timed out: " + label);
}
export async function recordingFields(preview: RecordedPreview, driver: RecordedDevtools, source: any, root: string, mark: (check: string) => void): Promise<void> {
  const view = await preview.backend.create(source); await preview.open(view, true);
  const selector = `document.body?.dataset.viewId === ${JSON.stringify(view.view_id)}`;
  const read = (code: string) => driver.evaluate(selector, code);
  const click = async (id: string) => until("field control " + id, async () => await read(`(() => { const e = document.getElementById(${JSON.stringify(id)}); if (!e || e.disabled) return false; e.click(); return true; })()`) === true);
  let page: any;
  try {
    await until("field view ready", () => !!(page = (preview as any).pages.get(view.view_id))?.ready);
    const settled = () => until("field gesture painted", async () => !page.document.controller.pending && await read("document.body.dataset.revision") === String(page.document.controller.snapshot.revision) && await read("document.body.dataset.generation") === page.key);
    const addPanel = async (button: string) => {
      const count = page.document.controller.snapshot.configuration.panels.length;
      await click(button); await until("field panel persisted", () => page.document.controller.snapshot.configuration.panels.length === count + 1); await settled();
      await read("(() => { const e = document.getElementById('target'); e.value = e.options[e.options.length - 1].value; e.dispatchEvent(new Event('change')); return true; })()");
    };
    const inspect = async (n: number) => {
      const name = `NT:/signal/${String(n).padStart(5, "0")}`;
      const generation = page.recordingInventory.snapshot.generation;
      await read(`(document.getElementById('recording-picker').open = true, document.getElementById('inventory-search').value = ${JSON.stringify(name)}, true)`);
      await click("inventory-find");
      // The previous filter can also have one row. Wait for both the new host result and
      // its rendered row; postMessage delivery does not finish when the host state changes.
      await until("requested field row painted", async () => !page.recordingInventory.snapshot.loading
        && page.recordingInventory.snapshot.generation > generation && !page.delivery.flight && !page.delivery.pending
        && page.recordingInventory.snapshot.page?.entries[0]?.name === name
        && await read(`(() => { const row = document.querySelector('#inventory-rows tr');
          if (row?.cells[0]?.textContent !== ${JSON.stringify(name)}) return false;
          const button = row.querySelector('button'); if (!button || button.disabled) return false;
          button.click(); return true; })()`) === true);
      await until("fresh field row", () => !page.recordingInventory.snapshot.loading && !!page.recordingInventory.snapshot.details && page.recordingInventory.snapshot.selected === name);
    };
    const choose = async (field: string, reject?: RegExp) => {
      await settled(); const revision = page.document.controller.snapshot.revision;
      await read(`(() => { const e = document.getElementById('inventory-field'); e.value = ${JSON.stringify(field)}; e.dispatchEvent(new Event('input')); return true; })()`);
      await click("inventory-field-add");
      if (reject) {
        await until("field refusal " + field, async () => reject.test(await read("document.getElementById('inventory-field-status').textContent")));
        assert.equal(page.document.controller.snapshot.revision, revision, "refused field must not mutate the working view");
      } else { await until("field revision saved", () => page.document.controller.snapshot.revision > revision); await settled(); }
    };
    await addPanel("values"); await inspect(6); await choose(".translation.x");
    const value = (entry: string) => read(`(() => { const rows = [...document.querySelectorAll('.recorded-values tr')]; return rows.find(r => r.cells[0].textContent === ${JSON.stringify(entry)})?.cells[1].textContent; })()`);
    await until("nested struct numeric value", async () => await value("NT:/signal/00006.translation.x") === "14");
    const binding = page.live.binding;
    assert.ok(binding.queries.some((q: any) => q.entry === "NT:/signal/00006" && q.field === ".translation.x"), "follow must carry the exact selected nested field");
    assert.equal(binding.compatibility.bindings[0].members.filter((m: any) => m.state === "resolved").length, 2);
    mark("inline nested struct selection is checked across two members, persisted and followed with exact latest value 14");
    await choose(".translation.*", /Field was not added/);
    await choose("[", /Unclosed/);
    await inspect(5); await choose("[9]", /Field was not added/); await choose("[*]", /scalar field|array index/); await choose("[1]");
    await until("numeric array value", async () => await value("NT:/signal/00005[1]") === "18");
    await inspect(11); await choose("[0]", /Field was not added/);
    await inspect(9); await choose(".x", /different definitions/);
    await inspect(12); await choose(".x", /no valid schema/);
    mark("literal array index returns 18; malformed, wildcard, missing, empty-array and conflicting or absent schema selections refuse without a revision");
    for (const button of ["plot", "statistics", "raw"]) { await addPanel(button); await inspect(6); await choose(".translation.x"); }
    await until("field plot rendered", () => (preview.rendered.get(view.view_id)?.plots ?? 0) > 0);
    const stat = (key: string) => read(`(() => { const rows = [...document.querySelectorAll('.recorded-statistics tr')]; return rows.find(r => r.cells[0].textContent === ${JSON.stringify(key)})?.cells[1].textContent; })()`);
    await until("three original projected samples", async () => await stat("count") === "3");
    assert.equal(Number(await stat("mean")), 8, "Nested field mean must equal (2 + 8 + 14) / 3");
    assert.equal(Number(await stat("std_dev")), 6, "Nested field sample deviation must equal 6");
    await until("three projected raw records", async () => await read("document.querySelectorAll('.raw-records tr').length") === 4);
    assert.ok((await read("document.querySelector('.raw-records').textContent")).includes("14"));
    const saved = await preview.backend.read(view.view_id), configuration: any = saved.configuration;
    assert.equal(configuration.requirements.length, 2, "repeated field is shared across the four panels");
    assert.ok(configuration.requirements.every((r: any) => r.value_type === "number"));
    assert.deepEqual(configuration.requirements.map((r: any) => r.field_path), [".translation.x", "[1]"]);
    mark("the same persisted field drives plot, latest, raw and server statistics: original values 2, 8, 14 give count 3, mean 8 and sample deviation 6");
    await read("(document.getElementById('inventory-field').scrollIntoView({ block: 'center' }), true)");
    await pause(150); fs.writeFileSync(path.join(root, "field-picker.png"), await driver.screenshot());
    await read("(document.getElementById('recording-picker').open = false, document.querySelector('.recorded-statistics').scrollIntoView({ block: 'center' }), true)");
    await pause(150); fs.writeFileSync(path.join(root, "field-workbench.png"), await driver.screenshot());
  } finally { page?.panel.dispose(); }
}
