/**
 * The explorer's MCP client over the Streamable HTTP transport: `initialize`, then `tools/call`
 * on `POST /mcp` with the session header, which the extension host can send where a webview could
 * not (EXPLORER_PLAN.md decision 2 and §3). A tool's result is the parsed JSON of its text
 * content; an error result is a rejected promise. Requests run in parallel: the transport serves
 * each on its own thread.
 *
 * One session per client, opened at the first call and deleted on dispose, so that a server that
 * exits when idle sees the viewer as a client while an editor is open. A session the server no
 * longer knows (it was restarted, or let the session expire after an hour idle) is replaced by a
 * new one, once per call, and the call sent again; a server that cannot be reached at all fails
 * the call with a message that says so, and the caller (which started the server) decides what
 * to do next. Needs Node's http only, so it is tested against a server in the test; nothing here
 * touches the VS Code API.
 */
import * as http from "http";
import {
  JsonRpcMessage,
  SESSION_HEADER,
  ToolOutcome,
  initializeRequest,
  initializedNotification,
  parseToolResponse,
  sessionLost,
  toolCallRequest,
} from "./explorer/mcpProtocol";

/** A tool call on a large log can take a while; the bridge allows ten minutes too. */
const REQUEST_TIMEOUT_MS = 600_000;

/** A failed call: the reason, and the tool's own result when it gave one. */
export class ToolError extends Error {
  constructor(
    message: string,
    readonly tool: string,
    readonly result?: Record<string, unknown>
  ) {
    super(message);
    this.name = "ToolError";
  }
}

/** One HTTP exchange, as the client sees it. */
interface Exchange {
  status: number;
  body: string;
  sessionId?: string;
}

export class McpClient {
  private nextId = 1;
  private sessionId?: string;
  /** The handshake in progress, shared by calls that arrive while it runs. */
  private initializing?: Promise<void>;
  private disposed = false;

  /**
   * @param endpoint the server's MCP endpoint, such as `http://127.0.0.1:41234/mcp`
   * @param clientVersion the extension's version, sent as the client's
   */
  constructor(
    readonly endpoint: string,
    private readonly clientVersion: string
  ) {}

  /** Whether a session is open. */
  get connected(): boolean {
    return this.sessionId !== undefined;
  }

  /**
   * Calls a tool and returns its result. Rejects with a ToolError when the tool reports an error
   * or the server cannot be reached.
   */
  async callTool(name: string, args: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
    if (this.disposed) throw new ToolError("The client was disposed", name);
    await this.ensureSession();
    let exchange = await this.post(toolCallRequest(this.nextId++, name, args), this.sessionId);
    if (sessionLost(exchange.status, this.sessionId !== undefined)) {
      // The server forgot the session: open another and try once more
      this.sessionId = undefined;
      await this.ensureSession();
      exchange = await this.post(toolCallRequest(this.nextId++, name, args), this.sessionId);
    }
    const outcome = this.outcomeOf(exchange, name);
    if (!outcome.ok) throw new ToolError(outcome.error, name, outcome.result);
    return outcome.result;
  }

  private outcomeOf(exchange: Exchange, tool: string): ToolOutcome {
    if (exchange.status !== 200) {
      return { ok: false, error: `The server answered HTTP ${exchange.status}: ${exchange.body.trim()}` };
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(exchange.body);
    } catch {
      return { ok: false, error: `The server's answer to ${tool} was not JSON` };
    }
    return parseToolResponse(parsed);
  }

  /** Opens a session when there is none; concurrent callers share one handshake. */
  private ensureSession(): Promise<void> {
    if (this.sessionId !== undefined) return Promise.resolve();
    if (!this.initializing) {
      this.initializing = this.initialize().finally(() => {
        this.initializing = undefined;
      });
    }
    return this.initializing;
  }

  private async initialize(): Promise<void> {
    const exchange = await this.post(initializeRequest(this.nextId++, this.clientVersion));
    if (exchange.status !== 200 || !exchange.sessionId) {
      throw new ToolError(
        `The server did not open a session (HTTP ${exchange.status}: ${exchange.body.trim()})`,
        "initialize"
      );
    }
    this.sessionId = exchange.sessionId;
    await this.post(initializedNotification(), this.sessionId);
  }

  /** Ends the session, so the server may count the viewer gone. */
  async dispose(): Promise<void> {
    this.disposed = true;
    const session = this.sessionId;
    this.sessionId = undefined;
    if (session === undefined) return;
    await new Promise<void>((resolve) => {
      const request = http.request(
        this.endpoint,
        { method: "DELETE", headers: { [SESSION_HEADER]: session }, timeout: 5_000 },
        (response) => {
          response.resume();
          response.on("end", resolve);
          response.on("error", () => resolve());
        }
      );
      request.on("timeout", () => request.destroy());
      request.on("error", () => resolve());
      request.end();
    });
  }

  /** Posts one message; rejects only when the server cannot be reached. */
  private post(message: JsonRpcMessage, sessionId?: string): Promise<Exchange> {
    const body = JSON.stringify(message);
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      "Content-Length": String(Buffer.byteLength(body)),
    };
    if (sessionId !== undefined) headers[SESSION_HEADER] = sessionId;
    return new Promise((resolve, reject) => {
      const request = http.request(
        this.endpoint,
        { method: "POST", headers, timeout: REQUEST_TIMEOUT_MS },
        (response) => {
          let text = "";
          response.setEncoding("utf8");
          response.on("data", (chunk: string) => {
            text += chunk;
          });
          response.on("end", () => {
            const header = response.headers[SESSION_HEADER.toLowerCase()];
            resolve({
              status: response.statusCode ?? 0,
              body: text,
              sessionId: typeof header === "string" ? header : undefined,
            });
          });
          response.on("error", (error) => reject(this.unreachable(message.method, error)));
        }
      );
      request.on("timeout", () => request.destroy(new Error("the request timed out")));
      request.on("error", (error) => reject(this.unreachable(message.method, error)));
      request.end(body);
    });
  }

  private unreachable(method: string, error: Error): ToolError {
    return new ToolError(`The server at ${this.endpoint} could not be reached: ${error.message}`, method);
  }
}
