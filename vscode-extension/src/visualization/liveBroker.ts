import { sourceContent } from "./recordedModel";
import { ByteBudget, decodedBytes } from "./byteBudget";
import { FollowTransport, aborted, encodeFollowOpen } from "./followClient";
import { FollowPage, FollowSource, FollowWindow, FollowSelection, FollowFrontier, FollowError, FollowLease, canonical, freezeJson, page, selectionKey } from "./followProtocol";
import { ViewFollowBinding, ViewFollowRequest, VIEW_PAGE_BYTES, viewRequest } from "./viewFollowProtocol";

export interface ViewIdentity { readonly viewId: string; readonly generation: number; readonly revision: number; readonly queryKey: string }
export interface FollowDelivery { readonly receivedAtMs?: number; readonly identity: ViewIdentity; readonly reset: number; readonly page: FollowPage; readonly frontier: FollowFrontier }
export interface FollowState { readonly kind: "reset" | "connecting" | "bootstrapping" | "catching_up" | "following" | "retrying" | "ended" | "error";
  readonly reason: string; readonly reset: number; readonly code?: string }
export interface FollowConsumer {
  readonly identity: ViewIdentity;
  current(identity: ViewIdentity): boolean;
  state(state: FollowState): void;
  binding?(binding: ViewFollowBinding): void;
  /** Resolve only after consuming the immutable page; do not retain it after resolution. */
  page(delivery: FollowDelivery): Promise<void> | void;
}
export interface FollowSubscription { dispose(): void; refresh(): void }
export interface BrokerOptions { readonly encodedBudgetBytes?: number; readonly encodedBudget?: ByteBudget; readonly decodedBudget?: ByteBudget; readonly consumerTimeoutMs?: number;
  readonly retryBaseMs?: number; readonly retryMaxMs?: number; readonly random?: () => number }
interface Consumer {
  readonly sink: FollowConsumer; readonly identity: ViewIdentity; keys: ReadonlySet<string>;
  readonly selections: readonly FollowSelection[]; readonly stop: AbortController; reset: number;
}
interface Job {
  readonly key: string; readonly source: FollowSource; readonly windows: readonly FollowWindow[];
  readonly view?: ViewFollowRequest;
  readonly consumers: Set<Consumer>; selections?: readonly FollowSelection[]; reserved: boolean; release?: () => void;
  restart?: { reason: string; code?: string }; controller?: AbortController; task?: Promise<void>;
}
function delay(ms: number, signals: readonly AbortSignal[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); signals.forEach(s => s.removeEventListener("abort", cancel)); };
    const cancel = () => { cleanup(); reject(aborted()); };
    const timer = setTimeout(() => { cleanup(); resolve(); }, ms);
    signals.forEach(s => s.addEventListener("abort", cancel, { once: true }));
    if (signals.some(s => s.aborted)) cancel();
  });
}
/** Window-owned acquisition. No VS Code dependency, renderer, decoder or recorder connection.
 * Only pending opens coalesce. Opened jobs keep their queries even through server replacement;
 * a later join or refresh bootstraps independently without resetting peers. No history is cached.
 * Callers clear their generation on reset. A page is borrowed until its callback settles;
 * bounded display buffers and webview copies belong to the later display-budget integration. */
export class LiveBroker {
  private readonly jobs = new Set<Job>();
  private readonly pending = new Map<string, Job>();
  private closed = false;
  private readonly budget: ByteBudget;
  private readonly decoded: ByteBudget;
  private readonly timeout: number;
  private readonly retryBase: number;
  private readonly retryMax: number;
  private readonly random: () => number;
  constructor(private readonly transport: FollowTransport, options: BrokerOptions = {}) {
    this.budget = options.encodedBudget ?? new ByteBudget(options.encodedBudgetBytes ?? 64 << 20);
    this.decoded = options.decodedBudget ?? new ByteBudget();
    this.timeout = options.consumerTimeoutMs ?? 5000;
    this.retryBase = options.retryBaseMs ?? 250; this.retryMax = options.retryMaxMs ?? 10000;
    this.random = options.random ?? Math.random;
    if (![this.budget.maximum, this.timeout, this.retryBase, this.retryMax].every(n => Number.isSafeInteger(n) && n > 0)
      || this.retryMax < this.retryBase || this.timeout > 30000) throw new Error("Invalid follow broker limits");
  }
  subscribe(source: FollowSource, windows: readonly FollowWindow[], selections: readonly FollowSelection[], sink: FollowConsumer): FollowSubscription {
    return this.attach(source, windows, selections, sink);
  }
  subscribeView(source: FollowSource, view: ViewFollowRequest, sink: FollowConsumer): FollowSubscription {
    viewRequest(view);
    if (!this.transport.openView || this.transport.pageBytes !== VIEW_PAGE_BYTES || this.transport.frontierScope !== "queries") throw new Error("This transport does not support 1 MiB bound view follows");
    if (sink.identity.viewId !== view.view_id || sink.identity.revision !== view.expected_revision) throw new Error("View consumer identity differs from its admitted revision");
    return this.attach(source, [], [], sink, Object.freeze({ ...view }));
  }
  private attach(source: FollowSource, windows: readonly FollowWindow[], selections: readonly FollowSelection[], sink: FollowConsumer, view?: ViewFollowRequest): FollowSubscription {
    if (this.closed) throw new Error("Follow broker is closed");
    const content = sourceContent(source);
    if (content.kind !== "session" || !content.anchor_member?.original_name || !content.anchor_member.recording_started_at
      || !content.origin_store_id || !content.session_id) throw new Error("An anchored recording source is required");
    if (windows.length > 4096 || !view && selections.length === 0 || selections.length > 256) throw new Error("Follow selection limit");
    if (this.resources.consumers >= 256) throw new Error("Follow consumer limit");
    const unique = new Map<string, FollowSelection>();
    for (const s of selections) {
      if (!s.entry || s.entry.length > 4096 || (s.field !== undefined && (!s.field || s.field.length > 4096))) throw new Error("Invalid follow selection");
      unique.set(selectionKey(s), Object.freeze({ entry: s.entry, ...(s.field === undefined ? {} : { field: s.field }) }));
    }
    // Make the identity independent of object property insertion order and caller mutations.
    const key = canonical({ source, windows, ...(view ? { view } : {}) });
    if (key.length > 1 << 20) throw new Error("Follow source/window byte limit");
    let consumer: Consumer = { sink, identity: Object.freeze({ ...sink.identity }), keys: new Set(unique.keys()),
      selections: Object.freeze([...unique.values()]), stop: new AbortController(), reset: 0 };
    let job = this.admit(key, source, windows, consumer, "Opening the selected recording", view);
    return { dispose: () => this.remove(job, consumer), refresh: () => {
      if (this.closed || consumer.stop.signal.aborted) return;
      const replacement: Consumer = { ...consumer, stop: new AbortController() };
      // Admission is synchronous and precedes detachment: a refusal preserves this view too.
      // Replacing one of 256 consumers requires byte/job headroom, not a 257th consumer slot.
      const next = this.admit(key, job.source, job.windows, replacement, "Explicit refresh of the same recording", view);
      this.remove(job, consumer); job = next; consumer = replacement;
    } };
  }
  private admit(key: string, source: FollowSource, windows: readonly FollowWindow[], consumer: Consumer, reason: string, view?: ViewFollowRequest): Job {
    if (!view) encodeFollowOpen(source, consumer.selections, windows, this.transport.pageBytes, this.transport.frontierScope);
    let job = this.pending.get(key);
    if (job && !view) {
      const union = this.union([...job.consumers, consumer]);
      if (union.length > 256) job = undefined;
      else try { encodeFollowOpen(job.source, union, job.windows, this.transport.pageBytes, this.transport.frontierScope); }
      catch (error) {
        if (!(error instanceof FollowError) || error.code !== "RESOURCE_LIMIT") throw error;
        job = undefined;
      }
    }
    // A valid subscription that cannot fit a pending union can open independently.
    if (!job) {
      if (this.jobs.size >= 32) throw new FollowError("RESOURCE_LIMIT", "Follow job limit reached; close another view before opening or refreshing");
      job = { key, view, source: freezeJson(JSON.parse(JSON.stringify(source))), windows: freezeJson(JSON.parse(JSON.stringify(windows))),
        consumers: new Set(), reserved: false };
    }
    this.reserve(job); this.jobs.add(job); this.pending.set(key, job);
    job.consumers.add(consumer);
    if (!job.task) this.start(job, reason);
    return job;
  }
  private union(consumers: Iterable<Consumer>): FollowSelection[] {
    return [...new Map([...consumers].flatMap(c => c.selections.map(s => [selectionKey(s), s] as const))).values()]
      .sort((a, b) => selectionKey(a) < selectionKey(b) ? -1 : 1);
  }
  private unpend(job: Job): void {
    if (this.pending.get(job.key) === job) this.pending.delete(job.key);
  }
  private reserve(job: Job): void {
    // A response plus the previous frontier may coexist. No encoded page history is cached.
    if (job.reserved) return;
    try { job.release = this.budget.reserve(2 * this.transport.pageBytes); }
    catch { throw new FollowError("RESOURCE_LIMIT", "Window follow byte budget is full; close another source or use smaller pages"); }
    job.reserved = true;
  }
  private remove(job: Job, c: Consumer): void {
    if (!job.consumers.delete(c)) return;
    c.stop.abort();
    if (!job.consumers.size) { job.restart = undefined; job.controller?.abort();
      this.unpend(job);
      if (!job.task) { this.jobs.delete(job); job.reserved = false; job.release?.(); job.release = undefined; } }
  }
  private state(job: Job, state: Omit<FollowState, "reset">, only?: Consumer): void {
    for (const c of only ? [only] : [...job.consumers]) {
      if (!this.current(job, c)) continue;
      try { c.sink.state({ ...state, reset: c.reset }); } catch { this.remove(job, c); }
    }
  }
  private current(job: Job, c: Consumer): boolean {
    if (!job.consumers.has(c)) return false;
    try { if (c.sink.current(c.identity)) return true; } catch { /* A failed/stale view cannot hold acquisition. */ }
    this.remove(job, c); return false;
  }
  private start(job: Job, reason: string): void {
    job.restart = { reason };
    // Seal before callbacks or awaiting credentials/create, not after the lease arrives.
    job.task = Promise.resolve().then(async () => {
      this.unpend(job); job.selections = Object.freeze(this.union(job.consumers));
      while (job.restart && job.consumers.size && !this.closed) {
        const why = job.restart; job.restart = undefined; job.controller = new AbortController();
        for (const c of job.consumers) c.reset++;
        this.state(job, { kind: "reset", ...why });
        await this.run(job, job.controller.signal);
      }
    }).finally(() => {
      job.task = undefined; job.controller = undefined; job.reserved = false; job.release?.(); job.release = undefined;
      if (!job.consumers.size) this.jobs.delete(job);
    });
  }
  private backoff(attempt: number): number {
    return Math.max(1, Math.round(Math.min(this.retryMax, this.retryBase * 2 ** Math.min(attempt, 12)) * (0.5 + 0.5 * this.random())));
  }
  private async run(job: Job, signal: AbortSignal): Promise<void> {
    let lease: FollowLease | undefined, cursor = "", previous: { prefix: string; frontier: FollowFrontier } | undefined, attempt = 0;
    let releaseLease: (() => void) | undefined, releaseFrontier: (() => void) | undefined;
    const reserve = (value: unknown) => {
      try { return this.decoded.reserve(decodedBytes(value)); }
      catch (error) { throw new FollowError("CLIENT_BUDGET", String(error)); }
    };
    const selections = job.selections!;
    const keys = new Set(selections.map(selectionKey));
    try {
      this.state(job, { kind: "connecting", reason: "Opening the selected recording" });
      while (!signal.aborted && job.consumers.size) {
        try {
          if (!lease) {
            if (job.view) {
              const opened = await this.transport.openView!(job.source, job.view, signal); lease = opened;
              releaseLease = reserve(opened);
              keys.clear(); opened.view_binding.queries.forEach(q => keys.add(selectionKey(q)));
              for (const c of [...job.consumers]) {
                c.keys = keys;
                if (this.current(job, c)) try { c.sink.binding?.(opened.view_binding); }
                catch (error) { this.state(job, { kind: "error", code: "CONSUMER_FAILED", reason: String(error) }, c); this.remove(job, c); }
              }
            } else lease = await this.transport.open(job.source, selections, job.windows, signal);
            cursor = lease.cursor;
          }
          if (signal.aborted) break;
          const raw = await this.transport.next(lease, cursor, signal);
          const receivedAtMs = performance.now();
          if (signal.aborted) break;
          if (raw === undefined) { attempt = 0; continue; } // 204 is a wait timeout, never EOF.
          const releasePage = reserve(raw);
          let checked: ReturnType<typeof page>;
          try {
            checked = page(raw, lease, cursor, keys, previous, this.transport.pageBytes);
            if (job.view && !checked.frontier.clock) throw new FollowError("INVALID_RESPONSE", "Recording panels require clock evidence on every prefix; update the server and Retry");
            if (checked.frontier !== previous?.frontier) {
              const replacement = reserve(checked.frontier); releaseFrontier?.(); releaseFrontier = replacement;
            }
            previous = { prefix: checked.page.prefix_id, frontier: checked.frontier };
            await Promise.all([...job.consumers].map(c => this.deliver(job, c, checked.page, checked.frontier, signal, receivedAtMs)));
          } finally { releasePage(); }
          if (signal.aborted || !job.consumers.size) break;
          cursor = checked.page.next_cursor; attempt = 0;
          const kind = checked.page.ended ? "ended" : !checked.page.frontier_complete
            ? checked.page.bootstrap ? "bootstrapping" : "catching_up" : "following";
          this.state(job, { kind, reason: kind === "ended" ? "Finalized recording consumed"
            : kind === "following" ? "Following the selected recording" : "Reading the remaining fixed prefix" });
          if (checked.page.ended) break;
        } catch (error) {
          if (signal.aborted) break;
          const e = error instanceof Error ? error : new Error(String(error));
          if (e instanceof FollowError && e.status === 410 && (e.code === "CURSOR_EXPIRED" || e.code === "CLOSED")) {
            this.state(job, { kind: "retrying", code: e.code, reason: e.message });
            await delay(this.backoff(attempt++), [signal]);
            job.restart = { reason: "Follow no longer available; clear buffers and rebootstrap the same recording", code: e.code }; break;
          }
          if (e instanceof FollowError && !e.retryable) { this.state(job, { kind: "error", code: e.code, reason: e.message }); break; }
          this.state(job, { kind: "retrying", code: e instanceof FollowError ? e.code : "TRANSPORT", reason: e.message });
          await delay(this.backoff(attempt++), [signal]);
        }
      }
    } catch (error) {
      if (!signal.aborted) this.state(job, { kind: "error", code: "CLIENT", reason: String(error) });
    } finally {
      releaseLease?.(); releaseFrontier?.();
      // This independent request survives cancellation of a waiting next. HTTP cleanup has a 2 s deadline.
      if (lease) try { await this.transport.close(lease); } catch { /* Lost cleanup is bounded by the server's 60 s idle lease. */ }
    }
  }
  private async deliver(job: Job, c: Consumer, data: FollowPage, frontier: FollowFrontier, signal: AbortSignal, receivedAtMs: number): Promise<void> {
    if (signal.aborted || !this.current(job, c)) return;
    const timeout = new AbortController();
    const timer = delay(this.timeout, [signal, c.stop.signal, timeout.signal]);
    try {
      const selected = Object.freeze({ ...data, batches: Object.freeze(data.batches.filter(b => c.keys.has(selectionKey(b)))) });
      await Promise.race([
        Promise.resolve().then(() => { if (!signal.aborted && this.current(job, c)) return c.sink.page(Object.freeze({ identity: c.identity, reset: c.reset, page: selected, frontier, receivedAtMs })); }),
        timer.then(() => { throw new FollowError("CONSUMER_TIMEOUT", "View did not consume its follow page within the deadline; reopen it to rebootstrap"); })
      ]);
    } catch (error) {
      if (!signal.aborted && !c.stop.signal.aborted) {
        this.state(job, { kind: "error", code: error instanceof FollowError ? error.code : "CONSUMER_FAILED", reason: String(error) }, c);
        this.remove(job, c);
      }
    } finally { timeout.abort(); }
  }
  async dispose(): Promise<void> {
    this.closed = true;
    const jobs = [...this.jobs.values()];
    for (const job of jobs) for (const c of [...job.consumers]) this.remove(job, c);
    await Promise.all(jobs.map(j => j.task)); this.jobs.clear(); this.pending.clear();
  }
  get resources(): { jobs: number; consumers: number; reservedBytes: number } {
    const jobs = [...this.jobs.values()];
    return { jobs: jobs.length, consumers: jobs.reduce((n, j) => n + j.consumers.size, 0),
      reservedBytes: jobs.filter(j => j.reserved).length * 2 * this.transport.pageBytes };
  }
}
