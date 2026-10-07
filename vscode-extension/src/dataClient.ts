/**
 * The data client: fetches an Arrow stream from a server's data endpoint and hands its bytes to
 * whoever asked, the webview above all, which opens them with its own reader. The extension
 * host parses nothing; it moves bytes (EXPLORER_PLAN.md §3, "The data client").
 *
 * It remembers the ETag and the bytes of the last responses, by request, and sends
 * `If-None-Match`, so a window the viewer returns to costs one round trip and no bytes: the
 * server answers 304 and the remembered bytes are returned. The memory is bounded, by entries
 * and by bytes, oldest out first. Needs Node's http only, so it is tested against a server in
 * the test.
 */
import * as http from "http";
import * as https from "https";
import { DataRequest, classifyStatus, dataQuery, dataUrl, errorMessage } from "./explorer/dataRequest";

/** A response: the bytes, and whether they came from the client's memory (a 304). */
export interface DataResponse {
  bytes: Uint8Array;
  fromCache: boolean;
  etag?: string;
}

/** A refusal over the server's size cap, with what the server said. */
export class TooLargeError extends Error {
  constructor(
    message: string,
    readonly rows: number | undefined,
    readonly bytes: number | undefined
  ) {
    super(message);
    this.name = "TooLargeError";
  }
}

/** A failed request: the server's message, or why it could not be reached. */
export class DataError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = "DataError";
  }
}

/** A tool call on a large log can take a while; so can a large stream. */
const REQUEST_TIMEOUT_MS = 600_000;

interface Remembered {
  etag: string;
  bytes: Uint8Array;
}

export class DataClient {
  private readonly remembered = new Map<string, Remembered>();
  private rememberedBytes = 0;

  /**
   * @param maxEntries how many responses to remember
   * @param maxBytes how many bytes of responses to remember in all
   */
  constructor(
    private readonly maxEntries = 32,
    private readonly maxBytes = 256 * 1024 * 1024
  ) {}

  /** Fetches a request's stream from the server whose MCP endpoint is `mcpUrl`. */
  async fetch(mcpUrl: string, request: DataRequest): Promise<DataResponse> {
    const key = `${mcpUrl} ${dataQuery(request)}`;
    const known = this.remembered.get(key);
    const headers: Record<string, string> = { Accept: "application/vnd.apache.arrow.stream, application/json" };
    if (known) headers["If-None-Match"] = known.etag;
    const url = dataUrl(mcpUrl, request);
    const response = await this.get(url, headers);
    const kind = classifyStatus(response.status);
    if (kind.kind === "unchanged") {
      if (!known) throw new DataError("The server answered 304 to a request with no ETag", 304);
      this.touch(key, known);
      return { bytes: known.bytes, fromCache: true, etag: known.etag };
    }
    if (kind.kind === "data") {
      const etag = response.headers["etag"];
      if (typeof etag === "string" && etag !== "") this.remember(key, { etag, bytes: response.body });
      return { bytes: response.body, fromCache: false, etag: typeof etag === "string" ? etag : undefined };
    }
    const text = Buffer.from(response.body).toString("utf8");
    if (kind.kind === "too_large") {
      let rows: number | undefined;
      let bytes: number | undefined;
      try {
        const parsed = JSON.parse(text) as { rows?: number; bytes?: number };
        rows = parsed.rows;
        bytes = parsed.bytes;
      } catch {
        // the message says enough
      }
      throw new TooLargeError(errorMessage(response.status, text), rows, bytes);
    }
    throw new DataError(errorMessage(response.status, text), response.status);
  }

  /** Forgets everything remembered: the server was restarted with another configuration. */
  clear(): void {
    this.remembered.clear();
    this.rememberedBytes = 0;
  }

  /** How many responses are remembered. */
  get size(): number {
    return this.remembered.size;
  }

  private touch(key: string, entry: Remembered) {
    // Re-insert, so the map's order is least recently used first
    this.remembered.delete(key);
    this.remembered.set(key, entry);
  }

  private remember(key: string, entry: Remembered) {
    const old = this.remembered.get(key);
    if (old) {
      this.remembered.delete(key);
      this.rememberedBytes -= old.bytes.byteLength;
    }
    if (entry.bytes.byteLength > this.maxBytes) return;
    this.remembered.set(key, entry);
    this.rememberedBytes += entry.bytes.byteLength;
    while (this.remembered.size > this.maxEntries || this.rememberedBytes > this.maxBytes) {
      const oldest = this.remembered.keys().next().value as string;
      const dropped = this.remembered.get(oldest)!;
      this.remembered.delete(oldest);
      this.rememberedBytes -= dropped.bytes.byteLength;
    }
  }

  private get(url: string, headers: Record<string, string>): Promise<{ status: number; headers: http.IncomingHttpHeaders; body: Uint8Array }> {
    return new Promise((resolve, reject) => {
      const request = (new URL(url).protocol === "https:" ? https : http).get(url, { headers, timeout: REQUEST_TIMEOUT_MS }, (response) => {
        const chunks: Buffer[] = [];
        response.on("data", (chunk: Buffer) => chunks.push(chunk));
        response.on("end", () => {
          const body = Buffer.concat(chunks);
          resolve({
            status: response.statusCode ?? 0,
            headers: response.headers,
            // A view of the concatenated bytes, without the pool's slack
            body: new Uint8Array(body.buffer, body.byteOffset, body.byteLength),
          });
        });
        response.on("error", (error) => reject(new DataError(`The stream from ${url} failed: ${error.message}`)));
      });
      request.on("timeout", () => request.destroy(new Error("the request timed out")));
      request.on("error", (error) => reject(new DataError(`The server could not be reached: ${error.message}`)));
    });
  }
}
