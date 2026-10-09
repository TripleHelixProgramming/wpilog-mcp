import { test } from "node:test";
import * as assert from "node:assert/strict";
import { parseExplorerUri, openValidated, selectionPrompt } from "../explorer/selection";
const link = "vscode://TripleHelixProgramming.wpilog-analyzer/open?path=%2Flogs%2Fa%20b.wpilog&entries=%2FA+B&entries=%2FC%22D&start=-2&end=4.5&kind=scatter&ignored=anything";
test("explorer links decode repeated entries and the window without executing unknown parameters", () => {
  assert.deepEqual(parseExplorerUri(link), { path: "/logs/a b.wpilog", entries: ["/A B", '/C"D'], start: -2, end: 4.5, kind: "scatter" });
  for (const bad of ["NaN", "Infinity", "", "2x"]) assert.throws(() => parseExplorerUri(link.replace("start=-2", `start=${bad}`)), /Invalid start/);
  assert.throws(() => parseExplorerUri(link.replace("end=4.5", "end=-3")), /after/);
});
test("an outside path is never opened and retains the server's refusal", async () => {
  let opened = false, checked = "";
  await assert.rejects(openValidated(parseExplorerUri(link), async path => { checked = path; throw new Error("Access denied: outside the configured log directories"); }, async () => { opened = true; }), /Access denied: outside the configured log directories/);
  assert.equal(checked, "/logs/a b.wpilog"); assert.equal(opened, false);
});
test("selection prompts name the evidence and choose the first tool by the actual pane", () => {
  const selection = parseExplorerUri(link);
  for (const [kind, selected, tool] of [["time_series", true, "get_statistics"], ["console", true, "search_strings"], ["field", false, "render_chart"]] as const) {
    const prompt = selectionPrompt({ ...selection, kind, selected });
    assert.ok(prompt.includes(`First call ${tool}`)); assert.ok(prompt.includes(JSON.stringify(selection.path)));
    assert.ok(prompt.includes(JSON.stringify(selection.entries[1]))); assert.ok(prompt.includes("-2 to 4.5 seconds"));
  }
});
