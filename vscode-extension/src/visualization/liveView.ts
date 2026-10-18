import { ByteBudget, decodedBytes } from "./byteBudget";
import { FollowSource, canonical } from "./followProtocol";
import { LiveBroker, FollowSubscription } from "./liveBroker";
import { LiveDisplay } from "./liveDisplay";
import { LivePresentation } from "./livePresentation";
import { RecordedConfiguration, RecordedView, recordedDataKey } from "./recordedModel";
import { ViewFollowBinding } from "./viewFollowProtocol";

export function liveDataKey(view: RecordedView): string {
  const config = view.configuration as RecordedConfiguration;
  return canonical({ recorded: recordedDataKey(view.source_ref, config), live: config.time.live });
}
/** Working metadata and acquisition have separate lifetimes. Presentation-only revisions reuse
 * admitted data; selector/time edits invalidate the generation before any asynchronous work. */
export class LiveView {
  private view?: RecordedView; private key?: string; private generation = 0;
  private subscription?: FollowSubscription; private display?: LiveDisplay; private binding?: ViewFollowBinding;
  private visible = true; private closed = false; private paused = false;
  private hiddenTimer?: NodeJS.Timeout; private retry?: NodeJS.Timeout; private check?: AbortController;
  private latest?: ReturnType<LiveDisplay['consume']>; private pending = false;
  private releaseLatest?: () => void;
  constructor(private broker: LiveBroker, private budget: ByteBudget, readonly presentation: LivePresentation,
    private bind: (view: RecordedView, signal: AbortSignal) => Promise<ViewFollowBinding>,
    private state: (value: { kind: string; reason: string; generation: number; paused: boolean; binding?: ViewFollowBinding }) => void,
    private retryMs = 5000, private hiddenMs = 30000,
    private analysis?: { complete(binding: ViewFollowBinding, finalized: boolean): void; invalidate(): void }) {}
  update(view: RecordedView, pending = false, rebind = false): void {
    const key = liveDataKey(view); this.view = structuredClone(view); this.pending = pending;
    if (key === this.key && this.subscription && !rebind) return;
    if (key !== this.key || rebind) { this.stop(); this.key = key; this.clear(); }
    if (this.visible && !this.closed && !pending && !this.subscription) this.start();
  }
  private current(generation: number): boolean { return !this.closed && this.visible && generation === this.generation; }
  private emit(kind: string, reason: string): void { this.state({ kind, reason, generation: this.generation, paused: this.paused, binding: this.binding }); }
  private start(): void {
    if (!this.view || this.closed || !this.visible || this.pending) return;
    this.stop(); this.analysis?.invalidate(); const generation = this.generation, view = this.view, key = this.key!;
    if (!(view.configuration as RecordedConfiguration).panels.some(p => p.series.length)) {
      this.emit("waiting", "Add a signal to a plot, value table, statistics or raw table"); return;
    }
    try {
      this.subscription = this.broker.subscribeView(view.source_ref as unknown as FollowSource, { view_id: view.view_id, expected_revision: view.revision }, {
        identity: { viewId: view.view_id, revision: view.revision, generation, queryKey: key }, current: () => this.current(generation),
        binding: binding => {
          if (!this.current(generation)) return;
          this.binding = binding; this.display?.dispose(); this.display = new LiveDisplay(binding, view.configuration as RecordedConfiguration, this.budget);
          this.emit("bootstrapping", "Reading the admitted recording interval"); this.scheduleCheck(generation);
        },
        state: state => {
          if (!this.current(generation)) return;
          if (state.kind === "reset") this.clear();
          if (state.kind === "ended") { clearTimeout(this.retry); this.check?.abort(); }
          this.emit(state.kind, state.reason);
          if (state.kind === "error" && ["NO_RESOLVED_BINDINGS", "CLOCK_UNAVAILABLE", "BINDING_CHANGED", "REVISION_CONFLICT"].includes(state.code ?? "")) this.scheduleCheck(generation, true);
        },
        page: delivery => {
          if (!this.current(generation)) return;
          const frame = this.display?.consume(delivery);
          if (frame?.complete) {
            // The last complete prefix can outlive the next in-progress prefix while paused.
            const release = this.budget.reserve(decodedBytes(frame)); this.releaseLatest?.(); this.releaseLatest = release;
            this.latest = frame; if (!this.paused) this.presentation.offer(frame);
            this.analysis?.complete(frame.binding, frame.finalized);
          }
        },
      });
    } catch (error) { this.emit("error", String(error)); }
  }
  private scheduleCheck(generation: number, required = false): void {
    clearTimeout(this.retry);
    if (!required && !this.binding?.compatibility.bindings.some(b => b.state === "unresolved")) return;
    this.retry = setTimeout(() => { this.retry = undefined; void this.recheck(generation, required); }, this.retryMs);
  }
  private async recheck(generation: number, required: boolean): Promise<void> {
    if (!this.current(generation) || !this.view || this.pending) return;
    const view = this.view, abort = new AbortController(); this.check = abort;
    try {
      const binding = await this.bind(view, abort.signal);
      if (!this.current(generation)) return;
      if (this.view?.revision !== view.revision) { this.scheduleCheck(generation, required); return; }
      const selections = (b?: ViewFollowBinding) => canonical(b?.queries.map(q => [q.entry, q.field]) ?? []);
      if (binding.follow_ready && (required || selections(binding) !== selections(this.binding))) { this.start(); return; }
      this.binding = binding; this.emit(binding.follow_ready ? "following" : "waiting", binding.reason);
    } catch (error) { if (this.current(generation)) this.emit("waiting", String(error)); }
    if (this.current(generation)) this.scheduleCheck(generation, required);
  }
  refresh(): void { if (!this.closed && this.visible) this.start(); }
  pause(paused: boolean): void { this.paused = paused; if (paused) this.presentation.hold(); else if (this.latest) this.presentation.offer(this.latest); this.emit(paused ? "paused" : "following", paused ? "Display paused; recording and acquisition continue" : "Display follows the latest completed prefix"); }
  show(): void { if (this.closed || this.visible) return; this.visible = true; clearTimeout(this.hiddenTimer); this.start(); }
  hide(): void {
    if (!this.visible || this.closed) return; this.visible = false; this.stop(); this.presentation.hold();
    this.hiddenTimer = setTimeout(() => { this.hiddenTimer = undefined; this.clear(); }, this.hiddenMs);
  }
  fail(reason: string): void { this.stop(); this.emit("error", reason); }
  private stop(): void { this.generation++; this.subscription?.dispose(); this.subscription = undefined; clearTimeout(this.retry); this.check?.abort(); this.check = undefined; }
  private clear(): void { this.analysis?.invalidate(); this.display?.dispose(); this.display = undefined; this.releaseLatest?.(); this.releaseLatest = undefined; this.latest = undefined; this.binding = undefined; this.presentation.offer(null); }
  dispose(): void { if (this.closed) return; this.closed = true; this.stop(); clearTimeout(this.hiddenTimer); this.clear(); this.presentation.dispose(); }
}
