import { test } from "node:test";
import * as assert from "node:assert/strict";
import { EntryInfo } from "../explorer/entryInfo";

test("an inactive editor gets its own server and log, including identical paths on two servers", async () => {
  const info = new EntryInfo<string>(async (spec, path, name) => ({ spec, path, name }));
  const a = { spec: "laptop", path: "/capture.wpilog", listing: {} };
  const b = { spec: "pit", path: "/capture.wpilog", listing: {} };
  await info.read(a, "/x");
  assert.deepEqual(await info.read(b, "/x"), { spec: "pit", path: b.path, name: "/x" });
  assert.deepEqual(await info.read(a, "/x"), { spec: "laptop", path: a.path, name: "/x" });
});
test("a refreshed live listing invalidates descriptions even with unchanged path and server", async () => {
  let metadata = "announced";
  const info = new EntryInfo<string>(async () => ({ metadata }));
  const log = { spec: "pit", path: "/capture.wpilog", listing: {} };
  assert.equal((await info.read(log, "/x")).metadata, "announced");
  metadata = "changed"; log.listing = {};
  assert.equal((await info.read(log, "/x")).metadata, "changed");
});
