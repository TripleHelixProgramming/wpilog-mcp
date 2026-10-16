import { randomUUID } from "node:crypto";
import { ByteBudget, decodedBytes } from "./byteBudget";
import { OverviewState } from "./inventoryOverview";

/** At most one structured clone is awaiting the renderer; pending state only borrows the
 * controller's latest objects. A stalled webview cannot accumulate unaccounted page copies. */
export class InventoryPresentation {
  private readonly owner = randomUUID();
  private sequence = 0;
  private pending?: OverviewState;
  private flight?: { id: string; release(): void; timer: NodeJS.Timeout };
  private disposed = false;
  constructor(private readonly budget: ByteBudget, private readonly send: (id: string, state: OverviewState) => void,
    private readonly stalled: (reason: string) => void, private readonly deadlineMs = 5000) {}
  offer(state: OverviewState): void {
    if (this.disposed) return;
    this.pending = state; this.flush();
  }
  private flush(): void {
    if (this.flight || !this.pending || this.disposed) return;
    const state = this.pending; this.pending = undefined;
    let release: () => void;
    try { release = this.budget.reserve(2 * decodedBytes(state)); }
    catch (error) { this.stalled(String(error)); return; }
    const id = `${this.owner}:${++this.sequence}`;
    const timer = setTimeout(() => {
      if (this.flight?.id === id) this.stalled("The overview stopped acknowledging updates; its inventory was released. Retry when this editor responds.");
    }, this.deadlineMs);
    this.flight = { id, release, timer };
    try { this.send(id, state); }
    catch { this.acknowledge(id); this.stalled("The overview could not accept its bounded signal page"); }
  }
  acknowledge(id: string): void {
    if (this.flight?.id !== id) return;
    clearTimeout(this.flight.timer); this.flight.release(); this.flight = undefined; this.flush();
  }
  dispose(): void {
    this.disposed = true; this.pending = undefined;
    if (this.flight) { clearTimeout(this.flight.timer); this.flight.release(); this.flight = undefined; }
  }
}
