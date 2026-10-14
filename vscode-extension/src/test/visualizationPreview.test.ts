import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
const root = path.resolve(__dirname, "../..");
test("recorded source overview is an explicit optional editor and leaves the default intact", () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  const overview = manifest.contributes.customEditors.find((e: any) => e.viewType === "wpilog-mcp.sourceOverview");
  assert.equal(overview?.priority, "option");
  assert.equal(manifest.contributes.customEditors.find((e: any) => e.viewType === "wpilog-mcp.explorer")?.priority, "default");
  assert.ok(manifest.contributes.commands.some((c: any) => c.command === "wpilog-mcp.explorer.openSourceOverview"));
});
test("recorded view documents have their own file association, not the source URI", () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  const editor = manifest.contributes.customEditors.find((e: any) => e.viewType === "wpilog-mcp.visualization");
  assert.deepEqual(editor?.selector, [{ filenamePattern: "*.wpiview" }]);
});
