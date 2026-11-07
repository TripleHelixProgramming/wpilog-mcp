export interface RecoveryActions<T> {
  suspend(): void;
  read(signal: AbortSignal): Promise<T>;
  apply(value: T, signal: AbortSignal): Promise<void>;
  failed(error: unknown): void;
}
/** A resync is a complete reattachment, not just a successful SSE reconnect. One generation
 * owns registration, metadata and admission. A late reply cannot revive a closed/replaced view. */
export class ViewRecovery<T> {
  private closed = false; private abort?: AbortController; private timer?: NodeJS.Timeout;
  constructor(private actions: RecoveryActions<T>, private retryMs = 1000) {}
  start(): void {
    if (this.closed) return;
    this.abort?.abort(); clearTimeout(this.timer);
    const owner = this.abort = new AbortController(); this.actions.suspend(); void this.run(owner, 0);
  }
  private current(owner: AbortController): boolean { return !this.closed && this.abort === owner && !owner.signal.aborted; }
  private async run(owner: AbortController, attempt: number): Promise<void> {
    try {
      const value = await this.actions.read(owner.signal);
      if (!this.current(owner)) return;
      await this.actions.apply(value, owner.signal);
    } catch (error) {
      if (!this.current(owner)) return;
      this.actions.failed(error);
      this.timer = setTimeout(() => { this.timer = undefined; void this.run(owner, attempt + 1); }, Math.min(10000, this.retryMs * 2 ** Math.min(attempt, 4)));
    }
  }
  dispose(): void { this.closed = true; this.abort?.abort(); clearTimeout(this.timer); }
}
