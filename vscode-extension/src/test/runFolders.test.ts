import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { runFolder } from "../smoke/runFolders";

function scratch(run: (base: string) => void): void {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-run-folders-"));
  try { run(base); } finally { fs.rmSync(base, { recursive: true, force: true }); }
}

test("a runner's new run replaces its earlier runs and nothing else beside them", () => scratch(base => {
  const first = runFolder(base, "run-");
  fs.writeFileSync(path.join(first, "result.json"), "{}");
  fs.writeFileSync(path.join(base, "run.log"), "a plant's log");
  fs.mkdirSync(path.join(base, "fixture"));
  const second = runFolder(base, "run-");
  assert.notEqual(second, first);
  assert.deepEqual(fs.readdirSync(base).sort(), [path.basename(second), "fixture", "run.log"].sort());
}));

test("a folder the caller names keeps every earlier run", () => scratch(base => {
  const first = runFolder(base, "run-", { keepEarlier: true });
  const second = runFolder(base, "run-", { keepEarlier: true });
  assert.deepEqual(fs.readdirSync(base).sort(), [path.basename(first), path.basename(second)].sort());
}));

test("replacing a run never deletes through a link out of it", () => scratch(base => {
  const outside = path.join(base, "logs"); fs.mkdirSync(outside);
  fs.writeFileSync(path.join(outside, "match.wpilog"), "a robot log");
  const runs = path.join(base, "runs");
  const first = runFolder(runs, "run-");
  try {
    fs.symlinkSync(outside, path.join(first, "logs"), process.platform === "win32" ? "junction" : "dir");
  } catch {
    return; // No link support here; the other platforms cover it.
  }
  runFolder(runs, "run-");
  assert.equal(fs.existsSync(first), false);
  assert.equal(fs.readFileSync(path.join(outside, "match.wpilog"), "utf8"), "a robot log");
}));

test("every native runner makes its run folder through runFolder", () => {
  const smoke = path.join(__dirname, "..", "..", "src", "smoke");
  for (const name of fs.readdirSync(smoke).filter(file => file.endsWith(".ts") && file !== "runFolders.ts")) {
    const source = fs.readFileSync(path.join(smoke, name), "utf8");
    assert.ok(!source.includes("mkdtempSync("), `${name} makes a run folder without runFolder, so nothing removes its earlier runs`);
  }
});
