import { HttpHeaders, noHeaders } from "../pitCredential";
/** User commands use local HTTP jobs; only the Java store owner writes mirror or peer files. */
import * as http from "http";
import * as https from "https";
import { LiveRequest, LiveStatus, MirrorRequest, MirrorStatus } from "./pitServer";
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
  private mirrorRoute(suffix: string, id?: string): string { return `/store/mirror${suffix}${id === undefined ? "" : `?mirror=${encodeURIComponent(id)}`}`; }
  status(id?: string): Promise<MirrorStatus> { return this.request(this.mirrorRoute("", id)); }
  configure(config: MirrorRequest): Promise<MirrorStatus> { return this.request("/store/mirror/configure", "POST", config); }
  disable(id?: string): Promise<MirrorStatus> { return this.request(this.mirrorRoute("", id), "DELETE"); }
  syncNow(id?: string): Promise<MirrorStatus> { return this.request(this.mirrorRoute("/sync", id), "POST", {}); }
  peers(): Promise<unknown> { return this.request("/store/mirror/peers"); }
  migrate(id: string): Promise<MirrorStatus> { return this.request(this.mirrorRoute("/migrate", id), "POST", { store_id: id }); }
  async mirrorSessions(id: string): Promise<string[]> {
    const sessions = new Set<string>(); let offset = 0, epoch: string | undefined, revision: number | undefined;
    for (;;) {
      const page = await this.request<{ files: { placement?: { session_id?: string } }[]; offset: number; total: number; has_more: boolean; catalog_epoch: string; catalog_revision: number }>(
        `${this.mirrorRoute("/files", id)}&offset=${offset}&limit=256`);
      if (!Array.isArray(page.files) || page.files.length > 256 || page.offset !== offset || !Number.isSafeInteger(page.total) || page.total > 4096
          || offset + page.files.length > page.total || page.has_more && page.files.length === 0
          || epoch !== undefined && (page.catalog_epoch !== epoch || page.catalog_revision !== revision)) throw new Error("Mirror catalog changed or returned an invalid page; retry session selection.");
      epoch = page.catalog_epoch; revision = page.catalog_revision;
      for (const file of page.files) if (typeof file.placement?.session_id === "string") sessions.add(file.placement.session_id);
      offset += page.files.length; if (!page.has_more) return [...sessions].sort();
    }
  }
  liveStatus(): Promise<LiveStatus> { return this.request("/store/live"); }
  configureLive(config: LiveRequest): Promise<LiveStatus> { return this.request("/store/live/configure", "POST", config); }
  disableLive(): Promise<LiveStatus> { return this.request("/store/live", "DELETE"); }
  pin(sessionId: string, pinned: boolean, id?: string): Promise<unknown> {
    return this.request(this.mirrorRoute(`/${pinned ? "pin_session" : "unpin_session"}`, id), "POST", { session_id: sessionId });
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
