import { test } from "node:test";
import * as assert from "node:assert/strict";
import { promises as fs } from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import { randomUUID } from "node:crypto";
import { RecoveryJournal } from "../visualization/recoveryJournal";

test("journal resumes exact draft and refuses quota exhaustion without losing old records", async () => {
  const scratch = await fs.mkdtemp(path.join(os.tmpdir(), "visualization-journal-"));
  const directory = path.join(scratch, "journal");
  try {
    const journal = new RecoveryJournal(directory, 1024, 1), id = randomUUID();
    const value = { view_id: id, base_revision: 3, configuration: { links: { cursor: false } } };
    await journal.write(value); assert.deepEqual(await new RecoveryJournal(directory).read(id), value);
    await assert.rejects(journal.write({ ...value, view_id: randomUUID() }), /full/);
    await assert.rejects(journal.write({ ...value, configuration: { extra: "x".repeat(1024) } }), /full/);
    assert.deepEqual(await journal.read(id), value);
    await assert.rejects(journal.write({ ...value, view_id: "../escape" }), /identity/);
    await journal.remove(id); assert.equal(await journal.read(id), undefined);
  } finally { await fs.rm(scratch, { recursive: true, force: true }); }
});
