/** Native document lifecycle is separate from source reading and panel rendering. */
export type Configuration = Record<string, unknown>;
export interface ViewSnapshot {
  view_id: string;
  revision: number;
  configuration: Configuration;
  checkpoint_configuration: Configuration | null;
  saved_view_ref: ({ store_id: string } | { server_instance_id: string; library_id: string }) & { saved_view_id: string; revision: number } | null;
}
export interface Backend {
  read(id: string): Promise<ViewSnapshot>;
  update(id: string, revision: number, configuration: Configuration): Promise<ViewSnapshot>;
  save(id: string, revision: number): Promise<ViewSnapshot>;
}
export interface Recovery { view_id: string; base_revision: number; configuration: Configuration; }
export interface Journal { write(value: Recovery): Promise<void>; remove(id: string): Promise<void>; read?(id: string): Promise<Recovery | undefined>; }
export interface NativeEdit { label: string; undo(): Promise<void>; redo(): Promise<void>; }
const copy = <T>(value: T): T => structuredClone(value);
const same = (a: Configuration, b: Configuration): boolean => JSON.stringify(a) === JSON.stringify(b);

export class DocumentController {
  warning: string | undefined;
  pending = false;
  saving = false;
  private undo: Configuration[] = [];
  private redo: Configuration[] = [];
  get draftUndoDepth(): number { return this.undo.length; }
  private serial: Promise<void> = Promise.resolve();
  private deferred: { configuration: Configuration; label: string }[] = [];
  private incoming: ViewSnapshot | undefined;
  private saveBase: ViewSnapshot | undefined;
  private cleanEventTimer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;
  constructor(public snapshot: ViewSnapshot, private backend: Backend, private journal: Journal,
    private emit: (edit: NativeEdit) => void, private changed: () => void = () => {}) { this.snapshot = copy(snapshot); }

  private queue(action: () => Promise<void>): Promise<void> {
    const task = this.serial.then(action); this.serial = task.catch(() => {}); return task;
  }
  async edit(configuration: Configuration, label = "Edit visualization"): Promise<void> {
    if (this.saving) {
      if (this.deferred.length >= 256) throw new Error("Too many pending gestures; wait for Save to finish");
      await this.journal.write({ view_id: this.snapshot.view_id,
        base_revision: this.saveBase!.revision, configuration: copy(configuration) });
      this.deferred.push({ configuration: copy(configuration), label });
      this.pending = true; return;
    }
    return this.queue(() => this.mutate(configuration, label, true));
  }
  private async mutate(configuration: Configuration, label: string, recordUndo: boolean, nativeEvent = true): Promise<void> {
    const before = copy(this.snapshot.configuration), base = this.snapshot.revision;
    let journaled = false;
    try {
      await this.journal.write({ view_id: this.snapshot.view_id, base_revision: base, configuration: copy(configuration) });
      journaled = true;
    } catch { this.warning = "Saved on server only; local recovery unavailable"; }
    this.snapshot.configuration = copy(configuration); this.pending = true;
    try {
      this.snapshot = copy(await this.backend.update(this.snapshot.view_id, base, configuration));
      this.pending = false;
      if (journaled) { this.warning = undefined; await this.journal.remove(this.snapshot.view_id).catch(() => {}); }
    } catch (error) {
      this.warning = String(error).includes("REVISION_CONFLICT") ? "Conflict; local changes retained" : "Pending server; saved locally";
      if (!journaled) {
        this.warning = "Changes not saved; closing can lose them. Retry or export.";
        if (nativeEvent && this.snapshot.saved_view_ref) this.emitEdit(before, configuration, label);
        throw new Error(this.warning, { cause: error });
      }
      if (String(error).includes("REVISION_CONFLICT")) {
        if (nativeEvent && this.snapshot.saved_view_ref) this.emitEdit(before, configuration, label);
        throw error;
      }
    }
    if (recordUndo && !this.snapshot.saved_view_ref) { this.undo.push(before); this.redo = []; }
    if (nativeEvent && this.snapshot.saved_view_ref) this.emitEdit(before, configuration, label);
  }
  private emitEdit(before: Configuration, after: Configuration, label: string): void {
    this.emit({ label,
      undo: () => this.queue(() => this.mutate(copy(before), `Undo ${label}`, false, false)),
      redo: () => this.queue(() => this.mutate(copy(after), `Redo ${label}`, false, false)),
    });
  }
  private acceptSaved(result: ViewSnapshot): void {
    // A notification may have already installed an edit newer than the recovery read.
    if (result.revision >= this.snapshot.revision) this.snapshot = copy(result);
  }
  private markUnsavedChanges(): void {
    const checkpoint = this.snapshot.checkpoint_configuration;
    if (checkpoint && !same(checkpoint, this.snapshot.configuration)) {
      this.emitEdit(checkpoint, this.snapshot.configuration, "Edits after saved checkpoint");
    }
  }
  async undoDraft(): Promise<void> {
    if (this.snapshot.saved_view_ref) throw new Error("Draft undo ends at Save view; use native Undo");
    return this.queue(async () => {
      const previous = this.undo.at(-1); if (!previous) return;
      const current = copy(this.snapshot.configuration);
      await this.mutate(previous, "Undo draft", false); this.undo.pop(); this.redo.push(current);
    });
  }
  async redoDraft(): Promise<void> {
    if (this.snapshot.saved_view_ref) throw new Error("Draft redo ends at Save view; use native Redo");
    return this.queue(async () => {
      const next = this.redo.at(-1); if (!next) return;
      const current = copy(this.snapshot.configuration);
      await this.mutate(next, "Redo draft", false); this.redo.pop(); this.undo.push(current);
    });
  }
  async promote(): Promise<void> {
    return this.queue(async () => {
      if (this.pending) throw new Error("Pending edits must reach the server before Save view");
      this.acceptSaved(await this.backend.save(this.snapshot.view_id, this.snapshot.revision));
      this.undo = []; this.redo = [];
      this.markUnsavedChanges();
    });
  }
  async save(): Promise<void> {
    if (!this.snapshot.saved_view_ref) return; // Save/Save All/auto-save never select a draft destination.
    if (this.saving) throw new Error("Save is already pending");
    this.saving = true; this.saveBase = copy(this.snapshot);
    await this.serial;
    try {
      if (this.pending && this.deferred.length === 0) throw new Error("Pending edits must reach the server before Save");
      const result = await this.backend.save(this.snapshot.view_id, this.saveBase.revision);
      this.acceptSaved(result);
      // VS Code normally confirms its saved position through the clean-tab event. A lost
      // event must not strand all later edits; bound only this acknowledgement wait.
      if (!this.disposed) {
        this.cleanEventTimer = setTimeout(() => {
          this.cleanEventTimer = undefined;
          void this.nativeSaveCompleted().catch(error => { this.warning = String(error); }).finally(() => {
            this.warning = [this.warning, "Native Save confirmation timed out; editing resumed. Check the tab before closing."].filter(Boolean).join(" ");
            this.changed();
          });
        }, 5000);
        this.cleanEventTimer.unref?.();
      }
    } catch (error) {
      this.saving = false;
      if (this.incoming) { const incoming = this.incoming; this.incoming = undefined; await this.agentChanged(incoming); }
      if (this.deferred.length) this.warning = "Save failed; pending local gestures retained for comparison";
      throw error;
    }
  }
  async nativeSaveCompleted(): Promise<void> {
    if (!this.saving) return;
    this.cancelCleanEventTimer();
    this.saving = false;
    // Recovering an older Save can leave acknowledged edits outside its checkpoint.
    // Re-emit them after the workbench marks Save clean, preserving native Undo/Redo.
    this.markUnsavedChanges();
    if (this.incoming && this.incoming.revision <= this.snapshot.revision) this.incoming = undefined;
    if (this.incoming && this.deferred.length) {
      this.warning = "REVISION_CONFLICT: agent changes and pending local gestures need comparison";
      throw new Error(this.warning);
    }
    if (this.incoming) { const incoming = this.incoming; this.incoming = undefined; await this.agentChanged(incoming); }
    const queued = this.deferred; this.deferred = []; this.pending = false;
    // One undo group preserves the latest journal until the entire accepted batch is durable.
    const last = queued.at(-1);
    if (last) await this.edit(last.configuration, queued.length === 1 ? last.label : "Edits during Save");
  }
  private cancelCleanEventTimer(): void {
    if (this.cleanEventTimer) clearTimeout(this.cleanEventTimer);
    this.cleanEventTimer = undefined;
  }
  dispose(): void { this.disposed = true; this.cancelCleanEventTimer(); }
  async revert(): Promise<void> {
    return this.queue(async () => {
      const checkpoint = this.snapshot.checkpoint_configuration;
      if (!checkpoint) throw new Error("Use Reset draft for an unsaved view");
      const restored = await this.backend.update(this.snapshot.view_id, this.snapshot.revision, checkpoint);
      this.snapshot = copy(restored); this.pending = false; this.warning = undefined;
      await this.journal.remove(this.snapshot.view_id).catch(() => {});
    });
  }
  async recover(value: Recovery): Promise<void> {
    if (value.view_id !== this.snapshot.view_id) throw new Error("Recovery belongs to another view");
    this.snapshot.configuration = copy(value.configuration); this.pending = true;
    const current = await this.backend.read(value.view_id);
    if (same(current.configuration, value.configuration)) {
      this.snapshot = copy(current); this.pending = false; await this.journal.remove(value.view_id).catch(() => {}); return;
    }
    if (current.revision !== value.base_revision) throw new Error("REVISION_CONFLICT: recovery kept for comparison");
    this.snapshot = copy(current); await this.edit(value.configuration, "Recover pending edit");
  }
  async agentChanged(incoming: ViewSnapshot): Promise<void> {
    if (incoming.view_id !== this.snapshot.view_id || incoming.revision <= this.snapshot.revision) return;
    if (this.saving) { this.incoming = copy(incoming); return; }
    if (this.pending) throw new Error("REVISION_CONFLICT: incoming revision cannot overwrite pending local edits");
    const before = copy(this.snapshot.configuration); this.snapshot = copy(incoming);
    if (incoming.saved_view_ref && !same(before, incoming.configuration)) this.emitEdit(before, incoming.configuration, "Agent edit");
  }
}
