import { randomUUID } from "node:crypto";
import { ByteBudget, decodedBytes } from "./byteBudget";
import { LiveFrame } from "./liveDisplay";

export interface FrameLatency { prefix_id: string; delivery_id: string; receipt_to_ack_ms: number; post_to_ack_ms: number; renderer_elapsed_ms: number; }

/** Both the visible frame and one in-flight replacement stay charged until renderer acknowledgement.
 * Updates coalesce at 4 Hz; a stopped renderer cannot accumulate messages or unaccounted copies. */
export class LivePresentation {
  private readonly owner = randomUUID(); private serial = 0;
  private pending?: LiveFrame | null;
  private displayed?: () => void;
  private flight?: { id: string; release: () => void; timer: NodeJS.Timeout; empty: boolean; sentAt: number; receivedAt?: number; prefix?: string };
  private timer?: NodeJS.Timeout; private last = 0; private closed = false; private stalled = false;
  constructor(private budget: ByteBudget, private send: (id: string, frame: LiveFrame | null) => void,
    private fail: (reason: string) => void, private intervalMs = 250, private timeoutMs = 5000,
    private readonly displayCopies = 0, private measured?: (value: FrameLatency) => void,
    private now: () => number = () => performance.now()) {}
  offer(frame: LiveFrame | null): void { if (this.closed || this.stalled) return; this.pending = frame; this.flush(); }
  hold(): void { this.pending = undefined; clearTimeout(this.timer); this.timer = undefined; }
  private flush(): void {
    if (this.flight || this.timer || this.pending === undefined || this.closed || this.stalled) return;
    const wait = this.intervalMs - (Date.now() - this.last);
    if (wait > 0 && this.pending !== null) { this.timer = setTimeout(() => { this.timer = undefined; this.flush(); }, wait); return; }
    const frame = this.pending; this.pending = undefined;
    let release: () => void;
    try { release = this.budget.reserve(frame ? (2 + this.displayCopies) * decodedBytes(frame) : 0); }
    catch (error) { this.stalled = true; this.fail(String(error)); return; }
    const id = `${this.owner}:${++this.serial}`; this.last = Date.now();
    const timer = setTimeout(() => { this.stalled = true; this.fail("The visualization stopped acknowledging frames; acquisition stopped. Close and reopen the view to release its retained display."); }, this.timeoutMs);
    this.flight = { id, release, timer, empty: frame === null, sentAt: this.now(), receivedAt: frame?.receivedAtMs, prefix: frame?.prefix };
    try { this.send(id, frame); } catch (error) { clearTimeout(timer); this.stalled = true; this.fail(String(error)); }
  }
  acknowledge(id: string, renderer?: { rendered?: boolean; render_elapsed_ms?: number }): void {
    if (this.flight?.id !== id) return;
    const flight = this.flight; clearTimeout(flight.timer); this.flight = undefined;
    const elapsed = renderer?.render_elapsed_ms, now = this.now();
    if (!flight.empty && renderer?.rendered === true && typeof elapsed === "number" && Number.isFinite(elapsed) && elapsed >= 0
        && flight.receivedAt !== undefined && Number.isFinite(flight.receivedAt) && flight.receivedAt <= flight.sentAt && now >= flight.sentAt) {
      this.measured?.({ prefix_id: flight.prefix!, delivery_id: id, receipt_to_ack_ms: now - flight.receivedAt,
        post_to_ack_ms: now - flight.sentAt, renderer_elapsed_ms: elapsed });
    }
    this.displayed?.(); this.displayed = flight.empty ? undefined : flight.release;
    if (flight.empty) flight.release(); this.flush();
  }
  dispose(): void {
    this.closed = true; clearTimeout(this.timer); this.pending = undefined;
    if (this.flight) { clearTimeout(this.flight.timer); this.flight.release(); this.flight = undefined; }
    this.displayed?.(); this.displayed = undefined;
  }
}
