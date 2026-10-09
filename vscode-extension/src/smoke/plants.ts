/** CI-only mutations of disposable compiled output; sources and the package are never changed. */
import { spawnSync } from "node:child_process";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";

if (process.platform !== "linux" || !process.env.CI) throw new Error("Editor plants run only in Linux CI under Xvfb");
const extension = path.resolve(__dirname, "..", ".."), root = path.dirname(extension);
const version = process.env.VSCODE_VERSION ?? "stable";
const reports = path.join(root, "build", "extension-smoke", version);
const plants = [
  { name: "activation", file: "extension.js", before: "function activate(context) {",
    after: 'function activate(context) { throw new Error("Planted activation failure");', checks: 0, reason: /activat/i },
  { name: "server", file: "serverManager.js", before: "ensure(spec) {", after: "ensure(spec) { return Promise.resolve(undefined);",
    checks: 1, reason: /starts the shared daemon/ },
  { name: "listing", file: "explorer.js", before: "async getChildren(element) {", after: "async getChildren(element) { return [];",
    checks: 2, reason: /actual Logs provider lists/ },
  { name: "editor", file: "explorer.js", before: 'vscode.commands.executeCommand("vscode.openWith",',
    after: 'Promise.resolve("vscode.openWith",', checks: 3, reason: /custom editor/ },
  { name: "data-append", file: "../media/dataView.js", before: "if (rows.length) await this.table.update(rows);",
    after: "if (rows.length) await this.table.replace(rows);", checks: 4, reason: /1 !== 3/ },
  { name: "pit-secret", file: "claudeRegistration.js", before: '"connect", "--url", url];',
    after: '"connect", "--url", url, "password=synthetic-smoke-secret"];', checks: 5, reason: /only a user-scope URL bridge/ },
];
const results: object[] = [];
for (const plant of plants) {
  const file = path.join(extension, "out", plant.file), original = fs.readFileSync(file, "utf8");
  assert.ok(original.includes(plant.before), `plant seam: ${plant.name}`);
  const label = `plant-${plant.name}`, directory = path.join(reports, label);
  fs.mkdirSync(directory, { recursive: true });
  const previous = new Set(fs.readdirSync(directory));
  const log = fs.openSync(path.join(directory, "run.log"), "w");
  try {
    fs.writeFileSync(file, original.replace(plant.before, plant.after));
    const child = spawnSync(process.execPath, [path.join(__dirname, "run.js")], { cwd: extension,
      env: { ...process.env, WPILOG_SMOKE_LABEL: label }, stdio: ["ignore", log, log], timeout: 120_000 });
    assert.equal(child.error, undefined, `${plant.name}: a timeout is not a caught fault`);
    assert.equal(child.status, 1, `${plant.name}: the planted production fault must fail`);
    const runs = fs.readdirSync(directory).filter(name => name.startsWith("run-") && !previous.has(name));
    assert.equal(runs.length, 1);
    const result = JSON.parse(fs.readFileSync(path.join(directory, runs[0], "result.json"), "utf8"));
    assert.equal(result.status, "failed");
    assert.equal(result.checks.length, plant.checks, `${plant.name}: earlier independent checks must pass`);
    assert.match(result.error, plant.reason, `${plant.name}: fail for the intended assertion`);
    results.push({ plant: plant.name, caught: true, passed_before: plant.checks });
    console.log(`CAUGHT: ${plant.name}`);
  } finally {
    fs.writeFileSync(file, original);
    fs.closeSync(log);
    fs.writeFileSync(path.join(reports, "plants.json"), JSON.stringify(results, null, 2) + "\n");
  }
}
