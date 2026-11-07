import { test } from "node:test";
import * as assert from "node:assert/strict";
import { LocalControlError, McpClient } from "../mcpClient";
import { DocumentController, NativeEdit } from "../visualization/documentController";
import { RecordedClient } from "../visualization/recordedClient";
import { RecordedView } from "../visualization/recordedModel";

/** Model immutable save receipts separately from the mutable working view, as API 2 does. */
function fixture() {
  let state: RecordedView = { view_id: "view", revision: 1, configuration: { value: 0 },
    checkpoint_configuration: { value: 0 }, initial_configuration: { value: 0 },
    saved_view_ref: { server_instance_id: "server", library_id: "library", saved_view_id: "saved", revision: 1 },
    source_ref: { kind: "file" }, attached_client_id: "client", detached_expires_at: null, has_unsaved_changes: false };
  const receipts = new Map<string, RecordedView>(), requests: any[] = [], edits: NativeEdit[] = [];
  const faults: { loseSave?: boolean; readError?: Error; read?: (view: RecordedView) => Promise<void> } = {};
  const backend = new RecordedClient(async () => ({ visualization: async (route: string, body: any) => {
    if (route.endsWith("/attach")) return structuredClone(state);
    if (route.endsWith("/save")) {
      requests.push(structuredClone(body));
      if (!receipts.has(body.request_id)) {
        assert.equal(body.revision, state.revision, "save CAS");
        state = { ...state, revision: state.revision + 1, checkpoint_configuration: structuredClone(state.configuration),
          saved_view_ref: { ...state.saved_view_ref!, revision: state.saved_view_ref!.revision + 1 }, has_unsaved_changes: false };
        receipts.set(body.request_id, structuredClone(state));
      }
      if (faults.loseSave) { faults.loseSave = false; throw new Error("save response lost after commit"); }
      return structuredClone(receipts.get(body.request_id));
    }
    assert.equal(route, "/views/view");
    if (body) {
      assert.equal(body.revision, state.revision, "edit CAS");
      state = { ...state, revision: state.revision + 1, configuration: structuredClone(body.configuration),
        has_unsaved_changes: JSON.stringify(body.configuration) !== JSON.stringify(state.checkpoint_configuration) };
    } else {
      if (faults.readError) throw faults.readError;
      const snapshot = structuredClone(state); await faults.read?.(snapshot); return snapshot;
    }
    return structuredClone(state);
  } }) as unknown as McpClient);
  const controller = new DocumentController(state, backend, { write: async () => {}, remove: async () => {} }, edit => edits.push(edit));
  return { backend, controller, faults, requests, edits, receipts, state: () => structuredClone(state) };
}

async function loseSaveThenEdit(f: ReturnType<typeof fixture>): Promise<void> {
  await f.controller.edit({ value: 1 }); f.faults.loseSave = true;
  await assert.rejects(f.controller.save(), /save response lost/);
  // The control stream delivered the committed checkpoint before the next local edit.
  await f.controller.agentChanged(f.state());
  await f.controller.edit({ value: 2 });
  assert.equal(f.state().revision, 4);
}

test("the adapter returns the current working snapshot when recovering a historical receipt", async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await loseSaveThenEdit(f);
  // An agent's edit can be newer than both the receipt and the editor's last notification.
  await f.backend.update("view", 4, { value: 3 });
  const recovered = await f.backend.save("view", f.controller.snapshot.revision);
  assert.deepEqual(recovered, f.state());
  assert.equal(recovered.revision, 5); assert.deepEqual(recovered.checkpoint_configuration, { value: 1 });
  assert.deepEqual(f.requests[1], f.requests[0]); assert.equal(f.receipts.size, 1);
});

test("recovered save preserves acknowledged edits, their dirty state and the next edit's revision", async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await loseSaveThenEdit(f);
  const before = f.state(); await f.controller.save();
  assert.deepEqual(f.controller.snapshot, before, "a historical receipt must not replace the working view");
  assert.deepEqual(f.controller.snapshot.configuration, { value: 2 });
  assert.deepEqual(f.controller.snapshot.checkpoint_configuration, { value: 1 });
  assert.equal((f.controller.snapshot as RecordedView).has_unsaved_changes, true);
  assert.deepEqual(f.requests[1], f.requests[0]); assert.equal(f.receipts.size, 1);
  const priorEvents = f.edits.length;
  await f.controller.nativeSaveCompleted();
  assert.equal(f.edits.length, priorEvents + 1, "after the clean-tab event, later edits must remain native-dirty");
  const unsaved = f.edits.at(-1)!;
  await unsaved.undo(); assert.deepEqual(f.state().configuration, { value: 1 });
  await unsaved.redo(); assert.deepEqual(f.state().configuration, { value: 2 });
  await f.controller.edit({ value: 3 }); // Must use the current working revision, not the receipt's.
  await f.controller.save(); await f.controller.nativeSaveCompleted();
  assert.equal(f.receipts.size, 2); assert.notEqual(f.requests[2].request_id, f.requests[0].request_id);
  assert.deepEqual(f.state().checkpoint_configuration, { value: 3 });
  assert.equal((f.controller.snapshot as RecordedView).has_unsaved_changes, false);
});

test("failed reconciliation preserves the original save UUID and the newer editor state", async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await loseSaveThenEdit(f);
  const before = structuredClone(f.controller.snapshot);
  f.faults.readError = new LocalControlError("working view temporarily unavailable", 409, "VIEW_UNAVAILABLE", true);
  await assert.rejects(f.controller.save(), /working view temporarily unavailable/);
  assert.deepEqual(f.controller.snapshot, before); assert.equal(f.controller.saving, false);
  f.faults.readError = undefined;
  await f.controller.save(); await f.controller.nativeSaveCompleted();
  assert.deepEqual(f.requests, [f.requests[0], f.requests[0], f.requests[0]]);
  assert.equal(f.receipts.size, 1); assert.deepEqual(f.controller.snapshot, f.state());
});

test("recovered saves with no later edits stay clean without another native edit", async t => {
  const f = fixture(); t.after(() => f.controller.dispose());
  await f.controller.edit({ value: 1 }); f.faults.loseSave = true;
  await assert.rejects(f.controller.save(), /save response lost/);
  const priorEvents = f.edits.length;
  await f.controller.save(); await f.controller.nativeSaveCompleted();
  assert.deepEqual(f.controller.snapshot, f.state()); assert.equal(f.edits.length, priorEvents);
  assert.equal(f.receipts.size, 1);
});

test("a recovery read and the same control notification do not conflict with a deferred gesture", async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await loseSaveThenEdit(f);
  f.faults.read = async () => {
    const incoming = await f.backend.update("view", f.state().revision, { value: 3 });
    // Return the latest read below; the same notification may already be waiting on Save.
    await f.controller.agentChanged(incoming);
    await f.controller.edit({ value: 4 });
  };
  // The read's snapshot and notification refer to the same revision, as when they race on the wire.
  const read = f.backend.read.bind(f.backend);
  f.backend.read = async id => { await read(id); return f.state(); };
  await f.controller.save(); await f.controller.nativeSaveCompleted();
  assert.deepEqual(f.state().configuration, { value: 4 });
  assert.deepEqual(f.state().checkpoint_configuration, { value: 1 });
  assert.equal(f.controller.pending, false);
});

test("promotion recovery cannot replace a newer notification arriving during the read", async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await loseSaveThenEdit(f);
  f.faults.read = async () => {
    const incoming = await f.backend.update("view", f.state().revision, { value: 3 });
    await f.controller.agentChanged(incoming);
  };
  await f.controller.promote();
  assert.deepEqual(f.controller.snapshot, f.state());
  await f.controller.edit({ value: 4 }); assert.deepEqual(f.state().configuration, { value: 4 });
});
