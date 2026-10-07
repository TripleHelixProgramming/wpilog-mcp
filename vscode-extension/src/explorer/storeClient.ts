import { HttpHeaders, noHeaders } from "../pitCredential";
/** User commands use local HTTP jobs; only the Java store owner writes mirror or peer files. */
import * as http from "http";
import * as https from "https";
import { MirrorRequest, MirrorStatus } from "./pitServer";
import { formatSize } from "./logsTree";

export class StoreControlError extends Error {
  constructor(message: string, readonly status?: number) { super(message); }
}
export interface SyncTarget { path: string; id: string; peers: string[] }
export interface SyncResult {
  sessions_created: unknown[];
  files_copied: { path: string; bytes: number }[];
  files_present: { path: string }[];
  conflicts: unknown[]; refusals: unknown[]; stopped: unknown[];
}
export interface SyncJob {
  job_id: string; state: "queued" | "running" | "done" | "failed";
  progress?: { phase: string; path?: string; bytes_copied?: number };
  result?: SyncResult; error?: string;
}
export function syncSummary(result: SyncResult): string {
  return `${result.sessions_created.length} sessions created, ${result.files_copied.length} files copied (${formatSize(result.files_copied.reduce((sum, file) => sum + file.bytes, 0))}), ${result.files_present.length} present, ${result.conflicts.length} conflicts, ${result.refusals.length} refusals, ${result.stopped.length} interrupted`;
}
export function rememberedPeers(target: SyncTarget): string[] { return [...new Set(target.peers)]; }

export class StoreClient {
  constructor(readonly endpoint: string, private readonly timeoutMs = 30_000, private readonly headersFor: HttpHeaders = noHeaders) {}
  private async request<T>(route: string, method = "GET", body?: unknown): Promise<T> {
    const url = new URL(route, this.endpoint);
    if (url.origin !== new URL(this.endpoint).origin) throw new StoreControlError("Store control URL changed server");
    const authorization = await this.headersFor(url.href);
    const data = body === undefined ? undefined : JSON.stringify(body);
    return new Promise((resolve, reject) => {
      const request = (url.protocol === "https:" ? https : http).request(url, { method,
        headers: { ...authorization, Accept: "application/json", ...(data === undefined ? {} : { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(data) }) } }, response => {
        const chunks: Buffer[] = []; let size = 0;
        response.on("data", (chunk: Buffer) => {
          size += chunk.length;
          if (size > 8 * 1024 * 1024) request.destroy(new StoreControlError("Store control response exceeds 8 MiB"));
          else chunks.push(chunk);
        });
        response.on("error", reject);
        response.on("end", () => {
          clearTimeout(timer);
          const text = Buffer.concat(chunks).toString("utf8");
          let value: any;
          try { value = JSON.parse(text); } catch { reject(new StoreControlError(`Store answered invalid JSON (HTTP ${response.statusCode})`)); return; }
          if ((response.statusCode ?? 0) < 200 || response.statusCode! >= 300) reject(new StoreControlError(value.error ?? `HTTP ${response.statusCode}`, response.statusCode));
          else resolve(value as T);
        });
      });
      const timer = setTimeout(() => request.destroy(new StoreControlError("Store control request timed out")), this.timeoutMs);
      request.on("error", error => { clearTimeout(timer); reject(error); });
      request.end(data);
    });
  }
  async uploadTargets(): Promise<{ stores: { id: string }[]; unreadable: { path: string; reason: string }[] }> {
    const value = await this.request<{ id?: string; mirror?: boolean; stores?: { id: string; mirror: boolean }[]; unreadable?: { path: string; reason: string }[] }>("/store");
    const stores = value.stores ?? (value.id ? [{ id: value.id, mirror: value.mirror ?? false }] : []);
    return { stores: stores.filter(store => !store.mirror), unreadable: value.unreadable ?? [] };
  }
  status(): Promise<MirrorStatus> { return this.request("/store/mirror"); }
  configure(config: MirrorRequest): Promise<MirrorStatus> { return this.request("/store/mirror/configure", "POST", config); }
  disable(): Promise<MirrorStatus> { return this.request("/store/mirror", "DELETE"); }
  syncNow(): Promise<MirrorStatus> { return this.request("/store/mirror/sync", "POST", {}); }
  pin(sessionId: string, pinned: boolean): Promise<unknown> {
    return this.request(`/store/mirror/${pinned ? "pin_session" : "unpin_session"}`, "POST", { session_id: sessionId });
  }
  targets(): Promise<{ stores: SyncTarget[]; unreadable: { path: string; reason: string }[] }> { return this.request("/store/sync"); }
  async sync(store: string, url: string, report: (job: SyncJob) => void,
    sleep: (ms: number) => Promise<void> = ms => new Promise(resolve => setTimeout(resolve, ms))): Promise<SyncResult> {
    const accepted = await this.request<{ job_id: string; url: string }>("/store/sync", "POST", { store, url });
    const poll = new URL(accepted.url, this.endpoint);
    if (poll.origin !== new URL(this.endpoint).origin || poll.pathname !== `/store/sync/${accepted.job_id}` || poll.search || poll.hash) {
      throw new StoreControlError("Server returned an invalid sync job URL");
    }
    for (;;) {
      const job = await this.request<SyncJob>(poll.href); report(job);
      if (job.state === "done" && job.result) return job.result;
      if (job.state === "failed") throw new StoreControlError(job.error ?? "Sync failed");
      if (job.state !== "queued" && job.state !== "running") throw new StoreControlError("Server returned an invalid sync job");
      await sleep(500);
    }
  }
}
