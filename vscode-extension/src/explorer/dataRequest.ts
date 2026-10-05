/**
 * Requests to the server's data endpoint (`GET /data/entries`, EXPLORER_PLAN.md §6) as pure
 * functions: the URL from a server's MCP endpoint and a request, the canonical key the client's
 * cache uses, and what a response's status means. The client (`dataClient.ts`) moves the bytes.
 */

/** What the viewer asks for: one or more entries over a window, exact or bucketed. */
export interface DataRequest {
  path: string;
  names: string[];
  startTime?: number;
  endTime?: number;
  maxPoints?: number;
  format?: "arrow" | "csv";
}

/** The data endpoint of a server whose MCP endpoint is `mcpUrl` (`http://127.0.0.1:41234/mcp`). */
export function dataEndpointOf(mcpUrl: string): string {
  const url = new URL(mcpUrl);
  url.pathname = "/data/entries";
  url.search = "";
  url.hash = "";
  return url.toString();
}

/**
 * The query, with its parameters in one order and numbers written one way, so that two
 * requests for the same thing are one key and one ETag on the server too.
 */
export function dataQuery(request: DataRequest): string {
  const params = new URLSearchParams();
  params.set("path", request.path);
  params.set("names", request.names.join(","));
  if (request.startTime !== undefined) params.set("start_time", String(request.startTime));
  if (request.endTime !== undefined) params.set("end_time", String(request.endTime));
  if (request.maxPoints !== undefined) params.set("max_points", String(Math.max(1, Math.floor(request.maxPoints))));
  if (request.format && request.format !== "arrow") params.set("format", request.format);
  return params.toString();
}

/** The full URL of a request on a server. */
export function dataUrl(mcpUrl: string, request: DataRequest): string {
  return `${dataEndpointOf(mcpUrl)}?${dataQuery(request)}`;
}

/** What a response's status says. */
export type ResponseKind =
  | { kind: "data" }
  | { kind: "unchanged" }
  /** Over the server's size cap: the body says how many rows and bytes, and how to narrow */
  | { kind: "too_large" }
  | { kind: "error" };

export function classifyStatus(status: number): ResponseKind {
  if (status === 200) return { kind: "data" };
  if (status === 304) return { kind: "unchanged" };
  if (status === 413) return { kind: "too_large" };
  return { kind: "error" };
}

/** The message of a JSON error body, with its hint, or the status alone when the body is not one. */
export function errorMessage(status: number, body: string): string {
  try {
    const parsed = JSON.parse(body) as { error?: unknown; hint?: unknown };
    if (typeof parsed.error === "string") {
      return typeof parsed.hint === "string" ? `${parsed.error} (${parsed.hint})` : parsed.error;
    }
  } catch {
    // not JSON
  }
  return `The server answered HTTP ${status}${body.trim() ? `: ${body.trim().slice(0, 200)}` : ""}`;
}
