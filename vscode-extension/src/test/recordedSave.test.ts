/** The product Save view flow must not select, create or grant a log store. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
let cancel = false;
const host = { window: {
  showQuickPick: async (items: any[]) => cancel ? undefined : items.find(i => !i.store),
  showOpenDialog: async () => assert.fail("Save must not select a local folder"), showInputBox: async () => cancel ? undefined : "Fixture layout",
} };
const loader = require("node:module"), original = loader._load;
let RecordedPreview: typeof import("../visualization/recordedPreview").RecordedPreview;
try { loader._load = (name: string, ...args: any[]) => name === "vscode" ? host : original(name, ...args); RecordedPreview = require("../visualization/recordedPreview").RecordedPreview; }
finally { loader._load = original; }

test("first Save view selects only a library title; cancel and ephemeral refusal never create a store", async () => {
  const preview: any = Object.create(RecordedPreview.prototype); preview.registerWindow = async () => {}; preview.pages = new Map();
  let promoted = 0, available = true;
  preview.host = { grantStore: async () => assert.fail("Save must not grant a store"), client: async () => assert.fail("Save must not launch ingestion") };
  preview.backend = { destinations: new Map(), capabilities: async () => ({ library: { available } }), call: async () => assert.fail("No store discovery or creation") };
  preview.paint = () => {}; preview.overviews = new Map();
  const page = { document: { controller: { snapshot: { view_id: "view" }, promote: async () => { promoted++; } } }, panel: { title: "Draft" } };
  cancel = true; await preview.save(page); assert.equal(promoted, 0); assert.equal(preview.backend.destinations.size, 0);
  cancel = false; await preview.save(page);
  assert.equal(promoted, 1); assert.deepEqual(preview.backend.destinations.get("view"), { title: "Fixture layout" });
  available = false; await assert.rejects(preview.save(page), /durable visualization library/); assert.equal(promoted, 1);
});

test("ephemeral server identity warns that drafts cannot survive a restart", async () => {
  const preview: any = Object.create(RecordedPreview.prototype); preview.registerWindow = async () => {}; preview.pages = new Map();
  preview.backend = { capabilities: async () => ({ server_instance_id: "owner", working_state: "ephemeral", working_state_reason: "Fixture permission refusal" }) };
  assert.equal(await preview.identity(), "owner");
  assert.match(preview.stateWarning, /restart.*Fixture permission refusal/);
});
