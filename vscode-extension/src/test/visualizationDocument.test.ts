import { test } from "node:test";
import * as assert from "node:assert/strict";
import { Backend, DocumentController, Journal, NativeEdit, Recovery, ViewSnapshot } from "../visualization/documentController";

function fixture(saved = false) {
  let state: ViewSnapshot = { view_id: "fixture", revision: 1, configuration: { value: 0 },
    checkpoint_configuration: saved ? { value: 0 } : null,
    saved_view_ref: saved ? { store_id: "store", saved_view_id: "saved", revision: 1 } : null };
  let offline = false, brokenJournal = false;
  let blockSave: (() => Promise<void>) | undefined;
  const backend: Backend = {
    async read() { return structuredClone(state); },
    async update(_id, revision, configuration) {
      if (offline) throw new Error("OFFLINE");
      if (revision !== state.revision) throw new Error("REVISION_CONFLICT");
      state = { ...state, revision: revision + 1, configuration: structuredClone(configuration) }; return structuredClone(state);
    },
    async save(_id, revision) {
      await blockSave?.();
      if (offline) throw new Error("OFFLINE");
      if (revision !== state.revision) throw new Error("REVISION_CONFLICT");
      state = { ...state, revision: revision + 1, checkpoint_configuration: structuredClone(state.configuration),
        saved_view_ref: { store_id: "store", saved_view_id: "saved", revision: (state.saved_view_ref?.revision ?? 0) + 1 } };
      return structuredClone(state);
    },
  };
  const entries = new Map<string, Recovery>();
  const journal: Journal = {
    async write(value) { if (brokenJournal) throw new Error("disk full"); entries.set(value.view_id, structuredClone(value)); },
    async remove(id) { entries.delete(id); },
  };
  const edits: NativeEdit[] = []; let notifications = 0;
  const controller = new DocumentController(structuredClone(state), backend, journal, edit => edits.push(edit), () => { notifications++; });
  return { controller, backend, entries, edits, state: () => state, notifications: () => notifications,
    offline: (v: boolean) => { offline = v; }, brokenJournal: () => { brokenJournal = true; },
    holdSave: () => { let release!: () => void; const promise = new Promise<void>(r => { release = r; }); blockSave = () => promise; return release; } };
}

test("drafts persist without native dirty events; promotion clears draft undo", async () => {
  const f = fixture(); await f.controller.edit({ value: 1 });
  assert.deepEqual(f.state().configuration, { value: 1 }); assert.equal(f.edits.length, 0);
  await f.controller.undoDraft(); assert.deepEqual(f.state().configuration, { value: 0 });
  await f.controller.redoDraft(); await f.controller.promote();
  assert.equal(f.controller.draftUndoDepth, 0);
  await assert.rejects(f.controller.undoDraft(), /draft/i);
  await f.controller.edit({ value: 2 }); assert.equal(f.edits.length, 1);
  await f.edits[0].undo(); assert.deepEqual(f.state().configuration, { value: 1 });
});
test("journal failure warns but server-acknowledged gestures still succeed", async () => {
  const f = fixture(); f.brokenJournal(); await f.controller.edit({ value: 2 });
  assert.deepEqual(f.state().configuration, { value: 2 }); assert.match(f.controller.warning!, /server only/i);
  assert.equal(f.controller.pending, false);
});
test("offline edits recover only against their original base; no newer edit is overwritten", async () => {
  const f = fixture(true); f.offline(true); await f.controller.edit({ value: 2 });
  assert.equal(f.controller.pending, true); assert.equal(f.edits.length, 1);
  const recovery = f.entries.get("fixture")!; assert.equal(recovery.base_revision, 1);
  f.offline(false); await f.backend.update("fixture", 1, { value: 7 });
  await assert.rejects(f.controller.recover(recovery), /REVISION_CONFLICT/);
  assert.deepEqual(f.state().configuration, { value: 7 }); assert.deepEqual(f.controller.snapshot.configuration, { value: 2 });
});
test("when neither persistence path works an edit stays pending and is never reported saved", async () => {
  const f = fixture(); f.offline(true); f.brokenJournal();
  await assert.rejects(f.controller.edit({ value: 3 }), /not saved/i);
  assert.equal(f.controller.pending, true); assert.deepEqual(f.controller.snapshot.configuration, { value: 3 });
});
test("a saved view remains native-dirty when both persistence paths fail", async () => {
  const f = fixture(true); f.offline(true); f.brokenJournal();
  await assert.rejects(f.controller.edit({ value: 3 }), /not saved/i);
  assert.equal(f.edits.length, 1, "native Close must not silently discard the pending edit");
  assert.deepEqual(f.controller.snapshot.configuration, { value: 3 });
});
test("save barrier defers native edits until the workbench confirms the saved position", async () => {
  const f = fixture(true); await f.controller.edit({ value: 1 });
  const release = f.holdSave(); const saving = f.controller.save();
  await f.controller.edit({ value: 2 }); assert.equal(f.edits.length, 1);
  release(); await saving; assert.equal(f.edits.length, 1);
  assert.deepEqual(f.state().checkpoint_configuration, { value: 1 });
  await f.controller.nativeSaveCompleted(); assert.equal(f.edits.length, 2);
  assert.deepEqual(f.state().configuration, { value: 2 });
  assert.notDeepEqual(f.state().configuration, f.state().checkpoint_configuration);
});
test("an agent edit during save rejects the stale checkpoint and retains local intent", async () => {
  const f = fixture(true); await f.controller.edit({ value: 1 });
  const release = f.holdSave(); const saving = f.controller.save();
  const agent = await f.backend.update("fixture", 2, { value: 8 });
  await f.controller.agentChanged(agent); release();
  await assert.rejects(saving, /REVISION_CONFLICT/);
  assert.deepEqual(f.state().checkpoint_configuration, { value: 0 });
  assert.equal(f.edits.length, 2);
});
test("revert uses CAS; a failed save preserves draft undo", async () => {
  const f = fixture(); await f.controller.edit({ value: 1 }); f.offline(true);
  await assert.rejects(f.controller.promote(), /OFFLINE/); assert.equal(f.controller.draftUndoDepth, 1); f.offline(false);
  await f.controller.undoDraft(); assert.deepEqual(f.state().configuration, { value: 0 });
  await f.controller.promote(); await f.controller.edit({ value: 4 }); await f.controller.revert();
  assert.deepEqual(f.state().configuration, { value: 0 });
});

test("a conflicting saved edit remains native-dirty with its local configuration retained", async () => {
  const f = fixture(true); await f.backend.update("fixture", 1, { value: 9 });
  await assert.rejects(f.controller.edit({ value: 2 }), /REVISION_CONFLICT/);
  assert.equal(f.controller.pending, true); assert.deepEqual(f.controller.snapshot.configuration, { value: 2 });
  assert.equal(f.edits.length, 1, "native close must see the retained conflicting edit");
});
test("gestures accepted during Save flush as one durable undo group without overwriting the latest journal", async () => {
  const f = fixture(true); await f.controller.edit({ value: 1 });
  const release = f.holdSave(); const saving = f.controller.save();
  await f.controller.edit({ value: 2 }); await f.controller.edit({ value: 3 });
  release(); await saving; f.offline(true); await f.controller.nativeSaveCompleted();
  assert.deepEqual(f.entries.get("fixture")?.configuration, { value: 3 });
  assert.equal(f.edits.length, 2, "one undo group for the gestures queued during Save");
});

test("a missing native clean event releases the save barrier after five seconds with a warning", async context => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(true); await f.controller.edit({ value: 1 }); await f.controller.save();
  await f.controller.edit({ value: 2 });
  context.mock.timers.tick(4999); assert.equal(f.controller.saving, true);
  context.mock.timers.tick(1);
  for (let i = 0; i < 30; i++) await Promise.resolve();
  assert.equal(f.controller.saving, false);
  assert.deepEqual(f.state().configuration, { value: 2 });
  assert.deepEqual(f.state().checkpoint_configuration, { value: 1 });
  assert.match(f.controller.warning!, /timed out/i);
  assert.equal(f.notifications(), 1, "the timeout warning must be repainted without another gesture");
  assert.equal(f.edits.length, 2);
  await f.controller.edit({ value: 3 }); assert.deepEqual(f.state().configuration, { value: 3 });
});

test("the native acknowledgement deadline starts after the server save, and acknowledgement cancels it", async context => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(true); await f.controller.edit({ value: 1 });
  const release = f.holdSave(); const save = f.controller.save();
  await f.controller.edit({ value: 2 }); context.mock.timers.tick(10000);
  assert.equal(f.controller.saving, true); assert.equal(f.controller.warning, undefined);
  release(); await save; await f.controller.nativeSaveCompleted();
  context.mock.timers.tick(5000);
  for (let i = 0; i < 30; i++) await Promise.resolve();
  assert.equal(f.controller.warning, undefined); assert.equal(f.edits.length, 2);
  assert.equal(f.notifications(), 0);
});
test("disposing a document cancels its clean-event timer and retains unflushed recovery", async context => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  for (const pendingServer of [false, true]) {
    const f = fixture(true); await f.controller.edit({ value: 1 });
    const release = pendingServer ? f.holdSave() : () => {};
    const save = f.controller.save(); if (!pendingServer) await save;
    await f.controller.edit({ value: 2 }); f.controller.dispose(); release(); await save;
    context.mock.timers.tick(5000);
    for (let i = 0; i < 30; i++) await Promise.resolve();
    assert.deepEqual(f.state().configuration, { value: 1 });
    assert.deepEqual(f.entries.get("fixture")?.configuration, { value: 2 });
    assert.equal(f.edits.length, 1); assert.equal(f.controller.warning, undefined);
    assert.equal(f.notifications(), 0);
  }
});
