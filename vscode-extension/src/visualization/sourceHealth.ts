import { canonical, FollowSource } from "./followProtocol";
import { SourceRef } from "./recordedModel";
type HealthSource = FollowSource | SourceRef;

export interface SourceHealth {
  source_ref: HealthSource;
  replica?: { generation: string; content_sha256: string; authority_state?: string; verification_basis?: string; server_epoch?: string };
  acquisition_state: "recording" | "reconnecting" | "waiting_for_time_sync" | "waiting_for_recording" | "inactive";
  history_state: "live" | "finalized";
  reason: string; connected: boolean | null; clock_synchronized: boolean | null; last_value_age_ms: number | null;
}
export type HealthObservation = { kind: "ok"; health: SourceHealth; reason: string } | { kind: "unavailable"; reason: string };
export interface HealthWatch { watch(source: HealthSource, changed: (value: HealthObservation) => void): { dispose(): void }; }
export function sourceHealth(value: any, source: HealthSource): SourceHealth {
  if (!value || canonical(value.source_ref) !== canonical(source)
      || !["recording", "reconnecting", "waiting_for_time_sync", "waiting_for_recording", "inactive"].includes(value.acquisition_state)
      || !["live", "finalized"].includes(value.history_state) || typeof value.reason !== "string" || value.reason.length > 4096
      || ![true, false, null].includes(value.connected) || ![true, false, null].includes(value.clock_synchronized)
      || value.last_value_age_ms !== null && (!Number.isFinite(value.last_value_age_ms) || value.last_value_age_ms < 0)) throw new Error("Invalid source health response");
  if (value.replica !== undefined && value.replica !== null) {
    const replica = value.replica;
    if (typeof replica.generation !== "string" || replica.generation.length > 1024 || !/^[0-9a-f]{64}$/.test(replica.content_sha256)
      || !["master_local", "legacy_unconfirmed", "provisional", "verified", "authoritative_mismatch"].includes(replica.authority_state))
      throw new Error("Invalid replica generation observation");
  }
  return value;
}
interface Job { source: HealthSource; listeners: Set<(value: HealthObservation) => void>; abort: AbortController; timer?: NodeJS.Timeout; }
/** Visible views of one source share a five-second heartbeat. Silence in the selected signals
 * never drives this status. No cache, handle, prefix, or acquisition survives its final subscriber. */
export class SourceHealthMonitor implements HealthWatch {
  private jobs = new Map<string, Job>(); private closed = false;
  constructor(private read: (source: HealthSource, signal: AbortSignal) => Promise<unknown>, private intervalMs = 5000) {}
  watch(source: HealthSource, changed: (value: HealthObservation) => void): { dispose(): void } {
    if (this.closed) throw new Error("Health monitor is closed");
    const key = canonical(source); let job = this.jobs.get(key);
    if (!job) {
      if (this.jobs.size >= 32) throw new Error("Health monitor supports at most 32 visible recording sources");
      job = { source: structuredClone(source), listeners: new Set(), abort: new AbortController() }; this.jobs.set(key, job);
      job.listeners.add(changed); void this.poll(key, job);
    } else job.listeners.add(changed);
    const owned = job;
    return { dispose: () => {
      owned.listeners.delete(changed);
      if (!owned.listeners.size) { owned.abort.abort(); clearTimeout(owned.timer); if (this.jobs.get(key) === owned) this.jobs.delete(key); }
    } };
  }
  private async poll(key: string, job: Job): Promise<void> {
    let value: HealthObservation;
    try { const health = sourceHealth(await this.read(job.source, job.abort.signal), job.source); value = { kind: "ok", health, reason: health.reason }; }
    catch (error) { value = { kind: "unavailable", reason: String(error) }; }
    if (this.closed || job.abort.signal.aborted || this.jobs.get(key) !== job) return;
    for (const changed of job.listeners) { try { changed(value); } catch { /* One closed panel cannot interrupt its peers. */ } }
    if (!this.closed && !job.abort.signal.aborted && this.jobs.get(key) === job) job.timer = setTimeout(() => { void this.poll(key, job); }, this.intervalMs);
  }
  dispose(): void { this.closed = true; for (const job of this.jobs.values()) { job.abort.abort(); clearTimeout(job.timer); } this.jobs.clear(); }
}
