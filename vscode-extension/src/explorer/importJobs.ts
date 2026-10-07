/** Imports run in the daemon that owns the store. Retrying an uncertain POST could duplicate
 * work, so only an explicit 503 admission refusal is retried; job polling never resubmits. */
import * as http from "http";
import * as https from "https";
import { LogListing } from "./logsTree";

export interface ImportRequest {
  store: string;
  paths: string[];
  move: boolean;
  stated_robot: string | null;
}
export interface ImportResult {
  files: { original_path: string; status: "imported" | "present" | "unassigned" | "refused"; path: string | null; reason: string | null }[];
  same_robots: { serial_number: string; directories: string[] }[];
}
export interface ImportJob {
  job_id: string;
  state: "queued" | "running" | "done" | "failed";
  progress: { phase: string; path: string | null; completed: number; total: number } | null;
  result?: ImportResult | null;
  error?: string | null;
}
export class ImportError extends Error {
  constructor(readonly error: string, readonly hint?: string, readonly status?: number) {
    super([error, hint].filter(s => s !== undefined).join("\n"));
  }
}

export function importEndpointOf(mcpUrl: string, assignment = false): URL {
  const url = new URL(mcpUrl);
  url.pathname = assignment ? "/store/assign" : "/store/import";
  url.search = "";
  url.hash = "";
  return url;
}

export function progressText(job: ImportJob): string {
  const p = job.progress;
  return p ? `${p.phase}: ${p.path ?? ""} (${p.completed}/${p.total})` : job.state;
}

export function resultSummary(result: ImportResult): string {
  const count = (status: string) => result.files.filter(f => f.status === status).length;
  return `${count("imported")} imported, ${count("present")} present, ${count("unassigned")} unassigned, ${count("refused")} refused`;
}

export function resultDetails(result: ImportResult): string[] {
  return result.files.map(f => `${f.status}: ${f.original_path}${f.path ? ` → ${f.path}` : ""}${f.reason ? `\n${f.reason}` : ""}`);
}

export function sameRobotMessages(result: ImportResult): string[] {
  return result.same_robots.map(r => `Robot serial ${r.serial_number} appears in ${r.directories.join(" and ")}. These directories remain separate.`);
}

export type Exchange = { status: number; headers: http.IncomingHttpHeaders; body: string };
type Pause = (ms: number) => Promise<void>;
const pause: Pause = ms => new Promise(resolve => setTimeout(resolve, ms));

function exchange(url: URL, body?: string): Promise<Exchange> {
  return new Promise((resolve, reject) => {
    const send = url.protocol === "https:" ? https : http;
    const request = send.request(url, { method: body === undefined ? "GET" : "POST", timeout: 30_000,
      headers: body === undefined ? { Accept: "application/json" } : { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) } }, response => {
      const chunks: Buffer[] = [];
      response.on("data", (chunk: Buffer) => chunks.push(chunk));
      response.on("end", () => resolve({ status: response.statusCode ?? 0, headers: response.headers, body: Buffer.concat(chunks).toString("utf8") }));
      response.on("error", reject);
    });
    request.on("timeout", () => request.destroy(new Error("The import request timed out")));
    request.on("error", reject);
    request.end(body);
  });
}

export function refusal(response: Exchange): ImportError {
  try {
    const body = JSON.parse(response.body) as { error?: string; hint?: string };
    if (typeof body.error === "string") return new ImportError(body.error, body.hint, response.status);
  } catch { /* A proxy may return a non-JSON error. */ }
  return new ImportError(`The server answered HTTP ${response.status}`, response.body, response.status);
}

/** Bound retries of an explicit busy response, preserving its exact words if it stays busy. */
async function admitted(url: URL, body: string | undefined, sleep: Pause): Promise<Exchange> {
  for (let attempt = 0; ; attempt++) {
    const response = await exchange(url, body);
    if (response.status !== 503) return response;
    const header = response.headers["retry-after"];
    const seconds = typeof header === "string" && /^\d+$/.test(header) ? Number(header) : undefined;
    const ms = seconds === undefined && typeof header === "string" ? Date.parse(header) - Date.now() : (seconds ?? NaN) * 1000;
    if (attempt >= 3 || !Number.isFinite(ms) || ms < 0 || ms > 60_000) throw refusal(response);
    await sleep(ms);
  }
}

/** The returned job URL must remain on this server, inside its polling route. */
export async function runImport(mcpUrl: string, request: ImportRequest,
  report: (job: ImportJob) => void, assignment = false, sleep: Pause = pause): Promise<ImportResult> {
  const endpoint = importEndpointOf(mcpUrl, assignment);
  const accepted = await admitted(endpoint, JSON.stringify(request), sleep);
  return pollImport(endpoint, accepted, report, sleep);
}

export async function pollImport(endpoint: URL, accepted: Exchange, report: (job: ImportJob) => void,
  sleep: Pause = pause): Promise<ImportResult> {
  if (accepted.status !== 202) throw refusal(accepted);
  const body = JSON.parse(accepted.body) as { job_id: string; url: string };
  const poll = new URL(body.url, endpoint);
  if (poll.origin !== endpoint.origin || poll.pathname !== `/store/import/${body.job_id}` || poll.search || poll.hash) {
    throw new ImportError("The server returned an invalid import job URL");
  }
  for (;;) {
    const response = await admitted(poll, undefined, sleep);
    if (response.status !== 200) throw refusal(response);
    const job = JSON.parse(response.body) as ImportJob;
    report(job);
    if (job.state === "done" && job.result) return job.result;
    if (job.state === "failed") throw new ImportError(job.error ?? "Import failed");
    if (job.state !== "queued" && job.state !== "running") throw new ImportError("The server returned an invalid import job");
    await sleep(500);
  }
}

/** Offers count a complete listing; they must never organize only the first page silently. */
export async function completeListing(call: (args: Record<string, unknown>) => Promise<LogListing>): Promise<LogListing> {
  let offset = 0;
  let combined: LogListing | undefined;
  for (;;) {
    const page = await call({ limit: 500, offset });
    if (page.status === "error") return page;
    combined = combined ? { ...combined, logs: [...(combined.logs ?? []), ...(page.logs ?? [])] } : page;
    if (!page.has_more) return { ...combined, has_more: false };
    const next = (page.offset ?? offset) + (page.returned ?? page.logs?.length ?? 0);
    if (next <= offset) throw new Error("The server's log listing did not advance to the next page");
    offset = next;
  }
}
