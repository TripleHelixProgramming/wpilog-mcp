/** The product Save view flow with supplied dialogs and a real import HTTP request. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
let folder: { scheme: string; fsPath: string }, cancel = false;
const host = { window: {
  showQuickPick: async (items: any[]) => cancel ? undefined : items.find(i => !i.store),
  showOpenDialog: async () => [folder], showInputBox: async () => "Fixture layout",
} };
const loader = require("node:module"), original = loader._load;
let RecordedPreview: typeof import("../visualization/recordedPreview").RecordedPreview;
try { loader._load = (name: string, ...args: any[]) => name === "vscode" ? host : original(name, ...args); RecordedPreview = require("../visualization/recordedPreview").RecordedPreview; }
finally { loader._load = original; }

test("first Save view leases its chosen store before an empty import; cancel creates nothing", async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "recorded-save-"));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  folder = { scheme: "file", fsPath: root }; let granted = false, promoted = 0; const requests: unknown[] = [];
  const canonical = await fs.realpath(root);
  const server = http.createServer(async (req, res) => {
    if (req.method === "POST") {
      if (!granted) { res.writeHead(403); res.end('{"error":"registration must finish before first mutation"}'); return; }
      let text = ""; for await (const chunk of req) text += chunk; requests.push(JSON.parse(text));
      res.writeHead(202); res.end('{"job_id":"fixture","url":"/store/import/fixture"}');
    } else res.end('{"state":"done","result":{"files":[],"same_robots":[]}}');
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const preview: any = Object.create(RecordedPreview.prototype); preview.registerWindow = async () => {};
  preview.host = { grantStore: async (p: string) => { assert.equal(p, canonical); await new Promise(resolve => setTimeout(resolve, 25)); granted = true; },
    client: async () => ({ endpoint: `http://127.0.0.1:${(server.address() as any).port}/mcp` }) };
  preview.backend = { destinations: new Map(), call: async () => ({ stores: granted ? [{ store_id: "store", path: canonical, label: "Fixture", writable: true }] : [] }) };
  preview.paint = () => {}; preview.overviews = new Map();
  const page = { document: { controller: { snapshot: { view_id: "view" }, promote: async () => { promoted++; } } }, panel: { title: "Draft" } };
  cancel = true; await preview.save(page); assert.equal(granted, false); assert.equal(promoted, 0);
  cancel = false; await preview.save(page);
  assert.deepEqual(requests, [{ store: canonical, paths: [], move: false, stated_robot: null }]);
  assert.equal(promoted, 1); assert.deepEqual(preview.backend.destinations.get("view"), { store_id: "store", title: "Fixture layout" });
  folder = { scheme: "vscode-remote", fsPath: root }; granted = false;
  await assert.rejects(preview.save(page), /local folder/i);
  assert.equal(granted, false);
});

test("ephemeral server identity warns that drafts cannot survive a restart", async () => {
  const preview: any = Object.create(RecordedPreview.prototype); preview.registerWindow = async () => {};
  preview.backend = { capabilities: async () => ({ server_instance_id: "owner", working_state: "ephemeral", working_state_reason: "Fixture permission refusal" }) };
  assert.equal(await preview.identity(), "owner");
  assert.match(preview.stateWarning, /restart.*Fixture permission refusal/);
});
