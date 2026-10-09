import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { checkComplete, prepareFixture } from "../smoke/evidence";

test("the runner accepts all six editor checks and refuses an incomplete or failed result", () => {
  const checks = ["activation", "server", "listing", "editor", "data append", "pit command"];
  assert.doesNotThrow(() => checkComplete({ status: "ok", checks }));
  assert.throws(() => checkComplete({ status: "ok", checks: checks.slice(0, 5) }));
  assert.throws(() => checkComplete({ status: "failed", checks }));
});

test("appending during a smoke run cannot change the baseline of the next plant", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-smoke-isolation-"));
  try {
    const source = path.join(root, "source", "smoke.wpilog"); fs.mkdirSync(path.dirname(source));
    fs.writeFileSync(source, Buffer.from([1, 2])); fs.writeFileSync(path.join(path.dirname(source), "append.bin"), Buffer.from([3]));
    const first = prepareFixture(source, path.join(root, "first"));
    fs.appendFileSync(first, Buffer.from([3]));
    assert.deepEqual(fs.readFileSync(source), Buffer.from([1, 2]), "the shared input is immutable");
    const second = prepareFixture(source, path.join(root, "second"));
    assert.notEqual(first, second);
    assert.deepEqual(fs.readFileSync(second), Buffer.from([1, 2]));
    assert.deepEqual(fs.readFileSync(path.join(path.dirname(second), "append.bin")), Buffer.from([3]));
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
