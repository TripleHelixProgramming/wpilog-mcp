import * as http from "node:http";
import * as https from "node:https";
import { TextDecoder } from "node:util";
import { HttpHeaders, noHeaders } from "../pitCredential";
import { FOLLOW_PAGE_BYTES, FollowError, FollowFrontierScope, FollowLease, FollowSelection, FollowSource, FollowWindow, lease, object, protocol, readFrontierScope } from "./followProtocol";
import { ViewFollowRequest, ViewFollowLease, VIEW_PAGE_BYTES, viewBinding, viewRequest } from "./viewFollowProtocol";

export { FOLLOW_PAGE_BYTES } from "./followProtocol";
export const FOLLOW_REQUEST_BYTES = 64 << 10;
function requestSize(bytes: number): void {
  if (bytes > FOLLOW_REQUEST_BYTES) throw new FollowError("RESOURCE_LIMIT", "Follow request exceeds 64 KiB; reduce selected signals or time windows");
}
/** Admission and HTTP use the same UTF-8 encoding, including the windows repeated per query.
 * Stop at the first overflowing query instead of constructing the entire oversized union. */
export function encodeFollowOpen(source: FollowSource, selections: readonly FollowSelection[], windows: readonly FollowWindow[], pageBytes: number, frontierScope: FollowFrontierScope = "all"): Buffer {
  const head = Buffer.from(`{"source_ref":${JSON.stringify(source)},"queries":[`);
  const tail = Buffer.from(`],"max_records":4096,"max_page_bytes":${pageBytes}${frontierScope === "queries" ? ',"frontier_scope":"queries"' : ""}}`);
  const parts = [head]; let size = head.length + tail.length; requestSize(size);
  for (const selection of selections) {
    const part = Buffer.from((parts.length > 1 ? "," : "") + JSON.stringify({ ...selection, order: "file", windows }));
    size += part.length; requestSize(size); parts.push(part);
  }
  return Buffer.concat([...parts, tail], size);
}
function encodeRequest(body: unknown): Buffer {
  const encoded = Buffer.from(JSON.stringify(body)); requestSize(encoded.length); return encoded;
}
export interface FollowTransport {
  readonly pageBytes: number;
  readonly frontierScope?: FollowFrontierScope;
  open(source: FollowSource, selections: readonly FollowSelection[], windows: readonly FollowWindow[], signal: AbortSignal): Promise<FollowLease>;
  openView?(source: FollowSource, view: ViewFollowRequest, signal: AbortSignal): Promise<ViewFollowLease>;
  next(lease: FollowLease, cursor: string, signal: AbortSignal): Promise<unknown | undefined>;
  close(lease: FollowLease): Promise<void>;
}
export function aborted(): Error { const error = new Error("Follow cancelled"); error.name = "AbortError"; return error; }
function credentials(headers: Promise<Record<string, string>>, signal: AbortSignal): Promise<Record<string, string>> {
  return new Promise((resolve, reject) => {
    const cancel = () => reject(aborted());
    signal.addEventListener("abort", cancel, { once: true });
    headers.then(value => { signal.removeEventListener("abort", cancel); resolve(value); },
      error => { signal.removeEventListener("abort", cancel); reject(error); });
    if (signal.aborted) { signal.removeEventListener("abort", cancel); cancel(); }
  });
}

/** One origin-bound adapter, borrowing the caller's credential provider. Redirects are refused. */
export class FollowClient implements FollowTransport {
  private readonly base: URL;
  constructor(endpoint: string, private readonly headersFor: HttpHeaders = noHeaders, readonly pageBytes = FOLLOW_PAGE_BYTES,
    readonly frontierScope: FollowFrontierScope = "all") {
    this.base = new URL(endpoint);
    if (!["http:", "https:"].includes(this.base.protocol) || this.base.username || this.base.password || this.base.search || this.base.hash) throw new Error("Expected an HTTP server endpoint without credentials or query");
    if (!Number.isInteger(pageBytes) || pageBytes < 1024 || pageBytes > FOLLOW_PAGE_BYTES) throw new Error("Invalid follow page budget");
    readFrontierScope(frontierScope);
  }
  async open(source: FollowSource, selections: readonly FollowSelection[], windows: readonly FollowWindow[], signal: AbortSignal): Promise<FollowLease> {
    const response = await this.request("POST", "", encodeFollowOpen(source, selections, windows, this.pageBytes, this.frontierScope), signal, 201);
    // Validate lease identity before using its ID for cleanup. An older server can ignore an
    // unknown option; a missing/malformed echo must not silently admit the larger frontier.
    const created = lease(response, source);
    try {
      const scope = readFrontierScope(object(response).frontier_scope);
      if (scope !== this.frontierScope) throw new FollowError("UNSUPPORTED_FRONTIER_SCOPE", "Server did not negotiate the requested frontier scope; update the server before opening this view");
      return Object.freeze({ ...created, frontier_scope: scope });
    } catch (error) {
      try { await this.close(created); } catch { /* Bounded cleanup; server idle expiry remains the fallback. */ }
      throw error;
    }
  }
  async next(lease: FollowLease, cursor: string, signal: AbortSignal): Promise<unknown | undefined> {
    return this.request("POST", `/${encodeURIComponent(lease.id)}/next`, encodeRequest({ cursor, wait_ms: 25000 }), signal, 200);
  }
  async openView(source: FollowSource, view: ViewFollowRequest, signal: AbortSignal): Promise<ViewFollowLease> {
    viewRequest(view);
    if (this.pageBytes !== VIEW_PAGE_BYTES || this.frontierScope !== "queries") throw new Error("Recording views require 1 MiB queried follows");
    const response = object(await this.request("POST", "", encodeRequest({ view_binding: view }), signal, 201));
    const created = lease(response, source);
    try {
      if (response.frontier_scope !== "queries" || response.max_page_bytes !== VIEW_PAGE_BYTES) protocol("View follow policy was not negotiated");
      const binding = viewBinding(response.view_binding, view, source);
      if (!binding.follow_ready) protocol("Server opened a view without ready bindings");
      return Object.freeze({ ...created, frontier_scope: "queries", view_binding: binding });
    } catch (error) { try { await this.close(created); } catch { /* Idle expiry bounds lost cleanup. */ } throw error; }
  }
  async close(lease: FollowLease): Promise<void> {
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 2000);
    try { await this.request("DELETE", `/${encodeURIComponent(lease.id)}`, undefined, controller.signal, 204); }
    finally { clearTimeout(timer); }
  }
  private async request(method: string, suffix: string, encoded: Buffer | undefined, signal: AbortSignal, expected: number): Promise<unknown> {
    if (signal.aborted) throw aborted();
    const url = new URL(`/read/v1/follows${suffix}`, this.base);
    const headers = await credentials(this.headersFor(url.toString()), signal);
    if (signal.aborted) throw aborted();
    return new Promise((resolve, reject) => {
      const client = url.protocol === "https:" ? https : http;
      let done = false;
      const finish = (error?: Error, value?: unknown) => {
        if (done) return; done = true; clearTimeout(timer); signal.removeEventListener("abort", cancel);
        if (error) reject(error); else resolve(value);
      };
      const req = client.request(url, { method, headers: { ...headers, "Accept": "application/json", ...(encoded ? { "Content-Type": "application/json", "Content-Length": encoded.length } : {}) } }, res => {
        const status = res.statusCode ?? 0;
        // One bounded allocation, rather than an unbounded chunks array followed by a second copy.
        const buffer = Buffer.allocUnsafe(this.pageBytes); let size = 0;
        res.on("data", (chunk: Buffer) => {
          if (size + chunk.length > buffer.length) { const e = new FollowError("RESOURCE_LIMIT", "Follow response exceeds byte limit", status); finish(e); req.destroy(e); return; }
          chunk.copy(buffer, size); size += chunk.length;
        });
        res.on("error", error => finish(error));
        res.on("end", () => {
          if (done) return;
          try {
            if (status === 204 && (expected === 200 || expected === 204)) { finish(); return; }
            if (status === 429 || status === 503) throw new FollowError("BUSY", `Follow server busy (HTTP ${status})`, status, true);
            if (status === 401 || status === 403) throw new FollowError("ACCESS_DENIED", `Follow access refused (HTTP ${status}); check the server directory grants or configured credentials`, status);
            let value: unknown;
            try { value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(buffer.subarray(0, size))); } catch { protocol("Invalid follow JSON"); }
            if (status !== expected) {
              const error = object(value);
              if (typeof error.code !== "string" || typeof error.message !== "string" || ((status === 413 || status === 409 && error.retryable !== undefined) && typeof error.retryable !== "boolean")) protocol("Invalid follow refusal");
              throw new FollowError(error.code, error.message, status, (status === 409 || status === 413) && error.retryable === true);
            }
            finish(undefined, value);
          } catch (error) { finish(error as Error); }
        });
      });
      const cancel = () => { const error = aborted(); finish(error); req.destroy(error); };
      const timer = setTimeout(() => { const error = new Error("Follow response deadline exceeded"); finish(error); req.destroy(error); }, 30000);
      req.on("error", error => finish(error)); signal.addEventListener("abort", cancel, { once: true });
      if (signal.aborted) cancel(); else req.end(encoded);
    });
  }
}
