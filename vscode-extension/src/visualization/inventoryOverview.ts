import { LocalControlError } from "../mcpClient";
import { ByteBudget, decodedBytes } from "./byteBudget";
import { InventoryBackend, InventoryDetails, InventoryFilter, InventoryHandle, InventoryPage, InventoryRow, InventorySummary } from "./inventoryClient";
import { SourceRef } from "./recordedModel";

export interface OverviewState {
  generation: number; loading: boolean; expired: boolean; hidden: boolean; reason?: string; retryAt: number;
  openedAt?: number; summary?: InventorySummary; page?: InventoryPage; details?: InventoryDetails;
  filter: InventoryFilter; selected?: string; previous: boolean; next: boolean; pageNumber: number;
}
export interface OverviewClock {
  now(): number; later(fn: () => void, ms: number): unknown; cancel(timer: unknown): void;
}
const clock: OverviewClock = { now: Date.now, later: (fn, ms) => setTimeout(fn, ms), cancel: timer => clearTimeout(timer as NodeJS.Timeout) };
/** A source owns one frozen traversal, at most two row pages and one fresh selected detail.
 * All requests serialize; cancellation invalidates delivery immediately, even if transport is late.
 * A replacement is admitted before retiring the readable old snapshot. No timer keeps it alive. */
export class InventoryOverview {
  private handle?: InventoryHandle;
  private cache = new Map<number, InventoryPage>();
  private cursors: (string | undefined)[] = [undefined];
  private index = 0;
  private generation = 0;
  private request?: AbortController;
  private serial: Promise<void> = Promise.resolve();
  private timer?: unknown;
  private release?: () => void;
  private visible = false;
  private disposed = false;
  private failures = 0;
  private refreshing?: Promise<void>;
  private state: OverviewState = { generation: 0, loading: false, expired: false, hidden: true, retryAt: 0,
    filter: { query: "", types: [], unavailable: false }, previous: false, next: false, pageNumber: 0 };
  constructor(readonly source: SourceRef, private readonly backend: InventoryBackend, private readonly budget: ByteBudget,
    private readonly publish: (state: OverviewState) => void, private readonly time: OverviewClock = clock) {}
  get snapshot(): OverviewState { return this.state; }
  get retainedPages(): number { return this.cache.size; }
  private emit(): void { this.state = { ...this.state, generation: this.generation }; this.publish(this.state); }
  private stop(): void { this.request?.abort(); this.time.cancel(this.timer); this.timer = undefined; this.generation++; }
  private alive(n: number): boolean { return this.visible && !this.disposed && n === this.generation; }
  private account(cache = this.cache, cursors = this.cursors, details: InventoryDetails | undefined | null = this.state.details, summary = this.state.summary): () => void {
    return this.budget.reserve(2 * decodedBytes({ pages: [...cache.values()], cursors, details, summary }));
  }
  private replaceAccounting(release: () => void): void { this.release?.(); this.release = release; }
  private expiry(): void {
    this.time.cancel(this.timer); this.timer = undefined;
    if (!this.handle || !this.visible || this.state.expired) return;
    this.timer = this.time.later(() => { this.timer = undefined; void this.refresh(true); }, Math.max(1, Date.parse(this.handle.summary.expires_at) - this.time.now()));
  }
  private work(action: (signal: AbortSignal, n: number) => Promise<void>): Promise<void> {
    this.stop(); const n = this.generation, request = new AbortController(); this.request = request;
    this.state = { ...this.state, loading: true, reason: undefined }; this.emit();
    const task = this.serial.catch(() => {}).then(async () => {
      if (!this.alive(n)) return;
      try { await action(request.signal, n); }
      catch (error) {
        if (!this.alive(n)) return;
        const invalidated = error instanceof LocalControlError && ["INVENTORY_EXPIRED", "INVENTORY_CHANGED", "SESSION_EXPIRED"].includes(error.code ?? "");
        this.failures++;
        const expired = this.state.expired || invalidated || !!this.handle && Date.parse(this.handle.summary.expires_at) <= this.time.now();
        this.state = { ...this.state, expired, reason: String(error instanceof Error ? error.message : error), retryAt: this.time.now() + Math.min(30000, 1000 * 2 ** Math.min(5, this.failures - 1)) };
        // Idle expiry and a changed declaration require a fresh traversal. One queued reopen,
        // never an unbounded automatic retry loop on an admission failure.
        if (invalidated && !this.refreshing) {
          queueMicrotask(() => { if (this.alive(n)) void this.refresh(true); });
        }
      } finally {
        if (this.alive(n)) { this.state = { ...this.state, loading: false }; this.emit(); this.expiry(); }
      }
    });
    this.serial = task; return task;
  }
  async show(): Promise<void> {
    if (this.disposed || this.visible) return;
    this.visible = true; this.state = { ...this.state, hidden: false }; await this.refresh(true);
  }
  async hide(): Promise<void> {
    if (!this.visible) return;
    this.visible = false; this.stop(); this.refreshing = undefined; const old = this.handle; this.handle = undefined;
    this.cache.clear(); this.cursors = [undefined]; this.release?.(); this.release = undefined;
    this.state = { ...this.state, hidden: true, loading: false, summary: undefined, page: undefined, details: undefined, previous: false, next: false }; this.emit();
    await this.serial; if (old) await this.backend.close(old).catch(() => {});
  }
  async dispose(): Promise<void> { this.disposed = true; await this.hide(); this.stop(); }
  refresh(automatic = false): Promise<void> {
    if (!this.visible || this.disposed) return Promise.resolve();
    if (this.refreshing) return this.refreshing;
    if (!automatic && this.time.now() < this.state.retryAt) return Promise.resolve();
    const task = this.work(async (signal, n) => {
      const opened = await this.backend.open(this.source, signal);
      let admitted = false;
      try {
        if (!this.alive(n)) return;
        if (Date.parse(opened.summary.expires_at) <= this.time.now()) throw new Error("New inventory has already expired; check the server clock and Retry");
        const first = await this.backend.list(opened, this.state.filter, undefined, signal);
        if (!this.alive(n)) return;
        const cache = new Map([[0, first]]), cursors = [undefined];
        const release = this.account(cache, cursors, null, opened.summary);
        const old = this.handle; this.handle = opened; admitted = true;
        this.cache = cache; this.cursors = cursors; this.index = 0; this.replaceAccounting(release);
        this.failures = 0;
        this.state = { ...this.state, summary: opened.summary, page: first, openedAt: this.time.now(), details: undefined,
          expired: false, reason: undefined, retryAt: 0, previous: false, next: !first.complete, pageNumber: 1 };
        this.emit(); if (old) await this.backend.close(old).catch(() => {});
        if (this.alive(n) && this.state.selected) await this.readSelected(signal, n);
      } finally { if (!admitted) await this.backend.close(opened).catch(() => {}); }
    });
    this.refreshing = task;
    void task.finally(() => { if (this.refreshing === task) this.refreshing = undefined; });
    return task;
  }
  async filter(query: string, type: string, unavailable = false): Promise<void> {
    if (!this.visible || !this.handle || this.state.expired) return;
    if ([...query].length > 256 || [...type].length > 256) { this.state = { ...this.state, reason: "Search and exact type are limited to 256 characters" }; this.emit(); return; }
    const filter = { query, types: type ? [type] : [], unavailable };
    await this.work(async (signal, n) => {
      const page = await this.backend.list(this.handle!, filter, undefined, signal);
      if (!this.alive(n)) return;
      const cache = new Map([[0, page]]), cursors = [undefined];
      const release = this.account(cache, cursors); this.cache = cache; this.cursors = cursors; this.index = 0; this.replaceAccounting(release);
      this.state = { ...this.state, page, filter, previous: false, next: !page.complete, pageNumber: 1, reason: undefined }; this.emit();
    });
  }
  async navigate(direction: -1 | 1, generation: number): Promise<void> {
    if (generation !== this.generation || !this.visible || !this.handle || this.state.loading || this.state.expired) return;
    if (direction < 0 ? !this.state.previous : !this.state.next) return;
    const target = this.index + direction;
    await this.work(async (signal, n) => {
      const cursors = [...this.cursors]; if (direction > 0) cursors[target] = this.state.page!.next_cursor!;
      // Server rechecks authority even when navigating back; only the adjacent page is retained.
      const page = await this.backend.list(this.handle!, this.state.filter, cursors[target], signal);
      if (!this.alive(n)) return;
      const cache = new Map([[this.index, this.state.page!], [target, page]]);
      const release = this.account(cache, cursors); this.cache = cache; this.cursors = cursors; this.index = target; this.replaceAccounting(release);
      this.state = { ...this.state, page, previous: target > 0, next: !page.complete, pageNumber: target + 1 }; this.emit();
    });
  }
  async select(name: string, generation: number): Promise<void> {
    if (generation !== this.generation || !this.visible || this.state.loading || this.state.expired) return;
    if (!this.state.page?.entries.some(r => r.name === name && !r.unavailable)) return;
    this.state = { ...this.state, selected: name, details: undefined };
    await this.work((signal, n) => this.readSelected(signal, n));
  }
  private async readSelected(signal: AbortSignal, n: number): Promise<void> {
    const selected = this.state.selected, handle = this.handle; if (!selected || !handle) return;
    let row: InventoryRow | undefined = [...this.cache.values()].flatMap(p => p.entries).find(r => r.name === selected);
    // The exact name can move off-page after refresh. Only this explicit selection's literal
    // matches are searched, one bounded page at a time; no full-inventory prefetch or binding guess.
    const seen = new Set<string>(); let cursor: string | undefined, temporary: (() => void) | undefined;
    try {
      while (!row) {
        const page = await this.backend.list(handle, { query: [...selected].slice(0, 256).join(""), types: [], unavailable: false }, cursor, signal);
        if (!this.alive(n)) return;
        const release = this.budget.reserve(decodedBytes({ page, cursors: [...seen] })); temporary?.(); temporary = release;
        row = page.entries.find(r => r.name === selected);
        if (row || page.complete) break;
        if (!page.next_cursor || seen.has(page.next_cursor) || seen.size >= 1024) throw new Error("Selected entry lookup exceeded its bounded traversal; refine the filter and select it again");
        seen.add(page.next_cursor); cursor = page.next_cursor;
      }
      if (!row) { this.state = { ...this.state, reason: `Selected entry is no longer present: ${selected}`, details: undefined }; return; }
      const details = await this.backend.details(handle, row, signal);
      if (!this.alive(n)) return;
      const release = this.account(this.cache, this.cursors, details); this.replaceAccounting(release);
      this.state = { ...this.state, details };
    } finally { temporary?.(); }

  }
}
