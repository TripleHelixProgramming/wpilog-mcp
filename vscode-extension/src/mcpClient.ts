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
import { leaseChanged, SessionRegistration } from "./directoryLease";
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
  private updating: Promise<void> = Promise.resolve();
  private registeredSession?: string;
  private lastRegistration?: SessionRegistration;
  private heartbeat?: ReturnType<typeof setInterval>;

  /**
   * @param endpoint the server's MCP endpoint, such as `http://127.0.0.1:41234/mcp`
   * @param clientVersion the extension's version, sent as the client's
   */
  constructor(
    readonly endpoint: string,
    private readonly clientVersion: string,
    private readonly registration?: () => Promise<SessionRegistration>
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
    await this.updating;
    await this.ensureSession();
    const calledSession = this.sessionId;
    let exchange = await this.post(toolCallRequest(this.nextId++, name, args), this.sessionId);
    if (sessionLost(exchange.status, this.sessionId !== undefined)) {
      // The server forgot the session: open another and try once more
      if (this.sessionId === calledSession) this.sessionId = undefined;
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
    if (this.disposed) return Promise.reject(new ToolError("The client was disposed", "initialize"));
    if (this.initializing) return this.initializing;
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
    const session = exchange.sessionId;
    try {
      await this.post(initializedNotification(), session);
      await this.register(session);
      if (this.disposed) throw new ToolError("The client was disposed", "initialize");
      this.sessionId = session;
      // A window remains a lease holder even while only its agents are making tool calls.
      if (this.registration && !this.heartbeat) {
        this.heartbeat = setInterval(() => void this.keepAlive().catch(() => {}), 30_000);
        this.heartbeat.unref();
      }
    } catch (error) {
      await this.deleteSession(session);
      throw error;
    }
  }

  /** Serialize lease replacement so a slow old secret/directory read cannot win over new settings. */
  refreshRegistration(): Promise<void> {
    const update = this.updating.catch(() => {}).then(async () => {
      if (this.disposed) throw new ToolError("The client was disposed", "registration");
      await this.ensureSession();
      try {
        const ping = await this.post({ jsonrpc: "2.0", id: this.nextId++, method: "ping" }, this.sessionId);
        if (ping.status !== 200) throw new RegistrationError(`Session ping refused (HTTP ${ping.status})`, ping.status);
        await this.register(this.sessionId!);
      } catch (error) {
        if (!(error instanceof RegistrationError) || error.status !== 404) throw error;
        this.sessionId = undefined;
        await this.ensureSession();
      }
    });
    this.updating = update;
    return update;
  }

  /** A ping preserves the session's lifetime without changing which key or directory lease is newest. */
  async keepAlive(): Promise<void> {
    await this.updating.catch(() => {});
    await this.ensureSession();
    const session = this.sessionId;
    const exchange = await this.post({ jsonrpc: "2.0", id: this.nextId++, method: "ping" }, session);
    if (exchange.status === 404) {
      if (this.sessionId === session) this.sessionId = undefined;
      await this.ensureSession();
    } else if (exchange.status !== 200) {
      throw new ToolError(`The session ping failed (HTTP ${exchange.status})`, "ping");
    }
  }

  private async register(session: string): Promise<void> {
    if (!this.registration) return;
    const registration = await this.registration();
    if (!leaseChanged(this.lastRegistration, registration, this.registeredSession !== session)) return;
    for (const [route, payload] of [["/directories", registration.directories], ["/tba-key", { key: registration.key }]] as const) {
      const reply = await this.exchange(new URL(route, this.endpoint).toString(), "POST", payload, session);
      if (reply.status !== 200) {
        // Directory errors explain the named path. Never echo a key response or request body.
        let reason = `Registration refused (HTTP ${reply.status})`;
        if (route === "/directories") {
          try {
            const body = JSON.parse(reply.body) as { error?: string; hint?: string };
            reason = [body.error, body.hint].filter(Boolean).join(" ") || reason;
          } catch { /* the HTTP status remains useful */ }
        }
        throw new RegistrationError(reason, reply.status);
      }
    }
    this.registeredSession = session;
    this.lastRegistration = JSON.parse(JSON.stringify(registration)) as SessionRegistration;
  }

  /** Ends the session even when disposal races its handshake, releasing directories and the key. */
  async dispose(): Promise<void> {
    this.disposed = true;
    clearInterval(this.heartbeat);
    await this.initializing?.catch(() => {});
    await this.updating.catch(() => {});
    const session = this.sessionId;
    this.sessionId = undefined;
    if (session !== undefined) await this.deleteSession(session);
  }

  private async deleteSession(session: string): Promise<void> {
    await this.exchange(this.endpoint, "DELETE", undefined, session, 5_000).catch(() => {});
  }

  /** Posts one message; rejects only when the server cannot be reached. */
  private post(message: JsonRpcMessage, sessionId?: string): Promise<Exchange> {
    return this.exchange(this.endpoint, "POST", message, sessionId);
  }

  private exchange(endpoint: string, method: string, payload?: unknown, sessionId?: string,
    timeout = REQUEST_TIMEOUT_MS): Promise<Exchange> {
    const body = payload === undefined ? "" : JSON.stringify(payload);
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      "Content-Length": String(Buffer.byteLength(body)),
    };
    if (sessionId !== undefined) headers[SESSION_HEADER] = sessionId;
    return new Promise((resolve, reject) => {
      const request = http.request(
        endpoint,
        { method, headers, timeout },
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
          response.on("error", (error) => reject(this.unreachable(method, error)));
        }
      );
      request.on("timeout", () => request.destroy(new Error("the request timed out")));
      request.on("error", (error) => reject(this.unreachable(method, error)));
      request.end(body);
    });
  }

  private unreachable(method: string, error: Error): ToolError {
    return new ToolError(`The server at ${this.endpoint} could not be reached: ${error.message}`, method);
  }
}

class RegistrationError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}
