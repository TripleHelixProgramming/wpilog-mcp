import * as vscode from "vscode";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { FixtureApi, NativeEvent } from "./visualizationFixture";

export function run(_testsPath: string, done: (error: Error | null, failures: number) => void): void {
  void smoke().then(() => done(null, 0), () => done(null, 1));
}
async function until(check: () => boolean, label: string): Promise<void> {
  const deadline = Date.now() + 10000;
  while (!check()) { if (Date.now() >= deadline) throw new Error(`Timed out: ${label}`); await new Promise(resolve => setTimeout(resolve, 25)); }
}
export async function smoke(): Promise<void> {
  const root = process.env.WPILOG_VISUALIZATION_FIXTURE!;
  const result = { version: vscode.version, checks: [] as string[], gateFailures: [] as string[], error: undefined as string | undefined };
  const pass = (label: string) => { result.checks.push(label); console.log("STAGE1 PASS: " + label); };
  let nativeEvents: NativeEvent[] = [];
  try {
    const extension = vscode.extensions.getExtension<FixtureApi>("wpilog-stage1.lifecycle-fixture")!;
    const api = await extension.activate(); nativeEvents = api.nativeEvents;
    if (process.env.WPILOG_VISUALIZATION_PHASE === "restore") {
      const expected = JSON.parse(fs.readFileSync(path.join(root, "hot-exit.json"), "utf8"));
      const uri = vscode.Uri.parse(expected.uri);
      await until(() => api.provider.documents.has(uri.toString()), "hot-exit tab restoration");
      const restored = api.provider.documents.get(uri.toString())!;
      assert.deepEqual(restored.controller.snapshot.configuration, expected.configuration);
      assert.deepEqual((await api.backend.read(restored.controller.snapshot.view_id)).configuration, expected.configuration);
      const restoredTab = vscode.window.tabGroups.all.flatMap(g => g.tabs).find(t => t.input instanceof vscode.TabInputCustom && t.input.uri.toString() === uri.toString());
      assert.equal(restoredTab?.isDirty, true);
      pass("actual editor restart restores the dirty document and reconciles its offline edit");
      return;
    }
    // A backup can race a newer server revision before VS Code reopens the document.
    const recoveryUri = await api.create(true);
    const recoveryId = recoveryUri.path.slice(1, -8);
    const baseRecovery = await api.backend.read(recoveryId);
    const localRecovery = structuredClone(baseRecovery.configuration);
    (localRecovery.links as { cursor: boolean }).cursor = false;
    const serverRecovery = structuredClone(baseRecovery.configuration);
    (serverRecovery.links as { time_range: boolean }).time_range = false;
    const backupFile = vscode.Uri.file(path.join(root, "conflicting-backup.json"));
    await vscode.workspace.fs.writeFile(backupFile, Buffer.from(JSON.stringify({
      view_id: recoveryId, base_revision: baseRecovery.revision, configuration: localRecovery,
    })));
    await api.backend.update(recoveryId, baseRecovery.revision, serverRecovery);
    const recovered = await api.provider.openCustomDocument(recoveryUri, { backupId: backupFile.toString(), untitledDocumentData: undefined });
    try {
      assert.equal(recovered.controller.pending, true);
      assert.match(recovered.controller.warning!, /REVISION_CONFLICT/);
      assert.deepEqual(recovered.controller.snapshot.configuration, localRecovery);
      assert.deepEqual((await api.backend.read(recoveryId)).configuration, serverRecovery);
    } finally { recovered.dispose(); }
    pass("backup conflict keeps the document open with local content and the server revision intact");
    const tab = (uri: vscode.Uri) => vscode.window.tabGroups.all.flatMap(g => g.tabs).find(t => t.input instanceof vscode.TabInputCustom && t.input.uri.toString() === uri.toString());
    const doc = (uri: vscode.Uri) => api.provider.documents.get(uri.toString())!;
    const toggle = (uri: vscode.Uri) => { const config = structuredClone(doc(uri).controller.snapshot.configuration); const links = config.links as { cursor: boolean }; links.cursor = !links.cursor; return config; };
    const setting = (value: string) => vscode.workspace.getConfiguration("files").update("autoSave", value, vscode.ConfigurationTarget.Global);
    for (const mode of ["off", "afterDelay", "onFocusChange"]) {
      await setting(mode); const draft = await api.create(); await api.open(draft);
      await until(() => !!doc(draft) && api.provider.ready.has(draft.toString()), "rendered draft webview ready");
      await doc(draft).controller.edit(toggle(draft));
      await vscode.commands.executeCommand("workbench.action.files.saveAll");
      await new Promise(resolve => setTimeout(resolve, 1200));
      assert.equal(tab(draft)?.isDirty, false); assert.equal(doc(draft).controller.snapshot.saved_view_ref, null);
      const changed = structuredClone(doc(draft).controller.snapshot.configuration);
      assert.equal(await vscode.window.tabGroups.close(tab(draft)!), true);
      await until(() => !api.provider.documents.has(draft.toString()), "closed draft disposed before reopening");
      await api.open(draft); await until(() => !!doc(draft), "reopen draft");
      assert.deepEqual(doc(draft).controller.snapshot.configuration, changed);
      pass(`draft ${mode}: no destination prompt; close/reopen retains state`);
    }
    await setting("off");
    const a = await api.create(true), b = await api.create(true);
    await api.open(a); await api.open(b, vscode.ViewColumn.Two);
    await until(() => !!doc(a) && !!doc(b) && api.provider.ready.has(a.toString()) && api.provider.ready.has(b.toString()), "two rendered custom documents");
    await doc(a).controller.edit(toggle(a)); await until(() => !!tab(a)?.isDirty, "native dirty after edit");
    assert.equal(tab(b)?.isDirty, false); assert.notDeepEqual(doc(a).controller.snapshot.configuration, doc(b).controller.snapshot.configuration);
    pass("two independent native custom documents");
    const restoreAfterFailedUndo = async () => {
      await api.open(a);
      assert.equal(vscode.window.tabGroups.activeTabGroup.activeTab, tab(a), "cleanup must target document A");
      await vscode.commands.executeCommand("workbench.action.files.revert");
      await until(() => !tab(a)?.isDirty, "cleanup after failed Undo gate");
      await doc(a).controller.edit(toggle(a)); await until(() => !!tab(a)?.isDirty, "fresh edit after failed Undo gate");
    };
    const untouchedB = await api.backend.read(doc(b).controller.snapshot.view_id);
    await api.open(b, vscode.ViewColumn.Two);
    await restoreAfterFailedUndo();
    assert.deepEqual(await api.backend.read(doc(b).controller.snapshot.view_id), untouchedB);
    assert.deepEqual(doc(a).controller.snapshot.configuration, toggle(b));
    pass("failed-gate cleanup reveals A before Revert and leaves B unchanged");
    const originalA = doc(a);
    await api.open(a);
    assert.equal(doc(a), originalA, "revealing an existing view must retain its native undo owner");
    const calls = (event: string) => nativeEvents.filter(value => value.event === event && value.uri === a.toString()).length;
    const edited = structuredClone(doc(a).controller.snapshot.configuration);
    try {
      for (let cycle = 0; cycle < 20; cycle++) {
        if (cycle > 0) {
          await api.open(b, vscode.ViewColumn.Two); await api.open(a);
        }
        api.trace("undo:command", a);
        await vscode.commands.executeCommand("default:undo");
        assert.equal(calls("undo:called"), cycle + 1, "one native Undo must invoke this document's callback exactly once");
        assert.equal(calls("undo:done"), cycle + 1, "native Undo must finish its server mutation");
        await until(() => !tab(a)?.isDirty, "native undo restores saved position");
        assert.deepEqual(doc(a).controller.snapshot.configuration, doc(a).controller.snapshot.checkpoint_configuration);
        assert.deepEqual((await api.backend.read(doc(a).controller.snapshot.view_id)).configuration, doc(a).controller.snapshot.checkpoint_configuration);
        await vscode.commands.executeCommand("default:redo");
        assert.equal(calls("redo:called"), cycle + 1, "one native Redo must invoke this document's callback exactly once");
        assert.equal(calls("redo:done"), cycle + 1);
        await until(() => !!tab(a)?.isDirty, "native redo marks dirty");
        assert.deepEqual(doc(a).controller.snapshot.configuration, edited);
        assert.deepEqual((await api.backend.read(doc(a).controller.snapshot.view_id)).configuration, edited);
      }
      pass("native undo and redo across 20 editor switches");
    } catch (error) {
      api.trace("undo:failed", a);
      result.gateFailures.push(String(error));
      // Keep the failure, then restore a known state so independent lifecycle gates still run.
      await restoreAfterFailedUndo();
    }
    const release = api.holdSave();
    const save = vscode.commands.executeCommand("workbench.action.files.save");
    await until(() => doc(a).controller.saving, "native save enters barrier");
    await doc(a).controller.edit(toggle(a)); release(); await save;
    await until(() => !doc(a).controller.saving && !!tab(a)?.isDirty, "post-save queued edit remains dirty");
    assert.notDeepEqual(doc(a).controller.snapshot.configuration, doc(a).controller.snapshot.checkpoint_configuration);
    pass("workbench save barrier retains an edit made during Save");
    await vscode.commands.executeCommand("workbench.action.files.revert");
    await until(() => !tab(a)?.isDirty, "native Revert");
    assert.deepEqual(doc(a).controller.snapshot.configuration, doc(a).controller.snapshot.checkpoint_configuration);
    pass("native Revert uses saved checkpoint");
    await doc(a).controller.edit(toggle(a));
    const releaseAgent = api.holdSave(); const saveAgent = vscode.commands.executeCommand("workbench.action.files.save");
    await until(() => doc(a).controller.saving, "agent-race save barrier");
    const oldCheckpoint = structuredClone(doc(a).controller.snapshot.checkpoint_configuration);
    const agent = await api.backend.update(doc(a).controller.snapshot.view_id, doc(a).controller.snapshot.revision, toggle(a));
    await doc(a).controller.agentChanged(agent); releaseAgent(); await saveAgent;
    await until(() => !doc(a).controller.saving, "agent-race rejection");
    assert.equal(tab(a)?.isDirty, true); assert.deepEqual(doc(a).controller.snapshot.checkpoint_configuration, oldCheckpoint);
    pass("agent edit during native Save rejects the stale checkpoint and keeps the tab dirty");
    await vscode.commands.executeCommand("workbench.action.files.revert");
    await until(() => !tab(a)?.isDirty, "Revert agent race");
    await vscode.commands.executeCommand("workbench.action.splitEditorRight");
    await new Promise(resolve => setTimeout(resolve, 200));
    assert.equal(vscode.window.tabGroups.all.flatMap(g => g.tabs).filter(t => t.input instanceof vscode.TabInputCustom && t.input.uri.toString() === a.toString()).length, 1);
    pass("native Split moves a single-instance document");
    const copy = await api.create(true); await api.open(copy, vscode.ViewColumn.Beside);
    assert.ok(tab(a) && tab(copy)); pass("independent UUID opens beside the existing view");
    await assert.rejects(api.provider.saveCustomDocumentAs(doc(copy), vscode.Uri.parse(`wpilog-view://${copy.authority}/save-as/Fixture%20store/Copy.wpiview`)), /Use Save view/);
    assert.equal(tab(copy)?.isDirty, false); pass("native Save As fallback refuses before mutation with Save view guidance");
    api.journalFailure(true); await doc(copy).controller.edit(toggle(copy));
    assert.match(doc(copy).controller.warning!, /server only/i); api.journalFailure(false);
    pass("journal failure keeps server-acknowledged edits available");
    await api.open(copy); await vscode.commands.executeCommand("workbench.action.files.save");
    await until(() => !tab(copy)?.isDirty, "save after journal failure");
    await setting("afterDelay"); await doc(copy).controller.edit(toggle(copy));
    await until(() => !tab(copy)?.isDirty && !doc(copy).controller.saving, "saved view auto-save");
    pass("saved view afterDelay auto-save checkpoints known destination");
    await setting("onFocusChange"); await doc(copy).controller.edit(toggle(copy));
    const focusChangeConfiguration = structuredClone(doc(copy).controller.snapshot.configuration);
    await api.open(b); await until(() => !tab(copy)?.isDirty && !doc(copy).controller.saving, "saved view focus-change auto-save");
    assert.deepEqual(doc(copy).controller.snapshot.checkpoint_configuration, focusChangeConfiguration);
    pass("saved view onFocusChange auto-save checkpoints known destination");
    await api.open(copy);
    await setting("off"); api.offline(true); await doc(copy).controller.edit(toggle(copy));
    assert.equal(doc(copy).controller.pending, true); assert.match(doc(copy).controller.warning!, /saved locally/i);
    pass("offline saved edit stays dirty and journaled for reconnect");
    await until(() => api.backupCalls.includes(copy.toString()), "native backup invocation");
    pass("native dirty backup invoked");
    const backupUri = vscode.Uri.file(path.join(root, "manual-backup.json"));
    const backup = await api.provider.backupCustomDocument(doc(copy), { destination: backupUri });
    const backedUp = JSON.parse(Buffer.from(await vscode.workspace.fs.readFile(vscode.Uri.parse(backup.id))).toString());
    assert.deepEqual(backedUp.configuration, doc(copy).controller.snapshot.configuration);
    pass("backup contains the exact current configuration");
    if (process.env.WPILOG_VISUALIZATION_DIALOGS === "1") {
      const beforeClose = structuredClone(doc(copy).controller.snapshot.configuration);
      fs.writeFileSync(path.join(root, "awaiting-close-cancel"), copy.toString());
      assert.equal(await vscode.window.tabGroups.close(tab(copy)!), false, "Click Cancel in the native close dialog");
      assert.equal(tab(copy)?.isDirty, true); assert.deepEqual(doc(copy).controller.snapshot.configuration, beforeClose);
      pass("native Close cancellation retains the dirty tab and its offline configuration");
    }
    fs.writeFileSync(path.join(root, "hot-exit.json"), JSON.stringify({ uri: copy.toString(), configuration: doc(copy).controller.snapshot.configuration }));
    if (result.gateFailures.length) throw new Error(result.gateFailures.join("; "));
  } catch (error) { result.error = String(error); console.error(error); throw error; }
  finally {
    const phase = process.env.WPILOG_VISUALIZATION_PHASE === "restore" ? "restore" : "initial";
    fs.writeFileSync(path.join(root, `${phase}-native-events.json`), JSON.stringify(nativeEvents, null, 2));
    fs.writeFileSync(path.join(root, phase === "restore" ? "restore-result.json" : "result.json"), JSON.stringify(result, null, 2));
  }
}
