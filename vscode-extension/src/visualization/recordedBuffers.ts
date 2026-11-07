import { randomUUID } from "node:crypto";
import { decodedBytes, VisualizationBudgets } from "./byteBudget";
export interface RecordedPlan { responseBytes: number; decodedBytes: number; maxPoints: number; rows: number; copies: number; }
/** Reserve before I/O, including a conservative JSON/Arrow-to-plot/DOM expansion and copies.
 * Fair initial requests use at most a quarter-window share; an existing peer is never evicted.
 * Reduction is done by the server, not inferred from client samples. */
export function recordedPlan(budgets: VisualizationBudgets, slots = 1, copies = 1): RecordedPlan {
  if (!Number.isInteger(slots) || slots < 1 || !Number.isInteger(copies) || copies < 1) throw new Error("Invalid recorded buffer plan");
  const available = Math.min(budgets.decoded.available, budgets.decoded.maximum / 4 / slots);
  const responseBytes = Math.floor(Math.min(4 << 20, budgets.encoded.available / 2, available / (64 * copies)));
  if (responseBytes < 4096) throw new Error("Window byte budget is full; use fewer signals, close another view or reload with a larger decodedBufferMiB setting");
  return { responseBytes, decodedBytes: responseBytes * 64 * copies, copies,
    maxPoints: Math.min(65536, Math.floor((responseBytes - 2048) / 64)), rows: Math.min(256, Math.floor(responseBytes / 1024)) };
}
interface Flight { id: string; slot?: string; release(): void; wire(): void; timer: NodeJS.Timeout; resolve(): void; reject(error: Error): void; }
/** One view owns a bounded serialized renderer handoff. Acknowledgement transfers a reservation
 * to the visible slot; replacement and clear release old bytes only after the renderer confirms.
 * Hidden expiry uses the same clear barrier. A stalled renderer retains charges until disposal. */
export class RecordedBuffers {
  private readonly owner = randomUUID(); private serial = 0;
  private tail: Promise<void> = Promise.resolve(); private queued = 0; private closed = false; private stopped = false;
  private displayed = new Map<string, () => void>(); private flight?: Flight;
  constructor(private readonly budgets: VisualizationBudgets, private readonly send: (message: any) => unknown,
    private readonly timeoutMs = 5000) {}
  private enqueue(work: () => Promise<void>): Promise<void> {
    if (this.closed || this.stopped) return Promise.reject(new Error("Recorded display is stopped; close and reopen it"));
    if (this.queued >= 64) return Promise.reject(new Error("Too many pending recorded requests; retry after the display catches up"));
    this.queued++; const task = this.tail.then(() => { if (this.closed || this.stopped) throw new Error("Recorded display is stopped"); return work(); });
    this.tail = task.catch(() => {}).finally(() => { this.queued--; }); return task;
  }
  clear(generation: string): Promise<void> {
    return this.enqueue(() => this.deliver({ type: "recordedClear", generation }, undefined, () => {}, () => {}));
  }
  load(slot: string, generation: string, signal: AbortSignal, plan: () => RecordedPlan,
    fetch: (limit: RecordedPlan) => Promise<any>, current: () => boolean): Promise<void> {
    return this.enqueue(async () => {
      if (signal.aborted || !current()) return;
      const limit = plan(); let release = this.budgets.decoded.reserve(limit.decodedBytes), wire = () => {};
      try {
        wire = this.budgets.encoded.reserve(2 * limit.responseBytes);
        const payload = await fetch(limit);
        if (signal.aborted || !current() || this.closed) return;
        const bytes = payload.bytes instanceof Uint8Array ? payload.bytes.byteLength : Buffer.byteLength(JSON.stringify(payload.result));
        if (bytes > limit.responseBytes) throw new Error("Recorded response exceeds its reserved byte limit");
        const retain = payload.bytes instanceof Uint8Array
          ? Math.min(limit.decodedBytes, 16384 + bytes * 64 * limit.copies)
          : 16384 + decodedBytes(payload.result) * (4 + 2 * limit.copies);
        if (retain > limit.decodedBytes) throw new Error("Recorded data exceeds its decoded byte allowance; narrow the range or use fewer signals");
        release(); release = this.budgets.decoded.reserve(retain);
        const owned = release, encoded = wire; release = () => {}; wire = () => {};
        await this.deliver({ ...payload, generation, point_limit: limit.maxPoints }, slot, owned, encoded);
      } finally { release(); wire(); }
    });
  }
  private deliver(message: any, slot: string | undefined, release: () => void, wire: () => void): Promise<void> {
    if (this.closed) { release(); wire(); return Promise.resolve(); }
    return new Promise<void>((resolve, reject) => {
      const id = `${this.owner}:${++this.serial}`;
      const timer = setTimeout(() => {
        this.stopped = true; reject(new Error("Recorded display stopped acknowledging buffers; close and reopen it to release retained data"));
      }, this.timeoutMs);
      this.flight = { id, slot, release, wire, timer, resolve, reject };
      try { Promise.resolve(this.send({ ...message, delivery_id: id })).then(sent => {
        if (sent === false && this.flight?.id === id) { clearTimeout(timer); this.stopped = true; reject(new Error("Recorded display could not receive its buffer")); }
      }, error => { clearTimeout(timer); this.stopped = true; reject(error); }); }
      catch (error) { clearTimeout(timer); this.stopped = true; reject(error); }
    });
  }
  acknowledge(id: string): void {
    if (!this.flight || id !== this.flight.id) return;
    const f = this.flight; this.flight = undefined; clearTimeout(f.timer); f.wire();
    if (f.slot === undefined) { this.displayed.forEach(release => release()); this.displayed.clear(); f.release(); }
    else { this.displayed.get(f.slot)?.(); this.displayed.set(f.slot, f.release); }
    f.resolve();
  }
  dispose(): void {
    this.closed = true;
    if (this.flight) { const f = this.flight; this.flight = undefined; clearTimeout(f.timer); f.release(); f.wire(); f.reject(new Error("Recorded view closed")); }
    this.displayed.forEach(release => release()); this.displayed.clear();
  }
}
