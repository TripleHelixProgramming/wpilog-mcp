import { sourceContent } from "./recordedModel";
import { VisualizationBudgets } from "./byteBudget";
import { canonical, freezeJson, object } from "./followProtocol";
import { RecordedBuffers, recordedPlan } from "./recordedBuffers";
import { displayCopies, RecordedConfiguration, RecordedView } from "./recordedModel";
import { reductionResponse } from "./reductionResponse";
import { chartResponse } from "./chartResponse";
import { ViewFollowBinding } from "./viewFollowProtocol";

type Kind = "value" | "statistics" | "histogram" | "scatter" | "reduction";
type AnalysisBinding = Pick<ViewFollowBinding, "compatibility" | "time_policy">;
interface Waiting { signal: AbortSignal; work(): Promise<void>; resolve(): void; reject(error: unknown): void; cancelled(): void; }
/** One window admits one analysis at a time, fairly between views. Acquisition never joins
 * this queue. Cancellation removes queued work, and a stalled renderer has its own 5 s bound. */
export class AnalysisQueue {
  private waiting: Waiting[] = []; private running = false;
  run(signal: AbortSignal, work: () => Promise<void>): Promise<void> {
    if (signal.aborted) return Promise.reject(new Error("Analysis cancelled"));
    if (this.waiting.length >= 32) return Promise.reject(new Error("32 views are waiting for analysis; close a view and Retry"));
    return new Promise((resolve, reject) => {
      const item: Waiting = { signal, work, resolve, reject, cancelled: () => {
        this.waiting = this.waiting.filter(w => w !== item); reject(new Error("Analysis cancelled"));
      } };
      signal.addEventListener("abort", item.cancelled, { once: true }); this.waiting.push(item); this.next();
    });
  }
  private next(): void {
    if (this.running) return;
    const item = this.waiting.shift(); if (!item) return;
    item.signal.removeEventListener("abort", item.cancelled); this.running = true;
    void Promise.resolve().then(() => { if (item.signal.aborted) throw new Error("Analysis cancelled"); return item.work(); })
      .then(item.resolve, item.reject).finally(() => { this.running = false; this.next(); });
  }
}

export interface AnalysisBackend {
  (route: string, body: object, signal: AbortSignal, limit: { maxResponseBytes: number; timeoutMs: number; maxRequestBytes?: number }): Promise<any>;
}
/** Latest/cursor/statistics/charts belong to the server's fresh prefix, never to the display buffer.
 * A complete follow prefix requests a coalesced refresh, at most one round per second. Cursor
 * changes invalidate only values; title/geometry revisions keep the admitted data generation. */
export class LiveAnalysis {
  private readonly buffers: RecordedBuffers;
  private readonly config: RecordedConfiguration;
  private binding?: AnalysisBinding; private finalized = false;
  private at?: number; private visible = true; private paused = false; private closed = false;
  private epochs: Record<Kind, number> = { value: 0, statistics: 0, histogram: 0, scatter: 0, reduction: 0 };
  private offsets = new Map<string, number>();
  private dirty = new Set<Kind>(); private blocked = new Set<string>();
  private active?: { kind: Kind; abort: AbortController }; private running = false;
  private timer?: NodeJS.Timeout; private hiddenTimer?: NodeJS.Timeout; private started = 0;
  constructor(private readonly view: RecordedView, private readonly generation: string,
    private readonly budgets: VisualizationBudgets, private readonly queue: AnalysisQueue,
    private readonly backend: AnalysisBackend, private readonly send: (message: any) => unknown,
    private readonly warning: (error: unknown) => void, private readonly intervalMs = 1000,
    private readonly hiddenMs = 30000) {
    this.config = view.configuration as RecordedConfiguration;
    this.buffers = new RecordedBuffers(budgets, message => send({ ...message,
      ...(message.type === "recordedClear" ? { type: "analysisClear" } : {}) }));
    this.state();
  }
  private state(clearValues = false): void {
    this.send({ type: "analysisState", generation: this.generation, value_epoch: this.epochs.value,
      statistics_epoch: this.epochs.statistics, histogram_epoch: this.epochs.histogram, scatter_epoch: this.epochs.scatter, reduction_epoch: this.epochs.reduction, history: this.history(), paused: this.paused, clear_values: clearValues });
  }
  complete(binding: AnalysisBinding, finalized: boolean): void {
    if (this.closed || !this.visible) return;
    const selections = (b?: AnalysisBinding) => canonical(b?.compatibility.bindings);
    if (this.binding && (selections(binding) !== selections(this.binding) || finalized !== this.finalized)) { this.blocked.clear(); this.cancel(); }
    this.binding = binding; this.finalized = finalized; this.state(); this.request();
  }
  private request(): void {
    if (sourceContent(this.view.source_ref).kind === "session") { this.dirty.add("value"); this.dirty.add("statistics"); }
    if (this.history()) this.dirty.add("reduction");
    this.dirty.add("histogram"); this.dirty.add("scatter"); this.wake();
  }
  private history(): boolean {
    return sourceContent(this.view.source_ref).kind === "session" && !!this.binding
      && (this.finalized ? this.config.time.recorded : this.binding.time_policy).mode !== "follow";
  }
  private selections(kind: Kind): string[][] {
    const panels = this.config.panels.filter(p => p.type === (kind === "value" ? "latest" : kind === "reduction" ? "time_series" : kind));
    const ids = kind === "scatter" ? panels.filter(p => p.series.length === 2).map(p => p.series.map(s => s.requirement_id))
      : panels.flatMap(p => p.series.map(s => [s.requirement_id]));
    return [...new Map(ids.map(pair => [JSON.stringify(pair), pair])).values()];
  }
  /** Pages replace each other, always with a freshly proven prefix. The offset is view-local,
   * not a durable cursor, and paging never affects the recording or the acquisition lease. */
  scatterPage(panelId: string, offset: number): void {
    if (!this.enabled() || !Number.isSafeInteger(offset) || offset < 0) return;
    const panel = this.config.panels.find(p => p.panel_id === panelId && p.type === "scatter" && p.series.length === 2);
    if (!panel) return;
    this.offsets.set(JSON.stringify(panel.series.map(s => s.requirement_id)), offset);
    this.epochs.scatter++; if (this.active?.kind === "scatter") this.active.abort.abort();
    for (const slot of this.blocked) if (slot.startsWith("scatter:")) this.blocked.delete(slot);
    this.state(); this.dirty.add("scatter"); this.wake();
  }
  private enabled(): boolean { return !this.closed && this.visible && !this.paused && !!this.binding; }
  private wake(): void {
    if (!this.enabled() || this.running || this.timer || !this.dirty.size) return;
    this.timer = setTimeout(() => { this.timer = undefined; void this.round(); }, Math.max(0, this.started + this.intervalMs - Date.now()));
  }
  private async round(): Promise<void> {
    if (!this.enabled()) return;
    this.running = true; this.started = Date.now();
    const kinds = [...this.dirty], epochs = { ...this.epochs }; this.dirty.clear();
    try {
      for (const kind of kinds) {
        const epoch = epochs[kind];
        for (const ids of this.selections(kind)) {
          if (!this.enabled() || this.epochs[kind] !== epoch) break;
          const selected = ids.map(id => this.binding!.compatibility.bindings.find(b => b.requirement_id === id));
          const id = ids[0], pair = JSON.stringify(ids), slot = `${kind}:${kind === "scatter" ? pair : id}`;
          if (selected.some(b => b?.state !== "resolved" || !b.entry) || this.blocked.has(slot)) continue;
          const binding = selected[0]!, y = selected[1];
          const abort = new AbortController(); this.active = { kind, abort };
          const current = () => this.enabled() && !abort.signal.aborted && this.epochs[kind] === epoch;
          const body = { source_ref: this.view.source_ref, ...(kind === "scatter" ? {
            x_entry: binding.entry, ...(binding.field_path ? { x_field: binding.field_path } : {}),
            y_entry: y!.entry, ...(y!.field_path ? { y_field: y!.field_path } : {})
          } : { entry: binding.entry, ...(binding.field_path ? { field: binding.field_path } : {}) }) };
          const copies = kind === "scatter" ? this.config.panels.filter(p => p.type === "scatter"
            && JSON.stringify(p.series.map(s => s.requirement_id)) === pair).length
            : displayCopies(this.config, id, kind === "value" ? "latest" : kind === "reduction" ? "time_series" : kind);
          try {
            await this.queue.run(abort.signal, () => this.buffers.load(slot, this.generation, abort.signal,
              () => recordedPlan(this.budgets, 1, copies), async limit => {
                let result: any;
                try {
                  const range = kind === "value" ? (this.at === undefined ? {} : { at_sec: this.at }) : this.statisticsRange(kind === "reduction");
                  const bytes = Math.min(1 << 20, limit.responseBytes);
                  const points = Math.min(4096, Math.floor((bytes - 8192) / 512));
                  if (kind === "reduction" && points < 1) throw new Error("Window byte budget is full; close a view or use fewer signals before retrying history");
                  const request = { ...body, ...range, ...(kind === "reduction" ? { max_points: points, max_bytes: bytes } : {}), ...(kind === "scatter" ? { offset: this.offsets.get(pair) ?? 0, limit: limit.rows } : {}) };
                  result = await this.backend(`/source/${kind}`, request, abort.signal,
                    { maxResponseBytes: Math.min(1 << 20, limit.responseBytes), timeoutMs: 15000, maxRequestBytes: 64 << 10 });
                  if (current()) {
                    if (kind === "reduction") reductionResponse(result, request);
                    else if (kind === "histogram" || kind === "scatter") chartResponse(result, request, kind === "scatter");
                    else {
                      const inputs = object(result.inputs), prefix = object(result.prefix);
                      if (canonical(inputs.source_ref) !== canonical(body.source_ref) || inputs.entry !== binding.entry
                        || (inputs.field ?? null) !== (binding.field_path ?? null) || typeof prefix.catalog_revision !== "string"
                        || typeof prefix.finalized !== "boolean" || !Array.isArray(prefix.members)) throw new Error("Invalid recording analysis identity or prefix");
                    }
                    freezeJson(result, limit.responseBytes);
                  }
                } catch (error) {
                  if (!current()) throw error;
                  result = { status: "error", error: String(error) };
                  if ((error as { retryable?: boolean }).retryable === true) this.dirty.add(kind);
                  else this.blocked.add(slot);
                }
                return { type: kind, ...(kind === "scatter" ? { pair: ids } : { requirement_id: id }), analysis_epoch: epoch, result };
              }, current));
          } catch (error) { if (current()) { this.blocked.add(slot); this.warning(error); } }
          finally { if (this.active?.abort === abort) this.active = undefined; }
        }
      }
    } finally { this.running = false; this.wake(); }
  }
  private statisticsRange(reduction = false): object {
    const time = this.finalized ? this.config.time.recorded : this.binding!.time_policy;
    if (!reduction && !this.finalized && (time.mode === "full" || time.mode === "fixed" &&
      (time.end_sec! - time.start_sec! < 1 || time.end_sec! - time.start_sec! > 300)))
      throw new Error("Recording analysis requires a 1–300 second interval while recording; choose Start/End or a shorter Follow window");
    if (time.mode === "follow") return { last_seconds: time.window_sec };
    return time.mode === "fixed" ? { start_time: time.start_sec, end_time: time.end_sec } : {};
  }
  cursor(at: number | undefined): void {
    if (this.closed || !this.visible || this.paused || at !== undefined && !Number.isFinite(at)) return;
    this.at = at; this.epochs.value++; if (this.active?.kind === "value") this.active.abort.abort();
    for (const slot of this.blocked) if (slot.startsWith("value:")) this.blocked.delete(slot);
    this.state(true); this.dirty.add("value"); this.wake();
  }
  pause(paused: boolean): void { if (this.closed) return; this.paused = paused; this.cancel(); if (!paused) this.request(); }
  refresh(): void { if (this.closed) return; this.blocked.clear(); this.cancel(); this.request(); }
  hide(): void {
    if (this.closed || !this.visible) return; this.visible = false; this.binding = undefined; this.cancel();
    this.hiddenTimer = setTimeout(() => { this.hiddenTimer = undefined; this.clear(); }, this.hiddenMs);
  }
  show(): void { if (this.closed) return; this.visible = true; clearTimeout(this.hiddenTimer); }
  invalidate(): void { if (this.closed) return; this.binding = undefined; this.blocked.clear(); this.cancel(); this.clear(); }
  private cancel(): void {
    for (const kind of Object.keys(this.epochs) as Kind[]) this.epochs[kind]++; this.active?.abort.abort(); clearTimeout(this.timer); this.timer = undefined;
    this.dirty.clear(); this.state();
  }
  private clear(): void { void this.buffers.clear(this.generation).catch(error => { if (!this.closed) this.warning(error); }); }
  acknowledge(id: string): void { this.buffers.acknowledge(id); }
  dispose(): void {
    if (this.closed) return; this.closed = true; this.cancel(); clearTimeout(this.hiddenTimer); this.binding = undefined; this.buffers.dispose();
  }
}
